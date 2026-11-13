package com.soulfriend.pet

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Habit voice and video calls (one-to-one).
 *  - The call itself runs in CallActivity's WebView (WebRTC, assets/call.html): sound and video go straight between the
 *    two phones, locked (DTLS-SRTP). When phones can't reach each other directly (mobile data), Habit's relay on the
 *    server (coturn, turn-setup.sh) passes the locked packets on without being able to open them.
 *  - The notes "offer" (with the caller's connection details), "ringing", "answer" and "end" go through Habit Chat,
 *    locked end to end like messages (kind "call"); they are not shown. A short line stays in the chat (kind "clog"):
 *    "📞 Voice call · 2:31", "📞 Missed voice call"…
 *  - A phone rings while Habit is open or the pet is on screen (Habit Chat is connected then). When Habit is closed, a
 *    Firebase push wakes it (Push.kt, once the server has push set up) and it rings like a normal call; without push it
 *    learns about the call at its next check and shows a missed call.
 */
object CallManager {
    const val RING = "habit_calls_ring"
    const val NOW = "habit_calls_now"
    const val RING_ID = 4711
    const val NOW_ID = 4712
    /** How long an incoming call rings, and how long a caller waits. */
    const val RING_MS = 45_000L
    const val WAIT_MS = 50_000L

    class Call(val id: String, val cid: String, val peer: String, val video: Boolean, val incoming: Boolean) {
        /** calling (my phone sent the offer), ringing (theirs or mine rings), connecting, connected, ended */
        @Volatile var state = if (incoming) "ringing" else "calling"
        @Volatile var offer = ""            // incoming: their offer
        @Volatile var answer = ""           // outgoing: their answer
        @Volatile var startedAt = System.currentTimeMillis()
        @Volatile var connectedAt = 0L
        @Volatile var reason = ""
        @Volatile var logged = false
        @Volatile var answered = false
    }

    @Volatile var active: Call? = null
    /** Server time minus phone time (from Habit Chat's sync), to tell how old a ring is. */
    @Volatile var skew = 0L
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    fun changed() { main.post { listeners.forEach { try { it() } catch (e: Exception) { } } } }
    private fun te(ctx: Context) = Store(ctx).lang == "te"

    // ---------- starting a call ----------
    /** I call the other person in this one-to-one chat (permissions were asked already). */
    fun startOutgoing(a: Activity, cid: String, video: Boolean) {
        val cur = active
        if (cur != null) { a.startActivity(Intent(a, CallActivity::class.java)); return }
        val c = ChatDb.get(a).conv(cid) ?: return
        val me = ChatSync.myUid(a)
        val peer = c.members().map { it.optString("uid") }.firstOrNull { it.isNotEmpty() && it != me } ?: return
        val call = Call("k" + System.currentTimeMillis().toString(36) + (1000..9999).random(), cid, peer, video, false)
        active = call
        a.startActivity(Intent(a, CallActivity::class.java))
        main.postDelayed({ if (active === call && (call.state == "calling" || call.state == "ringing")) end(a.applicationContext, call, "timeout", true) }, WAIT_MS)
    }

    /** Sends a note to the other phone, locked like a message. */
    fun send(ctx: Context, call: Call, cmd: String, extra: JSONObject? = null) {
        val body = JSONObject().put("t", "call").put("cmd", cmd).put("callId", call.id).put("video", call.video)
        extra?.keys()?.forEach { body.put(it, extra.get(it)) }
        val app = ctx.applicationContext
        pool.execute { try { ChatSync.signal(app, Store(app), call.cid, body) } catch (e: Exception) { } }
    }

    // ---------- notes from the other phone (from ChatSync) ----------
    fun onSignal(ctx: Context, store: Store, m: ChatMsg) {
        val app = ctx.applicationContext
        if (m.sender == ChatSync.myUid(ctx)) return
        val b = m.body
        val id = b.optString("callId")
        val cmd = b.optString("cmd")
        main.post {
            val cur = active
            when (cmd) {
                "offer" -> {
                    val conv = ChatDb.get(app).conv(m.cid)
                    val age = System.currentTimeMillis() + skew - m.at
                    when {
                        cur != null && cur.id == id -> { }                                           // the same ring twice
                        conv == null || conv.blocked || !conv.accepted -> { }                         // not from someone I accepted
                        age > WAIT_MS -> log(app, Call(id, m.cid, m.sender, b.optBoolean("video"), true).apply { startedAt = m.at }, "missed")
                        cur != null -> {                                                              // already on a call: busy
                            val other = Call(id, m.cid, m.sender, b.optBoolean("video"), true).apply { startedAt = m.at }
                            send(app, other, "end", JSONObject().put("reason", "busy"))
                            log(app, other, "missed")
                        }
                        else -> {
                            val call = Call(id, m.cid, m.sender, b.optBoolean("video"), true).apply { offer = b.optString("sdp") }
                            active = call
                            send(app, call, "ringing")
                            ring(app, call)
                            main.postDelayed({ if (active === call && call.state == "ringing") end(app, call, "timeout", true) }, RING_MS)
                        }
                    }
                }
                "ringing" -> if (cur != null && cur.id == id && !cur.incoming && cur.state == "calling") { cur.state = "ringing"; changed() }
                "answer" -> if (cur != null && cur.id == id && !cur.incoming && cur.state != "ended") { cur.answer = b.optString("sdp"); cur.state = "connecting"; changed() }
                "end" -> if (cur != null && cur.id == id) end(app, cur, "their-" + b.optString("reason", "hangup"), false)
            }
        }
    }

    // ---------- ringing ----------
    private fun ring(ctx: Context, call: Call) {
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val conv = ChatDb.get(ctx).conv(call.cid)
        val name = conv?.name ?: "Habit"
        val te = te(ctx)
        val pf = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val screen = Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(ctx, 4701, screen, pf)
        val answer = PendingIntent.getActivity(ctx, 4702, Intent(ctx, CallActivity::class.java).putExtra("answer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pf)
        val decline = PendingIntent.getBroadcast(ctx, 4703, Intent(ctx, CallActionReceiver::class.java).setAction("decline"), pf)
        val what = if (call.video) (if (te) "వీడియో కాల్ వస్తోంది" else "Incoming video call") else (if (te) "వాయిస్ కాల్ వస్తోంది" else "Incoming voice call")
        val n = Notification.Builder(ctx, RING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle((conv?.emoji?.let { "$it " } ?: "") + name).setContentText("Habit · $what")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(false)
            .setFullScreenIntent(open, true).setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), if (te) "తిరస్కరించు" else "Decline", decline).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.sym_action_call), if (te) "ఎత్తండి" else "Answer", answer).build())
            .build().apply { this.flags = this.flags or Notification.FLAG_INSISTENT }    // the ringtone repeats until answered or stopped
        try { nm.notify(RING_ID, n) } catch (e: SecurityException) { }
        // the call screen too, when Android lets it open (Habit is open, or may show over other apps for the pet)
        try { ctx.startActivity(screen) } catch (e: Exception) { }
        // while it rings, keep up with the caller (a hang-up stops the ringing at once), also when a push woke the phone
        ChatSync.hold(ctx, "ring")
        changed()
    }

    fun stopRinging(ctx: Context) { try { ctx.getSystemService(NotificationManager::class.java)?.cancel(RING_ID) } catch (e: Exception) { } }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(RING) == null) nm.createNotificationChannel(NotificationChannel(RING, "Habit calls (ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings for Habit voice and video calls"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 900, 700, 900, 700)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        if (nm.getNotificationChannel(NOW) == null) nm.createNotificationChannel(NotificationChannel(NOW, "Habit calls (on a call)", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while you are on a Habit call"; setSound(null, null)
        })
    }

    // ---------- during and after the call ----------
    /** The call screen: the two phones are talking now. */
    fun connected(ctx: Context, call: Call) {
        if (call.connectedAt == 0L) call.connectedAt = System.currentTimeMillis()
        call.state = "connected"
        stopRinging(ctx)
        CallService.start(ctx, call.video)
        changed()
    }

    /**
     * Ends the call ([reason]: hangup, cancel, decline, timeout, busy, failed, or "their-…" when the other phone ended it).
     * [tell]: send "end" to the other phone.
     */
    fun end(ctx: Context, call: Call, reason: String, tell: Boolean) {
        if (call.state == "ended") return
        call.state = "ended"; call.reason = reason
        if (tell) send(ctx, call, "end", JSONObject().put("reason", reason))
        stopRinging(ctx)
        ChatSync.release("ring")
        CallService.stop(ctx)
        val r = reason.removePrefix("their-")
        val st = when {
            call.connectedAt > 0 -> "done"
            call.incoming -> if (reason == "decline") "declined" else "missed"
            r == "decline" -> "declined"
            r == "busy" -> "busy"
            r == "cancel" && !reason.startsWith("their-") -> "cancelled"
            r == "failed" || r.startsWith("error") -> "failed"
            else -> "noanswer"
        }
        log(ctx, call, st)
        if (active === call) active = null
        changed()
    }

    /** The line that stays in the chat. */
    private fun log(ctx: Context, call: Call, st: String) {
        if (call.logged) return
        call.logged = true
        val me = ChatSync.myUid(ctx)
        val dur = if (call.connectedAt > 0) ((System.currentTimeMillis() - call.connectedAt) / 1000).toInt() else 0
        val body = JSONObject().put("t", "clog").put("video", call.video).put("out", !call.incoming).put("st", st).put("dur", dur)
        val m = ChatMsg(call.cid, "c" + call.id, 0, if (call.incoming) call.peer else me, call.startedAt, "clog", body, 1)
        val db = ChatDb.get(ctx)
        if (db.has(call.cid, m.id)) return
        db.putMsg(m)
        val missed = st == "missed"
        db.touch(call.cid, m.at, logText(ctx, m), missed && call.cid != ChatSync.openCid)
        if (missed) ChatSync.notify(ctx, listOf(m))
        ChatSync.changed()
    }

    fun logText(ctx: Context, m: ChatMsg): String {
        val te = te(ctx)
        val b = m.body
        val v = b.optBoolean("video")
        val kind = if (v) (if (te) "వీడియో కాల్" else "Video call") else (if (te) "వాయిస్ కాల్" else "Voice call")
        val d = b.optInt("dur")
        return (if (v) "🎥 " else "📞 ") + when (b.optString("st")) {
            "done" -> "$kind · %d:%02d".format(d / 60, d % 60)
            "missed" -> if (te) "మిస్డ్ $kind" else "Missed ${kind.lowercase()}"
            "declined" -> "$kind · " + (if (te) "తిరస్కరించారు" else "Declined")
            "busy" -> "$kind · " + (if (te) "బిజీగా ఉన్నారు" else "Busy")
            "noanswer" -> "$kind · " + (if (te) "జవాబు లేదు" else "No answer")
            "cancelled" -> "$kind · " + (if (te) "రద్దు చేశారు" else "Cancelled")
            else -> "$kind · " + (if (te) "కనెక్ట్ కాలేదు" else "Couldn't connect")
        }
    }
}

/** "Decline" on the ringing notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val call = CallManager.active ?: return
        if (intent.action == "decline" && call.incoming && call.state == "ringing") CallManager.end(ctx.applicationContext, call, "decline", true)
    }
}

/** Keeps the call going (and the microphone and camera allowed) when you leave the call screen; tap to go back. */
class CallService : Service() {
    companion object {
        fun start(ctx: Context, video: Boolean) {
            try { ctx.startForegroundService(Intent(ctx, CallService::class.java).putExtra("video", video)) } catch (e: Exception) { }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, CallService::class.java)) } catch (e: Exception) { } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallManager.channels(this)
        val call = CallManager.active
        if (call == null) { stopSelf(); return START_NOT_STICKY }
        val te = Store(this).lang == "te"
        val name = ChatDb.get(this).conv(call.cid)?.name ?: "Habit"
        val open = PendingIntent.getActivity(this, 4704, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CallManager.NOW)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(name).setContentText(if (te) "Habit కాల్ జరుగుతోంది · తిరిగి వెళ్ళడానికి నొక్కండి" else "On a Habit call · tap to go back")
            .setOngoing(true).setCategory(Notification.CATEGORY_CALL).setContentIntent(open).setUsesChronometer(true).setWhen(call.connectedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .build()
        val cam = intent?.getBooleanExtra("video", false) == true && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(CallManager.NOW_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (cam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0))
            else startForeground(CallManager.NOW_ID, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
}
