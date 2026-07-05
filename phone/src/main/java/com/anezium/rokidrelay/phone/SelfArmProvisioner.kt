package com.anezium.rokidrelay.phone

import android.content.Context
import org.json.JSONObject
import java.io.File

object SelfArmProvisioner {
    private const val PREF_DISABLE_REQUESTED_AT_MS = "self_arm_disable_requested_at_ms"
    private const val PREF_WIRELESS_BOOTSTRAPPED = "self_arm_wireless_bootstrapped"
    private const val PREF_WIRELESS_BOOTSTRAP_STATUS = "self_arm_wireless_bootstrap_status"
    private const val PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS = "self_arm_wireless_bootstrap_in_progress"
    private const val PREF_WIRELESS_BOOTSTRAP_HOST = "self_arm_wireless_bootstrap_host"
    private const val PREF_WIRELESS_BOOTSTRAP_PAIR_PORT = "self_arm_wireless_bootstrap_pair_port"
    private const val PREF_WIRELESS_BOOTSTRAP_CONNECT_PORT = "self_arm_wireless_bootstrap_connect_port"
    private const val PREF_WIRELESS_BOOTSTRAP_LAST_ERROR = "self_arm_wireless_bootstrap_last_error"

    data class Provision(
        val json: JSONObject,
        val keyPresent: Boolean,
    )

    data class WirelessBootstrap(
        val complete: Boolean,
        val inProgress: Boolean,
        val status: String,
        val host: String,
        val pairPort: Int,
        val connectPort: Int,
        val lastError: String,
    )

    fun buildProvision(context: Context): Provision {
        val appContext = context.applicationContext
        val key = ensureKeyMaterial(appContext)
        return buildProvision(
            watchdogScript = readWatchdogScript(appContext),
            privateKey = key.privateKeyPem,
            publicKey = key.publicKey,
            enrollmentAllowed = true,
        )
    }

    internal fun buildProvision(
        watchdogScript: String,
        privateKey: String = "",
        publicKey: String = "",
        enrollmentAllowed: Boolean = false,
    ): Provision {
        val keyPresent = privateKey.isNotBlank() && publicKey.isNotBlank()
        val json = JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", "self_arm_provision")
            .put("source", "phone")
            .put("packageName", Constants.CLIENT_PACKAGE)
            .put("accessibilityService", Constants.CLIENT_ACCESSIBILITY_SERVICE)
            .put("adbTcpPort", 5555)
            .put("watchdogVersion", Constants.SELF_ARM_WATCHDOG_VERSION)
            .put("watchdogScript", watchdogScript)
            .put("adbKeyProvisioned", keyPresent)
            .put("adbEnrollmentAllowed", keyPresent && enrollmentAllowed)
        if (keyPresent) {
            json.put("adbPrivateKey", privateKey)
            json.put("adbPublicKey", publicKey)
        }
        return Provision(json, keyPresent)
    }

    fun disablePayload(): JSONObject =
        JSONObject()
            .put("version", Constants.PROTOCOL_VERSION)
            .put("type", "self_arm_disable")
            .put("source", "phone")

    fun markProvisioned(context: Context, keyPresent: Boolean) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, true)
            .putBoolean(Constants.PREF_SELF_ARM_KEY_PRESENT, keyPresent)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)
            .remove(PREF_DISABLE_REQUESTED_AT_MS)
            .apply()
    }

    fun markDisableRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, true)
            .putLong(PREF_DISABLE_REQUESTED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun markDisabled(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)
            .putBoolean(Constants.PREF_SELF_ARM_KEY_PRESENT, false)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)
            .remove(PREF_DISABLE_REQUESTED_AT_MS)
            .apply()
    }

    fun markWirelessBootstrapRequested(context: Context, status: String = "Opening Wireless Debugging") {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS, true)
            .putString(PREF_WIRELESS_BOOTSTRAP_STATUS, status)
            .apply()
    }

    fun markWirelessPairingDiscovered(
        context: Context,
        host: String,
        pairPort: Int,
        connectPort: Int,
        status: String = "Pairing code ready",
    ) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS, true)
            .putString(PREF_WIRELESS_BOOTSTRAP_STATUS, status)
            .putString(PREF_WIRELESS_BOOTSTRAP_HOST, host)
            .putInt(PREF_WIRELESS_BOOTSTRAP_PAIR_PORT, pairPort)
            .putInt(PREF_WIRELESS_BOOTSTRAP_CONNECT_PORT, connectPort)
            .apply()
    }

    fun markWirelessBootstrapComplete(
        context: Context,
        host: String,
        connectPort: Int,
        status: String = "Wireless ADB bootstrap complete",
    ) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_WIRELESS_BOOTSTRAPPED, true)
            .putBoolean(PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS, false)
            .putString(PREF_WIRELESS_BOOTSTRAP_STATUS, status)
            .putString(PREF_WIRELESS_BOOTSTRAP_HOST, host)
            .putInt(PREF_WIRELESS_BOOTSTRAP_CONNECT_PORT, connectPort)
            .remove(PREF_WIRELESS_BOOTSTRAP_LAST_ERROR)
            .apply()
    }

    fun markWirelessBootstrapFailed(context: Context, status: String, error: String = status) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS, false)
            .putString(PREF_WIRELESS_BOOTSTRAP_STATUS, status)
            .putString(PREF_WIRELESS_BOOTSTRAP_LAST_ERROR, error)
            .apply()
    }

    fun provisioned(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)

    fun wirelessBootstrapped(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_WIRELESS_BOOTSTRAPPED, false)

    fun wirelessBootstrap(context: Context): WirelessBootstrap {
        val prefs = context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        return WirelessBootstrap(
            complete = prefs.getBoolean(PREF_WIRELESS_BOOTSTRAPPED, false),
            inProgress = prefs.getBoolean(PREF_WIRELESS_BOOTSTRAP_IN_PROGRESS, false),
            status = prefs.getString(PREF_WIRELESS_BOOTSTRAP_STATUS, "").orEmpty(),
            host = prefs.getString(PREF_WIRELESS_BOOTSTRAP_HOST, "").orEmpty(),
            pairPort = prefs.getInt(PREF_WIRELESS_BOOTSTRAP_PAIR_PORT, 0),
            connectPort = prefs.getInt(PREF_WIRELESS_BOOTSTRAP_CONNECT_PORT, 0),
            lastError = prefs.getString(PREF_WIRELESS_BOOTSTRAP_LAST_ERROR, "").orEmpty(),
        )
    }

    fun keyPresent(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SELF_ARM_KEY_PRESENT, false)

    fun disablePending(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)
        if (!pending) return false
        if (prefs.contains(PREF_DISABLE_REQUESTED_AT_MS)) return true
        if (prefs.getBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)) return true
        prefs.edit()
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)
            .remove(PREF_DISABLE_REQUESTED_AT_MS)
            .apply()
        return false
    }

    fun localKeyAvailable(context: Context): Boolean =
        runCatching {
            privateKeyFile(context.applicationContext).readText().isNotBlank() &&
                publicKeyFile(context.applicationContext).readText().isNotBlank()
        }.getOrDefault(false)

    fun ensureWirelessBootstrapPublicKey(context: Context): String =
        ensureKeyMaterial(context.applicationContext).publicKey

    internal fun ensureKeyMaterial(context: Context): AdbKeyGenerator.GeneratedKey {
        val privateFile = privateKeyFile(context)
        val publicFile = publicKeyFile(context)
        val privateKey = privateFile.takeIf { it.exists() }?.readText().orEmpty()
        val publicKey = publicFile.takeIf { it.exists() }?.readText().orEmpty()
        if (privateKey.isNotBlank() && publicKey.isNotBlank()) {
            return AdbKeyGenerator.GeneratedKey(privateKey, publicKey)
        }

        val generated = AdbKeyGenerator.generate()
        val dir = selfArmDir(context)
        if (!dir.exists()) dir.mkdirs()
        privateFile.writeText(generated.privateKeyPem)
        publicFile.writeText(generated.publicKey)
        privateFile.setReadable(true, true)
        privateFile.setWritable(true, true)
        publicFile.setReadable(true, true)
        publicFile.setWritable(true, true)
        return generated
    }

    private fun readWatchdogScript(context: Context): String =
        context.assets.open(Constants.SELF_ARM_WATCHDOG_ASSET)
            .bufferedReader()
            .use { it.readText() }

    private fun privateKeyFile(context: Context): File =
        File(selfArmDir(context), "adbkey")

    private fun publicKeyFile(context: Context): File =
        File(selfArmDir(context), "adbkey.pub")

    private fun selfArmDir(context: Context): File =
        File(context.applicationContext.filesDir, "self-arm")
}
