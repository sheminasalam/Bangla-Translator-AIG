package com.bangla.translator.scanner

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.bangla.translator.data.ScannedMessage
import com.bangla.translator.translation.BengaliDetector
import java.util.ArrayDeque
import java.util.regex.Pattern

/**
 * Robust scanner for detecting Bengali message elements in WhatsApp and WhatsApp Business.
 * Resilient against DOM/class/ID changes across WhatsApp updates by combining heuristic
 * structural filters with Unicode Bengali linguistic verification.
 */
class WhatsAppMessageScanner(
    private val bengaliRatioThreshold: Float = 0.20f
) {

    companion object {
        val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        private val PHONE_NUMBER_PATTERN = Pattern.compile("^[+]?[0-9\\s-]{7,16}$")
        private val SYSTEM_NOTICE_PATTERNS = listOf(
            "end-to-end encrypted",
            "messages and calls are end-to-end",
            "waiting for this message",
            "security code changed",
            "tap to learn more"
        )
        private val STATUS_INDICATORS = setOf(
            "online", "typing...", "recording audio...", "last seen", "swipe to reply"
        )
        private val ACTION_BUTTONS = setOf(
            "call", "pay", "search", "attach", "send", "voice message", "back", "more options"
        )
    }

    /**
     * Traverses the active accessibility node hierarchy to discover visible Bengali messages.
     * All traversed AccessibilityNodeInfo objects are guaranteed to be recycled.
     *
     * @param root The root node from AccessibilityService (e.g. rootInActiveWindow).
     * @param screenBounds The screen viewport rectangle used to verify visibility.
     * @param sessionGeneration Current session ID for constructing generation-safe display keys.
     * @return List of valid ScannedMessage instances.
     */
    fun scanVisibleMessages(
        root: AccessibilityNodeInfo?,
        screenBounds: Rect,
        sessionGeneration: Long
    ): List<ScannedMessage> {
        if (root == null) return emptyList()

        // 1. Verify package belongs to WhatsApp
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg !in SUPPORTED_PACKAGES) {
            return emptyList()
        }

        val results = mutableListOf<ScannedMessage>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        val tempBounds = Rect()
        var visitedCount = 0
        val maxNodesToVisit = 200 // Prevent deep tree performance bottlenecks

        try {
            while (!queue.isEmpty() && visitedCount < maxNodesToVisit) {
                val node = queue.poll() ?: continue
                visitedCount++

                try {
                    // Check node visibility
                    if (node.isVisibleToUser) {
                        node.getBoundsInScreen(tempBounds)

                        // Ensure node is within current visible screen viewport
                        val isHorizontallyVisible = tempBounds.right > screenBounds.left && tempBounds.left < screenBounds.right
                        val isVerticallyVisible = tempBounds.bottom > screenBounds.top && tempBounds.top < screenBounds.bottom

                        // ONLY extract from LEAF text nodes (childCount == 0) or TextViews to prevent
                        // parent ViewGroup containers from creating duplicate overlapping overlays!
                        val isLeafOrText = node.childCount == 0 ||
                                node.className?.toString()?.contains("TextView") == true ||
                                node.className?.toString()?.contains("TextEmojiLabel") == true

                        if (isHorizontallyVisible && isVerticallyVisible && isLeafOrText && tempBounds.width() > 15 && tempBounds.height() > 15) {
                            // Check if this node is inside a quoted/reply preview container
                            if (!isInsideQuotedMessage(node)) {
                                val candidateText = extractCandidateText(node)
                                if (candidateText != null && isLikelyBengaliMessage(candidateText, node)) {
                                    val normalized = candidateText.trim().replace(Regex("\\s+"), " ")
                                    val displayKey = "gen_${sessionGeneration}_${normalized.hashCode()}_${tempBounds.left}_${tempBounds.top}"
                                    
                                    // Spatial de-duplication: avoid adding duplicate items if overlapping with an existing scanned message
                                    val isDuplicate = results.any { existing ->
                                        existing.normalizedText == normalized &&
                                                Math.abs(existing.bounds.top - tempBounds.top) < 40 &&
                                                Math.abs(existing.bounds.left - tempBounds.left) < 60
                                    }

                                    if (!isDuplicate) {
                                        results.add(
                                            ScannedMessage(
                                                originalText = candidateText,
                                                normalizedText = normalized,
                                                bounds = Rect(tempBounds),
                                                displayKey = displayKey
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Enqueue children
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            // Recycle any remaining nodes in the queue
            while (!queue.isEmpty()) {
                queue.poll()?.recycle()
            }
        }

        return results
    }

    /**
     * Inspects the node and its immediate ancestors to verify if it belongs
     * to a quoted message / reply preview header, which should not be translated.
     */
    private fun isInsideQuotedMessage(node: AccessibilityNodeInfo): Boolean {
        // 1. Direct view ID check on the node
        val directId = node.viewIdResourceName?.lowercase() ?: ""
        if (directId.contains("quoted") || directId.contains("quote") || directId.contains("reply")) {
            return true
        }

        // 2. Ancestor view ID check (up to 4 levels)
        var current: AccessibilityNodeInfo? = node
        try {
            for (depth in 0..3) {
                val parent = current?.parent ?: break
                val parentId = parent.viewIdResourceName?.lowercase() ?: ""
                if (parentId.contains("quoted") || parentId.contains("quote") || parentId.contains("reply_container")) {
                    parent.recycle()
                    return true
                }
                if (current != node) {
                    current?.recycle()
                }
                current = parent
            }
        } finally {
            if (current != null && current != node) {
                current.recycle()
            }
        }
        return false
    }

    /**
     * Extracts text from node. Strictly prioritizes node.text for message contents
     * to avoid extracting full container descriptions from parent layouts.
     */
    private fun extractCandidateText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) return text

        // Only fallback to contentDescription if leaf node
        if (node.childCount == 0) {
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank() && !desc.startsWith("Voice message") && !desc.startsWith("Photo")) {
                return desc
            }
        }
        return null
    }

    /**
     * Applies heuristic filters to discard non-message UI (headers, buttons, timestamps)
     * before running Bengali Unicode ratio detection.
     */
    private fun isLikelyBengaliMessage(text: String, node: AccessibilityNodeInfo): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val lower = trimmed.lowercase()

        // 1. Filter out common system banners & notices
        for (pattern in SYSTEM_NOTICE_PATTERNS) {
            if (lower.contains(pattern)) return false
        }

        // 2. Filter out status indicators & typing labels
        if (lower in STATUS_INDICATORS) return false

        // 3. Filter out action buttons
        if (lower in ACTION_BUTTONS) return false

        // 4. Filter phone numbers
        if (PHONE_NUMBER_PATTERN.matcher(trimmed).matches()) return false

        // 5. Filter interactive input fields (e.g. "Type a message" EditText)
        val className = node.className?.toString() ?: ""
        if (node.isEditable || className.contains("EditText")) {
            return false
        }

        // 6. Linguistic check: Bengali character ratio test
        return BengaliDetector.isBengali(trimmed, bengaliRatioThreshold)
    }
}
