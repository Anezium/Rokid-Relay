package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfArmRecoveryRowStateTest {
    @Test
    fun glassesConfirmedStateBeatsLocalWaitingStates() {
        val row = rowState(
            selfArmProvisioned = false,
            selfArmWireless = wireless(inProgress = true, lastError = "still waiting"),
            relayEnabled = true,
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = false,
            ),
            glassesStateLive = true,
        )

        assertEquals("Armed on the glasses — they recover on their own", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun staleGlassesConfirmedStateShowsAge() {
        val row = rowState(
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
                receivedAtWallClockMs = NOW_MS - 2L * 60L * 60L * 1_000L,
            ),
            glassesStateLive = false,
        )

        assertEquals("Armed on the glasses — last confirmed 2h ago", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun staleGlassesConfirmedStateShowsJustNowForRecentReport() {
        val row = rowState(
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
                receivedAtWallClockMs = NOW_MS - 30_000L,
            ),
            glassesStateLive = false,
        )

        assertEquals("Armed on the glasses — last confirmed just now", row.value)
    }

    @Test
    fun staleGlassesConfirmedStateShowsJustNowWhenReceivedTimeMissing() {
        val row = rowState(
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
                receivedAtWallClockMs = 0L,
            ),
            glassesStateLive = false,
        )

        assertEquals("Armed on the glasses — last confirmed just now", row.value)
    }

    @Test
    fun grantMissingBrokenReportShowsRearmPrompt() {
        val row = rowState(
            selfArmProvisioned = true,
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = false,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("Glasses report settings grant missing — tap Re-arm", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun keyMissingBrokenReportShowsRearmPrompt() {
        val row = rowState(
            selfArmProvisioned = true,
            glassesState = glasses(
                armed = true,
                keyPresent = false,
                writeSecureGranted = true,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("Glasses report recovery key missing — tap Re-arm", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun keyAndGrantMissingBrokenReportShowsRearmPrompt() {
        val row = rowState(
            selfArmProvisioned = true,
            glassesState = glasses(
                armed = true,
                keyPresent = false,
                writeSecureGranted = false,
                accessibilityEnabled = true,
            ),
        )

        assertEquals(
            "Glasses report recovery key and settings grant missing — tap Re-arm",
            row.value,
        )
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun brokenReportDoesNotRequirePhoneLocalProvisionedState() {
        val row = rowState(
            selfArmProvisioned = false,
            glassesState = glasses(
                armed = true,
                keyPresent = false,
                writeSecureGranted = true,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("Glasses report recovery key missing — tap Re-arm", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
    }

    @Test
    fun brokenReportYieldsToWirelessBootstrapInProgress() {
        val row = rowState(
            selfArmProvisioned = true,
            selfArmWireless = wireless(inProgress = true, status = "opening_pairing"),
            glassesState = glasses(
                armed = true,
                keyPresent = false,
                writeSecureGranted = false,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("friendly:opening_pairing", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
    }

    @Test
    fun disablePendingOutranksBrokenReport() {
        val row = rowState(
            selfArmDisablePending = true,
            glassesState = glasses(
                armed = true,
                keyPresent = false,
                writeSecureGranted = false,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("Disable pending", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
    }

    @Test
    fun disablePendingOutranksGlassesConfirmedState() {
        val row = rowState(
            selfArmDisablePending = true,
            glassesState = glasses(
                armed = true,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
            ),
            glassesStateLive = true,
        )

        assertEquals("Disable pending", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
    }

    @Test
    fun glassesDisarmedReportBeatsPhoneLocalProvisionedState() {
        val row = rowState(
            selfArmProvisioned = true,
            glassesState = glasses(
                armed = false,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("Glasses report recovery disarmed — tap Re-arm", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun disarmedReportYieldsToWirelessBootstrapInProgress() {
        val row = rowState(
            selfArmProvisioned = true,
            selfArmWireless = wireless(inProgress = true, status = "pairing_wireless"),
            glassesState = glasses(
                armed = false,
                keyPresent = true,
                writeSecureGranted = true,
                accessibilityEnabled = true,
            ),
        )

        assertEquals("friendly:pairing_wireless", row.value)
        assertEquals(SelfArmRecoveryTone.Waiting, row.tone)
    }

    @Test
    fun absentGlassesReportKeepsPhoneLocalBehavior() {
        val provisioned = rowState(selfArmProvisioned = true, glassesState = null)
        assertEquals("Recovery armed", provisioned.value)
        assertEquals(SelfArmRecoveryTone.Ready, provisioned.tone)
        assertEquals("Re-arm", provisioned.actionLabel)

        val bootstrapNeeded = rowState(relayEnabled = true, glassesState = null)
        assertEquals(RELAY_SETUP_MESSAGE, bootstrapNeeded.value)
        assertEquals(SelfArmRecoveryTone.Waiting, bootstrapNeeded.tone)
        assertEquals("Bootstrap", bootstrapNeeded.actionLabel)
    }

    @Test
    fun provisionedStaysGreenWhileRelayEnabled() {
        val row = rowState(
            selfArmProvisioned = true,
            relayEnabled = true,
            glassesState = null,
        )

        assertEquals("Recovery armed", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
        assertEquals("Re-arm", row.actionLabel)
    }

    @Test
    fun provisionedOutranksCompletedBootstrapStatus() {
        val row = rowState(
            selfArmProvisioned = true,
            selfArmWireless = wireless(complete = true, status = "Wireless ADB bootstrap complete"),
            glassesState = null,
        )

        assertEquals("Recovery armed", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
    }

    @Test
    fun provisionedOutranksStaleBootstrapError() {
        val row = rowState(
            selfArmProvisioned = true,
            selfArmWireless = wireless(lastError = "old failure"),
            glassesState = null,
        )

        assertEquals("Recovery armed", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
    }

    private fun rowState(
        selfArmProvisioned: Boolean = false,
        selfArmDisablePending: Boolean = false,
        selfArmWireless: SelfArmProvisioner.WirelessBootstrap = wireless(),
        relayEnabled: Boolean = false,
        glassesState: SelfArmProvisioner.GlassesState? = null,
        glassesStateLive: Boolean = true,
        nowWallClockMs: Long = NOW_MS,
    ): SelfArmRecoveryRowState =
        selfArmRecoveryRowState(
            selfArmProvisioned = selfArmProvisioned,
            selfArmDisablePending = selfArmDisablePending,
            selfArmWireless = selfArmWireless,
            relayEnabled = relayEnabled,
            glassesState = glassesState,
            glassesStateLive = glassesStateLive,
            nowWallClockMs = nowWallClockMs,
            relayEnabledSetupMessage = RELAY_SETUP_MESSAGE,
            friendlyWirelessStatus = { raw -> "friendly:$raw" },
        )

    private fun wireless(
        complete: Boolean = false,
        inProgress: Boolean = false,
        status: String = "",
        lastError: String = "",
    ): SelfArmProvisioner.WirelessBootstrap =
        SelfArmProvisioner.WirelessBootstrap(
            complete = complete,
            inProgress = inProgress,
            status = status,
            host = "",
            pairPort = 0,
            connectPort = 0,
            lastError = lastError,
        )

    private fun glasses(
        armed: Boolean,
        keyPresent: Boolean,
        writeSecureGranted: Boolean,
        accessibilityEnabled: Boolean,
        receivedAtWallClockMs: Long = NOW_MS,
    ): SelfArmProvisioner.GlassesState =
        SelfArmProvisioner.GlassesState(
            armed = armed,
            keyPresent = keyPresent,
            writeSecureGranted = writeSecureGranted,
            accessibilityEnabled = accessibilityEnabled,
            helperVersionCode = 22,
            receivedAtWallClockMs = receivedAtWallClockMs,
        )

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val RELAY_SETUP_MESSAGE = "phone-local setup message"
    }
}
