package com.soulfriend.pet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Translate page: Telugu ⇄ English ⇄ Hindi.
 * Type or paste, speak (tap the language you will speak in), or take a photo of a board, letter or notice.
 * After speaking, the translation is read aloud, so two people can talk to each other.
 */
class TranslatePage(private val m: MainActivity) {
    companion object {
        /** code, name, speech language */
        val LANGS = listOf(Triple("te", "తెలుగు", "te-IN"), Triple("en", "English", "en-IN"), Triple("hi", "हिन्दी", "hi-IN"))
        fun codeOf(name: String) = when {
            name.startsWith("tel", true) -> "te"; name.startsWith("hin", true) -> "hi"; name.startsWith("eng", true) -> "en"; else -> ""
        }
        fun nameOf(code: String) = when (code) { "te" -> "Telugu"; "hi" -> "Hindi"; else -> "English" }
    }

    private var input = ""
    private var to = ""
    private var busy = false
    private var listeningIn = ""          // the language being listened to ("" = not listening)
    private var speakAfter = false        // read the translation aloud (after you spoke into the mic)
    private var photo: ByteArray? = null
    private var preparing = false
    private var resFrom = ""; private var resTo = ""; private var resOriginal = ""; private var resText = ""; private var resPhoto = false
    private var error = ""
    private var box: EditText? = null

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    /** Views side by side, sharing the width equally. */
    private fun equal(vararg views: View) = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = m.dp(8) }) }
    }

    fun build() {
        if (to.isEmpty()) to = store.trTo.ifEmpty { if (store.lang == "en") "te" else "en" }
        m.pageHeader("Translate · అనువాదం", "Telugu, English and Hindi. Type, speak, or take a photo of a board, letter or notice.")

        m.add(ui.section("Translate into").apply {
            put(equal(*LANGS.map { (code, name, _) -> ui.chip(name, to == code) { setTo(code) } }.toTypedArray()), 10)

            val b = ui.input("Type or paste here…", input, multi = true)
            b.onText { input = it }
            box = b
            put(b, 14)

            photo?.let { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) put(ImageView(m).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; adjustViewBounds = false
                    background = ui.round(Ui.LINE, 14); clipToOutline = true; contentDescription = "Your photo"
                    layoutParams = LinearLayout.LayoutParams(-1, m.dp(170))
                }, 10)
                put(ui.row(ui.text("📷 Photo added", 13.5f, Ui.TEAL_DARK, bold = true), ui.button("✕ Remove", "ghost") { photo = null; m.render() }), 6)
            }
            if (preparing) put(ui.text("Getting the photo ready…", 13.5f, Ui.MUTED), 8)

            put(ui.text("🎤 Tap the language you will speak in:", 13.5f, Ui.MUTED), 14)
            put(equal(*LANGS.map { (code, name, _) ->
                val on = listeningIn == code
                ui.button(if (on) "● Stop" else "🎤 $name", if (on) "danger" else "soft") { speak(code) }.apply {
                    contentDescription = if (on) "Stop listening" else "Speak in $name"
                }
            }.toTypedArray()), 6)

            put(equal(ui.button("📷 Camera", "ghost") { camera() }, ui.button("🖼️ Photo", "ghost") { pickPhoto() }, ui.button("📋 Paste", "ghost") { paste() }), 10)
            put(ui.button(if (busy) "Translating…" else "➜ Translate into ${LANGS.first { it.first == to }.second}", "primary") { if (!busy) translate() }, 14)
        }, 12)

        if (busy) m.add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text(if (photo != null) "🔎 Reading the photo and translating…" else "🐾 Translating…", 15.5f, Ui.TEAL_DARK, bold = true))
        }, 10)
        if (error.isNotEmpty()) m.add(ui.card(Ui.DANGER_SOFT).apply { addView(ui.text(error, 14.5f, Ui.DANGER)) }, 10)
        if (resText.isNotEmpty() && !busy) m.add(result(), 10)

        m.add(ui.text("What you translate (text or photo) is sent to the BigD server and Claude AI only to translate it. It isn't saved. " +
            "Translations are made by AI and can have mistakes: check important things with someone you trust.", 12.5f, Ui.MUTED), 16)
    }

    private fun result() = ui.card(Ui.TEAL_SOFT).apply {
        val from = resFrom.ifBlank { "" }
        addView(ui.text((if (resPhoto) "Text in the photo" + (if (from.isNotEmpty()) " ($from)" else "") else from.ifEmpty { "Your words" }) + "  →  " + nameOf(resTo),
            12.5f, Ui.TEAL_DARK, bold = true))
        if (resOriginal.isNotBlank()) put(ui.text(resOriginal, 14f, Ui.MUTED).apply { setTextIsSelectable(true) }, 6)
        put(ui.text(resText, 20f, Ui.INK).apply { setTextIsSelectable(true) }, 8)
        val buttons = ui.row(
            ui.button("🔊 Listen", "primary") { m.speakOut(resText) },
            ui.button("📋 Copy") {
                m.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", resText)); m.toast("Copied")
            },
            ui.button("↗ Share") {
                m.leavingForSystemScreen = true
                m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, resText), "Share the translation"))
            },
            ui.button("⇄ Swap") { swap() })
        put(android.widget.HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(buttons) }, 12)
        put(ui.text("🚩 Report this translation", 12.5f, Ui.MUTED).apply {
            setPadding(0, m.dp(6), 0, m.dp(2))
            setOnClickListener { m.reportAi("Translate", resText, if (resPhoto) "(a photo) " + resOriginal.take(500) else input) }
        }, 8)
    }

    private fun setTo(code: String) {
        if (to == code) return
        to = code; store.trTo = code; m.render()
    }

    /** Listen in [code]; when you stop speaking, translate and read it aloud. */
    private fun speak(code: String) {
        if (listeningIn == code) { listeningIn = ""; m.stopListening(); m.render(); return }
        m.stopTalking()
        if (to == code) { to = if (code == "en") "te" else "en"; store.trTo = to }   // you speak the "into" language: translate the other way
        photo = null; error = ""
        listeningIn = code; m.render()
        m.listen(onPartial = { p -> input = p; box?.setText(p) }, onDone = { heard ->
            if (listeningIn != code) return@listen
            listeningIn = ""
            if (!heard.isNullOrBlank()) { input = heard; speakAfter = true; translate() } else if (m.tab == "translate") m.render()
        }, lang = LANGS.first { it.first == code }.third)
    }

    private fun translate() {
        val t = input.trim()
        val pic = photo
        if (t.isEmpty() && pic == null) { m.toast("Type, say or choose a photo first."); return }
        if (listeningIn.isNotEmpty()) { listeningIn = ""; m.stopListening() }
        m.stopTalking()
        busy = true; error = ""
        val speak = speakAfter; speakAfter = false
        val target = to
        m.render()
        val body = JSONObject().put("to", target).put("text", Privacy.maybe(store, t))
        if (pic != null) body.put("image", JSONObject().put("mediaType", "image/jpeg").put("data", Base64.encodeToString(pic, Base64.NO_WRAP)))
        m.io.execute {
            val r = Net.post(store, "/api/translate", body, emptyMap(), 120_000)
            m.runOnUiThread {
                busy = false
                if (r.ok) {
                    resFrom = r.json.optString("from"); resOriginal = r.json.optString("original"); resText = r.json.optString("text")
                    resTo = target; resPhoto = pic != null
                } else error = r.error("Couldn't translate. Please try again.")
                if (m.tab == "translate") m.render()
                if (r.ok && speak && resText.isNotBlank()) m.speakOut(resText)
            }
        }
    }

    /** The translation becomes the new text, translated back the other way. */
    private fun swap() {
        if (resText.isBlank()) return
        val back = codeOf(resFrom).takeIf { it.isNotEmpty() && it != resTo } ?: if (resTo == "en") "te" else "en"
        input = resText; photo = null
        to = back; store.trTo = back
        resText = ""; resOriginal = ""
        translate()
    }

    private fun paste() {
        val clip = try { m.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(m)?.toString() } catch (e: Exception) { null }
        if (clip.isNullOrBlank()) { m.toast("Nothing copied yet. Long-press some text › Copy, then come back."); return }
        input = clip.take(4000); photo = null; m.render()
    }

    // ---------- photos ----------
    private fun pickPhoto() {
        m.leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { m.startActivityForResult(i, MainActivity.REQ_TR_PHOTO) }
        catch (e: Exception) {
            try { m.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), MainActivity.REQ_TR_PHOTO) }
            catch (e2: Exception) { m.leavingForSystemScreen = false; m.toast("Couldn't open your photos.") }
        }
    }

    private fun camera() {
        val file = CameraFiles.file(m)
        file.parentFile?.mkdirs(); file.delete()
        val uri = CameraFiles.uri(m)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        m.startCamera(i, MainActivity.REQ_TR_CAMERA, "No camera app found. Tap 🖼️ Photo to choose a picture instead.")
    }

    fun onPhoto(uri: Uri) = usePhoto(uri)

    fun onCamera() {
        val f = CameraFiles.file(m)
        if (!f.exists() || f.length() == 0L) { m.toast("No photo was taken."); return }
        usePhoto(Uri.fromFile(f))
    }

    /** Shrinks the photo (the AI reads up to about 1568 px best) and translates the text in it right away. */
    private fun usePhoto(uri: Uri) {
        preparing = true; error = ""; m.render()
        m.io.execute {
            val bytes = Images.prepare(m, uri, 1568)
            m.runOnUiThread {
                preparing = false
                try { CameraFiles.file(m).delete() } catch (e: Exception) { }
                if (bytes == null) { m.toast("Couldn't open that photo."); m.render(); return@runOnUiThread }
                photo = bytes; input = ""
                translate()
            }
        }
    }
}

/** Lets the phone's camera app save one photo for us (a tiny FileProvider, so no extra library or storage permission is needed). */
class CameraFiles : ContentProvider() {
    companion object {
        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.camera/photo.jpg")
        fun file(ctx: Context) = File(File(ctx.cacheDir, "camera"), "photo.jpg")
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.lastPathSegment != "photo.jpg") throw FileNotFoundException()
        val f = file(ctx); f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = context?.let { file(it) }
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) (f?.length() ?: 0L) else "photo.jpg" }.toTypedArray()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
