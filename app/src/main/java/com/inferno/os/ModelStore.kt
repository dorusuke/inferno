package com.inferno.os

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and unpacks on-device models (Whisper and the human voices) on demand. */
object ModelStore {
    /** A downloadable model package. Several voices can share one package. */
    data class Pack(val id: String, val name: String, val url: String, val mb: Int)

    data class Voice(val id: String, val name: String, val about: String, val pack: Pack, val sid: Int = 0)

    private const val ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    val WHISPER = listOf(
        Pack("whisper-tiny", "Whisper tiny (English)", ASR + "sherpa-onnx-whisper-tiny.en.tar.bz2", 118),
        Pack("whisper-base", "Whisper base (multilingual)", ASR + "sherpa-onnx-whisper-base.tar.bz2", 208),
    )

    private fun piper(id: String) = Pack("piper-$id", "Piper $id", TTS + "vits-piper-$id.tar.bz2", 67)
    private val KOKORO = Pack("kokoro", "Kokoro v0.19", TTS + "kokoro-en-v0_19.tar.bz2", 320)

    val VOICES = listOf(
        Voice("amy", "Amy", "Piper · American woman", piper("en_US-amy-medium")),
        Voice("ryan", "Ryan", "Piper · American man", piper("en_US-ryan-medium")),
        Voice("lessac", "Lessac", "Piper · clear American woman", piper("en_US-lessac-medium")),
        Voice("alan", "Alan", "Piper · British man", piper("en_GB-alan-medium")),
        Voice("jenny", "Jenny", "Piper · British woman", piper("en_GB-jenny_dioco-medium")),
        Voice("alba", "Alba", "Piper · Scottish woman", piper("en_GB-alba-medium")),
        Voice("k-bella", "Bella", "Kokoro · American woman", KOKORO, 1),
        Voice("k-adam", "Adam", "Kokoro · American man", KOKORO, 5),
        Voice("k-emma", "Emma", "Kokoro · British woman", KOKORO, 7),
        Voice("k-george", "George", "Kokoro · British man", KOKORO, 9),
    )

    fun voice(id: String): Voice? = VOICES.firstOrNull { it.id == id }

    /** Download progress by model id, 0..100. Present only while downloading. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val failed = MutableStateFlow<Set<String>>(emptySet())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }

    /** Folder holding the unpacked model, or null if not downloaded. */
    fun dir(ctx: Context, id: String): File? {
        val root = File(ctx.filesDir, "models/$id")
        if (!File(root, ".ok").exists()) return null
        return root.listFiles { f -> f.isDirectory }?.firstOrNull() ?: root
    }

    fun installed(ctx: Context, id: String): Boolean = dir(ctx, id) != null

    /** Starts a background download. Safe to call repeatedly. */
    fun start(ctx: Context, id: String, url: String, onDone: (Boolean) -> Unit = {}) {
        if (progress.value.containsKey(id)) return
        val app = ctx.applicationContext
        setProgress(id, 0)
        failed.value = failed.value - id
        scope.launch {
            val ok = downloadBlocking(app, id, url) { p -> setProgress(id, p) }
            progress.value = progress.value - id
            if (!ok) failed.value = failed.value + id
            onDone(ok)
        }
    }

    /** Returns the model folder, downloading first if needed. Blocks, so call off the main thread. */
    fun ensure(ctx: Context, id: String, url: String, onProgress: (Int) -> Unit): File? {
        dir(ctx, id)?.let { return it }
        return if (downloadBlocking(ctx, id, url, onProgress)) dir(ctx, id) else null
    }

    private fun setProgress(id: String, p: Int) {
        progress.value = progress.value + (id to p)
    }

    private fun downloadBlocking(ctx: Context, id: String, url: String, onProgress: (Int) -> Unit): Boolean {
        val root = File(ctx.filesDir, "models/$id")
        root.deleteRecursively()
        root.mkdirs()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            val total = c.contentLengthLong
            val counting = Counting(c.inputStream.buffered(1 shl 16))
            val base = root.canonicalPath + File.separator
            var last = -1
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(base)) continue
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { out -> tar.copyTo(out) }
                    }
                    if (total > 0) {
                        val p = (counting.count * 100 / total).toInt().coerceIn(0, 99)
                        if (p != last) {
                            last = p
                            onProgress(p)
                        }
                    }
                }
            }
            c.disconnect()
            File(root, ".ok").writeText("ok")
            true
        } catch (e: Throwable) {
            root.deleteRecursively()
            false
        }
    }
}
