package com.anezium.rokidrelay.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File

object SelfArmController {
    private const val TAG = "RokidRelaySelfArm"
    private const val PREFS = "rokid_relay_self_arm"
    private const val PREF_ARMED = "armed"
    private const val PREF_WATCHDOG_VERSION = "watchdog_version"
    private const val PREF_KEY_PROVISIONED = "adb_key_provisioned"
    private const val PREF_STOP_PENDING = "watchdog_stop_pending"
    private const val PREF_PROVISION_ALLOWED_UNTIL_MS = "provision_allowed_until_ms"
    private const val ADB_PORT = 5555
    private const val PROVISION_WINDOW_MS = 120_000L
    private const val INSTALL_SENTINEL = "ROKID_RELAY_INSTALL_RESULT"
    private const val DISABLE_SENTINEL = "ROKID_RELAY_DISABLE_RESULT"
    private const val WATCHDOG_PIDFILE = "/data/local/tmp/rokid-relay-a11y-watchdog.pid"

    data class ProvisionResult(
        val accepted: Boolean,
        val recoveryStarted: Boolean,
    )

    fun allowProvisionFromForeground(context: Context) {
        prefs(context.applicationContext).edit()
            .putLong(PREF_PROVISION_ALLOWED_UNTIL_MS, System.currentTimeMillis() + PROVISION_WINDOW_MS)
            .apply()
    }

    fun provision(context: Context, payload: JSONObject): ProvisionResult {
        val appContext = context.applicationContext
        if (!provisionAuthorized(appContext, payload)) {
            Log.w(TAG, "self-arm provision rejected: unauthorized payload or no foreground window")
            return ProvisionResult(accepted = false, recoveryStarted = false)
        }
        val script = payload.optString("watchdogScript").takeIf { it.isNotBlank() }
            ?: readAssetScript(appContext)
        val privateKey = payload.optString("adbPrivateKey").takeIf { it.isNotBlank() }
        val publicKey = payload.optString("adbPublicKey").takeIf { it.isNotBlank() }
        return runCatching {
            writeInternalWatchdog(appContext, script)
            if (!privateKey.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                writeKeyMaterial(appContext, privateKey, publicKey)
            } else {
                deleteKeyMaterial(appContext)
            }
            prefs(appContext).edit()
                .putBoolean(PREF_ARMED, true)
                .putString(PREF_WATCHDOG_VERSION, Constants.SELF_ARM_WATCHDOG_VERSION)
                .putBoolean(PREF_KEY_PROVISIONED, hasProvisionedKey(appContext))
                .putLong(PREF_PROVISION_ALLOWED_UNTIL_MS, 0L)
                .apply()
            Log.i(TAG, "self-arm provisioned key=${hasProvisionedKey(appContext)}")
            val recoveryStarted = runSelfArm(appContext, "provision")
            if (!recoveryStarted) {
                deleteKeyMaterial(appContext)
                prefs(appContext).edit()
                    .putBoolean(PREF_ARMED, false)
                    .putBoolean(PREF_KEY_PROVISIONED, false)
                    .putBoolean(PREF_STOP_PENDING, false)
                    .apply()
                Log.w(TAG, "self-arm provision rolled back: recovery did not start")
            }
            ProvisionResult(
                accepted = true,
                recoveryStarted = recoveryStarted,
            )
        }.onFailure {
            Log.w(TAG, "self-arm provision failed: ${it.message}")
        }.getOrDefault(ProvisionResult(accepted = false, recoveryStarted = false))
    }

    fun disable(context: Context, onComplete: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        prefs(appContext).edit()
            .putBoolean(PREF_ARMED, false)
            .putBoolean(PREF_STOP_PENDING, true)
            .apply()
        val keyMaterial = loadKeyMaterial(appContext)
        Thread {
            val stopped = stopWatchdogIfPossible(appContext, keyMaterial, "disable")
            onComplete(stopped)
        }.apply {
            name = "RokidRelaySelfArmDisable"
            start()
        }
    }

    fun maybeStart(context: Context, reason: String, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (!isArmed(appContext)) {
            if (stopPending(appContext)) {
                retryPendingStop(appContext, reason, onComplete)
            } else {
                onComplete?.invoke()
            }
            Log.d(TAG, "self-arm skipped reason=$reason armed=false")
            return
        }
        Thread {
            try {
                runCatching { runSelfArm(appContext, reason) }
                    .onFailure { Log.w(TAG, "self-arm failed reason=$reason: ${it.message}") }
            } finally {
                onComplete?.invoke()
            }
        }.apply {
            name = "RokidRelaySelfArm"
            start()
        }
    }

    fun isArmed(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(PREF_ARMED, false)

    fun hasProvisionedKey(context: Context): Boolean =
        privateKeyFile(context.applicationContext).exists() && publicKeyFile(context.applicationContext).exists()

    internal fun servicesWithRelayService(current: String?): String {
        val clean = current?.trim().orEmpty()
        if (clean.isBlank() || clean == "null") return Constants.ACCESSIBILITY_SERVICE
        val parts = clean.split(':').filter { it.isNotBlank() }
        if (Constants.ACCESSIBILITY_SERVICE in parts) return clean
        return (parts + Constants.ACCESSIBILITY_SERVICE).joinToString(":")
    }

    internal fun buildInstallCommand(script: String, action: String = "restart"): String =
        buildString {
            appendLine("setprop persist.adb.tcp.port $ADB_PORT")
            appendLine("setprop service.adb.tcp.port $ADB_PORT")
            appendLine("cat > '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' <<'ROKID_RELAY_WATCHDOG'")
            append(script.trimEnd())
            appendLine()
            appendLine("ROKID_RELAY_WATCHDOG")
            appendLine("chmod 700 '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}'")
            appendLine("sh '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' $action")
            appendLine("sleep 1")
            appendLine("WATCHDOG_STATUS=\"$(sh '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' status 2>/dev/null || true)\"")
            appendLine("case \"${'$'}WATCHDOG_STATUS\" in")
            appendLine("  *\"running=yes\"*) WATCHDOG_RUNNING=1 ;;")
            appendLine("  *) WATCHDOG_RUNNING=0 ;;")
            appendLine("esac")
            appendLine("PERSIST_PORT=\"$(getprop persist.adb.tcp.port)\"")
            appendLine("SERVICE_PORT=\"$(getprop service.adb.tcp.port)\"")
            appendLine("echo '$INSTALL_SENTINEL watchdog='\"${'$'}WATCHDOG_RUNNING\"' persist='\"${'$'}PERSIST_PORT\"' service='\"${'$'}SERVICE_PORT\"")
        }

    private fun provisionAuthorized(context: Context, payload: JSONObject): Boolean {
        if (payload.optString("source") != "phone") return false
        if (payload.optString("packageName") != Constants.CLIENT_PACKAGE) return false
        if (payload.optString("accessibilityService") != Constants.ACCESSIBILITY_SERVICE) return false
        if (payload.optString("watchdogVersion") != Constants.SELF_ARM_WATCHDOG_VERSION) return false
        val allowedUntil = prefs(context).getLong(PREF_PROVISION_ALLOWED_UNTIL_MS, 0L)
        return System.currentTimeMillis() <= allowedUntil
    }

    private fun runSelfArm(context: Context, reason: String): Boolean {
        val scriptFile = ensureInternalWatchdog(context)
        val repairedDirectly = repairAccessibilityDirect(context)
        val keyMaterial = loadKeyMaterial(context)
        if (keyMaterial == null) {
            Log.i(
                TAG,
                "self-arm reason=$reason directRepair=$repairedDirectly adbKey=false watchdog=${scriptFile.name}",
            )
            return repairedDirectly
        }
        val command = buildInstallCommand(scriptFile.readText(), action = "restart")
        val result = AdbLoopbackClient(port = ADB_PORT).runShell(command, keyMaterial)
        val watchdogStarted = result.authenticated && result.commandSent && installCommandSucceeded(result.output)
        if (watchdogStarted) {
            Log.i(TAG, "auth ADB OK and watchdog started reason=$reason directRepair=$repairedDirectly")
        } else {
            Log.w(
                TAG,
                "ADB self-arm incomplete reason=$reason connected=${result.connected} auth=${result.authenticated} sent=${result.commandSent} started=$watchdogStarted output=${result.output.take(120)}",
            )
        }
        return watchdogStarted || repairedDirectly
    }

    private fun retryPendingStop(context: Context, reason: String, onComplete: (() -> Unit)?) {
        val keyMaterial = loadKeyMaterial(context)
        Thread {
            try {
                stopWatchdogIfPossible(context, keyMaterial, "pending:$reason")
            } finally {
                onComplete?.invoke()
            }
        }.apply {
            name = "RokidRelaySelfArmStopPending"
            start()
        }
    }

    private fun stopWatchdogIfPossible(
        context: Context,
        keyMaterial: AdbKeyMaterial?,
        reason: String,
    ): Boolean {
        if (keyMaterial == null) {
            Log.i(TAG, "watchdog stop skipped reason=$reason: no provisioned key")
            deleteKeyMaterial(context)
            prefs(context).edit()
                .putBoolean(PREF_KEY_PROVISIONED, false)
                .putBoolean(PREF_STOP_PENDING, false)
                .apply()
            return true
        }
        val result = AdbLoopbackClient(port = ADB_PORT).runShell(
            buildDisableCommand(),
            keyMaterial,
        )
        val stopped = result.authenticated && result.commandSent && disableCommandSucceeded(result.output)
        Log.i(
            TAG,
            "watchdog stop reason=$reason connected=${result.connected} auth=${result.authenticated} sent=${result.commandSent} stopped=$stopped",
        )
        if (stopped) {
            deleteKeyMaterial(context)
            prefs(context).edit()
                .putBoolean(PREF_KEY_PROVISIONED, false)
                .putBoolean(PREF_STOP_PENDING, false)
                .apply()
        } else {
            prefs(context).edit()
                .putBoolean(PREF_KEY_PROVISIONED, true)
                .putBoolean(PREF_STOP_PENDING, true)
                .apply()
        }
        return stopped
    }

    private fun repairAccessibilityDirect(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "direct repair skipped: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        val resolver = context.contentResolver
        return runCatching {
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val next = servicesWithRelayService(current)
            if (current != next) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    next,
                )
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launch)
            Log.i(TAG, "direct repair applied servicePresent=${current == next}")
            true
        }.getOrElse {
            Log.w(TAG, "direct repair failed: ${it.message}")
            false
        }
    }

    private fun ensureInternalWatchdog(context: Context): File {
        val file = internalWatchdogFile(context)
        if (!file.exists()) writeInternalWatchdog(context, readAssetScript(context))
        return file
    }

    private fun writeInternalWatchdog(context: Context, script: String): File {
        val dir = selfArmDir(context)
        if (!dir.exists()) dir.mkdirs()
        val file = internalWatchdogFile(context)
        file.writeText(script)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        return file
    }

    private fun writeKeyMaterial(context: Context, privateKey: String, publicKey: String) {
        val dir = selfArmDir(context)
        if (!dir.exists()) dir.mkdirs()
        privateKeyFile(context).writeText(privateKey)
        publicKeyFile(context).writeText(publicKey)
        privateKeyFile(context).setReadable(true, true)
        privateKeyFile(context).setWritable(true, true)
        publicKeyFile(context).setReadable(true, true)
        publicKeyFile(context).setWritable(true, true)
    }

    private fun loadKeyMaterial(context: Context): AdbKeyMaterial? {
        val privateFile = privateKeyFile(context)
        val publicFile = publicKeyFile(context)
        if (!privateFile.exists() || !publicFile.exists()) return null
        return runCatching {
            AdbKeyMaterial.parse(privateFile.readText(), publicFile.readText())
        }.onFailure {
            Log.w(TAG, "ADB key parse failed: ${it.message}")
        }.getOrNull()
    }

    private fun deleteKeyMaterial(context: Context) {
        runCatching { privateKeyFile(context).delete() }
        runCatching { publicKeyFile(context).delete() }
    }

    private fun readAssetScript(context: Context): String =
        context.assets.open(Constants.SELF_ARM_WATCHDOG_ASSET)
            .bufferedReader()
            .use { it.readText() }

    internal fun installCommandSucceeded(output: String): Boolean {
        val line = output.lineSequence().lastOrNull { it.contains(INSTALL_SENTINEL) } ?: return false
        val values = line
            .substringAfter(INSTALL_SENTINEL)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { part ->
                val key = part.substringBefore("=", missingDelimiterValue = "")
                val value = part.substringAfter("=", missingDelimiterValue = "")
                if (key.isBlank()) null else key to value
            }
            .toMap()
        val port = "$ADB_PORT"
        return values["watchdog"] == "1" && values["persist"] == port && values["service"] == port
    }

    internal fun disableCommandSucceeded(output: String): Boolean {
        val line = output.lineSequence().lastOrNull { it.contains(DISABLE_SENTINEL) } ?: return false
        val values = line
            .substringAfter(DISABLE_SENTINEL)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { part ->
                val key = part.substringBefore("=", missingDelimiterValue = "")
                val value = part.substringAfter("=", missingDelimiterValue = "")
                if (key.isBlank()) null else key to value
            }
            .toMap()
        return values["watchdog"] == "1" && values["persist"] == "-1" && values["service"] == "-1"
    }

    internal fun buildDisableCommand(): String =
        buildString {
            appendLine("WATCHDOG_PIDFILE='$WATCHDOG_PIDFILE'")
            appendLine("sh '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' stop >/dev/null 2>&1 || true")
            appendLine("sleep 1")
            appendLine("WATCHDOG_STATUS=\"$(sh '${Constants.SELF_ARM_WATCHDOG_REMOTE_PATH}' status 2>/dev/null || true)\"")
            appendLine("case \"${'$'}WATCHDOG_STATUS\" in")
            appendLine("  *\"running=no\"*) WATCHDOG_STOPPED=1 ;;")
            appendLine("  *)")
            appendLine("    WATCHDOG_PID=\"$(cat \"$WATCHDOG_PIDFILE\" 2>/dev/null)\"")
            appendLine("    if [ -z \"${'$'}WATCHDOG_PID\" ] || ! kill -0 \"${'$'}WATCHDOG_PID\" 2>/dev/null; then")
            appendLine("      WATCHDOG_STOPPED=1")
            appendLine("    else")
            appendLine("      WATCHDOG_STOPPED=0")
            appendLine("    fi")
            appendLine("    ;;")
            appendLine("esac")
            appendLine("setprop persist.adb.tcp.port -1")
            appendLine("setprop service.adb.tcp.port -1")
            appendLine("PERSIST_PORT=\"$(getprop persist.adb.tcp.port)\"")
            appendLine("SERVICE_PORT=\"$(getprop service.adb.tcp.port)\"")
            appendLine("echo '$DISABLE_SENTINEL watchdog='\"${'$'}WATCHDOG_STOPPED\"' persist='\"${'$'}PERSIST_PORT\"' service='\"${'$'}SERVICE_PORT\"")
            appendLine("if [ \"${'$'}WATCHDOG_STOPPED\" = \"1\" ] && [ \"${'$'}PERSIST_PORT\" = \"-1\" ] && [ \"${'$'}SERVICE_PORT\" = \"-1\" ]; then")
            appendLine("  setprop ctl.restart adbd")
            appendLine("fi")
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stopPending(context: Context): Boolean =
        prefs(context).getBoolean(PREF_STOP_PENDING, false)

    private fun selfArmDir(context: Context): File =
        File(context.applicationContext.filesDir, "self-arm")

    private fun internalWatchdogFile(context: Context): File =
        File(selfArmDir(context), Constants.SELF_ARM_WATCHDOG_ASSET)

    private fun privateKeyFile(context: Context): File =
        File(selfArmDir(context), "adbkey")

    private fun publicKeyFile(context: Context): File =
        File(selfArmDir(context), "adbkey.pub")
}
