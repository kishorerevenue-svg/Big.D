package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.interfaces.ECPrivateKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * 💻 Linked devices: Habit Chat on a computer (bigdai.tech/web), like WhatsApp Web.
 *  - The computer shows a QR code: "HABITWEB:<link code>:<its one-time public key>". The phone reads it from a photo.
 *  - After the phone's own lock (fingerprint / PIN), the phone sends the computer its chat key and a copy of recent
 *    chats, locked for that one-time key (ChatCrypto): the server only passes the locked box on and can't open it.
 *  - 🔒 Locked chats, 📌 my notes, 🐾 Habit AI and 📢 broadcast lists stay on this phone. View-once photos too.
 *  - Up to 4 computers. Each can be logged out from here; one that isn't used for 30 days is logged out by itself,
 *    and all of them when this phone gets a new chat lock.
 */
object LinkedDevices {
    class Device(val id: String, val name: String, val at: Long, val seen: Long)

    fun list(store: Store): List<Device>? {
        val r = ChatSync.api(store, "GET", "/api/web/devices", timeoutMs = 15_000)
        if (!r.ok) return null
        val a = r.json.optJSONArray("devices") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Device(it.optString("id"), it.optString("name", "Computer"), it.optLong("at"), it.optLong("seen")) }
    }
    fun logout(store: Store, id: String): ChatSync.Reply = ChatSync.api(store, "POST", "/api/web/logout", JSONObject().put("id", id).toString(), timeoutMs = 15_000)

    // ---------- the computer's QR code ----------
    private val SPKI_P256 = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val THREE = BigInteger.valueOf(3)
    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when { b.size == 32 -> b; b.size > 32 -> b.copyOfRange(b.size - 32, b.size); else -> ByteArray(32 - b.size) + b }
    }
    /** A compressed P-256 point (02/03 + x) made whole again (04 + x + y): y² = x³ - 3x + b. */
    private fun decompress(c: ByteArray): ByteArray? {
        val x = BigInteger(1, c.copyOfRange(1, 33))
        if (x >= P) return null
        val rhs = x.modPow(THREE, P).subtract(x.multiply(THREE)).add(B).mod(P)
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != rhs) return null
        if (y.testBit(0) != (c[0] == 3.toByte())) y = P.subtract(y)
        return byteArrayOf(4) + fixed32(x) + fixed32(y)
    }
    /** (link code, the computer's one-time public key as base64 SPKI), or null when it isn't a Habit computer QR code. */
    fun parse(text: String): Pair<String, String>? {
        val m = Regex("^HABITWEB:([A-Za-z0-9]{8,20}):([A-Za-z0-9_-]{43,90})$").find(text.trim()) ?: return null
        val raw = try { Base64.getUrlDecoder().decode(m.groupValues[2]) } catch (e: Exception) { return null }
        val point = when {
            raw.size == 33 && (raw[0] == 2.toByte() || raw[0] == 3.toByte()) -> decompress(raw) ?: return null
            raw.size == 65 && raw[0] == 4.toByte() -> raw
            else -> return null
        }
        val spki = ChatCrypto.b64(SPKI_P256 + point)
        return try { ChatCrypto.pub(spki); Pair(m.groupValues[1], spki) } catch (e: Exception) { null }   // (a real point on the curve)
    }
    fun isLink(text: String) = text.trim().startsWith("HABITWEB:")

    // ---------- what goes to the computer ----------
    /** A chat key as the parts of a JWK, the form a browser can take it in: (x, y, d) in base64url. */
    fun jwkParts(priv: ECPrivateKey, pubB64: String): Array<String> {
        val spki = ChatCrypto.unb64(pubB64)
        val point = spki.copyOfRange(spki.size - 65, spki.size)
        val u = Base64.getUrlEncoder().withoutPadding()
        return arrayOf(u.encodeToString(point.copyOfRange(1, 33)), u.encodeToString(point.copyOfRange(33, 65)), u.encodeToString(fixed32(priv.s)))
    }
    private fun jwk(ctx: Context): JSONObject {
        val (priv, pubB64) = ChatKeys.mine(ctx)
        val (x, y, d) = jwkParts(priv as ECPrivateKey, pubB64)
        return JSONObject().put("kty", "EC").put("crv", "P-256").put("x", x).put("y", y).put("d", d)
    }
    class Copy(val history: ByteArray, val locked: JSONArray, val upto: JSONObject, val chats: Int, val msgs: Int)
    /** Recent chats for the computer (at most 300 messages a chat): not locked chats, not chats only on this phone. */
    fun copy(ctx: Context): Copy {
        val db = ChatDb.get(ctx)
        val convs = JSONArray(); val msgs = JSONObject(); val tasks = JSONObject(); val locked = JSONArray(); val upto = JSONObject()
        var total = 0; var chats = 0
        for (c in db.convs()) {
            if (c.isLocal) continue
            if (ChatTheme.isHidden(ctx, c.cid)) { locked.put(c.cid); continue }
            val o = JSONObject(c.data.toString()).put("unread", c.unread).put("preview", c.preview).put("last", c.last).put("muted", c.muted)
                .put("pin", db.kv("pin:${c.cid}").toLongOrNull()?.takeIf { it > 1 } ?: 0L).put("fav", db.kv("fav:${c.cid}") == "1")
                .put("unr", db.kv("unr:${c.cid}") == "1").put("dis", ChatTheme.disappear(ctx, c.cid))
            db.kv("wall:${c.cid}").toIntOrNull()?.let { o.put("wall", it) }
            db.kv("pinmsg:${c.cid}").takeIf { it.isNotEmpty() }?.let { try { o.put("pinmsg", JSONObject(it)) } catch (e: Exception) { } }
            convs.put(o); chats++
            val all = db.msgs(c.cid, 300)
            upto.put(c.cid, all.maxOfOrNull { it.seq } ?: 0L)     // (what reached the server after this, the server gives the computer)
            if (total >= 9000) continue
            val list = JSONArray()
            for (m in all) {
                if (m.kind in ChatDb.HIDDEN || m.kind == "call") continue
                val b = JSONObject(m.body.toString())
                b.remove("local")
                if (m.kind == "photo" && b.optBoolean("once")) { b.remove("file"); b.remove("key"); b.remove("iv"); b.remove("thumb") }   // view once: only on a phone
                list.put(JSONObject().put("id", m.id).put("seq", m.seq).put("sender", m.sender).put("at", m.at).put("kind", m.kind).put("body", b).put("state", m.state))
                total++
            }
            if (list.length() > 0) msgs.put(c.cid, list)
            val ts = JSONArray(); db.tasks(c.cid, false).forEach { ts.put(it) }
            if (ts.length() > 0) tasks.put(c.cid, ts)
        }
        val h = JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("convs", convs).put("msgs", msgs).put("tasks", tasks)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(h.toString().toByteArray(Charsets.UTF_8)) }
        return Copy(out.toByteArray(), locked, upto, chats, total)
    }
    private fun settings(ctx: Context): JSONObject {
        val db = ChatDb.get(ctx)
        return JSONObject().put("receipts", ChatTheme.receiptsOn(ctx)).put("preview", ChatTheme.previewOn(ctx)).put("notify", ChatTheme.notifyOn(ctx))
            .put("reactnotify", ChatTheme.reactNotify(ctx)).put("font", db.kv("set:font").ifEmpty { "m" }).put("wall", db.kv("set:wall").toIntOrNull() ?: 0)
    }

    /** Links the computer that showed [qr]. Returns null when done, or what went wrong. (Slow: not on the main thread.) */
    fun link(ctx: Context, store: Store, qr: String, progress: (String) -> Unit): String? {
        val te = store.lang == "te"
        fun t(en: String, tel: String) = if (te) tel else en
        val (code, webPub) = parse(qr) ?: return t("That isn't a Habit Chat computer QR code. Open bigdai.tech/web on the computer.", "అది Habit చాట్ కంప్యూటర్ QR కోడ్ కాదు. కంప్యూటర్‌లో bigdai.tech/web తెరవండి.")
        if (!ChatSync.isSetUp(ctx)) return t("Start Habit Chat on this phone first.", "ముందు ఈ ఫోన్‌లో Habit చాట్ మొదలుపెట్టండి.")
        progress(t("Copying your recent chats…", "మీ ఇటీవలి చాట్‌లు కాపీ చేస్తున్నాం…"))
        val copy = try { copy(ctx) } catch (e: Exception) { return t("Couldn't copy your chats. Please try again.", "చాట్‌లు కాపీ కాలేదు. మళ్ళీ ప్రయత్నించండి.") }
        if (copy.history.size > 30_000_000) return t("Your chats are too big to copy in one go. Clear some old chats and try again.", "చాట్‌లు చాలా పెద్దవి. కొన్ని పాత చాట్‌లు క్లియర్ చేసి మళ్ళీ ప్రయత్నించండి.")
        progress(t("Locking and sending them…", "లాక్ చేసి పంపుతున్నాం…"))
        val (lockedFile, key, iv) = ChatCrypto.lockFile(copy.history)
        val up = ChatSync.api(store, "POST", "/api/chat/file", raw = lockedFile, timeoutMs = 120_000)
        if (!up.ok) return up.error(t("Couldn't send your chats.", "చాట్‌లు పంపలేకపోయాం."))
        val (mode, uids) = ChatStatus.privacy(ctx)
        val me = ChatSync.me(ctx)
        val payload = JSONObject().put("v", 1).put("uid", ChatSync.myUid(ctx)).put("jwk", jwk(ctx)).put("pub", ChatKeys.mine(ctx).second).put("me", me)
            .put("hist", JSONObject().put("file", up.json.optString("id")).put("key", key).put("iv", iv).put("size", copy.history.size))
            .put("locked", copy.locked).put("lang", store.lang).put("pet", store.petKind.ifBlank { "puppy" }).put("set", settings(ctx))
            .put("lists", JSONArray().apply { ChatLists.all(ctx).forEach { l -> put(JSONObject().put("id", l.id).put("name", l.name).put("cids", JSONArray(l.cids.toList()))) } })
            .put("privacy", JSONObject().put("mode", mode).put("uids", JSONArray(uids.toList())))
        val s = ChatCrypto.seal(payload.toString().toByteArray(Charsets.UTF_8), mapOf("web" to webPub))
        progress(t("Linking the computer…", "కంప్యూటర్ లింక్ చేస్తున్నాం…"))
        val r = ChatSync.api(store, "POST", "/api/web/link", JSONObject().put("code", code)
            .put("sealed", JSONObject().put("epk", s.epk).put("iv", s.iv).put("ct", s.ct).put("key", s.keys["web"]))
            .put("upto", copy.upto).toString(), timeoutMs = 30_000)
        if (!r.ok) return r.error(t("Couldn't link the computer. Please try again.", "కంప్యూటర్ లింక్ కాలేదు. మళ్ళీ ప్రయత్నించండి."))
        return null
    }
    /** "Today, 3:05 PM", "Yesterday, …" or "3 Nov, …". */
    fun whenText(at: Long, te: Boolean): String {
        if (at <= 0) return ""
        val z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = when (z.toLocalDate()) { LocalDate.now() -> if (te) "ఈరోజు" else "Today"; LocalDate.now().minusDays(1) -> if (te) "నిన్న" else "Yesterday"; else -> z.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) }
        return day + ", " + z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    }
}

/** 💻 Linked devices: the computers using this phone's Habit Chat, "Link a computer" and "Log out". */
class LinkedDevicesActivity : Activity() {
    companion object {
        private const val REQ_CAM = 7601
        private const val REQ_PIC = 7602
        fun open(a: Activity, qr: String? = null) = a.startActivity(Intent(a, LinkedDevicesActivity::class.java).apply { if (qr != null) putExtra("qr", qr) })
    }
    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var box: LinearLayout
    private val C = ChatTheme
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)
    private var devices: List<LinkedDevices.Device>? = null
    private var loading = true
    private var busy: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ChatTheme.apply(this)
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.background = ChatTheme.Backdrop(root, C.BG, C.BG)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(12), dp(6)); minimumHeight = dp(60)
            addView(Ico.view(this@LinkedDevicesActivity, R.drawable.ic_back, C.TXT, 22, 48, t("Back", "వెనక్కి")) { finish() })
            addView(ui.text(t("Linked devices", "లింక్ చేసిన పరికరాలు"), 22f, C.TXT).apply { typeface = Fonts.heavy(context); setPadding(dp(4), 0, 0, 0) })
        })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ChatTheme.bars(this, C.BG, C.BG)
        draw(); refresh()
        intent.getStringExtra("qr")?.let { if (it.isNotBlank()) onQrText(it) }
    }

    private fun refresh() {
        loading = true; draw()
        Thread { val l = LinkedDevices.list(store); runOnUiThread { devices = l; loading = false; draw() } }.start()
    }

    private fun draw() {
        box.removeAllViews()
        // the picture and the button
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(22), dp(18), dp(20)); background = C.round(C.CARD, dp(24).toFloat(), C.EDGE, dp(1))
            addView(Ico.view(this@LinkedDevicesActivity, R.drawable.ic_media, C.TINTS[0].second, 34, 72, "", C.round(C.TINTS[0].first, dp(24).toFloat())), LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(ui.text(t("Use Habit Chat on your computer", "మీ కంప్యూటర్‌లో Habit చాట్ వాడండి"), 18f, C.TXT).apply { typeface = Fonts.heavy(context) }.apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) })
            addView(ui.text(t("1. On the computer, open  bigdai.tech/web\n2. Tap \"Link a computer\" below and take a photo of the QR code on its screen.",
                "1. కంప్యూటర్‌లో  bigdai.tech/web  తెరవండి\n2. కింద \"కంప్యూటర్ లింక్ చేయండి\" నొక్కి, స్క్రీన్ మీది QR కోడ్ ఫోటో తీయండి."), 15f, C.TXT))
            addView(TextView(this@LinkedDevicesActivity).apply {
                text = t("Link a computer", "కంప్యూటర్ లింక్ చేయండి"); textSize = 16f; setTextColor(C.ON_PRIMARY); typeface = Fonts.heavy(context); gravity = Gravity.CENTER
                setCompoundDrawablesRelative(Ico.d(context, R.drawable.ic_scan, C.ON_PRIMARY, dp(20)), null, null, null); compoundDrawablePadding = dp(8)
                setPadding(dp(18), dp(14), dp(18), dp(14)); background = C.round(C.PRIMARY, dp(26).toFloat()); setOnClickListener { scan() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // the computers
        box.addView(ui.text(t("LINKED COMPUTERS", "లింక్ అయిన కంప్యూటర్లు"), 12f, C.GOLD_TXT).apply { typeface = Fonts.heavy(context); letterSpacing = 0.07f; setPadding(dp(4), dp(22), dp(4), dp(6)) })
        val list = devices
        when {
            loading && list == null -> box.addView(ui.text(t("Loading…", "చూస్తున్నాం…"), 14.5f, C.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
            list == null -> box.addView(ui.text(t("No connection. Check your internet.", "కనెక్షన్ లేదు. ఇంటర్నెట్ చూడండి.") + "  ↻", 14.5f, C.DANGER).apply { setPadding(dp(4), dp(6), dp(4), dp(6)); setOnClickListener { refresh() } })
            list.isEmpty() -> box.addView(ui.text(t("No computers yet.", "ఇంకా కంప్యూటర్లు లేవు."), 14.5f, C.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
            else -> for (d in list) box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(10), dp(12)); background = C.round(C.CARD, dp(20).toFloat(), C.EDGE, dp(1))
                addView(if (d.name.contains("Android") || d.name.contains("iOS")) ui.text("📱", 22f, C.TXT).apply { gravity = Gravity.CENTER; background = C.round(C.CARD2, dp(14).toFloat()) }
                    else Ico.view(this@LinkedDevicesActivity, R.drawable.ic_media, C.TXT, 22, 44, "", C.round(C.CARD2, dp(14).toFloat())), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(this@LinkedDevicesActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                    addView(ui.text(d.name, 16f, C.TXT, bold = true))
                    val seen = maxOf(d.seen, d.at)
                    addView(ui.text(if (System.currentTimeMillis() - seen < 90_000) t("Active now", "ఇప్పుడు వాడుతున్నారు") else t("Last active ", "చివరిగా ") + LinkedDevices.whenText(seen, te), 13f, C.SUB))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@LinkedDevicesActivity).apply {
                    text = t("Log out", "లాగ్ అవుట్"); textSize = 13.5f; setTextColor(C.DANGER); typeface = Fonts.bold(context)
                    setPadding(dp(14), dp(9), dp(14), dp(9)); background = C.round(android.graphics.Color.TRANSPARENT, dp(18).toFloat(), C.DANGER, dp(1))
                    setOnClickListener { askLogout(d) }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        box.addView(ui.text("🔒 " + t("Your messages are locked end to end on all your devices: the computer gets this phone's chat key, locked for its QR code only, and Habit's server can't read your chats.",
            "మీ అన్ని పరికరాల్లో మెసేజ్‌లు ఎండ్ టు ఎండ్ లాక్‌లో ఉంటాయి: కంప్యూటర్‌కి ఈ ఫోన్ చాట్ కీ దాని QR కోడ్ కోసం మాత్రమే లాక్ అయి వెళ్తుంది, Habit సర్వర్ మీ చాట్‌లు చదవలేదు."), 13.5f, C.SUB).apply { setPadding(dp(4), dp(22), dp(4), dp(4)) })
        box.addView(ui.text("📱 " + t("Stay on this phone: 🔒 locked chats, 📌 my notes, 🐾 Habit AI, 📢 broadcast lists, view-once photos, and calls (a computer shows \"answer on your phone\"). Up to 4 computers; one not used for 30 days logs out by itself.",
            "ఈ ఫోన్‌లోనే ఉంటాయి: 🔒 లాక్ చేసిన చాట్‌లు, 📌 నా నోట్స్, 🐾 Habit AI, 📢 బ్రాడ్‌కాస్ట్ లిస్ట్‌లు, ఒకసారి చూసే ఫోటోలు, కాల్స్. 4 కంప్యూటర్ల వరకు; 30 రోజులు వాడని కంప్యూటర్ దానంతట అదే లాగ్ అవుట్ అవుతుంది."), 13.5f, C.SUB).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
    }

    private fun askLogout(d: LinkedDevices.Device) {
        AlertDialog.Builder(this).setTitle(t("Log out?", "లాగ్ అవుట్ చేయాలా?")).setMessage(t("Log out of Habit Chat on \"${d.name}\"? Its copy of your chats is removed the next time it opens.", "\"${d.name}\" లో Habit చాట్ నుంచి లాగ్ అవుట్ చేయాలా?"))
            .setPositiveButton(t("Log out", "లాగ్ అవుట్")) { _, _ ->
                Thread { val r = LinkedDevices.logout(store, d.id); runOnUiThread { if (r.ok) { toast(t("Logged out ✓", "లాగ్ అవుట్ చేశాం ✓")); refresh() } else toast(r.error("Couldn't log it out.")) } }.start()
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    // ---------- reading the QR code: a photo of it (or a screenshot) ----------
    private fun scan() {
        AlertDialog.Builder(this).setTitle(t("🔗 Link a computer", "🔗 కంప్యూటర్ లింక్ చేయండి"))
            .setItems(arrayOf(t("📷 Take a photo of the QR code", "📷 QR కోడ్ ఫోటో తీయండి"), t("🖼️ From a photo or screenshot", "🖼️ ఫోటో లేదా స్క్రీన్‌షాట్ నుంచి"))) { _, i -> if (i == 0) camera() else pickPhoto() }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun camera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM); return }
        val f = CameraFiles.file(this); f.parentFile?.mkdirs(); f.delete()
        val uri = CameraFiles.uri(this)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try { startActivityForResult(i, REQ_CAM) } catch (e: Exception) { toast(t("No camera app found.", "కెమెరా యాప్ లేదు.")) }
    }
    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_PIC) } catch (e: Exception) { try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PIC) } catch (e2: Exception) { toast("Couldn't open your photos.") } }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera()
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = when (requestCode) { REQ_CAM -> CameraFiles.uri(this); REQ_PIC -> data?.data ?: return; else -> return }
        showBusy(t("Reading the QR code…", "QR కోడ్ చదువుతున్నాం…"))
        Thread {
            val text = try { QrRead.decode(this, uri) } catch (e: Exception) { "" }
            if (requestCode == REQ_CAM) try { CameraFiles.file(this).delete() } catch (e: Exception) { }
            runOnUiThread {
                hideBusy()
                when {
                    text.isEmpty() -> AlertDialog.Builder(this).setMessage(t("No QR code found in that picture. Hold the phone steady, fill the photo with the QR code, and try again.", "ఆ ఫోటోలో QR కోడ్ కనిపించలేదు. ఫోన్ కదలకుండా, QR కోడ్ ఫోటో నిండా వచ్చేలా మళ్ళీ తీయండి."))
                        .setPositiveButton(t("Try again", "మళ్ళీ")) { _, _ -> scan() }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
                    else -> onQrText(text)
                }
            }
        }.start()
    }

    // ---------- linking ----------
    private fun onQrText(text: String) {
        if (LinkedDevices.parse(text) == null) {
            AlertDialog.Builder(this).setMessage(t("That isn't a Habit Chat computer QR code. On the computer, open bigdai.tech/web and photograph the QR code shown there.", "అది Habit చాట్ కంప్యూటర్ QR కోడ్ కాదు. కంప్యూటర్‌లో bigdai.tech/web తెరిచి, అక్కడి QR కోడ్ ఫోటో తీయండి."))
                .setPositiveButton(t("OK", "సరే"), null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(t("💻 Link this computer?", "💻 ఈ కంప్యూటర్ లింక్ చేయాలా?"))
            .setMessage(t("It will show your Habit chats and can send messages as you, until you log it out here. Only link your own computer, or a computer you trust.\n\nNever scan a Habit QR code someone sends you: that would give them your chats.",
                "మీరు ఇక్కడ లాగ్ అవుట్ చేసేవరకు ఆ కంప్యూటర్ మీ Habit చాట్‌లు చూపిస్తుంది, మీ పేరుతో మెసేజ్‌లు పంపగలదు. మీ సొంత కంప్యూటర్ లేదా నమ్మకమైనది మాత్రమే లింక్ చేయండి.\n\nఎవరైనా పంపిన Habit QR కోడ్‌ని ఎప్పుడూ స్కాన్ చేయకండి: దాంతో మీ చాట్‌లు వాళ్ళకు వెళ్తాయి."))
            .setPositiveButton(t("Link", "లింక్ చేయి")) { _, _ -> unlock { linkNow(text) } }
            .setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    /** The phone's own lock first (fingerprint, face or PIN), when it has one. */
    private fun unlock(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure || Build.VERSION.SDK_INT < 28) { then(); return }
        val b = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(t("Link a computer", "కంప్యూటర్ లింక్"))
            .setSubtitle(t("Confirm it's you", "మీరేనని నిర్ధారించండి"))
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        try {
            b.build().authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { then() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { toast(errString?.toString() ?: t("Not confirmed", "నిర్ధారించలేదు")) }
            })
        } catch (e: Exception) { toast(t("Couldn't confirm it's you.", "నిర్ధారించలేకపోయాం.")) }
    }
    private fun linkNow(text: String) {
        showBusy(t("Linking…", "లింక్ చేస్తున్నాం…"))
        val app = applicationContext
        Thread {
            val err = try { LinkedDevices.link(app, store, text) { p -> runOnUiThread { busy?.setMessage(p) } } } catch (e: Exception) { t("Couldn't link the computer. Please try again.", "కంప్యూటర్ లింక్ కాలేదు. మళ్ళీ ప్రయత్నించండి.") }
            runOnUiThread {
                hideBusy()
                if (err == null) {
                    AlertDialog.Builder(this).setTitle(t("Linked ✓", "లింక్ అయింది ✓"))
                        .setMessage(t("Habit Chat is open on the computer now. Your chats show there in a moment.", "కంప్యూటర్‌లో Habit చాట్ తెరుచుకుంది. మీ చాట్‌లు కాసేపట్లో అక్కడ కనిపిస్తాయి."))
                        .setPositiveButton(t("OK", "సరే"), null).show()
                    refresh()
                } else AlertDialog.Builder(this).setMessage(err).setPositiveButton(t("OK", "సరే"), null).show()
            }
        }.start()
    }
    private fun showBusy(msg: String) {
        hideBusy()
        busy = AlertDialog.Builder(this).setMessage(msg).setCancelable(false).create().also { it.show() }
    }
    private fun hideBusy() { try { busy?.dismiss() } catch (e: Exception) { }; busy = null }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { hideBusy(); super.onDestroy() }
}
