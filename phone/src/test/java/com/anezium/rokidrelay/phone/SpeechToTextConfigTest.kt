package com.anezium.rokidrelay.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpeechToTextConfigTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun nullBlankAndUnknownEngineIdsFallBackToAndroidCxr() {
        assertSame(SpeechToTextEngine.ANDROID_CXR, SpeechToTextEngine.fromId(null))
        assertSame(SpeechToTextEngine.ANDROID_CXR, SpeechToTextEngine.fromId("   "))
        assertSame(SpeechToTextEngine.ANDROID_CXR, SpeechToTextEngine.fromId("not-a-real-engine"))
    }

    @Test
    fun knownEngineIdsMapToTheirEnumValues() {
        SpeechToTextEngine.values().forEach { engine ->
            assertSame(engine, SpeechToTextEngine.fromId(engine.id))
            assertSame(engine, SpeechToTextEngine.fromId("  ${engine.id.uppercase()}  "))
        }
    }

    @Test
    fun savingAndroidCxrForcesAutoLanguage() {
        val store = SpeechToTextSettingsStore(context)

        store.saveSelectedLanguage(TranscriptionLanguage.FRENCH)
        store.saveSelectedEngine(SpeechToTextEngine.ANDROID_CXR)

        assertSame(TranscriptionLanguage.AUTO, store.selectedLanguage())
    }

    @Test
    fun selectedLanguageForAndroidCxrCoercesStaleSavedLanguageToAuto() {
        val store = SpeechToTextSettingsStore(context)

        store.saveSelectedLanguage(TranscriptionLanguage.CHINESE_SIMPLIFIED)

        assertSame(
            TranscriptionLanguage.AUTO,
            store.selectedLanguageForEngine(SpeechToTextEngine.ANDROID_CXR),
        )
        assertSame(TranscriptionLanguage.AUTO, store.selectedLanguage())
    }

    @Test
    fun selectedLanguageForApiEnginesKeepsSavedLanguage() {
        val store = SpeechToTextSettingsStore(context)

        store.saveSelectedLanguage(TranscriptionLanguage.FRENCH)

        assertSame(
            TranscriptionLanguage.FRENCH,
            store.selectedLanguageForEngine(SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER),
        )
    }

    @Test
    fun androidCxrAutoDoesNotSendExplicitLanguageHint() {
        assertNull(androidCxrLanguageTag(TranscriptionLanguage.AUTO, languageTagAttempt = 0))
    }

    @Test
    fun androidCxrExplicitLanguageWalksTagChainThenDropsHint() {
        assertEquals(
            "fr-FR",
            androidCxrLanguageTag(TranscriptionLanguage.FRENCH, languageTagAttempt = 0),
        )
        assertNull(androidCxrLanguageTag(TranscriptionLanguage.FRENCH, languageTagAttempt = 1))
    }

    @Test
    fun androidCxrCantoneseTriesGoogleCantoneseTagsBeforeHongKongChinese() {
        assertEquals(
            "yue-Hant-HK",
            androidCxrLanguageTag(TranscriptionLanguage.CANTONESE, languageTagAttempt = 0),
        )
        assertEquals(
            "yue-HK",
            androidCxrLanguageTag(TranscriptionLanguage.CANTONESE, languageTagAttempt = 1),
        )
        assertEquals(
            "zh-HK",
            androidCxrLanguageTag(TranscriptionLanguage.CANTONESE, languageTagAttempt = 2),
        )
        assertNull(androidCxrLanguageTag(TranscriptionLanguage.CANTONESE, languageTagAttempt = 3))
    }

    @Test
    fun androidCxrWalkAdvancesTagsBeforeRecognizerTarget() {
        assertEquals(
            AndroidCxrRecognizerWalkAttempt(languageTagAttempt = 1, recognizerAttempt = 0),
            nextAndroidCxrRecognizerWalkAttempt(
                language = TranscriptionLanguage.CANTONESE,
                languageTagAttempt = 0,
                recognizerAttempt = 0,
                recognizerTargetCount = 2,
            ),
        )
    }

    @Test
    fun androidCxrWalkAdvancesRecognizerAndResetsTagAfterTagChainExhausts() {
        assertEquals(
            AndroidCxrRecognizerWalkAttempt(languageTagAttempt = 0, recognizerAttempt = 1),
            nextAndroidCxrRecognizerWalkAttempt(
                language = TranscriptionLanguage.CANTONESE,
                languageTagAttempt = 2,
                recognizerAttempt = 0,
                recognizerTargetCount = 2,
            ),
        )
    }

    @Test
    fun androidCxrWalkReturnsNullWhenRecognizerTargetsExhaust() {
        assertNull(
            nextAndroidCxrRecognizerWalkAttempt(
                language = TranscriptionLanguage.CANTONESE,
                languageTagAttempt = 2,
                recognizerAttempt = 1,
                recognizerTargetCount = 2,
            ),
        )
    }

    @Test
    fun androidCxrAutoWalksRecognizerTargetsWithoutLanguageHint() {
        assertEquals(
            AndroidCxrRecognizerWalkAttempt(languageTagAttempt = 0, recognizerAttempt = 1),
            nextAndroidCxrRecognizerWalkAttempt(
                language = TranscriptionLanguage.AUTO,
                languageTagAttempt = 0,
                recognizerAttempt = 0,
                recognizerTargetCount = 2,
            ),
        )
    }

    @Test
    fun androidCxrTransientRetryPreservesWalkIndices() {
        assertEquals(
            AndroidCxrRecognizerWalkAttempt(languageTagAttempt = 2, recognizerAttempt = 1),
            sameAndroidCxrRecognizerWalkAttempt(
                languageTagAttempt = 2,
                recognizerAttempt = 1,
            ),
        )
    }

    @Test
    fun androidCxrLanguageFailureMentionsTriedTagsAndMultipleRecognizers() {
        assertEquals(
            "Speech language not supported (tried yue-Hant-HK, yue-HK, zh-HK; multiple recognizers)",
            VoiceController.androidCxrLanguageFailureMessage(
                language = TranscriptionLanguage.CANTONESE,
                message = "Speech language not supported",
                recognizerTargetCount = 3,
            ),
        )
    }
}
