package com.bangla.translator

import com.bangla.translator.translation.TranslationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun testTextNormalization() {
        val cache = TranslationCache()
        val raw = "   তুমি     কোথায়   আছো?   \n\n  "
        val expected = "তুমি কোথায় আছো?"

        assertEquals(expected, cache.normalize(raw))
    }

    @Test
    fun testCacheHitWithDifferentSpacing() {
        val cache = TranslationCache()
        cache.put("তুমি কেমন আছো?", "How are you?")

        // Spaced out variation should hit cache due to normalization
        val result = cache.get("   তুমি    কেমন   আছো?  ")
        assertNotNull(result)
        assertEquals("How are you?", result)
    }

    @Test
    fun testCacheMiss() {
        val cache = TranslationCache()
        assertNull(cache.get("কোনো অনুবাদ নেই"))
    }

    @Test
    fun testBoundedCapacityEviction() {
        val maxItems = 3
        val cache = TranslationCache(maxEntries = maxItems)

        cache.put("১", "One")
        cache.put("২", "Two")
        cache.put("৩", "Three")
        assertEquals(3, cache.size)

        // Adding 4th item should evict the oldest (LRU)
        cache.put("৪", "Four")
        assertEquals(3, cache.size)
        assertNull(cache.get("১"))
        assertNotNull(cache.get("৪"))
    }
}
