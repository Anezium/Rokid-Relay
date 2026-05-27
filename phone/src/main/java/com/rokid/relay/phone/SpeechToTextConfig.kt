package com.rokid.relay.phone

import android.content.Context

enum class SpeechToTextProvider(
    val displayName: String,
) {
    ANDROID("Android"),
    OPENAI("OpenAI"),
    ELEVENLABS("ElevenLabs"),
}

enum class SpeechToTextCredentialKind {
    NONE,
    OPENAI,
    ELEVENLABS,
}

enum class SpeechToTextEngine(
    val id: String,
    val provider: SpeechToTextProvider,
    val displayName: String,
    val shortLabel: String,
    val credentialKind: SpeechToTextCredentialKind,
    val completedAudioModelId: String? = null,
    val requiresMicrophonePermission: Boolean = false,
) {
    ANDROID_CXR(
        id = "android_cxr",
        provider = SpeechToTextProvider.ANDROID,
        displayName = "Android CXR",
        shortLabel = "Android CXR",
        credentialKind = SpeechToTextCredentialKind.NONE,
        requiresMicrophonePermission = true,
    ),
    OPENAI_GPT_4O_TRANSCRIBE(
        id = "openai_gpt_4o_transcribe",
        provider = SpeechToTextProvider.OPENAI,
        displayName = "OpenAI GPT-4o Transcribe",
        shortLabel = "GPT-4o",
        credentialKind = SpeechToTextCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-transcribe",
    ),
    OPENAI_GPT_4O_MINI_TRANSCRIBE(
        id = "openai_gpt_4o_mini_transcribe",
        provider = SpeechToTextProvider.OPENAI,
        displayName = "OpenAI GPT-4o mini Transcribe",
        shortLabel = "GPT-4o mini",
        credentialKind = SpeechToTextCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-mini-transcribe",
    ),
    ELEVENLABS_SCRIBE_V2(
        id = "elevenlabs_scribe_v2",
        provider = SpeechToTextProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2",
        shortLabel = "Scribe v2",
        credentialKind = SpeechToTextCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v2",
    ),
    ELEVENLABS_SCRIBE_V1(
        id = "elevenlabs_scribe_v1",
        provider = SpeechToTextProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v1",
        shortLabel = "Scribe v1",
        credentialKind = SpeechToTextCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v1",
    );

    val usesCompletedAudio: Boolean
        get() = completedAudioModelId != null

    val requiresCredential: Boolean
        get() = credentialKind != SpeechToTextCredentialKind.NONE

    companion object {
        fun fromId(id: String?): SpeechToTextEngine {
            val normalized = id.orEmpty().trim().lowercase()
            return values().firstOrNull { it.id == normalized } ?: ANDROID_CXR
        }
    }
}

class SpeechToTextSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun selectedEngine(): SpeechToTextEngine {
        val saved = prefs.getString(Constants.PREF_STT_ENGINE, null)
        if (!saved.isNullOrBlank()) return SpeechToTextEngine.fromId(saved)

        val credentials = SttCredentialStore(appContext)
        return if (credentials.hasOpenAiApiKey()) {
            SpeechToTextEngine.OPENAI_GPT_4O_TRANSCRIBE
        } else if (!credentials.apiKey(SpeechToTextCredentialKind.ELEVENLABS).isNullOrBlank()) {
            SpeechToTextEngine.ELEVENLABS_SCRIBE_V2
        } else {
            SpeechToTextEngine.ANDROID_CXR
        }
    }

    fun saveSelectedEngine(engine: SpeechToTextEngine) {
        prefs.edit().putString(Constants.PREF_STT_ENGINE, engine.id).apply()
    }
}
