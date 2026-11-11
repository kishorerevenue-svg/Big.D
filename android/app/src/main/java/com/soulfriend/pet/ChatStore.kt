package com.soulfriend.pet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This phone's chat key pair. The private half never leaves the phone: it is kept locked with a key from the phone's
 *  own key store (Android Keystore), so even someone copying the app's files can't use it. */
object ChatKeys {
    private const val ALIAS = "bigd_chat_lock"
    @Volatile private var cached: Pair<PrivateKey, String>? = null

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    /** (my private key, my public key in base64). Made the first time. */
    fun mine(ctx: Context): Pair<PrivateKey, String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = ctx.getSharedPreferences("bigd_chat", Context.MODE_PRIVATE)
            val pub = p.getString("pub", "") ?: ""
            val locked = p.getString("priv", "") ?: ""
            if (pub.isNotEmpty() && locked.contains(":")) {
                try {
                    val (iv, ct) = locked.split(":")
                    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, ChatCrypto.unb64(iv))) }
                    val k = ChatCrypto.priv(c.doFinal(ChatCrypto.unb64(ct)))
                    return Pair(k, pub).also { cached = it }
                } catch (e: Exception) { }   // (the phone's key store was reset: make a new pair below)
            }
            val kp = ChatCrypto.newKeyPair()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
            val ct = c.doFinal(kp.private.encoded)
            val pb = ChatCrypto.pubB64(kp.public)
            p.edit().putString("pub", pb).putString("priv", ChatCrypto.b64(c.iv) + ":" + ChatCrypto.b64(ct)).apply()
            return Pair(kp.private, pb).also { cached = it }
        }
    }
}

/** One chat as the phone keeps it. [data] is the server's view (members, admins, ticks…) plus our own fields. */
class ChatConv(val cid: String, val data: JSONObject, val last: Long, val unread: Int, val preview: String, val muted: Boolean) {
    val kind get() = data.optString("kind", "dm")
    val name get() = data.optString("name", "Habit friend")
    val emoji get() = data.optString("emoji", "🐾")
    val accepted get() = data.optBoolean("accepted", true)
    val blocked get() = data.optBoolean("blocked", false)
    val member get() = data.optBoolean("member", true)
    val isGroup get() = kind == "group"
    val isSelf get() = kind == "self"
    fun members(): List<JSONObject> { val a = data.optJSONArray("members") ?: JSONArray(); return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    fun isAdmin(uid: String) = kind == "dm" || kind == "self" || (data.optJSONArray("admins")?.let { a -> (0 until a.length()).any { a.optString(it) == uid } } ?: false)
    fun nameOf(uid: String): String = members().firstOrNull { it.optString("uid") == uid }?.optString("name") ?: "Someone"
    /** ✓ sent, ✓✓ delivered to everyone, ✓✓ (blue) read by everyone: 1, 2 or 3 (0 = not sent yet). */
    fun ticks(seq: Long, me: String): Int {
        if (seq <= 0) return 0
        val st = data.optJSONObject("st") ?: return 1
        val others = members().map { it.optString("uid") }.filter { it != me }
        if (others.isEmpty()) return 1
        val d = others.all { (st.optJSONObject(it)?.optLong("d") ?: 0) >= seq }
        val r = others.all { (st.optJSONObject(it)?.optLong("r") ?: 0) >= seq }
        return if (r) 3 else if (d) 2 else 1
    }
}

/** One message. kind: text, photo, voice, task, sys, locked. [body] is the opened message. state: 0 sending, 1 sent, -1 failed. */
class ChatMsg(val cid: String, val id: String, val seq: Long, val sender: String, val at: Long, val kind: String, val body: JSONObject, val state: Int)

/** The chats, messages and tasks on this phone (they stay here; the server deletes them once delivered). */
class ChatDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "bigd_chat.db", null, 1) {
    companion object {
        @Volatile private var one: ChatDb? = null
        fun get(ctx: Context): ChatDb = one ?: synchronized(this) { one ?: ChatDb(ctx.applicationContext).also { one = it } }
        const val SELF = "self"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE convs (cid TEXT PRIMARY KEY, data TEXT, last INTEGER, unread INTEGER DEFAULT 0, preview TEXT DEFAULT '', muted INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE msgs (cid TEXT, id TEXT, seq INTEGER, sender TEXT, at INTEGER, kind TEXT, body TEXT, state INTEGER, PRIMARY KEY(cid, id))")
        db.execSQL("CREATE INDEX msgs_at ON msgs(cid, at)")
        db.execSQL("CREATE TABLE tasks (cid TEXT, tid TEXT, data TEXT, state TEXT, at INTEGER, PRIMARY KEY(cid, tid))")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { }

    // ---------- small settings ----------
    fun kv(k: String): String = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
    fun setKv(k: String, v: String) { writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE) }

    // ---------- chats ----------
    private fun conv(c: android.database.Cursor) = ChatConv(c.getString(0), try { JSONObject(c.getString(1)) } catch (e: Exception) { JSONObject() }, c.getLong(2), c.getInt(3), c.getString(4) ?: "", c.getInt(5) == 1)
    fun convs(): List<ChatConv> = readableDatabase.rawQuery("SELECT cid, data, last, unread, preview, muted FROM convs WHERE hidden=0 ORDER BY last DESC", null).use { c ->
        val out = ArrayList<ChatConv>(); while (c.moveToNext()) out.add(conv(c)); out }
    fun conv(cid: String): ChatConv? = readableDatabase.rawQuery("SELECT cid, data, last, unread, preview, muted FROM convs WHERE cid=?", arrayOf(cid)).use { if (it.moveToFirst()) conv(it) else null }
    /** Saves the server's view of a chat (keeps our own unread count, preview and mute). */
    fun putConv(data: JSONObject) {
        val cid = data.optString("cid"); if (cid.isEmpty()) return
        val old = conv(cid)
        val cv = ContentValues().apply { put("cid", cid); put("data", data.toString()); put("last", maxOf(old?.last ?: 0L, data.optLong("last", data.optLong("at", 0)))) }
        if (old == null) writableDatabase.insert("convs", null, cv) else writableDatabase.update("convs", cv, "cid=?", arrayOf(cid))
    }
    fun patchConv(cid: String, f: (JSONObject) -> Unit) { val c = conv(cid) ?: return; f(c.data); writableDatabase.update("convs", ContentValues().apply { put("data", c.data.toString()) }, "cid=?", arrayOf(cid)) }
    fun touch(cid: String, at: Long, preview: String, unread: Boolean) {
        writableDatabase.execSQL("UPDATE convs SET last=MAX(last, ?), preview=?, unread=unread+?, hidden=0 WHERE cid=?", arrayOf(at, preview.take(120), if (unread) 1 else 0, cid))
    }
    fun readAll(cid: String) { writableDatabase.execSQL("UPDATE convs SET unread=0 WHERE cid=?", arrayOf(cid)) }
    fun setMuted(cid: String, on: Boolean) { writableDatabase.execSQL("UPDATE convs SET muted=? WHERE cid=?", arrayOf(if (on) 1 else 0, cid)) }
    /** Clears a chat on this phone (its messages and tasks; a group you left goes away too). */
    fun clear(cid: String, hide: Boolean) {
        writableDatabase.delete("msgs", "cid=?", arrayOf(cid)); writableDatabase.delete("tasks", "cid=?", arrayOf(cid))
        writableDatabase.execSQL("UPDATE convs SET preview='', unread=0, hidden=? WHERE cid=?", arrayOf(if (hide) 1 else 0, cid))
    }
    fun unreadTotal(): Int = readableDatabase.rawQuery("SELECT SUM(unread) FROM convs WHERE hidden=0 AND muted=0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    /** My own notes and reminders: a chat only on this phone. */
    fun ensureSelf(name: String) {
        if (conv(SELF) != null) return
        putConv(JSONObject().put("cid", SELF).put("kind", "self").put("name", name).put("emoji", "📌").put("accepted", true).put("member", true).put("at", 1L).put("last", 1L))
    }

    // ---------- messages ----------
    private fun msg(c: android.database.Cursor) = ChatMsg(c.getString(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getLong(4), c.getString(5) ?: "text",
        try { JSONObject(c.getString(6) ?: "{}") } catch (e: Exception) { JSONObject() }, c.getInt(7))
    fun msgs(cid: String, limit: Int = 400): List<ChatMsg> = readableDatabase.rawQuery(
        "SELECT * FROM (SELECT cid, id, seq, sender, at, kind, body, state FROM msgs WHERE cid=? ORDER BY at DESC LIMIT ?) ORDER BY at ASC", arrayOf(cid, limit.toString())).use { c ->
        val out = ArrayList<ChatMsg>(); while (c.moveToNext()) out.add(msg(c)); out }
    fun has(cid: String, id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM msgs WHERE cid=? AND id=?", arrayOf(cid, id)).use { it.moveToFirst() }
    fun putMsg(m: ChatMsg) {
        writableDatabase.insertWithOnConflict("msgs", null, ContentValues().apply {
            put("cid", m.cid); put("id", m.id); put("seq", m.seq); put("sender", m.sender); put("at", m.at); put("kind", m.kind); put("body", m.body.toString()); put("state", m.state)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun setSent(cid: String, id: String, seq: Long, at: Long, state: Int) {
        writableDatabase.execSQL("UPDATE msgs SET seq=?, state=?, at=CASE WHEN ?>0 THEN ? ELSE at END WHERE cid=? AND id=?", arrayOf(seq, state, at, at, cid, id))
    }
    fun setBody(cid: String, id: String, body: JSONObject) { writableDatabase.execSQL("UPDATE msgs SET body=? WHERE cid=? AND id=?", arrayOf(body.toString(), cid, id)) }
    fun deleteMsg(cid: String, id: String) { writableDatabase.delete("msgs", "cid=? AND id=?", arrayOf(cid, id)) }
    fun msg(cid: String, id: String): ChatMsg? = readableDatabase.rawQuery("SELECT cid, id, seq, sender, at, kind, body, state FROM msgs WHERE cid=? AND id=?", arrayOf(cid, id)).use { c -> if (c.moveToFirst()) msg(c) else null }
    /** The newest message that shows in the chat (not the hidden "delete for everyone" notes). */
    fun lastMsg(cid: String): ChatMsg? = readableDatabase.rawQuery("SELECT cid, id, seq, sender, at, kind, body, state FROM msgs WHERE cid=? AND kind<>'del' ORDER BY at DESC LIMIT 1", arrayOf(cid)).use { c -> if (c.moveToFirst()) msg(c) else null }
    /** "Delete for everyone": the message stays as "This message was deleted". */
    fun gone(cid: String, id: String) { writableDatabase.execSQL("UPDATE msgs SET kind='gone', body='{\"t\":\"gone\"}' WHERE cid=? AND id=?", arrayOf(cid, id)) }
    fun pending(): List<ChatMsg> = readableDatabase.rawQuery("SELECT cid, id, seq, sender, at, kind, body, state FROM msgs WHERE state=0 ORDER BY at ASC", null).use { c ->
        val out = ArrayList<ChatMsg>(); while (c.moveToNext()) out.add(msg(c)); out }

    // ---------- tasks and reminders (pinned at the top of a chat until closed) ----------
    fun tasks(cid: String, open: Boolean = true): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT data FROM tasks WHERE cid=? ${if (open) "AND state<>'closed'" else ""} ORDER BY at ASC", arrayOf(cid)).use { c ->
        val out = ArrayList<JSONObject>(); while (c.moveToNext()) try { out.add(JSONObject(c.getString(0))) } catch (e: Exception) { }; out }
    fun task(cid: String, tid: String): JSONObject? = readableDatabase.rawQuery("SELECT data FROM tasks WHERE cid=? AND tid=?", arrayOf(cid, tid)).use { if (it.moveToFirst()) try { JSONObject(it.getString(0)) } catch (e: Exception) { null } else null }
    fun putTask(cid: String, t: JSONObject) {
        writableDatabase.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("cid", cid); put("tid", t.optString("tid")); put("data", t.toString()); put("state", t.optString("state", "open")); put("at", t.optLong("at"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
