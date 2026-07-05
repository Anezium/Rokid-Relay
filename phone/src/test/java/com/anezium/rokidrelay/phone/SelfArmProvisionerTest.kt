package com.anezium.rokidrelay.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import com.anezium.rokidrelay.phone.selfarm.adb.AdbBridgeClient
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SelfArmProvisionerTest {
    @Test
    fun provisionPayloadDoesNotInventAdbKeys() {
        val provision = SelfArmProvisioner.buildProvision(
            watchdogScript = "#!/system/bin/sh\nPKG=\"${Constants.CLIENT_PACKAGE}\"\n",
        )

        assertEquals("self_arm_provision", provision.json.getString("type"))
        assertEquals(Constants.CLIENT_ACCESSIBILITY_SERVICE, provision.json.getString("accessibilityService"))
        assertEquals(Constants.SELF_ARM_WATCHDOG_VERSION, provision.json.getString("watchdogVersion"))
        assertFalse(provision.keyPresent)
        assertFalse(provision.json.has("adbPrivateKey"))
        assertFalse(provision.json.has("adbPublicKey"))
        assertFalse(provision.json.getBoolean("adbEnrollmentAllowed"))
        assertTrue(provision.json.getString("watchdogScript").contains(Constants.CLIENT_PACKAGE))
    }

    @Test
    fun contextProvisionGeneratesPhoneAdbKey() {
        val context = RuntimeEnvironment.getApplication() as Context

        val key = SelfArmProvisioner.ensureKeyMaterial(context)

        assertTrue(key.privateKeyPem.contains("BEGIN PRIVATE KEY"))
        assertTrue(key.publicKey.contains("rokid-relay@phone"))
        assertTrue(SelfArmProvisioner.localKeyAvailable(context))
    }

    @Test
    fun localKeyAvailableDoesNotGeneratePhoneAdbKey() {
        val context = RuntimeEnvironment.getApplication() as Context
        File(context.filesDir, "self-arm").deleteRecursively()

        assertFalse(SelfArmProvisioner.localKeyAvailable(context))
        assertFalse(File(context.filesDir, "self-arm").exists())
    }

    @Test
    fun disablePayloadUsesSelfArmDisableType() {
        assertEquals("self_arm_disable", SelfArmProvisioner.disablePayload().getString("type"))
    }

    @Test
    fun markDisabledClearsPendingDisable() {
        val context = RuntimeEnvironment.getApplication() as Context

        SelfArmProvisioner.markDisableRequested(context)
        assertTrue(SelfArmProvisioner.disablePending(context))

        SelfArmProvisioner.markDisabled(context)

        assertFalse(SelfArmProvisioner.disablePending(context))
        assertFalse(SelfArmProvisioner.provisioned(context))
        assertFalse(SelfArmProvisioner.keyPresent(context))
    }

    @Test
    fun legacyPendingDisableWithoutProvisionIsCleared() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, true)
            .apply()

        assertFalse(SelfArmProvisioner.disablePending(context))
        assertFalse(
            context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false),
        )
    }

    @Test
    fun newPendingDisableKeepsPendingState() {
        val context = RuntimeEnvironment.getApplication() as Context

        SelfArmProvisioner.markDisableRequested(context)

        assertTrue(SelfArmProvisioner.disablePending(context))
    }

    @Test
    fun wirelessBootstrapStateTracksCompletionSeparatelyFromProvisioning() {
        val context = RuntimeEnvironment.getApplication() as Context

        SelfArmProvisioner.markWirelessBootstrapRequested(context, "Opening Wireless Debugging")
        var state = SelfArmProvisioner.wirelessBootstrap(context)

        assertFalse(state.complete)
        assertTrue(state.inProgress)
        assertEquals("Opening Wireless Debugging", state.status)

        SelfArmProvisioner.markWirelessBootstrapComplete(context, "192.168.1.84", 33093)
        state = SelfArmProvisioner.wirelessBootstrap(context)

        assertTrue(state.complete)
        assertFalse(state.inProgress)
        assertFalse(SelfArmProvisioner.provisioned(context))
        assertEquals("192.168.1.84", state.host)
        assertEquals(33093, state.connectPort)
        assertEquals("", state.lastError)
    }

    @Test
    fun wirelessBootstrapErrorSurvivesLaterGlassesStatusUntilSuccess() {
        val context = RuntimeEnvironment.getApplication() as Context

        SelfArmProvisioner.markWirelessBootstrapFailed(
            context,
            status = "Wireless ADB bootstrap failed: KADB configure failed",
            error = "KADB configure failed",
        )
        SelfArmProvisioner.markWirelessBootstrapRequested(context, "wireless setup timeout")
        var state = SelfArmProvisioner.wirelessBootstrap(context)

        assertEquals("wireless setup timeout", state.status)
        assertEquals("KADB configure failed", state.lastError)

        SelfArmProvisioner.markWirelessBootstrapComplete(context, "192.168.1.84", 33093)
        state = SelfArmProvisioner.wirelessBootstrap(context)

        assertEquals("", state.lastError)
    }

    @Test
    fun wirelessBootstrapCommandGrantsSecureSettingsAndFailsOnlyIfNotGranted() {
        val command = AdbBridgeClient.buildBootstrapCommand("ADB_PUBLIC_KEY rokid-relay@phone")

        assertTrue(command.contains("pm grant ${Constants.CLIENT_PACKAGE} android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(command.contains("settings put global adb_wifi_enabled 1"))
        assertTrue(command.contains("echo ROKID_RELAY_WIRELESS_BOOTSTRAP grant="))
        assertTrue(command.contains("exit 1"))
    }

    @Test
    fun wirelessBootstrapCommandNeverDisruptsTheWirelessDebuggingSession() {
        // Restarting adbd or re-persisting its port drops the very Wireless Debugging session we
        // are running the command over, so the phone never reads the result. Trusting the key is
        // impossible from the shell uid. None of these must appear in the command.
        val command = AdbBridgeClient.buildBootstrapCommand("ADB_PUBLIC_KEY rokid-relay@phone")

        assertFalse(command.contains("ctl.restart adbd"))
        assertFalse(command.contains("setprop persist.adb.tcp.port"))
        assertFalse(command.contains("/data/misc/adb/adb_keys"))
        assertFalse(command.contains("set -e"))
    }
}
