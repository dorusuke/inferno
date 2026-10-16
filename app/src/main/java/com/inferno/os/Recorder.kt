package com.inferno.os

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small speech-grade AAC recorder: about 4 KB per second, so ~50 minutes fits one Gemini request. */
class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean get() = mr != null

    fun start(useCommMic: Boolean) {
        val f = File(ctx.filesDir, "rec_${System.currentTimeMillis()}.aac")
        val r = MediaRecorder(ctx)
        try {
            r.setAudioSource(
                if (useCommMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
            )
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            f.delete()
            throw e
        }
        mr = r
        file = f
    }

    /** Returns the saved file, or null if it was too short to keep. */
    fun stop(): File? {
        val r = mr ?: return null
        val f = file
        mr = null
        file = null
        return try {
            r.stop()
            f
        } catch (e: RuntimeException) {
            f?.delete()
            null
        } finally {
            r.release()
        }
    }
}

object Briefs {
    private const val MAX_BYTES = 14_000_000L

    private const val PROMPT = """Listen to this recorded conversation and write a brief for the person who recorded it.
Use plain text with these headings, in this order:
SUMMARY (three sentences at most)
KEY POINTS (short bullets)
DECISIONS (what was agreed, or "None")
ACTION ITEMS (who, what, and when if mentioned, or "None")
OPEN QUESTIONS (or "None")
TRANSCRIPT (speaker labels like Speaker 1, Speaker 2)
Only use what is actually said. If the audio is silent or unintelligible, say so in one line and skip the rest."""

    suspend fun make(file: File): String {
        if (file.length() > MAX_BYTES) error("That recording is too long to brief in one go.")
        val b64 = withContext(Dispatchers.IO) { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/aac").put("data", b64)))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        val text = Gemini.textOf(Gemini.generate(contents))
        if (text.isBlank()) error("Gemini returned an empty brief.")
        return text
    }

    fun save(ctx: Context, text: String, audio: File) {
        File(ctx.filesDir, audio.name.replace("rec_", "brief_").replace(".aac", ".txt")).writeText(text)
    }

    fun load(ctx: Context): List<Brief> {
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        return ctx.filesDir.listFiles { f -> f.name.startsWith("brief_") && f.extension == "txt" }
            ?.sortedByDescending { it.name }
            ?.map { f ->
                val ts = f.name.removePrefix("brief_").removeSuffix(".txt").toLongOrNull()
                Brief(if (ts != null) fmt.format(Date(ts)) else f.name, f.readText())
            } ?: emptyList()
    }
}
