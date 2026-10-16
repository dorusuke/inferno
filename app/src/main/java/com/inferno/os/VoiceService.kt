package com.inferno.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The always-on part. Listens for "Hey Inferno", asks Gemini, speaks the answer,
 * and records conversations on request.
 *
 * Only one thing can use the mic at a time, so wake-word listening pauses while a recording runs.
 * Stop a recording from the notification, the app, or by typing "stop recording".
 */
class VoiceService : Service(), TextToSpeech.OnInitListener, PhoneActions.RecordingControl {

    companion object {
        const val ACTION_ASK = "com.inferno.os.ASK"
        const val ACTION_REC_TOGGLE = "com.inferno.os.REC_TOGGLE"
        const val ACTION_REC_STOP = "com.inferno.os.REC_STOP"
        const val ACTION_QUIT = "com.inferno.os.QUIT"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL = "inferno"
        private const val NOTIF_ID = 7
        private const val GREETING = "Hey, I'm Inferno. I'm ready to help you."
        private const val MAX_RECORDING_MS = 50L * 60_000
        private val WAKE = Regex("\\b(?:inferno|in ferno|infernal)\\b", RegexOption.IGNORE_CASE)
    }

    private enum class Mode { WAKE, COMMAND }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val listenRunnable = Runnable { beginListening() }

    private lateinit var tts: TextToSpeech
    private lateinit var brain: Brain
    private lateinit var recorder: Recorder
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.WAKE
    private var running = false
    private var speaking = false
    private var pendingRecord = false
    private var glassesRouted = false
    private var recJob: Job? = null
    private var utterance = 0
    private var lastStatus = ""

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startForeground(
            NOTIF_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        recorder = Recorder(this)
        brain = Brain(PhoneActions(this, this))
        tts = TextToSpeech(this, this)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCb, handler)
        refreshGlasses()
        InfernoState.serviceOn.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let { processCommand(it) }
            ACTION_REC_STOP -> announceStop()
            ACTION_REC_TOGGLE -> if (recorder.isRecording) announceStop() else {
                pendingRecord = false
                startRecordingNow()
            }
            ACTION_QUIT -> stopSelf()
        }
        // Not sticky: Android 14 blocks restarting a mic service from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        if (::tts.isInitialized) tts.shutdown()
        if (recorder.isRecording) recorder.stop()
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCb)
        if (glassesRouted) Audio.release(this)
        scope.cancel()
        with(InfernoState) {
            serviceOn.value = false
            listening.value = false
            recording.value = false
            busy.value = false
            glasses.value = false
            status.value = "Voice is off"
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- glasses routing

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshGlasses()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshGlasses()
    }

    private fun refreshGlasses() {
        val present = Audio.glassesDevice(this) != null
        if (present && !glassesRouted) glassesRouted = Audio.connect(this)
        if (!present && glassesRouted) {
            Audio.release(this)
            glassesRouted = false
        }
        InfernoState.glasses.value = glassesRouted
    }

    // ---------------------------------------------------------------- speech out

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) {
            setStatus("Text-to-speech isn't available")
        } else {
            val r = tts.setLanguage(Locale.getDefault())
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { handler.post { afterSpeech() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { handler.post { afterSpeech() } }
            })
        }
        listenSoon(0)
    }

    private fun speak(text: String) {
        InfernoState.lastReply.value = text
        val clean = text.replace(Regex("[*#_`~>]"), "").take(3500)
        if (!ttsReady || clean.isBlank()) {
            afterSpeech()
            return
        }
        speaking = true
        setStatus("Speaking…")
        recognizer?.cancel()
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (glassesRouted) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u${++utterance}")
    }

    private fun afterSpeech() {
        speaking = false
        if (pendingRecord) {
            pendingRecord = false
            startRecordingNow()
            return
        }
        listenSoon(150)
    }

    // ---------------------------------------------------------------- speech in

    private fun listenSoon(delayMs: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private fun beginListening() {
        if (!running || speaking || recorder.isRecording || InfernoState.busy.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("No speech recognition service on this phone")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(i)
        InfernoState.listening.value = true
        setStatus(if (mode == Mode.WAKE) "Say “Hey Inferno”" else "Listening…")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            InfernoState.listening.value = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                if (mode == Mode.COMMAND) mode = Mode.WAKE
                listenSoon(200)
            } else {
                handleHeard(text)
            }
        }

        override fun onError(error: Int) {
            InfernoState.listening.value = false
            if (mode == Mode.COMMAND) mode = Mode.WAKE
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    setStatus("Microphone permission needed")
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    listenSoon(200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy()
                    recognizer = null
                    listenSoon(800)
                }
                else -> listenSoon(1500)
            }
        }
    }

    private fun handleHeard(text: String) {
        if (mode == Mode.COMMAND) {
            mode = Mode.WAKE
            processCommand(text)
            return
        }
        val m = WAKE.find(text)
        if (m == null) {
            listenSoon(100)
            return
        }
        val cmd = text.substring(m.range.last + 1).trim { it in " ,.!?" }
        if (cmd.isBlank()) {
            mode = Mode.COMMAND
            speak(GREETING)
        } else {
            processCommand(cmd)
        }
    }

    private fun processCommand(text: String) {
        if (InfernoState.busy.value) return
        InfernoState.lastHeard.value = text
        InfernoState.busy.value = true
        setStatus("Thinking…")
        scope.launch {
            var error: String? = null
            val reply = try {
                brain.ask(text)
            } catch (e: Exception) {
                error = e.message
                "Sorry, I couldn't get an answer just now."
            }
            InfernoState.busy.value = false
            speak(reply)
            if (error != null) InfernoState.lastReply.value = "Error: $error"
        }
    }

    // ---------------------------------------------------------------- recording

    override fun start(): String {
        if (recorder.isRecording || pendingRecord) return "Already recording."
        pendingRecord = true
        return "Recording begins as soon as your reply finishes. Tell the user it is starting and that " +
            "they can stop it from the notification or the app, or by typing stop recording."
    }

    override suspend fun stop(): String {
        pendingRecord = false
        if (!recorder.isRecording) return "Nothing is being recorded."
        return finishRecording().second
    }

    private fun startRecordingNow() {
        handler.removeCallbacks(listenRunnable)
        InfernoState.listening.value = false
        recognizer?.cancel()
        scope.launch {
            delay(400) // let the recognizer release the mic
            try {
                recorder.start(glassesRouted)
            } catch (e: Exception) {
                setStatus("Couldn't start recording")
                listenSoon(500)
                return@launch
            }
            InfernoState.recording.value = true
            setStatus("Recording…")
            recJob = scope.launch {
                delay(MAX_RECORDING_MS)
                announceStop()
            }
        }
    }

    private fun announceStop() {
        if (!recorder.isRecording) return
        scope.launch {
            InfernoState.busy.value = true
            val (ok, msg) = finishRecording()
            InfernoState.busy.value = false
            speak(if (ok) "Recording stopped. Your brief is ready." else msg)
        }
    }

    private suspend fun finishRecording(): Pair<Boolean, String> {
        recJob?.cancel()
        recJob = null
        val f = withContext(Dispatchers.IO) { recorder.stop() }
        InfernoState.recording.value = false
        if (f == null) return false to "That recording was too short to save."
        setStatus("Writing your brief…")
        return try {
            val text = Briefs.make(f)
            Briefs.save(this, text, f)
            InfernoState.briefs.value = Briefs.load(this)
            true to text.take(3000)
        } catch (e: Exception) {
            false to "I saved the recording, but couldn't write the brief."
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Inferno", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Inferno")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (InfernoState.recording.value) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, VoiceService::class.java).setAction(ACTION_REC_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop & brief", stop
                ).build()
            )
        }
        return b.build()
    }

    private fun setStatus(s: String) {
        InfernoState.status.value = s
        if (s == lastStatus) return
        lastStatus = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }
}
