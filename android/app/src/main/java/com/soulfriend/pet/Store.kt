package com.soulfriend.pet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Everything the puppy remembers, saved on the phone. */
object Config {
    /** Put your server address here, or type it in the app's setup screen. No slash at the end. */
    const val DEFAULT_SERVER = "https://bigdai.tech"
}

data class Todo(val text: String, val done: Boolean)
/** A Word/Excel file Habit made; [id] is the phone's download id. */
data class SavedFile(val id: Long, val name: String, val format: String, val at: Long)
/** A family member for SOS. [relation] is optional, e.g. "Son". */
data class FamilyContact(val name: String, val phone: String, val relation: String = "")
/** [repeat] is "", "daily", "weekdays", "weekly" or "monthly". */
data class Reminder(val id: Int, val at: Long, val text: String, val repeat: String = "")

class Store(context: Context) {
    internal val ctx: Context = context.applicationContext
    private val p: SharedPreferences = ctx.getSharedPreferences("soul_friend", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", Config.DEFAULT_SERVER) ?: Config.DEFAULT_SERVER
        set(v) { p.edit().putString("server", v.trim().trimEnd('/')).apply() }

    /** "auto", "te" or "en" */
    var lang: String
        get() = p.getString("lang", "auto") ?: "auto"
        set(v) { p.edit().putString("lang", v).apply() }

    var voiceOn: Boolean
        get() = p.getBoolean("voice", true)
        set(v) { p.edit().putBoolean("voice", v).apply() }

    /** puppy, kitten, bunny, panda, bear or fox */
    var petKind: String
        get() = p.getString("petKind", "puppy") ?: "puppy"
        set(v) { p.edit().putString("petKind", v).apply() }

    /** A random id for this phone, so the server can apply a fair daily limit. Not linked to the person. */
    val deviceId: String
        get() = p.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }

    /** Mask Aadhaar, phone and PAN numbers before anything leaves the phone. */
    var hidePersonal: Boolean
        get() = p.getBoolean("hidePersonal", false)
        set(v) { p.edit().putBoolean("hidePersonal", v).apply() }

    /** Use the natural (human-like) voice from the server when it is set up. */
    var naturalVoice: Boolean
        get() = p.getBoolean("naturalVoice", true)
        set(v) { p.edit().putBoolean("naturalVoice", v).apply() }
    /** Off = a normal grown-up voice. On = a high, cute cartoon voice. */
    var cuteVoice: Boolean
        get() = p.getBoolean("cuteVoice", false)
        set(v) { p.edit().putBoolean("cuteVoice", v).apply() }

    /** Habit AI Lite: find cards in new screenshots by itself (checked while charging). */
    var autoCards: Boolean
        get() = p.getBoolean("autoCards", false)
        set(v) { p.edit().putBoolean("autoCards", v).apply() }
    var autoCardsUpTo: Long
        get() = p.getLong("autoCardsUpTo", 0L)
        set(v) { p.edit().putLong("autoCardsUpTo", v).apply() }
    var autoCardsFound: Int
        get() = p.getInt("autoCardsFound", 0)
        set(v) { p.edit().putInt("autoCardsFound", v).apply() }
    var autoCardsAt: Long
        get() = p.getLong("autoCardsAt", 0L)
        set(v) { p.edit().putLong("autoCardsAt", v).apply() }

    /** The pet looks at you through the front camera when you open the app (at most every 3 hours), after you said yes once. */
    var mirrorOn: Boolean
        get() = p.getBoolean("mirrorOn", true)
        set(v) { p.edit().putBoolean("mirrorOn", v).apply() }
    var mirrorAsked: Boolean
        get() = p.getBoolean("mirrorAsked", false)
        set(v) { p.edit().putBoolean("mirrorAsked", v).apply() }
    // one-tap updates (Lite): the newest version the server told us about
    var updateCheckedAt: Long
        get() = p.getLong("updateCheckedAt", 0L)
        set(v) { p.edit().putLong("updateCheckedAt", v).apply() }
    var updateCode: Int
        get() = p.getInt("updateCode", 0)
        set(v) { p.edit().putInt("updateCode", v).apply() }
    var updateName: String
        get() = p.getString("updateName", "") ?: ""
        set(v) { p.edit().putString("updateName", v).apply() }
    var updateNotes: String
        get() = p.getString("updateNotes", "") ?: ""
        set(v) { p.edit().putString("updateNotes", v).apply() }
    var updateMb: Int
        get() = p.getInt("updateMb", 0)
        set(v) { p.edit().putInt("updateMb", v).apply() }
    /** "Later" on the update card: hidden for this version until this time. */
    var updateLaterUntil: Long
        get() = p.getLong("updateLaterUntil", 0L)
        set(v) { p.edit().putLong("updateLaterUntil", v).apply() }
    /** General questions may be counted (anonymously) so common ones get a prepared answer. On unless turned off. */
    var learnShare: Boolean
        get() = p.getBoolean("learnShare", true)
        set(v) { p.edit().putBoolean("learnShare", v).apply() }
    /** The person said yes to "Can I use the camera as my eyes?" (the pet looks only when asked). */
    var eyesAsked: Boolean
        get() = p.getBoolean("eyesAsked", false)
        set(v) { p.edit().putBoolean("eyesAsked", v).apply() }
    var mirrorAt: Long
        get() = p.getLong("mirrorAt", 0L)
        set(v) { p.edit().putLong("mirrorAt", v).apply() }

    /** The pet's little sounds (tada, laugh, wow…) when they fit the moment. */
    var funSounds: Boolean
        get() = p.getBoolean("funSounds", true)
        set(v) { p.edit().putBoolean("funSounds", v).apply() }

    /** Conversation mode: after a spoken answer the pet listens a little longer for the next question (no tap needed).
     *  "on" (the default: people asked to talk without tapping every time) or "off" ("ask" was the old default). */
    var convo: String
        get() {
            // once, with this version: on for everyone (the old yes/no question often didn't hear the answer and left it off)
            if (!p.getBoolean("convoOn2", false)) { p.edit().putString("convo", "on").putBoolean("convoOn2", true).apply(); return "on" }
            return (p.getString("convo", "on") ?: "on").let { if (it == "ask") "on" else it }
        }
        set(v) { p.edit().putString("convo", v).apply() }
    /** Habit push ([Push]): the app's Firebase ids from the server, the push address the server has, and when. */
    var pushCfg: String
        get() = p.getString("pushCfg", "") ?: ""
        set(v) { p.edit().putString("pushCfg", v).apply() }
    var pushTok: String
        get() = p.getString("pushTok", "") ?: ""
        set(v) { p.edit().putString("pushTok", v).apply() }
    var pushSentAt: Long
        get() = p.getLong("pushSentAt", 0L)
        set(v) { p.edit().putLong("pushSentAt", v).apply() }
    var pushCheckedAt: Long
        get() = p.getLong("pushCheckedAt", 0L)
        set(v) { p.edit().putLong("pushCheckedAt", v).apply() }
    /** The voice typing that heard words last time, when it isn't the phone's usual one ([Ears]). */
    var ears: String
        get() = p.getString("ears", "") ?: ""
        set(v) { p.edit().putString("ears", v).apply() }
    /** The voice typings that don't take the pet's own sound (they open the microphone themselves), comma-separated. */
    var earsNoFeed: String
        get() = p.getString("earsNoFeed", "") ?: ""
        set(v) { p.edit().putString("earsNoFeed", v).apply() }
    var convoAsked: Int
        get() = p.getInt("convoAsked", 0)
        set(v) { p.edit().putInt("convoAsked", v).apply() }
    /** "Interesting talk": when a spoken conversation goes quiet, the pet may say one short fact, tip or riddle (Sparks). */
    var sparks: Boolean
        get() = p.getBoolean("sparks", true)
        set(v) { p.edit().putBoolean("sparks", v).apply() }
    fun sparksSeen(): Set<String> = (p.getString("sparksSeen", "") ?: "").split(",").filter { it.isNotBlank() }.toSet()
    fun sparkTold(id: String) {
        val l = (p.getString("sparksSeen", "") ?: "").split(",").filter { it.isNotBlank() && it != id } + id
        p.edit().putString("sparksSeen", l.takeLast(15).joinToString(",")).apply()
    }

    /** Who is talking now, when they said who they are ("I'm Ravi"). Family members share a phone, so it lasts only for
     *  this conversation: it ends with the conversation, or 10 minutes after they last talked. */
    fun speakerNow(): String? {
        val name = p.getString("speaker", "") ?: ""
        return if (name.isNotBlank() && System.currentTimeMillis() - p.getLong("speakerAt", 0L) < 10 * 60_000L) name else null
    }
    fun setSpeaker(name: String) {
        val n = (Regex("^[\\p{L}\\p{M} .'-]+").find(name.trim())?.value ?: "").trim().take(40)   // a name only
        p.edit().putString("speaker", n).putLong("speakerAt", if (n.isEmpty()) 0L else System.currentTimeMillis()).apply()
    }
    fun touchSpeaker() { if (speakerNow() != null) p.edit().putLong("speakerAt", System.currentTimeMillis()).apply() }

    /** Show the Reels remote buttons on screen (Plus edition). */
    var remoteOn: Boolean
        get() = p.getBoolean("remoteOn", false)
        set(v) { p.edit().putBoolean("remoteOn", v).apply() }

    /** Logged in with a mobile number (or email) and password: the first screen (LoginActivity) is done. */
    var loggedIn: Boolean
        get() = p.getBoolean("loggedIn", false)
        set(v) { p.edit().putBoolean("loggedIn", v).apply() }
    /** The login, shown safely (e.g. ••••••2345). */
    var loginShown: String
        get() = p.getString("loginShown", "") ?: ""
        set(v) { p.edit().putString("loginShown", v).apply() }

    /** First-time setup guide finished? */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) { p.edit().putBoolean("onboarded", v).apply() }

    // ---- good-morning briefing ----
    var briefOn: Boolean
        get() = p.getBoolean("briefOn", false)
        set(v) { p.edit().putBoolean("briefOn", v).apply() }
    var briefHour: Int
        get() = p.getInt("briefHour", 7)
        set(v) { p.edit().putInt("briefHour", v).apply() }
    var briefMinute: Int
        get() = p.getInt("briefMinute", 0)
        set(v) { p.edit().putInt("briefMinute", v).apply() }
    /** Town or city for weather and panchangam timings, e.g. "Visakhapatnam". */
    var city: String
        get() = p.getString("city", "") ?: ""
        set(v) { p.edit().putString("city", v.trim()).apply() }

    // ---- message replies (WhatsApp, SMS, ...) ----
    /** Watch new chat-app notifications and suggest replies. */
    var msgWatch: Boolean
        get() = p.getBoolean("msgWatch", false)
        set(v) { p.edit().putBoolean("msgWatch", v).apply() }
    /** Which app groups to watch: whatsapp, sms, telegram, instagram, messenger, signal */
    var msgApps: Set<String>
        get() = p.getStringSet("msgApps", setOf("whatsapp", "sms")) ?: setOf("whatsapp", "sms")
        set(v) { p.edit().putStringSet("msgApps", HashSet(v)).apply() }
    var msgReadAloud: Boolean
        get() = p.getBoolean("msgReadAloud", true)
        set(v) { p.edit().putBoolean("msgReadAloud", v).apply() }
    var autoReply: Boolean
        get() = p.getBoolean("autoReply", false)
        set(v) { p.edit().putBoolean("autoReply", v).apply() }
    var autoMessage: String
        get() = p.getString("autoMessage", "I'm busy right now, I'll reply soon 🙏") ?: ""
        set(v) { p.edit().putString("autoMessage", v.trim()).apply() }
    /** Let the AI adapt the auto-reply to each message (language, greeting). */
    var autoSmart: Boolean
        get() = p.getBoolean("autoSmart", true)
        set(v) { p.edit().putBoolean("autoSmart", v).apply() }
    /** Add "(auto-reply)" at the end so people know it was automatic. */
    var autoTag: Boolean
        get() = p.getBoolean("autoTag", true)
        set(v) { p.edit().putBoolean("autoTag", v).apply() }

    // ---- message safety (danger alerts) ----
    /** Show a red warning when a message looks like fraud or danger. */
    var scamAlerts: Boolean
        get() = p.getBoolean("scamAlerts", true)
        set(v) { p.edit().putBoolean("scamAlerts", v).apply() }
    /** Checked messages of the last 7 days (newest last), kept only on this phone (see [MsgLog]). */
    fun msgLog(): List<JSONObject> { moveOldLog(); return MsgLog.all(ctx) }
    fun putMsg(o: JSONObject) { moveOldLog(); MsgLog.add(ctx, o) }
    fun removeMsg(id: String) = MsgLog.remove(ctx, id)
    fun clearMsgs() = MsgLog.clear(ctx)
    /** Earlier versions kept the list inside the settings, which made every small setting change slow. */
    private fun moveOldLog() {
        val old = p.getString("msgLog", null) ?: return
        p.edit().remove("msgLog").apply()
        try { val a = JSONArray(old); for (i in 0 until a.length()) a.optJSONObject(i)?.let { MsgLog.add(ctx, it) } } catch (e: Exception) { }
    }

    // ---- bottom tabs ----
    /** Tab keys in the order the user arranged them (empty = default order). */
    var tabOrder: List<String>
        get() = (p.getString("tabOrder", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) { p.edit().putString("tabOrder", v.joinToString(",")).apply() }

    // ---- community ----
    var feedName: String
        get() = p.getString("feedName", "") ?: ""
        set(v) { p.edit().putString("feedName", v.trim().take(30)).apply() }
    var feedAgreed: Boolean
        get() = p.getBoolean("feedAgreed", false)
        set(v) { p.edit().putBoolean("feedAgreed", v).apply() }
    /** Habit team password (only for the app's owner), to reply to and remove posts. */
    var teamKey: String
        get() = p.getString("teamKey", "") ?: ""
        set(v) { p.edit().putString("teamKey", v.trim()).apply() }
    var blockedUsers: Set<String>
        get() = p.getStringSet("blockedUsers", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("blockedUsers", HashSet(v)).apply() }

    // ---- letter helper ----
    /** "public" (applications and letters for people) or "office" (official correspondence of a government office). */
    var letterMode: String
        get() = p.getString("letterMode", "public") ?: "public"
        set(v) { p.edit().putString("letterMode", v).apply() }
    /** The letters' language last chosen ("" auto, "en", "te"): an English letter also means English voice typing. */
    var letterLang: String
        get() = p.getString("letterLang", "") ?: ""
        set(v) { p.edit().putString("letterLang", v).apply() }
    /** The office's own details for office letters: desig, office, district, name, ref, contact (kept on this phone). */
    var officeInfo: JSONObject
        get() = try { JSONObject(p.getString("officeInfo", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        set(v) { p.edit().putString("officeInfo", v.toString()).apply() }

    // ---- translate ----
    /** The language to translate into: "te", "en" or "hi" ("" = not chosen yet). */
    var trTo: String
        get() = p.getString("trTo", "") ?: ""
        set(v) { p.edit().putString("trTo", v).apply() }

    // ---- govt services ----
    /** "ap" (Andhra Pradesh) or "ts" (Telangana). */
    var govtState: String
        get() = p.getString("govtState", "ap") ?: "ap"
        set(v) { p.edit().putString("govtState", v).apply() }
    /** The list of official service links from the server, kept so the page also opens offline. */
    var govtCache: String
        get() = p.getString("govtCache", "") ?: ""
        set(v) { p.edit().putString("govtCache", v).putLong("govtCacheAt", System.currentTimeMillis()).apply() }
    val govtCacheAt: Long get() = p.getLong("govtCacheAt", 0L)

    /** This week's new scams (made on the server from the news), kept on the phone for 12 hours. */
    var scamCache: JSONObject?
        get() = try { p.getString("scamCache", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
        set(v) { p.edit().putString("scamCache", v?.toString()).putLong("scamCacheAt", System.currentTimeMillis()).apply() }
    val scamCacheAt: Long get() = p.getLong("scamCacheAt", 0L)

    // ---- family & SOS (kept only on this phone) ----
    fun family(): List<FamilyContact> {
        val a = try { JSONArray(p.getString("family", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { FamilyContact(it.optString("name"), it.optString("phone"), it.optString("relation")) }
    }
    fun saveFamily(list: List<FamilyContact>) {
        val a = JSONArray(); list.take(5).forEach { a.put(JSONObject().put("name", it.name).put("phone", it.phone).put("relation", it.relation)) }
        p.edit().putString("family", a.toString()).apply()
    }
    /** SOS calls the first family member by itself after a 5-second countdown (otherwise the dialer opens with the number). */
    var sosAutoCall: Boolean
        get() = p.getBoolean("sosAutoCall", true)
        set(v) { p.edit().putBoolean("sosAutoCall", v).apply() }

    var petX: Int
        get() = p.getInt("petX", -1)
        set(v) { p.edit().putInt("petX", v).apply() }
    var petY: Int
        get() = p.getInt("petY", -1)
        set(v) { p.edit().putInt("petY", v).apply() }

    // ---- to-do list ----
    fun todos(): List<Todo> {
        val a = JSONArray(p.getString("todos", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Todo(o.optString("text"), o.optBoolean("done")) }
    }
    private fun saveTodos(list: List<Todo>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("text", it.text).put("done", it.done)) }
        p.edit().putString("todos", a.toString()).apply()
    }
    fun addTodo(text: String) { if (text.isNotBlank()) saveTodos(todos() + Todo(text.trim(), false)) }
    fun setTodoDone(index: Int, done: Boolean) {
        val l = todos().toMutableList(); if (index in l.indices) { l[index] = l[index].copy(done = done); saveTodos(l) }
    }
    fun removeTodo(index: Int) { val l = todos().toMutableList(); if (index in l.indices) { l.removeAt(index); saveTodos(l) } }
    /** Finds the item that best matches what the AI said. Returns its index or -1. */
    fun findTodo(text: String): Int {
        val q = text.trim().lowercase(); val l = todos()
        l.indexOfFirst { it.text.lowercase() == q }.let { if (it >= 0) return it }
        return l.indexOfFirst { it.text.lowercase().contains(q) || q.contains(it.text.lowercase()) }
    }

    // ---- notes ----
    fun notes(): List<String> {
        val a = JSONArray(p.getString("notes", "[]")); return (0 until a.length()).map { a.getString(it) }
    }
    fun addNote(text: String) {
        if (text.isBlank()) return
        val a = JSONArray(); (notes() + text.trim()).takeLast(100).forEach { a.put(it) }
        p.edit().putString("notes", a.toString()).apply()
    }
    fun removeNote(index: Int) {
        val l = notes().toMutableList(); if (index in l.indices) l.removeAt(index)
        val a = JSONArray(); l.forEach { a.put(it) }; p.edit().putString("notes", a.toString()).apply()
    }

    // ---- things the puppy remembers about you ----
    fun memories(): List<String> {
        val a = JSONArray(p.getString("memories", "[]")); return (0 until a.length()).map { a.getString(it) }
    }
    private fun saveMemories(l: List<String>) { val a = JSONArray(); l.takeLast(80).forEach { a.put(it) }; p.edit().putString("memories", a.toString()).apply() }
    /** The whole memory, as the diary tidy wrote it ([Diary]). */
    fun setMemories(l: List<String>) = saveMemories(l.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    // ---- the pet's diary: its talks, kept only on this phone, and up to when they went into its memory ----
    var diary: String
        get() = p.getString("diary", "[]") ?: "[]"
        set(v) { p.edit().putString("diary", v).apply() }
    var diaryTidyAt: Long
        get() = p.getLong("diaryTidyAt", 0L)
        set(v) { p.edit().putLong("diaryTidyAt", v).apply() }
    fun remember(text: String) { val t = text.trim(); if (t.isNotEmpty() && memories().none { it.equals(t, true) }) saveMemories(memories() + t) }
    fun forget(text: String): Boolean {
        val q = text.trim().lowercase(); val l = memories()
        val keep = l.filterNot { it.lowercase() == q || it.lowercase().contains(q) || (q.length > 3 && q.contains(it.lowercase())) }
        saveMemories(keep); return keep.size < l.size
    }
    fun removeMemory(index: Int) { val l = memories().toMutableList(); if (index in l.indices) { l.removeAt(index); saveMemories(l) } }

    // ---- files Habit made ----
    fun files(): List<SavedFile> {
        val a = JSONArray(p.getString("files", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); SavedFile(o.getLong("id"), o.optString("name"), o.optString("format"), o.optLong("at")) }
    }
    private fun saveFiles(l: List<SavedFile>) {
        val a = JSONArray(); l.takeLast(200).forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("format", it.format).put("at", it.at)) }
        p.edit().putString("files", a.toString()).apply()
    }
    fun addFile(f: SavedFile) = saveFiles(files() + f)
    fun removeFile(id: Long) = saveFiles(files().filter { it.id != id })

    // ---- reminders ----
    fun reminders(): List<Reminder> {
        val a = JSONArray(p.getString("reminders", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Reminder(o.getInt("id"), o.getLong("at"), o.optString("text"), o.optString("repeat", "")) }
    }
    private fun saveReminders(list: List<Reminder>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("repeat", it.repeat)) }
        p.edit().putString("reminders", a.toString()).apply()
    }
    fun addReminder(at: Long, text: String, repeat: String = ""): Reminder {
        val id = p.getInt("nextId", 1); p.edit().putInt("nextId", id + 1).apply()
        val r = Reminder(id, at, text, repeat); saveReminders(reminders() + r); return r
    }
    fun updateReminder(r: Reminder) { saveReminders(reminders().map { if (it.id == r.id) r else it }) }
    fun reminder(id: Int): Reminder? = reminders().firstOrNull { it.id == id }
    fun removeReminder(id: Int) { saveReminders(reminders().filter { it.id != id }) }
}

/**
 * The list of checked messages, only on this phone, for 7 days. Each day has its own small file, and a new or
 * changed message is just one line added at the end: cheap for the battery even with many messages a day.
 */
object MsgLog {
    private const val DAYS = 7L
    private const val MAX = 600
    private val lock = Any()
    @Volatile private var pruned = ""
    private var cache: List<JSONObject>? = null

    private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "msglog").apply { mkdirs() }
    private fun dayOf(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    fun add(ctx: Context, o: JSONObject) = synchronized(lock) {
        try {
            java.io.File(dir(ctx), dayOf(System.currentTimeMillis()) + ".jsonl").appendText(o.toString() + "\n")
            pruneOnceADay(ctx)
        } catch (e: Exception) { }
        cache = null
    }

    fun remove(ctx: Context, id: String) = add(ctx, JSONObject().put("id", id).put("removed", true).put("at", System.currentTimeMillis()))

    fun clear(ctx: Context) = synchronized(lock) { dir(ctx).listFiles()?.forEach { it.delete() }; cache = null }

    /** The newest version of each message of the last 7 days, oldest first. Read from the files once, then kept in memory until something changes. */
    fun all(ctx: Context): List<JSONObject> = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - DAYS * 86_400_000L
        val list = cache ?: read(ctx).also { cache = it }
        list.filter { it.optLong("at") >= cutoff }.takeLast(MAX)
    }

    private fun read(ctx: Context): List<JSONObject> {
        val keep = LinkedHashMap<String, JSONObject>()
        dir(ctx).listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.forEach { f ->
            try {
                f.forEachLine { line ->
                    val o = try { JSONObject(line) } catch (e: Exception) { null } ?: return@forEachLine
                    val id = o.optString("id"); if (id.isEmpty()) return@forEachLine
                    keep.remove(id)
                    if (!o.optBoolean("removed")) keep[id] = o
                }
            } catch (e: Exception) { }
        }
        return keep.values.toList()
    }

    private fun pruneOnceADay(ctx: Context) {
        val today = dayOf(System.currentTimeMillis())
        if (pruned == today) return
        pruned = today
        val oldest = java.time.LocalDate.now().minusDays(DAYS).toString()
        dir(ctx).listFiles()?.forEach { if (it.name.removeSuffix(".jsonl") < oldest) it.delete() }
    }
}
