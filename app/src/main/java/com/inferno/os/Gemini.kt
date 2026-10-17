package com.inferno.os

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thin REST client for the Gemini API. Uses the Google account if signed in, else the API key. */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val TTS_MODEL = "gemini-2.5-flash-preview-tts"

    suspend fun generate(
        contents: JSONArray,
        system: String? = null,
        tools: JSONArray? = null,
        model: String = BuildConfig.GEMINI_MODEL,
        config: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val bearer = GoogleAuth.bearer()
        if (bearer == null && Keys.current.isBlank()) {
            error("Not signed in. Open your profile in the app and sign in or add an API key.")
        }
        val body = JSONObject().put("contents", contents)
        if (system != null) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        if (tools != null) body.put("tools", tools)
        if (config != null) body.put("generationConfig", config)

        val conn = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer $bearer")
            } else {
                conn.setRequestProperty("x-goog-api-key", Keys.current)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("Gemini error $code: ${text.take(300)}")
            val json = JSONObject(text)
            Usage.record(json.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L) ?: 0L)
            json
        } finally {
            conn.disconnect()
        }
    }

    /** Speech from a Gemini voice. Returns raw 16-bit mono PCM at 24 kHz. */
    suspend fun tts(text: String, voice: String): ByteArray {
        val cfg = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                )
            )
        val resp = generate(JSONArray().put(userText(text)), model = TTS_MODEL, config = cfg)
        val data = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
            ?.optJSONObject("inlineData")?.optString("data")
        if (data.isNullOrEmpty()) error("Gemini returned no audio.")
        return Base64.decode(data, Base64.DEFAULT)
    }

    fun userText(text: String): JSONObject =
        JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun textOf(resp: JSONObject): String {
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val t = parts.getJSONObject(i).optString("text")
                if (t.isNotEmpty()) append(t)
            }
        }.trim()
    }
}
