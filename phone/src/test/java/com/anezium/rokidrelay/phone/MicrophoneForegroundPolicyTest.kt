package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneForegroundPolicyTest {
    @Test
    fun androidCxrPresencePromotesForRunningArmedRelay() {
        assertTrue(
            shouldPromoteMicrophoneForegroundOnPresence(
                relayEnabled = true,
                relayServiceRunning = true,
                selectedEngine = SpeechToTextEngine.ANDROID_CXR,
            ),
        )
    }

    @Test
    fun presenceDoesNotPromoteWhenRelayIsDisabledStoppedOrUsingCloudStt() {
        assertFalse(
            shouldPromoteMicrophoneForegroundOnPresence(
                relayEnabled = false,
                relayServiceRunning = true,
                selectedEngine = SpeechToTextEngine.ANDROID_CXR,
            ),
        )
        assertFalse(
            shouldPromoteMicrophoneForegroundOnPresence(
                relayEnabled = true,
                relayServiceRunning = false,
                selectedEngine = SpeechToTextEngine.ANDROID_CXR,
            ),
        )
        assertFalse(
            shouldPromoteMicrophoneForegroundOnPresence(
                relayEnabled = true,
                relayServiceRunning = true,
                selectedEngine = SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER,
            ),
        )
    }

    @Test
    fun securityExceptionDetailIsKeptInLogAndDiagnosticsText() {
        val detail = microphoneForegroundFailureDetail(
            SecurityException("RECORD_AUDIO while-in-use eligibility denied"),
        )

        assertEquals(
            "SecurityException: RECORD_AUDIO while-in-use eligibility denied",
            detail,
        )
        assertEquals(
            "Mic foreground: off (SecurityException: RECORD_AUDIO while-in-use eligibility denied)",
            microphoneForegroundDiagnosticsLine(active = false, error = detail),
        )
    }
}
