package com.anezium.rokidrelay.phone

internal fun shouldPromoteMicrophoneForegroundOnPresence(
    relayEnabled: Boolean,
    relayServiceRunning: Boolean,
    selectedEngine: SpeechToTextEngine,
): Boolean =
    relayEnabled &&
        relayServiceRunning &&
        selectedEngine == SpeechToTextEngine.ANDROID_CXR

internal fun shouldRequestMicrophoneForegroundOnInitialWake(
    startReason: String,
    selectedEngine: SpeechToTextEngine,
    recordAudioGranted: Boolean,
): Boolean =
    (isNotificationWakeStart(startReason) || isBleWakeReplyStart(startReason)) &&
        selectedEngine == SpeechToTextEngine.ANDROID_CXR &&
        recordAudioGranted

internal fun initialWakeMicrophonePromotionLogLine(granted: Boolean, detail: String): String {
    val outcome = if (granted) "granted" else "denied"
    return "initial wake promotion mic=$outcome: ${detail.trim().ifBlank { "unknown" }}"
}

internal enum class InjectedAudioForegroundDecision {
    USE_MICROPHONE_FOREGROUND,
    TRY_WITHOUT_MICROPHONE_FOREGROUND,
}

internal fun injectedAudioForegroundDecision(
    microphoneForegroundActive: Boolean,
): InjectedAudioForegroundDecision =
    if (microphoneForegroundActive) {
        InjectedAudioForegroundDecision.USE_MICROPHONE_FOREGROUND
    } else {
        InjectedAudioForegroundDecision.TRY_WITHOUT_MICROPHONE_FOREGROUND
    }

internal fun microphoneForegroundFailureDetail(error: Throwable): String {
    val type = error::class.java.simpleName.ifBlank { "ForegroundServiceException" }
    val message = error.message?.trim().orEmpty()
    return if (message.isBlank()) type else "$type: $message"
}

internal fun microphoneForegroundDiagnosticsLine(active: Boolean, error: String): String =
    when {
        active -> "Mic foreground: active"
        error.isNotBlank() -> "Mic foreground: off ($error)"
        else -> "Mic foreground: off"
    }
