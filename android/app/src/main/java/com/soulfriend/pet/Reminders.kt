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
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reminders and timers: scheduled with the phone's alarm system, shown as a notification, spoken by the pet. */
object Reminders {
    const val CHANNEL = "reminders"
    const val ACTION_DONE = "com.soulfriend.pet.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.soulfriend.pet.REMINDER_SNOOZE"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders and timers set by BigD AI"
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

    fun add(ctx: Context, at: Long, text: String, repeat: String = ""): Pair<Reminder, Boolean> {
        val r = Store(ctx).addReminder(at, text, cleanRepeat(repeat))
        return Pair(r, schedule(ctx, r))
    }

    fun cancel(ctx: Context, r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, r))
        Store(ctx).removeReminder(r.id)
    }

    fun cleanRepeat(s: String) = s.lowercase().trim().let { if (it in setOf("daily", "weekdays", "weekly", "monthly")) it else "" }

    fun repeatLabel(repeat: String) = when (repeat) {
        "daily" -> "every day"; "weekdays" -> "Mon–Fri"; "weekly" -> "every week"; "monthly" -> "every month"; else -> ""
    }

    /** The next time a repeating reminder should ring after [now]. */
    fun nextTime(at: Long, repeat: String, now: Long = System.currentTimeMillis()): Long {
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        var guard = 0
        while (t.toInstant().toEpochMilli() <= now && guard++ < 2000) {
            t = when (repeat) {
                "daily" -> t.plusDays(1)
                "weekly" -> t.plusWeeks(1)
                "monthly" -> t.plusMonths(1)
                "weekdays" -> { var n = t.plusDays(1); while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1); n }
                else -> return at
            }
        }
        return t.toInstant().toEpochMilli()
    }

    fun rescheduleAll(ctx: Context) {
        val store = Store(ctx); val now = System.currentTimeMillis()
        store.reminders().forEach {
            when {
                it.repeat.isNotEmpty() -> { val r = it.copy(at = nextTime(it.at, it.repeat, now)); store.updateReminder(r); schedule(ctx, r) }
                it.at > now - 60_000 -> schedule(ctx, it.copy(at = maxOf(it.at, now + 5_000)))
                else -> store.removeReminder(it.id)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store(ctx)
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "Reminder"
        val nm = ctx.getSystemService(NotificationManager::class.java)

        when (intent.action) {
            Reminders.ACTION_DONE -> { nm.cancel(1000 + id); return }
            Reminders.ACTION_SNOOZE -> {
                nm.cancel(1000 + id)
                Reminders.add(ctx, System.currentTimeMillis() + 10 * 60_000L, text)
                return
            }
        }

        // ring now; repeating reminders schedule their next time
        val r = store.reminder(id)
        if (r != null && r.repeat.isNotEmpty()) {
            val next = r.copy(at = Reminders.nextTime(r.at, r.repeat))
            store.updateReminder(next); Reminders.schedule(ctx, next)
        } else store.removeReminder(id)

        Reminders.ensureChannel(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val done = PendingIntent.getBroadcast(ctx, 50_000 + id, Intent(ctx, ReminderReceiver::class.java).setAction(Reminders.ACTION_DONE).putExtra("id", id).putExtra("text", text), flags)
        val snooze = PendingIntent.getBroadcast(ctx, 60_000 + id, Intent(ctx, ReminderReceiver::class.java).setAction(Reminders.ACTION_SNOOZE).putExtra("id", id).putExtra("text", text), flags)
        val n = Notification.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("BigD AI reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Done ✓", done).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", snooze).build())
            .setAutoCancel(true)
            .build()
        try { nm.notify(1000 + id, n) } catch (e: SecurityException) { }
        if (PetService.running) {
            try { ctx.startService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_SAY).putExtra("text", text)) } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rescheduleAll(ctx)
            Briefing.schedule(ctx)
        }
    }
}
