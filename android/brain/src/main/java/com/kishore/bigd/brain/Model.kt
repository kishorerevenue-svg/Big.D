package com.kishore.bigd.brain

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The tiny brain's model file: Google's Gemma 4 E2B for phones (Apache 2.0), downloaded once into this app. */
object Model {
    const val NAME = "gemma-4-E2B-it.litertlm"
    const val SIZE_MB = 2600
    private const val DEFAULT_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$NAME"
    const val DEFAULT_SERVER = "https://bigdai.tech"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), NAME)
    private fun part(ctx: Context) = File(ctx.getExternalFilesDir(null), "$NAME.part")
    fun ready(ctx: Context) = file(ctx).let { it.exists() && it.length() > 500_000_000L }
    fun server(ctx: Context) = prefs(ctx).getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER

    /** This phone's memory in GB (the tiny brain wants 6 or more). */
    fun ramGb(ctx: Context): Int {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return Math.round(mi.totalMem / 1_073_741_824.0).toInt()
    }

    /** Where to download it from: the BigD server can name another place (owner's choice), else Hugging Face. Blocking. */
    fun url(ctx: Context): String = try {
        val c = URL(server(ctx) + "/brain/phone").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val u = if (c.responseCode == 200) Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(c.inputStream.bufferedReader().readText())?.groupValues?.get(1) else null
        c.disconnect()
        u?.replace("\\/", "/")?.takeIf { it.startsWith("https://") } ?: DEFAULT_URL
    } catch (e: Exception) { DEFAULT_URL }

    fun downloading(ctx: Context) = prefs(ctx).getLong("download", -1L) >= 0

    /** Starts the download (Android's own downloader: it keeps going in the background and shows its progress). */
    fun start(ctx: Context, url: String, mobileData: Boolean) {
        part(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("BigD Brain")
            .setDescription("Downloading the tiny brain (about 2.6 GB)")
            .setDestinationInExternalFilesDir(ctx, null, "$NAME.part")
            .setAllowedOverMetered(mobileData)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        prefs(ctx).edit().putLong("download", id).putString("problem", "").apply()
    }

    /** Bytes so far and in all, while downloading. */
    fun progress(ctx: Context): Pair<Long, Long>? {
        val id = prefs(ctx).getLong("download", -1L); if (id < 0) return null
        ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) to c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
        return null
    }

    /** The download ended: keep the file, or say what went wrong. */
    fun finish(ctx: Context) {
        val p = prefs(ctx); val id = p.getLong("download", -1L); if (id < 0) return
        val dm = ctx.getSystemService(DownloadManager::class.java)
        var status = -1; var reason = 0
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            }
        }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL ->
                if (part(ctx).length() > 500_000_000L) { file(ctx).delete(); part(ctx).renameTo(file(ctx)); p.edit().putLong("download", -1L).putString("problem", "").apply() }
                else { part(ctx).delete(); p.edit().putLong("download", -1L).putString("problem", "The file that came was too small. The download place may need a login; ask the app owner.").apply() }
            DownloadManager.STATUS_FAILED, -1 -> {   // failed, or removed from Android's downloads
                part(ctx).delete(); p.edit().putLong("download", -1L).putString("problem", "The download stopped (reason $reason). Check the internet and free space, and try again.").apply()
            }
            else -> { }                               // still waiting or downloading
        }
    }

    fun cancel(ctx: Context) {
        val id = prefs(ctx).getLong("download", -1L)
        if (id >= 0) try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (e: Exception) { }
        part(ctx).delete(); prefs(ctx).edit().putLong("download", -1L).apply()
    }

    fun delete(ctx: Context) { cancel(ctx); file(ctx).delete() }
}
