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
 * BigD Chat on the phone: sets it up (this phone's key goes to the server), keeps the chats up to date, sends messages,
 * photos, voice notes and tasks, and shows a notification for new ones.
 *  - While the app (or the floating pet) is open, it waits on the server, which answers as soon as something comes.
 *  - When the app is closed, it checks every 15 minutes (kind to the battery). Instant push comes with the calls.
 * Everything is locked and opened on the phone (ChatCrypto); the server only passes locked messages on.
 */
object ChatSync {
    const val CHANNEL = "bigd_chat"
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    /** The chat on screen right now (no notification for it, and its messages count as read). */
    @Volatile var openCid: String? = null
    private val holders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var loop: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val sendPool = Executors.newSingleThreadExecutor()
    private val filePool = Executors.newFixedThreadPool(2)

    fun changed() { main.post { listeners.forEach { try { it() } catch (e: Exception) { } } } }
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
        if (!server.startsWith("https://")) return Reply(0, JSONObject().put("error", "Set your server address in the BigD AI app first."))
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
        if (!r.ok) return r.error("Couldn't start BigD Chat.")
        val db = ChatDb.get(ctx)
        db.setKv("me", r.json.getJSONObject("me").toString())
        if (db.kv("cursor").isEmpty()) db.setKv("cursor", r.json.optLong("cursor").toString())
        val l = api(store, "GET", "/api/chat/convs")
        if (l.ok) l.json.optJSONArray("convs")?.let { a -> for (i in 0 until a.length()) db.putConv(a.getJSONObject(i)) }
        db.ensureSelf(if (store.lang == "te") "నా నోట్స్, రిమైండర్లు" else "My notes & reminders")
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
        val r = api(store, "GET", "/api/chat/sync?c=$cursor" + (if (wait) "&wait=1" else ""), timeoutMs = if (wait) 45_000 else 20_000)
        if (r.code == 409 && r.json.optBoolean("needKey")) { setup(ctx, store); return false }
        if (!r.ok) return false
        process(ctx, store, r.json)
        val next = r.json.optLong("cursor", cursor)
        db.setKv("cursor", next.toString())
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
        val fresh = ArrayList<ChatMsg>()
        j.optJSONArray("msgs")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val cid = m.optString("cid"); val id = m.optString("id")
                if (db.has(cid, id)) { if (m.optString("from") == me) db.setSent(cid, id, m.optLong("seq"), m.optLong("at"), 1); continue }
                val cm = decode(m, priv)
                db.putMsg(cm)
                if (cm.kind == "task") applyTask(ctx, cm)
                val mine = cm.sender == me
                db.touch(cid, cm.at, preview(ctx, cm), !mine && cid != openCid && cm.kind != "sys")
                if (!mine && cm.kind != "sys") fresh.add(cm)
                if (cm.kind == "photo" || cm.kind == "voice") fetchFile(ctx, store, cm)
            }
        }
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        changed()
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
    fun kindOf(body: JSONObject) = when (body.optString("t")) { "photo" -> "photo"; "voice" -> "voice"; "task" -> "task"; else -> "text" }

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

    // ---------- sending ----------
    private fun newId() = "m" + System.currentTimeMillis().toString(36) + (1000..9999).random()

    /** Sends a message: {t:"text", text} (photos and voice notes: [sendFile]; tasks: [sendTask]). [done] gets an error or null. */
    fun send(ctx: Context, store: Store, cid: String, body: JSONObject, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        val m = ChatMsg(cid, newId(), 0, myUid(ctx), System.currentTimeMillis(), kindOf(body), body, if (cid == ChatDb.SELF) 1 else 0)
        db.putMsg(m); db.touch(cid, m.at, preview(ctx, m), false); changed()
        if (cid == ChatDb.SELF) { done(null); return }
        sendPool.execute { val e = deliver(ctx, store, m, null); changed(); main.post { done(e) } }
    }

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

    /** A photo (JPEG) or a voice note (m4a): locked on the phone, then uploaded, then sent. */
    fun sendFile(ctx: Context, store: Store, cid: String, kind: String, bytes: ByteArray, body: JSONObject, done: (String?) -> Unit = {}) {
        val db = ChatDb.get(ctx)
        val id = newId()
        val local = File(dir(ctx), id + if (kind == "photo") ".jpg" else ".m4a").also { it.writeBytes(bytes) }
        body.put("t", kind).put("local", local.absolutePath)
        val m = ChatMsg(cid, id, 0, myUid(ctx), System.currentTimeMillis(), kind, body, if (cid == ChatDb.SELF) 1 else 0)
        db.putMsg(m); db.touch(cid, m.at, preview(ctx, m), false); changed()
        if (cid == ChatDb.SELF) { done(null); return }
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
        val payload = JSONObject(m.body.toString()).apply { remove("local"); remove("server") }.toString().toByteArray(Charsets.UTF_8)
        for (attempt in 0..1) {
            val s = ChatCrypto.seal(payload, keys)
            val env = JSONObject().put("v", 1).put("epk", s.epk).put("iv", s.iv).put("ct", s.ct).put("keys", JSONObject(s.keys)).put("kid", JSONObject(s.kids))
            val req = JSONObject().put("cid", m.cid).put("id", m.id).put("type", if (task != null) "task" else "msg").put("env", env)
            if (task != null) req.put("task", task)
            val r = api(store, "POST", "/api/chat/send", req.toString())
            if (r.ok) {
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
                val e = if ((m.kind == "photo" || m.kind == "voice") && m.body.optString("file").isEmpty() && local.isNotEmpty())
                    try { uploadAndDeliver(ctx, store, m, File(local).readBytes()) } catch (x: Exception) { "gone" } else deliver(ctx, store, m, null)
                if (e != null && e.startsWith("No connection")) break
            }
            changed()
        }
    }

    // ---------- photos and voice notes that came in ----------
    fun dir(ctx: Context) = File(ctx.filesDir, "chat").also { it.mkdirs() }
    private val fetching: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun fetchFile(ctx: Context, store: Store, m: ChatMsg) {
        if (m.body.optString("local").let { it.isNotEmpty() && File(it).exists() }) return
        val id = m.body.optString("file"); if (id.isEmpty() || !fetching.add(id)) return
        filePool.execute {
            val locked = try { download(store, id) } finally { fetching.remove(id) } ?: return@execute
            val bytes = ChatCrypto.openFile(locked, m.body.optString("key"), m.body.optString("iv")) ?: return@execute
            val f = File(dir(ctx), m.id + if (m.kind == "photo") ".jpg" else ".m4a").also { it.writeBytes(bytes) }
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
            "photo" -> "📷 " + b.optString("text").ifBlank { if (te) "ఫోటో" else "Photo" }
            "voice" -> "🎤 " + (if (te) "వాయిస్ మెసేజ్" else "Voice note")
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
            else -> ""
        }
    }

    private fun notify(ctx: Context, list: List<ChatMsg>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(ctx)
        val db = ChatDb.get(ctx)
        for ((cid, ms) in list.groupBy { it.cid }) {
            if (cid == openCid) continue
            val conv = db.conv(cid) ?: continue
            if (conv.muted) continue
            val last = ms.last()
            val te = Store(ctx).lang == "te"
            val title = when {
                !conv.accepted -> (if (te) "కొత్త చాట్ రిక్వెస్ట్: " else "New chat request: ") + conv.name
                conv.isGroup -> conv.nameOf(last.sender) + " @ " + conv.name
                else -> conv.name
            }
            val text = (if (ms.size > 1) "(${ms.size}) " else "") + preview(ctx, last)
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "BigD Chat", NotificationManager.IMPORTANCE_HIGH).apply { description = "New messages from your BigD chats" })
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
