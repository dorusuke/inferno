package com.inferno.os

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Counts Gemini requests and tokens on this phone.
 * Google doesn't tell apps how much quota is left, so "remaining" is the daily limit
 * you set minus what this app has used. Google resets daily quotas at midnight Pacific time.
 */
object Usage {
    data class Snap(val day: String, val requests: Int, val tokens: Long)

    val today = MutableStateFlow(Snap("", 0, 0))
    private var sp: SharedPreferences? = null
    private val pacific = TimeZone.getTimeZone("America/Los_Angeles")

    fun dayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = pacific }.format(Date())

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("inferno_usage", Context.MODE_PRIVATE)
        sp = p
        val day = p.getString("day", "") ?: ""
        today.value = if (day == dayKey()) {
            Snap(day, p.getInt("req", 0), p.getLong("tok", 0L))
        } else {
            Snap(dayKey(), 0, 0)
        }
    }

    @Synchronized
    fun record(tokens: Long) {
        val d = dayKey()
        val cur = today.value
        val base = if (cur.day == d) cur else Snap(d, 0, 0)
        val next = Snap(d, base.requests + 1, base.tokens + tokens)
        today.value = next
        sp?.edit()?.putString("day", d)?.putInt("req", next.requests)?.putLong("tok", next.tokens)?.apply()
    }

    /** Milliseconds until the daily quota resets. */
    fun msToReset(): Long {
        val c = Calendar.getInstance(pacific)
        c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return (c.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)
    }
}
