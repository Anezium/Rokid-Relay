package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionLanguageConfigTest {
    @Test
    fun nullBlankAndUnknownLanguageIdsFallBackToAuto() {
        assertSame(TranscriptionLanguage.AUTO, TranscriptionLanguage.fromId(null))
        assertSame(TranscriptionLanguage.AUTO, TranscriptionLanguage.fromId("   "))
        assertSame(TranscriptionLanguage.AUTO, TranscriptionLanguage.fromId("not-a-real-language"))
    }

    @Test
    fun knownLanguageIdsMapToTheirEnumValues() {
        TranscriptionLanguage.values().forEach { language ->
            assertSame(language, TranscriptionLanguage.fromId(language.id))
            assertSame(language, TranscriptionLanguage.fromId("  ${language.id.uppercase()}  "))
        }
    }

    @Test
    fun cantonesePreservesProviderCodesForTraditionalChineseOutput() {
        val language = TranscriptionLanguage.CANTONESE

        assertNull(language.openAiCode)
        assertTrue(language.openAiPrompt?.isNotBlank() == true)
        assertEquals("yue", language.elevenLabsCode)
        assertEquals("zh-HK", language.azureLocale)
        assertEquals("zh-HK", language.androidTag)
    }

    @Test
    fun traditionalChinesePreservesProviderCodes() {
        val language = TranscriptionLanguage.CHINESE_TRADITIONAL

        assertEquals("zh", language.openAiCode)
        assertTrue(language.openAiPrompt?.isNotBlank() == true)
        assertEquals("zh", language.elevenLabsCode)
        assertEquals("zh-TW", language.azureLocale)
        assertEquals("zh-TW", language.androidTag)
    }

    @Test
    fun simplifiedChinesePreservesProviderCodes() {
        val language = TranscriptionLanguage.CHINESE_SIMPLIFIED

        assertEquals("zh", language.openAiCode)
        assertTrue(language.openAiPrompt?.isNotBlank() == true)
        assertEquals("zh", language.elevenLabsCode)
        assertEquals("zh-CN", language.azureLocale)
        assertEquals("zh-CN", language.androidTag)
    }
}
