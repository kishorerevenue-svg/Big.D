package com.soulfriend.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * One-tap updates for Habit AI Lite (the edition shared on WhatsApp; Play Store editions are updated by the Play Store).
 * Once in a while it asks the server whether a newer version is out; the Home page then shows a card, and
 * "Update" opens the download in the browser. Android always asks before installing, and the new version
 * installs over the old one, so everything on the phone stays.
 */
object Updates {
    class Info(val code: Int, val name: String, val notes: String, val sizeMb: Int)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The newer version already found (null when this one is the newest). */
    fun waiting(store: Store): Info? =
        if (BuildConfig.SELF_UPDATE && store.updateCode > BuildConfig.VERSION_CODE) Info(store.updateCode, store.updateName, store.updateNotes, store.updateMb) else null

    /** Asks the server (at most every 6 hours unless [force]). [done] runs on the main thread with the newer version, or null. */
    fun check(store: Store, force: Boolean = false, done: (Info?) -> Unit = {}) {
        if (!BuildConfig.SELF_UPDATE || !store.server.startsWith("https://")) { done(null); return }
        if (!force && System.currentTimeMillis() - store.updateCheckedAt < 6 * 3600_000L) { done(waiting(store)); return }
        io.execute {
            val info = try {
                val c = URL(store.server + "/app/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                val ok = c.responseCode == 200
                val j = if (ok) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
                c.disconnect()
                j
            } catch (e: Exception) { null }
            main.post {
                if (info != null) {
                    store.updateCheckedAt = System.currentTimeMillis()
                    store.updateCode = info.optInt("versionCode")
                    store.updateName = info.optString("versionName")
                    store.updateNotes = info.optString("notes").take(300)
                    store.updateMb = (info.optLong("size") / 1_000_000L).toInt().coerceAtLeast(1)
                }
                done(waiting(store))
            }
        }
    }

    /** Opens the newest download in the browser; Android then asks "Do you want to update this app?". */
    fun open(ctx: Context, store: Store) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/download")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    /** A link to share the app (always the newest version). */
    fun link(store: Store) = store.server + "/download"
}
