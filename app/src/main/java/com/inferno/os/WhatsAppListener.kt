package com.inferno.os

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads WhatsApp notifications. That is the supported way to "check messages",
 * and the notification's Reply action lets us answer a chat without opening the app.
 */
class WhatsAppListener : NotificationListenerService() {

    data class Msg(val chat: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        instance = this
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (title == "WhatsApp") return // the "N new messages" roll-up

        val chat = cleanTitle(title)
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        synchronized(lock) {
            val dupe = msgs.takeLast(5).any { it.chat == chat && it.text == text }
            if (!dupe) {
                msgs.addLast(Msg(chat, text, sbn.postTime))
                while (msgs.size > 50) msgs.removeFirst()
            }
            if (action != null) replies[chat.lowercase()] = action
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        synchronized(lock) { replies.remove(cleanTitle(title).lowercase()) }
    }

    companion object {
        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val lock = Any()
        private val msgs = ArrayDeque<Msg>()
        private val replies = HashMap<String, Notification.Action>()

        @Volatile var connected = false
        @Volatile private var instance: WhatsAppListener? = null

        private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\(\\d+ messages?\\)$"), "").trim()

        fun recent(from: String, limit: Int): List<Msg> = synchronized(lock) {
            val f = from.trim().lowercase()
            msgs.filter { f.isEmpty() || it.chat.lowercase().contains(f) }
                .takeLast(limit).reversed()
        }

        /** Answers an existing chat through its notification. False if there is no live reply action. */
        fun reply(chat: String, text: String): Boolean {
            val ctx = instance ?: return false
            val q = chat.trim().lowercase()
            if (q.isEmpty()) return false
            val action = synchronized(lock) {
                val key = replies.keys.firstOrNull { it.contains(q) || q.contains(it) }
                key?.let { replies[it] }
            } ?: return false
            val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
            val results = Bundle().apply { putCharSequence(inputs[0].resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                action.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }
        }
    }
}
