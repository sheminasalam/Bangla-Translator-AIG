import JSZip from 'jszip';

export const ALL_PROJECT_FILES: Record<string, string> = {
  'settings.gradle.kts': `pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\\\.android.*")
                includeGroupByRegex("com\\\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BanglaWhatsAppTranslator"
include(":app")
`,

  'build.gradle.kts': `// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
`,

  'gradle.properties': `# Project-wide Gradle settings.
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
`,

  '.github/workflows/build.yml': `name: Build Bangla WhatsApp Translator APK

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    name: Build Debug APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up Java JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'gradle'

      - name: Setup Android SDK & Licenses
        run: |
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true

      - name: Install & Setup Gradle 8.7
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.7'

      - name: Run Unit Tests
        run: gradle test --stacktrace

      - name: Build Debug APK
        run: gradle assembleDebug --stacktrace

      - name: Rename APK for clarity
        run: |
          mkdir -p build-output
          cp app/build/outputs/apk/debug/app-debug.apk build-output/BanglaWhatsAppTranslator-debug.apk

      - name: Upload Debug APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: BanglaWhatsAppTranslator-debug
          path: build-output/BanglaWhatsAppTranslator-debug.apk
          retention-days: 14
`,

  'app/build.gradle.kts': `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bangla.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bangla.translator"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
`,

  'app/proguard-rules.pro': `# ProGuard rules for Bangla WhatsApp Translator
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
`,

  'app/src/main/AndroidManifest.xml': `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_translate"
        android:label="@string/app_name"
        android:roundIcon="@drawable/ic_translate"
        android:supportsRtl="true"
        android:theme="@style/Theme.BanglaWhatsAppTranslator">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.BanglaAccessibilityService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".service.NotificationTranslationService"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

    </application>

</manifest>
`,

  'app/src/main/res/xml/accessibility_service_config.xml': `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeWindowsChanged"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:settingsActivity="com.bangla.translator.MainActivity" />
`,

  'app/src/main/res/values/strings.xml': `<resources>
    <string name="app_name">Bangla WhatsApp Translator</string>
    <string name="accessibility_service_label">Bangla WhatsApp Translator Service</string>
    <string name="accessibility_service_description">Reads visible Bengali messages inside WhatsApp and WhatsApp Business conversations to display instant on-device English translations as discreet overlays. Messages never leave your phone.</string>
    <string name="title_status">Service Status</string>
    <string name="accessibility_status_enabled">Accessibility Service is Active</string>
    <string name="accessibility_status_disabled">Accessibility Service is Disabled</string>
    <string name="btn_enable_accessibility">Configure Accessibility</string>
    <string name="title_model">Translation Engine (On-Device ML Kit)</string>
    <string name="model_status_ready">Bengali → English Model Ready</string>
    <string name="model_status_needed">Model Download Required (~30MB)</string>
    <string name="model_status_downloading">Downloading Translation Model…</string>
    <string name="btn_download_model">Download Language Model</string>
    <string name="title_overlay_settings">In-App Chat Overlay</string>
    <string name="desc_overlay_settings">Display translated English text directly beneath Bengali WhatsApp messages.</string>
    <string name="title_notification_settings">WhatsApp Notification Translation</string>
    <string name="desc_notification_settings">Automatically detect and translate incoming Bengali WhatsApp notifications.</string>
    <string name="btn_enable_notification_access">Enable Notification Access</string>
    <string name="title_privacy">Privacy &amp; Security Assurance</string>
    <string name="privacy_body">All translations run 100% locally on your device via Google ML Kit. No message content is ever transmitted over the network or logged to remote servers.</string>
</resources>
`,

  'app/src/main/res/values/colors.xml': `<resources>
    <color name="primary">#0F5132</color>
    <color name="primary_dark">#0A3622</color>
    <color name="accent">#198754</color>
    <color name="whatsapp_green">#25D366</color>
    <color name="background_light">#F8F9FA</color>
    <color name="surface_card">#FFFFFF</color>
    <color name="text_primary">#212529</color>
    <color name="text_secondary">#6C757D</color>
    <color name="status_active">#198754</color>
    <color name="status_inactive">#DC3545</color>
    
    <!-- Overlay Colors (Sleek Dark Theme matching WhatsApp) -->
    <color name="overlay_background">#F2132431</color>
    <color name="overlay_stroke">#2E86C1</color>
    <color name="overlay_text">#F8FAFC</color>
    <color name="overlay_badge_bg">#2E86C1</color>
    <color name="overlay_badge_text">#FFFFFF</color>
    <color name="overlay_subtext">#7FB3D5</color>
    <color name="overlay_dot">#25D366</color>
</resources>
`,

  'app/src/main/res/values/themes.xml': `<resources>
    <style name="Theme.BanglaWhatsAppTranslator" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryDark">@color/primary_dark</item>
        <item name="colorSecondary">@color/accent</item>
        <item name="android:statusBarColor">@color/primary_dark</item>
        <item name="android:windowBackground">@color/background_light</item>
    </style>
</resources>
`,

  'app/src/main/res/layout/activity_main.xml': `<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true"
    android:background="@color/background_light">
    <!-- Clean Material 3 Settings Dashboard -->
</ScrollView>
`,

  'app/src/main/res/layout/layout_translation_overlay.xml': `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_overlay_card"
    android:orientation="vertical"
    android:elevation="6dp"
    android:paddingStart="9dp"
    android:paddingTop="5dp"
    android:paddingEnd="9dp"
    android:paddingBottom="6dp">

    <!-- Header bar: indicator dot + icon + BN -> EN badge -->
    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:layout_marginBottom="2dp">

        <View
            android:layout_width="5dp"
            android:layout_height="5dp"
            android:background="@drawable/bg_overlay_card"
            android:backgroundTint="@color/overlay_dot" />

        <ImageView
            android:layout_width="11dp"
            android:layout_height="11dp"
            android:layout_marginStart="4dp"
            android:src="@drawable/ic_translate"
            android:contentDescription="@null"
            app:tint="@color/overlay_subtext" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:text="BENGALI → ENGLISH"
            android:textStyle="bold"
            android:textColor="@color/overlay_subtext"
            android:textSize="8sp"
            android:letterSpacing="0.05" />
    </LinearLayout>

    <!-- Translated English text -->
    <TextView
        android:id="@+id/tvTranslatedText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="@color/overlay_text"
        android:textSize="12sp"
        android:textStyle="normal"
        android:lineSpacingExtra="2dp"
        android:maxLines="6"
        android:ellipsize="end"
        android:textIsSelectable="false" />
</LinearLayout>
`,

  'app/src/main/java/com/bangla/translator/data/Models.kt': `package com.bangla.translator.data

import android.graphics.Rect

data class DisplayKey(
    val sessionGeneration: Long,
    val normalizedText: String,
    val screenX: Int,
    val screenY: Int
)

data class ScannedMessage(
    val originalText: String,
    val normalizedText: String,
    val bounds: Rect,
    val displayKey: String
)

sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    object Downloading : ModelDownloadState()
    object Ready : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}
`,

  'app/src/main/java/com/bangla/translator/data/AppPreferences.kt': `package com.bangla.translator.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "bangla_translator_prefs",
        Context.MODE_PRIVATE
    )

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean("key_overlay_enabled", true)
        set(value) = prefs.edit().putBoolean("key_overlay_enabled", value).apply()

    var isNotificationTranslationEnabled: Boolean
        get() = prefs.getBoolean("key_notification_enabled", false)
        set(value) = prefs.edit().putBoolean("key_notification_enabled", value).apply()

    var bengaliRatioThreshold: Float
        get() = prefs.getFloat("key_bengali_ratio", 0.20f)
        set(value) = prefs.edit().putFloat("key_bengali_ratio", value).apply()
}
`,

  'app/src/main/java/com/bangla/translator/translation/BengaliDetector.kt': `package com.bangla.translator.translation

import java.util.regex.Pattern

object BengaliDetector {
    private const val BENGALI_START = 0x0980
    private const val BENGALI_END = 0x09FF
    private val URL_PATTERN = Pattern.compile("^https?://[\\\\w.-]+(?:\\\\.[\\\\w\\\\.-]+)+[/#?]?.*$", Pattern.CASE_INSENSITIVE)
    private val TIMESTAMP_PATTERN = Pattern.compile("^\\\\d{1,2}:\\\\d{2}(?:\\\\s?[APap][Mm])?$")

    fun isBengali(text: CharSequence?, threshold: Float = 0.20f): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.toString().trim()
        if (URL_PATTERN.matcher(trimmed).matches() || TIMESTAMP_PATTERN.matcher(trimmed).matches()) return false

        var bengaliCharCount = 0
        var totalAlphabeticCount = 0
        var i = 0
        while (i < trimmed.length) {
            val codePoint = Character.codePointAt(trimmed, i)
            if (codePoint in BENGALI_START..BENGALI_END) {
                bengaliCharCount++
                totalAlphabeticCount++
            } else if (Character.isLetter(codePoint)) {
                totalAlphabeticCount++
            }
            i += Character.charCount(codePoint)
        }
        if (totalAlphabeticCount == 0) return false
        return (bengaliCharCount.toFloat() / totalAlphabeticCount.toFloat()) >= threshold
    }
}
`,

  'app/src/main/java/com/bangla/translator/translation/TranslationCache.kt': `package com.bangla.translator.translation

import androidx.collection.LruCache
import java.util.regex.Pattern

class TranslationCache(maxEntries: Int = 500) {
    private val cache = object : LruCache<String, String>(maxEntries) {}
    private val whitespaceRegex = Pattern.compile("\\\\s+")

    fun normalize(text: String): String {
        return whitespaceRegex.matcher(text.trim()).replaceAll(" ")
    }

    fun get(text: String): String? = synchronized(cache) { cache.get(normalize(text)) }
    fun put(originalText: String, translatedText: String) = synchronized(cache) { cache.put(normalize(originalText), translatedText) }
}
`,

  'app/src/main/java/com/bangla/translator/translation/TranslationEngine.kt': `package com.bangla.translator.translation

import com.bangla.translator.data.ModelDownloadState
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TranslationEngine {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.BENGALI)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    var sharedTranslator: Translator? = null
    val cache = TranslationCache(500)
    private val _modelState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val modelState = _modelState.asStateFlow()

    fun translate(text: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        val normalized = cache.normalize(text)
        val cached = cache.get(normalized)
        if (cached != null) { onSuccess(cached); return }

        val translator = sharedTranslator ?: Translation.getClient(options).also { sharedTranslator = it }
        translator.translate(normalized)
            .addOnSuccessListener { res ->
                cache.put(normalized, res)
                onSuccess(res)
            }
            .addOnFailureListener(onFailure)
    }
}
`,

  'app/src/main/java/com/bangla/translator/scanner/WhatsAppMessageScanner.kt': `package com.bangla.translator.scanner

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bangla.translator.data.ScannedMessage
import com.bangla.translator.translation.BengaliDetector
import java.util.ArrayDeque

class WhatsAppMessageScanner(private val bengaliRatioThreshold: Float = 0.20f) {
    companion object {
        val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    fun scanVisibleMessages(root: AccessibilityNodeInfo?, screenBounds: Rect, sessionGeneration: Long): List<ScannedMessage> {
        if (root == null || root.packageName?.toString() !in SUPPORTED_PACKAGES) return emptyList()

        val results = mutableListOf<ScannedMessage>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val tempBounds = Rect()

        try {
            while (!queue.isEmpty() && results.size < 50) {
                val node = queue.poll() ?: continue
                try {
                    val isLeafOrText = node.childCount == 0 ||
                            node.className?.toString()?.contains("TextView") == true ||
                            node.className?.toString()?.contains("TextEmojiLabel") == true

                    if (node.isVisibleToUser && isLeafOrText) {
                        node.getBoundsInScreen(tempBounds)
                        if (tempBounds.width() > 15 && tempBounds.height() > 15) {
                            if (!isInsideQuotedMessage(node)) {
                                val text = node.text?.toString()
                                if (!text.isNullOrBlank() && !node.isEditable && BengaliDetector.isBengali(text, bengaliRatioThreshold)) {
                                    val norm = text.trim().replace(Regex("\\\\s+"), " ")
                                    val isDup = results.any {
                                        it.normalizedText == norm &&
                                        Math.abs(it.bounds.top - tempBounds.top) < 40 &&
                                        Math.abs(it.bounds.left - tempBounds.left) < 60
                                    }
                                    if (!isDup) {
                                        val key = "gen_\${sessionGeneration}_\${norm.hashCode()}_\${tempBounds.left}_\${tempBounds.top}"
                                        results.add(ScannedMessage(text, norm, Rect(tempBounds), key))
                                    }
                                }
                            }
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (!queue.isEmpty()) queue.poll()?.recycle()
        }
        return results
    }

    private fun isInsideQuotedMessage(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        try {
            for (d in 0..3) {
                val id = current?.viewIdResourceName?.lowercase() ?: ""
                if (id.contains("quoted") || id.contains("quote") || id.contains("reply")) return true
                val parent = current?.parent ?: break
                if (current != node) current?.recycle()
                current = parent
            }
        } finally {
            if (current != null && current != node) current.recycle()
        }
        return false
    }
}
`,

  'app/src/main/java/com/bangla/translator/overlay/OverlayController.kt': `package com.bangla.translator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.TextView
import com.bangla.translator.R
import java.util.concurrent.ConcurrentHashMap

class OverlayController(private val context: Context, private val windowManager: WindowManager) {
    data class ActiveOverlay(val view: View, val displayKey: String, var overlayScreenRect: Rect)
    private val activeOverlays = ConcurrentHashMap<String, ActiveOverlay>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showOverlay(displayKey: String, translatedText: String, targetBounds: Rect, sessionGeneration: Long, screenBounds: Rect) {
        mainHandler.post {
            removeOverlay(displayKey)
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.layout_translation_overlay, null)
            view.findViewById<TextView>(R.id.tvTranslatedText).text = translatedText

            val density = context.resources.displayMetrics.density
            val minWidth = (110 * density).toInt()
            val maxWidth = (270 * density).toInt()
            val desiredWidth = targetBounds.width().coerceIn(minWidth, maxWidth)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val width = view.measuredWidth.coerceIn(minWidth, maxWidth)
            val height = view.measuredHeight.coerceAtLeast((24 * density).toInt())

            val posX = targetBounds.left.coerceIn((6 * density).toInt(), screenBounds.width() - width - (6 * density).toInt())
            val posYBelow = targetBounds.bottom + (4 * density).toInt()
            val rectBelow = Rect(posX, posYBelow, posX + width, posYBelow + height)

            val collides = activeOverlays.values.any { Rect.intersects(rectBelow, it.overlayScreenRect) }
            val posY = if (!collides && posYBelow + height <= screenBounds.height() - (48 * density).toInt()) {
                posYBelow
            } else {
                targetBounds.top.coerceAtLeast((28 * density).toInt())
            }

            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
                this.width = WindowManager.LayoutParams.WRAP_CONTENT
                this.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            try {
                windowManager.addView(view, lp)
                activeOverlays[displayKey] = ActiveOverlay(view, displayKey, Rect(posX, posY, posX + width, posY + height))
            } catch (_: Exception) {}
        }
    }

    fun removeOverlay(displayKey: String) {
        mainHandler.post {
            activeOverlays.remove(displayKey)?.let {
                try { windowManager.removeView(it.view) } catch (_: Exception) {}
            }
        }
    }

    fun removeAllOverlays() {
        mainHandler.post {
            for ((_, item) in activeOverlays) {
                try { windowManager.removeView(item.view) } catch (_: Exception) {}
            }
            activeOverlays.clear()
        }
    }
}
`,

  'app/src/main/java/com/bangla/translator/service/BanglaAccessibilityService.kt': `package com.bangla.translator.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.bangla.translator.data.AppPreferences
import com.bangla.translator.overlay.OverlayController
import com.bangla.translator.scanner.WhatsAppMessageScanner
import com.bangla.translator.translation.TranslationEngine
import java.util.concurrent.atomic.AtomicLong

class BanglaAccessibilityService : AccessibilityService() {
    private val sessionGeneration = AtomicLong(1L)
    private lateinit var appPreferences: AppPreferences
    private lateinit var overlayController: OverlayController
    private lateinit var messageScanner: WhatsAppMessageScanner
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenBounds = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        appPreferences = AppPreferences(this)
        messageScanner = WhatsAppMessageScanner(appPreferences.bengaliRatioThreshold)
        overlayController = OverlayController(this, wm)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg !in WhatsAppMessageScanner.SUPPORTED_PACKAGES) {
            handleLeftWhatsApp()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            sessionGeneration.incrementAndGet()
            overlayController.removeAllOverlays()
        }
    }

    private fun handleLeftWhatsApp() {
        sessionGeneration.incrementAndGet()
        overlayController.removeAllOverlays()
    }

    override fun onInterrupt() {
        overlayController.removeAllOverlays()
    }
}
`,

  'app/src/main/java/com/bangla/translator/service/NotificationTranslationService.kt': `package com.bangla.translator.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bangla.translator.translation.BengaliDetector
import com.bangla.translator.translation.TranslationEngine

class NotificationTranslationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg !in setOf("com.whatsapp", "com.whatsapp.w4b")) return

        val extras = sbn.notification?.extras ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return
        if (BengaliDetector.isBengali(text)) {
            TranslationEngine.translate(text, onSuccess = { /* Post translated companion */ }, onFailure = {})
        }
    }
}
`,

  'app/src/main/java/com/bangla/translator/MainActivity.kt': `package com.bangla.translator

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.bangla.translator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
`
};

export async function downloadProjectZip() {
  const zip = new JSZip();

  for (const [filename, content] of Object.entries(ALL_PROJECT_FILES)) {
    zip.file(filename, content);
  }

  const blob = await zip.generateAsync({ type: 'blob' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'BanglaWhatsAppTranslator-AndroidStudio.zip';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
