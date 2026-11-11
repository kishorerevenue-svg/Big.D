package com.soulfriend.pet

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Govt services page: official website links for common services (Andhra Pradesh, Telangana and all India),
 * and simple answers to questions, found only on official government websites (gov.in, nic.in).
 * Habit AI is not a government app: the page says so clearly, and every answer links to the official source.
 */
class GovtPage(private val m: MainActivity) {
    private var cat = ""
    private var question = ""
    private var asking = false
    private var asked = ""
    private var answer = ""
    private var sources = listOf<Pair<String, String>>()     // title, link
    private var error = ""
    private var listening = false
    private var loading = false
    private var failedAt = 0L
    private var data: JSONObject? = null
    private var answerView: View? = null

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private val te get() = store.lang == "te"
    private fun stateName() = if (store.govtState == "ts") "Telangana" else "Andhra Pradesh"

    fun build() {
        if (data == null) data = try { JSONObject(store.govtCache).takeIf { it.has("services") } } catch (e: Exception) { null }
        val stale = data == null || System.currentTimeMillis() - store.govtCacheAt > 24 * 3600_000L
        if (stale && !loading && System.currentTimeMillis() - failedAt > 60_000) load()

        m.pageHeader("Govt services · ప్రభుత్వ సేవలు", "Official websites and simple steps for certificates, land, pensions and more")
        m.add(ui.card(Ui.SUN_SOFT).apply {
            addView(ui.text("ℹ️ Habit AI is not a government app", 15f, Ui.INK, bold = true))
            put(ui.text("We are not part of, or linked to, any government department. The links open official government websites. " +
                "Always check the details there, or at your Sachivalayam, MeeSeva or Tahsildar office. Never pay middlemen, and never share an OTP.", 13.5f, Ui.MUTED), 4)
        }, 12)

        m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("ap" to "Andhra Pradesh", "ts" to "Telangana").forEachIndexed { i, (k, l) ->
                addView(ui.chip(l, store.govtState == k) { store.govtState = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(8) })
            }
        }, 14)

        m.add(ui.section("❓ Ask about any service", "I look only on official government websites, then explain it simply, with the link.").apply {
            val b = ui.input("e.g. How do I get an income certificate?", question, multi = true)
            b.onText { question = it }
            put(b, 12)
            put(ui.row(ui.button(if (listening) "● Stop" else "🎤 Speak", if (listening) "danger" else "soft") { mic(b) },
                ui.button(if (asking) "Looking…" else "Ask", "primary") { if (!asking) ask() }), 10)
        }, 12)
        answerView = null
        if (asking) m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("🔎 Looking on official websites…", 15.5f, Ui.TEAL_DARK, bold = true))
            put(ui.text("This can take up to a minute.", 13f, Ui.MUTED), 4)
        }, 10)
        if (error.isNotEmpty()) m.add(ui.card(Ui.DANGER_SOFT).apply { addView(ui.text(error, 14.5f, Ui.DANGER)) }, 10)
        if (answer.isNotEmpty() && !asking) answerCard().also { answerView = it; m.add(it, 10) }

        servicesList()
    }

    // ---------- the list of services ----------
    private fun servicesList() {
        m.add(ui.label("Services · సేవలు"), 22)
        val d = data
        if (d == null) {
            m.add(ui.card().apply {
                addView(ui.text(if (loading) "Loading the list…" else "Couldn't load the list. Check your internet.", 14.5f, Ui.MUTED))
                if (!loading) put(ui.button("Try again", "ghost") { failedAt = 0; load() }, 10)
            }, 8)
            return
        }
        val cats = d.optJSONArray("cats") ?: JSONArray()
        val row = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.chip(if (te) "అన్నీ" else "All", cat.isEmpty()) { cat = ""; m.render() })
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val key = c.optString("key")
            row.addView(ui.chip(c.optString("icon") + " " + c.optString(if (te) "te" else "en"), cat == key) { cat = key; m.render() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = m.dp(6) })
        }
        m.add(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 8)

        val all = d.optJSONArray("services") ?: JSONArray()
        val shown = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filter { (it.optString("st") == "in" || it.optString("st") == store.govtState) && (cat.isEmpty() || it.optString("cat") == cat) }
            .sortedBy { if (it.optString("st") == "in") 1 else 0 }
        if (shown.isEmpty()) m.add(ui.text("Nothing here for ${stateName()} yet. Ask your question above.", 14f, Ui.MUTED), 10)
        shown.forEach { m.add(serviceCard(it), 8) }
        m.add(ui.text("Links checked against the official websites. Websites sometimes change: if one doesn't open, ask above or visit your local office.", 12.5f, Ui.MUTED), 12)
    }

    private fun serviceCard(s: JSONObject) = ui.card().apply {
        val main = s.optString(if (te) "te" else "en"); val other = s.optString(if (te) "en" else "te")
        addView(ui.text(main, 16f, Ui.INK, bold = true))
        if (other.isNotBlank()) put(ui.text(other, 13.5f, Ui.MUTED), 2)
        val url = s.optString("url")
        put(ui.text("📍 " + s.optString("where"), 13.5f, Ui.INK), 8)
        put(ui.text("🔗 " + (Uri.parse(url).host ?: url), 12.5f, Ui.TEAL_DARK), 4)
        val buttons = mutableListOf<View>(ui.button("🌐 Open website", "primary") { m.openUrl(url) })
        val letter = s.optString("letter")
        if (letter.isNotBlank()) buttons.add(ui.button("📝 Write application") { m.openLetter("$letter (${stateName()})") })
        buttons.add(ui.button("❓ Ask") {
            question = "How do I apply for ${s.optString("en")} in ${if (s.optString("st") == "in") "India" else stateName()}? Which documents are needed?"
            ask()
        })
        put(HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(ui.row(*buttons.toTypedArray())) }, 12)
    }

    private fun load() {
        loading = true
        m.io.execute {
            val r = Net.get(store, "/api/govt/services")
            m.runOnUiThread {
                loading = false
                if (r.ok && r.json.has("services")) { data = r.json; store.govtCache = r.json.toString() } else failedAt = System.currentTimeMillis()
                if (m.tab == "govt") m.render()
            }
        }
    }

    // ---------- asking ----------
    private fun mic(box: EditText) {
        if (listening) { listening = false; m.stopListening(); m.render(); return }
        listening = true; m.render()
        m.listen(onPartial = { p -> question = p; box.setText(p) }, onDone = { heard ->
            if (!listening) return@listen
            listening = false
            if (!heard.isNullOrBlank()) { question = heard; ask() } else if (m.tab == "govt") m.render()
        })
    }

    private fun ask() {
        val q = question.trim()
        if (q.length < 4) { m.toast("Type or say your question first."); return }
        if (listening) { listening = false; m.stopListening() }
        asking = true; error = ""; answer = ""; sources = emptyList(); asked = q
        m.render()
        val body = JSONObject().put("question", Privacy.maybe(store, q)).put("state", store.govtState).put("lang", store.lang)
        m.io.execute {
            val r = Net.post(store, "/api/govt/ask", body, emptyMap(), 120_000)
            m.runOnUiThread {
                asking = false
                if (r.ok) {
                    answer = plain(r.json.optString("text"))
                    val a = r.json.optJSONArray("sources") ?: JSONArray()
                    sources = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("title") to it.optString("url") }
                        .filter { it.second.startsWith("https://") || it.second.startsWith("http://") }
                } else error = r.error("Couldn't get an answer. Please try again.")
                if (m.tab == "govt") { m.render(); answerView?.let { m.scrollToView(it) } }
            }
        }
    }

    /** The answer without markdown marks (** and #). */
    private fun plain(s: String) = s.replace("**", "").lines().joinToString("\n") { it.replace(Regex("^#{1,6}\\s*"), "") }.trim()

    private fun answerCard() = ui.card().apply {
        addView(ui.text("❓ $asked", 13.5f, Ui.MUTED, bold = true))
        put(ui.text(answer, 15.5f, Ui.INK).apply { setTextIsSelectable(true) }, 8)
        if (sources.isNotEmpty()) {
            put(ui.label("Official websites"), 14)
            sources.forEach { (title, link) ->
                put(ui.text("🌐 " + title.ifBlank { link } + "\n" + (Uri.parse(link).host ?: ""), 14f, Ui.TEAL_DARK).apply {
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setPadding(0, m.dp(4), 0, m.dp(4))
                    setOnClickListener { m.openUrl(link) }
                }, 4)
            }
        }
        put(ui.row(ui.button("🔊 Listen") { m.speakOut(answer) }, ui.button("📋 Copy") {
            m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("answer", answer)); m.toast("Copied")
        }), 12)
        put(ui.text("Written by AI from official websites. It can make mistakes: please check on the official website.  🚩 Report", 12.5f, Ui.MUTED).apply {
            setOnClickListener { m.reportAi("Govt services", answer, asked) }
        }, 10)
    }
}
