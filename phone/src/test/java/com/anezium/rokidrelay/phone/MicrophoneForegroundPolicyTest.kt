package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneForegroundPolicyTest {
    @Test
    fun persistentArmedServiceRequiresEveryGate() {
        val booleans = listOf(false, true)
        val engines = listOf(
            SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER,
            SpeechToTextEngine.ANDROID_CXR,
        )

        booleans.forEach { relayEnabled ->
            engines.forEach { selectedEngine ->
                booleans.forEach { recordAudioGranted ->
                    booleans.forEach { microphoneForegroundActive ->
                        val expected = relayEnabled &&
                            selectedEngine == SpeechToTextEngine.ANDROID_CXR &&
                            recordAudioGranted &&
                            microphoneForegroundActive
                        assertEquals(
                            "relay=$relayEnabled engine=$selectedEngine " +
                                "recordAudio=$recordAudioGranted micFgs=$microphoneForegroundActive",
                            expected,
                            shouldPersistArmedService(
                                relayEnabled = relayEnabled,
                                selectedEngine = selectedEngine,
                                recordAudioGranted = recordAudioGranted,
                                microphoneForegroundActive = microphoneForegroundActive,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun foregroundNotificationSelectsArmHintForEveryGateCombination() {
        val normalText = "Forwarding replyable notifications to the glasses"
        val booleans = listOf(false, true)
        val engines = listOf(
            SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER,
            SpeechToTextEngine.ANDROID_CXR,
        )

        booleans.forEach { relayEnabled ->
            engines.forEach { selectedEngine ->
                booleans.forEach { recordAudioGranted ->
                    booleans.forEach { microphoneForegroundActive ->
                        val expected = if (
                            relayEnabled &&
                            selectedEngine == SpeechToTextEngine.ANDROID_CXR &&
                            recordAudioGranted &&
                            !microphoneForegroundActive
                        ) {
                            "Open Rokid Relay once to enable glasses voice replies"
                        } else {
                            normalText
                        }
                        assertEquals(
                            "relay=$relayEnabled engine=$selectedEngine " +
                                "recordAudio=$recordAudioGranted micFgs=$microphoneForegroundActive",
                            expected,
                            relayForegroundNotificationText(
                                defaultText = normalText,
                                relayEnabled = relayEnabled,
                                selectedEngine = selectedEngine,
                                recordAudioGranted = recordAudioGranted,
                                microphoneForegroundActive = microphoneForegroundActive,
                            ),
                        )
                    }
                }
            }
        }
    }

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
    fun backgroundNotificationAndBleReplyWakesRequestInitialAndroidCxrMicrophoneType() {
        listOf(
            RelayStarter.START_REASON_NOTIFICATION,
            RelayStarter.START_REASON_BLE_WAKE_REPLY,
        ).forEach { reason ->
            assertTrue(
                "Expected initial microphone type for $reason",
                shouldRequestMicrophoneForegroundOnInitialWake(
                    startReason = reason,
                    selectedEngine = SpeechToTextEngine.ANDROID_CXR,
                    recordAudioGranted = true,
                ),
            )
        }
    }

    @Test
    fun initialWakeMicrophoneTypeRequiresWakeReasonAndroidCxrAndRecordAudio() {
        assertFalse(
            shouldRequestMicrophoneForegroundOnInitialWake(
                startReason = RelayStarter.START_REASON_MANUAL,
                selectedEngine = SpeechToTextEngine.ANDROID_CXR,
                recordAudioGranted = true,
            ),
        )
        assertFalse(
            shouldRequestMicrophoneForegroundOnInitialWake(
                startReason = RelayStarter.START_REASON_NOTIFICATION,
                selectedEngine = SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER,
                recordAudioGranted = true,
            ),
        )
        assertFalse(
            shouldRequestMicrophoneForegroundOnInitialWake(
                startReason = RelayStarter.START_REASON_BLE_WAKE_REPLY,
                selectedEngine = SpeechToTextEngine.ANDROID_CXR,
                recordAudioGranted = false,
            ),
        )
    }

    @Test
    fun initialWakePromotionLogsExposeGrantAndFallbackOutcomes() {
        assertEquals(
            "initial wake promotion mic=granted: " +
                "reason=notification_posted type=connectedDevice|microphone",
            initialWakeMicrophonePromotionLogLine(
                granted = true,
                detail = "reason=notification_posted type=connectedDevice|microphone",
            ),
        )
        assertEquals(
            "initial wake promotion mic=denied: SecurityException: while-in-use denied; " +
                "fallback=connectedDevice",
            initialWakeMicrophonePromotionLogLine(
                granted = false,
                detail = "SecurityException: while-in-use denied; fallback=connectedDevice",
            ),
        )
    }

    @Test
    fun injectedAudioTriesWithoutMicrophoneForegroundInsteadOfFailingClosed() {
        assertEquals(
            InjectedAudioForegroundDecision.USE_MICROPHONE_FOREGROUND,
            injectedAudioForegroundDecision(microphoneForegroundActive = true),
        )
        assertEquals(
            InjectedAudioForegroundDecision.TRY_WITHOUT_MICROPHONE_FOREGROUND,
            injectedAudioForegroundDecision(microphoneForegroundActive = false),
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
