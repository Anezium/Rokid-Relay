package com.anezium.rokidrelay.phone

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

interface RealtimeSpeechToTextSession {
    fun start(languageTag: String, sampleRate: Int)
    fun sendAudio(data: ByteArray, offset: Int, length: Int)
    fun finishAudio()
    fun cancel()
}

interface RealtimeSpeechToTextListener {
    fun onReady()
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(message: String)
}

object ApiRealtimeSpeechToText {
    fun create(
        credentialStore: SttCredentialStore,
        engine: SpeechToTextEngine,
        listener: RealtimeSpeechToTextListener,
        forcedLanguage: TranscriptionLanguage = TranscriptionLanguage.AUTO,
    ): RealtimeSpeechToTextSession {
        require(engine.usesRealtime) { "${engine.displayName} is not a realtime STT engine" }
        val model = engine.realtimeModelId ?: error("${engine.displayName} has no realtime model id")
        return when (engine.provider) {
            SpeechToTextProvider.OPENAI -> OpenAiRealtimeSpeechToTextSession(
                apiKey = credentialStore.apiKey(SpeechToTextCredentialKind.OPENAI)?.trim().orEmpty(),
                model = model,
                listener = listener,
                forcedLanguage = forcedLanguage,
            )
            SpeechToTextProvider.ELEVENLABS -> ElevenLabsRealtimeSpeechToTextSession(
                apiKey = credentialStore.apiKey(SpeechToTextCredentialKind.ELEVENLABS)?.trim().orEmpty(),
                model = model,
                listener = listener,
                forcedLanguage = forcedLanguage,
            )
            SpeechToTextProvider.AZURE,
            SpeechToTextProvider.ANDROID,
            -> error("${engine.displayName} is not an API realtime STT engine")
        }
    }
}

private class OpenAiRealtimeSpeechToTextSession(
    private val apiKey: String,
    private val model: String,
    private val listener: RealtimeSpeechToTextListener,
    private val forcedLanguage: TranscriptionLanguage,
) : WebSocketListener(), RealtimeSpeechToTextSession {
    private val client = realtimeHttpClient()
    private val chunker = PcmChunker(INPUT_CHUNK_BYTES) { chunk ->
        sendChunk(Pcm16Resampler.upsample16kTo24k(chunk))
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var sessionUpdateSent = false
    private var language: String? = null

    override fun start(languageTag: String, sampleRate: Int) {
        require(apiKey.isNotBlank()) { "OpenAI STT key missing" }
        if (sampleRate != INPUT_SAMPLE_RATE) {
            listener.onError("OpenAI realtime expects 16 kHz CXR PCM input")
            return
        }
        language = forcedLanguage.openAiCode ?: languageCode(languageTag)
        val request = Request.Builder()
            .url("$OPENAI_REALTIME_URL?intent=transcription")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, this)
    }

    override fun sendAudio(data: ByteArray, offset: Int, length: Int) {
        if (!closed) chunker.append(data, offset, length)
    }

    override fun finishAudio() {
        if (closed) return
        chunker.flush()
        socket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
    }

    override fun cancel() {
        closed = true
        chunker.clear()
        socket?.close(1000, "cancel")
        socket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "session.created" -> sendSessionUpdate(webSocket)
            "session.updated" -> emitReady()
            "conversation.item.input_audio_transcription.delta" ->
                listener.onPartial(obj.optString("delta"))
            "conversation.item.input_audio_transcription.completed" ->
                listener.onFinal(obj.optString("transcript"))
            "conversation.item.input_audio_transcription.failed" ->
                listener.onError(obj.errorMessage("OpenAI realtime STT failed"))
            "error" -> {
                Log.w("RelayRealtimeStt", "OpenAI event error: ${obj.toString().take(500)}")
                listener.onError(obj.errorMessage("OpenAI realtime STT failed"))
            }
        }
    }

    private fun sendSessionUpdate(webSocket: WebSocket) {
        if (sessionUpdateSent || closed) return
        sessionUpdateSent = true
        webSocket.send(openAiSessionUpdate(model, language, forcedLanguage.openAiPrompt).toString())
    }

    private fun emitReady() {
        if (ready || closed) return
        ready = true
        listener.onReady()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!closed) listener.onError(t.message ?: "OpenAI realtime STT failed")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed) listener.onError(reason.ifBlank { "OpenAI realtime STT closed" })
    }

    private fun sendChunk(bytes: ByteArray) {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        socket?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", encoded)
                .toString(),
        )
    }

    private companion object {
        const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val INPUT_SAMPLE_RATE = 16_000
        const val INPUT_CHUNK_BYTES = 3_200
    }
}

private class ElevenLabsRealtimeSpeechToTextSession(
    private val apiKey: String,
    private val model: String,
    private val listener: RealtimeSpeechToTextListener,
    private val forcedLanguage: TranscriptionLanguage,
) : WebSocketListener(), RealtimeSpeechToTextSession {
    private val client = realtimeHttpClient()
    private val chunker = PcmChunker(CHUNK_BYTES) { chunk -> sendChunk(chunk, commit = false) }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false

    override fun start(languageTag: String, sampleRate: Int) {
        require(apiKey.isNotBlank()) { "ElevenLabs STT key missing" }
        if (sampleRate != SAMPLE_RATE) {
            listener.onError("ElevenLabs realtime expects 16 kHz CXR PCM input")
            return
        }
        val language = forcedLanguage.elevenLabsCode ?: languageCode(languageTag)
        val request = Request.Builder()
            .url(elevenLabsRealtimeUrl(model, language))
            .addHeader("xi-api-key", apiKey)
            .build()
        socket = client.newWebSocket(request, this)
    }

    override fun sendAudio(data: ByteArray, offset: Int, length: Int) {
        if (!closed) chunker.append(data, offset, length)
    }

    override fun finishAudio() {
        if (closed) return
        val remainder = chunker.drain()
        sendChunk(remainder ?: ByteArray(0), commit = true)
    }

    override fun cancel() {
        closed = true
        chunker.clear()
        socket?.close(1000, "cancel")
        socket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("message_type")) {
            "session_started" -> emitReady()
            "partial_transcript" -> listener.onPartial(obj.optString("text"))
            "committed_transcript",
            "committed_transcript_with_timestamps",
            -> listener.onFinal(obj.optString("text"))
            "auth_error",
            "quota_exceeded",
            "throttled",
            "rate_limited",
            "unaccepted_terms",
            "unaccepted_terms_error",
            "queue_overflow",
            "queue_overflow_error",
            "resource_exhausted",
            "resource_exhausted_error",
            "session_time_limit_exceeded_error",
            "input_error",
            "chunk_size_exceeded",
            "insufficient_audio_activity",
            "transcriber_error",
            "commit_throttled",
            "error",
            -> listener.onError(obj.errorMessage("ElevenLabs realtime STT failed"))
        }
    }

    private fun emitReady() {
        if (ready || closed) return
        ready = true
        listener.onReady()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!closed) listener.onError(t.message ?: "ElevenLabs realtime STT failed")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed) listener.onError(reason.ifBlank { "ElevenLabs realtime STT closed" })
    }

    private fun sendChunk(bytes: ByteArray, commit: Boolean) {
        val payload = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("sample_rate", SAMPLE_RATE)
            .put("commit", commit)
        socket?.send(payload.toString())
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 3_200
    }
}

private class PcmChunker(
    private val chunkBytes: Int,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val safeOffset = offset.coerceIn(0, data.size)
        val safeLength = length.coerceAtMost(data.size - safeOffset)
        if (safeLength <= 0) return
        buffer.write(data, safeOffset, safeLength)
        drainFullChunks()
    }

    @Synchronized
    fun flush() {
        drainFullChunks()
        drain()?.let(onChunk)
    }

    @Synchronized
    fun drain(): ByteArray? {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return bytes.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun clear() {
        buffer.reset()
    }

    private fun drainFullChunks() {
        while (buffer.size() >= chunkBytes) {
            val bytes = buffer.toByteArray()
            val chunk = bytes.copyOfRange(0, chunkBytes)
            val rest = bytes.copyOfRange(chunkBytes, bytes.size)
            buffer.reset()
            buffer.write(rest)
            onChunk(chunk)
        }
    }
}

private object Pcm16Resampler {
    fun upsample16kTo24k(input: ByteArray): ByteArray {
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = inputSamples * 3 / 2
        val output = ByteArray(outputSamples * 2)
        for (outIndex in 0 until outputSamples) {
            val sourcePosition = outIndex * 2.0 / 3.0
            val baseIndex = sourcePosition.toInt().coerceAtMost(inputSamples - 1)
            val nextIndex = (baseIndex + 1).coerceAtMost(inputSamples - 1)
            val fraction = sourcePosition - baseIndex
            val sample = sampleAt(input, baseIndex) +
                ((sampleAt(input, nextIndex) - sampleAt(input, baseIndex)) * fraction)
            writeSample(output, outIndex, sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        return output
    }

    private fun sampleAt(bytes: ByteArray, index: Int): Int {
        val byteIndex = index * 2
        val low = bytes[byteIndex].toInt() and 0xff
        val high = bytes[byteIndex + 1].toInt()
        return (high shl 8) or low
    }

    private fun writeSample(bytes: ByteArray, index: Int, sample: Int) {
        val byteIndex = index * 2
        bytes[byteIndex] = (sample and 0xff).toByte()
        bytes[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
    }
}

private fun openAiSessionUpdate(model: String, language: String?, prompt: String? = null): JSONObject {
    val transcription = JSONObject()
        .put("model", model)
        .put("delay", "low")
    if (!language.isNullOrBlank()) transcription.put("language", language)
    if (!prompt.isNullOrBlank()) transcription.put("prompt", prompt)
    return JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject()
                            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                            .put("transcription", transcription)
                            .put("turn_detection", JSONObject.NULL),
                    ),
                ),
        )
}

private fun elevenLabsRealtimeUrl(model: String, language: String?): String {
    val query = mutableListOf(
        "model_id=${model.urlEncoded()}",
        "audio_format=pcm_16000",
        "commit_strategy=manual",
        "include_timestamps=false",
    )
    if (!language.isNullOrBlank()) query += "language_code=${language.urlEncoded()}"
    return "wss://api.elevenlabs.io/v1/speech-to-text/realtime?${query.joinToString("&")}"
}

private fun realtimeHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

private fun JSONObject.errorMessage(fallback: String): String {
    val nested = optJSONObject("error")
    val message = nested?.optString("message").orEmpty()
        .ifBlank { optString("message") }
        .ifBlank { optString("error") }
        .ifBlank { fallback }
    return message
}

private fun languageCode(languageTag: String): String? {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.US)
    return language.takeIf { it.length in 2..3 }
}

private fun String.urlEncoded(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name())

@Suppress("unused")
private fun logRealtimeEvent(provider: String, text: String) {
    Log.v("RelayRealtimeStt", "$provider event ${text.take(80)}")
}
