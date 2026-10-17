package com.inferno.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** The things Inferno can actually do on the phone. Every result is a short string Gemini reads back. */
class PhoneActions(private val ctx: Context, private val rec: RecordingControl) {

    interface RecordingControl {
        fun start(): String
        suspend fun stop(): String
    }

    data class Contact(val name: String, val number: String)

    suspend fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "call_contact" -> call(a.optString("who"))
            "send_whatsapp" -> sendWhatsApp(a.optString("to"), a.optString("message"))
            "read_whatsapp" -> readWhatsApp(a.optString("from"))
            "start_recording" -> rec.start()
            "stop_recording" -> rec.stop()
            "last_brief" -> InfernoState.recordings.value.firstOrNull { it.brief != null }?.brief?.take(3000)
                ?: "There are no saved briefs yet."
            else -> "Unknown action $name."
        }
    } catch (e: Exception) {
        "That failed: ${e.message}"
    }

    // ---- calls ----

    private fun call(who: String): String {
        if (who.isBlank()) return "No contact was given."
        if (!has(Manifest.permission.CALL_PHONE)) return "Phone permission is missing."
        val c = resolveContact(who) ?: return "I couldn't find $who in your contacts."
        val tm = ctx.getSystemService(TelecomManager::class.java)
        tm.placeCall(Uri.fromParts("tel", c.number, null), Bundle())
        return "Calling ${c.name}."
    }

    // ---- WhatsApp ----

    private fun sendWhatsApp(to: String, message: String): String {
        if (to.isBlank() || message.isBlank()) return "I need both a contact and a message."

        // Best case: answer an existing chat straight from its notification, fully hands-free.
        if (WhatsAppListener.reply(to, message)) return "Sent to $to on WhatsApp."

        // Otherwise open the chat with the text filled in. WhatsApp does not allow silent sends,
        // so the user has to tap send once.
        val c = resolveContact(to) ?: return "I couldn't find $to in your contacts."
        val digits = normalise(c.number)
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return "I opened WhatsApp with your message to ${c.name}. It needs one tap on send to go out."
    }

    private fun readWhatsApp(from: String): String {
        val msgs = WhatsAppListener.recent(from, 8)
        if (msgs.isEmpty()) {
            return if (!WhatsAppListener.connected) {
                "Notification access isn't switched on for Inferno, so I can't see WhatsApp messages."
            } else {
                "No new WhatsApp messages."
            }
        }
        return msgs.joinToString("\n") { "${it.chat}: ${it.text}" }
    }

    // ---- contacts ----

    private fun resolveContact(query: String): Contact? {
        val q = query.trim()
        if (Regex("^[+\\d\\s()-]{5,}$").matches(q)) {
            return Contact(q, q.filter { it.isDigit() || it == '+' })
        }
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        val cursor = ctx.contentResolver.query(
            uri,
            arrayOf(nameCol, numCol),
            nameCol + " LIKE ?",
            arrayOf("%" + q + "%"),
            null
        )
        if (cursor != null) {
            cursor.use { c ->
                var best: Contact? = null
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    if (n.equals(q, ignoreCase = true)) return Contact(n, num)
                    val b = best
                    if (b == null || n.length < b.name.length) best = Contact(n, num)
                }
                return best
            }
        }
        return null
    }

    /** wa.me needs digits with country code. Bare 10-digit numbers get DEFAULT_COUNTRY_CODE. */
    private fun normalise(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) return digits
        val d = digits.trimStart('0')
        return if (d.length == 10) DEFAULT_COUNTRY_CODE + d else d
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Change this if you are not in India. */
        const val DEFAULT_COUNTRY_CODE = "91"
    }
}
