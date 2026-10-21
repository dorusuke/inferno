package com.inferno.os

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Speaks replies with either an Android voice or a Gemini voice. [done] fires when speech ends. */
class Speaker(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val done: () -> Unit,
) {
    companion object {
        val GEMINI_VOICES = listOf(
            "Kore" to "Kore · firm",
            "Puck" to "Puck · upbeat",
            "Charon" to "Charon · informative",
            "Aoede" to "Aoede · breezy",
            "Fenrir" to "Fenrir · excitable",
            "Leda" to "Leda · youthful",
            "Orus" to "Orus · firm",
            "Zephyr" to "Zephyr · bright",
            "Achernar" to "Achernar · soft",
            "Sulafat" to "Sulafat · warm",
            "Algieba" to "Algieba · smooth",
            "Gacrux" to "Gacrux · mature",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var track: AudioTrack? = null
    private var job: Job? = null
    private var seq = 0
    private var currentId = ""
    private var notify = true

    fun start(onReady: () -> Unit = {}) {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            val t = tts
            if (ready && t != null) {
                val r = t.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.US)
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { finish(utteranceId ?: "") }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { finish(utteranceId ?: "") }
                    }
                })
            }
            onReady()
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    /** Installed Android voices for the phone's language, as (id, label). */
    fun phoneVoices(): List<Pair<String, String>> {
        val t = tts ?: return emptyList()
        if (!ready) return emptyList()
        val all = try { t.voices } catch (e: Exception) { null } ?: return emptyList()
        val lang = Locale.getDefault().language
        val installed = all.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val pick = installed.filter { it.locale.language == lang }.ifEmpty { installed }
        return pick.sortedWith(compareBy({ it.locale.toString() }, { it.name }))
            .mapIndexed { i, v ->
                val online = if (v.isNetworkConnectionRequired) " · online" else ""
                v.name to "${v.locale.displayName} · Voice ${i + 1}$online"
            }
    }

    fun speak(text: String, glasses: Boolean, notifyWhenDone: Boolean = true) {
        stop()
        seq++
        val id = "s$seq"
        currentId = id
        notify = notifyWhenDone
        val local = ModelStore.voice(Prefs.localVoice.value)
        if (Prefs.engine.value == "local" && local != null && ModelStore.installed(ctx, local.pack.id)) {
            job = scope.launch(Dispatchers.Default) {
                try {
                    speakLocal(local, text, glasses, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    handler.post { if (currentId == id) speakPhone(text, glasses, id) }
                }
            }
        } else if (Prefs.engine.value == "gemini" && InfernoState.hasKey.value) {
            job = scope.launch {
                try {
                    val pcm = Gemini.tts(text, Prefs.geminiVoice.value)
                    if (currentId == id) playPcm(pcm, glasses, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentId == id) speakPhone(text, glasses, id)
                }
            }
        } else {
            speakPhone(text, glasses, id)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        currentId = ""
        try { tts?.stop() } catch (e: Exception) { }
        releaseTrack()
    }

    /** Streams offline speech: audio starts after the first sentence is ready, not after the whole reply. */
    private fun speakLocal(v: ModelStore.Voice, text: String, glasses: Boolean, id: String) {
        var tr: AudioTrack? = null
        var written = 0L
        var rate = 0
        val attrs = AudioAttributes.Builder()
            .setUsage(if (glasses) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val stats = LocalTts.stream(ctx, v, text, Prefs.rate.value) { samples, sr ->
            if (currentId != id) return@stream false
            if (tr == null) {
                rate = sr
                val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(maxOf(min, sr))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                t.play()
                tr = t
                track = t
            }
            val pcm = Pcm.floatToShort(samples)
            tr?.write(pcm, 0, pcm.size)
            written += pcm.size
            true
        }
        val t = tr
        if (stats == null || t == null) {
            handler.post { if (currentId == id) speakPhone(text, glasses, id) }
            return
        }
        InfernoState.ttsInfo.value = "first audio ${stats.firstAudioMs} ms · ${"%.2f".format(stats.rtf)}x real time"
        // Wait for the buffered audio to finish playing (or for an interruption).
        val end = System.currentTimeMillis() + written * 1000 / rate + 2000
        while (currentId == id && System.currentTimeMillis() < end) {
            if (t.playbackHeadPosition >= written) break
            Thread.sleep(30)
        }
        handler.post { finish(id) }
    }

    private fun speakPhone(text: String, glasses: Boolean, id: String) {
        val t = tts
        if (t == null || !ready) {
            handler.post { finish(id) }
            return
        }
        val want = Prefs.phoneVoice.value
        if (want.isNotBlank()) {
            try {
                t.voices?.firstOrNull { it.name == want }?.let { t.voice = it }
            } catch (e: Exception) { }
        }
        t.setSpeechRate(Prefs.rate.value)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (glasses) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun playPcm(pcm: ByteArray, glasses: Boolean, id: String) {
        val frames = pcm.size / 2
        if (frames < 100) {
            finish(id)
            return
        }
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (glasses) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        tr.write(pcm, 0, pcm.size)
        tr.setNotificationMarkerPosition(frames - 50)
        tr.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                handler.post { finish(id) }
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track = tr
        tr.play()
        // Safety net in case the marker never fires.
        handler.postDelayed({ finish(id) }, frames * 1000L / 24000 + 1500)
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (e: Exception) { }
        try { t.release() } catch (e: Exception) { }
    }

    private fun finish(id: String) {
        if (id.isEmpty() || id != currentId) return
        currentId = ""
        releaseTrack()
        if (notify) done()
    }
}
