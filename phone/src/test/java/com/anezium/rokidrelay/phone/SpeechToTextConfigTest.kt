package com.anezium.rokidrelay.phone

import org.junit.Assert.assertSame
import org.junit.Test

class SpeechToTextConfigTest {
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
}
