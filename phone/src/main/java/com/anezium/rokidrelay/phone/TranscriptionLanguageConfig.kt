package com.anezium.rokidrelay.phone

import java.util.Locale

/**
 * Forced transcription language for voice replies. AUTO keeps the historical behavior:
 * each engine guesses, seeded with the phone locale where the API accepts a hint.
 *
 * Script matters as much as language here. Cantonese and Taiwan Mandarin must come back in
 * Traditional Chinese, but auto-detection collapses both to Mandarin/Simplified, so each
 * entry carries per-provider codes instead of one BCP-47 tag: ElevenLabs takes ISO codes
 * (`yue` is distinct from `zh`), Azure takes full locales (`zh-HK`/`zh-TW`/`zh-CN` pick the
 * script), OpenAI has no Cantonese label so it is steered through the prompt instead.
 */
enum class TranscriptionLanguage(
    val id: String,
    val label: String,
    val summaryName: String,
    val openAiCode: String? = null,
    val openAiPrompt: String? = null,
    val elevenLabsCode: String? = null,
    val azureLocale: String? = null,
    val androidTag: String? = null,
    val uiNote: String? = null,
) {
    AUTO(
        id = "auto",
        label = "Auto",
        summaryName = "Auto",
        uiNote = "Follows the phone language. Engines may still guess the script on their own.",
    ),
    ENGLISH(
        id = "en",
        label = "English",
        summaryName = "English",
        openAiCode = "en",
        elevenLabsCode = "en",
        azureLocale = "en-US",
        androidTag = "en-US",
    ),
    FRENCH(
        id = "fr",
        label = "Français",
        summaryName = "French",
        openAiCode = "fr",
        elevenLabsCode = "fr",
        azureLocale = "fr-FR",
        androidTag = "fr-FR",
    ),
    GERMAN(
        id = "de",
        label = "Deutsch",
        summaryName = "German",
        openAiCode = "de",
        elevenLabsCode = "de",
        azureLocale = "de-DE",
        androidTag = "de-DE",
    ),
    SPANISH(
        id = "es",
        label = "Español",
        summaryName = "Spanish",
        openAiCode = "es",
        elevenLabsCode = "es",
        azureLocale = "es-ES",
        androidTag = "es-ES",
    ),
    ITALIAN(
        id = "it",
        label = "Italiano",
        summaryName = "Italian",
        openAiCode = "it",
        elevenLabsCode = "it",
        azureLocale = "it-IT",
        androidTag = "it-IT",
    ),
    PORTUGUESE(
        id = "pt",
        label = "Português",
        summaryName = "Portuguese",
        openAiCode = "pt",
        elevenLabsCode = "pt",
        azureLocale = "pt-BR",
        androidTag = "pt-BR",
        uiNote = "Azure and Android use the Brazilian locale (pt-BR).",
    ),
    JAPANESE(
        id = "ja",
        label = "日本語",
        summaryName = "Japanese",
        openAiCode = "ja",
        elevenLabsCode = "ja",
        azureLocale = "ja-JP",
        androidTag = "ja-JP",
    ),
    KOREAN(
        id = "ko",
        label = "한국어",
        summaryName = "Korean",
        openAiCode = "ko",
        elevenLabsCode = "ko",
        azureLocale = "ko-KR",
        androidTag = "ko-KR",
    ),
    CANTONESE(
        id = "yue",
        label = "廣東話",
        summaryName = "Cantonese",
        openAiPrompt = "廣東話語音。請用繁體中文轉寫。",
        elevenLabsCode = "yue",
        azureLocale = "zh-HK",
        androidTag = "yue-Hant-HK",
        uiNote = "ElevenLabs (yue) and Azure (zh-HK) write Traditional Chinese. OpenAI has no Cantonese mode and is prompt-steered only.",
    ),
    CHINESE_TRADITIONAL(
        id = "zh-hant",
        label = "中文繁體",
        summaryName = "Chinese (Traditional)",
        openAiCode = "zh",
        openAiPrompt = "請使用繁體中文。",
        elevenLabsCode = "zh",
        azureLocale = "zh-TW",
        androidTag = "zh-Hant-TW",
        uiNote = "Azure (zh-TW) guarantees Traditional script. ElevenLabs picks the script itself for Mandarin.",
    ),
    CHINESE_SIMPLIFIED(
        id = "zh-hans",
        label = "中文简体",
        summaryName = "Chinese (Simplified)",
        openAiCode = "zh",
        openAiPrompt = "请使用简体中文。",
        elevenLabsCode = "zh",
        azureLocale = "zh-CN",
        androidTag = "zh-Hans-CN",
    ),
    ;

    companion object {
        fun fromId(id: String?): TranscriptionLanguage {
            val normalized = id.orEmpty().trim().lowercase(Locale.US)
            return values().firstOrNull { it.id == normalized } ?: AUTO
        }
    }
}
