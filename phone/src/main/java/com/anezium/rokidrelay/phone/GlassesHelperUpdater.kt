package com.anezium.rokidrelay.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.example.cxrglobal.CXRLink

internal enum class HelperVersionSource {
    GLASSES_REPORT,
    REMEMBERED_FINGERPRINT,
}

internal data class InstalledHelperVersion(
    val versionCode: Long,
    val versionName: String,
    val source: HelperVersionSource,
)

internal fun resolveInstalledHelperVersion(
    glassesReportedVersionCode: Long?,
    rememberedFingerprint: String?,
): InstalledHelperVersion? {
    val fingerprint = parseClientFingerprint(rememberedFingerprint)
    val reported = glassesReportedVersionCode?.takeIf { it > 0L }
    if (reported != null) {
        return InstalledHelperVersion(
            versionCode = reported,
            versionName = fingerprint?.takeIf { it.versionCode == reported }?.versionName
                ?: reported.toString(),
            source = HelperVersionSource.GLASSES_REPORT,
        )
    }
    return fingerprint?.copy(source = HelperVersionSource.REMEMBERED_FINGERPRINT)
}

internal fun helperUpdateNeeded(
    bundledVersionCode: Long,
    appInstalled: Boolean?,
    installedVersion: InstalledHelperVersion?,
): Boolean {
    val installed = when (appInstalled) {
        true -> true
        false -> false
        null -> installedVersion != null
    }
    return !installed || installedVersion == null || bundledVersionCode > installedVersion.versionCode
}

private fun parseClientFingerprint(value: String?): InstalledHelperVersion? {
    val parts = value.orEmpty().split(':', limit = 3)
    val versionCode = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val versionName = parts.getOrNull(1).orEmpty().ifBlank { versionCode.toString() }
    return InstalledHelperVersion(
        versionCode = versionCode,
        versionName = versionName,
        source = HelperVersionSource.REMEMBERED_FINGERPRINT,
    )
}

internal data class HelperUpdateGates(
    val linkReady: Boolean,
    val voiceSessionActive: Boolean,
    val notificationReplyWindowActive: Boolean,
    val wirelessBootstrapInFlight: Boolean,
    val wifiRadioEnabled: Boolean,
    val glassesRecoveryArmed: Boolean,
)

internal enum class HelperUpdateGateBlock {
    NONE,
    LINK_NOT_READY,
    VOICE_SESSION_ACTIVE,
    NOTIFICATION_REPLY_WINDOW_ACTIVE,
    WIRELESS_BOOTSTRAP_IN_FLIGHT,
    WIFI_RADIO_OFF,
    RECOVERY_NOT_ARMED,
}

internal fun blockedHelperUpdateGate(
    gates: HelperUpdateGates,
    explicitAttempt: Boolean = false,
): HelperUpdateGateBlock =
    when {
        !gates.linkReady -> HelperUpdateGateBlock.LINK_NOT_READY
        gates.voiceSessionActive -> HelperUpdateGateBlock.VOICE_SESSION_ACTIVE
        gates.notificationReplyWindowActive -> HelperUpdateGateBlock.NOTIFICATION_REPLY_WINDOW_ACTIVE
        gates.wirelessBootstrapInFlight -> HelperUpdateGateBlock.WIRELESS_BOOTSTRAP_IN_FLIGHT
        !gates.wifiRadioEnabled -> HelperUpdateGateBlock.WIFI_RADIO_OFF
        !explicitAttempt && !gates.glassesRecoveryArmed -> HelperUpdateGateBlock.RECOVERY_NOT_ARMED
        else -> HelperUpdateGateBlock.NONE
    }

internal fun phoneWifiRadioEnabled(context: Context): Boolean = runCatching {
    context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
}.getOrDefault(false)

internal fun glassesRecoveryArmed(
    confirmedArmed: Boolean?,
    confirmationLive: Boolean,
): Boolean = confirmationLive && confirmedArmed == true

internal enum class AutomaticHelperAttemptBlock {
    NONE,
    BACKOFF,
    DAILY_CAP,
}

internal fun automaticHelperAttemptBlock(
    attemptsToday: Int,
    nextRetryAtMs: Long,
    nowMs: Long,
): AutomaticHelperAttemptBlock =
    when {
        attemptsToday >= GlassesHelperUpdater.MAX_AUTOMATIC_ATTEMPTS_PER_DAY ->
            AutomaticHelperAttemptBlock.DAILY_CAP
        nextRetryAtMs > nowMs -> AutomaticHelperAttemptBlock.BACKOFF
        else -> AutomaticHelperAttemptBlock.NONE
    }

internal fun helperRetryDelayMs(attemptNumber: Int): Long =
    when (attemptNumber.coerceAtLeast(1)) {
        1 -> 60_000L
        2 -> 5L * 60L * 1_000L
        else -> 30L * 60L * 1_000L
    }

enum class GlassesHelperUpdatePhase {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    WAITING_FOR_WIFI,
    UPDATING,
    VERIFYING,
    UPDATED,
    FAILED,
}

internal enum class HelperVerificationEvent {
    ATTEMPT_STARTED,
    INSTALL_SUCCEEDED,
    LEGACY_INSTALL_SUCCEEDED,
    TARGET_VERSION_REPORTED,
    VERIFICATION_TIMED_OUT,
    FAILED,
}

internal fun nextHelperVerificationPhase(
    current: GlassesHelperUpdatePhase,
    event: HelperVerificationEvent,
): GlassesHelperUpdatePhase =
    when (event) {
        HelperVerificationEvent.ATTEMPT_STARTED -> GlassesHelperUpdatePhase.UPDATING
        HelperVerificationEvent.INSTALL_SUCCEEDED -> GlassesHelperUpdatePhase.VERIFYING
        HelperVerificationEvent.LEGACY_INSTALL_SUCCEEDED,
        HelperVerificationEvent.TARGET_VERSION_REPORTED,
        -> GlassesHelperUpdatePhase.UPDATED
        HelperVerificationEvent.VERIFICATION_TIMED_OUT,
        HelperVerificationEvent.FAILED,
        -> GlassesHelperUpdatePhase.FAILED
    }.also { next ->
        require(
            event == HelperVerificationEvent.ATTEMPT_STARTED ||
                current == GlassesHelperUpdatePhase.UPDATING ||
                current == GlassesHelperUpdatePhase.VERIFYING,
        ) { "Invalid helper update transition $current -> $event" }
    }

internal fun helperVersionVerifiesTarget(reportedVersionCode: Long, targetVersionCode: Long): Boolean =
    reportedVersionCode == targetVersionCode

data class GlassesHelperUpdateSnapshot(
    val phase: GlassesHelperUpdatePhase,
    val displayText: String,
    val bundledVersionName: String = "",
    val bundledVersionCode: Long = 0L,
    val installedVersionCode: Long? = null,
    val failureReason: String = "",
    val updatedAtMs: Long = 0L,
) {
    val updatePending: Boolean
        get() = phase == GlassesHelperUpdatePhase.CHECKING ||
            phase == GlassesHelperUpdatePhase.UPDATE_AVAILABLE ||
            phase == GlassesHelperUpdatePhase.WAITING_FOR_WIFI ||
            phase == GlassesHelperUpdatePhase.UPDATING ||
            phase == GlassesHelperUpdatePhase.VERIFYING
}

internal fun blockedHelperUpdateSnapshot(
    available: GlassesHelperUpdateSnapshot,
    gate: HelperUpdateGateBlock,
): GlassesHelperUpdateSnapshot =
    when (gate) {
        HelperUpdateGateBlock.WIFI_RADIO_OFF -> available.copy(
            phase = GlassesHelperUpdatePhase.WAITING_FOR_WIFI,
            displayText = GlassesHelperUpdater.WAITING_FOR_WIFI_TEXT,
        )
        HelperUpdateGateBlock.RECOVERY_NOT_ARMED -> available.copy(
            phase = GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
            displayText = GlassesHelperUpdater.RECOVERY_ARM_REQUIRED_TEXT,
        )
        else -> available
    }

internal data class HelperUpdateOutcome(
    val success: Boolean,
    val forceUpdateAndLaunch: Boolean,
    val foregroundLaunch: Boolean,
    val status: String,
)

/**
 * Single owner for helper version decisions, automatic attempts, CXR installation, launch
 * requests, and glasses-reported verification.
 */
internal class GlassesHelperUpdater(
    context: Context,
    private val main: Handler,
    private val gatesProvider: () -> HelperUpdateGates,
    private val onRecoveryConfirmationInvalidated: () -> Unit,
    private val onStateTransition: (GlassesHelperUpdateSnapshot, String) -> Unit,
    private val onMessageReadinessChanged: (Boolean, String) -> Unit,
    private val onLaunchRequestHandled: (Boolean) -> Unit,
    private val onVersionReportRequested: () -> Unit,
    private val onOutcome: (HelperUpdateOutcome) -> Unit,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    @Volatile private var state = GlassesHelperUpdateSnapshot(
        phase = GlassesHelperUpdatePhase.CHECKING,
        displayText = "",
    )

    private var currentLink: CXRLink? = null
    private var currentClient: ClientBootstrap? = null
    private var currentAsset: ClientAssetInfo? = null
    private var installedState: Boolean? = null
    private var reportedVersionCode: Long? =
        SelfArmProvisioner.glassesState(appContext)?.helperVersionCode?.toLong()?.takeIf { it > 0L }
    private var inspectionInFlight = false
    private var operationInFlight = false
    private var pendingForegroundLaunch = false
    private var pendingForceUpdateAndLaunch = false
    private var verificationTimeout: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var operationGeneration = 0L
    private var attemptForceUpdateAndLaunch = false
    private var attemptForegroundLaunch = false
    private var reportSequence = 0L
    private var attemptStartReportSequence = 0L
    private var availableSinceVersionCode = 0L
    private var availableSinceMs = 0L
    private var availableStartRunnable: Runnable? = null
    private var verificationRequiredVersionCode = prefs.getLong(
        Constants.PREF_HELPER_UPDATE_VERIFICATION_REQUIRED_VERSION_CODE,
        0L,
    )
    private var verificationStartedAtMs = prefs.getLong(
        Constants.PREF_HELPER_UPDATE_VERIFICATION_STARTED_AT_MS,
        0L,
    )

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                main.post { evaluate() }
            }
        }
    }

    init {
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(wifiReceiver, filter)
        }
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): GlassesHelperUpdateSnapshot {
        val current = state
        if (
            current.phase == GlassesHelperUpdatePhase.UPDATED &&
            nowMs - current.updatedAtMs >= UPDATED_HIGHLIGHT_MS
        ) {
            return current.copy(
                phase = GlassesHelperUpdatePhase.UP_TO_DATE,
                displayText = upToDateText(current.bundledVersionName),
            )
        }
        return current
    }

    fun primeForUi() {
        checkOnMainThread()
        if (currentAsset != null || inspectionInFlight) return
        inspectionInFlight = true
        val generation = operationGeneration
        Thread {
            val asset = ClientBootstrap(appContext).bundledClient()
            main.post {
                if (generation != operationGeneration && currentLink != null) return@post
                inspectionInFlight = false
                if (asset == null) return@post
                currentAsset = asset
                if (currentLink != null && gatesProvider().linkReady) {
                    installedState = null
                    evaluate()
                    return@post
                }
                val installedVersion = resolveInstalledHelperVersion(
                    glassesReportedVersionCode = reportedVersionCode,
                    rememberedFingerprint = prefs.getString(Constants.PREF_CLIENT_APK_FINGERPRINT, null),
                )
                transition(
                    if (helperUpdateNeeded(asset.versionCode, appInstalled = null, installedVersion)) {
                        GlassesHelperUpdateSnapshot(
                            phase = GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
                            displayText = updateAvailableText(installedVersion?.versionName, asset.versionName),
                            bundledVersionName = asset.versionName,
                            bundledVersionCode = asset.versionCode,
                            installedVersionCode = installedVersion?.versionCode,
                        )
                    } else {
                        currentStatusForInstalledAsset(asset, installedVersion)
                    },
                )
            }
        }.apply {
            name = "RokidHelperUiInspect"
            start()
        }
    }

    fun request(
        link: CXRLink,
        openAfterInstall: Boolean = false,
        forceUpdateAndLaunch: Boolean = false,
    ) {
        if (currentLink !== link) {
            val keepVerificationTarget = state.phase == GlassesHelperUpdatePhase.VERIFYING
            currentLink = link
            currentClient = ClientBootstrap(appContext, link)
            if (!keepVerificationTarget) {
                currentAsset = null
                installedState = null
            }
            inspectionInFlight = false
            operationGeneration += 1L
        }
        pendingForegroundLaunch = pendingForegroundLaunch || openAfterInstall
        pendingForceUpdateAndLaunch = pendingForceUpdateAndLaunch || forceUpdateAndLaunch
        evaluate()
    }

    fun cancelForegroundLaunchRequest() {
        pendingForegroundLaunch = false
    }

    fun onEnvironmentChanged() {
        evaluate()
    }

    fun onLinkUnavailable() {
        operationGeneration += 1L
        if (state.phase == GlassesHelperUpdatePhase.UPDATING) {
            operationInFlight = false
            currentAsset?.let { failAttempt(it, "glasses disconnected") }
        }
        currentLink = null
        currentClient = null
        if (state.phase != GlassesHelperUpdatePhase.VERIFYING) {
            currentAsset = null
            installedState = null
        }
        inspectionInFlight = false
        publishMessageReadiness(false, "glasses link unavailable")
    }

    fun onHelperVersionReported(versionCode: Long) {
        if (versionCode <= 0L) return
        reportSequence += 1L
        reportedVersionCode = versionCode
        val asset = currentAsset
        if (
            state.phase == GlassesHelperUpdatePhase.VERIFYING &&
            asset != null &&
            helperVersionVerifiesTarget(versionCode, asset.versionCode)
        ) {
            if (
                (pendingForceUpdateAndLaunch || pendingForegroundLaunch) &&
                !attemptForceUpdateAndLaunch &&
                !attemptForegroundLaunch
            ) {
                launchAfterLateVerificationRequest(asset)
            } else {
                completeVerifiedUpdate(asset)
            }
            return
        }
        evaluate()
    }

    private fun evaluate() {
        checkOnMainThread()
        if (operationInFlight || state.phase == GlassesHelperUpdatePhase.VERIFYING) return
        val link = currentLink ?: return
        if (!gatesProvider().linkReady) return
        val client = currentClient ?: ClientBootstrap(appContext, link).also { currentClient = it }
        val asset = currentAsset
        if (asset != null && installedState != null) {
            decideAndAct(client, asset, installedState)
            return
        }
        if (inspectionInFlight) return
        inspectionInFlight = true
        transition(
            GlassesHelperUpdateSnapshot(
                phase = GlassesHelperUpdatePhase.CHECKING,
                displayText = "",
            ),
        )
        val generation = operationGeneration
        Thread {
            val inspectedAsset = client.bundledClient()
            val inspectedInstalled = client.queryInstalled()
            main.post {
                if (generation != operationGeneration || currentClient !== client) return@post
                inspectionInFlight = false
                if (inspectedAsset == null) {
                    failWithoutAttempt("glasses asset missing")
                    return@post
                }
                currentAsset = inspectedAsset
                installedState = inspectedInstalled
                decideAndAct(client, inspectedAsset, inspectedInstalled)
            }
        }.apply {
            name = "RokidHelperInspect"
            start()
        }
    }

    private fun decideAndAct(
        client: ClientBootstrap,
        asset: ClientAssetInfo,
        appInstalled: Boolean?,
    ) {
        val installedVersion = resolveInstalledHelperVersion(
            glassesReportedVersionCode = reportedVersionCode,
            rememberedFingerprint = prefs.getString(Constants.PREF_CLIENT_APK_FINGERPRINT, null),
        )
        val installed = when (appInstalled) {
            true -> true
            false -> false
            null -> installedVersion != null
        }
        val updateNeeded = helperUpdateNeeded(asset.versionCode, appInstalled, installedVersion)

        if (recoveredTargetVerification(asset)) {
            completeVerifiedUpdate(asset)
            return
        }

        if (!updateNeeded) {
            clearAvailableStart()
            availableSinceVersionCode = 0L
            availableSinceMs = 0L
            transition(currentStatusForInstalledAsset(asset, installedVersion))
            if (pendingForegroundLaunch || pendingForceUpdateAndLaunch) {
                launchCurrentHelper(client)
            } else {
                publishMessageReadiness(true, "glasses app ready in background")
            }
            return
        }

        val available = GlassesHelperUpdateSnapshot(
            phase = GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
            displayText = updateAvailableText(installedVersion?.versionName, asset.versionName),
            bundledVersionName = asset.versionName,
            bundledVersionCode = asset.versionCode,
            installedVersionCode = installedVersion?.versionCode,
        )
        transition(available)

        val explicitAttempt = pendingForegroundLaunch || pendingForceUpdateAndLaunch
        val gate = blockedHelperUpdateGate(gatesProvider(), explicitAttempt)
        if (gate != HelperUpdateGateBlock.NONE) {
            val readyWithOldHelper = installed
            val blocked = blockedHelperUpdateSnapshot(available, gate)
            transition(blocked)
            publishMessageReadiness(readyWithOldHelper, blocked.displayText)
            return
        }

        if (!explicitAttempt) {
            val attempts = attemptRecord(asset.versionCode)
            when (automaticHelperAttemptBlock(attempts.count, attempts.nextRetryAtMs, nowMs())) {
                AutomaticHelperAttemptBlock.BACKOFF -> {
                    val reason = attempts.lastFailureReason.ifBlank { "retry backoff active" }
                    transition(failedSnapshot(asset, installedVersion, reason))
                    publishMessageReadiness(installed, state.displayText)
                    scheduleRetry(attempts.nextRetryAtMs)
                    return
                }
                AutomaticHelperAttemptBlock.DAILY_CAP -> {
                    val reason = attempts.lastFailureReason.ifBlank { "retry limit reached today" }
                    transition(failedSnapshot(asset, installedVersion, reason))
                    publishMessageReadiness(installed, state.displayText)
                    scheduleRetry(startOfNextUtcDay(nowMs()))
                    return
                }
                AutomaticHelperAttemptBlock.NONE -> Unit
            }
        }

        if (availableSinceVersionCode != asset.versionCode) {
            availableSinceVersionCode = asset.versionCode
            availableSinceMs = nowMs()
        }
        val availableRemainingMs = AVAILABLE_STATE_HOLD_MS - (nowMs() - availableSinceMs)
        if (availableRemainingMs > 0L) {
            publishMessageReadiness(installed, available.displayText)
            scheduleAvailableStart(availableRemainingMs)
            return
        }

        startUpdateAttempt(client, asset, installedVersion, automatic = !explicitAttempt)
    }

    private fun startUpdateAttempt(
        client: ClientBootstrap,
        asset: ClientAssetInfo,
        installedVersion: InstalledHelperVersion?,
        automatic: Boolean,
    ) {
        clearAvailableStart()
        retryRunnable?.let(main::removeCallbacks)
        retryRunnable = null
        attemptForceUpdateAndLaunch = pendingForceUpdateAndLaunch
        attemptForegroundLaunch = pendingForegroundLaunch
        attemptStartReportSequence = reportSequence
        onRecoveryConfirmationInvalidated()
        if (asset.versionCode >= FIRST_SELF_REPORTING_HELPER_VERSION_CODE) {
            markTargetVerificationRequired(asset.versionCode)
        }
        if (automatic) recordAutomaticAttemptStarted(asset.versionCode)
        operationInFlight = true
        publishMessageReadiness(false, UPDATING_TEXT)
        transition(
            GlassesHelperUpdateSnapshot(
                phase = nextHelperVerificationPhase(
                    GlassesHelperUpdatePhase.UPDATE_AVAILABLE,
                    HelperVerificationEvent.ATTEMPT_STARTED,
                ),
                displayText = UPDATING_TEXT,
                bundledVersionName = asset.versionName,
                bundledVersionCode = asset.versionCode,
                installedVersionCode = installedVersion?.versionCode,
            ),
        )
        val generation = operationGeneration
        Thread {
            val installResult = client.installBundledClient(asset)
            // appStart opens MainActivity on the glasses, so it is reserved for the existing
            // foreground/forced consumers. The Rokid ROM drops the enabled accessibility service
            // on replacement; automatic attempts reach here only with glasses-confirmed self-arm,
            // whose direct repair/watchdog re-enables the service so it can report the new version.
            val shouldLaunch = attemptForceUpdateAndLaunch || attemptForegroundLaunch
            val launchResult = if (installResult.success && shouldLaunch) {
                client.openClient("glasses app installed/updated")
            } else {
                null
            }
            main.post {
                if (generation != operationGeneration || currentClient !== client) return@post
                operationInFlight = false
                if (!installResult.success) {
                    failAttempt(asset, installResult.status)
                    return@post
                }
                installedState = true
                if (shouldLaunch) {
                    onLaunchRequestHandled(attemptForegroundLaunch)
                    pendingForegroundLaunch = false
                    pendingForceUpdateAndLaunch = false
                }
                if (launchResult != null && !launchResult.success) {
                    failAttempt(asset, launchResult.status)
                    return@post
                }
                if (asset.versionCode < FIRST_SELF_REPORTING_HELPER_VERSION_CODE) {
                    completeLegacyUpdate(asset, readyForMessages = launchResult?.readyForMessages == true)
                } else {
                    beginVerification(asset)
                }
            }
        }.apply {
            name = "RokidHelperUpdate"
            start()
        }
    }

    private fun launchCurrentHelper(client: ClientBootstrap) {
        if (operationInFlight) return
        operationInFlight = true
        val force = pendingForceUpdateAndLaunch
        val foreground = pendingForegroundLaunch
        val generation = operationGeneration
        Thread {
            val result = client.openClient(
                if (client.clientLaunchPending()) {
                    "glasses app started after background install"
                } else {
                    "glasses app started"
                },
            )
            main.post {
                if (generation != operationGeneration || currentClient !== client) return@post
                operationInFlight = false
                pendingForceUpdateAndLaunch = false
                pendingForegroundLaunch = false
                onLaunchRequestHandled(foreground)
                if (result.success) {
                    publishMessageReadiness(true, result.status)
                } else {
                    val asset = currentAsset
                    if (asset != null) {
                        transition(failedSnapshot(asset, null, result.status))
                    }
                }
                onOutcome(
                    HelperUpdateOutcome(
                        success = result.success,
                        forceUpdateAndLaunch = force,
                        foregroundLaunch = foreground,
                        status = result.status,
                    ),
                )
            }
        }.apply {
            name = "RokidHelperLaunch"
            start()
        }
    }

    private fun beginVerification(asset: ClientAssetInfo) {
        transition(
            state.copy(
                phase = nextHelperVerificationPhase(
                    GlassesHelperUpdatePhase.UPDATING,
                    HelperVerificationEvent.INSTALL_SUCCEEDED,
                ),
                displayText = VERIFYING_TEXT,
            ),
        )
        publishMessageReadiness(false, VERIFYING_TEXT)
        if (
            reportSequence > attemptStartReportSequence &&
            reportedVersionCode?.let { helperVersionVerifiesTarget(it, asset.versionCode) } == true
        ) {
            completeVerifiedUpdate(asset)
            return
        }
        onVersionReportRequested()
        verificationTimeout?.let(main::removeCallbacks)
        val timeout = Runnable {
            verificationTimeout = null
            if (
                state.phase == GlassesHelperUpdatePhase.VERIFYING &&
                currentAsset?.versionCode == asset.versionCode
            ) {
                failAttempt(asset, "verification timed out")
            }
        }
        verificationTimeout = timeout
        main.postDelayed(timeout, VERIFICATION_TIMEOUT_MS)
    }

    private fun completeVerifiedUpdate(asset: ClientAssetInfo) {
        verificationTimeout?.let(main::removeCallbacks)
        verificationTimeout = null
        val completedAt = nowMs()
        verificationRequiredVersionCode = 0L
        verificationStartedAtMs = 0L
        prefs.edit()
            .putString(Constants.PREF_CLIENT_APK_FINGERPRINT, asset.fingerprint)
            .putLong(Constants.PREF_HELPER_UPDATED_AT_MS, completedAt)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_DAY)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_COUNT)
            .remove(Constants.PREF_HELPER_UPDATE_NEXT_RETRY_AT_MS)
            .remove(Constants.PREF_HELPER_UPDATE_LAST_FAILURE_REASON)
            .remove(Constants.PREF_HELPER_UPDATE_VERIFICATION_REQUIRED_VERSION_CODE)
            .remove(Constants.PREF_HELPER_UPDATE_VERIFICATION_STARTED_AT_MS)
            .apply()
        installedState = true
        clearAvailableStart()
        availableSinceVersionCode = 0L
        availableSinceMs = 0L
        transition(
            GlassesHelperUpdateSnapshot(
                phase = if (state.phase == GlassesHelperUpdatePhase.VERIFYING) {
                    nextHelperVerificationPhase(
                        GlassesHelperUpdatePhase.VERIFYING,
                        HelperVerificationEvent.TARGET_VERSION_REPORTED,
                    )
                } else {
                    GlassesHelperUpdatePhase.UPDATED
                },
                displayText = updatedText(asset.versionName),
                bundledVersionName = asset.versionName,
                bundledVersionCode = asset.versionCode,
                installedVersionCode = asset.versionCode,
                updatedAtMs = completedAt,
            ),
        )
        publishMessageReadiness(true, state.displayText)
        finishSuccessfulAttempt()
    }

    private fun completeLegacyUpdate(asset: ClientAssetInfo, readyForMessages: Boolean) {
        val completedAt = nowMs()
        verificationRequiredVersionCode = 0L
        verificationStartedAtMs = 0L
        prefs.edit()
            .putString(Constants.PREF_CLIENT_APK_FINGERPRINT, asset.fingerprint)
            .putLong(Constants.PREF_HELPER_UPDATED_AT_MS, completedAt)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_DAY)
            .remove(Constants.PREF_HELPER_UPDATE_ATTEMPT_COUNT)
            .remove(Constants.PREF_HELPER_UPDATE_NEXT_RETRY_AT_MS)
            .remove(Constants.PREF_HELPER_UPDATE_LAST_FAILURE_REASON)
            .remove(Constants.PREF_HELPER_UPDATE_VERIFICATION_REQUIRED_VERSION_CODE)
            .remove(Constants.PREF_HELPER_UPDATE_VERIFICATION_STARTED_AT_MS)
            .apply()
        if (
            prefs.getLong(Constants.PREF_HELPER_LEGACY_VERIFICATION_LOGGED_VERSION_CODE, 0L) !=
            asset.versionCode
        ) {
            Log.i(TAG, "verification was skipped for a legacy helper vc=${asset.versionCode}")
            prefs.edit()
                .putLong(Constants.PREF_HELPER_LEGACY_VERIFICATION_LOGGED_VERSION_CODE, asset.versionCode)
                .apply()
        }
        clearAvailableStart()
        availableSinceVersionCode = 0L
        availableSinceMs = 0L
        transition(
            state.copy(
                phase = nextHelperVerificationPhase(
                    GlassesHelperUpdatePhase.UPDATING,
                    HelperVerificationEvent.LEGACY_INSTALL_SUCCEEDED,
                ),
                displayText = updatedText(asset.versionName),
                failureReason = "",
                updatedAtMs = completedAt,
            ),
        )
        publishMessageReadiness(readyForMessages, state.displayText)
        finishSuccessfulAttempt()
    }

    private fun finishSuccessfulAttempt() {
        val force = attemptForceUpdateAndLaunch
        val foreground = attemptForegroundLaunch
        attemptForceUpdateAndLaunch = false
        attemptForegroundLaunch = false
        pendingForceUpdateAndLaunch = false
        pendingForegroundLaunch = false
        onOutcome(
            HelperUpdateOutcome(
                success = true,
                forceUpdateAndLaunch = force,
                foregroundLaunch = foreground,
                status = state.displayText,
            ),
        )
    }

    private fun failAttempt(asset: ClientAssetInfo, rawReason: String) {
        verificationTimeout?.let(main::removeCallbacks)
        verificationTimeout = null
        operationInFlight = false
        val reason = shortFailureReason(rawReason)
        availableSinceVersionCode = 0L
        availableSinceMs = 0L
        clearAvailableStart()
        val attempts = attemptRecord(asset.versionCode)
        val ordinal = attempts.count.coerceAtLeast(1).coerceAtMost(MAX_AUTOMATIC_ATTEMPTS_PER_DAY)
        val retryAt = nowMs() + helperRetryDelayMs(ordinal)
        prefs.edit()
            .putLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE, asset.versionCode)
            .putLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_DAY, utcDay(nowMs()))
            .putInt(Constants.PREF_HELPER_UPDATE_ATTEMPT_COUNT, attempts.count)
            .putLong(Constants.PREF_HELPER_UPDATE_NEXT_RETRY_AT_MS, retryAt)
            .putString(Constants.PREF_HELPER_UPDATE_LAST_FAILURE_REASON, reason)
            .apply()
        transition(failedSnapshot(asset, null, reason))
        val force = attemptForceUpdateAndLaunch
        val foreground = attemptForegroundLaunch
        attemptForceUpdateAndLaunch = false
        attemptForegroundLaunch = false
        pendingForceUpdateAndLaunch = false
        pendingForegroundLaunch = false
        onLaunchRequestHandled(foreground)
        // Once an update transfer has started, this ROM may already have replaced the package and
        // dropped its accessibility service. A pre-attempt report cannot prove message readiness.
        onRecoveryConfirmationInvalidated()
        publishMessageReadiness(false, state.displayText)
        onVersionReportRequested()
        scheduleRetry(retryAt)
        onOutcome(
            HelperUpdateOutcome(
                success = false,
                forceUpdateAndLaunch = force,
                foregroundLaunch = foreground,
                status = reason,
            ),
        )
    }

    private fun failWithoutAttempt(reason: String) {
        val cleanReason = shortFailureReason(reason)
        val failed = GlassesHelperUpdateSnapshot(
            phase = GlassesHelperUpdatePhase.FAILED,
            displayText = failedText(cleanReason),
            failureReason = cleanReason,
        )
        transition(failed)
        publishMessageReadiness(false, failed.displayText)
        onOutcome(
            HelperUpdateOutcome(
                success = false,
                forceUpdateAndLaunch = pendingForceUpdateAndLaunch,
                foregroundLaunch = pendingForegroundLaunch,
                status = cleanReason,
            ),
        )
        pendingForceUpdateAndLaunch = false
        pendingForegroundLaunch = false
    }

    private fun failedSnapshot(
        asset: ClientAssetInfo,
        installedVersion: InstalledHelperVersion?,
        reason: String,
    ): GlassesHelperUpdateSnapshot {
        val cleanReason = shortFailureReason(reason)
        return GlassesHelperUpdateSnapshot(
            phase = GlassesHelperUpdatePhase.FAILED,
            displayText = failedText(cleanReason),
            bundledVersionName = asset.versionName,
            bundledVersionCode = asset.versionCode,
            installedVersionCode = installedVersion?.versionCode ?: state.installedVersionCode,
            failureReason = cleanReason,
        )
    }

    private fun currentStatusForInstalledAsset(
        asset: ClientAssetInfo,
        installedVersion: InstalledHelperVersion?,
    ): GlassesHelperUpdateSnapshot {
        val updatedAt = prefs.getLong(Constants.PREF_HELPER_UPDATED_AT_MS, 0L)
        val fingerprint = prefs.getString(Constants.PREF_CLIENT_APK_FINGERPRINT, null)
        val recentlyUpdated = fingerprint == asset.fingerprint &&
            updatedAt > 0L &&
            nowMs() - updatedAt < UPDATED_HIGHLIGHT_MS
        return GlassesHelperUpdateSnapshot(
            phase = if (recentlyUpdated) {
                GlassesHelperUpdatePhase.UPDATED
            } else {
                GlassesHelperUpdatePhase.UP_TO_DATE
            },
            displayText = if (recentlyUpdated) updatedText(asset.versionName) else upToDateText(asset.versionName),
            bundledVersionName = asset.versionName,
            bundledVersionCode = asset.versionCode,
            installedVersionCode = installedVersion?.versionCode,
            updatedAtMs = updatedAt,
        )
    }

    private fun recordAutomaticAttemptStarted(versionCode: Long) {
        val now = nowMs()
        val record = attemptRecord(versionCode)
        prefs.edit()
            .putLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE, versionCode)
            .putLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_DAY, utcDay(now))
            .putInt(Constants.PREF_HELPER_UPDATE_ATTEMPT_COUNT, record.count + 1)
            .apply()
    }

    private fun attemptRecord(versionCode: Long): AttemptRecord {
        val today = utcDay(nowMs())
        val sameVersion = prefs.getLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE, 0L) == versionCode
        val sameDay = prefs.getLong(Constants.PREF_HELPER_UPDATE_ATTEMPT_DAY, Long.MIN_VALUE) == today
        return AttemptRecord(
            count = if (sameVersion && sameDay) {
                prefs.getInt(Constants.PREF_HELPER_UPDATE_ATTEMPT_COUNT, 0).coerceAtLeast(0)
            } else {
                0
            },
            nextRetryAtMs = if (sameVersion) {
                prefs.getLong(Constants.PREF_HELPER_UPDATE_NEXT_RETRY_AT_MS, 0L)
            } else {
                0L
            },
            lastFailureReason = if (sameVersion) {
                prefs.getString(Constants.PREF_HELPER_UPDATE_LAST_FAILURE_REASON, "").orEmpty()
            } else {
                ""
            },
        )
    }

    private fun scheduleRetry(atMs: Long) {
        retryRunnable?.let(main::removeCallbacks)
        val delay = (atMs - nowMs()).coerceAtLeast(1_000L)
        val runnable = Runnable {
            retryRunnable = null
            evaluate()
        }
        retryRunnable = runnable
        main.postDelayed(runnable, delay)
    }

    private fun markTargetVerificationRequired(versionCode: Long) {
        val startedAt = nowMs()
        verificationRequiredVersionCode = versionCode
        verificationStartedAtMs = startedAt
        prefs.edit()
            .putLong(Constants.PREF_HELPER_UPDATE_VERIFICATION_REQUIRED_VERSION_CODE, versionCode)
            .putLong(Constants.PREF_HELPER_UPDATE_VERIFICATION_STARTED_AT_MS, startedAt)
            .apply()
    }

    private fun recoveredTargetVerification(asset: ClientAssetInfo): Boolean {
        if (verificationRequiredVersionCode <= 0L || verificationStartedAtMs <= 0L) return false
        if (reportedVersionCode != asset.versionCode) return false
        val reportedState = SelfArmProvisioner.glassesState(appContext) ?: return false
        return reportedState.helperVersionCode.toLong() == asset.versionCode &&
            reportedState.receivedAtWallClockMs >= verificationStartedAtMs
    }

    private fun publishMessageReadiness(requestedReady: Boolean, status: String) {
        val verifiedReady = requestedReady && verificationRequiredVersionCode <= 0L
        onMessageReadinessChanged(verifiedReady, status)
    }

    private fun scheduleAvailableStart(delayMs: Long) {
        if (availableStartRunnable != null) return
        val runnable = Runnable {
            availableStartRunnable = null
            evaluate()
        }
        availableStartRunnable = runnable
        main.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }

    private fun clearAvailableStart() {
        availableStartRunnable?.let(main::removeCallbacks)
        availableStartRunnable = null
    }

    private fun launchAfterLateVerificationRequest(asset: ClientAssetInfo) {
        if (operationInFlight) return
        val client = currentClient ?: return
        operationInFlight = true
        attemptForceUpdateAndLaunch = pendingForceUpdateAndLaunch
        attemptForegroundLaunch = pendingForegroundLaunch
        val generation = operationGeneration
        Thread {
            val result = client.openClient("glasses app installed/updated")
            main.post {
                if (generation != operationGeneration || currentClient !== client) return@post
                operationInFlight = false
                onLaunchRequestHandled(attemptForegroundLaunch)
                pendingForceUpdateAndLaunch = false
                pendingForegroundLaunch = false
                if (result.success) {
                    completeVerifiedUpdate(asset)
                } else {
                    failAttempt(asset, result.status)
                }
            }
        }.apply {
            name = "RokidHelperLateLaunch"
            start()
        }
    }

    private fun transition(next: GlassesHelperUpdateSnapshot) {
        val previous = state
        if (previous == next) return
        state = next
        val event = "${previous.phase.name} -> ${next.phase.name}: ${next.displayText.ifBlank { "checking" }}"
        Log.i(TAG, event)
        onStateTransition(next, event)
    }

    private fun checkOnMainThread() {
        check(main.looper.isCurrentThread) { "GlassesHelperUpdater must run on its handler thread" }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private data class AttemptRecord(
        val count: Int,
        val nextRetryAtMs: Long,
        val lastFailureReason: String,
    )

    companion object {
        private const val TAG = "GlassesHelperUpdater"
        private const val FIRST_SELF_REPORTING_HELPER_VERSION_CODE = 23L
        private const val VERIFICATION_TIMEOUT_MS = 120_000L
        private const val UPDATED_HIGHLIGHT_MS = 60L * 60L * 1_000L
        private const val AVAILABLE_STATE_HOLD_MS = 1_200L
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        internal const val MAX_AUTOMATIC_ATTEMPTS_PER_DAY = 3
        const val WAITING_FOR_WIFI_TEXT = "Turn on the phone's Wi-Fi to update the glasses app"
        const val RECOVERY_ARM_REQUIRED_TEXT = "Glasses app update waits for recovery arm — tap Bootstrap"
        const val UPDATING_TEXT = "Updating the glasses app… keep the glasses on (about 15 s)"
        const val VERIFYING_TEXT = "Updating the glasses app… verifying"

        private fun updateAvailableText(oldVersion: String?, newVersion: String): String =
            "Glasses app update available (v${oldVersion ?: "?"} → v$newVersion)"

        private fun upToDateText(versionName: String): String =
            "Glasses app v$versionName — up to date"

        private fun updatedText(versionName: String): String =
            "Glasses app updated to v$versionName"

        private fun failedText(reason: String): String =
            "Glasses app update failed: $reason — will retry"

        private fun shortFailureReason(reason: String): String =
            reason
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .ifBlank { "unknown error" }
                .take(80)

        private fun utcDay(wallClockMs: Long): Long = wallClockMs / DAY_MS

        private fun startOfNextUtcDay(wallClockMs: Long): Long = (utcDay(wallClockMs) + 1L) * DAY_MS
    }
}
