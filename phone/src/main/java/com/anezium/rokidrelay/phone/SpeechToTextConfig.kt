package com.anezium.rokidrelay.phone

import android.content.Context

enum class SpeechToTextProvider(
    val displayName: String,
) {
    ANDROID("Android"),
    OPENAI("OpenAI"),
    ELEVENLABS("ElevenLabs"),
    AZURE("Azure"),
}

enum class SpeechToTextCredentialKind {
    NONE,
    OPENAI,
    ELEVENLABS,
    AZURE,
}

enum class SpeechToTextEngine(
    val id: String,
    val provider: SpeechToTextProvider,
    val displayName: String,
    val shortLabel: String,
    val choiceDescription: String,
    val choiceBadges: List<String>,
    val credentialKind: SpeechToTextCredentialKind,
    val completedAudioModelId: String? = null,
    val realtimeModelId: String? = null,
    val requiresMicrophonePermission: Boolean = false,
) {
    ANDROID_CXR(
        id = "android_cxr",
        provider = SpeechToTextProvider.ANDROID,
        displayName = "Android CXR",
        shortLabel = "Android CXR",
        choiceDescription = "Uses Android speech recognition with injected glasses audio.",
        choiceBadges = listOf("On device", "CXR audio pipe"),
        credentialKind = SpeechToTextCredentialKind.NONE,
        requiresMicrophonePermission = true,
    ),
    OPENAI_GPT_REALTIME_WHISPER(
        id = "openai_gpt_realtime_whisper",
        provider = SpeechToTextProvider.OPENAI,
        displayName = "OpenAI GPT Realtime Whisper",
        shortLabel = "RT Whisper",
        choiceDescription = "Best when you want words to appear while you speak.",
        choiceBadges = listOf("Realtime", "Low delay", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.OPENAI,
        realtimeModelId = "gpt-realtime-whisper",
    ),
    OPENAI_GPT_4O_TRANSCRIBE(
        id = "openai_gpt_4o_transcribe",
        provider = SpeechToTextProvider.OPENAI,
        displayName = "OpenAI GPT-4o Transcribe",
        shortLabel = "GPT-4o",
        choiceDescription = "Best accuracy for longer replies after you finish speaking.",
        choiceBadges = listOf("Buffered", "Most accurate", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-transcribe",
    ),
    OPENAI_GPT_4O_MINI_TRANSCRIBE(
        id = "openai_gpt_4o_mini_transcribe",
        provider = SpeechToTextProvider.OPENAI,
        displayName = "OpenAI GPT-4o mini Transcribe",
        shortLabel = "GPT-4o mini",
        choiceDescription = "Good everyday choice when cost matters more than top accuracy.",
        choiceBadges = listOf("Buffered", "Lower cost", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.OPENAI,
        completedAudioModelId = "gpt-4o-mini-transcribe",
    ),
    ELEVENLABS_SCRIBE_V2_REALTIME(
        id = "elevenlabs_scribe_v2_realtime",
        provider = SpeechToTextProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2 Realtime",
        shortLabel = "Scribe RT",
        choiceDescription = "Best for live captions with ElevenLabs voice accounts.",
        choiceBadges = listOf("Realtime", "Low delay", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.ELEVENLABS,
        realtimeModelId = "scribe_v2_realtime",
    ),
    ELEVENLABS_SCRIBE_V2(
        id = "elevenlabs_scribe_v2",
        provider = SpeechToTextProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2",
        shortLabel = "Scribe v2",
        choiceDescription = "Balanced accuracy for replies sent after you stop speaking.",
        choiceBadges = listOf("Buffered", "Balanced", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v2",
    ),
    ELEVENLABS_SCRIBE_V1(
        id = "elevenlabs_scribe_v1",
        provider = SpeechToTextProvider.ELEVENLABS,
        displayName = "ElevenLabs Scribe v1",
        shortLabel = "Scribe v1",
        choiceDescription = "Legacy option for older ElevenLabs setups.",
        choiceBadges = listOf("Buffered", "Legacy", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.ELEVENLABS,
        completedAudioModelId = "scribe_v1",
    ),
    AZURE_SPEECH(
        id = "azure_speech",
        provider = SpeechToTextProvider.AZURE,
        displayName = "Azure Speech to Text",
        shortLabel = "Azure STT",
        choiceDescription = "Transcribes after you finish speaking — not realtime, no live text.",
        choiceBadges = listOf("Buffered", "Free 5 h/mo", "Cloud audio"),
        credentialKind = SpeechToTextCredentialKind.AZURE,
        completedAudioModelId = "azure-conversation",
    );

    val usesCompletedAudio: Boolean
        get() = completedAudioModelId != null

    val usesRealtime: Boolean
        get() = realtimeModelId != null

    val usesApiAudio: Boolean
        get() = usesCompletedAudio || usesRealtime

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
            SpeechToTextEngine.OPENAI_GPT_REALTIME_WHISPER
        } else if (!credentials.apiKey(SpeechToTextCredentialKind.ELEVENLABS).isNullOrBlank()) {
            SpeechToTextEngine.ELEVENLABS_SCRIBE_V2_REALTIME
        } else if (!credentials.apiKey(SpeechToTextCredentialKind.AZURE).isNullOrBlank()) {
            SpeechToTextEngine.AZURE_SPEECH
        } else {
            SpeechToTextEngine.ANDROID_CXR
        }
    }

    fun saveSelectedEngine(engine: SpeechToTextEngine) {
        prefs.edit()
            .putString(Constants.PREF_STT_ENGINE, engine.id)
            .also { editor ->
                if (engine == SpeechToTextEngine.ANDROID_CXR) {
                    editor.putString(Constants.PREF_STT_LANGUAGE, TranscriptionLanguage.AUTO.id)
                }
            }
            .apply()
    }

    fun selectedLanguage(): TranscriptionLanguage =
        TranscriptionLanguage.fromId(prefs.getString(Constants.PREF_STT_LANGUAGE, null))

    fun selectedLanguageForEngine(engine: SpeechToTextEngine): TranscriptionLanguage {
        val selected = selectedLanguage()
        if (engine != SpeechToTextEngine.ANDROID_CXR || selected == TranscriptionLanguage.AUTO) {
            return selected
        }
        saveSelectedLanguage(TranscriptionLanguage.AUTO)
        return TranscriptionLanguage.AUTO
    }

    fun saveSelectedLanguage(language: TranscriptionLanguage) {
        prefs.edit().putString(Constants.PREF_STT_LANGUAGE, language.id).apply()
    }
}
