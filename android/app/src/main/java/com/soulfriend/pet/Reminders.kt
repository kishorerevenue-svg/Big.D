package com.soulfriend.pet

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Reminders and timers: scheduled with the phone's alarm system, shown as a notification, spoken by the puppy. */
object Reminders {
    const val CHANNEL = "reminders"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders and timers set by Soul Friend"
            })
        }
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("text", r.text)
        return PendingIntent.getBroadcast(ctx, r.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Returns true when the reminder will ring at the exact minute (otherwise Android may delay it a little). */
    fun schedule(ctx: Context, r: Reminder): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r))
        return exact
    }

    fun add(ctx: Context, at: Long, text: String): Pair<Reminder, Boolean> {
        val r = Store(ctx).addReminder(at, text)
        return Pair(r, schedule(ctx, r))
    }

    fun cancel(ctx: Context, r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, r))
        Store(ctx).removeReminder(r.id)
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        Store(ctx).reminders().forEach { if (it.at > now - 60_000) schedule(ctx, it.copy(at = maxOf(it.at, now + 5_000))) else Store(ctx).removeReminder(it.id) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "Reminder"
        Store(ctx).removeReminder(id)
        Reminders.ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("Soul Friend reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(1000 + id, n) } catch (e: SecurityException) { }
        if (PetService.running) {
            try { ctx.startService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_SAY).putExtra("text", text)) } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reminders.rescheduleAll(ctx)
    }
}
