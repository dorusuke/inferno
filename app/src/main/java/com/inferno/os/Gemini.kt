package com.inferno.os

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thin REST client for the Gemini API (Google AI Studio key). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    suspend fun generate(
        contents: JSONArray,
        system: String? = null,
        tools: JSONArray? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) error("No Gemini API key. Add GEMINI_API_KEY to local.properties.")
        val body = JSONObject().put("contents", contents)
        if (system != null) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        if (tools != null) body.put("tools", tools)

        val conn = URL(BASE + BuildConfig.GEMINI_MODEL + ":generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("Gemini error $code: ${text.take(300)}")
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
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
