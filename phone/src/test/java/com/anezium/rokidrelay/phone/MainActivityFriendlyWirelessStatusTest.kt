package com.anezium.rokidrelay.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityFriendlyWirelessStatusTest {
    private fun friendly(raw: String): String {
        val method = MainActivity::class.java.getDeclaredMethod("friendlyWirelessStatus", String::class.java)
        method.isAccessible = true
        return method.invoke(MainActivity(), raw) as String
    }

    @Test
    fun friendlyWirelessStatusUsesFirstMatchingRule() {
        assertEquals("Glasses recovery is ready", friendly("bootstrap ready but could not reach"))
    }

    @Test
    fun friendlyWirelessStatusKeepsSameNetworkErrors() {
        val message = "Connect this phone to Wi-Fi first — the same network as your glasses — then tap Bootstrap."

        assertEquals(message, friendly(message))
    }

    @Test
    fun friendlyWirelessStatusMapsProgressStates() {
        assertEquals("Pairing with the glasses…", friendly("Pairing with Wireless Debugging"))
        assertEquals(
            "Reading the pairing code from the glasses…",
            friendly("waiting_for_pairing"),
        )
        assertEquals("Setting up the glasses…", friendly("wireless_debugging open"))
    }

    @Test
    fun friendlyWirelessStatusMapsRetryableFailures() {
        assertEquals(
            "Couldn't finish on the glasses. Keep the glasses on and tap Bootstrap again.",
            friendly("manual_step required"),
        )
        assertEquals(
            "The pairing code expired. Tap Bootstrap to try again.",
            friendly("Pairing code expired"),
        )
        assertEquals("KADB configure failed", friendly("Wireless ADB bootstrap failed: KADB configure failed"))
        assertEquals(
            "Bootstrap failed. Keep both on the same Wi-Fi and tap Bootstrap again.",
            friendly("Wireless ADB bootstrap failed"),
        )
    }

    @Test
    fun friendlyWirelessStatusFallsBackToGenericSetup() {
        assertEquals("Setting up glasses recovery…", friendly(""))
        assertEquals("Setting up glasses recovery…", friendly("opening"))
    }
}
