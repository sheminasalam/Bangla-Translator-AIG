package com.bangla.translator.translation

import android.content.Context
import android.util.Log
import com.bangla.translator.data.ModelDownloadState
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-quality hybrid translation engine:
 * 1. Checks in-memory cache.
 * 2. Uses Google High-Quality Translate engine for conversational Bengali when online.
 * 3. Falls back seamlessly to Google ML Kit On-Device when offline.
 * 4. Applies Bengali chat colloquial/idiom post-processing to correct pet names ("সোনা" -> babe/sweetheart),
 *    idioms ("দাঁত মাজতে" -> "brush teeth", "প্যারা নাই" -> "no worries"), etc.
 */
object TranslationEngine {

    private const val TAG = "TranslationEngine"
    private val networkExecutor = Executors.newFixedThreadPool(3)

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.BENGALI)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    // Shared single translator instance
    private var sharedTranslator: Translator? = null

    // Cache instance
    val cache = TranslationCache(maxEntries = 500)

    private val _modelState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val modelState: StateFlow<ModelDownloadState> = _modelState.asStateFlow()

    private val isPreparingModel = AtomicBoolean(false)
    private var prepareTask: Task<Void>? = null

    /**
     * Initializes the engine and checks if Bengali-English models are already downloaded.
     */
    fun checkModelAvailability() {
        val modelManager = RemoteModelManager.getInstance()
        val bengaliModel = TranslateRemoteModel.Builder(TranslateLanguage.BENGALI).build()

        modelManager.isModelDownloaded(bengaliModel)
            .addOnSuccessListener { isDownloaded ->
                if (isDownloaded) {
                    _modelState.value = ModelDownloadState.Ready
                    getOrCreateTranslator()
                } else {
                    _modelState.value = ModelDownloadState.NotDownloaded
                }
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Error checking model download status", error)
                _modelState.value = ModelDownloadState.Error(error.localizedMessage ?: "Unknown model error")
            }
    }

    /**
     * Obtains or initializes the shared ML Kit Translator.
     */
    @Synchronized
    private fun getOrCreateTranslator(): Translator {
        val existing = sharedTranslator
        if (existing != null) return existing

        val translator = Translation.getClient(options)
        sharedTranslator = translator
        return translator
    }

    /**
     * Requests model download and preparation through a shared task.
     * Guaranteed not to trigger duplicate downloads or call downloadModelIfNeeded per message.
     */
    @Synchronized
    fun prepareModelIfNeeded(
        conditions: DownloadConditions = DownloadConditions.Builder().build(),
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ): Task<Void> {
        val existingTask = prepareTask
        if (existingTask != null && !existingTask.isComplete) {
            return existingTask
        }

        if (_modelState.value is ModelDownloadState.Ready && sharedTranslator != null) {
            onSuccess?.invoke()
            return Tasks.forResult(null)
        }

        _modelState.value = ModelDownloadState.Downloading
        val translator = getOrCreateTranslator()

        val downloadTask = translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                Log.d(TAG, "Bengali-English model prepared successfully.")
                _modelState.value = ModelDownloadState.Ready
                isPreparingModel.set(false)
                onSuccess?.invoke()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to download/prepare translation model", exception)
                _modelState.value = ModelDownloadState.Error(exception.localizedMessage ?: "Download failed")
                isPreparingModel.set(false)
                onFailure?.invoke(exception)
            }

        prepareTask = downloadTask
        return downloadTask
    }

    /**
     * Translates Bengali text to English.
     * First queries the in-memory TranslationCache. If missing, requests ML Kit.
     */
    /**
     * Translates Bengali text to English.
     * 1. Checks in-memory cache.
     * 2. Attempts high-accuracy Google conversational translation (online).
     * 3. Seamlessly falls back to on-device ML Kit if offline.
     * 4. Post-processes colloquial chat words and idioms.
     */
    fun translate(
        text: String,
        onSuccess: (translated: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val normalized = cache.normalize(text)
        if (normalized.isBlank()) {
            onSuccess("")
            return
        }

        // 1. Check cache first
        val cachedTranslation = cache.get(normalized)
        if (cachedTranslation != null) {
            onSuccess(cachedTranslation)
            return
        }

        // 2. Attempt high-accuracy online translation in background
        networkExecutor.execute {
            val onlineResult = translateOnline(normalized)
            if (onlineResult != null && onlineResult.isNotBlank()) {
                val enhanced = postProcessBengaliChat(normalized, onlineResult)
                cache.put(normalized, enhanced)
                onSuccess(enhanced)
                return@execute
            }

            // 3. Fallback to on-device ML Kit
            if (_modelState.value !is ModelDownloadState.Ready) {
                // If model not ready and offline, trigger failure
                onFailure(IllegalStateException("Translation model is not ready. Current state: ${_modelState.value}"))
                return@execute
            }

            val translator = getOrCreateTranslator()
            translator.translate(normalized)
                .addOnSuccessListener { result ->
                    val enhanced = postProcessBengaliChat(normalized, result)
                    cache.put(normalized, enhanced)
                    onSuccess(enhanced)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Translation error for text: $normalized", exception)
                    onFailure(exception)
                }
        }
    }

    /**
     * Fast, lightweight Google online translation API call for natural conversational Bengali.
     */
    private fun translateOnline(text: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=bn&tl=en&dt=t&q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                parseGtxResponse(response)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGtxResponse(json: String): String? {
        return try {
            val array = JSONArray(json)
            val sentences = array.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                sb.append(sentence.getString(0))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Post-processes common Bengali WhatsApp idioms and terms of endearment that
     * machine translation engines often mistranslate literally.
     */
    private fun postProcessBengaliChat(originalBengali: String, rawEnglish: String): String {
        var text = rawEnglish

        // 1. "সোনা" used as an address / vocative (mistranslated as "gold")
        if (originalBengali.contains("সোনা")) {
            text = text.replace(Regex("(?i)\\b(doing|are you|hello|hi|hey|my|dear|good morning|good night)\\s+gold\\b"), "$1 sweetheart")
            text = text.replace(Regex("(?i)\\bgold\\b([,!?\\s]*$)"), "sweetheart$1")
            text = text.replace(Regex("(?i)^gold[,\\s]+"), "Sweetheart, ")
        }

        // 2. "বাবু" (baby/babe)
        if (originalBengali.contains("বাবু")) {
            text = text.replace(Regex("(?i)\\bbabu\\b"), "babe")
        }

        // 3. "মাজতে" / "দাঁত মাজা" (teeth brushing, mistranslated as "mazz")
        if (originalBengali.contains("মাজতে") || originalBengali.contains("মাজা")) {
            text = text.replace(Regex("(?i)\\bgoing to mazz\\b"), "going to brush my teeth")
            text = text.replace(Regex("(?i)\\bmazz\\b"), "brush teeth")
        }

        // 4. "প্যারা নাই" / "প্যারা" (no worries / tension)
        if (originalBengali.contains("প্যারা নাই") || originalBengali.contains("প্যারা নেই")) {
            text = text.replace(Regex("(?i)\\bno pair\\b"), "no worries")
            text = text.replace(Regex("(?i)\\bno tension\\b"), "no worries")
        }

        // 5. "কিরে" / "কি খবর"
        if (originalBengali.contains("কিরে")) {
            text = text.replace(Regex("(?i)\\bwhat ray\\b"), "hey")
        }

        // 6. "হারিয়ে যেয়ো না" (stay in touch / don't disappear)
        if (originalBengali.contains("হারিয়ে যেয়ো না") || originalBengali.contains("হারিয়ে যেও না")) {
            text = text.replace(Regex("(?i)\\bdo not be lost\\b"), "never get lost")
            text = text.replace(Regex("(?i)\\bdon't be lost\\b"), "never get lost")
        }

        return text.trim()
    }

    /**
     * Cleans up resources when no longer needed.
     */
    @Synchronized
    fun close() {
        try {
            sharedTranslator?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing translator", e)
        } finally {
            sharedTranslator = null
            prepareTask = null
            _modelState.value = ModelDownloadState.NotDownloaded
        }
    }
}
