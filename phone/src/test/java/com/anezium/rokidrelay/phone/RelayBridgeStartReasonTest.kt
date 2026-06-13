package com.anezium.rokidrelay.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBridgeStartReasonTest {
    @Test
    fun interactiveReasonsMayOpenGlassesHelperAfterInstall() {
        listOf(
            "app_open",
            "authorization",
            "permissions",
            "stt_engine",
            "stt_provider",
            "stt_model",
            "microphone_permission",
        ).forEach { reason ->
            assertTrue("Expected $reason to allow foreground start", allowsGlassesForegroundStart(reason))
        }
    }

    @Test
    fun backgroundReasonsKeepGlassesHelperBehind() {
        listOf(
            "BOOT_COMPLETED",
            "MY_PACKAGE_REPLACED",
            "ACL_CONNECTED",
            "BLUETOOTH_ON",
            "glasses_present",
            "notification_listener",
            "",
        ).forEach { reason ->
            assertFalse("Expected $reason to stay background", allowsGlassesForegroundStart(reason))
        }
    }

    @Test
    fun notificationSendCanUseLiveCxrLinkWhileForegroundLaunchIsPending() {
        assertTrue(
            canSendNotificationEvent(
                bootstrapReadyForMessages = false,
                cxrConnected = true,
                glassConnected = true,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun notificationSendWaitsWhenCxrLinkIsNotReady() {
        assertFalse(
            canSendNotificationEvent(
                bootstrapReadyForMessages = false,
                cxrConnected = true,
                glassConnected = false,
                serviceConnected = true,
            ),
        )
    }
}
