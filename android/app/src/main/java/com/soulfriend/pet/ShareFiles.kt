package com.soulfriend.pet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Lets the app the person shares with (WhatsApp…) read one file from the app's cache: cache/share/<name>. Read only. */
class ShareFiles : ContentProvider() {
    companion object {
        fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }
        fun uri(ctx: Context, name: String): Uri = Uri.parse("content://${ctx.packageName}.share/" + Uri.encode(name))

        /** Downloads [url] into the share folder as [name] (old shared files are cleaned up). Blocking: call off the main thread. */
        fun download(ctx: Context, url: String, name: String, maxBytes: Long = 60L * 1024 * 1024): File? {
            val d = dir(ctx)
            d.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
            val f = File(d, name)
            if (f.exists() && f.length() > 0) return f
            val part = File(d, "$name.part")
            return try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                if (c.responseCode !in 200..299) return null
                var n = 0L
                c.inputStream.use { inp -> part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = inp.read(buf); if (r < 0) break; n += r; if (n > maxBytes) throw IllegalStateException("too big"); out.write(buf, 0, r) }
                } }
                if (n < 1000 || !part.renameTo(f)) { part.delete(); null } else f
            } catch (e: Exception) { part.delete(); null }
        }
    }

    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.startsWith(".") || name.endsWith(".part")) throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment.orEmpty().lowercase()
        return when { n.endsWith(".mp4") -> "video/mp4"; n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"; n.endsWith(".png") -> "image/png"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "")) ?: "application/octet-stream" }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
