package com.bangla.translator

import com.bangla.translator.translation.BengaliDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BengaliDetectorTest {

    @Test
    fun testPureBengaliMessage() {
        assertTrue(BengaliDetector.isBengali("তুমি কোথায় আছো?"))
        assertTrue(BengaliDetector.isBengali("কেমন আছেন? সব ঠিক আছে তো?"))
    }

    @Test
    fun testMixedBengaliAndEnglishMessage() {
        // "কাল meeting আছে?" contains 6 Bengali characters and 7 English letters -> ratio ~46% > 20%
        assertTrue(BengaliDetector.isBengali("কাল meeting আছে?"))
        assertTrue(BengaliDetector.isBengali("ভাই WhatsApp এ call দিন"))
    }

    @Test
    fun testEnglishOnlyMessage() {
        assertFalse(BengaliDetector.isBengali("Hello how are you?"))
        assertFalse(BengaliDetector.isBengali("Let's meet tomorrow at 10 AM"))
    }

    @Test
    fun testEmojiOnlyMessage() {
        assertFalse(BengaliDetector.isBengali("😂👍🎉"))
        assertFalse(BengaliDetector.isBengali("❤️🔥"))
    }

    @Test
    fun testBengaliWithEmojiMessage() {
        assertTrue(BengaliDetector.isBengali("ভালো আছি ভাই 😂👍"))
    }

    @Test
    fun testUrlExclusion() {
        assertFalse(BengaliDetector.isBengali("https://example.com"))
        assertFalse(BengaliDetector.isBengali("http://news.bangla.com/article/123"))
    }

    @Test
    fun testTimestampAndDurationExclusion() {
        assertFalse(BengaliDetector.isBengali("12:45 PM"))
        assertFalse(BengaliDetector.isBengali("09:30 am"))
        assertFalse(BengaliDetector.isBengali("0:15"))
    }

    @Test
    fun testNumbersOnly() {
        assertFalse(BengaliDetector.isBengali("123456789"))
        assertFalse(BengaliDetector.isBengali("+8801712345678"))
    }

    @Test
    fun testEmptyOrWhitespace() {
        assertFalse(BengaliDetector.isBengali(""))
        assertFalse(BengaliDetector.isBengali("   "))
        assertFalse(BengaliDetector.isBengali(null))
    }
}
