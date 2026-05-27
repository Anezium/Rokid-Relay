package com.anezium.rokidrelay.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CompletedAudioSpeechToTextInput(
    val pcm16Mono: ByteArray,
    val sampleRate: Int,
    val languageTag: String,
)

interface CompletedAudioSpeechToTextEngine {
    fun transcribe(input: CompletedAudioSpeechToTextInput): String
    fun cancel()
}

class ApiCompletedAudioSpeechToTextEngine(
    private val credentialStore: SttCredentialStore,
    private val engine: SpeechToTextEngine,
) : CompletedAudioSpeechToTextEngine {
    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun transcribe(input: CompletedAudioSpeechToTextInput): String {
        require(input.pcm16Mono.size >= MIN_AUDIO_BYTES) { "Not enough audio captured" }
        require(engine.usesCompletedAudio) { "${engine.displayName} is not a completed-audio STT engine" }
        val model = engine.completedAudioModelId ?: error("${engine.displayName} has no model id")
        val wav = Pcm16Wav.encode(input.pcm16Mono, input.sampleRate)
        return when (engine.provider) {
            SpeechToTextProvider.OPENAI -> transcribeOpenAi(model, wav)
            SpeechToTextProvider.ELEVENLABS -> transcribeElevenLabs(model, wav)
            SpeechToTextProvider.ANDROID -> error("${engine.displayName} is not an API STT engine")
        }
    }

    override fun cancel() {
        activeConnection?.disconnect()
        activeConnection = null
    }

    private fun transcribeOpenAi(model: String, wav: ByteArray): String {
        val apiKey = credentialStore.apiKey(SpeechToTextCredentialKind.OPENAI)?.trim().orEmpty()
        require(apiKey.isNotBlank()) { "OpenAI STT key missing" }
        val connection = (URL(OPENAI_TRANSCRIPTIONS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        activeConnection = connection
        return try {
            connection.useMultipart("OpenAI STT") { boundary ->
                writeField(boundary, "model", model)
                writeField(boundary, "response_format", "json")
                writeField(
                    boundary,
                    "prompt",
                    "Transcribe a short voice reply captured from Rokid smart glasses. Preserve the spoken language.",
                )
                writeFile(
                    boundary = boundary,
                    name = "file",
                    filename = "rokid-relay-reply.wav",
                    contentType = "audio/wav",
                    bytes = wav,
                )
            }.extractTranscript()
        } finally {
            activeConnection = null
        }
    }

    private fun transcribeElevenLabs(model: String, wav: ByteArray): String {
        val apiKey = credentialStore.apiKey(SpeechToTextCredentialKind.ELEVENLABS)?.trim().orEmpty()
        require(apiKey.isNotBlank()) { "ElevenLabs STT key missing" }
        val connection = (URL(ELEVENLABS_SPEECH_TO_TEXT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
        }
        activeConnection = connection
        return try {
            connection.useMultipart("ElevenLabs STT") { boundary ->
                writeField(boundary, "model_id", model)
                writeFile(
                    boundary = boundary,
                    name = "file",
                    filename = "rokid-relay-reply.wav",
                    contentType = "audio/wav",
                    bytes = wav,
                )
            }.extractTranscript()
        } finally {
            activeConnection = null
        }
    }

    private fun String.extractTranscript(): String {
        val json = runCatching { JSONObject(this) }.getOrElse {
            return trim().ifBlank { error("OpenAI STT returned an empty response") }
        }
        val error = json.optJSONObject("error")
        if (error != null) {
            error(error.optString("message", "OpenAI STT failed"))
        }
        return json.optString("text").trim().ifBlank {
            error("OpenAI STT returned no transcript")
        }
    }

    private fun HttpURLConnection.useMultipart(label: String, write: MultipartWriter.(String) -> Unit): String {
        val boundary = "----RokidRelay${UUID.randomUUID().toString().replace("-", "")}"
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            val writer = MultipartWriter(outputStream.buffered())
            writer.write(boundary, write)
            val status = responseCode
            val body = runCatching {
                (if (status in 200..299) inputStream else errorStream ?: inputStream)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrDefault("")
            if (status !in 200..299) {
                error("$label failed ($status): ${body.take(420).ifBlank { "no error body" }}")
            }
            return body
        } finally {
            disconnect()
        }
    }

    private class MultipartWriter(private val stream: java.io.OutputStream) {
        fun write(boundary: String, block: MultipartWriter.(String) -> Unit) {
            DataOutputStream(stream).use { dataStream ->
                output = dataStream
                block(boundary)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }
        }

        fun writeField(boundary: String, name: String, value: String) {
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            output.writeBytes(value)
            output.writeBytes("\r\n")
        }

        fun writeFile(
            boundary: String,
            name: String,
            filename: String,
            contentType: String,
            bytes: ByteArray,
        ) {
            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
            output.writeBytes("Content-Type: $contentType\r\n\r\n")
            output.write(bytes)
            output.writeBytes("\r\n")
        }

        private lateinit var output: DataOutputStream
    }

    private companion object {
        const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val ELEVENLABS_SPEECH_TO_TEXT_URL = "https://api.elevenlabs.io/v1/speech-to-text"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
        const val MIN_AUDIO_BYTES = 3_200
    }
}

class SttCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun hasOpenAiApiKey(): Boolean = !openAiApiKey().isNullOrBlank()

    fun hasCredential(engine: SpeechToTextEngine): Boolean =
        !apiKey(engine.credentialKind).isNullOrBlank()

    fun apiKey(kind: SpeechToTextCredentialKind): String? {
        val raw = prefs.getString(kind.keyPreferenceName() ?: return null, null) ?: return null
        return runCatching {
            SttKeystoreAesGcm.decrypt(JSONObject(raw))
                .optString("apiKey")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun openAiApiKey(): String? = apiKey(SpeechToTextCredentialKind.OPENAI)

    fun openAiAccountLabel(): String? =
        accountLabel(SpeechToTextCredentialKind.OPENAI)
            ?: openAiApiKey()?.redactedSttKey()

    fun accountLabel(kind: SpeechToTextCredentialKind): String? {
        val labelName = kind.labelPreferenceName() ?: return null
        return prefs.getString(labelName, null)
            ?: apiKey(kind)?.redactedSttKey()
    }

    fun saveApiKey(kind: SpeechToTextCredentialKind, apiKey: String) {
        val keyName = kind.keyPreferenceName() ?: error("No API key is required for this STT engine")
        val labelName = kind.labelPreferenceName() ?: error("No API key is required for this STT engine")
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "API key is required" }
        val encrypted = SttKeystoreAesGcm.encrypt(JSONObject().put("apiKey", cleanKey))
        prefs.edit()
            .putString(keyName, encrypted.toString())
            .putString(labelName, cleanKey.redactedSttKey())
            .apply()
    }

    fun saveOpenAiApiKey(apiKey: String) = saveApiKey(SpeechToTextCredentialKind.OPENAI, apiKey)

    fun clearApiKey(kind: SpeechToTextCredentialKind) {
        val keyName = kind.keyPreferenceName() ?: return
        val labelName = kind.labelPreferenceName() ?: return
        prefs.edit()
            .remove(keyName)
            .remove(labelName)
            .apply()
    }

    fun clearOpenAiApiKey() = clearApiKey(SpeechToTextCredentialKind.OPENAI)
}

private fun SpeechToTextCredentialKind.keyPreferenceName(): String? =
    when (this) {
        SpeechToTextCredentialKind.NONE -> null
        SpeechToTextCredentialKind.OPENAI -> Constants.PREF_STT_OPENAI_KEY
        SpeechToTextCredentialKind.ELEVENLABS -> Constants.PREF_STT_ELEVENLABS_KEY
    }

private fun SpeechToTextCredentialKind.labelPreferenceName(): String? =
    when (this) {
        SpeechToTextCredentialKind.NONE -> null
        SpeechToTextCredentialKind.OPENAI -> Constants.PREF_STT_OPENAI_LABEL
        SpeechToTextCredentialKind.ELEVENLABS -> Constants.PREF_STT_ELEVENLABS_LABEL
    }

private object Pcm16Wav {
    fun encode(pcm16Mono: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm16Mono.size
        val byteRate = sampleRate * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream(WAV_HEADER_BYTES + dataSize).apply {
            writeAscii("RIFF")
            writeIntLe(36 + dataSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLe(16)
            writeShortLe(1)
            writeShortLe(CHANNEL_COUNT)
            writeIntLe(sampleRate)
            writeIntLe(byteRate)
            writeShortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            writeShortLe(BITS_PER_SAMPLE)
            writeAscii("data")
            writeIntLe(dataSize)
            write(pcm16Mono)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private const val WAV_HEADER_BYTES = 44
    private const val CHANNEL_COUNT = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16
}

private object SttKeystoreAesGcm {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rokid_relay_stt_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun encrypt(json: JSONObject): JSONObject {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return JSONObject()
            .put("version", 1)
            .put("iv", cipher.iv.toBase64())
            .put("ciphertext", cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)).toBase64())
    }

    fun decrypt(json: JSONObject): JSONObject {
        val iv = json.getString("iv").fromBase64()
        val ciphertext = json.getString("ciphertext").fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}

private fun String.redactedSttKey(): String {
    val clean = trim()
    if (clean.length <= 12) return "saved key"
    return "${clean.take(8)}...${clean.takeLast(4)}"
}
