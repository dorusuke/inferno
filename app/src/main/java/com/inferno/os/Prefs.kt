package com.inferno.os

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings that survive restarts. Call Prefs.init(ctx) once from any entry point. */
object Prefs {
    private var sp: SharedPreferences? = null

    /** "phone" = Android voices, "gemini" = Gemini voices. */
    val engine = MutableStateFlow("phone")
    val phoneVoice = MutableStateFlow("")
    val geminiVoice = MutableStateFlow("Kore")
    val rate = MutableStateFlow(1.0f)
    val localVoice = MutableStateFlow("amy")
    /** "gemini" = send the command to Gemini as audio; otherwise a Whisper pack id from ModelStore.WHISPER. */
    val stt = MutableStateFlow("gemini")
    val bargeIn = MutableStateFlow(true)
    val dailyLimit = MutableStateFlow(250)
    val quiet = MutableStateFlow(true)
    val accountName = MutableStateFlow("")
    val accountEmail = MutableStateFlow("")

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("inferno", Context.MODE_PRIVATE)
        sp = p
        engine.value = p.getString("engine", "phone") ?: "phone"
        phoneVoice.value = p.getString("phone_voice", "") ?: ""
        geminiVoice.value = p.getString("gemini_voice", "Kore") ?: "Kore"
        rate.value = p.getFloat("rate", 1.0f)
        localVoice.value = p.getString("local_voice", "amy") ?: "amy"
        stt.value = p.getString("stt", "gemini") ?: "gemini"
        bargeIn.value = p.getBoolean("barge_in", true)
        dailyLimit.value = p.getInt("daily_limit", 250)
        quiet.value = p.getBoolean("quiet", true)
        accountName.value = p.getString("account_name", "") ?: ""
        accountEmail.value = p.getString("account_email", "") ?: ""
        Keys.init(ctx)
        Usage.init(ctx)
        GoogleAuth.init(ctx)
        Keys.refresh()
    }

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val p = sp ?: return
        val e = p.edit()
        block(e)
        e.apply()
    }

    fun setEngine(v: String) { engine.value = v; edit { it.putString("engine", v) } }
    fun setPhoneVoice(v: String) { phoneVoice.value = v; edit { it.putString("phone_voice", v) } }
    fun setGeminiVoice(v: String) { geminiVoice.value = v; edit { it.putString("gemini_voice", v) } }
    fun setLocalVoice(v: String) { localVoice.value = v; edit { it.putString("local_voice", v) } }
    fun setStt(v: String) { stt.value = v; edit { it.putString("stt", v) } }
    fun setBargeIn(v: Boolean) { bargeIn.value = v; edit { it.putBoolean("barge_in", v) } }
    fun setRate(v: Float) { rate.value = v; edit { it.putFloat("rate", v) } }
    fun setLimit(v: Int) { dailyLimit.value = v; edit { it.putInt("daily_limit", v) } }
    fun setQuiet(v: Boolean) { quiet.value = v; edit { it.putBoolean("quiet", v) } }
    fun setAccount(name: String, email: String) {
        accountName.value = name
        accountEmail.value = email
        edit { it.putString("account_name", name).putString("account_email", email) }
    }
}
