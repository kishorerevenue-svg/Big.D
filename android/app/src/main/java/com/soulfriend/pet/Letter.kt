package com.soulfriend.pet

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The letter helper on the Files page. You say or type what you need, the pet asks every detail the letter
 * needs (each question can be answered by typing or with 🎤, and read aloud with 🔊), suggests extras that make
 * the letter better, and then writes a complete, nicely laid-out Word (or Excel) file with no blanks.
 * Two kinds: Public (applications and letters for people) and Office (letters, reports, memos, proceedings,
 * notices… in government office format, with the office's details saved on the phone).
 * Papers about the matter (camera photos, photos or a PDF) can be added: the AI reads them first.
 */
class LetterHelper(private val m: MainActivity) {
    private class Q(val id: String, val q: String, val hint: String, val required: Boolean, val label: String = "")
    /** A paper about the matter: a photo (JPEG) or a PDF. */
    private class Paper(val name: String, val mime: String, val data: ByteArray, val thumb: Bitmap?)

    companion object {
        private const val MAX_PAPERS = 5
        private const val MAX_PDF = 8 * 1024 * 1024
        private const val MAX_ALL = 13 * 1024 * 1024
        /** icon, type (sent to the AI) */
        private val OFFICE_TYPES = listOf("📨" to "Letter to higher office", "📋" to "Report", "📝" to "Memo", "↩️" to "Endorsement",
            "⚖️" to "Proceedings / Order", "📢" to "Notice", "⚠️" to "Show-cause notice", "✉️" to "Reply to a petition",
            "📎" to "Covering letter", "🗂️" to "Office order", "👥" to "Minutes of meeting", "📊" to "Excel statement")
    }

    private var officeType = ""            // the chosen office document type ("" = let the AI decide)
    private val papers = mutableListOf<Paper>()
    private var addingPaper = false
    private var docsId = ""                // the server keeps the papers until the letter is made
    private var notes = ""                 // what the AI read in the papers
    private var summary = ""               // the matter in 2 or 3 lines, from the papers
    private var showNotes = false
    private var editOffice = false
    private var officeDraft: JSONObject? = null   // the office details being typed (not saved yet)

    private var stage = "start"            // start, planning, questions, making, done
    private var request = ""
    private var letterLang = ""            // "" = same as the request, "en" or "te"
    private var title = ""
    private var format = "docx"
    private var say = ""
    /** Set when Habit's own brain planned this file (Claude couldn't): its skill, sent back when making the file. */
    private var ownSkill = ""
    private val questions = mutableListOf<Q>()
    private val extras = mutableListOf<Q>()
    private val answers = HashMap<String, String>()
    private val chosen = LinkedHashSet<String>()
    private var error = ""
    private var result: JSONObject? = null
    private var fileId = -1L

    private var listeningId = ""           // the field that is listening right now
    private var voiceAll = false           // "Answer all by voice" is running
    private var misses = 0
    private val fields = HashMap<String, EditText>()
    private val micButtons = HashMap<String, Button>()

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })

    /** Called when the pet hears "make a letter…": start with this request. */
    fun start(req: String, mode: String? = null) {
        if (mode != null) {
            // a fresh letter from another page: the right helper, and none of the papers of an earlier letter
            if (store.letterMode != mode) { m.stopListening(); listeningId = ""; store.letterMode = mode }
            papers.clear(); docsId = ""; notes = ""; summary = ""
        }
        request = req.trim(); result = null; error = ""
        if (request.length >= 3) plan() else { stage = "start"; m.render() }
    }

    fun build() {
        fields.clear(); micButtons.clear()
        when (stage) {
            "planning" -> m.add(busy(if (papers.isNotEmpty()) "📄 Reading your papers and planning the questions… (up to a minute)" else "🐾 Thinking about what your letter needs…"), 12)
            "making" -> m.add(busy("✍️ Writing your letter… this can take up to a minute."), 12)
            "questions" -> buildQuestions()
            "done" -> buildDone()
            else -> buildStart()
        }
    }

    private fun busy(text: String) = ui.card(Ui.TEAL_SOFT).apply {
        addView(ui.text(text, 16f, Ui.TEAL_DARK, bold = true))
        put(ui.text("You can keep using your phone. I'll be ready soon.", 13f, Ui.MUTED), 4)
    }

    // ---------------- 1. what do you need? ----------------
    private val office get() = store.letterMode == "office"

    private fun buildStart() {
        // the switch at the top: Public or Office
        m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(modeTab("🙋 Public · ప్రజలు", "Applications and letters", !office) { setMode("public") }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(modeTab("🏢 Office · కార్యాలయం", "Office letters and reports", office) { setMode("office") },
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = m.dp(8) })
        }, 12)
        if (office) buildOfficeStart() else buildPublicStart()
    }

    private fun modeTab(title: String, sub: String, on: Boolean, onClick: () -> Unit) = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; setPadding(m.dp(14), m.dp(12), m.dp(14), m.dp(12))
        background = if (on) ui.round(Ui.TEAL, 18) else ui.round(Ui.SURFACE, 18, Ui.LINE)
        addView(ui.text(title, 15f, if (on) Color.WHITE else Ui.INK, bold = true))
        addView(ui.text(sub, 12.5f, if (on) Color.parseColor("#DDFFFFFF") else Ui.MUTED))
        contentDescription = title
        setOnClickListener { onClick() }
    }

    /** Home's Office box: the Office side of the letter helper, with [type] already chosen ("" = let the AI decide). */
    fun openOffice(type: String) {
        m.stopListening(); listeningId = ""
        store.letterMode = "office"; error = ""
        if (stage == "start") officeType = type
    }

    private fun setMode(mode: String) {
        if (store.letterMode == mode) return
        m.stopListening(); listeningId = ""
        store.letterMode = mode; error = ""; m.render()
    }

    private fun buildPublicStart() {
        m.add(ui.section("✨ Letter helper · లేఖ సహాయకుడు",
            "Tell me what you need. I'll ask each detail, you answer by typing or by voice 🎤, and I'll make a complete letter. Easy for everyone, even elders.").apply {
            val box = ui.input("What do you need? e.g. \"Leave letter for 2 days\"", request, multi = true)
            box.onText { request = it }
            fields["request"] = box
            put(box, 12)
            put(ui.row(mic("request", box, append = true)), 8)
            put(ui.text("Or tap one:", 13f, Ui.MUTED), 14)
            val ideas = listOf("Leave letter", "Application", "Complaint letter", "Request letter", "Bank letter", "Excel list")
            ideas.chunked(2).forEach { row ->
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, s ->
                        addView(ui.chip(s, false) { request = s; plan() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                    }
                }, 6)
            }
            put(ui.text("📎 Related papers (optional)", 14f, Ui.INK, bold = true), 16)
            papersBlock(this)
            languageChips(this)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
            put(ui.button("➜ Next: ask me the details", "primary") { plan() }, 14)
            put(ui.button("I wrote everything: make it now, no questions", "ghost") { direct() }, 10)
        }, 12)
    }

    private fun buildOfficeStart() {
        m.add(ui.section("🏢 Office letters · కార్యాలయ లేఖలు",
            "Letters, reports, memos, proceedings and notices in office format. Add the petition or the earlier letter, and I'll read it first.").apply {
            put(ui.text("1. What do you want to prepare?", 14f, Ui.INK, bold = true), 12)
            OFFICE_TYPES.chunked(2).forEach { row ->
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, (icon, name) ->
                        addView(ui.chip("$icon $name", officeType == name) { officeType = if (officeType == name) "" else name; m.render() },
                            LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = m.dp(6) })
                    }
                }, 6)
            }
            put(ui.text("2. What is the matter?", 14f, Ui.INK, bold = true), 16)
            val box = ui.input("e.g. \"Report to the RDO on a petition for name correction in the land records\"", request, multi = true)
            box.onText { request = it }
            fields["request"] = box
            put(box, 8)
            put(ui.row(mic("request", box, append = true)), 8)
            put(ui.text("3. Related papers (optional)", 14f, Ui.INK, bold = true), 16)
            papersBlock(this)
            languageChips(this)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
            put(ui.button("➜ Next: ask me the details", "primary") { plan() }, 14)
        }, 12)
        officeCard()
        m.add(ui.text("Papers you add are read by Claude AI only to write this document, and kept on the Habit server only until it is made (at most 6 hours). " +
            "\"Hide personal details\" doesn't cover photos and PDFs. Don't add secret or classified papers, and follow your office's rules on using AI tools.", 12f, Ui.MUTED), 10)
    }

    private fun languageChips(sec: LinearLayout) {
        sec.put(ui.text("Letter language", 14f, Ui.INK, bold = true), 16)
        sec.put(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("" to "Auto", "en" to "English", "te" to "తెలుగు").forEachIndexed { i, (k, l) ->
                addView(ui.chip(l, letterLang == k) { letterLang = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
            }
        }, 6)
    }

    // ---------------- the office's own details (saved on this phone) ----------------
    private fun officeLine(o: JSONObject) = listOf(o.optString("desig"), o.optString("office"), o.optString("district")).filter { it.isNotBlank() }.joinToString(", ")

    private fun officeCard() {
        val o = store.officeInfo
        val line = officeLine(o)
        m.add(ui.section("🏢 Your office · మీ కార్యాలయం", "Saved only on this phone. Used for the From line, the header and the signature, so you don't type them every time.").apply {
            if (!editOffice && line.isNotBlank()) {
                val more = listOf(o.optString("name").takeIf { it.isNotBlank() }?.let { "Officer: $it" },
                    o.optString("ref").takeIf { it.isNotBlank() }?.let { "Rc.No. style: $it" }, o.optString("contact").takeIf { it.isNotBlank() }).filterNotNull()
                put(ui.text(line, 15f, Ui.INK, bold = true), 10)
                if (more.isNotEmpty()) put(ui.text(more.joinToString("\n"), 13.5f, Ui.MUTED), 2)
                put(ui.row(ui.button("✏️ Change") { officeDraft = JSONObject(o.toString()); editOffice = true; m.render() }), 8)
                return@apply
            }
            val d = officeDraft ?: JSONObject(o.toString()).also { officeDraft = it }
            fun field(key: String, hint: String) = put(ui.input(hint, d.optString(key)).apply { onText { d.put(key, it.trim()) } }, 8)
            field("desig", "Designation, e.g. Tahsildar")
            field("office", "Office, e.g. Tahsildar Office, ____ Mandal")
            field("district", "District, e.g. Visakhapatnam District")
            field("name", "Officer's name (optional)")
            field("ref", "Rc.No. style (optional), e.g. Rc.No.B")
            field("contact", "Office phone or email (optional)")
            put(ui.row(ui.button("Save", "primary") {
                if (d.optString("desig").isBlank() || d.optString("office").isBlank()) { m.toast("Write at least the designation and the office."); return@button }
                store.officeInfo = d; officeDraft = null; editOffice = false; m.toast("Office details saved ✓"); m.render()
            }, ui.button("Cancel", "ghost") { officeDraft = null; editOffice = false; m.render() }), 10)
        }, 12)
    }

    // ---------------- papers about the matter ----------------
    private fun papersBlock(sec: LinearLayout) {
        sec.put(ui.text("Add the petition, an earlier letter, an order or any paper about the matter. I'll read it to understand.", 12.5f, Ui.MUTED), 4)
        sec.put(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(ui.button("📷 Camera") { camera() }, ui.button("🖼️ Photos") { pickPhotos() }, ui.button("📄 PDF") { pickPdf() })
                .forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = m.dp(6) }) }
        }, 8)
        papers.forEachIndexed { i, p -> sec.put(paperRow(p, i), 8) }
        if (addingPaper) sec.put(ui.text("⏳ Getting the paper ready…", 13f, Ui.MUTED), 6)
        if (papers.isNotEmpty()) sec.put(ui.text("${papers.size} of $MAX_PAPERS papers · ${kb(papers.sumOf { it.data.size })} · read by Claude AI only for this letter, " +
            "and deleted from the Habit server after it is made (at most 6 hours)", 12f, Ui.MUTED), 4)
    }

    private fun kb(n: Int) = if (n >= 1024 * 1024) String.format(java.util.Locale.ENGLISH, "%.1f MB", n / 1048576f) else "${maxOf(1, n / 1024)} KB"

    private fun paperRow(p: Paper, i: Int) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(m.dp(8), m.dp(8), m.dp(8), m.dp(8)); background = ui.round(Ui.FIELD, 14, Ui.LINE)
        if (p.thumb != null) addView(ImageView(m).apply { setImageBitmap(p.thumb); scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(Ui.LINE, 8); clipToOutline = true },
            LinearLayout.LayoutParams(m.dp(46), m.dp(46)))
        else addView(TextView(m).apply { text = "📄"; textSize = 26f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(m.dp(46), m.dp(46)))
        addView(ui.stack(ui.text(p.name, 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
            ui.text((if (p.mime == "application/pdf") "PDF" else "Photo") + " · " + kb(p.data.size), 12f, Ui.MUTED)),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = m.dp(10) })
        addView(ui.button("✕", "ghost") { papers.removeAt(i); docsId = ""; m.render() }.apply { contentDescription = "Remove ${p.name}" })
    }

    private fun roomFor(): Int {
        val left = MAX_PAPERS - papers.size
        if (left <= 0) m.toast("You can add up to $MAX_PAPERS papers. Remove one first.")
        return left
    }

    private fun camera() {
        if (roomFor() <= 0) return
        val file = CameraFiles.file(m)
        file.parentFile?.mkdirs(); file.delete()
        val uri = CameraFiles.uri(m)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        m.startCamera(i, MainActivity.REQ_LETTER_CAMERA, "No camera app found. Tap 🖼️ Photos instead.")
    }

    private fun pickPhotos() {
        val left = roomFor(); if (left <= 0) return
        m.leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").apply {
                if (left > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(left, MediaStore.getPickImagesMaxLimit()))
            }
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, left > 1)
        try { m.startActivityForResult(i, MainActivity.REQ_LETTER_PHOTOS) }
        catch (e: Exception) {
            try { m.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), MainActivity.REQ_LETTER_PHOTOS) }
            catch (e2: Exception) { m.leavingForSystemScreen = false; m.toast("Couldn't open your photos.") }
        }
    }

    private fun pickPdf() {
        if (roomFor() <= 0) return
        m.leavingForSystemScreen = true
        try { m.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), MainActivity.REQ_LETTER_PDF) }
        catch (e: Exception) {
            try { m.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), MainActivity.REQ_LETTER_PDF) }
            catch (e2: Exception) { m.leavingForSystemScreen = false; m.toast("Couldn't open your files.") }
        }
    }

    /** The photo just taken with the camera. */
    fun onCamera() {
        val f = CameraFiles.file(m)
        if (!f.exists() || f.length() == 0L) { m.toast("No photo was taken."); return }
        addPhotos(listOf(Uri.fromFile(f)))
    }

    fun onPhotos(list: List<Uri>) = addPhotos(list.take(maxOf(0, MAX_PAPERS - papers.size)))

    private fun addPhotos(list: List<Uri>) {
        if (list.isEmpty()) return
        addingPaper = true; m.render()
        m.io.execute {
            val made = list.mapNotNull { u -> Images.prepare(m, u, 1568) }
            try { CameraFiles.file(m).delete() } catch (e: Exception) { }
            m.runOnUiThread {
                addingPaper = false
                var n = papers.count { it.mime != "application/pdf" }
                for (b in made) {
                    if (papers.size >= MAX_PAPERS) break
                    if (papers.sumOf { it.data.size } + b.size > MAX_ALL) { m.toast("That's too much together. Remove a paper first."); break }
                    papers.add(Paper("Photo ${++n}.jpg", "image/jpeg", b, thumbOf(b))); docsId = ""
                }
                if (made.size < list.size) m.toast("Couldn't open some of the photos.")
                m.render()
            }
        }
    }

    private fun thumbOf(b: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 8 })
    } catch (e: Throwable) { null }

    fun onPdf(uri: Uri) {
        if (papers.size >= MAX_PAPERS) return
        addingPaper = true; m.render()
        m.io.execute {
            var name = "Document.pdf"
            try { m.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } } } catch (e: Exception) { }
            val bytes: ByteArray? = try {
                m.contentResolver.openInputStream(uri)?.use { inp ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0
                    while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > MAX_PDF) return@use null; out.write(buf, 0, n) }
                    out.toByteArray()
                }
            } catch (e: Exception) { null }
            m.runOnUiThread {
                addingPaper = false
                when {
                    bytes == null -> m.toast("This PDF is too big (more than 8 MB) or couldn't be opened. Take photos of the pages instead.")
                    bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-" -> m.toast("That file isn't a PDF.")
                    papers.sumOf { it.data.size } + bytes.size > MAX_ALL -> m.toast("That's too much together. Remove a paper first.")
                    else -> { papers.add(Paper(name, "application/pdf", bytes, null)); docsId = "" }
                }
                m.render()
            }
        }
    }

    private fun plan() {
        val typed = request.trim().length >= 3
        if (!typed && !(office && officeType.isNotEmpty()) && papers.isEmpty()) {
            m.toast(if (office) "Choose what to prepare, or write the matter, first." else "Tell me what you need first, for example \"leave letter\"."); return
        }
        if (addingPaper) { m.toast("Wait a moment: the paper is still getting ready."); return }
        m.stopListening(); stage = "planning"; error = ""; m.render()
        val body = JSONObject().put("request", Privacy.maybe(store, request)).put("lang", store.lang).put("mode", store.letterMode)
        if (office) { if (officeType.isNotEmpty()) body.put("docType", officeType); body.put("office", store.officeInfo) }
        if (papers.isNotEmpty()) body.put("docs", JSONArray().apply {
            papers.forEach { p -> put(JSONObject().put("name", p.name).put("mediaType", p.mime).put("data", Base64.encodeToString(p.data, Base64.NO_WRAP))) }
        })
        val withPapers = papers.isNotEmpty()
        m.io.execute {
            val r = Net.post(store, "/api/letter/plan", body, emptyMap(), if (withPapers) 180_000 else 90_000)
            m.runOnUiThread {
                if (!r.ok) { stage = "start"; error = r.error("Couldn't start. Please try again."); m.render(); return@runOnUiThread }
                val j = r.json
                title = j.optString("title", "Letter"); format = j.optString("format", "docx"); say = j.optString("say", "")
                ownSkill = if (j.optBoolean("own")) j.optString("skill") else ""
                summary = j.optString("summary"); notes = j.optString("notes"); docsId = j.optString("docsId"); showNotes = false
                questions.clear(); extras.clear(); answers.clear(); chosen.clear(); misses = 0
                j.optJSONArray("questions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                    questions.add(Q(o.optString("id", "q$i"), o.optString("q"), o.optString("hint"), o.optBoolean("required", true))) } }
                j.optJSONArray("extras")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                    extras.add(Q("x_" + o.optString("id", "e$i"), o.optString("q"), o.optString("hint"), false, o.optString("label"))) } }
                stage = "questions"; m.render(); m.scrollTop()
                if (say.isNotBlank() && store.voiceOn) m.speakOut(say)
            }
        }
    }

    // ---------------- 2. the questions ----------------
    /** A small note when Habit's own brain (not Claude) is making the file. */
    private fun ownNote(done: Boolean) {
        val te = store.lang == "te"
        m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text(if (te) "🧠 Habit సొంత మెదడు" else "🧠 Habit's own brain", 15f, Ui.TEAL_DARK, bold = true))
            put(ui.text(if (done) (if (te) "ఈ ఫైల్‌ను Habit తను నేర్చుకున్న ఫార్మాట్‌తో సొంతంగా తయారు చేసింది. పంపే ముందు ఒకసారి చదవండి."
                    else "Habit made this file by itself, from a format it has learned. Please read it once before you send it.")
                else (if (te) "Claude ఇప్పుడు అందుబాటులో లేదు, అందుకే Habit తను నేర్చుకున్న ఫార్మాట్‌తో ఈ ఫైల్ తయారు చేస్తుంది."
                    else "Claude can't help right now, so Habit makes this file by itself, from a format it has learned."), 13.5f, Ui.INK), 4)
        }, 12)
    }

    private fun buildQuestions() {
        if (ownSkill.isNotBlank()) ownNote(false)
        m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("📝 $title", 19f, Ui.INK, bold = true))
            if (say.isNotBlank()) put(ui.text(say, 14f, Ui.MUTED), 4)
            put(if (voiceAll) ui.button("■ Stop voice answers", "danger") { stopVoiceAll() }
                else ui.button("🎙 Answer all by voice (I'll read each question)", "sun") { startVoiceAll() }, 12)
            put(ui.text("Or type, or tap 🎤 next to any question. Tap 🔊 to hear a question.", 12.5f, Ui.MUTED), 6)
        }, 12)

        if (summary.isNotBlank() || notes.isNotBlank()) m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("📄 What I understood from your papers", 15f, Ui.TEAL_DARK, bold = true))
            if (summary.isNotBlank()) put(ui.text(summary, 14.5f, Ui.INK), 6)
            if (notes.isNotBlank()) {
                if (showNotes) put(ui.text(notes, 13.5f, Ui.INK).apply { setTextIsSelectable(true) }, 8)
                put(ui.row(ui.button(if (showNotes) "Hide the details" else "Show the details I found", "ghost") { showNotes = !showNotes; m.render() }), 8)
            }
            put(ui.text("Something wrong? Correct it in \"Anything else to add?\" below.", 12f, Ui.MUTED), 6)
        }, 12)

        m.add(ui.section("Details · వివరాలు").apply {
            questions.forEachIndexed { i, q -> question(this, "${i + 1}. ${q.q}", q) }
        }, 12)

        if (extras.isNotEmpty()) m.add(ui.section("➕ Make it better (optional)", "Tick what you want to add to the letter.").apply {
            extras.forEach { x ->
                put(ui.check(x.label, x.id in chosen) { on -> if (on) chosen.add(x.id) else chosen.remove(x.id); m.render() }, 6)
                if (x.id in chosen) question(this, x.q, x)
            }
        }, 12)

        m.add(ui.section("Anything else to add?").apply {
            val note = ui.input("Optional. For example: \"mention that I will make up the work\"", answers["note"] ?: "", multi = true)
            note.onText { answers["note"] = it }
            fields["note"] = note
            put(note, 8)
            put(ui.row(mic("note", note, append = true)), 8)
        }, 12)

        if (error.isNotBlank()) m.add(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 10)
        m.add(ui.button("✨ Make my letter", "primary") { make() }, 14)
        m.add(ui.button("↺ Start over", "ghost") { reset() }, 8)
    }

    private fun question(box: LinearLayout, label: String, q: Q) {
        val active = listeningId == q.id
        box.put(ui.line(ui.text(label, 15f, if (active) Ui.TEAL_DARK else Ui.INK, bold = true),
            ui.button("🔊", "ghost") { m.speakOut(q.q) }), 14)
        val input = ui.input(if (q.hint.isNotBlank()) "e.g. ${q.hint}" else "Type or tap 🎤", answers[q.id] ?: "", multi = true)
        if (active) input.background = ui.round(Ui.TEAL_SOFT, 14, Ui.TEAL)
        input.onText { answers[q.id] = it }
        fields[q.id] = input
        box.put(input, 6)
        box.put(ui.row(mic(q.id, input, append = false)), 6)
    }

    /** 🎤 button that fills [input] with what you say (live while you speak). */
    private fun mic(id: String, input: EditText, append: Boolean): Button {
        val b = ui.button(if (listeningId == id) "● Listening… tap to stop" else "🎤 Speak", if (listeningId == id) "danger" else "soft") {
            if (listeningId == id) { voiceAll = false; m.stopListening(); listeningId = ""; m.render(); return@button }
            if (voiceAll) stopVoiceAll()
            listenInto(id, input, append, continuous = true) { }
        }
        micButtons[id] = b
        return b
    }

    /** Fills [input] with what you say. [continuous] = keep listening through pauses until you tap stop. */
    private fun listenInto(id: String, input: EditText, append: Boolean, continuous: Boolean = false, after: (Boolean) -> Unit) {
        val before = if (append) input.text.toString().trim() else ""
        fun join(s: String) = if (before.isEmpty()) s else "$before $s"
        listeningId = id
        micButtons[id]?.let { it.text = "● Listening… tap to stop"; it.background = ui.round(Ui.DANGER_SOFT, 24); it.setTextColor(Ui.DANGER) }
        input.background = ui.round(Ui.TEAL_SOFT, 14, Ui.TEAL)
        m.listen(onPartial = { p -> (fields[id] ?: input).let { it.setText(join(p)); it.setSelection(it.text.length) } }, onDone = { heard ->
            if (listeningId != id) return@listen
            listeningId = ""
            val ok = !heard.isNullOrBlank()
            if (ok) { val v = join(heard!!); answers[id] = v; if (id == "request") request = v }
            m.render()
            after(ok)
        }, continuous = continuous)
    }

    // ---------------- answer all by voice ----------------
    private fun asked() = questions + extras.filter { it.id in chosen }

    private fun startVoiceAll() {
        voiceAll = true; misses = 0
        voiceNext(0)
    }

    private fun stopVoiceAll() {
        voiceAll = false; m.stopListening(); m.stopTalking(); listeningId = ""; m.render()
    }


    private fun voiceNext(from: Int) {
        if (!voiceAll || stage != "questions") return
        val list = asked()
        val i = (from until list.size).firstOrNull { answers[list[it].id].isNullOrBlank() }
        if (i == null) {
            voiceAll = false; listeningId = ""; m.render()
            m.speakOut(if (store.lang == "en") "Thank you! Now tap Make my letter." else "ధన్యవాదాలు! ఇప్పుడు Make my letter నొక్కండి.")
            return
        }
        val q = list[i]
        listeningId = q.id; m.render()
        fields[q.id]?.let { m.scrollToView(it) }
        m.speakOut(q.q) {
            if (!voiceAll) return@speakOut
            val input = fields[q.id] ?: return@speakOut
            listenInto(q.id, input, append = false) { ok ->
                if (!voiceAll) return@listenInto
                if (ok) { misses = 0; voiceNext(i + 1) }
                else if (++misses >= 2) { misses = 0; voiceNext(i + 1) }   // skip it after two tries
                else voiceNext(i)
            }
        }
    }

    // ---------------- 3. make the letter ----------------
    private fun make() {
        val missing = questions.filter { it.required && answers[it.id].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            AlertDialog.Builder(m)
                .setTitle("Some answers are empty")
                .setMessage(missing.joinToString("\n") { "• " + it.q } + "\n\nI'll write the letter without them. Or go back and answer them.")
                .setPositiveButton("Make it anyway") { _, _ -> doMake() }
                .setNegativeButton("Answer them", null).show()
            return
        }
        doMake()
    }

    private fun doMake() {
        voiceAll = false; m.stopListening(); m.stopTalking(); listeningId = ""
        stage = "making"; error = ""; m.render(); m.scrollTop()
        val ans = JSONArray(); questions.forEach { q -> answers[q.id]?.takeIf { it.isNotBlank() }?.let { ans.put(JSONObject().put("id", q.id).put("q", q.q).put("a", Privacy.maybe(store, it))) } }
        val ex = JSONArray(); extras.filter { it.id in chosen }.forEach { q -> answers[q.id]?.takeIf { it.isNotBlank() }?.let { ex.put(JSONObject().put("label", q.label).put("q", q.q).put("a", Privacy.maybe(store, it))) } }
        val body = JSONObject().put("request", Privacy.maybe(store, request.ifBlank { officeType })).put("title", title).put("format", format)
            .put("lang", letterLang.ifEmpty { store.lang }).put("answers", ans).put("extras", ex)
            .put("note", Privacy.maybe(store, answers["note"] ?: "")).put("mode", store.letterMode)
        if (office) { if (officeType.isNotEmpty()) body.put("docType", officeType); body.put("office", store.officeInfo) }
        if (notes.isNotBlank()) body.put("notes", notes)
        if (docsId.isNotBlank()) body.put("docsId", docsId)
        if (ownSkill.isNotBlank()) body.put("skill", ownSkill)
        m.io.execute {
            val r = Net.post(store, "/api/letter/make", body, timeoutMs = 150_000)
            m.runOnUiThread {
                if (!r.ok || !r.json.optString("url").startsWith(store.server + "/files/")) {
                    stage = "questions"; error = r.error("Couldn't write the letter. Please try again."); m.render(); return@runOnUiThread
                }
                result = r.json
                fileId = Actions(m) { _, _ -> }.downloadFile(r.json.optString("url"), r.json.optString("name", "Letter.docx")) ?: -1L
                stage = "done"; m.render(); m.scrollTop()
                val s = r.json.optString("say")
                if (s.isNotBlank() && store.voiceOn) m.speakOut(s)
            }
        }
    }

    private fun direct() {
        if (request.trim().length < 8) { m.toast("Write all the details in the box first."); return }
        stage = "making"; error = ""; m.render()
        val acts = Actions(m) { t, body -> AlertDialog.Builder(m).setTitle(t).setMessage(body).setPositiveButton("OK", null).show() }
        Brain(store).ask("$request (make it as a file)", onReply = { json ->
            val list = json.optJSONArray("actions")
            var made = false
            if (list != null) for (i in 0 until list.length()) { val a = list.optJSONObject(i) ?: continue; if (a.optString("type") == "download") made = true; acts.run(a) }
            stage = "start"
            if (made) { request = ""; m.toast("✅ Your file is downloading. It's in the list below.") } else error = json.optString("reply")
            m.render()
        }, onError = { err -> stage = "start"; error = err; m.render() }, local = false)
    }

    // ---------------- 4. ready ----------------
    private fun buildDone() {
        val r = result ?: run { stage = "start"; buildStart(); return }
        val name = r.optString("name", "Letter")
        val xl = r.optString("format") == "xlsx"
        if (r.optBoolean("own")) ownNote(true)
        m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("✅ Your ${if (xl) "Excel file" else "letter"} is ready!", 19f, Ui.TEAL_DARK, bold = true))
            put(ui.text("$name · saved in Downloads › Habit AI", 13f, Ui.MUTED), 4)
            put(ui.row(ui.button("Open", "primary") { open(xl) }, ui.button("Share") { share(xl) }), 12)
        }, 12)
        val preview = r.optString("preview")
        if (preview.isNotBlank()) m.add(ui.section("Preview").apply {
            put(ui.text(preview, 14.5f, Ui.INK).apply { setTextIsSelectable(true); setPadding(m.dp(12), m.dp(12), m.dp(12), m.dp(12)); background = ui.round(Ui.FIELD, 12, Ui.LINE) }, 10)
        }, 12)
        m.add(ui.button("✏️ Change my answers and make it again") { stage = "questions"; result = null; m.render(); m.scrollTop() }, 12)
        m.add(ui.button("➕ New letter", "ghost") { reset() }, 8)
        m.add(ui.text("🚩 Something wrong or offensive in this letter? Report it", 13f, Ui.MUTED).apply {
            setPadding(0, m.dp(6), 0, m.dp(6)); setOnClickListener { m.reportAi("letter helper", preview.ifBlank { name }, request) }
        }, 14)
    }

    private fun mime(xl: Boolean) = if (xl) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun uri() = if (fileId < 0) null else try { m.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(fileId) } catch (e: Exception) { null }

    private fun open(xl: Boolean) {
        val u = uri() ?: run { m.toast("Still downloading… try again in a moment."); return }
        try { m.leavingForSystemScreen = true; m.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime(xl)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { m.toast("Install Microsoft Word or Google Docs to open it.") }
    }

    private fun share(xl: Boolean) {
        val u = uri() ?: run { m.toast("Still downloading… try again in a moment."); return }
        m.leavingForSystemScreen = true
        m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(xl)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share your letter"))
    }

    private fun reset() {
        m.stopListening(); m.stopTalking()
        stage = "start"; request = ""; result = null; error = ""; ownSkill = ""; questions.clear(); extras.clear(); answers.clear(); chosen.clear()
        voiceAll = false; listeningId = ""
        papers.clear(); docsId = ""; notes = ""; summary = ""; officeType = ""; showNotes = false
        m.render(); m.scrollTop()
    }

    fun stop() { if (voiceAll || listeningId.isNotEmpty()) { voiceAll = false; listeningId = ""; m.stopListening(); m.stopTalking() } }
}

/** Calls [f] with the new text whenever the box changes. */
fun EditText.onText(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
})
