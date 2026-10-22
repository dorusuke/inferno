package com.inferno.os

import android.content.Context
import java.io.File

/** Saves the last crash to a file so the app can show it next time. Java-level crashes only. */
object CrashLog {
    private var file: File? = null

    fun install(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            note("thread ${t.name}", e)
            old?.uncaughtException(t, e)
        }
    }

    fun note(where: String, e: Throwable) {
        try {
            file?.writeText("$where\n" + android.util.Log.getStackTraceString(e).take(3500))
        } catch (x: Throwable) { }
    }

    fun last(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (e: Throwable) { "" }

    fun clear() { try { file?.delete() } catch (e: Throwable) { } }
}
