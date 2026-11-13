package com.soulfriend.pet

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Habit push (Firebase Cloud Messaging): a Habit call rings, and a new chat message shows, while Habit is closed.
 *  - Only when the Habit server has it set up (the owner does it once on the Teach page). The phone then gets the app's
 *    Firebase ids from the server (they aren't secret, but they aren't in the public code either), starts Firebase with
 *    them, and gives the server its push address.
 *  - A push has no words in it (chats and calls stay locked end to end). It only wakes the phone, which then fetches the
 *    locked notes from the Habit server itself: a call rings (CallManager), a message shows its notification (ChatSync).
 */
object Push {
    @Volatile private var started = false
    @Volatile private var busy = false

    /** Starts Firebase with the ids saved last time. At app start too: a push may be what woke the phone. */
    fun init(ctx: Context): Boolean {
        if (started) return true
        val cfg = try { JSONObject(Store(ctx).pushCfg) } catch (e: Exception) { return false }
        return try {
            if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                .setApplicationId(cfg.getString("appId")).setApiKey(cfg.getString("apiKey"))
                .setProjectId(cfg.getString("projectId")).setGcmSenderId(cfg.getString("senderId")).build())
            started = true
            true
        } catch (e: Exception) { false }
    }

    /**
     * Asks the Habit server (at most every few hours) whether push is set up; if so, starts Firebase and gives the server
     * this phone's push address. Needs Habit Chat to be set up on this phone (that's who the calls are for).
     */
    fun setup(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val store = Store(app)
        if (busy || !ChatSync.isSetUp(app)) return
        if (!force && System.currentTimeMillis() - store.pushCheckedAt < 6 * 3600_000L) return
        busy = true
        Thread {
            try {
                store.pushCheckedAt = System.currentTimeMillis()
                val r = ChatSync.api(store, "GET", "/api/push/config?pkg=" + Uri.encode(app.packageName), timeoutMs = 15_000)
                if (r.code == 404) { store.pushCfg = ""; return@Thread }            // not set up on the server (yet)
                if (!r.ok || r.json.optString("appId").isEmpty()) return@Thread
                val cfg = JSONObject().put("appId", r.json.optString("appId")).put("apiKey", r.json.optString("apiKey"))
                    .put("projectId", r.json.optString("projectId")).put("senderId", r.json.optString("senderId")).toString()
                if (store.pushCfg != cfg) { store.pushCfg = cfg; store.pushTok = "" }   // (a changed project starts with the app's next start)
                if (!init(app)) return@Thread
                val tok = Tasks.await(FirebaseMessaging.getInstance().getToken(), 30, TimeUnit.SECONDS)
                if (!tok.isNullOrBlank()) sendToken(app, tok)
            } catch (e: Exception) {
            } finally { busy = false }
        }.start()
    }

    /** Gives the Habit server this phone's push address (when it's new, or once a week). Off the main thread. */
    fun sendToken(ctx: Context, tok: String) {
        val store = Store(ctx)
        if (tok.isBlank() || !ChatSync.isSetUp(ctx)) return
        if (tok == store.pushTok && System.currentTimeMillis() - store.pushSentAt < 7 * 86400_000L) return
        val r = ChatSync.api(store, "POST", "/api/chat/push", JSONObject().put("token", tok).put("pkg", ctx.packageName).toString(), timeoutMs = 15_000)
        if (r.ok) { store.pushTok = tok; store.pushSentAt = System.currentTimeMillis() }
    }

    /** True when this phone can be woken by a push (Firebase started and the server has its address). */
    fun on(ctx: Context): Boolean = started && Store(ctx).pushTok.isNotEmpty()
}

/** Firebase hands Habit a push here (even when Habit was closed): fetch the locked notes and messages from the server. */
class HabitPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.sendToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext
        if (!ChatSync.isSetUp(app)) return
        // the push itself has no words: a call rings (CallManager) and a message shows its notification when they arrive
        val store = Store(app)
        try { if (ChatSync.syncOnce(app, store, false)) ChatSync.flushPending(app, store) } catch (e: Exception) { }
    }
}

/** Habit's app: starts Firebase before anything else when Habit push is set up (a push may be what started it). */
class HabitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Push.init(this) } catch (e: Exception) { }
    }
}
