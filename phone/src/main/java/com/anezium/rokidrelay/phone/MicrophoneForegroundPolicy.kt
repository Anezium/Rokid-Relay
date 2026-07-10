package com.anezium.rokidrelay.phone

internal fun shouldPromoteMicrophoneForegroundOnPresence(
    relayEnabled: Boolean,
    relayServiceRunning: Boolean,
    selectedEngine: SpeechToTextEngine,
): Boolean =
    relayEnabled &&
        relayServiceRunning &&
        selectedEngine == SpeechToTextEngine.ANDROID_CXR

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
