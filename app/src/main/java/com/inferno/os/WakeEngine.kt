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
import java.util.zip.ZipInputStream

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
        /** Wake word heard. [inlinePcm] is the command if it was said in the same breath, else null. */
        fun onWake(inlinePcm: ShortArray?)
        /** Result of a command capture. Null if nothing was said. */
        fun onCommand(pcm: ShortArray?)
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

    private fun isWake(text: String): Boolean = WakeText.isWake(text)

    /** Mic the last session actually used, so the UI can show glasses vs phone. */
    @Volatile var route = ""

    private var ctx: Context? = null
    fun attach(c: Context) { ctx = c.applicationContext }

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
            if (glasses) {
                ctx?.let { c -> Audio.glassesMic(c)?.let { ar.setPreferredDevice(it) } }
            }
            ar.startRecording()
            route = Audio.label(ar.routedDevice?.type)
            if (command) {
                val pcm = capture(ar, my, emptyList(), 6000)
                if (token == my) cb.onCommand(pcm)
            } else {
                rec.reset()
                val gate = EnergyGate()
                val chunk = ShortArray(1600)
                val ring = ArrayDeque<ShortArray>()
                var lastWake = 0L
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
                    // Silence and steady noise never reach the recognizer, which is most of the idle saving.
                    val step = gate.step(Pcm.rms(c, n))
                    if (step.closed) rec.reset()
                    if (!step.feed) continue
                    val fin = rec.acceptWaveForm(c, n)
                    val text = if (fin) {
                        JSONObject(rec.result).optString("text")
                    } else {
                        JSONObject(rec.partialResult).optString("partial")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (isWake(text) && now - lastWake > 1500) {
                        lastWake = now
                        rec.reset()
                        val pcm = capture(ar, my, ring.toList(), 700)
                        if (token == my) cb.onWake(pcm)
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
        val noise = if (pre.isNotEmpty()) pre.minOf { Pcm.rms(it, it.size) }.coerceAtLeast(150.0) else 400.0
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
            if (Pcm.rms(c, n) > thr) {
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
        return Pcm.concat(out)
    }
}
