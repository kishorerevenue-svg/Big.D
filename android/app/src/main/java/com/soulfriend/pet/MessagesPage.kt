package com.soulfriend.pet

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Messages page: is a message safe or a fraud? Paste or share it, or check a screenshot or a QR code.
 * For any message: explain it in simple words, write a reply (and send it by WhatsApp, SMS or any app),
 * and report or block it. This week's new scams, the fake messages list that people add to (FakeList),
 * and the messages of the last 7 days with 🟢 🟡 🔴 labels.
 * Nothing here runs in the background: everything happens when you tap (kind to the battery).
 */
class MessagesPage(private val m: MainActivity) {
    private var input = ""
    private var checking = false
    private var verdict: ScamCheck.Verdict? = null
    // the message the buttons below work on (the one just checked, or one tapped in the list)
    private var curText = ""
    private var curSender = ""
    private var curApp = ""
    private var curId = ""
    // a picture to check
    private var pic: ByteArray? = null
    private var picThumb: Bitmap? = null
    private var qr = ""
    private var preparing = false
    // explain, reply, report
    private var explainText = ""
    private var explaining = false
    private var replyOpen = false
    private var replyTone = "polite"
    private var replyLang = ""
    private var replyText = ""
    private var replyWarn = ""
    private var replying = false
    private var reportOpen = false
    // this week's new scams, and the 7-day list
    private var scamsLoading = false
    private var scamsTried = 0L
    private var scamsRetries = 0
    private var scamsHolder: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var filter = ""
    private var showAll = false
    private var resultView: View? = null
    // the fake messages list that people add to (in its own class)
    private val fakes = FakeList(m)

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private fun equal(vararg views: View) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = m.dp(6) }) }
    }

    /** Text shared to Habit AI from another app: check it right away. */
    fun checkShared(text: String) { input = text.take(3000); pic = null; qr = ""; verdict = null; check() }

    fun build() {
        resultView = null
        m.pageHeader("Messages · మెసేజ్‌లు", "Chat with your people on Habit, and check whether a message is safe or a fraud.")
        m.chatPage.build()

        m.add(ui.section("🛡️ Is this message safe?", "Paste a message you got, share it to Habit AI, or check a picture of it.").apply {
            val box = ui.input("Paste or type the message here…", input, multi = true)
            box.onText { input = it }
            put(box, 10)
            put(equal(ui.button("📋 Paste") {
                val clip = try { m.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(m)?.toString() } catch (e: Exception) { null }
                if (clip.isNullOrBlank()) m.toast("Nothing copied yet. Long-press a message › Copy, then come back.") else { input = clip; box.setText(clip) }
            }, ui.button("📷 Camera") { camera() }, ui.button("🖼️ Screenshot") { pickPicture() }), 8)
            val p = pic
            if (p != null || preparing) {
                put(pictureRow(), 8)
                put(ui.text("The picture goes to the Habit server and Claude AI only for this check, and is not kept. " +
                    "\"Hide personal details\" can't cover pictures, so crop out anything private first.", 12f, Ui.MUTED), 6)
            }
            if (qr.isNotBlank()) put(qrWarning(), 8)
            put(ui.button(if (checking) "Checking…" else "🛡️ Check it", "primary") { if (!checking) check() }, 10)
            put(ui.text("Tip: in WhatsApp or SMS, long-press the message › Copy, then tap 📋 Paste. Or tap Share › Habit AI. " +
                "For a fake payment screenshot or a QR code, tap 🖼️ Screenshot.", 12.5f, Ui.MUTED), 8)
        }, 12)

        verdict?.let { v -> result(v).also { resultView = it; m.add(it, 10) } }
        if (curText.isNotBlank() || verdict != null) {
            if (explainText.isNotBlank() || explaining) m.add(explainCard(), 10)
            if (replyOpen) m.add(replyCard(), 10)
            if (reportOpen) m.add(reportCard(), 10)
        }

        fakes.build()   // the fake messages list people add to, right under the check (the yellow "Automatic checking" box is gone)
        scamsCard()

        if (BuildConfig.MESSAGE_REPLIES) {
            val access = MessageHub.accessGranted(m)
            m.add(ui.section("Automatic check · ఆటోమేటిక్ చెక్", "Every new message gets a 🟢 🟡 🔴 label here. A dangerous one shows a red warning at once.").apply {
                if (!access || !store.msgWatch) {
                    put(ui.text("○ Off. Turn it on to check every new WhatsApp and SMS message.", 14f, Ui.INK), 10)
                    put(ui.button("Turn on", "primary") { m.showMessageDisclosure() }, 8)
                    if (!access) put(ui.text("If Android says \"Restricted setting\": open Settings › Apps › Habit AI › ⋮ › Allow restricted settings, then tap Turn on again.", 12f, Ui.MUTED), 6)
                } else {
                    put(ui.text("✅ On for " + MessageHub.APPS.filter { it.first in store.msgApps }.joinToString(", ") { it.second }, 14f, Ui.GOOD, bold = true), 10)
                    put(ui.check("🛡️ Red warning for dangerous messages", store.scamAlerts) { store.scamAlerts = it }, 6)
                    put(ui.text("Light on the battery: each message is first checked on the phone, and only ones with warning signs go for a full check.", 12f, Ui.MUTED), 6)
                }
            }, 12)
        }

        history()
        if (BuildConfig.MESSAGE_REPLIES) m.add(m.messagesCard(), 18)
    }

    // =================== checking ===================
    private fun check() {
        val t = input.trim()
        val p = pic
        if (p == null && t.length < 5) { m.toast("Paste or type a message, or add a screenshot, first."); return }
        if (preparing) { m.toast("Wait a moment: the picture is still getting ready."); return }
        checking = true; verdict = null; resetHelpers(); m.render()
        if (p != null) { checkPicture(p, t); return }
        m.io.execute {
            val v = ScamCheck.checkBlocking(store, t, "", "", always = true)
            val id = "manual_" + System.currentTimeMillis()
            ScamCheck.log(store, id, "Checked by you", "You", t, v, "manual")
            m.runOnUiThread {
                checking = false; verdict = v; curText = t; curSender = ""; curApp = ""; curId = id
                if (v.level == "danger") reportOpen = true
                m.render(); resultView?.let { m.scrollToView(it) }
                if (v.level == "danger") dangerDialog(v, t)
            }
        }
    }

    /** A screenshot, a payment screenshot or a QR code: the AI reads it; a QR code was already read on the phone. */
    private fun checkPicture(p: ByteArray, typed: String) {
        val q = qr
        val hints = JSONArray()
        QrRead.describe(q).takeIf { it.isNotBlank() }?.let { hints.put(it) }
        val body = JSONObject().put("text", Privacy.maybe(store, typed).take(3000)).put("lang", store.lang).put("hints", hints)
            .put("image", JSONObject().put("mediaType", "image/jpeg").put("data", Base64.encodeToString(p, Base64.NO_WRAP)))
        if (q.isNotBlank()) body.put("qr", q.take(1000))
        m.io.execute {
            val r = Net.post(store, "/api/scan", body, emptyMap(), 90_000)
            val v = if (r.ok && r.json.has("level")) ScamCheck.Verdict.from(r.json)
                else if (QrRead.upi(q) != null) ScamCheck.Verdict("danger", "QR code that takes money from you", listOf(QrRead.describe(q)),
                    listOf("Don't scan it or enter your UPI PIN", "Nobody needs your QR scan or PIN to send you money", "Lost money? Call 1930 at once"), offline = true)
                else null
            val read = r.json.optString("read").ifBlank { typed }
            val id = "pic_" + System.currentTimeMillis()
            if (v != null) ScamCheck.log(store, id, "Picture", "Checked by you", read.ifBlank { "(a picture)" }, v, "manual")
            m.runOnUiThread {
                checking = false
                if (v == null) { m.toast(r.error("Couldn't check the picture. Please try again.")); m.render(); return@runOnUiThread }
                verdict = v; curText = read.ifBlank { QrRead.describe(q) }; curSender = ""; curApp = "Picture"; curId = id
                pic = null; picThumb = null; qr = ""                 // checked: the picture is not kept
                if (v.level == "danger") reportOpen = true
                m.render(); resultView?.let { m.scrollToView(it) }
                if (v.level == "danger") dangerDialog(v, curText)
            }
        }
    }

    private fun resetHelpers() { explainText = ""; explaining = false; replyOpen = false; replyText = ""; replyWarn = ""; reportOpen = false }

    private fun pictureRow(): View = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(m.dp(8), m.dp(8), m.dp(8), m.dp(8)); background = ui.round(Color.parseColor("#F5F8F7"), 14, Ui.LINE)
        val t = picThumb
        if (t != null) addView(ImageView(m).apply { setImageBitmap(t); scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(Ui.LINE, 8); clipToOutline = true },
            LinearLayout.LayoutParams(m.dp(52), m.dp(52)))
        addView(ui.text(if (preparing) "⏳ Getting the picture ready…" else "📷 Picture added. Tap 🛡️ Check it.", 14f, Ui.INK),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = m.dp(10) })
        if (!preparing) addView(ui.button("✕", "ghost") { pic = null; picThumb = null; qr = ""; m.render() }.apply { contentDescription = "Remove the picture" })
    }

    /** Shown at once when the picture has a QR code (read on the phone, before any AI check). */
    private fun qrWarning(): View {
        val upi = QrRead.upi(qr) != null
        return ui.card(if (upi) ScamCheck.soft("danger") else Ui.TEAL_SOFT).apply {
            addView(ui.text("🔳 QR code found", 14.5f, if (upi) ScamCheck.color("danger") else Ui.TEAL_DARK, bold = true))
            put(ui.text(QrRead.describe(qr), 14f, Ui.INK), 4)
            if (upi) put(ui.text("If someone says \"scan this to receive money\", it's a fraud. Never enter your UPI PIN to receive money.", 13f, Ui.INK, bold = true), 6)
        }
    }

    // ---------- the picture: camera or gallery ----------
    private fun camera() {
        val file = CameraFiles.file(m)
        file.parentFile?.mkdirs(); file.delete()
        val uri = CameraFiles.uri(m)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        m.startCamera(i, MainActivity.REQ_MSG_CAMERA, "No camera app found. Tap 🖼️ Screenshot instead.")
    }

    private fun pickPicture() {
        m.leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { m.startActivityForResult(i, MainActivity.REQ_MSG_PHOTO) }
        catch (e: Exception) {
            try { m.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), MainActivity.REQ_MSG_PHOTO) }
            catch (e2: Exception) { m.leavingForSystemScreen = false; m.toast("Couldn't open your photos.") }
        }
    }

    fun onCamera() {
        val f = CameraFiles.file(m)
        if (!f.exists() || f.length() == 0L) { m.toast("No photo was taken."); return }
        usePicture(Uri.fromFile(f))
    }

    fun onPicture(uri: Uri) = usePicture(uri)

    private fun usePicture(uri: Uri) {
        preparing = true; pic = null; picThumb = null; qr = ""; verdict = null; resetHelpers(); m.render()
        m.io.execute {
            val code = QrRead.decode(m, uri)                 // on the phone
            val bytes = Images.prepare(m, uri, 1568)
            val thumb = bytes?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (e: Throwable) { null } }
            try { CameraFiles.file(m).delete() } catch (e: Exception) { }
            m.runOnUiThread {
                preparing = false
                if (bytes == null) { m.toast("Couldn't open that picture."); m.render(); return@runOnUiThread }
                pic = bytes; picThumb = thumb; qr = code
                m.render()
            }
        }
    }

    // =================== the result and what to do with it ===================
    private fun result(v: ScamCheck.Verdict): View = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; setPadding(m.dp(16), m.dp(14), m.dp(16), m.dp(14))
        background = ui.round(ScamCheck.soft(v.level), 20, ScamCheck.color(v.level))
        addView(ui.text(ScamCheck.label(v.level), 18f, ScamCheck.color(v.level), bold = true))
        if (v.title.isNotBlank()) put(ui.text(v.title, 16f, Ui.INK, bold = true), 6)
        if (curSender.isNotBlank() || curApp.isNotBlank()) put(ui.text(listOf(curSender, curApp).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, Ui.MUTED), 2)
        if (curText.isNotBlank()) put(ui.text("“" + curText.take(400) + (if (curText.length > 400) "…" else "") + "”", 13.5f, Ui.INK).apply {
            setPadding(m.dp(10), m.dp(8), m.dp(10), m.dp(8)); background = ui.round(Color.WHITE, 12)
        }, 8)
        if (v.reasons.isNotEmpty()) put(ui.text("Why · ఎందుకు\n" + v.reasons.joinToString("\n") { "• $it" }, 14f, Ui.INK), 8)
        if (v.advice.isNotEmpty()) put(ui.text("What to do · ఏం చేయాలి\n" + v.advice.joinToString("\n") { "✓ $it" }, 14f, Ui.INK), 8)
        put(equal(
            ui.button(if (explaining) "…" else "🗣️ Explain") { explain() },
            ui.button("✍️ Reply") { replyOpen = !replyOpen; m.render() },
            ui.button("🚨 Report", if (v.level == "danger") "danger" else "soft") { reportOpen = !reportOpen; m.render() }), 12)
        if (v.level != "safe" && curText.isNotBlank()) put(ui.button("📋 Add to the fake messages list (warn others)", "ghost") { fakes.addDialog(curText) }, 10)
        if (v.offline) put(ui.text("Checked on your phone only. Connect to the internet for a full check.", 12f, Ui.MUTED), 8)
        else put(ui.text("🚩 Wrong result? Tell the Habit team", 12.5f, Ui.MUTED).apply {
            setPadding(0, m.dp(4), 0, m.dp(4))
            setOnClickListener { m.reportAi("message check", listOf(ScamCheck.label(v.level), v.title, v.reasons.joinToString("; "), v.advice.joinToString("; ")).filter { it.isNotBlank() }.joinToString("\n"), "") }
        }, 10)
    }

    // ---------- explain in simple words ----------
    private fun explain() {
        if (explaining) return
        val t = curText.ifBlank { input.trim() }
        if (t.length < 3) { m.toast("There is no message text to explain."); return }
        explaining = true; explainText = ""; m.render()
        val body = JSONObject().put("text", Privacy.maybe(store, t).take(4000)).put("lang", store.lang)
        m.io.execute {
            val r = Net.post(store, "/api/message/explain", body, emptyMap(), 60_000)
            m.runOnUiThread {
                explaining = false
                if (r.ok) explainText = r.json.optString("text") else m.toast(r.error("Couldn't explain it. Please try again."))
                m.render()
            }
        }
    }

    private fun explainCard() = ui.section("🗣️ What this message means · అర్థం").apply {
        if (explaining) { put(ui.text("Reading it…", 14f, Ui.MUTED), 8); return@apply }
        put(ui.text(explainText, 15f, Ui.INK).apply { setTextIsSelectable(true) }, 8)
        put(ui.row(ui.button("🔊 Listen") { m.speakOut(explainText) }, ui.button("📋 Copy") { copy(explainText) }), 10)
    }

    // ---------- write a reply, then send it with any app ----------
    private fun replyCard() = ui.section("✍️ Help me reply · జవాబు").apply {
        if (verdict?.level == "danger") put(ui.text("⚠️ This message looks dangerous. It's safer not to reply at all.", 13.5f, ScamCheck.color("danger"), bold = true), 6)
        put(ui.text("How should it sound?", 13.5f, Ui.MUTED), 8)
        val tones = listOf("polite" to "🙏 Polite", "friendly" to "🙂 Friendly", "formal" to "🏢 Formal", "no" to "✋ Say no")
            .map { (k, l) -> ui.chip(l, replyTone == k) { replyTone = k; m.render() } }
        put(equal(tones[0], tones[1]), 6)
        put(equal(tones[2], tones[3]), 6)
        put(ui.text("Language (Auto = same as the message)", 13.5f, Ui.MUTED), 10)
        put(equal(*listOf("" to "Auto", "te" to "తెలుగు", "en" to "English")
            .map { (k, l) -> ui.chip(l, replyLang == k) { replyLang = k; m.render() } }.toTypedArray()), 6)
        put(ui.button(if (replying) "Writing…" else if (replyText.isBlank()) "✍️ Write the reply" else "↺ Write it again", "primary") { if (!replying) writeReply() }, 12)
        if (replyText.isNotBlank()) {
            if (replyWarn.isNotBlank()) put(ui.text("⚠️ $replyWarn", 13f, ScamCheck.color("careful"), bold = true), 10)
            val box = ui.input("Your reply", replyText, multi = true)
            box.onText { replyText = it }
            put(box, 10)
            put(ui.text("Change it if you like, then send:", 12.5f, Ui.MUTED), 8)
            put(equal(ui.button("🟢 WhatsApp") { sendWhatsApp(replyText) }, ui.button("💬 SMS") { sendSms(replyText) }), 6)
            put(equal(ui.button("↗ Other apps") { shareText(replyText, "Send the reply") }, ui.button("📋 Copy") { copy(replyText) }), 6)
            val live = if (BuildConfig.MESSAGE_REPLIES && curId.isNotBlank()) MessageHub.recent(curId) else null
            if (live?.action != null) put(ui.button("↩ Send it in the ${live.app} chat now", "primary") { sendInChat(live) }, 8)
        }
    }

    /** Sends the reply through the message's own notification (like replying from the notification), after you say yes. */
    private fun sendInChat(inc: Incoming) {
        val t = replyText.trim()
        if (t.isEmpty()) return
        AlertDialog.Builder(m).setTitle("Send this reply?").setMessage("To " + inc.sender.ifBlank { "them" } + " on " + inc.app + ":\n\n“" + t + "”")
            .setPositiveButton("Send") { _, _ ->
                if (MessageHub.send(m, inc, t)) m.toast("Sent ✓") else m.toast("Couldn't send it from here. Use 🟢 WhatsApp or 💬 SMS above.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun writeReply() {
        val t = curText.ifBlank { input.trim() }
        if (t.length < 2) { m.toast("There is no message to reply to."); return }
        val tone = when (replyTone) {
            "friendly" -> "Write a friendly, warm reply."; "formal" -> "Write a formal, respectful reply."
            "no" -> "Politely say no / decline, kindly and clearly."; else -> "Write a polite reply."
        }
        val lang = when (replyLang) { "te" -> " Write it in Telugu script."; "en" -> " Write it in English."; else -> "" }
        replying = true; m.render()
        val body = JSONObject().put("text", Privacy.maybe(store, t).take(3000)).put("sender", curSender.ifBlank { "Someone" }).put("app", curApp.ifBlank { "chat" })
            .put("instruction", tone + lang)
        m.io.execute {
            val r = Net.post(store, "/api/reply", body, emptyMap(), 45_000)
            m.runOnUiThread {
                replying = false
                if (r.ok) { replyText = r.json.optString("reply"); replyWarn = r.json.optString("warning") } else m.toast(r.error("Couldn't write a reply. Please try again."))
                m.render()
            }
        }
    }

    // ---------- report and block ----------
    private fun reportCard() = ui.section("🚨 Report and block · ఫిర్యాదు").apply {
        put(ui.text("Lost money? Call 1930 at once: the sooner you call, the better the chance to stop the money.", 13.5f, Ui.INK), 6)
        put(equal(ui.button("📞 Call 1930", "danger") { dial("1930") }, ui.button("🛡️ Chakshu") { open("https://sancharsaathi.gov.in/sfc/") }), 10)
        put(equal(ui.button("🌐 cybercrime.gov.in") { open("https://cybercrime.gov.in") }, ui.button("📝 Police complaint") { complaint() }), 6)
        put(ui.button("👨‍👩‍👧 Ask my family to check it") { askFamily() }, 6)
        put(ui.text("Chakshu (Sanchar Saathi) is for reporting fraud calls and messages. cybercrime.gov.in is for complaints about online fraud.", 12f, Ui.MUTED), 8)
        put(ui.text("🚫 To block the sender: in WhatsApp, open the chat › ⋮ › More › Block. In Messages (SMS), open the message › ⋮ › Block. " +
            "Then delete the message. Don't call back unknown numbers.", 13f, Ui.INK), 8)
    }

    private fun complaint() {
        val when_ = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(java.time.LocalDate.now())
        val from = listOf(curSender, curApp).filter { it.isNotBlank() && it != "Checked by you" && it != "You" }.joinToString(" on ")
        val req = "Complaint to the Cyber Crime Police about a fraud message I received" + (if (from.isNotBlank()) " from $from" else "") +
            " (around $when_). The message: \"${curText.take(900)}\"." + (verdict?.title?.takeIf { it.isNotBlank() }?.let { " Habit AI says: $it." } ?: "")
        m.openLetter(req, "public")
    }

    private fun askFamily() {
        val v = verdict
        val msg = "Please check this message I got. Is it safe?\n\n“" + curText.take(800) + "”" +
            (if (v != null) "\n\nHabit AI says: " + ScamCheck.label(v.level) + (if (v.title.isNotBlank()) " · " + v.title else "") else "")
        val fam = store.family()
        if (fam.isEmpty()) { shareText(msg, "Send to your family"); return }
        val names = fam.map { it.name + if (it.relation.isNotBlank()) " (${it.relation})" else "" } + "Someone else…"
        AlertDialog.Builder(m).setTitle("Send it to").setItems(names.toTypedArray()) { _, i ->
            if (i >= fam.size) shareText(msg, "Send to") else sendWhatsApp(msg, fam[i].phone)
        }.show()
    }

    /** A big red window for dangerous messages. */
    fun dangerDialog(v: ScamCheck.Verdict, text: String) {
        val red = ScamCheck.color("danger")
        val box = LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(m.dp(20), m.dp(16), m.dp(20), m.dp(8))
            addView(ui.text("⚠️  DANGER · ప్రమాదం", 20f, Color.WHITE, bold = true).apply {
                setPadding(m.dp(12), m.dp(8), m.dp(12), m.dp(8)); background = ui.round(red, 12)
            })
            put(ui.text(v.title.ifBlank { "This looks like a fraud message" }, 17f, red, bold = true), 12)
            if (text.isNotBlank()) put(ui.text("“" + text.take(200) + "”", 14f, Ui.INK).apply { setPadding(m.dp(10), m.dp(8), m.dp(10), m.dp(8)); background = ui.round(ScamCheck.soft("danger"), 12) }, 8)
            if (v.advice.isNotEmpty()) put(ui.text(v.advice.joinToString("\n") { "✓ $it" }, 14.5f, Ui.INK, bold = true), 10)
            put(ui.row(ui.button("📞 Call 1930", "danger") { dial("1930") }, ui.button("🛡️ Chakshu") { open("https://sancharsaathi.gov.in/sfc/") }), 12)
        }
        AlertDialog.Builder(m).setView(box).setPositiveButton("OK, I understand", null).show()
    }

    // =================== this week's new scams ===================
    private fun scamsLang() = if (store.lang == "en") "en" else "te"

    /** The card sits in its own holder, so when the list arrives only this card is redrawn (not the page you may be typing in). */
    private fun scamsCard() {
        val holder = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        scamsHolder = holder
        fillScams(holder)
        m.add(holder, 14)
    }

    private fun fillScams(holder: LinearLayout) {
        holder.removeAllViews()
        val cache = store.scamCache?.takeIf { it.optString("lang") == scamsLang() }
        val stale = cache == null || System.currentTimeMillis() - store.scamCacheAt > 12 * 3600_000L
        if (stale && !scamsLoading && System.currentTimeMillis() - scamsTried > 60_000) loadScams()
        holder.addView(ui.section("📰 This week's new scams · కొత్త మోసాలు", "Know them before they reach you. Share them with your family.").apply {
            val items = cache?.optJSONArray("items")
            if (items == null || items.length() == 0) {
                put(ui.text(if (scamsLoading || scamsRetries > 0) "⏳ Getting this week's list from the news… (about a minute the first time)"
                    else "Not ready yet. Open this page again in a little while.", 14f, Ui.MUTED), 8)
                return@apply
            }
            val lines = StringBuilder()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(m.dp(12), m.dp(10), m.dp(12), m.dp(10)); background = ui.round(Color.parseColor("#FFF7F3"), 14, Ui.LINE)
                    addView(ui.text("${i + 1}. " + o.optString("title"), 15f, Ui.INK, bold = true))
                    addView(ui.text(o.optString("how"), 14f, Ui.INK))
                    if (o.optString("do").isNotBlank()) addView(ui.text("✓ " + o.optString("do"), 14f, Ui.GOOD, bold = true))
                }, 8)
                lines.append("${i + 1}. ").append(o.optString("title")).append(": ").append(o.optString("how")).append(" ").append(o.optString("do")).append("\n")
            }
            put(equal(ui.button("🔊 Listen") { m.speakOut(lines.toString()) },
                ui.button("📤 Share with family") { shareText("⚠️ New scams this week (from Habit AI):\n\n" + lines.toString().trim(), "Share") }), 10)
            val at = cache.optLong("at")
            val src = cache.optJSONArray("sources")
            val names = if (src == null) "" else (0 until src.length()).mapNotNull { src.optJSONObject(it)?.optString("url")?.let { u -> Uri.parse(u).host?.removePrefix("www.") } }.distinct().take(4).joinToString(", ")
            put(ui.text("From the news" + (if (names.isNotBlank()) " ($names)" else "") +
                (if (at > 0) " · " + DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())) else "") +
                " · written by AI, check with official sources", 12f, Ui.MUTED), 8)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** One small download when the page opens (at most every 12 hours). The first list of the day takes the server about a minute
     *  to make, so while it says "pending" we look again 3 times, 30 seconds apart, only while this page is open. */
    private fun loadScams() {
        scamsLoading = true; scamsTried = System.currentTimeMillis()
        val lang = scamsLang()
        m.io.execute {
            val r = Net.get(store, "/api/scams?lang=$lang")
            m.runOnUiThread {
                scamsLoading = false
                if (r.ok && r.json.optJSONArray("items") != null) { store.scamCache = r.json.put("lang", lang); scamsRetries = 0 }
                else if (r.code == 202 && scamsRetries < 3) {
                    scamsRetries++
                    handler.postDelayed({ if (m.tab == "messages" && !scamsLoading) loadScams() }, 30_000)
                } else scamsRetries = 0
                scamsHolder?.takeIf { it.isAttachedToWindow && m.tab == "messages" }?.let { fillScams(it) }
            }
        }
    }

    // =================== the last 7 days ===================
    private fun history() {
        val all = store.msgLog().reversed()
        val danger = all.count { it.optString("level") == "danger" }
        val careful = all.count { it.optString("level") == "careful" }
        m.add(ui.label("Last 7 days · 7 రోజులు"), 20)
        if (all.isEmpty()) { m.add(ui.text(if (BuildConfig.MESSAGE_REPLIES) "New messages will appear here with a 🟢 🟡 🔴 safety label." else "Messages you check will appear here.", 14f, Ui.MUTED), 8); return }
        m.add(ui.text("${all.size} checked · 🔴 $danger dangerous · 🟡 $careful careful", 14f, Ui.INK, bold = true), 6)
        m.add(ui.row(ui.chip("All", filter == "") { filter = ""; showAll = false; m.render() },
            ui.chip("🔴 Danger", filter == "danger") { filter = "danger"; showAll = false; m.render() },
            ui.chip("🟡 Careful", filter == "careful") { filter = "careful"; showAll = false; m.render() }, gap = 6), 8)
        val list = if (filter.isEmpty()) all else all.filter { it.optString("level") == filter }
        if (list.isEmpty()) m.add(ui.text("None. 🎉", 14f, Ui.MUTED), 8)
        list.take(if (showAll) 300 else 40).forEach { m.add(row(it), 8) }
        if (!showAll && list.size > 40) m.add(ui.button("Show all ${list.size}", "ghost") { showAll = true; m.render() }, 8)
        m.add(ui.text("Kept only on this phone, for 7 days. Tap one to explain it, reply or report it.", 12f, Ui.MUTED), 8)
        m.add(ui.button("Clear the list", "ghost") {
            AlertDialog.Builder(m).setMessage("Remove all messages from this list?").setPositiveButton("Clear") { _, _ -> store.clearMsgs(); m.render() }.setNegativeButton("Cancel", null).show()
        }, 8)
    }

    private fun row(o: JSONObject): View {
        val level = o.optString("level")
        val v = o.optJSONObject("v")?.let { ScamCheck.Verdict.from(it) }
        val at = Instant.ofEpochMilli(o.optLong("at")).atZone(ZoneId.systemDefault())
        return LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.round(Color.WHITE, 16, Ui.LINE)
            addView(View(m).apply { background = ui.round(ScamCheck.color(level), 3) }, LinearLayout.LayoutParams(m.dp(6), -1).apply { setMargins(m.dp(8), m.dp(10), 0, m.dp(10)) })
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(m.dp(12), m.dp(10), m.dp(14), m.dp(10))
                addView(ui.line(ui.text(o.optString("sender").ifBlank { "Unknown" } + " · " + o.optString("app"), 14.5f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    ui.text(m.fmt.format(at), 12f, Ui.MUTED)))
                addView(ui.text(ScamCheck.label(level) + (if (v != null && level != "safe" && v.title.isNotBlank()) " · " + v.title else ""), 13f, ScamCheck.color(level), bold = true))
                addView(ui.text(o.optString("text"), 14f, Ui.INK).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { open(o) }
            setOnLongClickListener {
                AlertDialog.Builder(m).setMessage("Remove this message from the list?").setPositiveButton("Remove") { _, _ -> store.removeMsg(o.optString("id")); m.render() }
                    .setNegativeButton("Keep", null).show(); true
            }
        }
    }

    /** A message from the list becomes the one the buttons work on (check it again if it wasn't checked). */
    private fun open(o: JSONObject) {
        resetHelpers()
        curText = o.optString("text"); curSender = o.optString("sender"); curApp = o.optString("app"); curId = o.optString("id")
        pic = null; picThumb = null; qr = ""
        val v = o.optJSONObject("v")?.let { ScamCheck.Verdict.from(it) }
        if (v != null) { verdict = v; m.render(); resultView?.let { m.scrollToView(it) }; return }
        verdict = null; checking = true; m.render(); m.scrollTop()
        m.io.execute {
            val nv = ScamCheck.checkBlocking(store, curText, curSender, curApp, always = true)
            ScamCheck.log(store, curId, curApp, curSender, curText, nv, o.optString("src", "manual"))
            m.runOnUiThread {
                checking = false; verdict = nv; m.render(); resultView?.let { m.scrollToView(it) }
                if (nv.level == "danger") dangerDialog(nv, curText)
            }
        }
    }

    // =================== sending and small helpers ===================
    private fun copy(t: String) {
        try { m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Habit AI", t)); m.toast("Copied") } catch (e: Exception) { }
    }

    private fun shareText(t: String, title: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), title)) } catch (e: Exception) { }
    }

    /** The sender's number, when the message came from a phone number (SMS). */
    private fun number(): String {
        val d = curSender.filter { it.isDigit() || it == '+' }
        return if (d.count { it.isDigit() } >= 10 && curSender.none { it.isLetter() }) d else ""
    }

    /** WhatsApp: to [phone] when known (an SMS if WhatsApp isn't there), otherwise you pick the chat in WhatsApp. */
    private fun sendWhatsApp(t: String, phone: String = number()) {
        m.leavingForSystemScreen = true
        if (phone.isNotBlank()) {
            val d = phone.filter { it.isDigit() }
            val n = when { phone.startsWith("+") -> d; d.length == 10 -> "91$d"; d.length == 11 && d.startsWith("0") -> "91" + d.drop(1); else -> d }
            try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$n&text=" + Uri.encode(t)))); return } catch (e: Exception) { }
            sendSms(t, phone); return
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { m.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t).setPackage(pkg)); return } catch (e: Exception) { }
        }
        m.toast("WhatsApp isn't installed."); shareText(t, "Send with")
    }

    private fun sendSms(t: String, to: String = number()) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to))).putExtra("sms_body", t)) } catch (e: Exception) { shareText(t, "Send with") }
    }

    private fun dial(n: String) {
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) } catch (e: Exception) { m.toast("Please dial $n") }
    }

    private fun open(url: String) = m.openUrl(url)
}
