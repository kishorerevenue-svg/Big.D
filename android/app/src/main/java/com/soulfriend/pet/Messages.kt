package com.soulfriend.pet

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** A new chat message seen in a notification, with the app's own "Reply" button. */
data class Incoming(
    val key: String,
    val pkg: String,
    val app: String,
    val chat: String,
    val sender: String,
    val text: String,
    val group: Boolean,
    val action: Notification.Action,
    val at: Long = System.currentTimeMillis()
)

/** Knows the chat apps, reads their notifications and sends replies through them. */
object MessageHub {
    /** group key -> (label, packages) */
    val APPS: List<Triple<String, String, List<String>>> = listOf(
        Triple("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
        Triple("sms", "SMS (Messages)", listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms")),
        Triple("telegram", "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web")),
        Triple("instagram", "Instagram", listOf("com.instagram.android")),
        Triple("messenger", "Messenger", listOf("com.facebook.orca")),
        Triple("signal", "Signal", listOf("org.thoughtcrime.securesms"))
    )

    fun groupOf(pkg: String): String? = APPS.firstOrNull { pkg in it.third }?.first
    private fun labelOf(pkg: String): String = when (groupOf(pkg)) { "sms" -> "SMS"; null -> pkg; else -> APPS.first { pkg in it.third }.second }

    /** The pet shows new messages through this. [autoSent] is the text if it was auto-replied. */
    @Volatile var listener: ((Incoming, String?) -> Unit)? = null
    @Volatile var latest: Incoming? = null

    private val seen = LinkedHashSet<String>()
    private val sentTexts = LinkedHashSet<String>()
    private val lastAuto = HashMap<String, Long>()

    fun accessGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, MessageListener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessSettings(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MessageListener::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Reads one notification. Returns null if it is not a new message we can reply to. */
    fun parse(ctx: Context, sbn: StatusBarNotification): Incoming? {
        if (sbn.packageName == ctx.packageName || groupOf(sbn.packageName) == null) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val actions = n.actions ?: return null
        val reply = actions.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true &&
                (Build.VERSION.SDK_INT < 28 || a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || a.semanticAction == Notification.Action.SEMANTIC_ACTION_NONE)
        } ?: actions.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return null

        val ex = n.extras ?: return null
        var sender = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val group = (Build.VERSION.SDK_INT >= 28 && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) || !conv.isNullOrBlank()

        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val last = msgs.last() as? Bundle
            if (last != null) {
                last.getCharSequence("text")?.toString()?.trim()?.let { if (it.isNotEmpty()) text = it }
                @Suppress("DEPRECATION")
                val person = if (Build.VERSION.SDK_INT >= 28) last.getParcelable<Person>("sender_person") else null
                val who = person?.name?.toString() ?: last.getCharSequence("sender")?.toString()
                if (who.isNullOrBlank()) return null          // last message was written by the owner
                sender = who.trim()
            }
        }
        val self = ex.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        if (text.isEmpty() || sender.isEmpty() || sender == self || sender.equals("You", true)) return null
        if (Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE).matches(text)) return null
        if (wasSentByUs(text)) return null
        val chat = if (group && !conv.isNullOrBlank()) conv else sender
        return Incoming(sbn.key, sbn.packageName, labelOf(sbn.packageName), chat, sender, text, group, reply)
    }

    /** True the first time we see this exact message. */
    @Synchronized fun isNew(m: Incoming): Boolean {
        val id = m.key + "|" + m.sender + "|" + m.text
        if (!seen.add(id)) return false
        while (seen.size > 100) seen.remove(seen.first())
        return true
    }

    @Synchronized private fun wasSentByUs(text: String) = sentTexts.any { it.trim() == text.trim() }

    /** Sends [reply] through the app's own notification Reply button. */
    fun send(ctx: Context, m: Incoming, reply: String): Boolean {
        val inputs = m.action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, reply) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            m.action.actionIntent.send(ctx, 0, intent)
            synchronized(this) { sentTexts.add(reply); while (sentTexts.size > 30) sentTexts.remove(sentTexts.first()) }
            true
        } catch (e: Exception) { false }
    }

    /** Auto-reply: not in groups, at most once per chat every 30 minutes. */
    @Synchronized fun autoAllowed(m: Incoming): Boolean {
        if (m.group) return false
        val id = m.pkg + "|" + m.chat
        val now = System.currentTimeMillis()
        if (now - (lastAuto[id] ?: 0L) < 30 * 60_000L) return false
        lastAuto[id] = now
        return true
    }
}

/** Android calls this for every new notification once "Notification access" is allowed. */
class MessageListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = Store(this)
        if (!store.msgWatch) return
        val m = MessageHub.parse(this, sbn) ?: return
        if (MessageHub.groupOf(m.pkg) !in store.msgApps) return
        if (!MessageHub.isNew(m)) return
        MessageHub.latest = m
        if (store.autoReply && MessageHub.autoAllowed(m)) autoReply(store, m)
        else main.post { MessageHub.listener?.invoke(m, null) }
    }

    private fun autoReply(store: Store, m: Incoming) {
        val base = store.autoMessage.ifBlank { "I'm busy right now, I'll reply soon." }
        val finish = { text: String ->
            val out = if (store.autoTag) "$text (auto-reply)" else text
            val ok = MessageHub.send(this, m, out)
            main.post { MessageHub.listener?.invoke(m, if (ok) out else null) }
        }
        if (!store.autoSmart) { finish(base); return }
        Brain(store).draftReply(m, base, auto = true, onDraft = { reply, _ -> finish(reply) }, onError = { finish(base) })
    }
}
