package com.soulfriend.pet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * BigD AI Lite: finds ID cards in the phone's screenshots (and WhatsApp pictures) by itself and files them in the Vault,
 * a folder for each person. Everything happens on the phone. After the first look, new pictures are checked
 * only while the phone is charging (kind to the battery). Google Play allows reading all photos only for gallery apps,
 * so the Play Store edition lets you choose the pictures instead (BuildConfig.AUTO_CARDS = false).
 */
object AutoCards {
    private const val JOB_ID = 7701
    const val FIRST_LOOK = 400                       // the first time: the newest 400 pictures

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(ctx: Context) = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && ctx.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

    /** Screenshots and WhatsApp pictures added after [since] (seconds), newest first, at most [limit]. */
    fun pictures(ctx: Context, since: Long, limit: Int): List<Pair<Uri, Long>> {
        val out = ArrayList<Pair<Uri, Long>>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val where = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val sel = "($where LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR $where LIKE ?) AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
        val args = arrayOf("%Screenshot%", "%Screenshot%", "%WhatsApp Images%", since.toString())
        try {
            ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), sel, args,
                MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val at = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext() && out.size < limit) out.add(ContentUris.withAppendedId(base, c.getLong(id)) to c.getLong(at))
            }
        } catch (e: Exception) { }
        return out
    }

    class Outcome { val added = ArrayList<VaultItem>(); var same = 0; var none = 0; var checked = 0; var problem = "" }

    /** Saves a card in the Vault unless it is already there. Returns the new item, or null when it was there already. */
    fun keep(vault: VaultStore, c: CardFinder.Card, jpeg: ByteArray): VaultItem? {
        val have = vault.items()
        val dup = have.any { it.type == c.type && ((c.number.isNotBlank() && CardFinder.key(it.number) == CardFinder.key(c.number)) ||
            (c.number.isBlank() && c.holder.isNotBlank() && CardFinder.samePerson(it.holder, c.holder))) }
        if (dup) return null
        // the same person as a folder that is already there: use that folder's name
        val holder = have.map { it.holder }.firstOrNull { it.isNotBlank() && CardFinder.samePerson(it, c.holder) } ?: c.holder
        val id = "v" + System.currentTimeMillis() + (100..999).random()
        val item = VaultItem(id, c.type, VaultItem.label(c.type) + if (holder.isNotBlank()) " · " + holder.substringBefore(' ') else "",
            c.number, holder, notes = "Found in a screenshot. Please check the details.", photo = true)
        vault.savePhoto(id, jpeg); vault.put(item)
        return item
    }

    /** Looks at the pictures added since the last look. Blocking: run it on its own thread. [step] returns false to stop. */
    fun scan(ctx: Context, store: Store, vault: VaultStore, limit: Int, step: (done: Int, total: Int, o: Outcome) -> Boolean): Outcome {
        val o = Outcome()
        val list = pictures(ctx, store.autoCardsUpTo, limit)
        var newest = store.autoCardsUpTo
        for ((i, p) in list.withIndex()) {
            if (!step(i, list.size, o)) break
            when (val r = CardFinder.find(ctx, p.first)) {
                is CardFinder.Result.Found -> try { if (keep(vault, r.card, r.jpeg)?.let { o.added.add(it) } == null) o.same++ } catch (e: Exception) { o.problem = "Couldn't save one card." }
                is CardFinder.Result.NotACard -> o.none++
                is CardFinder.Result.Problem -> { o.problem = r.message; if (r.message.contains("text reader")) break }
            }
            o.checked++
            if (p.second > newest) newest = p.second
        }
        // pictures are looked at newest first, so "done up to here" moves on only after a full pass (a stopped pass starts again next time)
        if (list.isNotEmpty() && o.checked == list.size) store.autoCardsUpTo = maxOf(store.autoCardsUpTo, newest)
        store.autoCardsFound = store.autoCardsFound + o.added.size
        store.autoCardsAt = System.currentTimeMillis()
        step(list.size, list.size, o)
        return o
    }

    /** New pictures are checked a couple of times a day, only while the phone is charging and the battery is fine. */
    fun schedule(ctx: Context) {
        try {
            ctx.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, CardJob::class.java))
                .setRequiresCharging(true).setRequiresBatteryNotLow(true).setPeriodic(8 * 3600_000L).setPersisted(true).build())
        } catch (e: Exception) { }
    }

    fun cancel(ctx: Context) { try { ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID) } catch (e: Exception) { } }

    /** "Found 2 new cards", only when something was found. */
    fun tell(ctx: Context, o: Outcome) {
        if (o.added.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("vault") == null) nm.createNotificationChannel(NotificationChannel("vault", "Vault", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 78, Intent(ctx, MainActivity::class.java).putExtra("tab", "vault").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, "vault").setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("🔒 ${o.added.size} new card${if (o.added.size == 1) "" else "s"} in your Vault")
            .setContentText("Found in your screenshots and saved on this phone. Tap to check them.")
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(7702, n) } catch (e: SecurityException) { }
    }
}

/** Runs while the phone charges: looks at new screenshots for cards. */
class CardJob : JobService() {
    @Volatile private var stop = false
    override fun onStartJob(params: JobParameters): Boolean {
        val store = Store(this)
        if (!BuildConfig.AUTO_CARDS || !store.autoCards || !AutoCards.allowed(this)) return false
        stop = false
        Thread {
            val o = try { AutoCards.scan(this, store, VaultStore(this), 150) { _, _, _ -> !stop } } catch (e: Throwable) { null }
            if (o != null) AutoCards.tell(this, o)
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stop = true; return true }
}
