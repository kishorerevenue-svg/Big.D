package com.soulfriend.pet

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Habit Chat on the phone: sets it up (this phone's key goes to the server), keeps the chats up to date, sends messages,
 * photos, voice notes and tasks, and shows a notification for new ones.
 *  - While the app (or the floating pet) is open, it waits on the server, which answers as soon as something comes.
 *  - When the app is closed, it checks every 15 minutes (kind to the battery). Instant push (Firebase) comes later.
 *  - Also videos, documents, replies, forwards, "delete for everyone", and the notes of voice and video calls (CallManager).
 *  - Like WhatsApp: stickers, GIFs, polls, events, locations, contact cards, link previews (made on the sender's phone),
 *    and small hidden notes that change other messages: reactions, poll votes, event answers, edits, pins, "opened"
 *    (view once). Disappearing messages, "typing…", and two chats only on this phone: 🐾 Habit AI and 📢 broadcast lists.
 * Everything is locked and opened on the phone (ChatCrypto); the server only passes locked messages on.
 */
object ChatSync {
    const val CHANNEL = "bigd_chat"
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    /** The chat on screen right now (no notification for it, and its messages count as read). */
    @Volatile var openCid: String? = null
    private val holders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Holders that mean "the app is on the screen" (for online / last seen; the floating pet alone doesn't count). */
    private val FG = setOf("main", "chat", "info", "status", "call")
    /** Hidden control notes: they change another message and never show themselves. */
    val CTL = ChatDb.HIDDEN
    @Volatile private var lastExpire = 0L
    @Volatile private var loop: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val sendPool = Executors.newSingleThreadExecutor()
    private val filePool = Executors.newFixedThreadPool(2)

    fun changed() { main.post { listeners.forEach { try { it() } catch (e: Exception) { } } } }
    /** Only "typing…" changed (cheaper than redrawing a whole chat). */
    val typingListeners = CopyOnWriteArraySet<() -> Unit>()
    fun typingChanged() { main.post { typingListeners.forEach { try { it() } catch (e: Exception) { } } } }
    fun isSetUp(ctx: Context) = ChatDb.get(ctx).kv("ready") == "1"
    fun me(ctx: Context): JSONObject = ChatDb.get(ctx).kv("me").let { try { JSONObject(it) } catch (e: Exception) { JSONObject() } }
    fun myUid(ctx: Context): String = me(ctx).optString("uid")

    // ---------- talking to the server ----------
    class Reply(val code: Int, val json: JSONObject) {
        val ok get() = code in 200..299
        fun error(fallback: String) = json.optString("error", "").ifBlank { if (code == 0) "No connection. Check your internet." else fallback }
    }
    fun api(store: Store, method: String, path: String, body: String? = null, timeoutMs: Int = 30_000, raw: ByteArray? = null): Reply {
        val server = store.server
        if (!server.startsWith("https://")) return Reply(0, JSONObject().put("error", "Set your server address in the Habit AI app first."))
        return try {
            val c = URL(server + path).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = timeoutMs
            c.setRequestProperty("X-Device", store.deviceId)
            if (body != null || raw != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (raw != null) "application/octet-stream" else "application/json; charset=utf-8")
                val bytes = raw ?: body!!.toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            Reply(code, try { JSONObject(text) } catch (e: Exception) { JSONObject() })
        } catch (e: Exception) { Reply(0, JSONObject().put("error", "No connection. Check your internet.")) }
    }
    /** A locked file from the server (status photos and videos). */
    fun downloadLocked(store: Store, id: String): ByteArray? = if (id.isEmpty()) null else download(store, id)
    private fun download(store: Store, id: String): ByteArray? = try {
        val c = URL(store.server + "/api/chat/file?id=" + id).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.setRequestProperty("X-Device", store.deviceId)
        val b = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        c.disconnect(); b
    } catch (e: Exception) { null }

    // ---------- setting up ----------
    /** Sends this phone's key to the server. Returns null when ready, "profile" when a name is needed first, or an error. */
    fun setup(ctx: Context, store: Store): String? {
        val (_, pub) = ChatKeys.mine(ctx)
        val r = api(store, "POST", "/api/chat/hello", JSONObject().put("pub", pub).toString())
        if (r.json.optBoolean("needProfile")) return "profile"
        if (!r.ok) return r.error("Couldn't start Habit Chat.")
        val db = ChatDb.get(ctx)
        db.setKv("me", r.json.getJSONObject("me").toString())
        if (db.kv("cursor").isEmpty()) db.setKv("cursor", r.json.optLong("cursor").toString())
        val l = api(store, "GET", "/api/chat/convs")
        if (l.ok) l.json.optJSONArray("convs")?.let { a -> for (i in 0 until a.length()) db.putConv(a.getJSONObject(i)) }
        db.ensureSelf(if (store.lang == "te") "నా నోట్స్, రిమైండర్లు" else "My notes & reminders")
        db.ensureAi()
        db.setKv("ready", "1")
        changed()
        return null
    }

    // ---------- keeping up to date ----------
    fun hold(ctx: Context, who: String) {
        if (!isSetUp(ctx)) return
        holders.add(who)
        synchronized(this) {
            if (loop?.isAlive == true) return
            val app = ctx.applicationContext
            loop = Thread {
                val store = Store(app); var pause = 1000L
                while (holders.isNotEmpty()) {
                    val ok = try { syncOnce(app, store, true) } catch (e: Exception) { false }
                    if (ok) { flushPending(app, store); pause = 1000L }
                    else { try { Thread.sleep(pause) } catch (e: InterruptedException) { }; pause = minOf(pause * 2, 60_000L) }
                }
            }.apply { isDaemon = true; name = "bigd-chat"; start() }
        }
    }
    fun release(who: String) { holders.remove(who) }

    /** One ask to the server ([wait]: until something comes, at most about 25 s). Returns false on no connection. */
    fun syncOnce(ctx: Context, store: Store, wait: Boolean): Boolean {
        val db = ChatDb.get(ctx)
        val cursor = db.kv("cursor").toLongOrNull() ?: 0L
        val fg = holders.any { it in FG }
        val r = api(store, "GET", "/api/chat/sync?c=$cursor" + (if (wait) "&wait=1" else "") + (if (fg) "&fg=1" else ""), timeoutMs = if (wait) 45_000 else 20_000)
        if (r.code == 409 && r.json.optBoolean("needKey")) { setup(ctx, store); return false }
        if (!r.ok) return false
        r.json.optLong("now").takeIf { it > 0 }?.let { CallManager.skew = it - System.currentTimeMillis() }
        process(ctx, store, r.json)
        val next = r.json.optLong("cursor", cursor)
        db.setKv("cursor", next.toString())
        // disappearing messages: the ones past their time go (checked about once a minute)
        if (System.currentTimeMillis() - lastExpire > 60_000) { lastExpire = System.currentTimeMillis(); if (db.expire() > 0) changed() }
        // (the server marks messages delivered when we confirm them, with the next ask)
        if (!wait && next != cursor) api(store, "GET", "/api/chat/sync?c=$next")
        return true
    }

    private fun process(ctx: Context, store: Store, j: JSONObject) {
        val db = ChatDb.get(ctx)
        val me = myUid(ctx)
        val priv = ChatKeys.mine(ctx).first
        j.optJSONArray("convs")?.let { a -> for (i in 0 until a.length()) db.putConv(a.getJSONObject(i)) }
        j.optJSONArray("gone")?.let { a -> for (i in 0 until a.length()) db.patchConv(a.getString(i)) { it.put("member", false) } }
        j.optJSONArray("ticks")?.let { a -> for (i in 0 until a.length()) { val t = a.getJSONObject(i); db.patchConv(t.getString("cid")) { it.put("st", t.getJSONObject("st")) } } }
        val serverNow = j.optLong("now").takeIf { it > 0 } ?: System.currentTimeMillis()
        j.optJSONArray("typing")?.let { a -> for (i in 0 until a.length()) onTyping(a.getJSONObject(i), serverNow) }
        if (j.optBoolean("status")) filePool.execute { try { ChatStatus.refresh(ctx, store) } catch (e: Exception) { } }
        if (j.optBoolean("comms")) { db.setKv("comms:fresh", "0"); }
        val fresh = ArrayList<ChatMsg>()
        j.optJSONArray("msgs")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val cid = m.optString("cid"); val id = m.optString("id")
                if (db.has(cid, id)) { if (m.optString("from") == me) db.setSent(cid, id, m.optLong("seq"), m.optLong("at"), 1); continue }
                val cm = decode(m, priv)
                stopTyping(cid, cm.sender)
                when (cm.kind) {
                    "del" -> { applyDelete(ctx, cm); continue }          // "delete for everyone": nothing new to show
                    "call" -> { CallManager.onSignal(ctx, store, cm); continue }   // ringing, answers, hang-ups (CallManager)
                    "react" -> { applyReact(ctx, cm)?.let { fresh.add(it) }; continue }   // a reaction to my message: a notification
                    "vote", "rsvp", "edit", "pin", "opened" -> { applyCtl(ctx, cm); continue }
                }
                val conv = db.conv(cid)
                // "only admins send messages": anything else from a member is dropped here too (the server refuses it as well)
                if (conv != null && conv.isGroup && conv.onlyAdmins && !conv.isAdmin(cm.sender) && cm.kind !in setOf("task", "sys", "locked")) continue
                if (cm.kind == "timer" && !applyTimer(ctx, cm)) continue
                expiry(ctx, cm)
                db.putMsg(cm)
                if (cm.kind == "task") applyTask(ctx, cm)
                val mine = cm.sender == me
                db.touch(cid, cm.at, preview(ctx, cm), !mine && cid != openCid && cm.kind != "sys")
                if (!mine && cm.kind != "sys") fresh.add(cm)
                if (autoFetch(ctx, cm)) fetchFile(ctx, store, cm)
            }
        }
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        // only "typing…" came: the chats don't need drawing again
        val onlyTyping = listOf("convs", "gone", "ticks", "msgs").all { (j.optJSONArray(it)?.length() ?: 0) == 0 } && !j.optBoolean("comms")
        if (!onlyTyping) changed()
    }

    // ---------- "typing…" ----------
    /** Who is typing where: cid → (uid → (until, "" or "rec")). */
    private val typingNow = ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<Long, String>>>()
    private fun onTyping(t: JSONObject, serverNow: Long) {
        val cid = t.optString("cid"); val u = t.optString("u")
        if (cid.isEmpty() || u.isEmpty()) return
        val age = (serverNow - t.optLong("at")).coerceAtLeast(0)
        if (!t.optBoolean("on", true) || age > 7000) { typingNow[cid]?.remove(u); typingChanged(); return }
        typingNow.getOrPut(cid) { ConcurrentHashMap() }[u] = Pair(System.currentTimeMillis() + 6500 - age, t.optString("what"))
        typingChanged()
        main.postDelayed({ typingChanged(); changed() }, 7000 - age)
    }
    fun stopTyping(cid: String, uid: String) { if (typingNow[cid]?.remove(uid) != null) typingChanged() }
    /** (uid, "" or "rec") of the people typing (or recording a voice note) in this chat now. */
    fun typingIn(cid: String): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        return typingNow[cid]?.filter { it.value.first > now }?.map { it.key to it.value.second } ?: emptyList()
    }
    @Volatile private var lastTyping = 0L
    @Volatile private var lastTypingCid = ""
    /** Tells the others I'm typing ([what] "rec": recording a voice note); at most every 4 seconds. */
    fun typing(store: Store, cid: String, what: String = "") {
        val now = System.currentTimeMillis()
        if (cid == lastTypingCid && now - lastTyping < 4000) return
        lastTyping = now; lastTypingCid = cid
        Thread { api(store, "POST", "/api/chat/typing", JSONObject().put("cid", cid).put("what", what).toString(), timeoutMs = 8000) }.start()
    }
    /** Online / last seen of the other person in a one-to-one chat: {on, at} (or hidden). */
    fun presence(store: Store, uid: String): JSONObject? = api(store, "GET", "/api/chat/presence?uid=$uid", timeoutMs = 10_000).let { if (it.ok) it.json else null }

    // ---------- the hidden notes: reactions, votes, event answers, edits, pins, "opened" ----------
    /** A reaction (or "" to take mine back). Returns a notification line when it's someone reacting to my message. */
    private fun applyReact(ctx: Context, d: ChatMsg): ChatMsg? {
        val db = ChatDb.get(ctx)
        val target = db.msg(d.cid, d.body.optString("id")) ?: return null
        val rx = target.body.optJSONObject("_rx") ?: JSONObject()
        val prev = rx.optJSONObject(d.sender)
        if (prev != null && prev.optLong("at") > d.at) return null           // an older note that came late
        val e = d.body.optString("e").take(16)
        if (e.isEmpty()) rx.remove(d.sender) else rx.put(d.sender, JSONObject().put("e", e).put("at", d.at))
        target.body.put("_rx", rx)
        db.setBody(d.cid, target.id, target.body)
        val me = myUid(ctx)
        if (e.isEmpty() || target.sender != me || d.sender == me) return null
        val te = Store(ctx).lang == "te"
        db.touch(d.cid, d.at, (if (te) "$e స్పందించారు: " else "Reacted $e to: ") + preview(ctx, target).take(60), false)
        return if (ChatTheme.reactNotify(ctx)) ChatMsg(d.cid, d.id, d.seq, d.sender, d.at, "react", JSONObject().put("t", "react").put("e", e).put("p", preview(ctx, target).take(60)), 1) else null
    }
    private fun applyCtl(ctx: Context, d: ChatMsg) {
        val db = ChatDb.get(ctx)
        val id = d.body.optString("id")
        when (d.kind) {
            "vote" -> {
                val t = db.msg(d.cid, id)?.takeIf { it.kind == "poll" } ?: return
                val votes = t.body.optJSONObject("_votes") ?: JSONObject()
                if ((votes.optJSONObject(d.sender)?.optLong("at") ?: 0L) > d.at) return
                val n = t.body.optJSONArray("o")?.length() ?: 0
                val ch = d.body.optJSONArray("ch") ?: JSONArray()
                val list = (0 until ch.length()).map { ch.optInt(it, -1) }.filter { it in 0 until n }.distinct().let { if (t.body.optBoolean("multi")) it else it.take(1) }
                if (list.isEmpty()) votes.remove(d.sender) else votes.put(d.sender, JSONObject().put("ch", JSONArray(list)).put("at", d.at))
                t.body.put("_votes", votes); db.setBody(d.cid, t.id, t.body)
            }
            "rsvp" -> {
                val t = db.msg(d.cid, id)?.takeIf { it.kind == "event" } ?: return
                val rs = t.body.optJSONObject("_rsvp") ?: JSONObject()
                if ((rs.optJSONObject(d.sender)?.optLong("at") ?: 0L) > d.at) return
                val a = d.body.optString("a")
                if (a !in setOf("y", "m", "n")) rs.remove(d.sender) else rs.put(d.sender, JSONObject().put("a", a).put("at", d.at))
                t.body.put("_rsvp", rs); db.setBody(d.cid, t.id, t.body)
            }
            "edit" -> {
                // only the sender, only text, and only within 15 minutes (a little extra for slow clocks)
                val t = db.msg(d.cid, id) ?: return
                if (t.sender != d.sender || t.kind != "text" || d.at - t.at > 20 * 60_000L) return
                t.body.put("text", d.body.optString("text").take(4000)).put("_ed", true)
                t.body.remove("lp")
                db.setBody(d.cid, t.id, t.body)
                if (db.lastMsg(d.cid)?.id == t.id) db.touch(d.cid, 0, preview(ctx, t), false)
            }
            "pin" -> {
                val conv = db.conv(d.cid) ?: return
                if (conv.isGroup && conv.adminsEdit && !conv.isAdmin(d.sender)) return
                val cur = try { JSONObject(db.kv("pinmsg:${d.cid}").ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }
                if (cur.optLong("at") > d.at) return
                if (d.body.optBoolean("on")) db.setKv("pinmsg:${d.cid}", JSONObject().put("id", id).put("by", d.sender).put("at", d.at).put("until", d.body.optLong("until")).toString())
                else if (cur.optString("id") == id) db.setKv("pinmsg:${d.cid}", "")
            }
            "opened" -> {
                val t = db.msg(d.cid, id) ?: return
                if (t.sender == myUid(ctx) && t.body.optBoolean("once")) { t.body.put("_opened", true); db.setBody(d.cid, t.id, t.body) }
            }
        }
    }
    /** Disappearing messages turned on or off (in groups, only admins can when the group says so). */
    private fun applyTimer(ctx: Context, d: ChatMsg): Boolean {
        val db = ChatDb.get(ctx)
        val conv = db.conv(d.cid) ?: return false
        if (conv.isGroup && conv.adminsEdit && !conv.isAdmin(d.sender)) return false
        db.setKv("dis:${d.cid}", d.body.optLong("s").coerceIn(0L, 90L * 86400).toString())
        return true
    }
    /** A message in a chat with disappearing messages on gets its time to go. */
    private fun expiry(ctx: Context, m: ChatMsg) {
        if (m.kind == "task" || m.kind == "sys" || m.kind == "timer") return
        val s = ChatTheme.disappear(ctx, m.cid)
        if (s > 0) m.body.put("_exp", maxOf(m.at, System.currentTimeMillis()) + s * 1000)
    }

    /** Sends a hidden note (it is kept until it goes, so it also goes later without a connection). */
    private fun sendNote(ctx: Context, store: Store, cid: String, body: JSONObject) {
        val db = ChatDb.get(ctx)
        val conv = db.conv(cid) ?: return
        if (conv.isLocal) return
        val note = ChatMsg(cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), kindOf(body), body, 0)
        db.putMsg(note)
        sendPool.execute { deliver(ctx, store, note, null); changed() }
    }
    /** My reaction to a message ("" takes it back). */
    fun react(ctx: Context, store: Store, m: ChatMsg, e: String) {
        val me = myUid(ctx)
        val d = ChatMsg(m.cid, newId(), 0, me, System.currentTimeMillis(), "react", JSONObject().put("t", "react").put("id", m.id).put("e", e), 1)
        applyReact(ctx, d); changed()
        sendNote(ctx, store, m.cid, JSONObject(d.body.toString()))
    }
    /** My vote in a poll (the options' numbers; empty takes it back). */
    fun vote(ctx: Context, store: Store, poll: ChatMsg, choices: List<Int>) {
        val body = JSONObject().put("t", "vote").put("id", poll.id).put("ch", JSONArray(choices))
        applyCtl(ctx, ChatMsg(poll.cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), "vote", body, 1)); changed()
        sendNote(ctx, store, poll.cid, JSONObject(body.toString()))
    }
    /** My answer to an event: "y" going, "m" maybe, "n" can't go. */
    fun rsvp(ctx: Context, store: Store, ev: ChatMsg, a: String) {
        val body = JSONObject().put("t", "rsvp").put("id", ev.id).put("a", a)
        applyCtl(ctx, ChatMsg(ev.cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), "rsvp", body, 1)); changed()
        sendNote(ctx, store, ev.cid, JSONObject(body.toString()))
    }
    /** Edits my text message (within 15 minutes). */
    fun edit(ctx: Context, store: Store, m: ChatMsg, text: String) {
        val body = JSONObject().put("t", "edit").put("id", m.id).put("text", text.take(4000))
        applyCtl(ctx, ChatMsg(m.cid, newId(), 0, m.sender, System.currentTimeMillis(), "edit", body, 1)); changed()
        sendNote(ctx, store, m.cid, JSONObject(body.toString()))
    }
    /** Pins a message at the top of the chat for everyone ([until]: when it comes off), or unpins it. */
    fun pin(ctx: Context, store: Store, m: ChatMsg, on: Boolean, until: Long) {
        val body = JSONObject().put("t", "pin").put("id", m.id).put("on", on).put("until", until)
        val db = ChatDb.get(ctx)
        if (on) db.setKv("pinmsg:${m.cid}", JSONObject().put("id", m.id).put("by", myUid(ctx)).put("at", System.currentTimeMillis()).put("until", until).toString())
        else db.setKv("pinmsg:${m.cid}", "")
        changed()
        sendNote(ctx, store, m.cid, body)
    }
    /** The pinned message of a chat (null when none, or its time is over). */
    fun pinned(ctx: Context, cid: String): ChatMsg? {
        val db = ChatDb.get(ctx)
        val p = try { JSONObject(db.kv("pinmsg:$cid").ifEmpty { return null }) } catch (e: Exception) { return null }
        if (p.optLong("until") in 1 until System.currentTimeMillis()) { db.setKv("pinmsg:$cid", ""); return null }
        return db.msg(cid, p.optString("id"))?.takeIf { it.kind != "gone" }
    }
    /** "Opened" goes back to the sender of a view-once photo. */
    fun opened(ctx: Context, store: Store, m: ChatMsg) = sendNote(ctx, store, m.cid, JSONObject().put("t", "opened").put("id", m.id))
    /** Turns disappearing messages on or off for everyone in the chat (a line shows in the chat). */
    fun setTimer(ctx: Context, store: Store, cid: String, secs: Long, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        db.setKv("dis:$cid", secs.toString())
        send(ctx, store, cid, JSONObject().put("t", "timer").put("s", secs), done)
    }
    /** ⭐ Star / unstar (only on this phone). */
    fun star(ctx: Context, m: ChatMsg, on: Boolean) {
        if (on) m.body.put("_star", true) else m.body.remove("_star")
        ChatDb.get(ctx).setBody(m.cid, m.id, m.body); changed()
    }

    private fun decode(m: JSONObject, priv: PrivateKey): ChatMsg {
        val cid = m.optString("cid"); val id = m.optString("id"); val seq = m.optLong("seq"); val from = m.optString("from"); val at = m.optLong("at")
        if (m.optString("type") == "sys") return ChatMsg(cid, id, seq, from, at, "sys", m.optJSONObject("sys") ?: JSONObject(), 1)
        val env = m.optJSONObject("env")
        val plain = env?.takeIf { it.optString("key").isNotEmpty() }?.let { ChatCrypto.open(it.optString("epk"), it.optString("iv"), it.optString("ct"), it.optString("key"), priv) }
        val body = plain?.let { try { JSONObject(String(it, Charsets.UTF_8)) } catch (e: Exception) { null } }
            ?: return ChatMsg(cid, id, seq, from, at, "locked", JSONObject(), 1)
        m.optJSONObject("task")?.let { body.put("server", it) }      // who and what state, as the server says
        return ChatMsg(cid, id, seq, from, at, kindOf(body), body, 1)
    }
    private val KINDS = setOf("photo", "voice", "video", "doc", "task", "del", "gone", "call", "clog", "sticker", "gif", "poll", "event", "loc", "contact",
        "react", "vote", "rsvp", "edit", "pin", "timer", "opened")
    fun kindOf(body: JSONObject) = body.optString("t").let { if (it in KINDS) it else "text" }
    /** Kinds that carry a locked file on the server (a sticker only when it's a person's own, not one of Habit's packs). */
    val FILE_KINDS = setOf("photo", "voice", "video", "doc", "sticker", "gif")
    fun hasFile(m: ChatMsg) = m.kind in FILE_KINDS && m.body.optString("pack").isEmpty()
    /** The file name on the phone for a message's file (documents keep their own ending, like .pdf). */
    fun fileName(m: ChatMsg): String = m.id + when (m.kind) {
        "photo" -> ".jpg"; "voice" -> ".m4a"; "video" -> ".mp4"; "sticker" -> ".png"; "gif" -> ".gif"
        else -> m.body.optString("name").substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(6).let { if (it.isEmpty()) ".bin" else ".$it" }
    }
    /** Photos, voice notes, stickers and GIFs come by themselves; videos and documents as chosen in Chat settings (small ones always, unless "only when I tap"). */
    private fun autoFetch(ctx: Context, m: ChatMsg): Boolean {
        if (!hasFile(m)) return false
        if (m.kind == "photo" || m.kind == "voice" || m.kind == "sticker" || m.kind == "gif") return true
        if (m.kind !in FILE_KINDS) return false
        return when (ChatTheme.autoDl(ctx)) {
            "never" -> false
            "always" -> true
            else -> m.body.optLong("size") in 1..5_000_000L || ChatTheme.onWifi(ctx)
        }
    }

    /** A task message changes the task pinned at the top of the chat. */
    fun applyTask(ctx: Context, m: ChatMsg) {
        val db = ChatDb.get(ctx)
        val b = m.body; val tid = b.optString("tid"); if (tid.isEmpty()) return
        val t = db.task(m.cid, tid) ?: JSONObject().put("tid", tid).put("at", m.at).put("remarks", JSONArray()).put("state", "open")
        val te = Store(ctx).lang == "te"
        when (b.optString("action")) {
            "new" -> {
                t.put("title", b.optString("title")).put("note", b.optString("note")).put("by", m.sender).put("to", b.optString("to"))
                    .put("due", b.optLong("due")).put("state", "open").put("at", m.at)
                // a reminder on this phone when it's mine to do (or my own reminder)
                val due = b.optLong("due"); val me = myUid(ctx)
                if (due > System.currentTimeMillis() && !t.optBoolean("reminded") && (b.optString("to") == me || m.cid == ChatDb.SELF || (b.optString("to").isEmpty() && m.sender == me))) {
                    try { Reminders.add(ctx, due, "📌 " + b.optString("title")); t.put("reminded", true) } catch (e: Exception) { }
                }
            }
            "remark" -> t.optJSONArray("remarks")?.put(JSONObject().put("by", m.sender).put("text", b.optString("text")).put("at", m.at))
            "done" -> { t.put("state", "done"); t.optJSONArray("remarks")?.put(JSONObject().put("by", m.sender).put("text", (if (te) "✔ పని పూర్తయింది" else "✔ Marked done") + b.optString("text").let { if (it.isBlank()) "" else ": $it" }).put("at", m.at)) }
            "close" -> t.put("state", "closed")
            "reopen" -> { t.put("state", "open"); if (b.optString("text").isNotBlank()) t.optJSONArray("remarks")?.put(JSONObject().put("by", m.sender).put("text", "↩ " + b.optString("text")).put("at", m.at)) }
        }
        b.optJSONObject("server")?.optString("state")?.takeIf { it.isNotEmpty() }?.let { t.put("state", it) }
        t.put("last", m.at)
        db.putTask(m.cid, t)
    }

    /** Someone deleted their own message for everyone: it becomes "This message was deleted" here too. */
    private fun applyDelete(ctx: Context, d: ChatMsg) {
        val db = ChatDb.get(ctx)
        val target = db.msg(d.cid, d.body.optString("id")) ?: return
        if (target.sender != d.sender || target.kind == "task" || target.kind == "sys") return     // only the sender can, and not tasks
        if (pinned(ctx, d.cid)?.id == target.id) db.setKv("pinmsg:${d.cid}", "")
        target.body.optString("local").takeIf { it.isNotEmpty() }?.let { try { File(it).delete() } catch (e: Exception) { } }
        db.gone(d.cid, target.id)
        db.lastMsg(d.cid)?.let { if (it.id == target.id) db.touch(d.cid, it.at, preview(ctx, ChatMsg(it.cid, it.id, it.seq, it.sender, it.at, "gone", JSONObject(), 1)), false) }
    }

    /** A call note (ring, answer, hang-up): locked and sent like a message, but not kept in the chat. */
    fun signal(ctx: Context, store: Store, cid: String, body: JSONObject): String? =
        deliver(ctx, store, ChatMsg(cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), "call", body, 0), null)

    /** Deletes my message for everyone in the chat (here at once; on their phones when the note reaches them). */
    fun deleteForAll(ctx: Context, store: Store, m: ChatMsg, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        m.body.optString("local").takeIf { it.isNotEmpty() }?.let { try { File(it).delete() } catch (e: Exception) { } }
        db.gone(m.cid, m.id)
        db.lastMsg(m.cid)?.let { if (it.id == m.id) db.touch(m.cid, it.at, preview(ctx, ChatMsg(it.cid, it.id, it.seq, it.sender, it.at, "gone", JSONObject(), 1)), false) }
        changed()
        // the note itself is kept (hidden) until it's sent, so it goes later if there's no connection now
        val note = ChatMsg(m.cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), "del", JSONObject().put("t", "del").put("id", m.id), 0)
        db.putMsg(note)
        sendPool.execute { val e = deliver(ctx, store, note, null); changed(); main.post { done(e) } }
    }

    // ---------- sending ----------
    private fun newId() = "m" + System.currentTimeMillis().toString(36) + (1000..9999).random()

    /** Sends a message: {t:"text", text} (photos and voice notes: [sendFile]; tasks: [sendTask]). [done] gets an error or null.
     *  In 🐾 Habit AI the AI answers; in a 📢 broadcast list a copy goes to each person's own chat. */
    fun send(ctx: Context, store: Store, cid: String, body: JSONObject, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        val conv = db.conv(cid)
        val local = cid == ChatDb.SELF || conv?.isLocal == true
        val m = ChatMsg(cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), kindOf(body), body, if (local) 1 else 0)
        expiry(ctx, m)
        db.putMsg(m); db.touch(cid, m.at, preview(ctx, m), false); changed()
        if (local) {
            done(null)
            when {
                conv?.isAi == true -> askAi(ctx, store, m)
                conv?.isBcast == true -> { if (m.kind == "text") addPreview(ctx, m, later = false); fanOut(ctx, store, conv, m, null) }
                m.kind == "text" -> filePool.execute { addPreview(ctx, m) }       // my notes: a link gets its preview too
            }
            return
        }
        sendPool.execute {
            if (m.kind == "text") addPreview(ctx, m)
            val e = deliver(ctx, store, m, null); changed(); main.post { done(e) }
        }
    }
    /** A link in a text message gets a preview (title, words and a small picture), made here on the sender's phone. */
    private fun addPreview(ctx: Context, m: ChatMsg, later: Boolean = true) {
        if (m.body.has("lp") || m.body.optBoolean("_nolp") || !ChatTheme.linkPreviews(ctx)) return
        val u = LinkPreview.firstUrl(m.body.optString("text")) ?: return
        val lp = (if (later) LinkPreview.get(u) else LinkPreview.cached(u)) ?: return
        m.body.put("lp", lp); ChatDb.get(ctx).setBody(m.cid, m.id, m.body); changed()
    }
    /** The message as it travels: without what only this phone keeps (its file's place, stars, reactions…). */
    fun cleanBody(b: JSONObject): JSONObject {
        val o = JSONObject(b.toString())
        o.remove("local"); o.remove("server")
        o.keys().asSequence().toList().filter { it.startsWith("_") }.forEach { o.remove(it) }
        return o
    }
    /** 📢 A broadcast list: each person gets the message in their own chat with me, as a normal message. */
    private fun fanOut(ctx: Context, store: Store, list: ChatConv, m: ChatMsg, bytes: ByteArray?) {
        val cids = list.data.optJSONArray("cids") ?: return
        val clean = cleanBody(m.body).apply { remove("re"); for (k in listOf("file", "key", "iv", "size")) remove(k) }
        for (i in 0 until cids.length()) {
            val c = cids.optString(i)
            if (c.isEmpty() || ChatDb.get(ctx).conv(c)?.let { it.member && !it.blocked } != true) continue
            if (bytes != null && hasFile(m)) sendFile(ctx, store, c, m.kind, bytes, JSONObject(clean.toString()))
            else send(ctx, store, c, JSONObject(clean.toString()))
        }
    }

    // ---------- 🐾 Habit AI: a chat with the AI (only on this phone; the AI is the same one the pet uses) ----------
    @Volatile private var brain: Brain? = null
    /** True while Habit AI is thinking (the chat shows "typing…"). */
    @Volatile var aiBusy = false
    private fun askAi(ctx: Context, store: Store, m: ChatMsg) {
        val app = ctx.applicationContext
        val photo = if (m.kind == "photo") try { Pair("image/jpeg", android.util.Base64.encodeToString(File(m.body.optString("local")).readBytes(), android.util.Base64.NO_WRAP)) } catch (e: Exception) { null } else null
        val q = m.body.optString("text").ifBlank { if (photo != null) (if (store.lang == "te") "ఈ ఫోటోలో ఏముంది?" else "What's in this photo?") else "" }
        if (q.isBlank()) return
        aiBusy = true; changed()
        main.post {
            val b = brain ?: Brain(Store(app)).also { brain = it }
            b.ask(q, onReply = { json -> aiBusy = false; aiReply(app, json) }, onError = { err -> aiBusy = false; aiSay(app, "⚠️ $err") }, photo = photo, local = photo == null)
        }
    }
    /** The AI's answer goes into the chat; reminders, to-dos, notes and alarms it was asked for are done on the phone. */
    private fun aiReply(ctx: Context, json: JSONObject) {
        val lines = ArrayList<String>()
        json.optJSONArray("actions")?.let { a ->
            val run = Actions(ctx) { title, text -> lines.add(if (title.isBlank()) text else "$title\n$text") }
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> if (x.optString("type") != "look") run.run(x)?.let { lines.add("✅ $it") } }
        }
        aiSay(ctx, (json.optString("reply", "").ifBlank { "🐾" } + if (lines.isEmpty()) "" else "\n\n" + lines.joinToString("\n")).trim())
    }
    private fun aiSay(ctx: Context, text: String) {
        val db = ChatDb.get(ctx)
        val m = ChatMsg(ChatDb.AI, newId(), 0, "ai", System.currentTimeMillis(), "text", JSONObject().put("t", "text").put("text", text.take(8000)), 1)
        db.putMsg(m); db.touch(ChatDb.AI, m.at, "🐾 " + text.take(80), openCid != ChatDb.AI); changed()
        if (openCid != ChatDb.AI) notify(ctx, listOf(m))
    }
    /** Forgets the AI chat's short memory (when its messages are cleared). */
    fun aiForget() { brain?.clear() }

    /** A task: action "new" (title, note, due, to), "remark" (text), "done", "close" or "reopen". Applied once the server agrees. */
    fun sendTask(ctx: Context, store: Store, cid: String, body: JSONObject, done: (String?) -> Unit = {}) {
        body.put("t", "task")
        val m = ChatMsg(cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), "task", body, 1)
        if (cid == ChatDb.SELF) { ChatDb.get(ctx).putMsg(m); applyTask(ctx, m); ChatDb.get(ctx).touch(cid, m.at, preview(ctx, m), false); changed(); done(null); return }
        val task = JSONObject().put("id", body.optString("tid")).put("action", body.optString("action"))
        if (body.optString("action") == "new") task.put("to", body.optString("to")).put("due", body.optLong("due"))
        sendPool.execute {
            val db = ChatDb.get(ctx)
            db.putMsg(ChatMsg(m.cid, m.id, 0, m.sender, m.at, m.kind, m.body, 0))
            val e = deliver(ctx, store, m, task)
            if (e == null) { applyTask(ctx, ChatMsg(m.cid, m.id, 0, m.sender, m.at, m.kind, m.body, 1)); db.touch(cid, m.at, preview(ctx, m), false) }
            else db.deleteMsg(m.cid, m.id)                                   // not allowed (or no connection): nothing changes
            changed(); main.post { done(e) }
        }
    }

    /** A photo (JPEG), a voice note (m4a), a video (mp4) or a document: locked on the phone, then uploaded, then sent. */
    fun sendFile(ctx: Context, store: Store, cid: String, kind: String, bytes: ByteArray, body: JSONObject, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        val conv = db.conv(cid)
        val isLocal = cid == ChatDb.SELF || conv?.isLocal == true
        val id = newId()
        body.put("t", kind)
        val m0 = ChatMsg(cid, id, 0, myUid(ctx), System.currentTimeMillis(), kind, body, 0)
        val local = File(dir(ctx), fileName(m0)).also { it.writeBytes(bytes) }
        body.put("local", local.absolutePath)
        if (kind == "video" || kind == "doc" || kind == "gif") body.put("size", bytes.size.toLong())
        val m = ChatMsg(cid, id, 0, myUid(ctx), m0.at, kind, body, if (isLocal) 1 else 0)
        expiry(ctx, m)
        db.putMsg(m); db.touch(cid, m.at, preview(ctx, m), false); changed()
        if (isLocal) {
            done(null)
            if (conv?.isAi == true) askAi(ctx, store, m) else if (conv?.isBcast == true) fanOut(ctx, store, conv, m, bytes)
            return
        }
        sendPool.execute { val e = uploadAndDeliver(ctx, store, m, bytes); changed(); main.post { done(e) } }
    }
    private fun uploadAndDeliver(ctx: Context, store: Store, m: ChatMsg, bytes: ByteArray): String? {
        if (m.body.optString("file").isEmpty()) {
            val (locked, key, iv) = ChatCrypto.lockFile(bytes)
            val r = api(store, "POST", "/api/chat/file", raw = locked, timeoutMs = 90_000)
            if (!r.ok) { if (r.code != 0) ChatDb.get(ctx).setSent(m.cid, m.id, 0, 0, -1); return r.error("Couldn't send the file.") }
            m.body.put("file", r.json.optString("id")).put("key", key).put("iv", iv)
            ChatDb.get(ctx).setBody(m.cid, m.id, m.body)
        }
        return deliver(ctx, store, m, null)
    }

    /** Locks and sends one message; if the people (or their keys) changed, locks it again for the right ones. */
    private fun deliver(ctx: Context, store: Store, m: ChatMsg, task: JSONObject?): String? {
        val db = ChatDb.get(ctx)
        var keys = keysFor(ctx, store, m.cid, false) ?: return "No connection. It will be sent when you're back online."
        val payload = cleanBody(m.body).toString().toByteArray(Charsets.UTF_8)
        // hidden notes (reactions, votes…), call notes and the disappearing-messages switch go as "ctl": allowed in
        // "only admins" groups too (the phones check what they really are)
        val type = when { task != null -> "task"; m.kind in CTL || m.kind == "call" || m.kind == "timer" -> "ctl"; else -> "msg" }
        for (attempt in 0..1) {
            val s = ChatCrypto.seal(payload, keys)
            val env = JSONObject().put("v", 1).put("epk", s.epk).put("iv", s.iv).put("ct", s.ct).put("keys", JSONObject(s.keys)).put("kid", JSONObject(s.kids))
            val req = JSONObject().put("cid", m.cid).put("id", m.id).put("type", type).put("env", env)
            if (task != null) req.put("task", task)
            val r = api(store, "POST", "/api/chat/send", req.toString())
            if (r.ok) {
                if (m.kind in CTL) { db.deleteMsg(m.cid, m.id); return null }      // a hidden note has done its job
                db.setSent(m.cid, m.id, r.json.optLong("seq"), r.json.optLong("at"), 1)
                if (db.conv(m.cid)?.accepted == false) db.patchConv(m.cid) { it.put("accepted", true) }   // replying accepts a request
                r.json.optJSONObject("task")?.let { m.body.put("server", it); db.setBody(m.cid, m.id, m.body) }
                return null
            }
            if (r.code == 409 && r.json.has("keys")) { keys = parseKeys(r.json.getJSONObject("keys")); db.setKv("keys:" + m.cid, r.json.getJSONObject("keys").toString()); continue }
            if (r.code == 0) return "No connection. It will be sent when you're back online."
            db.setSent(m.cid, m.id, 0, 0, -1)
            return r.error("Couldn't send it.")
        }
        db.setSent(m.cid, m.id, 0, 0, -1)
        return "Couldn't send it. Please try again."
    }
    private fun parseKeys(o: JSONObject): Map<String, String> { val out = HashMap<String, String>(); o.keys().forEach { u -> o.optJSONObject(u)?.optString("pub")?.takeIf { it.isNotEmpty() }?.let { out[u] = it } }; return out }
    private fun keysFor(ctx: Context, store: Store, cid: String, fresh: Boolean): Map<String, String>? {
        val db = ChatDb.get(ctx)
        val cached = db.kv("keys:$cid")
        if (!fresh && cached.isNotEmpty()) return parseKeys(JSONObject(cached))
        val r = api(store, "GET", "/api/chat/keys?cid=$cid")
        if (!r.ok) return null
        db.setKv("keys:$cid", r.json.getJSONObject("keys").toString())
        return parseKeys(r.json.getJSONObject("keys"))
    }
    /** Messages that couldn't go (no connection) go now. */
    fun flushPending(ctx: Context, store: Store) {
        val list = ChatDb.get(ctx).pending().filter { it.cid != ChatDb.SELF && it.kind != "task" && System.currentTimeMillis() - it.at > 3000 }
        if (list.isEmpty()) return
        sendPool.execute {
            for (m in list) {
                val local = m.body.optString("local")
                val e = if (hasFile(m) && m.body.optString("file").isEmpty() && local.isNotEmpty())
                    try { uploadAndDeliver(ctx, store, m, File(local).readBytes()) } catch (x: Exception) { "gone" } else deliver(ctx, store, m, null)
                if (e != null && e.startsWith("No connection")) break
            }
            changed()
        }
    }

    // ---------- photos and voice notes that came in ----------
    fun dir(ctx: Context) = File(ctx.filesDir, "chat").also { it.mkdirs() }
    private val fetching: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun isFetching(m: ChatMsg) = m.body.optString("file").let { it.isNotEmpty() && it in fetching }
    fun fetchFile(ctx: Context, store: Store, m: ChatMsg) {
        if (m.body.optString("local").let { it.isNotEmpty() && File(it).exists() }) return
        val id = m.body.optString("file"); if (id.isEmpty() || !fetching.add(id)) return
        filePool.execute {
            val locked = try { download(store, id) } finally { fetching.remove(id) } ?: return@execute
            val bytes = ChatCrypto.openFile(locked, m.body.optString("key"), m.body.optString("iv")) ?: return@execute
            val f = File(dir(ctx), fileName(m)).also { it.writeBytes(bytes) }
            m.body.put("local", f.absolutePath)
            ChatDb.get(ctx).setBody(m.cid, m.id, m.body)
            changed()
        }
    }

    // ---------- words for lists and notifications ----------
    fun preview(ctx: Context, m: ChatMsg): String {
        val te = Store(ctx).lang == "te"
        val b = m.body
        return when (m.kind) {
            "text" -> b.optString("text")
            "photo" -> if (b.optBoolean("once")) "👁 " + (if (te) "ఒకసారి చూసే ఫోటో" else "View once photo") else "📷 " + b.optString("text").ifBlank { if (te) "ఫోటో" else "Photo" }
            "sticker" -> "🏷️ " + (if (te) "స్టిక్కర్" else "Sticker")
            "gif" -> "🎞️ GIF"
            "poll" -> "📊 " + b.optString("q")
            "event" -> "📅 " + b.optString("title")
            "loc" -> "📍 " + b.optString("name").ifBlank { if (te) "లొకేషన్" else "Location" }
            "contact" -> "👤 " + b.optString("name")
            "timer" -> "⏱ " + (if (b.optLong("s") > 0) (if (te) "కనిపించకుండా పోయే మెసేజ్‌లు: " else "Disappearing messages: ") + ChatTheme.timerText(b.optLong("s"), te) else (if (te) "కనిపించకుండా పోయే మెసేజ్‌లు ఆఫ్" else "Disappearing messages off"))
            "react" -> (if (te) "${b.optString("e")} స్పందించారు: " else "Reacted ${b.optString("e")} to: ") + b.optString("p")
            "voice" -> "🎤 " + (if (te) "వాయిస్ మెసేజ్" else "Voice note")
            "video" -> "🎬 " + b.optString("text").ifBlank { if (te) "వీడియో" else "Video" }
            "doc" -> "📄 " + b.optString("name").ifBlank { if (te) "ఫైల్" else "Document" }
            "gone" -> "🚫 " + (if (te) "ఈ మెసేజ్ తొలగించబడింది" else "This message was deleted")
            "clog" -> CallManager.logText(ctx, m)
            "task" -> when (b.optString("action")) {
                "new" -> "📌 " + b.optString("title")
                "remark" -> "💬 " + b.optString("text")
                "done" -> "✔ " + (if (te) "పని పూర్తయింది" else "Marked done")
                "close" -> "✅ " + (if (te) "పని మూసివేశారు" else "Task closed")
                else -> "↩ " + (if (te) "మళ్ళీ తెరిచారు" else "Task reopened")
            }
            "sys" -> sysText(ctx, m.cid, b)
            else -> "🔒 " + (if (te) "మెసేజ్" else "Message")
        }
    }
    fun sysText(ctx: Context, cid: String, s: JSONObject): String {
        val te = Store(ctx).lang == "te"
        val conv = ChatDb.get(ctx).conv(cid); val me = myUid(ctx)
        fun nm(u: String) = if (u == me) (if (te) "మీరు" else "You") else conv?.nameOf(u) ?: "Someone"
        val who = s.optJSONArray("who")?.let { a -> (0 until a.length()).joinToString(", ") { nm(a.optString(it)) } } ?: ""
        val by = nm(s.optString("by"))
        return when (s.optString("what")) {
            "created" -> if (te) "$by \"${s.optString("name")}\" గ్రూప్ చేశారు" else "$by made the group \"${s.optString("name")}\""
            "added" -> if (te) "$by $who ని చేర్చారు" else "$by added $who"
            "removed" -> if (te) "$by $who ని తీసేశారు" else "$by removed $who"
            "left" -> if (te) "$by వెళ్ళిపోయారు" else "$by left"
            "name" -> if (te) "$by గ్రూప్ పేరు \"${s.optString("name")}\" గా మార్చారు" else "$by renamed the group to \"${s.optString("name")}\""
            "admins" -> if (who.isNotEmpty()) (if (te) "$by $who ని అడ్మిన్ చేశారు" else "$by made $who an admin") else (if (te) "అడ్మిన్లు మారారు" else "Admins changed")
            "desc" -> if (te) "$by గ్రూప్ వివరణ మార్చారు" else "$by changed the group description"
            "emoji" -> if (te) "$by గ్రూప్ చిత్రం ${s.optString("emoji")} గా మార్చారు" else "$by changed the group picture to ${s.optString("emoji")}"
            "only" -> if (s.optBoolean("on")) (if (te) "$by సెట్టింగ్ మార్చారు: అడ్మిన్లు మాత్రమే మెసేజ్ పంపగలరు" else "$by changed this group's settings so only admins can send messages")
                else (if (te) "$by సెట్టింగ్ మార్చారు: అందరూ మెసేజ్ పంపగలరు" else "$by changed this group's settings so all members can send messages")
            "infoedit" -> if (s.optBoolean("on")) (if (te) "$by: గ్రూప్ వివరాలు అడ్మిన్లు మాత్రమే మార్చగలరు" else "$by changed the settings so only admins can edit the group info")
                else (if (te) "$by: గ్రూప్ వివరాలు అందరూ మార్చగలరు" else "$by changed the settings so all members can edit the group info")
            "joined" -> if (s.optBoolean("link")) (if (te) "$who ఆహ్వాన లింక్‌తో చేరారు" else "$who joined using this group's invite link") else (if (te) "$who చేరారు" else "$who joined")
            "linked" -> if (te) "$by ఈ గ్రూప్‌ని \"${s.optString("name")}\" కమ్యూనిటీలో చేర్చారు" else "$by added this group to the community \"${s.optString("name")}\""
            "unlinked" -> if (te) "$by ఈ గ్రూప్‌ని \"${s.optString("name")}\" కమ్యూనిటీ నుంచి తీసేశారు" else "$by removed this group from the community \"${s.optString("name")}\""
            "community" -> if (te) "$by \"${s.optString("name")}\" కమ్యూనిటీ చేశారు. ఇక్కడ అడ్మిన్లు అందరికీ ప్రకటనలు పంపుతారు." else "$by made the community \"${s.optString("name")}\". Admins share announcements with everyone here."
            else -> ""
        }
    }
    /** Does this message mention me (@name, or @everyone)? */
    fun mentionsMe(ctx: Context, m: ChatMsg): Boolean {
        val a = m.body.optJSONArray("mn") ?: return false
        val me = myUid(ctx)
        return (0 until a.length()).any { a.optString(it) == me || a.optString(it) == "*" }
    }

    fun notify(ctx: Context, list: List<ChatMsg>) {
        if (!ChatTheme.notifyOn(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(ctx)
        val db = ChatDb.get(ctx)
        for ((cid, ms) in list.groupBy { it.cid }) {
            if (cid == openCid) continue
            val conv = db.conv(cid) ?: continue
            // a muted chat stays quiet, except when someone mentions me (@name) or replies to me
            if (conv.muted && ms.none { mentionsMe(ctx, it) || it.body.optJSONObject("re")?.optString("who") == myUid(ctx) }) continue
            val last = ms.last()
            val te = Store(ctx).lang == "te"
            val title = when {
                !conv.accepted -> (if (te) "కొత్త చాట్ రిక్వెస్ట్: " else "New chat request: ") + conv.name
                conv.isGroup -> conv.nameOf(last.sender) + " @ " + conv.name
                else -> conv.name
            }
            // hidden chats and "don't show the message" (Chat settings): only "New message"
            val secret = ChatTheme.isHidden(ctx, cid) || !ChatTheme.previewOn(ctx)
            if (ChatTheme.isHidden(ctx, cid)) {
                val te0 = Store(ctx).lang == "te"
                val open0 = PendingIntent.getActivity(ctx, 7302, Intent(ctx, MainActivity::class.java).putExtra("tab", "messages").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val b0 = android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Habit Chat")
                    .setContentText(if (te0) "🔒 కొత్త మెసేజ్" else "🔒 New message").setAutoCancel(true).setContentIntent(open0).setCategory(android.app.Notification.CATEGORY_MESSAGE)
                try { nm.notify("chat", "hidden".hashCode(), b0.build()) } catch (e: SecurityException) { }
                continue
            }
            val text = if (secret) (if (te) "కొత్త మెసేజ్" else "New message") + (if (ms.size > 1) " (${ms.size})" else "") else (if (ms.size > 1) "(${ms.size}) " else "") + preview(ctx, last)
            val open = PendingIntent.getActivity(ctx, cid.hashCode(), Intent(ctx, ChatActivity::class.java).putExtra("cid", cid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open)
                .setCategory(android.app.Notification.CATEGORY_MESSAGE).setWhen(last.at).setShowWhen(true)
            try { nm.notify("chat", cid.hashCode(), b.build()) } catch (e: SecurityException) { }
        }
    }
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Habit Chat", NotificationManager.IMPORTANCE_HIGH).apply { description = "New messages from your Habit chats" })
    }
    fun clearNotification(ctx: Context, cid: String) { try { ctx.getSystemService(NotificationManager::class.java)?.cancel("chat", cid.hashCode()) } catch (e: Exception) { } }
}

/** When the app is closed: a quiet check for new chat messages every 15 minutes. */
class ChatAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ChatSync.isSetUp(ctx)) return
        val pr = goAsync()
        Thread { try { val s = Store(ctx); if (ChatSync.syncOnce(ctx, s, false)) ChatSync.flushPending(ctx, s) } catch (e: Exception) { } finally { pr.finish() } }.start()
    }
    companion object {
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 7301, Intent(ctx, ChatAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 15 * 60_000L, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi)
        }
    }
}
