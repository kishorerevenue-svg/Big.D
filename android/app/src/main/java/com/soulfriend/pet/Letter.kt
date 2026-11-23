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
        /** The letter helper last drawn; the pet gives it what it hears while its questions are on the screen. */
        @Volatile var showing: LetterHelper? = null
    }

    private var officeType = ""            // the chosen office document type ("" = let the AI decide)
    private val papers = mutableListOf<Paper>()
    // an example letter whose FORMAT to follow (kept on this phone for the next letters too)
    private var forExample = false
    private var example: Paper? = null
    private var exampleLoaded = false
    private fun exampleFile() = java.io.File(m.filesDir, "letter-example.bin")
    private fun loadExample() {
        if (exampleLoaded) return
        exampleLoaded = true
        try {
            val f = exampleFile(); val name = store.exampleName
            if (f.exists() && name.isNotBlank()) { val b = f.readBytes(); example = Paper(name, store.exampleMime, b, if (store.exampleMime == "application/pdf") null else thumbOf(b)) }
        } catch (e: Exception) { }
    }
    private fun setExample(p: Paper?) {
        example = p; exampleLoaded = true
        try { if (p == null) { exampleFile().delete(); store.exampleName = "" } else { exampleFile().writeBytes(p.data); store.exampleName = p.name; store.exampleMime = p.mime } } catch (e: Exception) { }
    }
    private var addingPaper = false
    private var docsId = ""                // the server keeps the papers until the letter is made
    private var notes = ""                 // what the AI read in the papers
    private var summary = ""               // the matter in 2 or 3 lines, from the papers
    private var showNotes = false
    private var editOffice = false
    private var officeDraft: JSONObject? = null   // the office details being typed (not saved yet)

    private var stage = "start"            // start, planning, questions, making, drafting, rough, finishing, done
    private val pieces = mutableListOf<String>()   // what they wrote about the matter, added piece by piece (➕ Add)
    private var rough: JSONObject? = null   // the rough copy: title, format, content, lines
    private var change = ""                 // what to change in the rough copy
    private var editingAt = 0L              // the "My letters" entry being changed (0: a new letter)
    private var request = ""
    private var letterLang = m.store.letterLang   // "" = same as the request, "en" or "te" (remembered)
    private var speakLang = ""             // the language the answers are spoken in ("" = the letter's language)
    private var ownWhy = ""                // why Habit's own brain makes it: limit, resting, busy, nokey, own
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
        result = null; error = ""
        if (stage != "start" || rough != null) { pieces.clear(); rough = null; change = ""; editingAt = 0L; questions.clear(); answers.clear() }
        if (req.trim().length >= 3) pieces.add(req.trim())
        request = ""; stage = "start"; m.render(); m.scrollTop()
        if (store.voiceOn && req.trim().length >= 3) m.speakOut(if (store.lang == "en") "Add all the details in the box, and the papers if any. Then tap Prepare the rough copy."
            else "వివరాలన్నీ బాక్స్‌లో చెప్పండి, పత్రాలు ఉంటే జోడించండి. తర్వాత రఫ్ కాపీ నొక్కండి.")
    }

    /** A letter is half filled (its questions on the screen, some answered). */
    fun inProgress() = (stage == "questions" && answers.values.any { it.isNotBlank() }) || stage == "rough" || (stage == "start" && pieces.isNotEmpty())

    /**
     * The pet heard [text] while this letter's questions are on the screen: it goes into the answer being filled (the one
     * listening, else the first empty one, else "Anything else"), not into a new letter. Returns where it went, or null.
     */
    fun takeVoice(text: String): String? {
        if (!m.resumedNow || m.tab != "files" || text.isBlank()) return null
        if (stage == "rough") { change = (change.trim() + " " + text.trim()).trim(); m.render(); fields["change"]?.let { m.scrollToView(it) }; return "What to change" }
        if (stage == "start" && pieces.isNotEmpty()) { request = (request.trim() + " " + text.trim()).trim(); m.render(); fields["request"]?.let { m.scrollToView(it) }; return "The matter" }
        if (stage != "questions") return null
        val list = asked()
        val q = list.firstOrNull { it.id == listeningId } ?: list.firstOrNull { answers[it.id].isNullOrBlank() }
        val id = q?.id ?: "note"
        val old = answers[id].orEmpty().trim()
        answers[id] = if (old.isEmpty()) text.trim() else "$old ${text.trim()}"
        m.render()
        fields[id]?.let { m.scrollToView(it) }
        return q?.q ?: "Anything else to add?"
    }

    /** The pet's voice typing language while this letter's questions are on the screen, or null. */
    fun petLang(): String? = if (m.resumedNow && m.tab == "files" && (stage == "questions" || stage == "rough")) voiceLang() else null

    /** Voice typing's language: the one chosen for speaking, else the letter's ("English" letter: English words, not Telugu letters). */
    private fun voiceLang(): String = if (speakLang.ifEmpty { letterLang.ifEmpty { store.lang } } == "en") "en-IN" else "te-IN"

    fun build() {
        showing = this
        fields.clear(); micButtons.clear()
        when (stage) {
            "planning" -> m.add(busy(if (papers.isNotEmpty()) "📄 Reading your papers and planning the questions… (up to a minute)" else "🐾 Thinking about what your letter needs…"), 12)
            "making" -> m.add(busy("✍️ Writing your letter… this can take up to a minute."), 12)
            "drafting" -> m.add(busy(if (papers.isNotEmpty() || docsId.isNotBlank()) "📄 Reading your papers and writing the rough copy… (up to a minute)" else "✍️ Writing the rough copy… (up to a minute; when Habit's own brain writes it, 2–4 minutes)"), 12)
            "finishing" -> m.add(busy("📄 Making the final Word file…"), 12)
            "rough" -> buildRough()
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

    /** "📥 My letters (n)" on top: goes to the downloaded letters (each can be opened, shared or changed). */
    private fun lettersButton() {
        val n = store.letters.length()
        if (n == 0) return
        m.add(ui.button("📂 Your letters & files · మీ లేఖలు ($n)", "soft") { lettersView?.let { m.scrollToView(it) } }, 8)
    }
    private var lettersView: View? = null
    private var justMade = 0L                    // the letter just made: shown first, marked ✅

    private fun buildPublicStart() {
        lettersButton()
        m.library.box()                        // 📚 the public library and 📁 my files
        m.add(ui.section("✨ Letter helper · లేఖ సహాయకుడు",
            "Write or speak 🎤 everything in one box (➕ Add more as many times as you need). I'll write a rough copy with line numbers; tell me what to change, then get the final Word file.").apply {
            matterBlock(this, "What do you need? Write everything about it here, e.g. \"Leave letter for 2 days, from 18-11 to 19-11, for my sister's marriage\"")
            put(ui.text("Or tap one:", 13f, Ui.MUTED), 14)
            val ideas = listOf("Leave letter", "Application", "Complaint letter", "Request letter", "Bank letter", "Excel list")
            ideas.chunked(2).forEach { row ->
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, s ->
                        addView(ui.chip(s, false) { request = (s + ": " + request).trim().trimEnd(':'); m.render(); fields["request"]?.let { b -> b.requestFocus(); (b as? EditText)?.setSelection(b.text.length) } }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                    }
                }, 6)
            }
            put(ui.text("📎 Related papers (optional)", 14f, Ui.INK, bold = true), 16)
            papersBlock(this)
            exampleBlock(this)
            languageChips(this)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
            put(ui.button("✨ Prepare the rough copy · రఫ్ కాపీ తయారు చేయి", "primary") { draft() }, 14)
        }, 12)
    }

    // ---------- the owner's old office files: find by Sy.No., village or name (only on the owner's phone) ----------
    private var oldQ = ""
    private var oldFound: org.json.JSONArray? = null
    private var oldBusy = false
    private var oldAnswer = ""                   // Habit's own words about what the files say ("" while thinking or none)
    private var oldThinking = false
    private fun oldFilesBox() {
        if (store.teamKey.isBlank() && store.officeCode.isBlank()) return
        m.add(ui.section("🔎 Old files · పాత ఫైళ్లు", "Find every old letter about a Sy.No., village or name, with dates. Only for your office.").apply {
            val box = ui.input("Sy.No.165 Chinna Musidivada", oldQ).apply { setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            fun go() { oldQ = box.text.toString().trim(); if (oldQ.length >= 2) searchOld() }
            box.setOnEditorActionListener { _, _, _ -> go(); true }
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(box, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button(if (oldBusy) "…" else "🔎", "primary") { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = m.dp(8) })
            }, 8)
            val f = oldFound
            if (f != null && !oldBusy && f.length() > 0 && (oldThinking || oldAnswer.isNotBlank())) put(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(m.dp(14), m.dp(12), m.dp(14), m.dp(12)); background = ui.round(Ui.TEAL_SOFT, 14, Ui.LINE)
                addView(ui.text("🧠 What Habit found · హ్యాబిట్ చెప్పేది", 14f, Ui.TEAL_DARK, bold = true))
                addView(ui.text(if (oldThinking) "Reading the files… · ఫైళ్లు చదువుతున్నాను…" else oldAnswer, 14f, Ui.INK).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(6) })
            }, 8)
            if (f != null && !oldBusy) {
                if (f.length() == 0) put(ui.text("Nothing found for \"$oldQ\".", 13f, Ui.MUTED), 8)
                else put(ui.text(when {
                        f.optJSONObject(0)?.optBoolean("aside") == true -> "Only staff / election / other lists have these words (not land records) · tap one to read"
                        f.optJSONObject(0)?.optBoolean("weak") == true -> "${f.length()} found, but not in the same line (may not be about this) · tap one to read"
                        else -> "${f.length()} found · Sy.No. matches first · tap one to read" }, 12.5f, Ui.MUTED, bold = true), 8)
                for (i in 0 until f.length()) {
                    val o = f.optJSONObject(i) ?: continue
                    put(LinearLayout(m).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(m.dp(12), m.dp(9), m.dp(12), m.dp(9)); background = ui.round(Ui.FIELD, 12, Ui.LINE)
                        addView(ui.text(listOf(o.optString("date"), o.optString("title")).filter { it.isNotBlank() }.joinToString(" · "), 14f, Ui.INK, bold = true))
                        if (o.optString("about").isNotBlank()) addView(ui.text("📌 " + o.optString("about"), 12.5f, Ui.TEAL_DARK, bold = true))
                        if (o.optString("rc").isNotBlank()) addView(ui.text(o.optString("rc"), 12f, Ui.MUTED))
                        val h = o.optJSONArray("hits")
                        if (h != null && h.length() > 0) addView(ui.text(h.optString(0), 12.5f, Ui.INK).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                        setOnClickListener { readOld(o.optString("id"), o.optString("title"), o.optString("about")) }
                    }, 6)
                }
            }
        }, 12)
    }
    private fun searchOld() {
        oldBusy = true; m.render()
        m.io.execute {
            val r = try { Net.post(store, "/api/office/search", JSONObject().put("q", oldQ), owner(), 30_000) } catch (e: Exception) { null }
            m.runOnUiThread {
                oldBusy = false
                if (r != null && r.ok) {
                    oldFound = r.json.optJSONArray("found") ?: org.json.JSONArray()
                    oldAnswer = ""
                    if ((oldFound?.length() ?: 0) > 0 && store.officeCode.isNotBlank()) askOld(oldQ)
                }
                else m.toast(r?.json?.optString("error")?.ifBlank { null } ?: "Couldn't search now. Run the server update and try again.")
                m.render()
            }
        }
    }
    /** Habit reads what was found and says, in plain words, what the files show about it and what each is for. */
    private fun askOld(q: String) {
        oldThinking = true
        m.io.execute {
            val r = try { Net.post(store, "/api/office/ask", JSONObject().put("q", q).put("lang", store.lang), owner(), 90_000) } catch (e: Exception) { null }
            m.runOnUiThread {
                if (q != oldQ) return@runOnUiThread
                oldThinking = false
                oldAnswer = if (r != null && r.ok) r.json.optString("answer").trim() else ""
                m.render()
            }
        }
    }
    private fun readOld(id: String, title: String, about: String = "") {
        m.io.execute {
            val r = try { Net.post(store, "/api/office/read", JSONObject().put("id", id), owner(), 30_000) } catch (e: Exception) { null }
            m.runOnUiThread {
                val text = r?.json?.optString("text").orEmpty()
                if (r == null || !r.ok || text.isBlank()) { m.toast("Couldn't open it."); return@runOnUiThread }
                val v = TextView(m).apply { this.text = text; textSize = 14f; setTextColor(Ui.INK); setTextIsSelectable(true); setPadding(m.dp(18), m.dp(8), m.dp(18), m.dp(8)) }
                android.app.AlertDialog.Builder(m).setTitle(title)
                    .setView(android.widget.ScrollView(m).apply { addView(v) })
                    .setPositiveButton("➕ Use in this letter") { _, _ -> pieces.add("From the old file \"$title\": " + text.take(2500)); m.render(); m.toast("Added to the matter box") }
                    .setNeutralButton("✏️ What is this file?") { _, _ -> tellAbout(id, title, about) }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    /** Someone in the office says what a file is ("GO 388 regularised list", "Election: polling stations, 2024"): Habit keeps it. */
    private fun tellAbout(id: String, title: String, about: String) {
        val box = ui.input("e.g. GO 388 regularisation, approved list, 2024", about)
        android.app.AlertDialog.Builder(m).setTitle("What is \"$title\"?")
            .setMessage("Say it in a few words, so Habit and your staff understand it (what it is for, which GO or scheme, the year). This file only; only your office sees it.")
            .setView(LinearLayout(m).apply { setPadding(m.dp(20), m.dp(4), m.dp(20), 0); addView(box, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ ->
                val a = box.text.toString().trim()
                m.io.execute {
                    val r = try { Net.post(store, "/api/office/about", JSONObject().put("id", id).put("about", a), owner(), 20_000) } catch (e: Exception) { null }
                    m.runOnUiThread {
                        if (r != null && r.ok) {
                            val f = oldFound
                            if (f != null) for (i in 0 until f.length()) f.optJSONObject(i)?.takeIf { it.optString("id") == id }?.put("about", r.json.optString("about"))
                            m.toast("Saved ✓ Habit knows what this file is"); m.render()
                        } else m.toast(r?.json?.optString("error")?.ifBlank { null } ?: "Couldn't save now. Run the server update and try again.")
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- the office space: one person makes it, the staff join with its code, and all share the old files ----------
    private var space: JSONObject? = null        // what the server says about our office (null: not asked yet)
    private var spaceHead = false
    private var spaceFor = ""                    // the code the office was last asked for
    private var spaceBusy = ""                   // "" or what is going on ("Adding 3 of 10…")
    private var spaceForm = ""                   // "" | "create" | "join"
    private var spaceNote = ""
    private fun loadSpace() {
        if (store.officeCode.isBlank()) { space = null; return }
        spaceFor = store.officeCode; spaceNote = ""
        m.io.execute {
            var r = try { Net.post(store, "/api/office/me", JSONObject(), owner(), 20_000) } catch (e: Exception) { null }
            if (r != null && r.code == 403)          // a code saved in Settings: join with it
                r = try { Net.post(store, "/api/office/join", JSONObject().put("code", store.officeCode).put("name", store.officeName.ifBlank { "Staff" }), emptyMap(), 20_000)
                    .let { j -> if (j.ok) Net.post(store, "/api/office/me", JSONObject(), owner(), 20_000) else j } } catch (e: Exception) { null }
            m.runOnUiThread {
                if (r != null && r.ok) { space = r.json.optJSONObject("office"); spaceHead = r.json.optBoolean("head") }
                else { space = null; spaceNote = r?.error("") ?: ""; if (spaceNote.isBlank() || r?.code == 404) spaceNote = "Couldn't open the office now. Run the server update, or check the internet." }
                m.render()
            }
        }
    }
    private fun spacePost(path: String, body: JSONObject, done: (Net.Reply) -> Unit) {
        spaceBusy = "…"; m.render()
        m.io.execute {
            val r = Net.post(store, path, body, owner(), 30_000)
            m.runOnUiThread { spaceBusy = ""; if (!r.ok) m.toast(r.error("Couldn't do it now. Run the server update and try again.")); done(r); m.render() }
        }
    }
    private fun shareCode(code: String, name: String) {
        val t = "Join our office \"$name\" in Habit AI Lite:\nOffice letters › 🏢 Office space › Join, and type the code: $code"
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Send the office code")) } catch (e: Exception) { }
    }
    private fun officeSpaceBox() {
        if (store.officeCode.isBlank()) { space = null; spaceHead = false } else if (spaceFor != store.officeCode) loadSpace()
        val o = space
        m.add(ui.section("🏛 Office space · కార్యాలయ స్థలం",
            if (o == null) "For your whole office: one person makes it, the staff join with its code. Then everyone gets the office's old letters (find by Sy.No.) and letters in your own office's way."
            else "Your office's old letters are shared only inside this office. Other offices learn just the way of writing, with names and numbers hidden.").apply {
            if (spaceBusy.isNotBlank()) put(ui.text("⏳ $spaceBusy", 13.5f, Ui.TEAL, bold = true), 8)
            if (o == null) {
                if (store.officeCode.isNotBlank() && spaceNote.isBlank()) { put(ui.text("Opening your office…", 13f, Ui.MUTED), 8); return@apply }
                if (spaceNote.isNotBlank()) put(ui.text("⚠️ $spaceNote", 13f, Ui.DANGER), 8)
                when (spaceForm) {
                    "create" -> {
                        val n = ui.input("Office name, e.g. Tahsildar Office, Pendurthi", store.officeInfo.optString("office"))
                        val pl = ui.input("Place / Mandal, District (optional)", store.officeInfo.optString("district"))
                        put(n, 8); put(pl, 8)
                        put(ui.row(ui.button("Make it", "primary") {
                            val name = n.text.toString().trim()
                            if (name.length < 3) { m.toast("Write the office's name."); return@button }
                            spacePost("/api/office/create", JSONObject().put("name", name).put("place", pl.text.toString().trim())) { r ->
                                if (r.ok) {
                                    store.officeCode = r.json.optString("code"); store.officeHead = r.json.optString("headKey"); spaceFor = store.officeCode
                                    space = r.json.optJSONObject("office"); spaceHead = true; spaceForm = ""; spaceNote = ""
                                    m.toast("Office space made ✓ Send the code to your staff.")
                                }
                            }
                        }, ui.button("Cancel", "ghost") { spaceForm = ""; m.render() }), 10)
                    }
                    "join" -> {
                        val c = ui.input("Office code, e.g. TAH-7K29Q").apply { setSingleLine(true) }
                        val nm = ui.input("Your name (the head sees it)", store.officeName).apply { setSingleLine(true) }
                        put(c, 8); put(nm, 8)
                        put(ui.row(ui.button("Join", "primary") {
                            val code = c.text.toString().trim().uppercase()
                            if (code.length < 5) { m.toast("Type the code your office head gave."); return@button }
                            store.officeName = nm.text.toString()
                            spacePost("/api/office/join", JSONObject().put("code", code).put("name", store.officeName.ifBlank { "Staff" })) { r ->
                                if (r.ok) { store.officeCode = code; spaceFor = code; store.officeHead = ""; space = r.json.optJSONObject("office"); spaceHead = false; spaceForm = ""; spaceNote = ""; m.toast("Joined ✓") }
                            }
                        }, ui.button("Cancel", "ghost") { spaceForm = ""; m.render() }), 10)
                    }
                    else -> put(ui.row(ui.button("➕ Make our office", "primary") { spaceForm = "create"; spaceNote = ""; m.render() },
                        ui.button("🔑 Join with a code") { spaceForm = "join"; spaceNote = ""; m.render() }), 10)
                }
                return@apply
            }
            put(ui.text("🏢 " + o.optString("name"), 16f, Ui.INK, bold = true), 10)
            put(ui.text(listOfNotNull(o.optString("place").ifBlank { null }, "${o.optInt("files")} old files", if (spaceHead) "you are the head" else null).joinToString(" · "), 12.5f, Ui.MUTED), 2)
            put(ui.text("Code: ${store.officeCode}", 14f, Ui.TEAL, bold = true).apply { setTextIsSelectable(true) }, 8)
            put(ui.row(ui.button("📤 Send the code") { shareCode(store.officeCode, o.optString("name")) },
                ui.button("📎 Add old files") { pickOfficeFiles() }), 8)
            put(ui.text("🖥 Many files or folders: on a computer open ${store.server.removePrefix("https://")}/office and drag them in.", 12f, Ui.MUTED), 6)
            if (spaceHead) {
                put(ui.text("Head only", 13f, Ui.INK, bold = true), 14)
                put(ui.chip((if (o.optBoolean("share", true)) "✅" else "⬜") + " Other offices may learn our way of writing (names & numbers hidden)", o.optBoolean("share", true)) {
                    spacePost("/api/office/settings", JSONObject().put("share", !o.optBoolean("share", true))) { r -> if (r.ok) space = r.json.optJSONObject("office") }
                }, 6)
                put(ui.row(ui.button("🔄 New code") {
                    android.app.AlertDialog.Builder(m).setMessage("Make a new code? The old code stops working for new joins, and staff must type the new one.")
                        .setPositiveButton("New code") { _, _ -> spacePost("/api/office/new-code", JSONObject()) { r -> if (r.ok) { store.officeCode = r.json.optString("code"); spaceFor = store.officeCode; m.toast("New code ready ✓") } } }
                        .setNegativeButton("Cancel", null).show()
                }), 8)
                val mem = o.optJSONArray("members")
                if (mem != null && mem.length() > 0) {
                    put(ui.text("Staff (${mem.length()})", 13f, Ui.MUTED, bold = true), 10)
                    for (i in 0 until mem.length()) {
                        val x = mem.optJSONObject(i) ?: continue
                        put(LinearLayout(m).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            addView(ui.text("👤 " + x.optString("name"), 14f, Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(ui.button("Remove", "ghost") {
                                android.app.AlertDialog.Builder(m).setMessage("Remove ${x.optString("name")}? That phone can't use the office's files any more.")
                                    .setPositiveButton("Remove") { _, _ -> spacePost("/api/office/remove", JSONObject().put("d", x.optString("d"))) { r -> if (r.ok) space = r.json.optJSONObject("office") } }
                                    .setNegativeButton("Cancel", null).show()
                            })
                        }, 4)
                    }
                }
            }
            put(ui.row(ui.button("Leave this office", "ghost") {
                android.app.AlertDialog.Builder(m).setMessage(if (spaceHead) "Leave? You are the head: keep the code safe if you want to come back (as staff). The office and its files stay." else "Leave this office on this phone?")
                    .setPositiveButton("Leave") { _, _ -> store.officeCode = ""; store.officeHead = ""; space = null; spaceHead = false; spaceFor = ""; m.render() }
                    .setNegativeButton("Cancel", null).show()
            }), 12)
        }, 12)
    }
    private fun pickOfficeFiles() {
        m.leavingForSystemScreen = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "image/jpeg", "image/png"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { m.startActivityForResult(i, MainActivity.REQ_OFFICE_FILES) } catch (e: Exception) { m.leavingForSystemScreen = false; m.toast("Couldn't open your files.") }
    }
    /** The picked old files go to the office one by one (only their text is kept there). */
    fun onOfficeFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        spaceBusy = "Adding 1 of ${uris.size}…"; m.render()
        m.io.execute {
            var added = 0; var same = 0; var bad = 0; var waited = false
            for ((k, uri) in uris.withIndex()) {
                m.runOnUiThread { spaceBusy = "Adding ${k + 1} of ${uris.size}…"; m.render() }
                var name = "file"; var type = m.contentResolver.getType(uri) ?: ""
                try { m.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } } } catch (e: Exception) { }
                if (uri.scheme == "file") name = (uri.lastPathSegment ?: name).substringAfter('_')
                if (type.isBlank() && name.endsWith(".docx", true)) type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                val bytes = try { m.contentResolver.openInputStream(uri)?.use { inp -> val b = inp.readBytes(); if (b.size > 15 * 1024 * 1024) null else b } } catch (e: Throwable) { null }
                if (bytes == null) { bad++; continue }
                if (waited && !(name.endsWith(".docx", true) || name.endsWith(".txt", true))) { bad++; continue }
                val r = Net.post(store, "/api/office/add", JSONObject().put("name", name).put("path", name).put("mediaType", type)
                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)), owner(), 180_000)
                when {
                    r.ok && r.json.optBoolean("added") -> added++
                    r.ok && r.json.optBoolean("same") -> same++
                    r.code == 429 -> { waited = true; bad++ }
                    else -> bad++
                }
            }
            m.runOnUiThread {
                spaceBusy = ""
                m.toast(listOfNotNull("✓ $added added", if (same > 0) "$same were already there" else null, if (bad > 0) "$bad couldn't be read" else null,
                    if (waited) "PDFs/photos wait till tomorrow (Word files go now)" else null).joinToString(" · "))
                spaceFor = ""; m.render()
            }
        }
    }

    private fun buildOfficeStart() {
        lettersButton()
        oldFilesBox()
        m.library.box()                        // 📚 the public library and 📁 my files
        m.add(ui.section("🏢 Office letters · కార్యాలయ లేఖలు",
            "Letters, reports, memos, proceedings and notices in office format. Add the petition or the earlier letter, and I'll read it first.").apply {
            put(ui.text("1. What to prepare? (slide ⟷)", 14f, Ui.INK, bold = true), 12)
            put(android.widget.HorizontalScrollView(m).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // the chosen one first
                    OFFICE_TYPES.sortedByDescending { it.second == officeType }.forEachIndexed { i, (icon, name) ->
                        addView(ui.chip("$icon $name", officeType == name) { officeType = if (officeType == name) "" else name; m.render() },
                            LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                    }
                })
            }, 6)
            put(ui.text("2. What is the matter? (write or speak everything here; ➕ Add more as many times as you need)", 14f, Ui.INK, bold = true), 16)
            matterBlock(this, "e.g. \"Report to the RDO on the joint survey in Sy.No. …: who objected, what was found…\"")
            put(ui.text("3. Related papers (optional)", 14f, Ui.INK, bold = true), 16)
            papersBlock(this)
            exampleBlock(this)
            languageChips(this)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
            put(ui.button("✨ Prepare the rough copy · రఫ్ కాపీ తయారు చేయి", "primary") { draft() }, 14)
        }, 12)
        officeCard()
        officeSpaceBox()
        m.add(ui.text("Papers you add are read by Claude AI only to write this document, and kept on the Habit server only until it is made (at most 6 hours). " +
            "\"Hide personal details\" doesn't cover photos and PDFs. Don't add secret or classified papers, and follow your office's rules on using AI tools.", 12f, Ui.MUTED), 10)
    }

    private fun languageChips(sec: LinearLayout) {
        sec.put(ui.text("Letter language", 14f, Ui.INK, bold = true), 16)
        sec.put(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("" to "Auto", "en" to "English", "te" to "తెలుగు").forEachIndexed { i, (k, l) ->
                addView(ui.chip(l, letterLang == k) { letterLang = k; store.letterLang = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
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

    /** A small box: an example letter whose format to follow (from this phone); from a computer: the Teach page. */
    private fun exampleBlock(sec: LinearLayout) {
        loadExample()
        sec.put(ui.text("📑 Example format (optional) · నమూనా", 14f, Ui.INK, bold = true), 16)
        val ex = example
        if (ex == null) {
            sec.put(ui.text("Have a letter in the format you want? Add it: I'll follow its layout, not its facts.", 12.5f, Ui.MUTED), 4)
            sec.put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(ui.button("📷", "soft") { forExample = true; camera() }, ui.button("🖼️", "soft") { forExample = true; pickPhotos() }, ui.button("📄 PDF", "soft") { forExample = true; pickPdf() })
                    .forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = m.dp(6) }) }
            }, 6)
        } else sec.put(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(m.dp(8), m.dp(6), m.dp(6), m.dp(6)); background = ui.round(Ui.SUN_SOFT, 12, Ui.LINE)
            if (ex.thumb != null) addView(ImageView(m).apply { setImageBitmap(ex.thumb); scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.round(Ui.LINE, 6); clipToOutline = true }, LinearLayout.LayoutParams(m.dp(36), m.dp(36)))
            else addView(TextView(m).apply { text = "📑"; textSize = 20f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(m.dp(36), m.dp(36)))
            addView(ui.stack(ui.text(ex.name, 13.5f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                ui.text("Format to follow · kept for the next letters too", 11.5f, Ui.MUTED)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = m.dp(8) })
            addView(ui.button("✕", "ghost") { setExample(null); m.render() }.apply { contentDescription = "Remove the example" })
        }, 6)
        if (office) sec.put(ui.text("🖥 From a computer: open ${store.server.removePrefix("https://")}/teach › 📑 Example letters, and upload Word, PDF or photos. Habit picks the closest one by itself.", 12f, Ui.MUTED), 6)
    }

    /** The owner's phone (with the team key) also gets their own old office files; others never do. */
    private fun owner(): Map<String, String> = buildMap {
        if (store.teamKey.isNotBlank()) put("X-Admin-Key", store.teamKey)
        if (store.officeCode.isNotBlank()) put("X-Office-Code", store.officeCode)    // office staff: the office's old files too
        if (store.officeHead.isNotBlank()) put("X-Office-Head", store.officeHead)
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
        if (forExample) return 1
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

    fun onPhotos(list: List<Uri>) = addPhotos(if (forExample) list.take(1) else list.take(maxOf(0, MAX_PAPERS - papers.size)))

    private fun addPhotos(list: List<Uri>) {
        if (list.isEmpty()) return
        addingPaper = true; m.render()
        m.io.execute {
            val made = list.mapNotNull { u -> Images.prepare(m, u, 1568) }
            try { CameraFiles.file(m).delete() } catch (e: Exception) { }
            m.runOnUiThread {
                addingPaper = false
                if (forExample) {
                    forExample = false
                    made.firstOrNull()?.let { setExample(Paper("Example photo.jpg", "image/jpeg", it, thumbOf(it))) } ?: m.toast("Couldn't open the photo.")
                    m.render(); return@runOnUiThread
                }
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
        if (papers.size >= MAX_PAPERS && !forExample) return
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
                    forExample -> setExample(Paper(name, "application/pdf", bytes, null))
                    papers.sumOf { it.data.size } + bytes.size > MAX_ALL -> m.toast("That's too much together. Remove a paper first.")
                    else -> { papers.add(Paper(name, "application/pdf", bytes, null)); docsId = "" }
                }
                forExample = false
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
            val r = Net.post(store, "/api/letter/plan", body, owner(), if (withPapers) 180_000 else 90_000)
            m.runOnUiThread {
                if (!r.ok) { stage = "start"; error = r.error("Couldn't start. Please try again."); m.render(); return@runOnUiThread }
                val j = r.json
                title = j.optString("title", "Letter"); format = j.optString("format", "docx"); say = j.optString("say", "")
                ownSkill = if (j.optBoolean("own")) j.optString("skill") else ""
                ownWhy = if (j.optBoolean("own")) j.optString("why") else ""
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
        // why Claude isn't making it (so it's clear what to do)
        val why = when (ownWhy) {
            "limit" -> if (te) "ఈ ఫోన్‌కి ఈరోజు Claude మెసేజ్‌లు అయిపోయాయి; రేపు మళ్ళీ వస్తాయి." else "This phone's Claude messages for today are used up; they come back tomorrow."
            "resting" -> if (te) "సర్వర్‌లో Claude ఆగి ఉంది (నెల పరిమితి లేదా క్రెడిట్): Teach పేజీలో చూడండి." else "Claude is paused on the server (monthly limit or credit): see the Teach page."
            "busy" -> if (te) "Claude ఇప్పుడే బిజీగా ఉంది: \"↺ Start over\" నొక్కితే మళ్ళీ Claude ప్రయత్నిస్తుంది." else "Claude was busy just now: tap \"↺ Start over\" to try Claude again."
            "nokey" -> if (te) "సర్వర్‌లో Claude కీ లేదు." else "The server has no Claude key."
            else -> ""
        }
        m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text(if (te) "🧠 Habit సొంత మెదడు" else "🧠 Habit's own brain", 15f, Ui.TEAL_DARK, bold = true))
            put(ui.text(if (done) (if (te) "ఈ ఫైల్‌ను Habit తను నేర్చుకున్న ఫార్మాట్‌తో సొంతంగా తయారు చేసింది. పంపే ముందు ఒకసారి చదవండి."
                    else "Habit made this file by itself, from a format it has learned. Please read it once before you send it.")
                else (if (te) "Claude ఇప్పుడు అందుబాటులో లేదు, అందుకే Habit తను నేర్చుకున్న ఫార్మాట్‌తో ఈ ఫైల్ తయారు చేస్తుంది."
                    else "Claude can't help right now, so Habit makes this file by itself, from a format it has learned."), 13.5f, Ui.INK), 4)
            if (why.isNotBlank() && !done) put(ui.text(why, 12.5f, Ui.MUTED), 4)
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
            // the letter's language, and the language you speak (an English letter: English voice typing, so the words come
            // out in English letters, not Telugu ones)
            put(ui.text("Letter in · లేఖ భాష", 13.5f, Ui.INK, bold = true), 12)
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("" to "Auto", "en" to "English", "te" to "తెలుగు").forEachIndexed { i, (k, l) ->
                    addView(ui.chip(l, letterLang == k) { letterLang = k; store.letterLang = k; m.stopListening(); listeningId = ""; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                }
            }, 6)
            put(ui.text("🎤 I speak in · నేను మాట్లాడే భాష", 13.5f, Ui.INK, bold = true), 10)
            val now = voiceLang().take(2)
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("en" to "English", "te" to "తెలుగు").forEachIndexed { i, (k, l) ->
                    addView(ui.chip(l, now == k) { speakLang = k; m.stopListening(); listeningId = ""; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                }
            }, 6)
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

        m.add(ui.section("📎 Earlier letter / reference papers (if you have them)",
            "Add a photo or PDF of the earlier letter, order or petition: Claude reads it while writing (references, dates, facts).").apply { papersBlock(this) }, 12)

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
        m.listen(lang = voiceLang(), onPartial = { p -> (fields[id] ?: input).let { it.setText(join(p)); it.setSelection(it.text.length) } }, onDone = { heard ->
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
        // papers added on this screen (or changed after the questions came): they go along now, for Claude to read
        val sendPapers = docsId.isBlank() && papers.isNotEmpty()
        if (sendPapers) body.put("docs", JSONArray().apply {
            papers.forEach { p -> put(JSONObject().put("name", p.name).put("mediaType", p.mime).put("data", Base64.encodeToString(p.data, Base64.NO_WRAP))) }
        })
        if (ownSkill.isNotBlank()) body.put("skill", ownSkill)
        m.io.execute {
            val r = Net.post(store, "/api/letter/make", body, timeoutMs = if (sendPapers) 240_000 else 150_000)
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
        if (rough != null) m.add(ui.button("✏️ Change this letter · ఈ లేఖ మార్చు", "primary") { stage = "rough"; result = null; m.render(); m.scrollTop() }, 12)
        else m.add(ui.button("✏️ Change my answers and make it again") { stage = "questions"; result = null; m.render(); m.scrollTop() }, 12)
        m.add(ui.button("➕ New letter", "ghost") { reset() }, 8)
        m.add(ui.text("🚩 Something wrong or offensive in this letter? Report it", 13f, Ui.MUTED).apply {
            setPadding(0, m.dp(6), 0, m.dp(6)); setOnClickListener { m.reportAi("letter helper", preview.ifBlank { name }, request) }
        }, 14)
    }

    // ---------------- one box for the matter (➕ add more, as many times as needed) ----------------
    private fun matterAll() = (pieces + request.trim()).filter { it.isNotBlank() }.joinToString("\n")

    private fun matterBlock(sec: LinearLayout, hint: String) {
        pieces.forEachIndexed { i, s ->
            sec.put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(m.dp(10), m.dp(8), m.dp(4), m.dp(8)); background = ui.round(Ui.FIELD, 12, Ui.LINE)
                addView(ui.text("${i + 1}.", 14f, Ui.TEAL_DARK, bold = true), LinearLayout.LayoutParams(m.dp(30), -2))
                addView(ui.text(s, 14f, Ui.INK).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("✕", "ghost") { pieces.removeAt(i); m.render() }.apply { contentDescription = "Remove part ${i + 1}" })
            }, 6)
        }
        val box = ui.input(if (pieces.isEmpty()) hint else "Anything more? Write or speak, then ➕ Add", request, multi = true)
        box.minLines = 4
        box.onText { request = it }
        fields["request"] = box
        sec.put(box, 8)
        sec.put(ui.row(mic("request", box, append = true), ui.button("➕ Add · జోడించు", "sun") {
            val t = request.trim()
            if (t.isEmpty()) { m.toast("Write or speak something first."); return@button }
            m.stopListening(); listeningId = ""
            pieces.add(t); request = ""; m.render()
            fields["request"]?.let { m.scrollToView(it) }
        }), 8)
        if (pieces.isNotEmpty()) sec.put(ui.text("${pieces.size} part(s) added. Add more, or prepare the rough copy.", 12.5f, Ui.MUTED), 4)
    }

    // ---------------- the rough copy (numbered lines) → changes → the final file ----------------
    private fun docsBody(body: JSONObject) {
        if (docsId.isNotBlank()) body.put("docsId", docsId)
        else if (papers.isNotEmpty()) body.put("docs", JSONArray().apply {
            papers.forEach { p -> put(JSONObject().put("name", p.name).put("mediaType", p.mime).put("data", Base64.encodeToString(p.data, Base64.NO_WRAP))) }
        })
    }

    private fun draft() {
        val all = matterAll()
        if (all.length < 3 && papers.isEmpty()) { m.toast(if (office) "Write the matter (or add the papers) first." else "Tell me what you need first."); return }
        if (addingPaper) { m.toast("Wait a moment: the paper is still getting ready."); return }
        m.stopListening(); listeningId = ""
        if (request.isNotBlank()) { pieces.add(request.trim()); request = "" }
        stage = "drafting"; error = ""; m.render(); m.scrollTop()
        val body = JSONObject().put("request", Privacy.maybe(store, pieces.joinToString("\n") { "- $it" })).put("lang", letterLang.ifEmpty { store.lang }).put("mode", store.letterMode)
        if (office) { if (officeType.isNotEmpty()) body.put("docType", officeType); body.put("office", store.officeInfo) }
        docsBody(body)
        loadExample()
        example?.let { e -> body.put("example", JSONObject().put("name", e.name).put("mediaType", e.mime).put("data", Base64.encodeToString(e.data, Base64.NO_WRAP))) }
        val heavy = body.has("docs") || body.has("example")
        m.io.execute {
            val r = Net.post(store, "/api/letter/draft", body, owner(), 320_000)
            m.runOnUiThread {
                if (!r.ok || r.json.optJSONArray("lines") == null) { stage = "start"; error = r.error("Couldn't write the rough copy. Please try again."); m.render(); return@runOnUiThread }
                rough = r.json; change = ""; editingAt = 0L
                r.json.optString("docsId").takeIf { it.isNotBlank() }?.let { docsId = it }
                stage = "rough"; m.render(); m.scrollTop()
                if (store.voiceOn) m.speakOut(if (store.lang == "en") "Here is the rough copy. Tell me what to change, by line number." else "రఫ్ కాపీ సిద్ధం. ఏ లైన్‌లో ఏమి మార్చాలో చెప్పండి.")
            }
        }
    }

    private fun buildRough() {
        val r = rough ?: run { stage = "start"; buildStart(); return }
        val lines = r.optJSONArray("lines") ?: JSONArray()
        m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("📝 Rough copy · రఫ్ కాపీ", 19f, Ui.INK, bold = true))
            put(ui.text(r.optString("title", "Letter") + " · the line numbers are only here, not in the final letter", 13f, Ui.MUTED), 4)
        }, 12)
        m.add(ui.card().apply {
            for (i in 0 until lines.length()) {
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(ui.text("${i + 1}", 13f, Ui.TEAL_DARK, bold = true).apply { gravity = Gravity.END; setPadding(0, m.dp(1), m.dp(10), 0) }, LinearLayout.LayoutParams(m.dp(38), -2))
                    addView(ui.text(lines.optString(i), 14.5f, Ui.INK).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, -2, 1f))
                }, if (i == 0) 0 else 6)
            }
        }, 10)
        m.add(ui.section("✏️ What to change? · ఏమి మార్చాలి?",
            "Say or write it with the line number. e.g. \"line 5: the date is 12-11-2026\", \"after line 9 add that the survey was stopped\", \"10వ లైన్ తీసేయి\"").apply {
            val box = ui.input("Your change…", change, multi = true)
            box.minLines = 3
            box.onText { change = it }
            fields["change"] = box
            put(box, 8)
            put(ui.row(mic("change", box, append = true)), 8)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 10)
            put(ui.button("✏️ Make this change · మార్చు", "primary") { revise() }, 12)
        }, 12)
        m.add(ui.button("✅ Final copy: make the Word file · ఫైనల్ కాపీ", "sun") { finalCopy() }, 14)
        m.add(ui.button("← Add more to the matter and write it again", "ghost") { stage = "start"; m.render(); m.scrollTop() }, 8)
        m.add(ui.button("↺ Start over", "ghost") { reset() }, 6)
    }

    private fun revise() {
        val r = rough ?: return
        if (change.trim().length < 2) { m.toast("Write or speak what to change first."); return }
        m.stopListening(); listeningId = ""
        stage = "drafting"; error = ""; m.render()
        val body = JSONObject().put("content", r.optJSONObject("content")).put("title", r.optString("title")).put("format", r.optString("format", "docx"))
            .put("change", Privacy.maybe(store, change.trim())).put("lang", letterLang.ifEmpty { store.lang }).put("mode", store.letterMode)
        if (office) body.put("office", store.officeInfo)
        if (docsId.isNotBlank()) body.put("docsId", docsId)
        m.io.execute {
            val x = Net.post(store, "/api/letter/revise", body, owner(), 320_000)
            m.runOnUiThread {
                stage = "rough"
                if (!x.ok || x.json.optJSONArray("lines") == null) { error = x.error("Couldn't make the change. Please try again."); m.render(); return@runOnUiThread }
                rough = x.json; change = ""; m.render(); m.scrollTop()
                m.toast("✓ Changed. Read it once more.")
            }
        }
    }

    private fun finalCopy() {
        val r = rough ?: return
        m.stopListening(); listeningId = ""
        stage = "finishing"; error = ""; m.render()
        val body = JSONObject().put("content", r.optJSONObject("content")).put("title", r.optString("title")).put("format", r.optString("format", "docx"))
            .apply { if (office && officeType.isNotEmpty()) put("docType", officeType) }
        m.io.execute {
            val x = Net.post(store, "/api/letter/final", body, owner(), 90_000)
            m.runOnUiThread {
                if (!x.ok || !x.json.optString("url").startsWith(store.server + "/files/")) { stage = "rough"; error = x.error("Couldn't make the file. Please try again."); m.render(); return@runOnUiThread }
                val lines = r.optJSONArray("lines") ?: JSONArray()
                result = JSONObject(x.json.toString()).put("preview", (0 until lines.length()).joinToString("\n") { lines.optString(it) })
                fileId = Actions(m) { _, _ -> }.downloadFile(x.json.optString("url"), x.json.optString("name", "Letter.docx")) ?: -1L
                keepLetter(r, x.json)
                // back to the page: the form is ready for a new letter, and this one is on top of "Your letters & files"
                justMade = editingAt
                pieces.clear(); request = ""; papers.clear(); docsId = ""; rough = null; change = ""; editingAt = 0L; result = null
                stage = "start"; m.render()
                m.toast(if (r.optString("format") == "xlsx") "✅ Excel file ready · saved in Downloads" else "✅ Letter ready · saved in Downloads")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ lettersView?.let { m.scrollToView(it) } }, 120)
            }
        }
    }

    /** Saved in "My letters" (on this phone): the file and its text, so it can be opened, shared or changed later. */
    private fun keepLetter(r: JSONObject, file: JSONObject) {
        val at = if (editingAt > 0) editingAt else System.currentTimeMillis()
        val e = JSONObject().put("at", at).put("when", System.currentTimeMillis()).put("title", r.optString("title", "Letter")).put("format", r.optString("format", "docx"))
            .put("name", file.optString("name")).put("url", file.optString("url")).put("file", fileId).put("mode", store.letterMode)
            .put("content", r.optJSONObject("content")).put("lines", r.optJSONArray("lines"))
        val old = store.letters
        val list = JSONArray().put(e)
        for (i in 0 until old.length()) old.optJSONObject(i)?.let { if (it.optLong("at") != at && list.length() < 30) list.put(it) }
        store.letters = list
        editingAt = at
    }

    /** The ids (in Downloads) of the files that are letters here: the Files page doesn't list them twice. */
    fun letterFileIds(): Set<Long> { val l = store.letters; return (0 until l.length()).mapNotNull { l.optJSONObject(it)?.optLong("file", -1L)?.takeIf { f -> f >= 0 } }.toSet() }

    /** "Your letters & files": every letter made here (Open, Share, ✏️ Change, Remove); [others] adds the other files. */
    fun lettersSection(others: Int) {
        val list = store.letters
        if (list.length() == 0) { lettersView = null; return }
        m.add(ui.section("📂 Your letters & files · మీ లేఖలు (${list.length() + others})", "Saved on this phone, in Downloads › Habit AI. ✏️ Change opens the rough copy with line numbers again.").apply {
            lettersView = this
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val day = java.text.SimpleDateFormat("dd-MM-yyyy, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(e.optLong("when", e.optLong("at"))))
                val fresh = justMade != 0L && e.optLong("at") == justMade
                put(LinearLayout(m).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(m.dp(12), m.dp(10), m.dp(12), m.dp(10)); background = ui.round(if (fresh) Ui.TEAL_SOFT else Ui.FIELD, 14, Ui.LINE)
                    if (fresh) addView(ui.text("✅ Just made · ఇప్పుడే తయారైంది", 13f, Ui.TEAL_DARK, bold = true))
                    addView(ui.text((if (e.optString("format") == "xlsx") "📊 " else "📄 ") + e.optString("title", "Letter"), 15f, Ui.INK, bold = true))
                    addView(ui.text(day, 12f, Ui.MUTED))
                    addView(ui.row(ui.button("📖 Open", "soft") { openSaved(e, share = false) }, ui.button("📤 Share", "soft") { openSaved(e, share = true) }, gap = 6),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(8) })
                    addView(ui.row(ui.button("✏️ Change", "primary") { changeSaved(e) }, ui.button("🗑 Remove", "ghost") { removeSaved(e) }, gap = 6),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(6) })
                }, 8)
            }
        }, 12)
    }

    private fun changeSaved(e: JSONObject) {
        m.stopListening(); listeningId = ""
        rough = JSONObject().put("title", e.optString("title")).put("format", e.optString("format", "docx")).put("content", e.optJSONObject("content")).put("lines", e.optJSONArray("lines") ?: JSONArray())
        editingAt = e.optLong("at"); change = ""; error = ""; result = null; docsId = ""
        if (e.optString("mode").isNotBlank()) store.letterMode = e.optString("mode")
        stage = "rough"; m.render(); m.scrollTop()
    }

    private fun removeSaved(e: JSONObject) {
        AlertDialog.Builder(m).setMessage("Remove \"${e.optString("title")}\"? It is deleted from this phone.")
            .setPositiveButton("Remove") { _, _ ->
                val old = store.letters; val list = JSONArray()
                for (i in 0 until old.length()) old.optJSONObject(i)?.let { if (it.optLong("at") != e.optLong("at")) list.put(it) }
                store.letters = list
                // the file in Downloads goes too (it is one list now)
                e.optLong("file", -1L).takeIf { it >= 0 }?.let { id -> try { m.getSystemService(DownloadManager::class.java).remove(id) } catch (x: Exception) { }; store.removeFile(id) }
                m.render()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Opens (or shares) a saved letter; if its file is gone from Downloads, it is made again from its text. */
    private fun openSaved(e: JSONObject, share: Boolean) {
        val xl = e.optString("format") == "xlsx"
        val u = try { e.optLong("file", -1L).takeIf { it >= 0 }?.let { m.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(it) } } catch (x: Exception) { null }
        if (u != null) { send(u, xl, share); return }
        m.toast("Getting the file ready…")
        val body = JSONObject().put("content", e.optJSONObject("content")).put("title", e.optString("title")).put("format", e.optString("format", "docx"))
        m.io.execute {
            val x = Net.post(store, "/api/letter/final", body, owner(), 90_000)
            m.runOnUiThread {
                if (!x.ok) { m.toast(x.error("Couldn't get the file. Check the internet.")); return@runOnUiThread }
                val id = Actions(m) { _, _ -> }.downloadFile(x.json.optString("url"), x.json.optString("name", "Letter.docx")) ?: -1L
                val old = store.letters
                for (i in 0 until old.length()) old.optJSONObject(i)?.let { if (it.optLong("at") == e.optLong("at")) it.put("file", id) }
                store.letters = old
                m.toast("Downloading… tap Open again in a moment.")
            }
        }
    }

    private fun send(u: Uri, xl: Boolean, share: Boolean) {
        m.leavingForSystemScreen = true
        try {
            if (share) m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(xl)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share your letter"))
            else m.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime(xl)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (x: Exception) { m.toast("Install Microsoft Word or Google Docs to open it.") }
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
        pieces.clear(); rough = null; change = ""; editingAt = 0L
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
