package com.anezium.rokidrelay.phone

import android.content.Context
import org.json.JSONObject
import java.io.File

object SelfArmProvisioner {
    data class Provision(
        val json: JSONObject,
        val keyPresent: Boolean,
    )

    fun buildProvision(context: Context): Provision {
        val appContext = context.applicationContext
        val privateKey = privateKeyFile(appContext).takeIf { it.exists() }?.readText().orEmpty()
        val publicKey = publicKeyFile(appContext).takeIf { it.exists() }?.readText().orEmpty()
        return buildProvision(
            watchdogScript = readWatchdogScript(appContext),
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }

    internal fun buildProvision(
        watchdogScript: String,
        privateKey: String = "",
        publicKey: String = "",
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
            .apply()
    }

    fun markDisableRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, true)
            .apply()
    }

    fun markDisabled(context: Context) {
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)
            .putBoolean(Constants.PREF_SELF_ARM_KEY_PRESENT, false)
            .putBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)
            .apply()
    }

    fun provisioned(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SELF_ARM_PROVISIONED, false)

    fun keyPresent(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SELF_ARM_KEY_PRESENT, false)

    fun disablePending(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_SELF_ARM_DISABLE_PENDING, false)

    fun localKeyAvailable(context: Context): Boolean =
        privateKeyFile(context.applicationContext).exists() &&
            publicKeyFile(context.applicationContext).exists()

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
