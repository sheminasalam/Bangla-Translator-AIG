package com.bangla.translator.translation

import androidx.collection.LruCache
import java.util.regex.Pattern

/**
 * Thread-safe LRU cache for Bengali-to-English translations.
 * Keyed strictly by normalized message text, separating linguistic identity
 * from screen position and conversation sessions.
 */
class TranslationCache(maxEntries: Int = 500) {

    private val cache = object : LruCache<String, String>(maxEntries) {}
    private val whitespaceRegex = Pattern.compile("\\s+")

    /**
     * Normalizes text for cache lookups:
     * - Trims leading/trailing whitespace
     * - Collapses internal sequences of whitespace into single spaces
     * - Preserves casing and punctuation
     */
    fun normalize(text: String): String {
        val trimmed = text.trim()
        return whitespaceRegex.matcher(trimmed).replaceAll(" ")
    }

    /**
     * Retrieves translation for the given text, if previously cached.
     */
    fun get(text: String): String? {
        val key = normalize(text)
        synchronized(cache) {
            return cache.get(key)
        }
    }

    /**
     * Stores a translation result in the cache.
     */
    fun put(originalText: String, translatedText: String) {
        val key = normalize(originalText)
        synchronized(cache) {
            cache.put(key, translatedText)
        }
    }

    /**
     * Clears all cached translations.
     */
    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    /**
     * Current number of entries in cache.
     */
    val size: Int
        get() = synchronized(cache) { cache.size() }
}
