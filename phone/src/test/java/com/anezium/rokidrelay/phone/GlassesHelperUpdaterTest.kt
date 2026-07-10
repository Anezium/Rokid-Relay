package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesHelperUpdaterTest {
    @Test
    fun glassesReportTakesPrecedenceOverRememberedFingerprint() {
        val installed = resolveInstalledHelperVersion(
            glassesReportedVersionCode = 21L,
            rememberedFingerprint = "24:0.1.10",
        )

        assertEquals(21L, installed?.versionCode)
        assertEquals("21", installed?.versionName)
        assertEquals(HelperVersionSource.GLASSES_REPORT, installed?.source)
        assertTrue(helperUpdateNeeded(24L, appInstalled = true, installedVersion = installed))
    }

    @Test
    fun fingerprintIsFallbackWhenLegacyHelperNeverReports() {
        val installed = resolveInstalledHelperVersion(
            glassesReportedVersionCode = null,
            rememberedFingerprint = "24:0.1.10:legacy-apk-hash",
        )

        assertEquals(24L, installed?.versionCode)
        assertEquals("0.1.10", installed?.versionName)
        assertEquals(HelperVersionSource.REMEMBERED_FINGERPRINT, installed?.source)
        assertFalse(helperUpdateNeeded(24L, appInstalled = true, installedVersion = installed))
    }

    @Test
    fun unknownInstalledVersionNeedsOneManagedUpdateAttemptForBundledVersion() {
        val installed = resolveInstalledHelperVersion(null, null)

        assertNull(installed)
        assertTrue(helperUpdateNeeded(24L, appInstalled = true, installedVersion = installed))
    }

    @Test
    fun packageManagerMissingAppOverridesRememberedCurrentVersion() {
        val installed = resolveInstalledHelperVersion(null, "24:0.1.10")

        assertTrue(helperUpdateNeeded(24L, appInstalled = false, installedVersion = installed))
    }

    @Test
    fun everyBusyGateBlocksAnAutomaticUpdate() {
        val base = HelperUpdateGates(
            linkReady = true,
            voiceSessionActive = false,
            notificationReplyWindowActive = false,
            wirelessBootstrapInFlight = false,
            wifiRadioEnabled = true,
            glassesRecoveryArmed = true,
        )

        assertEquals(HelperUpdateGateBlock.NONE, blockedHelperUpdateGate(base))
        assertEquals(
            HelperUpdateGateBlock.LINK_NOT_READY,
            blockedHelperUpdateGate(base.copy(linkReady = false)),
        )
        assertEquals(
            HelperUpdateGateBlock.VOICE_SESSION_ACTIVE,
            blockedHelperUpdateGate(base.copy(voiceSessionActive = true)),
        )
        assertEquals(
            HelperUpdateGateBlock.NOTIFICATION_REPLY_WINDOW_ACTIVE,
            blockedHelperUpdateGate(base.copy(notificationReplyWindowActive = true)),
        )
        assertEquals(
            HelperUpdateGateBlock.WIRELESS_BOOTSTRAP_IN_FLIGHT,
            blockedHelperUpdateGate(base.copy(wirelessBootstrapInFlight = true)),
        )
    }

    @Test
    fun wifiRadioOffUsesDedicatedGateWithoutConsumingAnAttempt() {
        val block = blockedHelperUpdateGate(
            HelperUpdateGates(
                linkReady = true,
                voiceSessionActive = false,
                notificationReplyWindowActive = false,
                wirelessBootstrapInFlight = false,
                wifiRadioEnabled = false,
                glassesRecoveryArmed = true,
            ),
        )

        assertEquals(HelperUpdateGateBlock.WIFI_RADIO_OFF, block)
        assertEquals(
            AutomaticHelperAttemptBlock.NONE,
            automaticHelperAttemptBlock(attemptsToday = 0, nextRetryAtMs = 0L, nowMs = 10_000L),
        )
    }

    @Test
    fun recoveryMustBeConfirmedArmedForAutomaticAttempt() {
        val base = HelperUpdateGates(
            linkReady = true,
            voiceSessionActive = false,
            notificationReplyWindowActive = false,
            wirelessBootstrapInFlight = false,
            wifiRadioEnabled = true,
            glassesRecoveryArmed = true,
        )

        assertEquals(HelperUpdateGateBlock.NONE, blockedHelperUpdateGate(base))
        assertEquals(
            HelperUpdateGateBlock.RECOVERY_NOT_ARMED,
            blockedHelperUpdateGate(base.copy(glassesRecoveryArmed = false)),
        )
        assertFalse(glassesRecoveryArmed(false, confirmationLive = true))
        assertEquals(
            HelperUpdateGateBlock.RECOVERY_NOT_ARMED,
            blockedHelperUpdateGate(
                base.copy(
                    glassesRecoveryArmed = glassesRecoveryArmed(
                        confirmedArmed = null,
                        confirmationLive = true,
                    ),
                ),
            ),
        )
        assertEquals(
            HelperUpdateGateBlock.RECOVERY_NOT_ARMED,
            blockedHelperUpdateGate(
                base.copy(
                    glassesRecoveryArmed = glassesRecoveryArmed(
                        confirmedArmed = true,
                        confirmationLive = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun explicitAttemptBypassesOnlyRecoveryArmGate() {
        val unarmed = HelperUpdateGates(
            linkReady = true,
            voiceSessionActive = false,
            notificationReplyWindowActive = false,
            wirelessBootstrapInFlight = false,
            wifiRadioEnabled = true,
            glassesRecoveryArmed = false,
        )

        assertEquals(
            HelperUpdateGateBlock.NONE,
            blockedHelperUpdateGate(unarmed, explicitAttempt = true),
        )
        assertEquals(
            HelperUpdateGateBlock.VOICE_SESSION_ACTIVE,
            blockedHelperUpdateGate(
                unarmed.copy(voiceSessionActive = true),
                explicitAttempt = true,
            ),
        )
    }

    @Test
    fun recoveryArmGateKeepsUpdateAvailableWithExactText() {
        val available = GlassesHelperUpdateSnapshot(
            phase = GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
            displayText = "available",
            bundledVersionName = "0.1.11",
            bundledVersionCode = 25L,
            installedVersionCode = 24L,
        )

        val blocked = blockedHelperUpdateSnapshot(
            available,
            HelperUpdateGateBlock.RECOVERY_NOT_ARMED,
        )

        assertEquals(GlassesHelperUpdatePhase.UPDATE_AVAILABLE, blocked.phase)
        assertEquals(
            "Glasses app update waits for recovery arm — tap Bootstrap",
            blocked.displayText,
        )
    }

    @Test
    fun automaticAttemptsUseOneFiveAndThirtyMinuteBackoff() {
        assertEquals(60_000L, helperRetryDelayMs(1))
        assertEquals(5L * 60L * 1_000L, helperRetryDelayMs(2))
        assertEquals(30L * 60L * 1_000L, helperRetryDelayMs(3))
        assertEquals(30L * 60L * 1_000L, helperRetryDelayMs(8))
    }

    @Test
    fun backoffAndDailyAttemptCapBlockAutomaticRetry() {
        assertEquals(
            AutomaticHelperAttemptBlock.BACKOFF,
            automaticHelperAttemptBlock(
                attemptsToday = 1,
                nextRetryAtMs = 60_001L,
                nowMs = 60_000L,
            ),
        )
        assertEquals(
            AutomaticHelperAttemptBlock.NONE,
            automaticHelperAttemptBlock(
                attemptsToday = 2,
                nextRetryAtMs = 60_000L,
                nowMs = 60_000L,
            ),
        )
        assertEquals(
            AutomaticHelperAttemptBlock.DAILY_CAP,
            automaticHelperAttemptBlock(
                attemptsToday = 3,
                nextRetryAtMs = 0L,
                nowMs = 60_000L,
            ),
        )
    }

    @Test
    fun verificationSucceedsOnlyForExactTargetReport() {
        val updating = nextHelperVerificationPhase(
            GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
            HelperVerificationEvent.ATTEMPT_STARTED,
        )
        val verifying = nextHelperVerificationPhase(
            updating,
            HelperVerificationEvent.INSTALL_SUCCEEDED,
        )

        assertEquals(GlassesHelperUpdatePhase.UPDATING, updating)
        assertEquals(GlassesHelperUpdatePhase.VERIFYING, verifying)
        assertFalse(helperVersionVerifiesTarget(reportedVersionCode = 23L, targetVersionCode = 24L))
        assertFalse(helperVersionVerifiesTarget(reportedVersionCode = 25L, targetVersionCode = 24L))
        assertTrue(helperVersionVerifiesTarget(reportedVersionCode = 24L, targetVersionCode = 24L))
        assertEquals(
            GlassesHelperUpdatePhase.UPDATED,
            nextHelperVerificationPhase(verifying, HelperVerificationEvent.TARGET_VERSION_REPORTED),
        )
    }

    @Test
    fun verificationTimeoutTransitionsToFailed() {
        assertEquals(
            GlassesHelperUpdatePhase.FAILED,
            nextHelperVerificationPhase(
                GlassesHelperUpdatePhase.VERIFYING,
                HelperVerificationEvent.VERIFICATION_TIMED_OUT,
            ),
        )
    }
}
