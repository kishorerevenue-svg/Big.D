package com.soulfriend.pet

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * The letter helper on the Files page. You say or type what you need, the pet asks every detail the letter
 * needs (each question can be answered by typing or with 🎤, and read aloud with 🔊), suggests extras that make
 * the letter better, and then writes a complete, nicely laid-out Word (or Excel) file with no blanks.
 */
class LetterHelper(private val m: MainActivity) {
    private class Q(val id: String, val q: String, val hint: String, val required: Boolean, val label: String = "")

    private var stage = "start"            // start, planning, questions, making, done
    private var request = ""
    private var letterLang = ""            // "" = same as the request, "en" or "te"
    private var title = ""
    private var format = "docx"
    private var say = ""
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
    fun start(req: String) {
        request = req.trim(); result = null; error = ""
        if (request.length >= 3) plan() else { stage = "start"; m.render() }
    }

    fun build() {
        fields.clear(); micButtons.clear()
        when (stage) {
            "planning" -> m.add(busy("🐾 Thinking about what your letter needs…"), 12)
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
    private fun buildStart() {
        m.add(ui.section("✨ Letter helper · లేఖ సహాయకుడు",
            "Tell me what you need. I'll ask each detail, you answer by typing or by voice 🎤, and I'll make a complete letter. Easy for everyone, even elders.").apply {
            val box = ui.input("What do you need? e.g. \"Leave letter for 2 days\"", request, multi = true)
            box.onText { request = it }
            fields["request"] = box
            put(box, 12)
            put(ui.row(mic("request", box, append = true)), 8)
            put(ui.button("➜ Next: ask me the details", "primary") { plan() }, 10)
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
            put(ui.text("Letter language", 14f, Ui.INK, bold = true), 16)
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("" to "Auto", "en" to "English", "te" to "తెలుగు").forEachIndexed { i, (k, l) ->
                    addView(ui.chip(l, letterLang == k) { letterLang = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) })
                }
            }, 6)
            if (error.isNotBlank()) put(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 12)
            put(ui.button("I wrote everything: make it now, no questions", "ghost") { direct() }, 14)
        }, 12)
    }

    private fun plan() {
        if (request.trim().length < 3) { m.toast("Tell me what you need first, for example \"leave letter\"."); return }
        m.stopListening(); stage = "planning"; error = ""; m.render()
        val body = JSONObject().put("request", Privacy.maybe(store, request)).put("lang", store.lang)
        m.io.execute {
            val r = Net.post(store, "/api/letter/plan", body)
            m.runOnUiThread {
                if (!r.ok) { stage = "start"; error = r.error("Couldn't start. Please try again."); m.render(); return@runOnUiThread }
                val j = r.json
                title = j.optString("title", "Letter"); format = j.optString("format", "docx"); say = j.optString("say", "")
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
    private fun buildQuestions() {
        m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("📝 $title", 19f, Ui.INK, bold = true))
            if (say.isNotBlank()) put(ui.text(say, 14f, Ui.MUTED), 4)
            put(if (voiceAll) ui.button("■ Stop voice answers", "danger") { stopVoiceAll() }
                else ui.button("🎙 Answer all by voice (I'll read each question)", "sun") { startVoiceAll() }, 12)
            put(ui.text("Or type, or tap 🎤 next to any question. Tap 🔊 to hear a question.", 12.5f, Ui.MUTED), 6)
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
            if (listeningId == id) { m.stopListening(); listeningId = ""; voiceAll = false; m.render(); return@button }
            if (voiceAll) stopVoiceAll()
            listenInto(id, input, append) { }
        }
        micButtons[id] = b
        return b
    }

    private fun listenInto(id: String, input: EditText, append: Boolean, after: (Boolean) -> Unit) {
        val before = if (append) input.text.toString().trim() else ""
        fun join(s: String) = if (before.isEmpty()) s else "$before $s"
        listeningId = id
        micButtons[id]?.let { it.text = "● Listening… tap to stop"; it.background = ui.round(Ui.DANGER_SOFT, 24); it.setTextColor(Ui.DANGER) }
        input.background = ui.round(Ui.TEAL_SOFT, 14, Ui.TEAL)
        m.listen(onPartial = { p -> (fields[id] ?: input).setText(join(p)) }, onDone = { heard ->
            if (listeningId != id) return@listen
            listeningId = ""
            val ok = !heard.isNullOrBlank()
            if (ok) { val v = join(heard!!); answers[id] = v; if (id == "request") request = v }
            m.render()
            after(ok)
        })
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
        val ans = JSONArray(); questions.forEach { q -> answers[q.id]?.takeIf { it.isNotBlank() }?.let { ans.put(JSONObject().put("q", q.q).put("a", Privacy.maybe(store, it))) } }
        val ex = JSONArray(); extras.filter { it.id in chosen }.forEach { q -> answers[q.id]?.takeIf { it.isNotBlank() }?.let { ex.put(JSONObject().put("label", q.label).put("q", q.q).put("a", Privacy.maybe(store, it))) } }
        val body = JSONObject().put("request", Privacy.maybe(store, request)).put("title", title).put("format", format)
            .put("lang", letterLang.ifEmpty { store.lang }).put("answers", ans).put("extras", ex)
            .put("note", Privacy.maybe(store, answers["note"] ?: ""))
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
        }, onError = { err -> stage = "start"; error = err; m.render() })
    }

    // ---------------- 4. ready ----------------
    private fun buildDone() {
        val r = result ?: run { stage = "start"; buildStart(); return }
        val name = r.optString("name", "Letter")
        val xl = r.optString("format") == "xlsx"
        m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("✅ Your ${if (xl) "Excel file" else "letter"} is ready!", 19f, Ui.TEAL_DARK, bold = true))
            put(ui.text("$name · saved in Downloads › BigD AI", 13f, Ui.MUTED), 4)
            put(ui.row(ui.button("Open", "primary") { open(xl) }, ui.button("Share") { share(xl) }), 12)
        }, 12)
        val preview = r.optString("preview")
        if (preview.isNotBlank()) m.add(ui.section("Preview").apply {
            put(ui.text(preview, 14.5f, Ui.INK).apply { setTextIsSelectable(true); setPadding(m.dp(12), m.dp(12), m.dp(12), m.dp(12)); background = ui.round(Color.parseColor("#FBFCFC"), 12, Ui.LINE) }, 10)
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
        stage = "start"; request = ""; result = null; error = ""; questions.clear(); extras.clear(); answers.clear(); chosen.clear()
        voiceAll = false; listeningId = ""
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
