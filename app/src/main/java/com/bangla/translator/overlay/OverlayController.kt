package com.bangla.translator.overlay

import android.content.Context
import android.graphics.Color
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
 * Requirements implemented:
 * 1. Icons are always strictly behind the translation bubble when expanded.
 * 2. Icon badge placed at the middle of the side away from the outer edge of the screen:
 *    - Outgoing (right): icon is on the LEFT of the bubble (at vertical middle).
 *    - Incoming (left): icon is on the RIGHT of the bubble (at vertical middle).
 *    This prevents collision with the text typing bar for the very last message!
 * 3. Tapping anywhere on the screen immediately closes the expanded bubble via a
 *    transparent full-screen dismiss backdrop.
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
        private const val BADGE_GAP_DP = 4
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

    // Transparent full-screen backdrop to dismiss when clicking anywhere on screen
    private var dismissBackdropView: View? = null

    private val density = context.resources.displayMetrics.density
    private val marginPx = (HORIZONTAL_MARGIN_DP * density).toInt()
    private val gapPx = (ATTACHMENT_GAP_DP * density).toInt()
    private val badgeGapPx = (BADGE_GAP_DP * density).toInt()
    private val minExpandedWidthPx = (MIN_EXPANDED_WIDTH_DP * density).toInt()
    private val statusBarInsetPx = (STATUS_BAR_MARGIN_DP * density).toInt()
    private val navBarInsetPx = (NAV_BAR_MARGIN_DP * density).toInt()

    private val autoCollapseRunnable = Runnable {
        collapseAll()
    }

    /**
     * Displays or updates a translation overlay on the main thread.
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
            val isOtherExpanded = (expandedDisplayKey != null && !isExpanded)

            // If another bubble is expanded, hide this collapsed badge to keep icons behind/hidden
            llCollapsed.visibility = if (isExpanded || isOtherExpanded) View.GONE else View.VISIBLE
            llExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

            val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast(minExpandedWidthPx)
            val bubbleWidth = targetBounds.width().coerceIn(minExpandedWidthPx, maxAllowedWidth)

            val measuredWidth: Int
            val measuredHeight: Int
            val posX: Int
            val posY: Int

            val bottomLimit = if (inputBarTop != null && inputBarTop > statusBarInsetPx + (100 * density).toInt()) {
                inputBarTop - (4 * density).toInt()
            } else {
                screenH - (navBarInsetPx + (56 * density).toInt())
            }

            if (isExpanded) {
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                measuredWidth = bubbleWidth
                measuredHeight = overlayView.measuredHeight

                var calculatedX = if (isOutgoing) targetBounds.right - measuredWidth else targetBounds.left
                if (calculatedX + measuredWidth > screenW - marginPx) calculatedX = screenW - measuredWidth - marginPx
                if (calculatedX < marginPx) calculatedX = marginPx
                posX = calculatedX

                // Vertical placement for expanded bubble: prefer below; if too close to bottom limit, place above!
                posY = if (targetBounds.bottom + gapPx + measuredHeight <= bottomLimit) {
                    targetBounds.bottom + gapPx
                } else {
                    (targetBounds.top - measuredHeight - gapPx).coerceAtLeast(statusBarInsetPx)
                }
            } else {
                // Collapsed State: Icon badge placed at the middle of the side away from the outer edge of screen!
                overlayView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                measuredWidth = overlayView.measuredWidth
                measuredHeight = overlayView.measuredHeight

                var calculatedX = if (isOutgoing) {
                    // Outgoing bubble on right -> place icon on LEFT side of bubble
                    targetBounds.left - measuredWidth - badgeGapPx
                } else {
                    // Incoming bubble on left -> place icon on RIGHT side of bubble
                    targetBounds.right + badgeGapPx
                }
                if (calculatedX + measuredWidth > screenW - marginPx) calculatedX = screenW - measuredWidth - marginPx
                if (calculatedX < marginPx) calculatedX = marginPx
                posX = calculatedX

                // Middle of the side of the bubble vertically!
                posY = targetBounds.centerY() - (measuredHeight / 2)
            }

            // Don't show if scrolled off screen
            if (targetBounds.top >= bottomLimit && !isExpanded) {
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

            if (isExpanded) {
                overlayView.elevation = 24 * density
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
     * Expands a specific translation overlay:
     * 1. Attaches a transparent full-screen backdrop so clicking ANYWHERE dismisses it.
     * 2. Hides other collapsed badges so no icons cover the text.
     * 3. Sets higher elevation on the expanded bubble.
     */
    fun expandOverlay(displayKey: String) {
        runOnMainThread {
            val previousKey = expandedDisplayKey
            expandedDisplayKey = displayKey

            // Attach full-screen backdrop so clicking anywhere closes the expanded bubble
            ensureDismissBackdropAttached()

            // Collapse previous if different
            if (previousKey != null && previousKey != displayKey) {
                activeOverlays[previousKey]?.let { updateOverlayDisplayState(it, isExpanded = false) }
            }

            // Hide other collapsed badges while translation bubble is open
            for ((key, other) in activeOverlays) {
                if (key != displayKey) {
                    other.view.findViewById<View>(R.id.llCollapsedBadge)?.visibility = View.GONE
                }
            }

            // Expand requested bubble on top
            activeOverlays[displayKey]?.let { active ->
                updateOverlayDisplayState(active, isExpanded = true)
            }

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
                collapseAll()
            }
        }
    }

    /**
     * Automatically collapses all expanded overlays back to small icon badges
     * and removes the full-screen dismiss backdrop.
     */
    fun collapseAll() {
        runOnMainThread {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            removeDismissBackdrop()

            val currentExpanded = expandedDisplayKey
            expandedDisplayKey = null

            // Restore all collapsed badges
            for ((_, overlay) in activeOverlays) {
                overlay.view.findViewById<View>(R.id.llCollapsedBadge)?.visibility = View.VISIBLE
            }

            if (currentExpanded != null) {
                activeOverlays[currentExpanded]?.let { updateOverlayDisplayState(it, isExpanded = false) }
            }
        }
    }

    /**
     * Creates and attaches a full-screen transparent view to intercept taps anywhere on screen.
     */
    private fun ensureDismissBackdropAttached() {
        if (dismissBackdropView != null) return
        val backdrop = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                collapseAll()
            }
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        try {
            windowManager.addView(backdrop, lp)
            dismissBackdropView = backdrop
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach dismiss backdrop", e)
        }
    }

    private fun removeDismissBackdrop() {
        val backdrop = dismissBackdropView ?: return
        dismissBackdropView = null
        try {
            windowManager.removeView(backdrop)
        } catch (_: Exception) {}
    }

    private fun updateOverlayDisplayState(active: ActiveOverlay, isExpanded: Boolean) {
        val llCollapsed = active.view.findViewById<LinearLayout>(R.id.llCollapsedBadge) ?: return
        val llExpanded = active.view.findViewById<LinearLayout>(R.id.llExpandedCard) ?: return
        val lp = active.view.layoutParams as? WindowManager.LayoutParams ?: return

        val isOtherExpanded = (expandedDisplayKey != null && !isExpanded)
        llCollapsed.visibility = if (isExpanded || isOtherExpanded) View.GONE else View.VISIBLE
        llExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val screenW = active.lastScreenBounds.width()
        val screenH = active.lastScreenBounds.height()
        val isOutgoing = active.currentBounds.right > screenW * 0.78f || active.currentBounds.left > screenW * 0.40f

        val maxAllowedWidth = (screenW - (marginPx * 2)).coerceAtLeast(minExpandedWidthPx)
        val bubbleWidth = active.currentBounds.width().coerceIn(minExpandedWidthPx, maxAllowedWidth)

        val bottomLimit = if (active.lastInputBarTop != null && active.lastInputBarTop!! > statusBarInsetPx + (100 * density).toInt()) {
            active.lastInputBarTop!! - (4 * density).toInt()
        } else {
            screenH - (navBarInsetPx + (56 * density).toInt())
        }

        val measuredWidth: Int
        val measuredHeight: Int
        val posX: Int
        val posY: Int

        if (isExpanded) {
            // Adopt EXACT WhatsApp bubble width
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            measuredWidth = bubbleWidth
            measuredHeight = active.view.measuredHeight

            var calculatedX = if (isOutgoing) active.currentBounds.right - measuredWidth else active.currentBounds.left
            if (calculatedX + measuredWidth > screenW - marginPx) calculatedX = screenW - measuredWidth - marginPx
            if (calculatedX < marginPx) calculatedX = marginPx
            posX = calculatedX

            posY = if (active.currentBounds.bottom + gapPx + measuredHeight <= bottomLimit) {
                active.currentBounds.bottom + gapPx
            } else {
                (active.currentBounds.top - measuredHeight - gapPx).coerceAtLeast(statusBarInsetPx)
            }
            active.view.elevation = 24 * density
        } else {
            // Collapsed: middle of the side away from outer edge of screen
            active.view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            measuredWidth = active.view.measuredWidth
            measuredHeight = active.view.measuredHeight

            var calculatedX = if (isOutgoing) {
                active.currentBounds.left - measuredWidth - badgeGapPx
            } else {
                active.currentBounds.right + badgeGapPx
            }
            if (calculatedX + measuredWidth > screenW - marginPx) calculatedX = screenW - measuredWidth - marginPx
            if (calculatedX < marginPx) calculatedX = marginPx
            posX = calculatedX

            posY = active.currentBounds.centerY() - (measuredHeight / 2)
            active.view.elevation = 2 * density
        }

        lp.x = posX
        lp.y = posY
        lp.width = measuredWidth
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        try {
            windowManager.updateViewLayout(active.view, lp)
            active.overlayScreenRect = Rect(posX, posY, posX + measuredWidth, posY + measuredHeight)
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
                removeDismissBackdrop()
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
                        removeDismissBackdrop()
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
            removeDismissBackdrop()
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
