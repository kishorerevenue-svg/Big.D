package com.soulfriend.pet

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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

/** Turns the AI's "actions" into real things on the phone. Each returns a short line to show the user.
 *  [onCall]: the floating pet places calls itself (one person: it calls; several: it asks which). Without it, the dialer opens. */
class Actions(private val ctx: Context, private val onCall: ((String, List<Pair<String, String>>) -> Unit)? = null, private val showText: (String, String) -> Unit) {
    private val store = Store(ctx)
    private val timeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

    fun run(a: JSONObject): String? = try {
        when (a.optString("type")) {
            "reminder" -> reminder(a.optString("at"), a.optString("text", "Reminder"), a.optString("repeat", ""))
            "timer" -> timer(a.optLong("seconds", 0), a.optString("label", "Timer"))
            "alarm" -> alarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label", "Habit AI"))
            "todo_add" -> { store.addTodo(a.optString("text")); "Added to your list: ${a.optString("text")}" }
            "todo_done" -> store.findTodo(a.optString("text")).let { if (it < 0) "Couldn't find that item in your list" else { store.setTodoDone(it, true); "Marked done: ${store.todos()[it].text}" } }
            "todo_remove" -> store.findTodo(a.optString("text")).let { if (it < 0) "Couldn't find that item in your list" else { val t = store.todos()[it].text; store.removeTodo(it); "Removed: $t" } }
            "note_save" -> { store.addNote(a.optString("text")); "Note saved" }
            "call" -> call(a.optString("number"), a.optString("contact"), a.optString("alt"), a.optString("way"))
            "whatsapp" -> whatsapp(a.optString("number"), a.optString("contact"), a.optString("message"), a.optString("alt"))
            "open_app" -> openApp(a.optString("name"))
            "show_text" -> { showText(a.optString("title", "Habit AI"), a.optString("text")); null }
            "copy_text" -> { copy(a.optString("text")); "Copied" }
            "remember" -> { store.remember(a.optString("text")); "I'll remember that" }
            "forget" -> if (store.forget(a.optString("text"))) "Forgotten" else "I didn't have that saved"
            "download" -> download(a.optString("url"), a.optString("name", "Habit-file"))
            "letter" -> openLetterHelper(a.optString("request"))
            "open_page" -> openPage(a.optString("page"))
            "game" -> if (GamesActivity.open(ctx, a.optString("name"))) null else "Open the Habit AI app to play"
            "play" -> play(a.optString("q"), a.optBoolean("short"))
            "habit_call" -> habitCall(a.optString("contact"), a.optString("alt"), a.optBoolean("video"))
            else -> null
        }
    } catch (e: Exception) { "Sorry, I couldn't do that on this phone" }

    /** Saves a Word/Excel file the server made into Downloads › Habit AI. */
    private fun download(url: String, name: String): String {
        if (!url.startsWith(store.server + "/files/")) return "That file link didn't come from your Habit server"
        downloadFile(url, name) ?: return "Couldn't save the file on this phone"
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Habit-file" }
        return "📄 $safe is ready. Open it from the download notification or the Files page in the Habit AI app."
    }

    /** Starts the download and remembers the file. Returns the phone's download id. */
    fun downloadFile(url: String, name: String): Long? {
        if (!url.startsWith(store.server + "/files/")) return null
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Habit-file" }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe).setDescription("From Habit AI")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Habit AI/$safe")
        else req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, safe)
        val id = try { ctx.getSystemService(DownloadManager::class.java).enqueue(req) } catch (e: Exception) { return null }
        store.addFile(SavedFile(id, safe, if (safe.endsWith(".xlsx")) "xlsx" else "docx", System.currentTimeMillis()))
        return id
    }

    /** "Play <a song>": finds it on YouTube (through the Habit server) and plays it right inside Habit, with more like it to
     *  swipe to. If the server can't search YouTube now, YouTube's own search opens with those words. */
    private fun play(q: String, short: Boolean): String? {
        val words = q.trim().take(120)
        if (words.isBlank()) return null
        Thread {
            val r = try { Net.get(store, "/api/videos/search?q=" + android.net.Uri.encode(words) + (if (short) "&short=1" else "")) } catch (e: Exception) { null }
            val arr = r?.json?.optJSONArray("videos")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (r != null && r.ok && arr != null && arr.length() > 0) {
                    MediaViewer.items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { v ->
                        MediaViewer.Item(if (short) "short" else "video", v.optString("id"), "", v.optString("ch"), v.optString("title"), 0, false) } }
                    MediaViewer.start = 0; MediaViewer.server = store.server; MediaViewer.onLike = null; MediaViewer.onComments = null
                    start(Intent(ctx, MediaViewer::class.java))
                } else start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(words))))
            }
        }.start()
        return if (store.lang == "te") "🎵 \"$words\" వెతికి ప్లే చేస్తున్నాను…" else "🎵 Finding and playing \"$words\"…"
    }

    /** "Make a letter" without the details: open the letter helper, which asks for each detail. */
    private fun openLetterHelper(request: String): String {
        val i = Intent(ctx, MainActivity::class.java).putExtra("tab", "files").putExtra("letter", request.ifBlank { "letter" })
        return if (start(i)) "📝 Opening the letter helper. Answer a few questions and I'll write it." else "Open the Habit AI app › Files to make the letter"
    }

    /** Opens a page of the Habit AI app (tasks, family, translate, files, vault, feedback, govt, messages). */
    /** Opens a part of the Habit AI app (the pet can take you anywhere in it). */
    private fun openPage(page: String): String? {
        var p = when (page.trim().lowercase()) {
            "chats", "chat", "habit chat" -> "messages"
            "media", "videos", "news" -> "feedback"
            "reminders", "notes", "todo", "to-do", "lists" -> "tasks"
            "settings", "pet", "my pet", "voice" -> "pet"
            "sos" -> "family"
            "letters", "papers", "documents" -> "files"
            else -> page.trim().lowercase()
        }
        if (p == "games") return if (GamesActivity.open(ctx)) null else "Open the Habit AI app"
        if (p == "office") { store.letterMode = "office"; p = "files" }
        else if (p == "files") store.letterMode = "public"
        if (p !in setOf("tasks", "family", "translate", "files", "vault", "feedback", "govt", "messages", "safety", "home", "pet")) p = "home"
        return if (start(Intent(ctx, MainActivity::class.java).putExtra("tab", p))) null else "Open the Habit AI app"
    }

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

    /** "Habit call to Roja", "video call Roja": a call inside Habit Chat to someone they chat with there. */
    private fun habitCall(contact: String, alt: String, video: Boolean): String {
        val te = store.lang == "te"
        if (contact.isBlank()) return if (te) "⚠ ఎవరికి Habit కాల్ చేయాలి?" else "⚠ Who should I call on Habit?"
        val c = habitChat(contact, alt)
            ?: return if (te) "⚠ $contact మీ Habit చాట్‌లలో లేరు. ముందు Habit లో వారితో ఒకసారి చాట్ మొదలుపెట్టండి." else "⚠ $contact isn't in your Habit chats yet. Start a chat with them in Habit once first."
        val need = listOf(Manifest.permission.RECORD_AUDIO) + (if (video) listOf(Manifest.permission.CAMERA) else emptyList())
        if (need.any { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            return if (te) "⚠ Habit కాల్స్ కోసం యాప్‌లో మైక్రోఫోన్ (వీడియోకి కెమెరా) అనుమతి ఇవ్వండి." else "⚠ Allow the microphone (and the camera for video) in the Habit app for Habit calls."
        CallManager.startOutgoing(ctx, c.cid, video)
        store.setCallWay(Names.key(contact), "habit")                // a plain "call <them>" is a Habit call from now on ("phone call" changes it back)
        return if (te) "🐾 ${c.name} కి Habit ${if (video) "వీడియో " else ""}కాల్ చేస్తున్నాను" else "🐾 ${if (video) "Video calling" else "Calling"} ${c.name} on Habit"
    }

    /** Someone they chat with one-to-one in Habit Chat whose name answers to [name] (or [alt]). */
    private fun habitChat(name: String, alt: String): ChatConv? = try {
        ChatDb.get(ctx).convs().filter { it.isDm && !it.isLocal && !it.blocked && it.member }
            .map { c -> c to maxOf(Names.match(name, c.name), if (alt.isBlank()) 0 else Names.match(alt, c.name)) }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    } catch (e: Exception) { null }

    /**
     * Every contact whose name matches [name], with each number once (name, number), at most 6. A name said in Telugu
     * finds one saved in English letters and the other way ("రోజా" finds "Roja"): first as it is, then [alt] (the brain's
     * other spelling), then by how the names sound.
     */
    fun lookupAll(name: String, alt: String = ""): List<Pair<String, String>> {
        if (name.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val keys = setOf(Names.key(name), Names.key(alt)).filter { it.length >= 2 }
        // "Roja" means the contact saved as just "Roja", not "Attayya Roja" or "Roja Divya" (they say those names in full)
        fun whole(l: List<Pair<String, String>>) = l.filter { Names.key(it.first) in keys }.ifEmpty { l }
        exact(name).ifEmpty { if (alt.isBlank()) emptyList() else exact(alt) }.let { if (it.isNotEmpty()) return whole(it).take(6) }
        val same = ArrayList<Pair<String, String>>(); val near = ArrayList<Pair<String, String>>(); val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 6000) {
                    val who = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val how = maxOf(Names.match(name, who), if (alt.isBlank()) 0 else Names.match(alt, who))
                    if (how == 0) continue
                    val key = digits(num).takeLast(10)
                    if (key.length < 5 || !seen.add(key)) continue
                    (if (how == 2) same else near).add(who to num)
                }
            }
        } catch (e: Exception) { }
        return whole(same.ifEmpty { near }).take(6)
    }

    private fun exact(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); val seen = HashSet<String>()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
                ContactsContract.Data.IS_SUPER_PRIMARY + " DESC, " + ContactsContract.Data.IS_PRIMARY + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < 25) {
                    val num = c.getString(1) ?: continue
                    val key = digits(num).takeLast(10)
                    if (key.length < 5 || !seen.add(key)) continue
                    out.add((c.getString(0) ?: name) to num)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun call(number: String, contact: String, alt: String = "", way: String = ""): String? {
        val given = digits(number)
        // how they call this person: they say "Habit call" or "phone call" once, and a plain "call Roja" does the same after
        if (given.isBlank() && contact.isNotBlank()) {
            val k = Names.key(contact)
            if (way == "phone") store.setCallWay(k, "phone")
            else if (store.callWay(k) == "habit" && habitChat(contact, alt) != null) return habitCall(contact, alt, false)
        }
        val found = if (given.isBlank()) lookupAll(contact, alt) else listOf(contact.ifBlank { given } to given)
        if (found.isEmpty()) {
            val te = store.lang == "te"
            // not in the phone's contacts, but someone they chat with in Habit Chat: a Habit call
            if (contact.isNotBlank()) habitChat(contact, alt)?.let { c ->
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    CallManager.startOutgoing(ctx, c.cid, false)
                    return if (te) "🐾 ${c.name} కి Habit కాల్ చేస్తున్నాను" else "🐾 Calling ${c.name} on Habit"
                }
            }
            // "⚠": it didn't happen (the pet says this, not "calling")
            return if (contact.isNotBlank() && ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                (if (te) "⚠ $contact ని వెతకడానికి Habit AI యాప్‌లో Contacts అనుమతి ఇవ్వండి." else "⚠ Allow Contacts in the Habit AI app so I can find $contact.")
            else if (contact.isNotBlank()) (if (te) "⚠ మీ కాంటాక్ట్‌లలో $contact దొరకలేదు. ఫోన్‌లో సేవ్ చేసిన పేరు చెప్పండి, లేదా నంబర్ చెప్పండి." else "⚠ I couldn't find $contact in your contacts. Say the name as it's saved, or tell me the number.")
            else (if (te) "⚠ ఏ నంబర్‌కి కాల్ చేయాలి?" else "⚠ Which number should I call?")
        }
        // the floating pet calls by itself (and asks which one when several match)
        onCall?.let { it(contact, found); return null }
        val (who, n) = found.first()
        return if (start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(n))))) "Opening the dialer for $who. Tap Call." else "No phone app found"
    }

    private fun whatsapp(number: String, contact: String, message: String, alt: String = ""): String {
        val found = lookup(contact) ?: lookupAll(contact, alt).firstOrNull()?.second
        var n = digits(number).ifBlank { found?.let { digits(it) } ?: "" }.removePrefix("+")
        if (n.length == 11 && n.startsWith("0")) n = n.drop(1)
        if (n.length == 10) n = "91$n"
        val enc = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        if (n.isNotBlank()) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=$enc"))
            if (installed("com.whatsapp")) i.setPackage("com.whatsapp")
            if (start(i)) return "WhatsApp is open with your message. Tap the green ➤ Send button to send it."
        }
        if (contact.isNotBlank() && found == null && number.isBlank()) {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
            if (installed("com.whatsapp")) share.setPackage("com.whatsapp")
            val why = if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                "Allow Contacts in the Habit AI app so I can find people. " else "I couldn't find $contact in your contacts. "
            return if (start(share)) why + "In WhatsApp, choose $contact, then tap ➤ Send." else "WhatsApp is not installed"
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        if (installed("com.whatsapp")) share.setPackage("com.whatsapp")
        return if (start(share)) "In WhatsApp, choose the chat, then tap ➤ Send." else "WhatsApp is not installed"
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
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit AI", text))
    }

    fun share(text: String) {
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    private fun installed(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    private fun start(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
}
