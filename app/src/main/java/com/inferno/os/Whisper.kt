package com.inferno.os

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Offline Whisper, used ONLY on the command captured after the wake word. It never runs
 * while waiting for "Hey Inferno". Loaded on first use, freed after a quiet spell.
 */
object Whisper {
    private var rec: OfflineRecognizer? = null
    private var loaded = ""
    private var lastUse = 0L
    private const val IDLE_RELEASE_MS = 120_000L

    data class Result(val text: String, val loadMs: Long, val decodeMs: Long, val audioMs: Long)

    @Synchronized
    fun transcribe(ctx: Context, modelId: String, pcm: ShortArray): Result? {
        val m = ModelStore.WHISPER.firstOrNull { it.id == modelId } ?: return null
        val dir = ModelStore.dir(ctx, m.id) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        if (rec == null || loaded != m.id) {
            release()
            fun find(suffix: String) = dir.walkTopDown().first { it.isFile && it.name.endsWith(suffix) }.absolutePath
            val cfg = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = find("encoder.int8.onnx"),
                        decoder = find("decoder.int8.onnx"),
                        language = "en",
                        task = "transcribe",
                    ),
                    tokens = find("tokens.txt"),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper",
                )
            )
            rec = OfflineRecognizer(config = cfg)
            loaded = m.id
        }
        val t1 = SystemClock.elapsedRealtime()
        val r = rec ?: return null
        val stream = r.createStream()
        val text = try {
            stream.acceptWaveform(Pcm.toFloat(pcm), 16000)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        val t2 = SystemClock.elapsedRealtime()
        lastUse = t2
        return Result(text, t1 - t0, t2 - t1, pcm.size * 1000L / 16000)
    }

    @Synchronized
    fun releaseIfIdle() {
        if (rec != null && SystemClock.elapsedRealtime() - lastUse > IDLE_RELEASE_MS) release()
    }

    @Synchronized
    fun release() {
        try { rec?.release() } catch (e: Throwable) { }
        rec = null
        loaded = ""
    }
}
