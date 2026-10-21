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
    private const val TTS_FALLBACK = "gemini-2.5-flash-preview-tts"
    @Volatile private var ttsModel: String? = null

    private class ApiError(val code: Int, msg: String) : Exception(msg)

    suspend fun generate(
        contents: JSONArray,
        system: String? = null,
        tools: JSONArray? = null,
        model: String = BuildConfig.GEMINI_MODEL,
        config: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val bearer = GoogleAuth.bearer()
        val key = Keys.current
        if (bearer == null && key.isBlank()) {
            error(
                if (GoogleAuth.signedIn) {
                    "Not signed in. The Google session expired or was refused. Open your profile and sign in again, or add an API key."
                } else {
                    "Not signed in. Open your profile in the app and sign in or add an API key."
                }
            )
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

        val json = try {
            if (bearer != null) call(body, model, bearer, null) else call(body, model, null, key)
        } catch (e: ApiError) {
            // Google account refused (API not enabled, no access, expired): retry with the API key if there is one.
            if (bearer != null && key.isNotBlank() && (e.code == 401 || e.code == 403)) {
                call(body, model, null, key)
            } else {
                throw e
            }
        }
        Usage.record(json.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L) ?: 0L)
        json
    }

    private fun call(body: JSONObject, model: String, bearer: String?, key: String?): JSONObject {
        val conn = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer $bearer")
            } else if (key != null) {
                conn.setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val who = if (bearer != null) "Google account" else "API key"
                throw ApiError(code, "Gemini error $code ($who): ${text.take(400)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Asks the API which speech models exist (names change often) and picks a flash one. */
    private suspend fun pickTtsModel(): String = withContext(Dispatchers.IO) {
        ttsModel?.let { return@withContext it }
        val found = try {
            val bearer = GoogleAuth.bearer()
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .openConnection() as HttpURLConnection
            try {
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                else if (Keys.current.isNotBlank()) conn.setRequestProperty("x-goog-api-key", Keys.current)
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models")
                val names = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).optString("name").removePrefix("models/") }
                    .filter { it.contains("tts") }
                names.firstOrNull { it.contains("flash") && !it.contains("preview") }
                    ?: names.firstOrNull { it.contains("flash") } ?: names.firstOrNull()
            } finally { conn.disconnect() }
        } catch (e: Exception) { null }
        (found ?: TTS_FALLBACK).also { ttsModel = it }
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
        val resp = generate(JSONArray().put(userText(text)), model = pickTtsModel(), config = cfg)
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
