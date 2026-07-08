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
        )

        assertEquals("Armed on the glasses — they recover on their own", row.value)
        assertEquals(SelfArmRecoveryTone.Ready, row.tone)
        assertEquals("Re-arm", row.actionLabel)
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

    private fun rowState(
        selfArmProvisioned: Boolean = false,
        selfArmDisablePending: Boolean = false,
        selfArmWireless: SelfArmProvisioner.WirelessBootstrap = wireless(),
        relayEnabled: Boolean = false,
        glassesState: SelfArmProvisioner.GlassesState? = null,
    ): SelfArmRecoveryRowState =
        selfArmRecoveryRowState(
            selfArmProvisioned = selfArmProvisioned,
            selfArmDisablePending = selfArmDisablePending,
            selfArmWireless = selfArmWireless,
            relayEnabled = relayEnabled,
            glassesState = glassesState,
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
    ): SelfArmProvisioner.GlassesState =
        SelfArmProvisioner.GlassesState(
            armed = armed,
            keyPresent = keyPresent,
            writeSecureGranted = writeSecureGranted,
            accessibilityEnabled = accessibilityEnabled,
            helperVersionCode = 22,
            receivedAtWallClockMs = 1234L,
        )

    private companion object {
        const val RELAY_SETUP_MESSAGE = "phone-local setup message"
    }
}
