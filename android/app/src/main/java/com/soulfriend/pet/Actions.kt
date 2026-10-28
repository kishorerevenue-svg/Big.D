package com.soulfriend.pet

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turns the AI's "actions" into real things on the phone. Each returns a short line to show the user. */
class Actions(private val ctx: Context, private val showText: (String, String) -> Unit) {
    private val store = Store(ctx)
    private val timeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

    fun run(a: JSONObject): String? = try {
        when (a.optString("type")) {
            "reminder" -> reminder(a.optString("at"), a.optString("text", "Reminder"), a.optString("repeat", ""))
            "timer" -> timer(a.optLong("seconds", 0), a.optString("label", "Timer"))
            "alarm" -> alarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label", "BigD AI"))
            "todo_add" -> { store.addTodo(a.optString("text")); "Added to your list: ${a.optString("text")}" }
            "todo_done" -> store.findTodo(a.optString("text")).let { if (it < 0) "Couldn't find that item in your list" else { store.setTodoDone(it, true); "Marked done: ${store.todos()[it].text}" } }
            "todo_remove" -> store.findTodo(a.optString("text")).let { if (it < 0) "Couldn't find that item in your list" else { val t = store.todos()[it].text; store.removeTodo(it); "Removed: $t" } }
            "note_save" -> { store.addNote(a.optString("text")); "Note saved" }
            "call" -> call(a.optString("number"), a.optString("contact"))
            "whatsapp" -> whatsapp(a.optString("number"), a.optString("contact"), a.optString("message"))
            "open_app" -> openApp(a.optString("name"))
            "show_text" -> { showText(a.optString("title", "BigD AI"), a.optString("text")); null }
            "copy_text" -> { copy(a.optString("text")); "Copied" }
            "remember" -> { store.remember(a.optString("text")); "I'll remember that" }
            "forget" -> if (store.forget(a.optString("text"))) "Forgotten" else "I didn't have that saved"
            else -> null
        }
    } catch (e: Exception) { "Sorry, I couldn't do that on this phone" }

    private fun parseTime(s: String): Long? {
        if (s.isBlank()) return null
        return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {
            try { ZonedDateTime.parse(s).toInstant().toEpochMilli() } catch (e2: Exception) {
                try { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (e3: Exception) { null }
            }
        }
    }

    private fun reminder(at: String, text: String, repeat: String): String {
        var ms = parseTime(at) ?: return "I couldn't understand the time for that reminder"
        val rep = Reminders.cleanRepeat(repeat)
        if (ms < System.currentTimeMillis() - 30_000) {
            if (rep.isEmpty()) return "That time has already passed"
            ms = Reminders.nextTime(ms, rep)
        }
        val (_, exact) = Reminders.add(ctx, ms, text, rep)
        val whenText = timeFmt.format(java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        val again = if (rep.isEmpty()) "" else ", then ${Reminders.repeatLabel(rep)}"
        return "Reminder set for $whenText$again" + if (exact) "" else " (may ring a few minutes late)"
    }

    private fun timer(seconds: Long, label: String): String {
        if (seconds <= 0) return "How long should the timer be?"
        Reminders.add(ctx, System.currentTimeMillis() + seconds * 1000, "Time's up: $label")
        val m = seconds / 60; val s = seconds % 60
        return "Timer set: " + (if (m > 0) "$m min " else "") + (if (s > 0) "$s sec" else "")
    }

    private fun alarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23) return "What time should the alarm ring?"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(i)) "Alarm set for %d:%02d".format(hour, minute) else "Your phone's clock app didn't accept the alarm"
    }

    private fun lookup(name: String): String? {
        if (name.isBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    private fun call(number: String, contact: String): String {
        val n = digits(number).ifBlank { lookup(contact)?.let { digits(it) } ?: "" }
        if (n.isBlank()) {
            return if (contact.isNotBlank() && ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                "Allow Contacts in the BigD AI app so I can find $contact"
            else if (contact.isNotBlank()) "I couldn't find $contact in your contacts" else "Which number should I call?"
        }
        return if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")))) "Opening the dialer for ${contact.ifBlank { n }}. Tap Call." else "No phone app found"
    }

    private fun whatsapp(number: String, contact: String, message: String): String {
        var n = digits(number).ifBlank { lookup(contact)?.let { digits(it) } ?: "" }.removePrefix("+")
        if (n.length == 10) n = "91$n"
        val enc = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        if (n.isNotBlank()) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=$enc"))
            if (installed("com.whatsapp")) i.setPackage("com.whatsapp")
            if (start(i)) return "Opening WhatsApp. Check the message and tap Send."
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        if (installed("com.whatsapp")) share.setPackage("com.whatsapp")
        return if (start(share)) "Opening WhatsApp. Pick the chat and tap Send." else "WhatsApp is not installed"
    }

    private fun openApp(name: String): String {
        val q = name.trim().lowercase()
        if (q.isBlank()) return "Which app should I open?"
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        val hit = apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) || q.contains(it.first.lowercase()) }
            ?: return "I couldn't find an app called $name"
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return "I couldn't open ${hit.first}"
        return if (start(i)) "Opening ${hit.first}" else "I couldn't open ${hit.first}"
    }

    fun copy(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BigD AI", text))
    }

    fun share(text: String) {
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    private fun installed(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    private fun start(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
}
