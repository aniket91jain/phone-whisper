package com.kafkasl.phonewhisper

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    // Long dictations can produce multi-MB WAV uploads and Groq processing time
    // scales with audio length. Default OkHttp timeouts (10 s) fail on anything
    // over ~30 s of speech. The ceilings here support up to ~15 min of audio,
    // which is the practical limit before Groq's 25 MB file-size cap kicks in.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * Default proper-noun hint passed to Whisper as the `prompt` parameter to bias
     * transcription toward correct spelling of names. Carried over from
     * whisper-writer/src/config.yaml line 6. Editable from the Settings screen.
     */
    const val DEFAULT_PROMPT_HINT =
        "Jasmine Journeys, JJ, Amado, Assagao, OwnerRez, PriceLabs, Plaud, Soniox, " +
        "Adhiraj, Kanika, Preksha Shah, Vinu Daniel, Joppan, Wallmakers"

    // Groq's hard limit for the audio file is 25 MB. Chunk at 20 MB to leave headroom
    // for the multipart form overhead, the WAV header on each chunk, etc.
    private const val MAX_CHUNK_BYTES = 20 * 1024 * 1024
    private const val WAV_HEADER_SIZE = 44
    private const val TAG = "TranscriberClient"

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        promptHint: String,
        callback: (Result) -> Unit
    ) {
        if (wavData.size <= MAX_CHUNK_BYTES) {
            transcribeOne(wavData, apiKey, promptHint, callback)
        } else {
            transcribeChunked(wavData, apiKey, promptHint, callback)
        }
    }

    /** Single-shot transcription request — used directly for short audio and per-chunk for long audio. */
    private fun transcribeOne(
        wavData: ByteArray,
        apiKey: String,
        promptHint: String,
        callback: (Result) -> Unit
    ) {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            // Match WhisperWriter's STT settings: force English so non-Latin proper nouns
            // (Indian English names etc.) aren't mis-routed through other languages.
            .addFormDataPart("language", "en")
            .addFormDataPart("temperature", "0")

        if (promptHint.isNotBlank()) {
            bodyBuilder.addFormDataPart("prompt", promptHint)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) =
                callback(parseResponse(response.body?.string() ?: ""))
        })
    }

    /**
     * Transcribe a WAV that exceeds Groq's 25 MB single-request limit. Strips the WAV
     * header, splits the PCM body into <= 20 MB chunks at sample boundaries, re-encodes
     * each chunk as its own WAV, fires all chunk requests in parallel, and concatenates
     * the resulting transcripts in order.
     *
     * Chunks split at fixed byte boundaries (not silence), so words at chunk edges may
     * occasionally be cut. Acceptable trade-off for now — silence-aware splitting can
     * be added later if needed.
     */
    private fun transcribeChunked(
        wavData: ByteArray,
        apiKey: String,
        promptHint: String,
        callback: (Result) -> Unit
    ) {
        if (wavData.size < WAV_HEADER_SIZE + 2) {
            callback(Result(null, "audio too small to chunk"))
            return
        }

        val pcm = wavData.copyOfRange(WAV_HEADER_SIZE, wavData.size)
        // Each chunk gets its own 44-byte header re-added by WavWriter.encode, so
        // budget for that. Round down to an even boundary so we never split a 16-bit
        // sample mid-byte (or mid-frame for stereo, though we only record mono).
        val maxPcmPerChunk = ((MAX_CHUNK_BYTES - WAV_HEADER_SIZE) / 2) * 2

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + maxPcmPerChunk, pcm.size)
            chunks.add(pcm.copyOfRange(offset, end))
            offset = end
        }

        Log.i(TAG, "Chunking ${wavData.size}-byte WAV into ${chunks.size} pieces of <=$maxPcmPerChunk PCM bytes each")

        val numChunks = chunks.size
        val transcripts = arrayOfNulls<String>(numChunks)
        val errors = arrayOfNulls<String>(numChunks)
        val pending = AtomicInteger(numChunks)

        for ((index, pcmChunk) in chunks.withIndex()) {
            val chunkWav = WavWriter.encode(pcmChunk)
            transcribeOne(chunkWav, apiKey, promptHint) { result ->
                if (result.text != null) {
                    transcripts[index] = result.text
                } else {
                    errors[index] = result.error
                    Log.w(TAG, "Chunk $index failed: ${result.error}")
                }
                if (pending.decrementAndGet() == 0) {
                    onAllChunksDone(transcripts, errors, callback)
                }
            }
        }
    }

    private fun onAllChunksDone(
        transcripts: Array<String?>,
        errors: Array<String?>,
        callback: (Result) -> Unit
    ) {
        val combined = transcripts
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .joinToString(" ")
        val firstError = errors.firstOrNull { it != null }
        if (combined.isBlank()) {
            callback(Result(null, firstError ?: "all chunks empty"))
        } else if (firstError != null) {
            // Partial success — got some text, but at least one chunk failed.
            // Surface the partial text so the user keeps most of their dictation,
            // but include the error in the message so they know it's incomplete.
            callback(Result(combined, "partial: chunk failed ($firstError)"))
        } else {
            callback(Result(combined, null))
        }
    }
}
