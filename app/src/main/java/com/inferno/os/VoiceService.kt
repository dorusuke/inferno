package com.inferno.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The always-on part. Listens for "Hey Inferno", asks Gemini, speaks the answer,
 * and records conversations on request.
 *
 * Only one thing can use the mic at a time, so wake-word listening pauses while a recording runs.
 * Stop a recording from the notification, the app, or by typing "stop recording".
 */
class VoiceService : Service(), PhoneActions.RecordingControl {

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

    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var recorder: Recorder
    private var recognizer: SpeechRecognizer? = null
    private var mode = Mode.WAKE
    private var running = false
    private var speaking = false
    private var pendingRecord = false
    private var glassesRouted = false
    private var recJob: Job? = null
    private var lastStatus = ""
    private var wake: WakeEngine? = null
    private var wakeReady = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        running = true
        Prefs.init(this)
        createChannel()
        startForeground(
            NOTIF_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        recorder = Recorder(this)
        brain = Brain(PhoneActions(this, this))
        speaker = Speaker(this, scope) { afterSpeech() }
        speaker.start()
        prepareWake()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCb, handler)
        refreshGlasses()
        Recordings.refresh(this)
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
        Quiet.off(this)
        wake?.close()
        wake = null
        if (::speaker.isInitialized) speaker.shutdown()
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

    private fun speak(text: String) {
        InfernoState.lastReply.value = text
        val clean = text.replace(Regex("[*#_`~>]"), "").take(3500)
        if (clean.isBlank()) {
            afterSpeech()
            return
        }
        speaking = true
        setStatus("Speaking…")
        recognizer?.cancel()
        wake?.stop()
        Quiet.off(this)
        speaker.speak(clean, glassesRouted)
    }

    private fun afterSpeech() {
        speaking = false
        if (pendingRecord) {
            pendingRecord = false
            startRecordingNow()
            return
        }
        if (mode == Mode.COMMAND) {
            // Soft "go ahead" ping, then listen for the actual question.
            Quiet.off(this)
            val d = Sounds.play(Sounds.Kind.READY, glassesRouted)
            listenSoon(d + 100)
        } else {
            listenSoon(150)
        }
    }

    // ---------------------------------------------------------------- offline wake word

    private val wakeCb = object : WakeEngine.Callback {
        override fun onWake(inlineWav: ByteArray?) { handler.post { handleWake(inlineWav) } }
        override fun onCommand(wav: ByteArray?) { handler.post { handleCommandAudio(wav) } }
        override fun onFailure() {
            handler.post {
                InfernoState.listening.value = false
                setStatus("Mic busy, retrying…")
                listenSoon(1500)
            }
        }
    }

    private fun prepareWake() {
        scope.launch {
            setStatus("Preparing wake word…")
            val ok = withContext(Dispatchers.IO) {
                try {
                    val dir = WakeModel.download(this@VoiceService) { p ->
                        handler.post { setStatus("Downloading wake-word model $p%") }
                    }
                    if (dir == null) {
                        false
                    } else {
                        wake = WakeEngine(dir, wakeCb)
                        true
                    }
                } catch (e: Throwable) {
                    false
                }
            }
            wakeReady = ok && wake != null
            if (!wakeReady) setStatus("Wake-word model unavailable, using the phone's listener")
            listenSoon(0)
        }
    }

    private fun handleWake(inlineWav: ByteArray?) {
        InfernoState.listening.value = false
        if (inlineWav != null) {
            Sounds.play(Sounds.Kind.READY, glassesRouted)
            processAudio(inlineWav)
        } else {
            mode = Mode.COMMAND
            val d = Sounds.play(Sounds.Kind.WAKE, glassesRouted)
            speaking = true
            handler.postDelayed({ if (running) speak(GREETING) }, d)
        }
    }

    private fun handleCommandAudio(wav: ByteArray?) {
        InfernoState.listening.value = false
        mode = Mode.WAKE
        if (wav == null) listenSoon(200) else processAudio(wav)
    }

    // ---------------------------------------------------------------- speech in

    private fun listenSoon(delayMs: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private fun beginListening() {
        if (!running || speaking || recorder.isRecording || InfernoState.busy.value) return
        if (wakeReady) {
            InfernoState.listening.value = true
            setStatus(if (mode == Mode.WAKE) "Say “Hey Inferno”" else "Listening…")
            wake?.start(mode == Mode.COMMAND, glassesRouted)
            return
        }
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
        Quiet.on(this) // hides the recognizer's start/stop beeps
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
        Quiet.off(this)
        if (cmd.isBlank()) {
            mode = Mode.COMMAND
            val d = Sounds.play(Sounds.Kind.WAKE, glassesRouted)
            speaking = true
            handler.postDelayed({ if (running) speak(GREETING) }, d)
        } else {
            Sounds.play(Sounds.Kind.READY, glassesRouted)
            processCommand(cmd)
        }
    }

    private fun processCommand(text: String) {
        if (InfernoState.busy.value) return
        InfernoState.lastHeard.value = text
        runAsk { brain.ask(text) }
    }

    private fun processAudio(wav: ByteArray) {
        if (InfernoState.busy.value) return
        InfernoState.lastHeard.value = "Voice message"
        runAsk { brain.askAudio(wav) }
    }

    private fun runAsk(block: suspend () -> String) {
        InfernoState.busy.value = true
        setStatus("Thinking…")
        scope.launch {
            var error: String? = null
            val reply = try {
                block()
            } catch (e: Exception) {
                error = e.message
                when {
                    error?.contains("429") == true -> "I've hit the Gemini usage limit for now. Try again later."
                    error?.startsWith("Not signed in") == true -> "Open the app and sign in, or add an API key in your profile."
                    else -> "Sorry, I couldn't get an answer just now."
                }
            }
            InfernoState.busy.value = false
            if (error != null) {
                Quiet.off(this@VoiceService)
                val d = Sounds.play(Sounds.Kind.ERROR, glassesRouted)
                delay(d / 2)
            }
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
        wake?.stop()
        scope.launch {
            Quiet.off(this@VoiceService)
            val d = Sounds.play(Sounds.Kind.REC_START, glassesRouted)
            delay(d + 150) // chime first so it isn't recorded; also lets the recognizer release the mic
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
            speak(if (ok) "Recording stopped. Your brief is ready in Recordings." else msg)
        }
    }

    private suspend fun finishRecording(): Pair<Boolean, String> {
        recJob?.cancel()
        recJob = null
        val f = withContext(Dispatchers.IO) { recorder.stop() }
        InfernoState.recording.value = false
        Quiet.off(this)
        Sounds.play(Sounds.Kind.REC_STOP, glassesRouted)
        if (f == null) return false to "That recording was too short to save."
        Recordings.refresh(this)
        val id = f.name.removePrefix("rec_").removeSuffix(".aac").toLongOrNull()
            ?: return false to "I saved the recording, but couldn't write the brief."
        setStatus("Writing your brief…")
        return try {
            val text = Recordings.makeBrief(f)
            Recordings.saveBrief(this, id, text)
            Recordings.refresh(this)
            true to text.take(3000)
        } catch (e: Exception) {
            false to "I saved the recording. You can make the brief later from Recordings."
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
