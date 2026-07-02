package com.anezium.rokidrelay.glasses

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SelfArmControllerTest {
    @Test
    fun relayServiceIsOnlyAddedWhenMissing() {
        assertEquals(
            Constants.ACCESSIBILITY_SERVICE,
            SelfArmController.servicesWithRelayService(null),
        )
        assertEquals(
            "other.service/.A:${Constants.ACCESSIBILITY_SERVICE}",
            SelfArmController.servicesWithRelayService("other.service/.A"),
        )
        assertEquals(
            "other.service/.A:${Constants.ACCESSIBILITY_SERVICE}",
            SelfArmController.servicesWithRelayService("other.service/.A:${Constants.ACCESSIBILITY_SERVICE}"),
        )
    }

    @Test
    fun installCommandDeploysWatchdogAndConfiguresLoopbackAdb() {
        val command = SelfArmController.buildInstallCommand("#!/system/bin/sh\necho ok\n", "start")

        assertTrue(command.contains("setprop persist.adb.tcp.port 5555"))
        assertTrue(command.contains("setprop service.adb.tcp.port 5555"))
        assertTrue(command.contains("cat > '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}'"))
        assertTrue(command.contains("sh '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' start"))
    }

    @Test
    fun disableWithoutProvisionedKeyCompletesDisabled() {
        val context = RuntimeEnvironment.getApplication() as Context
        val latch = CountDownLatch(1)
        var disabled = false

        SelfArmController.disable(context) {
            disabled = it
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(disabled)
        assertFalse(SelfArmController.hasProvisionedKey(context))
    }

    @Test
    fun keylessProvisionDeletesStaleKeyMaterial() {
        val context = RuntimeEnvironment.getApplication() as Context
        val dir = File(context.filesDir, "self-arm").apply { mkdirs() }
        File(dir, "adbkey").writeText("old-private")
        File(dir, "adbkey.pub").writeText("old-public")
        assertTrue(SelfArmController.hasProvisionedKey(context))

        SelfArmController.allowProvisionFromForeground(context)
        val provision = SelfArmController.provision(
            context,
            JSONObject()
                .put("source", "phone")
                .put("packageName", Constants.CLIENT_PACKAGE)
                .put("accessibilityService", Constants.ACCESSIBILITY_SERVICE)
                .put("watchdogVersion", Constants.SELF_ARM_WATCHDOG_VERSION)
                .put("watchdogScript", "#!/system/bin/sh\necho watchdog\n"),
        )

        assertTrue(provision.accepted)
        assertFalse(SelfArmController.hasProvisionedKey(context))
    }

    @Test
    fun installCommandRequiresVerifiedWatchdogStart() {
        assertTrue(
            SelfArmController.installCommandSucceeded(
                "ROKID_RELAY_INSTALL_RESULT watchdog=1 persist=5555 service=5555\n",
            ),
        )
        assertFalse(
            SelfArmController.installCommandSucceeded(
                "ROKID_RELAY_INSTALL_RESULT watchdog=0 persist=5555 service=5555\n",
            ),
        )
        assertFalse(
            SelfArmController.installCommandSucceeded(
                "ROKID_RELAY_INSTALL_RESULT watchdog=1 persist=-1 service=-1\n",
            ),
        )
        assertFalse(
            SelfArmController.installCommandSucceeded(
                "ROKID_RELAY_INSTALL_RESULT watchdog=1 persist=5555 service=-1\n",
            ),
        )
        assertFalse(SelfArmController.installCommandSucceeded("command delivered without sentinel"))
    }

    @Test
    fun disableCommandRequiresVerifiedRemoteStop() {
        assertTrue(
            SelfArmController.disableCommandSucceeded(
                "noise\nROKID_RELAY_DISABLE_RESULT watchdog=1 persist=-1 service=-1\n",
            ),
        )
        assertFalse(
            SelfArmController.disableCommandSucceeded(
                "ROKID_RELAY_DISABLE_RESULT watchdog=0 persist=-1 service=-1\n",
            ),
        )
        assertFalse(
            SelfArmController.disableCommandSucceeded(
                "ROKID_RELAY_DISABLE_RESULT watchdog=1 persist=5555 service=5555\n",
            ),
        )
        assertFalse(
            SelfArmController.disableCommandSucceeded(
                "ROKID_RELAY_DISABLE_RESULT watchdog=1 persist=-1 service=5555\n",
            ),
        )
        assertFalse(SelfArmController.disableCommandSucceeded("command delivered without sentinel"))
    }
}
