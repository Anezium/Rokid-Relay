package com.anezium.rokidrelay.phone

import android.content.Context
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
}
