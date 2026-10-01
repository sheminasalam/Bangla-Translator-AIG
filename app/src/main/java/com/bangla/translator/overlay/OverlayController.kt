package com.bangla.translator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.bangla.translator.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller responsible for managing floating Accessibility overlays above/below
 * WhatsApp messages using TYPE_ACCESSIBILITY_OVERLAY.
 * All mutations execute strictly on the main looper thread.
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "OverlayController"
        private const val OVERLAY_MARGIN_DP = 4
        private const val MIN_OVERLAY_WIDTH_DP = 140
        private const val MAX_OVERLAY_WIDTH_DP = 280
        private const val STATUS_BAR_MARGIN_DP = 28
        private const val NAV_BAR_MARGIN_DP = 48
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    data class ActiveOverlay(
        val view: View,
        val displayKey: String,
        val sessionGeneration: Long,
        var currentBounds: Rect
    )

    // Map of displayKey -> ActiveOverlay (thread-safe reference map)
    private val activeOverlays = ConcurrentHashMap<String, ActiveOverlay>()

    private val density = context.resources.displayMetrics.density
    private val marginPx = (OVERLAY_MARGIN_DP * density).toInt()
    private val minWidthPx = (MIN_OVERLAY_WIDTH_DP * density).toInt()
    private val maxWidthPx = (MAX_OVERLAY_WIDTH_DP * density).toInt()
    private val statusBarInsetPx = (STATUS_BAR_MARGIN_DP * density).toInt()
    private val navBarInsetPx = (NAV_BAR_MARGIN_DP * density).toInt()

    /**
     * Displays or updates a translation overlay on the main thread.
     */
    fun showOverlay(
        displayKey: String,
        translatedText: String,
        targetBounds: Rect,
        sessionGeneration: Long,
        screenBounds: Rect
    ) {
        runOnMainThread {
            // If already present for this display key, update content & position
            val existing = activeOverlays[displayKey]
            if (existing != null) {
                if (existing.sessionGeneration != sessionGeneration) {
                    removeOverlay(displayKey)
                } else {
                    updateOverlayView(existing, translatedText, targetBounds, screenBounds)
                    return@runOnMainThread
                }
            }

            // Inflate new overlay view
            val inflater = LayoutInflater.from(context)
            val overlayView = inflater.inflate(R.layout.layout_translation_overlay, null)
            val tvTranslated = overlayView.findViewById<TextView>(R.id.tvTranslatedText)
            tvTranslated.text = translatedText

            // Calculate width constraint based on target message bubble
            val desiredWidth = targetBounds.width().coerceIn(minWidthPx, maxWidthPx)
            overlayView.measure(
                View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val measuredWidth = overlayView.measuredWidth.coerceAtLeast(minWidthPx)
            val measuredHeight = overlayView.measuredHeight.coerceAtLeast((28 * density).toInt())

            // Calculate smart positioning with boundary clamping
            val (posX, posY) = calculateIntelligentPosition(
                targetBounds = targetBounds,
                overlayWidth = measuredWidth,
                overlayHeight = measuredHeight,
                screenBounds = screenBounds
            )

            val layoutParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }

            try {
                windowManager.addView(overlayView, layoutParams)
                activeOverlays[displayKey] = ActiveOverlay(
                    view = overlayView,
                    displayKey = displayKey,
                    sessionGeneration = sessionGeneration,
                    currentBounds = targetBounds
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach translation overlay to WindowManager", e)
            }
        }
    }

    /**
     * Determines optimal X/Y coordinates:
     * 1. Try below message bubble.
     * 2. If insufficient vertical room, place above message bubble.
     * 3. Clamp X and Y strictly within visible screen boundaries.
     */
    private fun calculateIntelligentPosition(
        targetBounds: Rect,
        overlayWidth: Int,
        overlayHeight: Int,
        screenBounds: Rect
    ): Pair<Int, Int> {
        val screenW = screenBounds.width()
        val screenH = screenBounds.height()

        // 1. Horizontal positioning: align with start of bubble, then clamp
        var posX = targetBounds.left
        if (posX + overlayWidth > screenW - marginPx) {
            posX = screenW - overlayWidth - marginPx
        }
        if (posX < marginPx) {
            posX = marginPx
        }

        // 2. Vertical positioning: try below bubble first
        val spaceBelow = screenH - navBarInsetPx - targetBounds.bottom
        val spaceAbove = targetBounds.top - statusBarInsetPx

        var posY: Int
        if (spaceBelow >= overlayHeight + marginPx) {
            posY = targetBounds.bottom + marginPx
        } else if (spaceAbove >= overlayHeight + marginPx) {
            posY = targetBounds.top - overlayHeight - marginPx
        } else {
            // If neither fits cleanly, pick side with more space and clamp
            posY = if (spaceBelow > spaceAbove) {
                targetBounds.bottom + marginPx
            } else {
                targetBounds.top - overlayHeight - marginPx
            }
        }

        // Strict boundary clamp
        val minY = statusBarInsetPx
        val maxY = screenH - navBarInsetPx - overlayHeight
        posY = posY.coerceIn(minY, maxY.coerceAtLeast(minY))

        return Pair(posX, posY)
    }

    private fun updateOverlayView(
        active: ActiveOverlay,
        translatedText: String,
        targetBounds: Rect,
        screenBounds: Rect
    ) {
        val tv = active.view.findViewById<TextView>(R.id.tvTranslatedText)
        if (tv.text != translatedText) {
            tv.text = translatedText
        }

        val lp = active.view.layoutParams as? WindowManager.LayoutParams ?: return
        val (posX, posY) = calculateIntelligentPosition(
            targetBounds = targetBounds,
            overlayWidth = active.view.width.coerceAtLeast(minWidthPx),
            overlayHeight = active.view.height.coerceAtLeast((28 * density).toInt()),
            screenBounds = screenBounds
        )

        if (lp.x != posX || lp.y != posY) {
            lp.x = posX
            lp.y = posY
            try {
                windowManager.updateViewLayout(active.view, lp)
                active.currentBounds = targetBounds
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update overlay view position", e)
            }
        }
    }

    /**
     * Removes a single overlay by displayKey.
     */
    fun removeOverlay(displayKey: String) {
        runOnMainThread {
            val removed = activeOverlays.remove(displayKey) ?: return@runOnMainThread
            try {
                windowManager.removeView(removed.view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view for key: $displayKey", e)
            }
        }
    }

    /**
     * Cleans up overlays that are no longer part of the visible keys in the current scan.
     */
    fun reconcileVisibleOverlays(currentlyVisibleKeys: Set<String>) {
        runOnMainThread {
            val iterator = activeOverlays.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in currentlyVisibleKeys) {
                    try {
                        windowManager.removeView(entry.value.view)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing scrolled-out overlay", e)
                    }
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Removes all overlays immediately (e.g. on chat switch, leaving WhatsApp, or disabling feature).
     */
    fun removeAllOverlays() {
        runOnMainThread {
            for ((key, overlay) in activeOverlays) {
                try {
                    windowManager.removeView(overlay.view)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay on clear all: $key", e)
                }
            }
            activeOverlays.clear()
        }
    }

    val activeCount: Int
        get() = activeOverlays.size

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
