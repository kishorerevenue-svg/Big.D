package com.soulfriend.pet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Good-morning briefing: panchangam, weather, today's reminders and to-dos, once a day. */
object Briefing {
    private const val CHANNEL = "briefing"
    private const val JOB_ID = 7701

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 7700, Intent(ctx, BriefingReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Sets (or clears) tomorrow's / today's briefing alarm. */
    fun schedule(ctx: Context) {
        val store = Store(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(ctx))
        if (!store.briefOn) return
        val now = ZonedDateTime.now()
        var t = now.withHour(store.briefHour).withMinute(store.briefMinute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        val at = t.toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
    }

    /** Fetches and shows the briefing as soon as there is internet. */
    fun runNow(ctx: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, BriefingJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(60 * 60_000L)
            .build()
        ctx.getSystemService(JobScheduler::class.java).schedule(job)
    }

    /** Today's reminders as short lines, for the server. */
    fun todaysReminders(store: Store): List<String> {
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        return store.reminders().filter { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
            .sortedBy { it.at }
            .map { "%s %s".format(java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(java.time.Instant.ofEpochMilli(it.at).atZone(zone)), it.text) }
    }

    fun show(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Good-morning briefing", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("☀️ Good morning from BigD AI")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(7702, n) } catch (e: SecurityException) { }
        if (PetService.running) {
            try {
                ctx.startService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_SAY)
                    .putExtra("text", text).putExtra("mood", "excited").putExtra("prefix", "☀️ "))
            } catch (e: Exception) { }
        }
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Briefing.runNow(ctx)
        Briefing.schedule(ctx)   // tomorrow
    }
}

class BriefingJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val store = Store(this)
        Brain(store).briefing(Briefing.todaysReminders(store),
            onOk = { text -> Briefing.show(this, text); jobFinished(params, false) },
            onError = { _ -> jobFinished(params, false) })
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
