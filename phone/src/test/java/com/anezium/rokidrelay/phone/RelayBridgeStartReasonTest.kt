package com.anezium.rokidrelay.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBridgeStartReasonTest {
    @Test
    fun interactiveReasonsMayOpenGlassesHelperAfterInstall() {
        listOf(
            RelayStarter.START_REASON_MANUAL,
            RelayStarter.START_REASON_RELAUNCH,
            "authorization",
            "permissions",
            "stt_engine",
            "stt_provider",
            "stt_model",
            "microphone_permission",
        ).forEach { reason ->
            assertTrue("Expected $reason to allow foreground start", allowsGlassesForegroundStart(reason))
            assertTrue("Expected $reason to be user initiated", isUserInitiatedRelayStart(reason))
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
            "app_open",
            "notification_listener",
            RelayStarter.START_REASON_NOTIFICATION,
            "",
        ).forEach { reason ->
            assertFalse("Expected $reason to stay background", allowsGlassesForegroundStart(reason))
            assertFalse("Expected $reason to stay passive", isUserInitiatedRelayStart(reason))
        }
    }

    @Test
    fun notificationReasonIsWakeOnly() {
        assertTrue(isNotificationWakeStart(RelayStarter.START_REASON_NOTIFICATION))
        assertFalse(allowsGlassesForegroundStart(RelayStarter.START_REASON_NOTIFICATION))
        assertFalse(isUserInitiatedRelayStart(RelayStarter.START_REASON_NOTIFICATION))
    }

    @Test
    fun bleWakeReplyMayOpenHelperWithoutBecomingManualStart() {
        assertTrue(isBleWakeReplyStart(RelayStarter.START_REASON_BLE_WAKE_REPLY))
        assertTrue(allowsGlassesForegroundStart(RelayStarter.START_REASON_BLE_WAKE_REPLY))
        assertFalse(isUserInitiatedRelayStart(RelayStarter.START_REASON_BLE_WAKE_REPLY))
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
