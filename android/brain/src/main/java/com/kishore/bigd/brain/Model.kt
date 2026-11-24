package com.kishore.bigd.brain

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The tiny brain's model file, downloaded once into this app. Two sizes:
 *  small: Google's Gemma 3 1B for phones (about 0.6 GB, Gemma Terms of Use), given by the Habit server;
 *  big: Google's Gemma 4 E2B for phones (about 2.6 GB, Apache 2.0), from Hugging Face: smarter, better Telugu. */
object Model {
    class Size(val key: String, val name: String, val mb: Int, val minBytes: Long, val ramGb: Int)
    val SMALL = Size("small", "gemma3-1b-it-int4.litertlm", 600, 300_000_000L, 3)
    val BIG = Size("big", "gemma-4-E2B-it.litertlm", 2600, 500_000_000L, 6)
    const val NAME = "gemma-4-E2B-it.litertlm"
    private const val DEFAULT_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$NAME"
    const val DEFAULT_SERVER = "https://bigdai.tech"

    /** The size on this phone (or being downloaded, or last chosen). Phones that already have the big one keep it. */
    fun size(ctx: Context): Size = when (prefs(ctx).getString("size", "")) {
        "small" -> SMALL
        "big" -> BIG
        else -> if (File(ctx.getExternalFilesDir(null), BIG.name).exists()) BIG else SMALL
    }
    fun choose(ctx: Context, s: Size) { prefs(ctx).edit().putString("size", s.key).apply() }
    fun prefs(ctx: Context) = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), size(ctx).name)
    private fun part(ctx: Context) = File(ctx.getExternalFilesDir(null), size(ctx).name + ".part")
    fun ready(ctx: Context) = file(ctx).let { it.exists() && it.length() > size(ctx).minBytes }
    fun server(ctx: Context) = prefs(ctx).getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER

    /** This phone's memory in GB (the tiny brain wants 6 or more). */
    fun ramGb(ctx: Context): Int {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return Math.round(mi.totalMem / 1_073_741_824.0).toInt()
    }

    /** Where to download [s] from (the Habit server says; the big one can also come straight from Hugging Face).
     *  Blocking. Gives (url, problem): no url when the server can't give it yet, with the reason. */
    fun url(ctx: Context, s: Size): Pair<String?, String> {
        val fallback = if (s === BIG) DEFAULT_URL else null
        return try {
            val c = URL(server(ctx) + "/brain/phone").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val j = if (c.responseCode == 200) org.json.JSONObject(c.inputStream.bufferedReader().readText()) else null
            c.disconnect()
            if (s === BIG) (j?.optString("url")?.takeIf { it.startsWith("https://") } ?: DEFAULT_URL) to ""
            else {
                val sm = j?.optJSONObject("small")
                val u = sm?.optString("url")?.takeIf { it.startsWith("https://") }
                when {
                    u == null -> null to "The Habit server can't give the small brain yet (it needs an update). Try the big one, or try again later."
                    sm?.optBoolean("ready") != true -> null to ("The Habit server is getting the small brain ready" +
                        (if (sm?.has("progress") == true) " (${sm?.optInt("progress")}%)" else "") + ". Try again in 10 minutes." +
                        (sm?.optString("why")?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""))
                    else -> u to ""
                }
            }
        } catch (e: Exception) { fallback to (if (fallback == null) "Can't reach the Habit server. Check the internet and try again." else "") }
    }

    fun downloading(ctx: Context) = prefs(ctx).getLong("download", -1L) >= 0

    /** Starts the download (Android's own downloader: it keeps going in the background and shows its progress). */
    fun start(ctx: Context, s: Size, url: String, mobileData: Boolean) {
        choose(ctx, s)
        part(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Habit Brain")
            .setDescription("Downloading the tiny brain (about ${"%.1f".format(s.mb / 1000.0)} GB)")
            .setDestinationInExternalFilesDir(ctx, null, s.name + ".part")
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
                if (part(ctx).length() > size(ctx).minBytes) { file(ctx).delete(); part(ctx).renameTo(file(ctx)); p.edit().putLong("download", -1L).putString("problem", "").apply() }
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

    /** Is this size's brain already on the phone (both can be kept, and changed between at once)? */
    fun has(ctx: Context, s: Size) = File(ctx.getExternalFilesDir(null), s.name).let { it.exists() && it.length() > s.minBytes }
    /** Removes the brain in use; if the other one is on the phone, that one is used from now on. */
    fun delete(ctx: Context) {
        cancel(ctx); file(ctx).delete()
        val other = if (size(ctx) === BIG) SMALL else BIG
        if (has(ctx, other)) choose(ctx, other)
    }
}
