package com.anezium.rokidrelay.phone

internal enum class SelfArmRecoveryTone {
    Ready,
    Waiting,
    Neutral,
}

internal data class SelfArmRecoveryRowState(
    val value: String,
    val tone: SelfArmRecoveryTone,
    val actionLabel: String,
)

internal fun selfArmRecoveryRowState(
    selfArmProvisioned: Boolean,
    selfArmDisablePending: Boolean,
    selfArmWireless: SelfArmProvisioner.WirelessBootstrap,
    relayEnabled: Boolean,
    glassesState: SelfArmProvisioner.GlassesState?,
    glassesStateLive: Boolean,
    nowWallClockMs: Long,
    relayEnabledSetupMessage: String,
    friendlyWirelessStatus: (String) -> String,
): SelfArmRecoveryRowState {
    if (
        !selfArmDisablePending &&
        glassesState != null &&
        glassesState.armed &&
        glassesState.keyPresent &&
        glassesState.writeSecureGranted
    ) {
        val value = if (glassesStateLive) {
            "Armed on the glasses — they recover on their own"
        } else {
            "Armed on the glasses — last confirmed ${
                glassesReportAge(
                    nowWallClockMs,
                    glassesState.receivedAtWallClockMs,
                )
            }"
        }
        return SelfArmRecoveryRowState(
            value = value,
            tone = SelfArmRecoveryTone.Ready,
            actionLabel = "Re-arm",
        )
    }
    if (
        !selfArmDisablePending &&
        !selfArmWireless.inProgress &&
        glassesState != null &&
        glassesState.armed &&
        (!glassesState.keyPresent || !glassesState.writeSecureGranted)
    ) {
        val issue = when {
            !glassesState.keyPresent && !glassesState.writeSecureGranted ->
                "recovery key and settings grant missing"
            !glassesState.keyPresent -> "recovery key missing"
            else -> "settings grant missing"
        }
        return SelfArmRecoveryRowState(
            value = "Glasses report $issue — tap Re-arm",
            tone = SelfArmRecoveryTone.Waiting,
            actionLabel = "Re-arm",
        )
    }
    if (
        !selfArmDisablePending &&
        !selfArmWireless.inProgress &&
        selfArmProvisioned &&
        glassesState?.armed == false
    ) {
        return SelfArmRecoveryRowState(
            value = "Glasses report recovery disarmed — tap Re-arm",
            tone = SelfArmRecoveryTone.Waiting,
            actionLabel = "Re-arm",
        )
    }

    // Value and tone are decided together so they can never disagree. A live bootstrap
    // outranks "Recovery armed" (progress must be visible after tapping Re-arm), but a
    // provisioned device stays green over the forever-true bootstrap-complete flag and over a
    // stale lastError, which only clears on the next successful bootstrap.
    val fallback = when {
        selfArmDisablePending ->
            "Disable pending" to SelfArmRecoveryTone.Waiting
        selfArmWireless.inProgress ->
            friendlyWirelessStatus(selfArmWireless.status) to SelfArmRecoveryTone.Waiting
        selfArmProvisioned ->
            "Recovery armed" to SelfArmRecoveryTone.Ready
        selfArmWireless.complete -> friendlyWirelessStatus(
            selfArmWireless.status.ifBlank { "complete" },
        ) to SelfArmRecoveryTone.Ready
        selfArmWireless.lastError.isNotBlank() ->
            friendlyWirelessStatus(selfArmWireless.lastError) to SelfArmRecoveryTone.Waiting
        relayEnabled ->
            relayEnabledSetupMessage to SelfArmRecoveryTone.Waiting
        else -> "Off" to SelfArmRecoveryTone.Neutral
    }
    return SelfArmRecoveryRowState(
        value = fallback.first,
        tone = fallback.second,
        actionLabel = when {
            selfArmProvisioned -> "Re-arm"
            selfArmWireless.complete -> "Arm"
            relayEnabled -> "Bootstrap"
            else -> "Arm"
        },
    )
}

private fun glassesReportAge(nowMs: Long, receivedMs: Long): String {
    val deltaMs = if (receivedMs <= 0L) {
        0L
    } else {
        (nowMs - receivedMs).coerceAtLeast(0L)
    }
    val seconds = deltaMs / 1_000L
    return when {
        seconds < 60L -> "just now"
        seconds < 60L * 60L -> "${seconds / 60L}m ago"
        seconds < 24L * 60L * 60L -> "${seconds / (60L * 60L)}h ago"
        else -> "${seconds / (24L * 60L * 60L)}d ago"
    }
}
