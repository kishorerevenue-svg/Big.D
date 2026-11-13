package com.soulfriend.pet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Habit's games, opened only by the pet ("let's play", "ఆట ఆడదాం"): a quiz, riddles, memory cards,
 * tic-tac-toe and quick maths, in one page that is part of the app (assets/games/index.html). It works without internet
 * and nothing leaves the phone; the page only asks the app to say things aloud and to close.
 */
class GamesActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val store by lazy { Store(this) }

    companion object {
        val NAMES = setOf("quiz", "riddles", "memory", "tictactoe", "maths", "any")
        /** The games page while it is on the screen (the floating pet plays along by voice). */
        @Volatile var shown: GamesActivity? = null
        /** Opens the games ([game]: one of [NAMES], or "" for the list). */
        fun open(ctx: Context, game: String = ""): Boolean = try {
            ctx.startActivity(Intent(ctx, GamesActivity::class.java).putExtra("game", game.takeIf { it in NAMES } ?: "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        } catch (e: Exception) { false }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ChatTheme.apply(this)
        super.onCreate(savedInstanceState)
        AppLook.refresh(this)
        val hero = AppLook.HERO
        ChatTheme.bars(this, hero[0], AppLook.BG)
        tts = TextToSpeech(this, this)
        web = WebView(this).apply {
            setBackgroundColor(AppLook.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true        // the best scores, kept on this phone
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true   // the page never goes anywhere else
                // only the app's own page: nothing is fetched from the internet
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.toString().startsWith("file:///android_asset/games/")) null
                    else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            addJavascriptInterface(Bridge(), "HabitApp")
        }
        setContentView(web)
        val hex = { c: Int -> String.format("%06X", c and 0xFFFFFF) }
        val q = Uri.Builder()
            .appendQueryParameter("lang", if (store.lang == "te") "te" else "en")
            .appendQueryParameter("pet", store.petKind)
            .appendQueryParameter("game", intent.getStringExtra("game") ?: "")
            .appendQueryParameter("dark", if (AppLook.dark) "1" else "0")
            .appendQueryParameter("bg", hex(AppLook.BG)).appendQueryParameter("card", hex(AppLook.CARD))
            .appendQueryParameter("txt", hex(AppLook.TXT)).appendQueryParameter("sub", hex(AppLook.SUB))
            .appendQueryParameter("line", hex(AppLook.LINE)).appendQueryParameter("pri", hex(AppLook.PRIMARY))
            .appendQueryParameter("prit", hex(AppLook.PRIMARY_TXT)).appendQueryParameter("soft", hex(AppLook.SOFT))
            .appendQueryParameter("acc", hex(AppLook.ACCENT)).appendQueryParameter("g1", hex(hero[0])).appendQueryParameter("g2", hex(hero[1]))
            .build().encodedQuery
        web.loadUrl("file:///android_asset/games/index.html?$q")
    }

    override fun onInit(status: Int) { ttsReady = status == TextToSpeech.SUCCESS }

    @Volatile private var state = ""
    /** What is on the game's screen now, for the pet's brain ("Habit's riddles game, riddle 3: … The answer is …"). */
    fun context(): String = state.ifBlank { "Habit's games page is open" }.let { "On Habit's games page now: $it" }

    /** The pet heard [text] while the games are on the screen: the page answers, picks, goes next… [done]: whether it used it. */
    fun voice(text: String, done: (Boolean) -> Unit) {
        try { web.evaluateJavascript("window.voice ? window.voice(${JSONObject.quote(text)}) : false") { r -> done(r == "true") } }
        catch (e: Exception) { done(false) }
    }

    override fun onResume() { super.onResume(); shown = this; petGame(true) }

    /** The floating pet sits small in the corner while the games are on the screen. */
    private fun petGame(on: Boolean) {
        if (PetService.running) try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_GAME).putExtra("on", on)) } catch (e: Exception) { }
    }

    private fun speak(text: String, lang: String) {
        val t = tts ?: return
        if (!ttsReady || !store.voiceOn || text.isBlank()) return
        val te = lang == "te" || text.any { it in 'ఀ'..'౿' }
        val r = t.setLanguage(Locale.forLanguageTag(if (te) "te-IN" else "en-IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return
        val (pitch, rate) = PetVoice.phone(PuppyView.kindOf(store.petKind), PuppyView.Mood.HAPPY, store.cuteVoice)
        t.setPitch(pitch); t.setSpeechRate(rate)
        t.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "game")
    }

    /** What the page may ask: say something aloud (the floating pet says it when it's on the screen), stop talking, close,
     *  and what is on the screen now (for the pet). */
    inner class Bridge {
        @JavascriptInterface fun say(text: String, lang: String) { runOnUiThread {
            if (PetService.running) try {
                startService(Intent(this@GamesActivity, PetService::class.java).setAction(PetService.ACTION_SAY)
                    .putExtra("text", text).putExtra("mood", "happy").putExtra("prefix", "").putExtra("local", true).putExtra("game", true))
                return@runOnUiThread
            } catch (e: Exception) { }
            speak(text, lang)
        } }
        @JavascriptInterface fun hush() { runOnUiThread {
            try { tts?.stop() } catch (e: Exception) { }
            if (PetService.running) try { startService(Intent(this@GamesActivity, PetService::class.java).setAction(PetService.ACTION_HUSH)) } catch (e: Exception) { }
        } }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
        @JavascriptInterface fun state(text: String) { state = text.take(1500) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // inside a game the back button goes to the games list; on the list it closes
        web.evaluateJavascript("window.goBack ? (window.goBack(), 1) : 0") { if (it != "1") runOnUiThread { finish() } }
    }

    override fun onPause() { super.onPause(); if (shown === this) shown = null; petGame(false); try { tts?.stop() } catch (e: Exception) { } }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) { }
        try { web.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }
}
