package com.bangla.translator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bangla.translator.data.AppPreferences
import com.bangla.translator.data.ScannedMessage
import com.bangla.translator.overlay.OverlayController
import com.bangla.translator.scanner.WhatsAppMessageScanner
import com.bangla.translator.translation.TranslationEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Android Accessibility Service that inspects visible WhatsApp and WhatsApp Business
 * chat hierarchies, extracts Bengali messages, translates them locally on-device, and renders
 * overlays near the original messages.
 */
class BanglaAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "BanglaAccessService"
        private const val DEBOUNCE_DELAY_MS = 150L
        private const val WATCHDOG_INTERVAL_MS = 350L

        val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SYSTEM_OVERLAY_PACKAGES = setOf(
            "com.bangla.translator",
            "com.bangla.translator.debug",
            "com.android.systemui",
            "android"
        )

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    // Core generation tracking to prevent race conditions across chat switches
    private val sessionGeneration = AtomicLong(1L)

    private lateinit var appPreferences: AppPreferences
    private lateinit var overlayController: OverlayController
    private lateinit var messageScanner: WhatsAppMessageScanner
    private lateinit var windowManager: WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenBounds = Rect()

    // Currently visible keys mapped to message metadata for the active generation
    private val activeVisibleKeys = ConcurrentHashMap<String, ScannedMessage>()

    // In-flight tracker: (normalizedText, generation) -> in-flight
    private val inFlightSet = ConcurrentHashMap<Pair<String, Long>, Boolean>()

    private var lastObservedChatWindow: String? = null
    private var currentTypingBarTop: Int? = null
    private val isWatchdogActive = AtomicBoolean(false)
    private var nonForegroundCount = 0

    // Debounced scan task
    private val scanRunnable = Runnable {
        performHierarchyScan()
    }

    // Safety watchdog task to detect leaving WhatsApp even if foreign events are filtered
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isWhatsAppForeground()) {
                nonForegroundCount++
                if (nonForegroundCount >= 2) {
                    handleLeftWhatsApp()
                    nonForegroundCount = 0
                } else {
                    mainHandler.postDelayed(this, 300L)
                }
            } else {
                nonForegroundCount = 0
                if (overlayController.activeCount > 0 || inFlightSet.isNotEmpty()) {
                    mainHandler.postDelayed(this, 300L)
                } else {
                    isWatchdogActive.set(false)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "BanglaAccessibilityService connected.")
        isServiceRunning = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        appPreferences = AppPreferences(this)
        appPreferences.registerListener(this)

        messageScanner = WhatsAppMessageScanner(appPreferences.bengaliRatioThreshold)
        overlayController = OverlayController(this, windowManager)

        updateScreenBounds()

        // Configure accessibility dynamic properties
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 100
        serviceInfo = info

        TranslationEngine.checkModelAvailability()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // Ignore events from our own overlay windows, system UI, and keyboards
        if (pkg in SYSTEM_OVERLAY_PACKAGES || pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("ime")) {
            return
        }

        // If a foreign application or launcher window state changed, user left WhatsApp
        if (pkg !in SUPPORTED_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty()) {
                handleLeftWhatsApp()
            }
            return
        }

        if (!appPreferences.isOverlayEnabled) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scheduleDebouncedScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleDebouncedScan()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleDebouncedScan()
            }
        }
    }

    /**
     * Differentiates chat transitions from minor dialogs or keyboard events.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""

        // Ignore soft keyboard / input method window state changes
        if (className.contains("InputMethod") || className.contains("Keyguard")) {
            return
        }

        // WhatsApp conversation screen detection
        val isConversationActivity = className.contains("Conversation") ||
                className.contains("Chat") ||
                className.contains("HomeActivity")

        if (isConversationActivity && className != lastObservedChatWindow) {
            lastObservedChatWindow = className
            startNewSession()
        }

        scheduleDebouncedScan()
    }

    /**
     * Increments session generation and invalidates old overlays and in-flight tasks.
     */
    private fun startNewSession() {
        val newGen = sessionGeneration.incrementAndGet()
        Log.d(TAG, "Starting new conversation session generation: $newGen")

        overlayController.removeAllOverlays()
        activeVisibleKeys.clear()
        inFlightSet.clear()
    }

    /**
     * Debounces scans to prevent high-frequency hierarchy re-traversals.
     */
    private fun scheduleDebouncedScan() {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, DEBOUNCE_DELAY_MS)
        ensureWatchdogRunning()
    }

    /**
     * Traverses the WhatsApp hierarchy, identifies visible Bengali messages,
     * reconciles stale overlays, and triggers asynchronous on-device translations.
     */
    private fun performHierarchyScan() {
        if (!isWhatsAppForeground()) {
            handleLeftWhatsApp()
            return
        }

        if (!appPreferences.isOverlayEnabled) {
            overlayController.removeAllOverlays()
            return
        }

        val currentGen = sessionGeneration.get()
        val root = rootInActiveWindow ?: return

        val scanResult = try {
            messageScanner.scanVisibleMessages(root, screenBounds, currentGen)
        } finally {
            root.recycle()
        }

        val scannedMessages = scanResult.messages
        currentTypingBarTop = scanResult.inputBarTop

        val currentVisibleKeySet = HashSet<String>()
        var hasNewMessageArrived = false
        for (msg in scannedMessages) {
            if (!activeVisibleKeys.containsKey(msg.displayKey)) {
                hasNewMessageArrived = true
            }
            currentVisibleKeySet.add(msg.displayKey)
            activeVisibleKeys[msg.displayKey] = msg
        }

        // Auto-collapse any expanded translation when a new message arrives
        if (hasNewMessageArrived) {
            overlayController.collapseAll()
        }

        // Remove overlays for messages that have scrolled away
        overlayController.reconcileVisibleOverlays(currentVisibleKeySet)

        // Process each visible Bengali message
        for (msg in scannedMessages) {
            processMessageTranslation(msg, currentGen)
        }
    }

    /**
     * Dispatches translation request with strict generation and visibility checks.
     */
    private fun processMessageTranslation(msg: ScannedMessage, taskGeneration: Long) {
        // Check cache first for instant synchronous display
        val cached = TranslationEngine.cache.get(msg.normalizedText)
        if (cached != null) {
            validateAndDisplay(msg, cached, taskGeneration)
            return
        }

        // Avoid duplicate in-flight requests for identical text in the same generation
        val inFlightKey = Pair(msg.normalizedText, taskGeneration)
        if (inFlightSet.putIfAbsent(inFlightKey, true) != null) {
            return
        }

        TranslationEngine.translate(
            text = msg.normalizedText,
            onSuccess = { translatedText ->
                inFlightSet.remove(inFlightKey)
                mainHandler.post {
                    validateAndDisplay(msg, translatedText, taskGeneration)
                }
            },
            onFailure = { error ->
                inFlightSet.remove(inFlightKey)
                Log.w(TAG, "Translation failed for ${msg.normalizedText}: ${error.message}")
            }
        )
    }

    /**
     * Validates all 5 asynchronous safety rules before rendering an overlay:
     * 1. Translation belongs to current session/generation.
     * 2. Message is still considered visible in activeVisibleKeys.
     * 3. WhatsApp is still the active foreground application.
     * 4. Overlay feature is still enabled.
     * 5. Service is still connected.
     */
    private fun validateAndDisplay(
        msg: ScannedMessage,
        translatedText: String,
        taskGeneration: Long
    ) {
        val currentGen = sessionGeneration.get()
        if (taskGeneration != currentGen) {
            Log.d(TAG, "Rejected translation from stale generation $taskGeneration (current: $currentGen)")
            return
        }

        if (!isWhatsAppForeground()) {
            Log.d(TAG, "Rejected translation: WhatsApp is no longer in foreground.")
            handleLeftWhatsApp()
            return
        }

        if (!appPreferences.isOverlayEnabled) {
            return
        }

        if (!activeVisibleKeys.containsKey(msg.displayKey)) {
            Log.d(TAG, "Message ${msg.displayKey} is no longer in active visible set.")
            return
        }

        overlayController.showOverlay(
            displayKey = msg.displayKey,
            translatedText = translatedText,
            targetBounds = msg.bounds,
            sessionGeneration = taskGeneration,
            screenBounds = screenBounds,
            inputBarTop = currentTypingBarTop
        )
        ensureWatchdogRunning()
    }

    /**
     * Checks if WhatsApp or WhatsApp Business is the active foreground app.
     */
    private fun isWhatsAppForeground(): Boolean {
        val root = rootInActiveWindow ?: return true // Do NOT assume left on transient null root!
        val pkg = try {
            root.packageName?.toString() ?: ""
        } finally {
            root.recycle()
        }
        if (pkg.isEmpty() || pkg in SYSTEM_OVERLAY_PACKAGES || pkg.contains("inputmethod") || pkg.contains("keyboard")) {
            return true
        }
        return pkg in SUPPORTED_PACKAGES
    }

    /**
     * Invoked when the user leaves WhatsApp (Home button, App switcher, or foreign app).
     */
    private fun handleLeftWhatsApp() {
        Log.d(TAG, "Leaving WhatsApp detected. Invalidating overlays and in-flight tasks.")
        sessionGeneration.incrementAndGet()
        overlayController.removeAllOverlays()
        activeVisibleKeys.clear()
        inFlightSet.clear()
        lastObservedChatWindow = null
        currentTypingBarTop = null
    }

    private fun ensureWatchdogRunning() {
        if (isWatchdogActive.compareAndSet(false, true)) {
            mainHandler.postDelayed(watchdogRunnable, 200L)
        }
    }

    private fun updateScreenBounds() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        screenBounds.set(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenBounds()
        // Orientation / display size changed: recalculate overlays
        scheduleDebouncedScan()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == AppPreferences.KEY_OVERLAY_ENABLED) {
            if (!appPreferences.isOverlayEnabled) {
                overlayController.removeAllOverlays()
            } else {
                scheduleDebouncedScan()
            }
        } else if (key == AppPreferences.KEY_BENGALI_RATIO) {
            messageScanner = WhatsAppMessageScanner(appPreferences.bengaliRatioThreshold)
            scheduleDebouncedScan()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BanglaAccessibilityService interrupted.")
        overlayController.removeAllOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        appPreferences.unregisterListener(this)
        overlayController.removeAllOverlays()
        TranslationEngine.close()
        Log.i(TAG, "BanglaAccessibilityService destroyed.")
    }
}
