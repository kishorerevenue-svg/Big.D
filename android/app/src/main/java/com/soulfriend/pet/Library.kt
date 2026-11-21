package com.soulfriend.pet

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library on the Files page, in two boxes (the 🏛 Office space is the third, in Letter.kt):
 *  🌐 Public library — like Wikipedia for everyday rules: GOs, circulars, notices, scheme details. Anyone who has logged in
 *     adds; everyone finds and reads; the pet answers from all of it (server: library.js).
 *  📁 My files — their own small files: kept on this phone (to read without the internet) and on the server for them only,
 *     so the pet answers from them and they open on the web too (bigdai.tech/library). Only the text is kept.
 * Files shared to "Give to Habit" from another app come here: the person picks the box.
 */
class LibraryHelper(private val m: MainActivity) {
    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private fun te() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te()) tel else en

    private val dir by lazy { File(m.filesDir, "myfiles").apply { mkdirs() } }
    private val indexFile get() = File(dir, "index.json")
    private var mine: JSONArray? = null
    private var synced = false
    private var pubQ = ""
    private var pubFound: JSONArray? = null
    private var busy = ""
    private var pickFor = "my"

    // ---------- my files on the phone ----------
    private fun myList(): JSONArray {
        mine?.let { return it }
        val a = try { JSONArray(indexFile.readText()) } catch (e: Exception) { JSONArray() }
        mine = a; return a
    }
    private fun saveMine() { try { indexFile.writeText(myList().toString()) } catch (e: Exception) { } }
    private fun keepLocal(item: JSONObject, text: String) {
        val id = item.optString("id"); if (id.isBlank()) return
        try { File(dir, "$id.txt").writeText(text) } catch (e: Exception) { }
        val a = myList()
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == id) { a.put(i, item); saveMine(); return }
        a.put(item); saveMine()
    }
    private fun localText(id: String) = try { File(dir, "$id.txt").takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
    private fun dropLocal(id: String) {
        try { File(dir, "$id.txt").delete() } catch (e: Exception) { }
        val a = myList(); val b = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") != id) b.put(it) }
        mine = b; saveMine()
    }
    /** Files added on the web (or another phone) show here too. */
    private fun sync() {
        if (synced) return
        synced = true
        m.io.execute {
            val r = Net.post(store, "/api/library/my-list", JSONObject(), emptyMap(), 20_000)
            if (!r.ok) return@execute
            val items = r.json.optJSONArray("items") ?: return@execute
            m.runOnUiThread {
                val old = myList(); val have = HashMap<String, JSONObject>()
                for (i in 0 until old.length()) old.optJSONObject(i)?.let { have[it.optString("id")] = it }
                val now = JSONArray()
                for (i in 0 until items.length()) items.optJSONObject(i)?.let { now.put(have[it.optString("id")] ?: it) }
                // (files only on this phone, never sent: kept)
                for (o in have.values) if (o.optBoolean("phoneOnly")) now.put(o)
                mine = now; saveMine(); if (m.tab == "files") m.render()
            }
        }
    }

    // ---------- the page ----------
    fun box() {
        sync()
        m.add(ui.section(t("📚 Library · గ్రంథాలయం", "📚 గ్రంథాలయం · Library"),
            t("🌐 Public: GOs, circulars, notices, scheme details for everyone (like Wikipedia); the pet answers from them. 📁 Mine: your own files, only for you.",
                "🌐 పబ్లిక్: అందరి కోసం GOలు, సర్క్యులర్లు, నోటీసులు, పథకాల వివరాలు (వికీపీడియా లాగా); పెట్ వాటి నుంచి జవాబు చెబుతుంది. 📁 నావి: మీ సొంత ఫైళ్లు, మీకే.")).apply {
            if (busy.isNotBlank()) put(ui.text("⏳ $busy", 13.5f, Ui.TEAL, bold = true), 8)

            // 🌐 the public library
            put(ui.text(t("🌐 Public library", "🌐 పబ్లిక్ గ్రంథాలయం"), 15f, Ui.INK, bold = true), 12)
            val q = ui.input(t("Find: GO 30, pension rules, e-crop…", "వెతకండి: GO 30, పెన్షన్ నియమాలు, ఈ-క్రాప్…"), pubQ).apply {
                setSingleLine(true); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            }
            fun go() { pubQ = q.text.toString().trim(); search() }
            q.setOnEditorActionListener { _, _, _ -> go(); true }
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(q, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("🔎", "primary") { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = m.dp(8) })
            }, 6)
            pubFound?.let { f ->
                if (f.length() == 0) put(ui.text(t("Nothing found.", "ఏమీ దొరకలేదు."), 13f, Ui.MUTED), 6)
                for (i in 0 until minOf(f.length(), 15)) f.optJSONObject(i)?.let { o -> put(itemRow(o, false), 6) }
            }
            put(ui.button(t("➕ Add to the public library", "➕ పబ్లిక్ గ్రంథాలయంలో చేర్చు")) { pick("public") }, 8)
            put(ui.text(t("Only things right for everyone. Files with Aadhaar, PAN, phone or bank numbers wait for the owner. Only the text is kept.",
                "అందరికీ పనికొచ్చేవి మాత్రమే. ఆధార్, పాన్, ఫోన్ లేదా బ్యాంక్ నంబర్లు ఉన్న ఫైళ్లు ఓనర్ చూసే వరకు ఆగుతాయి. టెక్స్ట్ మాత్రమే ఉంచుతాం."), 12f, Ui.MUTED), 4)

            // 📁 my files
            put(ui.text(t("📁 My files", "📁 నా ఫైళ్లు"), 15f, Ui.INK, bold = true), 18)
            val a = myList()
            if (a.length() == 0) put(ui.text(t("None yet. Add a file, or share one to \"Give to Habit\" from any app. Then ask the pet: \"what does my land paper say?\"",
                "ఇంకా లేవు. ఫైల్ చేర్చండి, లేదా ఏ యాప్ నుంచైనా Share › \"Give to Habit\". తర్వాత పెట్‌ని అడగండి: \"నా భూమి పేపర్‌లో ఏముంది?\""), 12.5f, Ui.MUTED), 6)
            for (i in a.length() - 1 downTo 0) a.optJSONObject(i)?.let { o -> put(itemRow(o, true), 6) }
            put(ui.row(ui.button(t("➕ Add my file", "➕ నా ఫైల్ చేర్చు"), "primary") { pick("my") },
                ui.button(t("🌐 On the web", "🌐 వెబ్‌లో")) {
                    try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/library#my"))) } catch (e: Exception) { }
                }), 8)
            put(ui.text(t("Your files: only you (and your pet) can read them; the owner sees just the titles to stop misuse.",
                "మీ ఫైళ్లు: మీరు (మీ పెట్) మాత్రమే చదవగలరు; దుర్వినియోగం ఆపడానికి ఓనర్‌కి పేర్లు మాత్రమే కనిపిస్తాయి."), 12f, Ui.MUTED), 4)
        }, 12)
    }

    private fun itemRow(o: JSONObject, my: Boolean) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(m.dp(12), m.dp(9), m.dp(8), m.dp(9)); background = ui.round(Ui.FIELD, 12, Ui.LINE)
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text((if (my) "📄 " else "📘 ") + o.optString("title"), 14f, Ui.INK, bold = true))
            val sn = o.optString("snippet")
            val info = listOfNotNull(o.optString("added").take(10).ifBlank { null }, o.optString("by").ifBlank { null }?.let { t("by $it", "$it చేర్చారు") }).joinToString(" · ")
            addView(ui.text(sn.ifBlank { info }, 12f, Ui.MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (my) addView(ui.button("🗑", "ghost") { removeMine(o) })
        setOnClickListener { open(o, my) }
    }

    private fun search() {
        busy = t("Looking…", "వెతుకుతున్నాను…"); m.render()
        m.io.execute {
            val r = Net.post(store, "/api/library/search", JSONObject().put("q", pubQ), emptyMap(), 30_000)
            m.runOnUiThread {
                busy = ""
                if (r.ok) pubFound = r.json.optJSONArray("found") ?: JSONArray() else m.toast(r.error(t("Couldn't search now.", "ఇప్పుడు వెతకలేకపోయాను.")))
                m.render()
            }
        }
    }

    private fun open(o: JSONObject, my: Boolean) {
        val id = o.optString("id"); val title = o.optString("title")
        val local = if (my) localText(id) else null
        if (local != null) { show(title, local, o, my); return }
        m.io.execute {
            val r = Net.post(store, "/api/library/read", JSONObject().put("id", id), emptyMap(), 30_000)
            m.runOnUiThread {
                val text = r.json.optString("text")
                if (!r.ok || text.isBlank()) { m.toast(r.error(t("Couldn't open it.", "తెరవలేకపోయాను."))); return@runOnUiThread }
                if (my) keepLocal(o, text)
                show(title, text, o, my)
            }
        }
    }

    private fun show(title: String, text: String, o: JSONObject, my: Boolean) {
        val v = TextView(m).apply { this.text = text; textSize = 14f; setTextColor(Ui.INK); setTextIsSelectable(true); setPadding(m.dp(18), m.dp(8), m.dp(18), m.dp(8)) }
        AlertDialog.Builder(m).setTitle(title).setView(ScrollView(m).apply { addView(v) })
            .setPositiveButton(t("🐾 Ask the pet", "🐾 పెట్‌ని అడుగు")) { _, _ ->
                // the pet reads it out simply (the floating pet, if it is on)
                if (!PetService.running) { m.toast(t("Turn on the floating pet first (Pet page).", "ముందు ఫ్లోటింగ్ పెట్ ఆన్ చేయండి (పెట్ పేజీ).")); return@setPositiveButton }
                try {
                    m.startService(Intent(m, PetService::class.java).setAction(PetService.ACTION_ASK)
                        .putExtra("text", if (te()) "\"$title\" ఫైల్‌లో ముఖ్యమైన విషయాలు సులభంగా చెప్పు" else "Tell me simply the main points of the file \"$title\"")
                        .putExtra("context", "The file \"$title\" (${if (my) "the person's own file" else "from Habit's public library"}), the first part:\n" + text.take(6000)))
                } catch (e: Exception) { m.toast(t("Turn on the floating pet first.", "ముందు ఫ్లోటింగ్ పెట్ ఆన్ చేయండి.")) }
            }
            .setNegativeButton(t("Close", "మూసేయి"), null).show()
    }

    private fun removeMine(o: JSONObject) {
        AlertDialog.Builder(m).setMessage(t("Delete \"${o.optString("title")}\" from your files (phone and server)?", "\"${o.optString("title")}\" ని మీ ఫైళ్ల నుంచి తీసేయాలా (ఫోన్, సర్వర్ రెండింటి నుంచి)?"))
            .setPositiveButton(t("Delete", "తీసేయి")) { _, _ ->
                val id = o.optString("id")
                dropLocal(id); m.render()
                if (!o.optBoolean("phoneOnly")) m.io.execute { Net.post(store, "/api/library/delete", JSONObject().put("id", id), emptyMap(), 20_000) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    // ---------- adding files ----------
    fun pick(where: String) {
        pickFor = where
        m.leavingForSystemScreen = true
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "image/jpeg", "image/png", "image/webp"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { m.startActivityForResult(i, MainActivity.REQ_LIB_FILES) } catch (e: Exception) { m.leavingForSystemScreen = false; m.toast(t("Couldn't open your files.", "ఫైళ్లు తెరవలేకపోయాను.")) }
    }
    fun onPicked(uris: List<Uri>) = add(uris, pickFor)

    /** Shared from another app (Share › Give to Habit): which box? */
    fun given(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val boxes = mutableListOf(t("📁 My files (only me)", "📁 నా ఫైళ్లు (నాకు మాత్రమే)") to "my", t("🌐 Public library (everyone)", "🌐 పబ్లిక్ గ్రంథాలయం (అందరికీ)") to "public")
        if (store.officeCode.isNotBlank()) boxes.add(t("🏛 Our office files", "🏛 మా ఆఫీస్ ఫైళ్లు") to "office")
        AlertDialog.Builder(m).setTitle(t("Where should Habit keep ${if (uris.size == 1) "it" else "these ${uris.size} files"}?", "హ్యాబిట్ ఎక్కడ ఉంచాలి?"))
            .setItems(boxes.map { it.first }.toTypedArray()) { _, k ->
                when (val w = boxes[k].second) { "office" -> m.letter.onOfficeFiles(uris); else -> add(uris, w) }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    private fun add(uris: List<Uri>, where: String) {
        if (uris.isEmpty()) return
        busy = t("Adding 1 of ${uris.size}…", "${uris.size} లో 1 చేరుస్తున్నాను…"); m.render()
        m.io.execute {
            var added = 0; var same = 0; var held = 0; var bad = 0; var why = ""
            for ((k, uri) in uris.withIndex()) {
                m.runOnUiThread { busy = t("Adding ${k + 1} of ${uris.size}… (reading a PDF or photo takes a moment)", "${uris.size} లో ${k + 1} చేరుస్తున్నాను…"); m.render() }
                var name = "file"; var type = m.contentResolver.getType(uri) ?: ""
                try { m.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } } } catch (e: Exception) { }
                if (type.isBlank() && name.endsWith(".docx", true)) type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                if (type.isBlank() && name.endsWith(".pdf", true)) type = "application/pdf"
                val bytes = try { m.contentResolver.openInputStream(uri)?.use { inp -> val b = inp.readBytes(); if (b.size > 15 * 1024 * 1024) null else b } } catch (e: Throwable) { null }
                if (bytes == null) { bad++; why = t("too big or couldn't be opened", "చాలా పెద్దది లేదా తెరవలేదు"); continue }
                val r = Net.post(store, if (where == "public") "/api/library/add" else "/api/library/my-add",
                    JSONObject().put("name", name).put("mediaType", type).put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)), emptyMap(), 300_000)
                when {
                    r.ok && r.json.optBoolean("same") -> same++
                    r.ok -> {
                        if (r.json.optBoolean("held")) held++ else added++
                        if (where != "public") r.json.optJSONObject("item")?.let { m.runOnUiThread { keepLocal(it, r.json.optString("text")) } }
                    }
                    else -> { bad++; why = r.error(t("couldn't be read", "చదవలేకపోయాను")); if (r.code == 401) break }
                }
            }
            m.runOnUiThread {
                busy = ""
                m.toast(listOfNotNull(t("✓ $added added", "✓ $added చేర్చాను"),
                    if (same > 0) t("$same were already there", "$same ముందే ఉన్నాయి") else null,
                    if (held > 0) t("⏳ $held wait for the owner (ID/phone/bank numbers)", "⏳ $held ఓనర్ చూసే వరకు ఆగుతాయి") else null,
                    if (bad > 0) "✕ $bad: $why" else null).joinToString(" · "))
                if (where == "public") { pubQ = ""; search() } else m.render()
            }
        }
    }
}
