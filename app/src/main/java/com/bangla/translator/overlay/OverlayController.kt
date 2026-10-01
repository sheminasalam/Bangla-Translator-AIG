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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bangla.translator.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Controller responsible for managing interactive on-demand translation insets
 * attached directly to WhatsApp message bubbles using TYPE_ACCESSIBILITY_OVERLAY.
 *
 * States per message:
 * 1. Collapsed (Default): A small, sleek [EN] translate icon badge attached to the bubble.
 * 2. Expanded (On Click): Full attached inset showing "বাংলা → English" and translated text.
 * 3. Auto-Hide: Automatically collapses back to the small icon when a new message arrives.
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
        private const val AUTO_COLLAPSE_TIMEOUT_MS = 15000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    data class ActiveOverlay(
        val view: View,
        val displayKey: String,
        val sessionGeneration: Long,
        var currentBounds: Rect,
        var lastScreenBounds: Rect,
        var lastInputBarTop: Int?,
        var overlayScreenRect: Rect = Rect()
    )

    private val activeOverlays = ConcurrentHashMap<String, ActiveOverlay>()

    // Key of the currently expanded overlay (null if all are collapsed)
    private var expandedDisplayKey: String? = null

    private val density = context.resources.displayMetrics.density
    private val marginPx = (HORIZONTAL_MARGIN_DP * density).toInt()
    private val gapPx = (ATTACHMENT_GAP_DP * density).toInt()
    private val statusBarInsetPx = (STATUS_BAR_MARGIN_DP * density).toInt()
    private val navBarInsetPx = (NAV_BAR_MARGIN_DP * density).toInt()

    private val autoCollapseRunnable = Runnable {
        collapseAll()
    }

    /**
     * Displays or updates a translation overlay on the main thread.
     * By default, it appears as a small compact icon badge attached to the bubble.
     * Tapping the badge expands the full translation inset.
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

            val inflater = LayoutInflater.from(context)
            val overlayView = inflater.inflate(R.layout.layout_translation_overlay, null)

            val llCollapsed = overlayView.findViewById<LinearLayout>(R.id.llCollapsedBadge)
            val llExpanded = overlayView.findViewById<LinearLayout>(R.id.llExpandedCard)
            val ivBadge = overlayView.findViewById<ImageView>(R.id.ivBadgeIcon)
            val tvBadge = overlayView.findViewById<TextView>(R.id.tvBadgeText)
            val tvLabel = overlayView.findViewById<TextView>(R.id.tvLanguageLabel)
            val tvTranslated = overlayView.findViewById<TextView>(R.id.tvTranslatedText)

            tvTranslated.text = translatedText

            val screenW = screenBounds.width()
            val screenH = screenBounds.height()
            val isOutgoing = targetBounds.left > screenW * 0.30f

            // Contextual theme matching WhatsApp message type
            val bgRes = if (isOutgoing) R.drawable.bg_overlay_outgoing else R.drawable.bg_overlay_incoming
            val labelColor = if (isOutgoing) {
                ContextCompat.getColor(context, R.color.overlay_outgoing_label)
            } else {
                ContextCompat.getColor(context, R.color.overlay_incoming_label)
            }

            llCollapsed.setBackgroundResource(bgRes)
            llExpanded.setBackgroundResource(bgRes)
            ivBadge.setColorFilter(labelColor)
            tvBadge.setTextColor(labelColor)
            tvLabel.setTextColor(labelColor)

            // Click listener: tap icon badge to expand translation
            llCollapsed.setOnClickListener {
                expandOverlay(displayKey)
            }

            // Click listener: tap expanded card to collapse back to badge
            llExpanded.setOnClickListener {
                collapseOverlay(displayKey)
            }

            val isExpanded = (displayKey == expandedDisplayKey)
            llCollapsed.visibility = if (isExpanded) View.GONE else View.VISIBLE
            llExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

            val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast((70 * density).toInt())
            val bubbleWidth = targetBounds.width().coerceIn((55 * density).toInt(), maxAllowedWidth)

            if (isExpanded) {
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            } else {
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }

            val measuredWidth = if (isExpanded) {
                overlayView.measuredWidth.coerceIn((50 * density).toInt(), maxAllowedWidth)
            } else {
                overlayView.measuredWidth
            }
            val measuredHeight = overlayView.measuredHeight

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
                    lastScreenBounds = screenBounds,
                    lastInputBarTop = inputBarTop,
                    overlayScreenRect = placedRect
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach translation overlay", e)
            }
        }
    }

    /**
     * Expands a specific translation overlay and collapses any other currently expanded one.
     */
    fun expandOverlay(displayKey: String) {
        runOnMainThread {
            val previousKey = expandedDisplayKey
            expandedDisplayKey = displayKey

            // Collapse previous if different
            if (previousKey != null && previousKey != displayKey) {
                activeOverlays[previousKey]?.let { updateOverlayDisplayState(it, isExpanded = false) }
            }

            // Expand requested
            activeOverlays[displayKey]?.let { updateOverlayDisplayState(it, isExpanded = true) }

            // Auto-collapse after timeout as a safety feature
            mainHandler.removeCallbacks(autoCollapseRunnable)
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_TIMEOUT_MS)
        }
    }

    /**
     * Collapses a specific translation overlay back to its compact icon badge.
     */
    fun collapseOverlay(displayKey: String) {
        runOnMainThread {
            if (expandedDisplayKey == displayKey) {
                expandedDisplayKey = null
                mainHandler.removeCallbacks(autoCollapseRunnable)
                activeOverlays[displayKey]?.let { updateOverlayDisplayState(it, isExpanded = false) }
            }
        }
    }

    /**
     * Automatically collapses all expanded overlays back to their small icon badges.
     * Called when a new WhatsApp message arrives or when chat context changes.
     */
    fun collapseAll() {
        runOnMainThread {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            val currentExpanded = expandedDisplayKey ?: return@runOnMainThread
            expandedDisplayKey = null
            activeOverlays[currentExpanded]?.let { updateOverlayDisplayState(it, isExpanded = false) }
        }
    }

    private fun updateOverlayDisplayState(active: ActiveOverlay, isExpanded: Boolean) {
        val llCollapsed = active.view.findViewById<LinearLayout>(R.id.llCollapsedBadge) ?: return
        val llExpanded = active.view.findViewById<LinearLayout>(R.id.llExpandedCard) ?: return
        val lp = active.view.layoutParams as? WindowManager.LayoutParams ?: return

        llCollapsed.visibility = if (isExpanded) View.GONE else View.VISIBLE
        llExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val screenW = active.lastScreenBounds.width()
        val isOutgoing = active.currentBounds.left > screenW * 0.30f
        val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast((70 * density).toInt())
        val bubbleWidth = active.currentBounds.width().coerceIn((55 * density).toInt(), maxAllowedWidth)

        if (isExpanded) {
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        } else {
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val measuredWidth = if (isExpanded) {
            active.view.measuredWidth.coerceIn((50 * density).toInt(), maxAllowedWidth)
        } else {
            active.view.measuredWidth
        }

        var posX = if (isOutgoing) {
            active.currentBounds.right - measuredWidth
        } else {
            active.currentBounds.left
        }

        if (posX + measuredWidth > screenW - marginPx) {
            posX = screenW - measuredWidth - marginPx
        }
        if (posX < marginPx) {
            posX = marginPx
        }

        val posY = active.currentBounds.bottom + gapPx

        lp.x = posX
        lp.y = posY
        lp.width = measuredWidth
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        try {
            windowManager.updateViewLayout(active.view, lp)
            active.overlayScreenRect = Rect(posX, posY, posX + measuredWidth, posY + active.view.measuredHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay view display state", e)
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

        active.currentBounds = targetBounds
        active.lastScreenBounds = screenBounds
        active.lastInputBarTop = inputBarTop

        val isExpanded = (active.displayKey == expandedDisplayKey)
        val llCollapsed = active.view.findViewById<LinearLayout>(R.id.llCollapsedBadge)
        val llExpanded = active.view.findViewById<LinearLayout>(R.id.llExpandedCard)

        llCollapsed?.visibility = if (isExpanded) View.GONE else View.VISIBLE
        llExpanded?.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val lp = active.view.layoutParams as? WindowManager.LayoutParams ?: return
        val screenW = screenBounds.width()
        val screenH = screenBounds.height()
        val isOutgoing = targetBounds.left > screenW * 0.30f

        val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast((70 * density).toInt())
        val bubbleWidth = targetBounds.width().coerceIn((55 * density).toInt(), maxAllowedWidth)

        if (isExpanded) {
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        } else {
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val measuredWidth = if (isExpanded) {
            active.view.measuredWidth.coerceIn((50 * density).toInt(), maxAllowedWidth)
        } else {
            active.view.measuredWidth
        }
        val measuredHeight = active.view.measuredHeight

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
                active.overlayScreenRect = Rect(posX, posY, posX + measuredWidth, posY + measuredHeight)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update overlay layout", e)
            }
        }
    }

    /**
     * Removes a single overlay by displayKey.
     */
    fun removeOverlay(displayKey: String) {
        runOnMainThread {
            if (expandedDisplayKey == displayKey) {
                expandedDisplayKey = null
                mainHandler.removeCallbacks(autoCollapseRunnable)
            }
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
                    if (expandedDisplayKey == entry.key) {
                        expandedDisplayKey = null
                        mainHandler.removeCallbacks(autoCollapseRunnable)
                    }
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
            expandedDisplayKey = null
            mainHandler.removeCallbacks(autoCollapseRunnable)
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
