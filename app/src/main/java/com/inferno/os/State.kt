package com.inferno.os

import kotlinx.coroutines.flow.MutableStateFlow

data class Brief(val name: String, val text: String)

/** Shared UI state. The service writes, the screen reads. */
object InfernoState {
    val status = MutableStateFlow("Starting…")
    val serviceOn = MutableStateFlow(false)
    val glasses = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val lastHeard = MutableStateFlow("")
    val lastReply = MutableStateFlow("")
    val briefs = MutableStateFlow<List<Brief>>(emptyList())
}
