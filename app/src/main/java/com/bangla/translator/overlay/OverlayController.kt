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
 * Controller responsible for managing interactive on-demand translation overlays
 * attached directly to WhatsApp message bubbles.
 *
 * States per message:
 * 1. Collapsed (Default): A small, sleek [EN] translate icon badge attached to the bubble corner.
 * 2. Expanded (On Click): Full chat bubble matching the EXACT width, horizontal span, and
 *    rounded shape of the WhatsApp message bubble.
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
        private const val MIN_EXPANDED_WIDTH_DP = 140
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
    private val minExpandedWidthPx = (MIN_EXPANDED_WIDTH_DP * density).toInt()
    private val statusBarInsetPx = (STATUS_BAR_MARGIN_DP * density).toInt()
    private val navBarInsetPx = (NAV_BAR_MARGIN_DP * density).toInt()

    private val autoCollapseRunnable = Runnable {
        collapseAll()
    }

    /**
     * Displays or updates a translation overlay on the main thread.
     * Collapsed by default; expands into a bubble matching the WhatsApp message bubble size on click.
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

            // 100% reliable WhatsApp incoming vs outgoing detection
            val isOutgoing = targetBounds.right > screenW * 0.78f || targetBounds.left > screenW * 0.40f

            // Apply contextual styling matching WhatsApp chat bubble colors & shape
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

            // Click to expand
            llCollapsed.setOnClickListener {
                expandOverlay(displayKey)
            }

            // Click to collapse
            llExpanded.setOnClickListener {
                collapseOverlay(displayKey)
            }

            val isExpanded = (displayKey == expandedDisplayKey)
            llCollapsed.visibility = if (isExpanded) View.GONE else View.VISIBLE
            llExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

            val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast(minExpandedWidthPx)
            val bubbleWidth = targetBounds.width().coerceIn(minExpandedWidthPx, maxAllowedWidth)

            if (isExpanded) {
                // Force EXACT width matching the WhatsApp chat bubble!
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            } else {
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }

            val measuredWidth = if (isExpanded) bubbleWidth else overlayView.measuredWidth
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
     * Expands a specific translation overlay into full WhatsApp bubble size and collapses any other.
     */
    fun expandOverlay(displayKey: String) {
        runOnMainThread {
            val previousKey = expandedDisplayKey
            expandedDisplayKey = displayKey

            if (previousKey != null && previousKey != displayKey) {
                activeOverlays[previousKey]?.let { updateOverlayDisplayState(it, isExpanded = false) }
            }

            activeOverlays[displayKey]?.let { updateOverlayDisplayState(it, isExpanded = true) }

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
     * Automatically collapses all expanded overlays back to small icon badges.
     * Triggered when a new WhatsApp message arrives or chat context changes.
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
        val isOutgoing = active.currentBounds.right > screenW * 0.78f || active.currentBounds.left > screenW * 0.40f

        val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast(minExpandedWidthPx)
        val bubbleWidth = active.currentBounds.width().coerceIn(minExpandedWidthPx, maxAllowedWidth)

        if (isExpanded) {
            // Adopt EXACT WhatsApp bubble width so text flows horizontally in a natural bubble
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        } else {
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val measuredWidth = if (isExpanded) bubbleWidth else active.view.measuredWidth

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
        val screenW = screenBounds.width()
        val isOutgoing = targetBounds.right > screenW * 0.78f || targetBounds.left > screenW * 0.40f

        val bgRes = if (isOutgoing) R.drawable.bg_overlay_outgoing else R.drawable.bg_overlay_incoming
        val labelColor = if (isOutgoing) {
            ContextCompat.getColor(context, R.color.overlay_outgoing_label)
        } else {
            ContextCompat.getColor(context, R.color.overlay_incoming_label)
        }

        val llCollapsed = active.view.findViewById<LinearLayout>(R.id.llCollapsedBadge)
        val llExpanded = active.view.findViewById<LinearLayout>(R.id.llExpandedCard)
        val ivBadge = active.view.findViewById<ImageView>(R.id.ivBadgeIcon)
        val tvBadge = active.view.findViewById<TextView>(R.id.tvBadgeText)
        val tvLabel = active.view.findViewById<TextView>(R.id.tvLanguageLabel)

        llCollapsed?.setBackgroundResource(bgRes)
        llExpanded?.setBackgroundResource(bgRes)
        ivBadge?.setColorFilter(labelColor)
        tvBadge?.setTextColor(labelColor)
        tvLabel?.setTextColor(labelColor)

        updateOverlayDisplayState(active, isExpanded)
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
