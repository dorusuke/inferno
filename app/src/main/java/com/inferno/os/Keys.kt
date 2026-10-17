package com.inferno.os

import android.content.Context

/** The pasted Gemini API key. Stored privately on the phone. */
object Keys {
    @Volatile
    var current: String = BuildConfig.GEMINI_API_KEY
        private set

    private const val PREFS = "inferno"
    private const val KEY = "gemini_key"

    fun init(ctx: Context) {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) current = saved
    }

    fun save(ctx: Context, key: String) {
        val k = key.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, k).apply()
        current = k
        refresh()
    }

    /** Recomputes whether any sign-in method is available. */
    fun refresh() {
        InfernoState.hasKey.value = current.isNotBlank() || Prefs.accountEmail.value.isNotBlank()
    }
}
