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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bangla.translator.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller responsible for rendering compact, message-attached translation insets
 * directly beneath WhatsApp message bubbles using TYPE_ACCESSIBILITY_OVERLAY.
 * Designed to look visually integrated into WhatsApp dark mode messages (IMAGE 2).
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val TAG = "OverlayController"
        private const val HORIZONTAL_MARGIN_DP = 6
        private const val STATUS_BAR_MARGIN_DP = 28
        private const val NAV_BAR_MARGIN_DP = 48
        private const val ATTACHMENT_GAP_DP = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    data class ActiveOverlay(
        val view: View,
        val displayKey: String,
        val sessionGeneration: Long,
        var currentBounds: Rect,
        var overlayScreenRect: Rect = Rect()
    )

    // Map of displayKey -> ActiveOverlay (thread-safe reference map)
    private val activeOverlays = ConcurrentHashMap<String, ActiveOverlay>()

    private val density = context.resources.displayMetrics.density
    private val marginPx = (HORIZONTAL_MARGIN_DP * density).toInt()
    private val gapPx = (ATTACHMENT_GAP_DP * density).toInt()
    private val statusBarInsetPx = (STATUS_BAR_MARGIN_DP * density).toInt()
    private val navBarInsetPx = (NAV_BAR_MARGIN_DP * density).toInt()

    /**
     * Displays or updates a translation overlay on the main thread, visually attached
     * directly beneath the target WhatsApp message bubble.
     */
    fun showOverlay(
        displayKey: String,
        translatedText: String,
        targetBounds: Rect,
        sessionGeneration: Long,
        screenBounds: Rect,
        inputBarTop: Int? = null
    ) {
        runOnMainThread {
            val existing = activeOverlays[displayKey]
            if (existing != null) {
                if (existing.sessionGeneration != sessionGeneration) {
                    removeOverlay(displayKey)
                } else {
                    updateOverlayView(existing, translatedText, targetBounds, screenBounds, inputBarTop)
                    return@runOnMainThread
                }
            }

            // Inflate new sleek inset overlay view
            val inflater = LayoutInflater.from(context)
            val overlayView = inflater.inflate(R.layout.layout_translation_overlay, null)
            val rootLayout = overlayView.findViewById<LinearLayout>(R.id.llOverlayRoot)
            val tvLabel = overlayView.findViewById<TextView>(R.id.tvLanguageLabel)
            val tvTranslated = overlayView.findViewById<TextView>(R.id.tvTranslatedText)

            tvTranslated.text = translatedText

            val screenW = screenBounds.width()
            val screenH = screenBounds.height()
            val isOutgoing = targetBounds.left > screenW * 0.30f

            // Apply contextual styling matching WhatsApp message bubble type (IMAGE 2)
            if (isOutgoing) {
                rootLayout.setBackgroundResource(R.drawable.bg_overlay_outgoing)
                tvLabel.setTextColor(ContextCompat.getColor(context, R.color.overlay_outgoing_label))
            } else {
                rootLayout.setBackgroundResource(R.drawable.bg_overlay_incoming)
                tvLabel.setTextColor(ContextCompat.getColor(context, R.color.overlay_incoming_label))
            }

            // Translation width follows the message bubble width
            val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast((70 * density).toInt())
            val bubbleWidth = targetBounds.width().coerceIn((55 * density).toInt(), maxAllowedWidth)

            overlayView.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val measuredWidth = overlayView.measuredWidth.coerceIn((50 * density).toInt(), maxAllowedWidth)
            val measuredHeight = overlayView.measuredHeight.coerceAtLeast((20 * density).toInt())

            // Align outgoing translations with right edge; incoming with left edge
            var posX = if (isOutgoing) {
                targetBounds.right - measuredWidth
            } else {
                targetBounds.left
            }

            if (posX + measuredWidth > screenW - marginPx) {
                posX = screenW - measuredWidth - marginPx
            }
            if (posX < marginPx) {
                posX = marginPx
            }

            // Attached directly beneath the WhatsApp bubble with 2dp gap
            val posY = targetBounds.bottom + gapPx

            // Bottom boundary to ensure it never covers the composer or keyboard
            val bottomLimit = if (inputBarTop != null && inputBarTop > statusBarInsetPx + (100 * density).toInt()) {
                inputBarTop - (4 * density).toInt()
            } else {
                screenH - (navBarInsetPx + (56 * density).toInt())
            }

            if (targetBounds.top >= bottomLimit || posY + measuredHeight > bottomLimit) {
                return@runOnMainThread
            }

            val layoutParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
                width = measuredWidth
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }

            try {
                windowManager.addView(overlayView, layoutParams)
                val placedRect = Rect(posX, posY, posX + measuredWidth, posY + measuredHeight)
                activeOverlays[displayKey] = ActiveOverlay(
                    view = overlayView,
                    displayKey = displayKey,
                    sessionGeneration = sessionGeneration,
                    currentBounds = targetBounds,
                    overlayScreenRect = placedRect
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach translation overlay to WindowManager", e)
            }
        }
    }

    private fun updateOverlayView(
        active: ActiveOverlay,
        translatedText: String,
        targetBounds: Rect,
        screenBounds: Rect,
        inputBarTop: Int? = null
    ) {
        val tv = active.view.findViewById<TextView>(R.id.tvTranslatedText)
        if (tv.text != translatedText) {
            tv.text = translatedText
        }

        val lp = active.view.layoutParams as? WindowManager.LayoutParams ?: return
        val screenW = screenBounds.width()
        val screenH = screenBounds.height()
        val isOutgoing = targetBounds.left > screenW * 0.30f

        val rootLayout = active.view.findViewById<LinearLayout>(R.id.llOverlayRoot)
        val tvLabel = active.view.findViewById<TextView>(R.id.tvLanguageLabel)

        if (isOutgoing) {
            rootLayout?.setBackgroundResource(R.drawable.bg_overlay_outgoing)
            tvLabel?.setTextColor(ContextCompat.getColor(context, R.color.overlay_outgoing_label))
        } else {
            rootLayout?.setBackgroundResource(R.drawable.bg_overlay_incoming)
            tvLabel?.setTextColor(ContextCompat.getColor(context, R.color.overlay_incoming_label))
        }

        val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast((70 * density).toInt())
        val bubbleWidth = targetBounds.width().coerceIn((55 * density).toInt(), maxAllowedWidth)

        active.view.measure(
            View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val measuredWidth = active.view.measuredWidth.coerceIn((50 * density).toInt(), maxAllowedWidth)
        val measuredHeight = active.view.measuredHeight.coerceAtLeast((20 * density).toInt())

        var posX = if (isOutgoing) {
            targetBounds.right - measuredWidth
        } else {
            targetBounds.left
        }

        if (posX + measuredWidth > screenW - marginPx) {
            posX = screenW - measuredWidth - marginPx
        }
        if (posX < marginPx) {
            posX = marginPx
        }

        val posY = targetBounds.bottom + gapPx

        val bottomLimit = if (inputBarTop != null && inputBarTop > statusBarInsetPx + (100 * density).toInt()) {
            inputBarTop - (4 * density).toInt()
        } else {
            screenH - (navBarInsetPx + (56 * density).toInt())
        }

        if (targetBounds.top >= bottomLimit || posY + measuredHeight > bottomLimit) {
            return
        }

        if (lp.x != posX || lp.y != posY || lp.width != measuredWidth) {
            lp.x = posX
            lp.y = posY
            lp.width = measuredWidth
            try {
                windowManager.updateViewLayout(active.view, lp)
                active.currentBounds = targetBounds
                active.overlayScreenRect = Rect(posX, posY, posX + measuredWidth, posY + measuredHeight)
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
