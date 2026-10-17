package com.inferno.os

import android.content.Context
import android.media.AudioManager

/**
 * Android's speech recognizer plays a "tudung" beep every time it starts and stops listening.
 * Apps can't turn that off, so we mute the media volume while listening and restore it
 * whenever Inferno itself needs to make a sound. The ringer is never touched.
 */
object Quiet {
    private var muted = false

    private fun streams(am: AudioManager): List<Int> =
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)
        } else {
            listOf(AudioManager.STREAM_MUSIC)
        }

    fun on(ctx: Context) {
        if (muted || !Prefs.quiet.value) return
        val am = ctx.getSystemService(AudioManager::class.java)
        for (s in streams(am)) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } catch (e: Exception) { }
        }
        muted = true
    }

    fun off(ctx: Context) {
        if (!muted) return
        val am = ctx.getSystemService(AudioManager::class.java)
        for (s in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        muted = false
    }
}
