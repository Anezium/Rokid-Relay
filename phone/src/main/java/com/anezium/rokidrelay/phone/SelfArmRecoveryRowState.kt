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
        return SelfArmRecoveryRowState(
            value = "Armed on the glasses — they recover on their own",
            tone = SelfArmRecoveryTone.Ready,
            actionLabel = "Re-arm",
        )
    }
    if (!selfArmDisablePending && selfArmProvisioned && glassesState?.armed == false) {
        return SelfArmRecoveryRowState(
            value = "Glasses report recovery disarmed — tap Re-arm",
            tone = SelfArmRecoveryTone.Waiting,
            actionLabel = "Re-arm",
        )
    }

    return SelfArmRecoveryRowState(
        value = when {
            selfArmDisablePending -> "Disable pending"
            selfArmProvisioned -> "Recovery armed"
            selfArmWireless.complete -> friendlyWirelessStatus(
                selfArmWireless.status.ifBlank { "complete" },
            )
            selfArmWireless.lastError.isNotBlank() ->
                friendlyWirelessStatus(selfArmWireless.lastError)
            selfArmWireless.inProgress -> friendlyWirelessStatus(selfArmWireless.status)
            relayEnabled -> relayEnabledSetupMessage
            else -> "Off"
        },
        tone = when {
            selfArmProvisioned || selfArmWireless.complete -> SelfArmRecoveryTone.Ready
            relayEnabled ||
                selfArmDisablePending ||
                selfArmWireless.inProgress ||
                selfArmWireless.lastError.isNotBlank() -> SelfArmRecoveryTone.Waiting
            else -> SelfArmRecoveryTone.Neutral
        },
        actionLabel = when {
            selfArmProvisioned -> "Re-arm"
            selfArmWireless.complete -> "Arm"
            relayEnabled -> "Bootstrap"
            else -> "Arm"
        },
    )
}
