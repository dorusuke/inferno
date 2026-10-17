package com.inferno.os

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Soft glass-bell chimes, synthesized on the fly (no sound files).
 * Gentle two-note pings in the style of modern phone UI sounds, all original.
 */
object Sounds {
    enum class Kind { WAKE, READY, ERROR, REC_START, REC_STOP }

    private const val RATE = 44100
    private class Note(val hz: Double, val atMs: Int, val lenMs: Int, val gain: Double)

    private fun notes(kind: Kind): List<Note> = when (kind) {
        Kind.WAKE -> listOf(Note(1175.0, 0, 380, 0.50), Note(1760.0, 95, 520, 0.45))
        Kind.READY -> listOf(Note(1568.0, 0, 300, 0.35))
        Kind.ERROR -> listOf(Note(392.0, 0, 300, 0.45), Note(294.0, 110, 420, 0.40))
        Kind.REC_START -> listOf(Note(880.0, 0, 300, 0.45), Note(1320.0, 100, 450, 0.45))
        Kind.REC_STOP -> listOf(Note(1320.0, 0, 300, 0.45), Note(880.0, 100, 450, 0.40))
    }

    /** Plays the chime and returns how long it lasts in milliseconds. */
    fun play(kind: Kind, glasses: Boolean): Long {
        return try {
            val ns = notes(kind)
            val totalMs = ns.maxOf { it.atMs + it.lenMs }
            val n = RATE * totalMs / 1000
            val mix = DoubleArray(n)
            for (nt in ns) {
                val start = RATE * nt.atMs / 1000
                val len = RATE * nt.lenMs / 1000
                for (i in 0 until len) {
                    val idx = start + i
                    if (idx >= n) break
                    val t = i.toDouble() / RATE
                    val env = (1.0 - exp(-t * 1200.0)) * exp(-t * 11.0)
                    val w = 2.0 * PI * nt.hz * t
                    val v = sin(w) + 0.28 * sin(2.0 * w) * exp(-t * 18.0) + 0.10 * sin(3.0 * w) * exp(-t * 25.0)
                    mix[idx] += v * env * nt.gain
                }
            }
            val pcm = ShortArray(n) { (mix[it].coerceIn(-1.0, 1.0) * 0.8 * Short.MAX_VALUE).toInt().toShort() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (glasses) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({
                try { track.release() } catch (e: Exception) { }
            }, totalMs + 250L)
            totalMs.toLong()
        } catch (e: Exception) {
            0L
        }
    }
}
