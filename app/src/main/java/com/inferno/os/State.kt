package com.inferno.os

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** One saved recording. The brief is null until Gemini has written it. */
data class Rec(val id: Long, val audio: File, val brief: String?) {
    /** Recorder runs at 32 kbps, so 4000 bytes is one second. */
    val durationMs: Long get() = audio.length() * 1000 / 4000
}

/** Shared UI state. The service writes, the screens read. */
object InfernoState {
    val status = MutableStateFlow("Starting…")
    val serviceOn = MutableStateFlow(false)
    val glasses = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    /** True when there is either a signed-in Google account or an API key. */
    val hasKey = MutableStateFlow(false)
    val lastHeard = MutableStateFlow("")
    val lastReply = MutableStateFlow("")
    val recordings = MutableStateFlow<List<Rec>>(emptyList())
}
