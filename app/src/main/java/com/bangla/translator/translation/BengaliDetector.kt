package com.bangla.translator.translation

import java.util.regex.Pattern

/**
 * High-performance Unicode-based detector for Bengali text.
 * Correctly distinguishes Bengali and mixed Bengali-English messages from
 * English-only text, URLs, numbers, emojis, and system messages.
 */
object BengaliDetector {

    // Bengali Unicode block: U+0980 through U+09FF
    private const val BENGALI_START = 0x0980
    private const val BENGALI_END = 0x09FF

    // Precompiled patterns for fast exclusion
    private val URL_PATTERN = Pattern.compile(
        "^https?://[\\w.-]+(?:\\.[\\w\\.-]+)+[/#?]?.*$",
        Pattern.CASE_INSENSITIVE
    )
    private val TIMESTAMP_PATTERN = Pattern.compile(
        "^\\d{1,2}:\\d{2}(?:\\s?[APap][Mm])?$"
    )
    private val AUDIO_DURATION_PATTERN = Pattern.compile(
        "^\\d{1,2}:\\d{2}$"
    )

    /**
     * Checks if the given character is within the Bengali Unicode block.
     */
    fun isBengaliChar(ch: Char): Boolean {
        val code = ch.code
        return code in BENGALI_START..BENGALI_END
    }

    /**
     * Checks if the given codepoint is within the Bengali Unicode block.
     */
    fun isBengaliCodePoint(codePoint: Int): Boolean {
        return codePoint in BENGALI_START..BENGALI_END
    }

    /**
     * Determines whether the given text is likely a Bengali or mixed Bengali message
     * needing translation, using an alphabetic ratio that ignores punctuation, emojis,
     * and whitespace.
     *
     * @param text Raw or trimmed message text.
     * @param threshold Minimum ratio of Bengali characters to total alphabetic letters (default 0.20 = 20%).
     * @return true if the text meets the criteria for Bengali translation.
     */
    fun isBengali(text: CharSequence?, threshold: Float = 0.20f): Boolean {
        if (text.isNullOrBlank()) return false

        val trimmed = text.toString().trim()
        if (trimmed.isEmpty()) return false

        // Quick rejection for URLs
        if (URL_PATTERN.matcher(trimmed).matches()) return false

        // Quick rejection for timestamps or audio durations
        if (TIMESTAMP_PATTERN.matcher(trimmed).matches() || AUDIO_DURATION_PATTERN.matcher(trimmed).matches()) {
            return false
        }

        var bengaliCharCount = 0
        var totalAlphabeticCount = 0

        var i = 0
        val length = trimmed.length
        while (i < length) {
            val codePoint = Character.codePointAt(trimmed, i)
            val charCount = Character.charCount(codePoint)

            when {
                // Bengali character
                codePoint in BENGALI_START..BENGALI_END -> {
                    bengaliCharCount++
                    totalAlphabeticCount++
                }
                // Latin or other alphabetic character
                Character.isLetter(codePoint) -> {
                    totalAlphabeticCount++
                }
                // Ignore whitespace, digits, symbols, emojis, and punctuation
                else -> {
                    // Non-letter characters (spaces, punctuation, emojis) do not penalize the ratio
                }
            }

            i += charCount
        }

        // If no alphabetic letters were found (e.g., only emojis, punctuation, or numbers)
        if (totalAlphabeticCount == 0) {
            return false
        }

        val ratio = bengaliCharCount.toFloat() / totalAlphabeticCount.toFloat()
        return ratio >= threshold
    }

    /**
     * Quick check if the text contains at least one Bengali character.
     */
    fun containsBengali(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        for (i in 0 until text.length) {
            if (isBengaliChar(text[i])) return true
        }
        return false
    }
}
