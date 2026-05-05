package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient()

    /**
     * Default proper-noun hint passed to Whisper as the `prompt` parameter to bias
     * transcription toward correct spelling of names. Carried over from
     * whisper-writer/src/config.yaml line 6. Editable from the Settings screen.
     */
    const val DEFAULT_PROMPT_HINT =
        "Jasmine Journeys, JJ, Amado, Assagao, OwnerRez, PriceLabs, Plaud, Soniox, " +
        "Adhiraj, Kanika, Preksha Shah, Vinu Daniel, Joppan, Wallmakers"

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
}
