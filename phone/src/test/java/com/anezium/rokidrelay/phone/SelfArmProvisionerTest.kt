package com.anezium.rokidrelay.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

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
        assertTrue(provision.json.getString("watchdogScript").contains(Constants.CLIENT_PACKAGE))
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
}
