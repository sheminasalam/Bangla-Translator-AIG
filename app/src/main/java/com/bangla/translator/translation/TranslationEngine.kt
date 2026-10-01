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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton translation engine wrapping Google ML Kit On-Device Translation.
 * Ensures a single shared Bengali-English Translator instance and a centralized
 * model download and preparation pipeline.
 */
object TranslationEngine {

    private const val TAG = "TranslationEngine"

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

        // 2. Validate model is ready
        if (_modelState.value !is ModelDownloadState.Ready) {
            onFailure(IllegalStateException("Translation model is not ready. Current state: ${_modelState.value}"))
            return
        }

        // 3. Perform ML Kit translation on shared client
        val translator = getOrCreateTranslator()
        translator.translate(normalized)
            .addOnSuccessListener { result ->
                cache.put(normalized, result)
                onSuccess(result)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Translation error for text: $normalized", exception)
                onFailure(exception)
            }
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
