package com.inferno.os

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Pure logic with no Android dependencies, so it can be unit-tested on any machine. */

object WakeText {
    private val NON_LETTERS = Regex("[^a-z ]")

    private fun words(text: String) =
        text.lowercase().replace(NON_LETTERS, " ").split(" ").filter { it.isNotEmpty() }

    private fun hitAt(w: List<String>, i: Int): Int {
        val x = w[i]
        if (x == "inferno" || (x.length >= 6 && distance(x, "inferno") <= 1)) return 1
        if (x == "in" && i + 2 < w.size && w[i + 1] == "for" && w[i + 2] == "no") return 3
        if (x == "infer" && i + 1 < w.size && w[i + 1] == "no") return 2
        return 0
    }

    fun isWake(text: String): Boolean {
        val w = words(text)
        return w.indices.any { hitAt(w, it) > 0 }
    }

    /** What was said after the wake phrase, or the whole text if there was no wake phrase. */
    fun stripWake(text: String): String {
        val w = words(text)
        for (i in w.indices) {
            val n = hitAt(w, i)
            if (n > 0) return w.drop(i + n).joinToString(" ")
        }
        return text.trim()
    }

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/**
 * Decides which 100 ms chunks are worth giving to the wake-word recognizer. Silence and steady
 * background noise are skipped, which is where most of the idle CPU saving comes from.
 */
class EnergyGate(private val floor: Double = 450.0, private val hangoverChunks: Int = 8) {
    private var noise = 250.0
    private var hang = 0

    var open = false
        private set

    /** Result of one chunk: feed it to the recognizer, and whether the gate just closed (reset the recognizer). */
    data class Step(val feed: Boolean, val closed: Boolean)

    fun step(level: Double): Step {
        val thr = maxOf(floor, noise * 2.5)
        if (level > thr) {
            hang = hangoverChunks
            noise += 0.004 * (level - noise) // creeps up so steady loud rooms stop triggering
        } else {
            noise += 0.03 * (level - noise)
            if (hang > 0) hang--
        }
        val was = open
        open = hang > 0
        return Step(feed = open || was, closed = was && !open)
    }
}

object Pcm {
    fun rms(a: ShortArray, n: Int = a.size): Double {
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += a[i].toDouble() * a[i].toDouble()
        return sqrt(sum / n)
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        val all = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (c in parts) {
            System.arraycopy(c, 0, all, pos, c.size)
            pos += c.size
        }
        return all
    }

    fun toWav(pcm: ShortArray, rate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    /** Reads a 16-bit mono WAV made by [toWav] back into samples. */
    fun fromWav(wav: ByteArray): ShortArray {
        if (wav.size <= 44) return ShortArray(0)
        val bb = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((wav.size - 44) / 2) { bb.short }
    }

    fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun floatToShort(f: FloatArray) = ShortArray(f.size) { (f[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}

object Sentences {
    /** Splits a reply into speakable chunks so audio can start after the first one is ready. */
    fun split(text: String, maxLen: Int = 220): List<String> {
        val out = ArrayList<String>()
        for (raw in text.replace("\n", " ").split(Regex("(?<=[.!?])\\s+"))) {
            var s = raw.trim()
            while (s.length > maxLen) {
                val cut = s.lastIndexOf(' ', maxLen).let { if (it < 40) maxLen else it }
                out.add(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
            }
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
