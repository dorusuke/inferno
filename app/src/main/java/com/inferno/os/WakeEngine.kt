package com.inferno.os

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/** Downloads the small offline speech model once (about 40 MB). */
object WakeModel {
    private const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun find(ctx: Context): File? {
        val root = File(ctx.filesDir, "vosk")
        if (!File(root, ".ok").exists()) return null
        return root.walkTopDown().maxDepth(3).firstOrNull {
            it.isDirectory && File(it, "am").isDirectory && File(it, "conf").isDirectory
        }
    }

    fun download(ctx: Context, onProgress: (Int) -> Unit): File? {
        find(ctx)?.let { return it }
        val root = File(ctx.filesDir, "vosk")
        root.deleteRecursively()
        root.mkdirs()
        val zip = File(ctx.cacheDir, "vosk-model.zip")
        return try {
            val c = URL(ZIP_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            val total = c.contentLength.toLong()
            c.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            c.disconnect()
            val base = root.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(base)) continue
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            File(root, ".ok").writeText("ok")
            find(ctx)
        } catch (e: Exception) {
            root.deleteRecursively()
            null
        } finally {
            zip.delete()
        }
    }
}

/**
 * Listens to the mic directly (no system recognizer, so no beeps), spots "Hey Inferno" offline,
 * then captures the spoken command as audio until you stop talking.
 */
class WakeEngine(modelDir: File, private val cb: Callback) {
    interface Callback {
        /** Wake word heard. [inlineWav] is the command if it was said in the same breath, else null. */
        fun onWake(inlineWav: ByteArray?)
        /** Result of a command capture. Null if nothing was said. */
        fun onCommand(wav: ByteArray?)
        fun onFailure()
    }

    companion object {
        const val RATE = 16000
        private const val GRAMMAR = "[\"hey inferno\", \"inferno\", \"in for no\", \"hey in for no\", \"[unk]\"]"
    }

    private val model = Model(modelDir.absolutePath)
    private val rec = Recognizer(model, RATE.toFloat(), GRAMMAR)
    @Volatile private var token = 0
    private var thread: Thread? = null

    fun start(command: Boolean, glasses: Boolean) {
        stop()
        val my = ++token
        val t = Thread { run(my, command, glasses) }
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        token++
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(400) } catch (e: InterruptedException) { }
        }
    }

    fun close() {
        stop()
        try { rec.close() } catch (e: Exception) { }
        try { model.close() } catch (e: Exception) { }
    }

    private fun isWake(text: String): Boolean = text.contains("inferno") || text.contains("in for no")

    private fun run(my: Int, command: Boolean, glasses: Boolean) {
        val src = if (glasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
        } catch (e: Exception) {
            null
        }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            ar?.release()
            if (token == my) cb.onFailure()
            return
        }
        try {
            ar.startRecording()
            if (command) {
                val pcm = capture(ar, my, emptyList(), 6000)
                if (token == my) cb.onCommand(pcm?.let { wav(it) })
            } else {
                rec.reset()
                val chunk = ShortArray(1600)
                val ring = ArrayDeque<ShortArray>()
                while (token == my) {
                    val n = ar.read(chunk, 0, chunk.size)
                    if (n < 0) {
                        if (token == my) cb.onFailure()
                        return
                    }
                    if (n == 0) continue
                    val c = chunk.copyOf(n)
                    ring.addLast(c)
                    while (ring.size > 25) ring.removeFirst()
                    val fin = rec.acceptWaveForm(c, n)
                    val text = if (fin) {
                        JSONObject(rec.result).optString("text")
                    } else {
                        JSONObject(rec.partialResult).optString("partial")
                    }
                    if (isWake(text)) {
                        rec.reset()
                        val pcm = capture(ar, my, ring.toList(), 700)
                        if (token == my) cb.onWake(pcm?.let { wav(it) })
                        return
                    }
                }
            }
        } catch (e: Exception) {
            if (token == my) cb.onFailure()
        } finally {
            try { ar.stop() } catch (e: Exception) { }
            ar.release()
        }
    }

    /**
     * Collects audio until the speaker pauses. Returns null if nobody starts talking within [waitMs].
     * [pre] is audio from just before (the wake phrase), kept in front so the whole phrase is sent.
     */
    private fun capture(ar: AudioRecord, my: Int, pre: List<ShortArray>, waitMs: Int): ShortArray? {
        val out = ArrayList<ShortArray>(pre)
        val noise = if (pre.isNotEmpty()) pre.minOf { rms(it, it.size) }.coerceAtLeast(150.0) else 400.0
        val thr = maxOf(900.0, noise * 3.0)
        val chunk = ShortArray(1600)
        val start = SystemClock.elapsedRealtime()
        var speaking = false
        var lastVoice = 0L
        while (token == my) {
            val n = ar.read(chunk, 0, chunk.size)
            if (n < 0) return null
            if (n == 0) continue
            val c = chunk.copyOf(n)
            out.add(c)
            val now = SystemClock.elapsedRealtime()
            if (rms(c, n) > thr) {
                speaking = true
                lastVoice = now
            }
            if (!speaking) {
                if (now - start > waitMs) return null
            } else if (now - lastVoice > 900 || now - start > 15_000) {
                break
            }
        }
        if (token != my) return null
        val total = out.sumOf { it.size }
        val all = ShortArray(total)
        var pos = 0
        for (c in out) {
            System.arraycopy(c, 0, all, pos, c.size)
            pos += c.size
        }
        return all
    }

    private fun rms(a: ShortArray, n: Int): Double {
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += a[i].toDouble() * a[i].toDouble()
        return sqrt(sum / n)
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(RATE)
        bb.putInt(RATE * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }
}
