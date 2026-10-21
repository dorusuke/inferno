package com.inferno.os

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Offline voices (Piper and Kokoro) through sherpa-onnx, streamed sentence by sentence. */
object LocalTts {
    private var tts: OfflineTts? = null
    private var loaded = ""

    data class Stats(val firstAudioMs: Long, val rtf: Double, val audioMs: Long)

    @Synchronized
    private fun engine(ctx: Context, v: ModelStore.Voice): OfflineTts? {
        if (tts != null && loaded == v.pack.id) return tts
        try { tts?.release() } catch (e: Throwable) { }
        tts = null
        val dir = ModelStore.dir(ctx, v.pack.id) ?: return null
        val esp = File(dir, "espeak-ng-data").absolutePath
        val tokens = File(dir, "tokens.txt").absolutePath
        val model = if (v.pack.id == "kokoro") {
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = tokens,
                    dataDir = esp,
                ),
                numThreads = 3, provider = "cpu",
            )
        } else {
            val onnx = dir.listFiles { f -> f.extension == "onnx" }?.firstOrNull() ?: return null
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = onnx.absolutePath, tokens = tokens, dataDir = esp),
                numThreads = 2, provider = "cpu",
            )
        }
        tts = OfflineTts(config = OfflineTtsConfig(model = model))
        loaded = v.pack.id
        return tts
    }

    /** Speaks [text]; [onSamples] gets float PCM and the sample rate as it is produced, return false to stop. */
    fun stream(
        ctx: Context, v: ModelStore.Voice, text: String, speed: Float,
        onSamples: (FloatArray, Int) -> Boolean,
    ): Stats? {
        val e = engine(ctx, v) ?: return null
        val rate = e.sampleRate()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        var samples = 0L
        var stopped = false
        for (sentence in Sentences.split(text)) {
            if (stopped) break
            e.generateWithCallback(sentence, v.sid, speed) { chunk ->
                if (first < 0) first = SystemClock.elapsedRealtime() - t0
                samples += chunk.size
                if (onSamples(chunk, rate)) 1 else { stopped = true; 0 }
            }
        }
        val total = SystemClock.elapsedRealtime() - t0
        val audioMs = samples * 1000 / rate
        return Stats(first.coerceAtLeast(0), if (audioMs > 0) total.toDouble() / audioMs else 0.0, audioMs)
    }

    @Synchronized
    fun release() {
        try { tts?.release() } catch (e: Throwable) { }
        tts = null
        loaded = ""
    }
}
