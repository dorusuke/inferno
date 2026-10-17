package com.inferno.os

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Keeps the conversation and lets Gemini call phone actions as tools. */
class Brain(private val actions: PhoneActions) {
    private val history = JSONArray()

    suspend fun ask(text: String): String = send(Gemini.userText(text), false)

    /** A spoken command as WAV audio. Gemini hears it directly. */
    suspend fun askAudio(wav: ByteArray): String {
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("text", "The user just spoke this to you. Respond to what they said. Ignore the wake phrase Hey Inferno."))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", b64)))
        return send(JSONObject().put("role", "user").put("parts", parts), true)
    }

    private suspend fun send(userContent: JSONObject, audio: Boolean): String {
        val mark = history.length()
        try {
            history.put(userContent)
            trim()
            val userIdx = history.length() - 1
            for (step in 0 until 5) {
                val resp = Gemini.generate(history, system(), TOOLS)
                val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?: return "Sorry, I didn't get an answer to that."
                history.put(content) // replayed untouched so thought signatures survive
                val parts = content.optJSONArray("parts") ?: JSONArray()
                val calls = (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { it.has("functionCall") }
                if (calls.isEmpty()) {
                    // Keep history light: swap the audio for a text marker once it has been answered.
                    if (audio) history.put(userIdx, Gemini.userText("(voice message)"))
                    return Gemini.textOf(resp).ifBlank { "Done." }
                }

                val results = JSONArray()
                for (c in calls) {
                    val fc = c.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val out = actions.run(name, fc.optJSONObject("args") ?: JSONObject())
                    results.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name)
                                .put("response", JSONObject().put("result", out))
                        )
                    )
                }
                history.put(JSONObject().put("role", "user").put("parts", results))
            }
            return "Sorry, that took too many steps."
        } catch (e: Exception) {
            // Never leave a dangling tool call in history, the API would reject every later turn.
            while (history.length() > mark) history.remove(history.length() - 1)
            throw e
        }
    }

    private fun trim() {
        while (history.length() > 24) history.remove(0)
        while (history.length() > 0 && !isPlainUser(history.getJSONObject(0))) history.remove(0)
    }

    private fun isPlainUser(o: JSONObject): Boolean {
        if (o.optString("role") != "user") return false
        val p = o.optJSONArray("parts") ?: return false
        return p.length() > 0 && p.getJSONObject(0).has("text")
    }

    private fun system(): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a"))
        return """
            You are Inferno, a voice assistant living in the user's smart glasses and phone.
            Your replies are read aloud: keep them to one to three short sentences unless asked for more,
            in plain spoken language with no markdown, no lists and no emojis.
            Answer questions directly from your own knowledge.
            Use the tools for phone actions: calling, WhatsApp, recordings and briefs.
            Only send a WhatsApp message when the user clearly told you what to say, never invent message content.
            If a contact name is missing or unclear, ask instead of guessing.
            Reply in the language the user speaks.
            Current date and time: $now.
        """.trimIndent()
    }

    companion object {
        private fun decl(
            name: String,
            desc: String,
            props: Map<String, String> = emptyMap(),
            required: List<String> = emptyList(),
        ): JSONObject {
            val d = JSONObject().put("name", name).put("description", desc)
            if (props.isNotEmpty()) {
                val p = JSONObject()
                props.forEach { (k, v) ->
                    p.put(k, JSONObject().put("type", "string").put("description", v))
                }
                d.put(
                    "parameters",
                    JSONObject().put("type", "object").put("properties", p)
                        .put("required", JSONArray(required))
                )
            }
            return d
        }

        val TOOLS: JSONArray = JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray()
                    .put(decl("call_contact", "Place a phone call to a saved contact or a phone number.",
                        mapOf("who" to "Contact name or phone number"), listOf("who")))
                    .put(decl("send_whatsapp", "Send a WhatsApp message to a contact.",
                        mapOf("to" to "Contact name or phone number", "message" to "Exact message text"),
                        listOf("to", "message")))
                    .put(decl("read_whatsapp", "Read recent incoming WhatsApp messages, optionally only from one person.",
                        mapOf("from" to "Optional contact or chat name to filter by")))
                    .put(decl("start_recording", "Start recording the conversation around the user so a brief can be made later."))
                    .put(decl("stop_recording", "Stop the current recording and produce the analysis and brief."))
                    .put(decl("last_brief", "Get the most recent saved conversation brief."))
            )
        )
    }
}
