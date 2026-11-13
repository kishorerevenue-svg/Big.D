package com.soulfriend.pet

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** The Habit AI app: the bottom bar (Home · Chats · the round Talk button · Tasks · Media), and Pet, Files, Vault,
 *  Translate, Family & SOS and Govt services opened from Home. */
class MainActivity : Activity() {

    internal lateinit var store: Store
    internal lateinit var ui: Ui
    private lateinit var vault: VaultStore
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private lateinit var navLeft: LinearLayout
    private lateinit var navRight: LinearLayout
    private lateinit var talkFab: ImageView       // the round Talk button in the middle of the bottom bar
    private lateinit var rootFrame: FrameLayout
    /** The page area (above the bottom bar): the page scrolls in it; a bar can stay fixed at its top ([setTopBar]). */
    private lateinit var bodyFrame: FrameLayout
    private var topBar: View? = null
    @Volatile private var resumedNow = false
    private lateinit var talkLabel: TextView
    private lateinit var fab: TextView
    internal val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
    internal val io = Executors.newSingleThreadExecutor()

    internal var tab = "home"
    internal var tasksTab = "todo"
    private var pickingPhotoFor: String? = null
    internal var leavingForSystemScreen = false
    private var pendingShare: String? = null
    private var pendingLetter: String? = null
    private var pendingPost: Pair<String, List<Uri>>? = null
    private var backCb: Any? = null
    private var renderedTab = ""

    internal val letter by lazy { LetterHelper(this) }
    internal val messages by lazy { MessagesPage(this) }
    internal val chatPage by lazy { ChatPage(this) }
    private var socialPage: SocialPage? = null
    internal val social: SocialPage get() = socialPage ?: SocialPage(this).also { socialPage = it }
    internal val translate by lazy { TranslatePage(this) }
    private var familyPage: FamilyPage? = null
    internal val family: FamilyPage get() = familyPage ?: FamilyPage(this).also { familyPage = it }
    internal val govt by lazy { GovtPage(this) }

    companion object {
        private const val REQ_PHOTO = 4402
        private const val REQ_SOCIAL_PHOTO = 4403
        private const val REQ_SOCIAL_VIDEO = 4404
        const val REQ_TR_PHOTO = 4405
        const val REQ_TR_CAMERA = 4406
        const val REQ_CONTACT = 4407
        const val REQ_LETTER_CAMERA = 4408
        const val REQ_LETTER_PHOTOS = 4409
        const val REQ_LETTER_PDF = 4410
        const val REQ_MSG_CAMERA = 4411
        const val REQ_MSG_PHOTO = 4412
        const val REQ_VAULT_CARDS = 4413
        const val REQ_CAM_INTENT = 24
        const val REQ_CAM_MIRROR = 25
        const val REQ_CARD_PERM = 26
        const val REQ_CHAT_CONTACTS = 27
        const val REQ_LOCATION = 21
        const val REQ_CALL = 22
        const val REQ_NOTIFY = 23
        /** Pages opened from Home (not in the bottom bar). Back and the Home tab return to Home. */
        val TOOL_PAGES = setOf("translate", "family", "govt", "pet")
        /** The bottom bar: three tabs, the round Talk button, three tabs. */
        class NavTab(val key: String, val en: String, val te: String, val icon: Int)
        val TABS = listOf(NavTab("home", "Home", "హోమ్", R.drawable.ic_home), NavTab("messages", "Chats", "చాట్‌లు", R.drawable.ic_chat),
            NavTab("files", "Files", "ఫైల్స్", R.drawable.ic_folder),
            NavTab("tasks", "Tasks", "పనులు", R.drawable.ic_tasks), NavTab("vault", "Vault", "వాల్ట్", R.drawable.ic_lock),
            NavTab("feedback", "Media", "మీడియా", R.drawable.ic_media))
    }

    internal fun dp(v: Int) = ui.dp(v)
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    internal fun add(v: View, top: Int = 14) = content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    internal fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The look this screen was made in (it is made again when the phone's dark mode or the Theme setting changes). */
    private var builtDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // dark or light pages (and pop-ups) in the chosen colour
        AppLook.refresh(this); builtDark = AppLook.dark
        setTheme(if (builtDark) R.style.Habit_AppDark else R.style.Habit_App)
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this); vault = VaultStore(this)
        // like WhatsApp: everyone logs in first (mobile number or email + password), once
        if (!store.loggedIn) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        Reminders.ensureChannel(this)
        if (ChatSync.isSetUp(this)) ChatAlarm.schedule(this)
        ChatTheme.bars(this, Ui.BG, AppLook.CARD)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(28)) }
        scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        // the bottom bar: Home · Chats · (the raised round Talk button) · Tasks · Media
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(8))
            elevation = dp(12).toFloat()
        }
        navLeft = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        talkLabel = TextView(this).apply { textSize = 11.5f; gravity = Gravity.CENTER; typeface = Fonts.heavy(context); includeFontPadding = false }
        val navMid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; contentDescription = "Talk"
            addView(talkLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
            setOnClickListener { talkNow() }
        }
        nav.addView(navLeft, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(navMid, LinearLayout.LayoutParams(dp(66), dp(56)))
        nav.addView(navRight, LinearLayout.LayoutParams(0, dp(56), 1f))
        talkFab = ImageView(this).apply {
            setImageDrawable(getDrawable(R.drawable.ic_mic)!!.mutate()); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16)); contentDescription = "Talk: ask Habit AI by voice"
            elevation = dp(10).toFloat()
            setOnClickListener { talkNow() }
        }
        // the round + button on the Media page (write a post)
        fab = TextView(this).apply {
            text = "+"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
            typeface = Fonts.bold(context); contentDescription = "New post"; visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEAL) }
            elevation = dp(8).toFloat()
            setOnClickListener { social.startCompose() }
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (tab == "feedback") socialPage?.checkSeen() }
        val body = FrameLayout(this).apply {
            bodyFrame = this
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(fab, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(18)) })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
        val root = FrameLayout(this).apply {
            rootFrame = this
            setBackgroundColor(Ui.BG); fitsSystemWindows = true
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(talkFab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        // half of the round button sits above the bar
        nav.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val lp = talkFab.layoutParams as FrameLayout.LayoutParams
            val want = (bottom - top) - dp(26)
            if (lp.bottomMargin != want) { lp.bottomMargin = want; talkFab.layoutParams = lp }
        }
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    /** Opens a page, a letter request from the pet, or something shared to Habit AI from another app. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra("tab")?.let { setTab(it) }
        if (i.getBooleanExtra("mirror", false)) { pendingMirror = true; i.removeExtra("mirror") }
        i.getStringExtra("letter")?.let { tab = "files"; pendingLetter = it; i.removeExtra("letter") }
        val toMedia = i.component?.className?.endsWith(".PostToMedia") == true
        if (toMedia && (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE)) {
            // Share › "Post on Habit Media": text, a link (YouTube, Instagram…) or photos
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val pics = if (i.type?.startsWith("image/") == true || i.type?.startsWith("video/") == true) sharedImages(i) else emptyList()
            if (t.isNotBlank() || pics.isNotEmpty()) { tab = "feedback"; pendingPost = t to pics }
            i.action = Intent.ACTION_MAIN
        } else if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!t.isNullOrBlank()) { setTab("safety"); pendingShare = t }      // Share › Habit AI: check the message
            i.action = Intent.ACTION_MAIN
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedImages(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            if (out.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out.add(it) } }
        } catch (e: Exception) { }
        return out.take(4)
    }

    override fun onResume() {
        super.onResume(); resumedNow = true; leavingForSystemScreen = false
        AppLook.refresh(this); if (AppLook.dark != builtDark) { recreate(); return }
        render()
        ChatSync.listeners.add(navRefresh)      // the number of unread chats on the Chats tab
        ChatSync.hold(this, "main")      // Habit Chat: new messages come at once while the app is open
        if (BuildConfig.AUTO_CARDS && store.autoCards && AutoCards.allowed(this)) AutoCards.schedule(this)
        // a new version out? (Habit AI Lite asks the server at most every 6 hours; the card appears on Home)
        if (BuildConfig.SELF_UPDATE) { val had = Updates.waiting(store)?.code; Updates.check(store) { info -> if (info != null && info.code != had && tab == "home" && !isFinishing) render() } }
        if (store.onboarded) {
            if (pendingMirror) { pendingMirror = false; content.postDelayed({ lookAtMe(false) }, 500) }
            else if (store.mirrorOn && (tab == "home" || tab == "pet") && System.currentTimeMillis() - store.mirrorAt > 3 * 3600_000L)
                content.postDelayed({ if (!isFinishing && hasWindowFocus() && (tab == "home" || tab == "pet")) lookAtMe(true) }, 1500)
            pendingShare?.let { pendingShare = null; messages.checkShared(it) }
            pendingLetter?.let { pendingLetter = null; letter.start(it) }
            pendingPost?.let { pendingPost = null; social.fromShare(it.first, it.second) }
        }
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        ChatSync.listeners.remove(navRefresh)
        ChatSync.release("main")
        mirrorBanner?.let { hideMirror() }
        socialPage?.flushViews()
        familyPage?.onPause()
        letter.stop(); stopListening(); stopTalking()
        if (pickingPhotoFor == null && !leavingForSystemScreen) VaultLock.lock()
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) { }
        try { rec?.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }

    internal fun go(t: String) { setTab(t); render(); scroll.scrollTo(0, 0) }
    /** "safety" is not a tab of its own: it is Tasks › 🛡️ Fake (checking messages, the fake messages list, new scams). */
    private fun setTab(t: String) { if (t == "safety") { tasksTab = "fake"; tab = "tasks" } else tab = t }
    /** True while Tasks › 🛡️ Fake is on screen. */
    internal fun onSafety() = tab == "tasks" && tasksTab == "fake"
    /** Opens the letter helper on the Files page with this request (e.g. "Application for a birth certificate").
     *  [mode] "public" or "office" switches the helper first (e.g. a police complaint from the Messages page is always "public"). */
    internal fun openLetter(req: String, mode: String? = null) { tab = "files"; scroll.scrollTo(0, 0); letter.start(req, mode) }
    /** Opens a website in the phone's browser. */
    internal fun openUrl(url: String) {
        leavingForSystemScreen = true
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { leavingForSystemScreen = false; toast("Couldn't open $url") }
    }
    internal fun scrollTop() { scroll.post { scroll.scrollTo(0, 0) } }
    /** A bar fixed at the top of the page while the page scrolls under it (Habit Chat while chats are picked), or none. */
    internal fun setTopBar(v: View?) {
        topBar?.let { bodyFrame.removeView(it) }
        topBar = v
        if (v != null) bodyFrame.addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }
    /** A page started or stopped needing Back for itself (e.g. picked chats). */
    internal fun backChanged() = updateBack()
    internal val scrollY: Int get() = scroll.scrollY
    internal fun scrollToY(y: Int) { scroll.post { scroll.scrollTo(0, y) } }
    /** Scrolls so that [v] is in view (a little below the top). */
    internal fun scrollToView(v: View) {
        scroll.post {
            val r = Rect(); v.getDrawingRect(r)
            try { scroll.offsetDescendantRectToMyCoords(v, r); scroll.smoothScrollTo(0, maxOf(0, r.top - dp(140))) } catch (e: Exception) { }
        }
    }

    // =================== frame ===================
    internal fun render() {
        val keepY = if (renderedTab == tab) scroll.scrollY else 0
        if (renderedTab == "messages" && tab != "messages") chatPage.leave()
        setTopBar(null)
        content.removeAllViews()
        if (!store.onboarded) { nav.visibility = View.GONE; talkFab.visibility = View.GONE; fab.visibility = View.GONE; secure(false); renderSetup(); renderedTab = ""; updateBack(); return }
        nav.visibility = View.VISIBLE; talkFab.visibility = View.VISIBLE
        buildNav()
        secure(tab == "vault")
        when (tab) {
            "pet" -> petPage()
            "tasks" -> tasksPage()
            "files" -> filesPage()
            "messages" -> messages.build()
            "vault" -> vaultPage()
            "feedback" -> social.build()
            "translate" -> translate.build()
            "family" -> family.build()
            "govt" -> govt.build()
            else -> homePage()
        }
        renderedTab = tab
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
        val chat = tab == "messages"
        if (chat) ChatTheme.refresh(this)
        // Habit Chat's rows reach closer to the screen's edges (it lines up its own top part)
        // (room at the bottom so the end of a page isn't under the round Talk button)
        content.setPadding(if (chat) dp(8) else dp(16), dp(12), if (chat) dp(8) else dp(16), dp(48))
        updateNav()
        scroll.setBackgroundColor(if (chat) ChatTheme.BG else Color.TRANSPARENT)
        rootFrame.setBackgroundColor(if (chat) ChatTheme.BG else Ui.BG)
        ChatTheme.bars(this, if (chat) ChatTheme.BG else Ui.BG, if (chat) ChatTheme.BAR else AppLook.CARD)
        if (chat) {
            // Habit Chat's new-chat button: a rounded square with a pen (gold on navy, navy on white)
            val ld = android.graphics.drawable.LayerDrawable(arrayOf(ChatTheme.round(ChatTheme.PRIMARY, dp(20).toFloat()), Ico.d(this, R.drawable.ic_edit, ChatTheme.ON_PRIMARY_ICON)))
            ld.setLayerGravity(1, Gravity.CENTER); ld.setLayerSize(1, dp(26), dp(26))
            fab.text = ""; fab.background = ld
        } else {
            fab.text = "+"; fab.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEAL) }
            fab.setTextColor(Color.WHITE)
        }
        fab.contentDescription = if (chat) "New chat" else "New post"
        fab.setOnClickListener { if (tab == "messages") chatPage.plusMenu() else social.startCompose() }
        fab.visibility = if ((tab == "feedback" && social.showFab) || (chat && ChatSync.isSetUp(this))) View.VISIBLE else View.GONE
        if (tab == "feedback") scroll.post { socialPage?.checkSeen() }
        updateBack()
    }

    /** Back inside Media (from a profile, a post or the composer) goes to the previous Media screen;
     *  Back on Translate, Family & SOS or Govt services goes to Home. */
    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val sub = socialPage?.screen
        val want = store.onboarded && ((tab == "feedback" && sub != null && sub != "feed") || tab in TOOL_PAGES || (tab == "messages" && chatPage.picking))
        if (want && backCb == null) {
            val cb = android.window.OnBackInvokedCallback { if (!pageBack()) dropBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCb = cb
        } else if (!want) dropBack()
    }

    private fun dropBack() {
        if (Build.VERSION.SDK_INT < 33) return
        (backCb as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCb = null
    }

    /** Handles Back inside a page. Returns false when Back should leave the app as usual. */
    private fun pageBack(): Boolean {
        if (!store.onboarded) return false
        return when (tab) {
            "feedback" -> social.back()
            "messages" -> chatPage.back()
            "family" -> { if (!family.back()) go("home"); true }
            in TOOL_PAGES -> { go("home"); true }
            else -> false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pageBack()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Blocks screenshots and hides the app preview in Recents while the Vault is on screen. */
    private fun secure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---------- the bottom bar: Home · Chats · Talk · Tasks · Media (the chosen one in a soft pill) ----------
    private val navItems = LinkedHashMap<String, LinearLayout>()

    private fun buildNav() {
        if (navItems.isEmpty()) {
            TABS.forEach { navItems[it.key] = navItem(it) }
            navItems.values.forEachIndexed { i, v -> (if (i < 3) navLeft else navRight).addView(v, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(1), 0, dp(1), 0) }) }
        }
        updateNav()
    }

    private fun navItem(t: NavTab): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = t.key
        val box = FrameLayout(this@MainActivity)
        box.addView(ImageView(this@MainActivity).apply { setImageDrawable(getDrawable(t.icon)!!.mutate()) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        // the number of unread chats (Chats only)
        box.addView(TextView(this@MainActivity).apply {
            textSize = 10f; typeface = Fonts.heavy(context); gravity = Gravity.CENTER; includeFontPadding = false; setPadding(dp(4), 0, dp(4), 0); minWidth = dp(16); visibility = View.GONE
        }, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END))
        addView(box, LinearLayout.LayoutParams(dp(40), dp(26)))
        // the name shrinks a little on narrow phones (six tabs and Talk share the bar)
        addView(TextView(this@MainActivity).apply {
            textSize = 11f; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(2); marginStart = dp(2); marginEnd = dp(2) })
        setOnClickListener { if (tab != t.key) go(t.key) else if (t.key == "feedback") social.home() else scrollTop() }
    }

    private val navRefresh: () -> Unit = { if (store.onboarded && navItems.isNotEmpty()) updateNav() }

    private fun updateNav() {
        // on Habit Chat the bar takes the chat's look (navy and gold, or clean light); elsewhere the chosen colour
        val chat = tab == "messages"
        val te = store.lang == "te"
        val bar = if (chat) ChatTheme.BAR else AppLook.CARD
        nav.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(if (chat) ChatTheme.DIVIDER else AppLook.LINE),
            android.graphics.drawable.ColorDrawable(bar))).apply { setLayerInset(1, 0, 1, 0, 0) }
        val pill = if (chat) ChatTheme.PILL else AppLook.PILL
        val onInk = if (chat) ChatTheme.PILL_TXT else AppLook.PILL_TXT
        val offInk = if (chat) ChatTheme.SUB else AppLook.SUB
        val unread = try { if (ChatSync.isSetUp(this)) ChatDb.get(this).convs().count { it.unread > 0 && !it.muted } else 0 } catch (e: Exception) { 0 }
        TABS.forEach { t ->
            val item = navItems[t.key] ?: return@forEach
            val on = t.key == tab || (t.key == "home" && tab in TOOL_PAGES)
            item.background = if (on) ui.round(pill, 18) else null
            val box = item.getChildAt(0) as FrameLayout
            (box.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(if (on) onInk else offInk)
            (box.getChildAt(1) as TextView).apply {
                val n = if (t.key == "messages") unread else 0
                visibility = if (n > 0) View.VISIBLE else View.GONE
                text = if (n > 99) "99+" else n.toString()
                setTextColor(if (chat) ChatTheme.ON_GOLD else Color.WHITE)
                background = ui.round(if (chat) ChatTheme.GOLD else AppLook.ACCENT, 8, bar)
            }
            (item.getChildAt(1) as TextView).apply {
                text = if (te) t.te else t.en
                setTextColor(if (on) onInk else offInk); typeface = if (on) Fonts.heavy(context) else Fonts.semi(context)
            }
            item.contentDescription = t.en + if (t.key == "messages" && unread > 0) ", $unread unread" else ""
        }
        // the round Talk button: the main colour, with a ring of the bar's colour
        val prim = if (chat) ChatTheme.PRIMARY else AppLook.PRIMARY
        talkFab.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(prim); setStroke(dp(4), bar) }
        talkFab.imageTintList = android.content.res.ColorStateList.valueOf(if (chat) ChatTheme.ON_PRIMARY_ICON else AppLook.ON_PRIMARY)
        talkLabel.text = if (te) "మాట్లాడు" else "Talk"
        talkLabel.setTextColor(if (chat) ChatTheme.TXT else AppLook.TXT)
    }

    /** A page's title and what it is for; pages opened from Home get a ← back to Home. */
    internal fun pageHeader(title: String, sub: String) {
        if (tab in TOOL_PAGES) add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ico.view(this@MainActivity, R.drawable.ic_back, Ui.INK, 22, 42, if (store.lang == "te") "హోమ్‌కి" else "Back to Home", ui.round(Ui.SURFACE, 21, Ui.LINE)) { go("home") },
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(ui.title(title).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        }, 4)
        else add(ui.title(title), 4)
        add(ui.text(sub, 14f, Ui.MUTED), 4)
    }

    // =================== 1. HOME ===================
    /** "A new version is ready" on Home (Habit AI Lite only), unless they said "Later" in the last 2 days. */
    private fun updateCard(): View? {
        val info = Updates.waiting(store) ?: return null
        if (System.currentTimeMillis() < store.updateLaterUntil) return null
        return ui.card().apply {
            put(ui.text("🎁 New version ${info.name} is ready · కొత్త వెర్షన్ వచ్చింది", 16f, Ui.INK, bold = true))
            if (info.notes.isNotBlank()) put(ui.text("What's new: " + info.notes, 13.5f, Ui.MUTED), 6)
            put(ui.text("About ${info.sizeMb} MB. Your browser downloads it, then tap Open › Update. Everything on your phone stays.", 12.5f, Ui.MUTED), 6)
            put(ui.row(ui.button("⬇ Update now", "primary") { leavingForSystemScreen = true; Updates.open(this@MainActivity, store) },
                ui.button("Later", "ghost") { store.updateLaterUntil = System.currentTimeMillis() + 2 * 86400_000L; go("home") }), 10)
        }
    }

    private fun t(en: String, te: String) = if (store.lang == "te") te else en
    /** A heading between parts of a page. */
    private fun heading(s: String) = ui.text(s, 17f, Ui.INK).apply { typeface = Fonts.heavy(context); letterSpacing = -0.01f }

    private fun homePage() {
        val now = ZonedDateTime.now()
        val (greetTe, greetEn) = when (now.hour) {
            in 4..11 -> "శుభోదయం" to "Good morning"
            in 12..15 -> "శుభ మధ్యాహ్నం" to "Good afternoon"
            in 16..20 -> "శుభ సాయంత్రం" to "Good evening"
            else -> "శుభ రాత్రి" to "Good night"
        }
        val me = try { if (ChatSync.isSetUp(this)) ChatSync.me(this) else JSONObject() } catch (e: Exception) { JSONObject() }
        val first = me.optString("name").trim().substringBefore(' ')
        // the top: the logo, "Habit AI", ✨ ask Habit AI, and my picture (the app's colour and language)
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ico.view(this@MainActivity, R.drawable.ic_pawprint, Ui.ON_TEAL, 22, 40, "", ui.round(Ui.TEAL, 12)), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(ui.text("Habit AI", 22f, Ui.INK).apply { typeface = Fonts.heavy(context); letterSpacing = -0.015f; setPadding(dp(11), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ico.view(this@MainActivity, R.drawable.ic_sparkle, Ui.INK, 20, 42, t("Ask Habit AI", "Habit AI ని అడగండి"), ui.round(Ui.SURFACE, 21, Ui.LINE)) { askAi() },
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(myPicture(me, dp(42)).apply { setOnClickListener { lookSheet(me) }; contentDescription = t("You: the app's colour and language", "మీరు: యాప్ రంగు, భాష") },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        }, 0)

        // the big card: the date, hello in Telugu and English, and the pet
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(8), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, AppLook.HERO).apply { cornerRadius = ui.dpf(26) }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(ui.text(DateTimeFormatter.ofPattern("EEEE · d MMMM", Locale.ENGLISH).format(now).uppercase(Locale.ENGLISH), 11f, Color.parseColor("#D9FFFFFF"), bold = true).apply { letterSpacing = 0.09f })
        words.addView(ui.text(greetTe, 17f, Color.WHITE).apply { typeface = Fonts.semi(context); setPadding(0, dp(6), 0, 0) })
        words.addView(ui.text(greetEn + if (first.isNotEmpty()) ", $first" else "", 20f, Color.WHITE).apply { typeface = Fonts.heavy(context); setLineSpacing(0f, 1.05f) })
        words.addView(TextView(this).apply {
            val label = if (PetService.running) t("Close my pet", "పెట్‌ని మూసేయి") else t("Show my pet", "నా పెట్‌ని చూపించు")
            // (on white in both looks: the deep main colour)
            text = Ico.withIcon(context, R.drawable.ic_pawprint, AppLook.p.primary, dp(16), label)
            textSize = 13.5f; setTextColor(AppLook.p.primaryTxt); typeface = Fonts.bold(context); includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(16), dp(9)); background = ui.round(Color.WHITE, 20)
            setOnClickListener { togglePet() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        hero.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(PuppyView(this).apply { kind = PuppyView.kindOf(store.petKind); mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY },
            LinearLayout.LayoutParams(dp(112), dp(112)))
        add(hero, 14)
        updateCard()?.let { add(it, 12) }

        // today: to-dos, reminders, unread chats, and the next reminder
        add(heading(t("Today", "ఈరోజు")), 22)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val rems = store.reminders().sortedBy { it.at }
        val todayCount = rems.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
        val pending = store.todos().count { !it.done }
        val unread = try { if (ChatSync.isSetUp(this)) ChatDb.get(this).convs().count { it.unread > 0 } else 0 } catch (e: Exception) { 0 }
        fun stat(icon: Int, tint: Pair<Int, Int>, n: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(10), dp(11)); background = ui.round(Ui.SURFACE, 18, Ui.LINE)
            addView(Ico.view(this@MainActivity, icon, tint.second, 18, 34, "", ui.round(tint.first, 11)), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(ui.text(n.toString(), 22f, Ui.INK).apply { typeface = Fonts.heavy(context); includeFontPadding = false; setPadding(0, dp(9), 0, 0) })
            addView(ui.text(label, 12f, Ui.MUTED).apply { typeface = Fonts.semi(context); maxLines = 2; setLineSpacing(0f, 1.05f); setPadding(0, dp(3), 0, 0) })
            contentDescription = "$n $label"
            setOnClickListener { onClick() }
        }
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(stat(R.drawable.ic_tasks, Ui.TEAL_SOFT to Ui.TEAL_DARK, pending, t("To-dos open", "చేయాల్సినవి")) { tasksTab = "todo"; go("tasks") },
                stat(R.drawable.ic_bell, AppLook.TINT2, todayCount, t("Reminders today", "ఈరోజు రిమైండర్లు")) { tasksTab = "rem"; go("tasks") },
                stat(R.drawable.ic_chat, AppLook.TINT3, unread, t("Unread chats", "చదవని చాట్‌లు")) { go("messages") })
                .forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(9) }) }
        }, 10)
        rems.firstOrNull { it.at > System.currentTimeMillis() }?.let { r ->
            val z = Instant.ofEpochMilli(r.at).atZone(zone)
            val whenTxt = (if (z.toLocalDate() == today) t("Today", "ఈరోజు") + ", " + DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(z) else fmt.format(z)) +
                Reminders.repeatLabel(r.repeat).let { if (it.isEmpty()) "" else " · $it" }
            add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(11), dp(8), dp(11)); background = ui.round(Ui.SURFACE, 18, Ui.LINE)
                addView(Ico.view(this@MainActivity, R.drawable.ic_clock, AppLook.TINT2.second, 20, 38, "", ui.round(AppLook.TINT2.first, 12)), LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.text(t("NEXT REMINDER", "తదుపరి రిమైండర్") + " · " + whenTxt, 10.5f, Ui.MUTED, bold = true).apply { letterSpacing = 0.05f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(ui.text(r.text, 14.5f, Ui.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                addView(Ico.view(this@MainActivity, R.drawable.ic_chevron_right, Ui.MUTED, 18, 28, ""), LinearLayout.LayoutParams(dp(28), dp(28)))
                setOnClickListener { tasksTab = "rem"; go("tasks") }
            }, 10)
        }

        // the Office box: letters, reports, memos and orders in government office format (Files › Office)
        add(officeBox(), 14)

        // quick actions, and more for you: 3 tiles in a row
        fun action(icon: Int, tint: Pair<Int, Int>, label: String, hot: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), dp(13), dp(6), dp(12))
            background = if (hot) ui.round(Ui.TEAL, 18) else ui.round(Ui.SURFACE, 18, Ui.LINE)
            addView(Ico.view(this@MainActivity, icon, if (hot) Ui.ON_TEAL else tint.second, 21, 42, "", ui.round(if (hot) Color.parseColor("#33FFFFFF") else tint.first, 14)),
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(ui.text(label, 12.5f, if (hot) Ui.ON_TEAL else Ui.INK).apply { typeface = Fonts.bold(context); gravity = Gravity.CENTER; maxLines = 2; setLineSpacing(0f, 1.02f); setPadding(0, dp(8), 0, 0) })
            contentDescription = label
            setOnClickListener { onClick() }
        }
        fun grid(vararg tiles: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tiles.toList().chunked(3).forEachIndexed { r, row ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(9) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(9) })
            }
        }
        val soft = Ui.TEAL_SOFT to Ui.TEAL_DARK
        add(heading(t("Quick actions", "త్వరగా చేయండి")), 22)
        add(grid(
            action(R.drawable.ic_mic, soft, t("Talk now", "మాట్లాడండి"), hot = true) { talkNow() },
            action(R.drawable.ic_doc, soft, t("Make a file", "ఫైల్ తయారు")) { go("files") },
            action(R.drawable.ic_bell, AppLook.TINT2, t("Reminder", "రిమైండర్")) { tasksTab = "rem"; go("tasks") },
            action(R.drawable.ic_shield, AppLook.TINT3, t("Safety check", "మోసం చెక్")) { go("safety") },
            action(R.drawable.ic_lock, soft, t("My Vault", "నా వాల్ట్")) { go("vault") },
            action(R.drawable.ic_globe, AppLook.TINT2, t("Translate", "అనువాదం")) { go("translate") }), 10)
        add(heading(t("More for you", "ఇంకా మీ కోసం")), 22)
        add(grid(
            action(R.drawable.ic_pawprint, soft, t("Your pet", "మీ పెట్")) { go("pet") },
            action(R.drawable.ic_users, AppLook.TINT3, t("Family & SOS", "కుటుంబం & SOS")) { go("family") },
            action(R.drawable.ic_bank, AppLook.TINT2, t("Govt services", "ప్రభుత్వ సేవలు")) { go("govt") },
            action(R.drawable.ic_pill, AppLook.TINT3, t("Medicine", "మందులు")) { family.focus = "medicine"; go("family") },
            action(R.drawable.ic_media, soft, t("News & Media", "వార్తలు & మీడియా")) { go("feedback") },
            action(R.drawable.ic_chat, AppLook.TINT2, t("Chats", "చాట్‌లు")) { go("messages") }), 10)

        val tips = listOfNotNull(
            "Say \"remind me to take medicine every day at 8 am\"",
            "Say \"make a leave letter for 2 days as a Word file\"",
            "Hold the pet for the menu: type, photo, lists",
            "Tap the round Talk button at the bottom to ask anything by voice",
            "Got a strange message? Copy it and tap Safety check",
            if (BuildConfig.MESSAGE_REPLIES) "Turn on WhatsApp replies in the Pet page" else null,
            "Drag the pet down to ✕ to close it",
            "Can't read an English letter or board? Take a photo in Translate",
            "Save your family's numbers in Family & SOS: hold SOS for 3 seconds to call them",
            "Need a certificate? Govt services shows the official website and writes the application",
            "Tap your picture at the top to change the app's colour"
        )
        add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(15)); background = ui.round(Ui.TEAL_SOFT, 20)
            addView(ui.text("", 13f, Ui.TEAL_DARK, bold = true).apply { text = Ico.withIcon(context, R.drawable.ic_sparkle, Ui.TEAL_DARK, dp(16), t("Tip", "చిట్కా")) })
            addView(ui.text(tips[(now.dayOfYear + now.hour) % tips.size], 14.5f, Ui.INK).apply { setPadding(0, dp(4), 0, 0) })
        }, 22)

        // last on the page: this version, and a check for a newer one (Habit AI Lite; Play Store editions update by themselves)
        add(versionCard(), 14)
    }

    /** Office · కార్యాలయం on Home: opens the Office side of the letter helper; each chip starts that kind of paper. */
    private fun officeBox(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(13)); background = ui.round(Ui.SURFACE, 20, Ui.LINE)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ico.view(this@MainActivity, R.drawable.ic_briefcase, Ui.ON_TEAL, 22, 44, "", ui.round(Ui.TEAL, 14)), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text("Office · కార్యాలయం", 16f, Ui.INK).apply { typeface = Fonts.heavy(context) })
                addView(ui.text(t("Letters, reports, memos and orders in office format", "కార్యాలయ ఫార్మాట్‌లో లేఖలు, నివేదికలు, మెమోలు, ఉత్తర్వులు"), 12.5f, Ui.MUTED).apply { setPadding(0, dp(1), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(Ico.view(this@MainActivity, R.drawable.ic_chevron_right, Ui.MUTED, 18, 28, ""), LinearLayout.LayoutParams(dp(28), dp(28)))
            contentDescription = t("Office letters and reports", "కార్యాలయ లేఖలు, నివేదికలు")
            setOnClickListener { letter.openOffice(""); go("files") }
        })
        // the kinds used most, one tap each (the rest are on the Office page)
        val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        listOf("📨 Letter to higher office" to "Letter to higher office", "📋 Report" to "Report", "📝 Memo" to "Memo", "↩️ Endorsement" to "Endorsement",
            "⚖️ Proceedings" to "Proceedings / Order", "📢 Notice" to "Notice", t("More…", "ఇంకా…") to "").forEach { (label, type) ->
            chips.addView(ui.chip(label, false) { letter.openOffice(type); go("files") }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
    }

    /** My picture (profile photo, or my initials) with a ring of the second colour. */
    private fun myPicture(me: JSONObject, size: Int): View = FrameLayout(this).apply {
        val ini = ChatDraw.initials(me.optString("name"))
        if (ini.isNotEmpty()) addView(TextView(this@MainActivity).apply {
            text = ini; textSize = size / resources.displayMetrics.density * 0.34f; gravity = Gravity.CENTER; setTextColor(Ui.TEAL_DARK)
            typeface = Fonts.heavy(context); includeFontPadding = false; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEAL_SOFT) }
        }, FrameLayout.LayoutParams(-1, -1))
        else addView(Ico.view(this@MainActivity, R.drawable.ic_person, Ui.TEAL_DARK, 22, 42, "", GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEAL_SOFT) }), FrameLayout.LayoutParams(-1, -1))
        val photo = me.optString("avatar")
        if (photo.isNotBlank()) {
            val iv = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            Images.load(if (photo.startsWith("http")) photo else store.server.trimEnd('/') + photo, iv, size)
        }
        foreground = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), AppLook.ACCENT) }
    }

    /** ✨ on Home: ask Habit AI (its chat), or start Habit Chat first. */
    private fun askAi() {
        if (ChatSync.isSetUp(this)) { ChatDb.get(this).ensureAi(); ChatActivity.open(this, ChatDb.AI) } else go("messages")
    }

    /** My picture on Home: the app's colour (six choices) and the language. */
    private fun lookSheet(me: JSONObject) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)) }
        var dlg: android.app.Dialog? = null
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(myPicture(me, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(ui.text(me.optString("name").ifBlank { "Habit AI" }, 18f, Ui.INK).apply { typeface = Fonts.heavy(context) })
                me.optString("handle").takeIf { it.isNotBlank() }?.let { addView(ui.text("@$it", 13.5f, Ui.MUTED)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        box.addView(ui.text("", 15f, Ui.INK).apply { text = Ico.withIcon(context, R.drawable.ic_palette, Ui.INK, dp(18), t("App colour", "యాప్ రంగు")); typeface = Fonts.heavy(context); setPadding(0, dp(20), 0, 0) })
        box.addView(ui.text(t("Home, Tasks, Files, Vault and the other pages. Chats keep their navy and gold look.", "హోమ్, పనులు, ఫైల్స్, వాల్ట్, మిగతా పేజీలు. చాట్‌లు నేవీ & గోల్డ్‌లోనే ఉంటాయి."), 12.5f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        AppLook.PALETTES.chunked(3).forEach { row ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { pal ->
                    val on = pal.id == AppLook.p.id
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(10), dp(4), dp(10))
                        background = if (on) ui.round(Ui.TEAL_SOFT, 16, Ui.TEAL) else ui.round(Ui.SURFACE, 16, Ui.LINE)
                        addView(FrameLayout(this@MainActivity).apply {
                            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pal.g1, pal.g2)).apply { shape = GradientDrawable.OVAL }
                            if (on) addView(Ico.view(this@MainActivity, R.drawable.ic_tick, Color.WHITE, 20, 40, ""), FrameLayout.LayoutParams(-1, -1))
                            // a dot of the second colour
                            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent); setStroke(dp(2), Color.WHITE) } },
                                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
                        }, LinearLayout.LayoutParams(dp(40), dp(40)))
                        addView(ui.text(if (store.lang == "te") pal.te else pal.en, 12f, Ui.INK, bold = on).apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1f) })
                        contentDescription = pal.en + if (on) ", chosen" else ""
                        setOnClickListener { AppLook.choose(this@MainActivity, pal.id); dlg?.dismiss(); render() }
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
            })
        }
        box.addView(grid)
        box.addView(ui.text(t("Language", "భాష"), 15f, Ui.INK).apply { typeface = Fonts.heavy(context); setPadding(0, dp(18), 0, dp(8)) })
        box.addView(ui.row(ui.chip("English", store.lang != "te") { store.lang = "en"; dlg?.dismiss(); render() },
            ui.chip("తెలుగు", store.lang == "te") { store.lang = "te"; dlg?.dismiss(); render() }))
        box.addView(ui.button(t("Your pet and settings", "మీ పెట్, సెట్టింగ్స్"), "ghost") { dlg?.dismiss(); go("pet") }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        dlg = ChatTheme.sheet(this, ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false }, AppLook.CARD, AppLook.LINE)
    }

    private fun versionCard(): View = ui.card().apply {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        addView(ui.text("ℹ️ " + getString(R.string.app_name) + " · " + (if (store.lang == "te") "వెర్షన్" else "Version") + " $v", 14.5f, Ui.INK, bold = true))
        if (BuildConfig.SELF_UPDATE) {
            val waiting = Updates.waiting(store)
            if (waiting != null) put(ui.text("🎁 " + (if (store.lang == "te") "కొత్త వెర్షన్ ${waiting.name} సిద్ధంగా ఉంది" else "New version ${waiting.name} is ready"), 13.5f, Ui.GOOD, bold = true), 4)
            put(ui.row(
                if (waiting != null) ui.button("⬇ " + (if (store.lang == "te") "అప్‌డేట్ చేయండి" else "Update now"), "primary") { leavingForSystemScreen = true; Updates.open(this@MainActivity, store) }
                else ui.button("🔄 " + (if (store.lang == "te") "కొత్త వెర్షన్ ఉందేమో చూడండి" else "Check for a new version"), "ghost") {
                    toast(if (store.lang == "te") "చూస్తున్నాం…" else "Checking…")
                    Updates.check(store, force = true) { info ->
                        if (info == null) toast(if (store.lang == "te") "మీ దగ్గర కొత్త వెర్షనే ఉంది ✓" else "You have the newest version ✓")
                        else { store.updateLaterUntil = 0L; render() }
                    }
                }), 10)
        }
    }

    private fun togglePet() {
        if (PetService.running) stopService(Intent(this, PetService::class.java))
        else if (!Settings.canDrawOverlays(this)) { toast("First allow \"Show over other apps\" on the Pet page."); go("pet"); return }
        else { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
        content.postDelayed({ render() }, 400)
    }

    private fun talkNow() {
        if (!Settings.canDrawOverlays(this)) { toast("First allow \"Show over other apps\" on the Pet page."); go("pet"); return }
        if (!PetService.running) startForegroundService(Intent(this, PetService::class.java))
        content.postDelayed({ try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_LISTEN)) } catch (e: Exception) { } }, 900)
        moveTaskToBack(true)
    }

    // =================== 2. PET ===================
    private fun petPage() {
        pageHeader("Your pet", "Choose, set up and teach your pet")
        add(ui.card().apply {
            val pet = PuppyView(this@MainActivity).apply { kind = PuppyView.kindOf(store.petKind); mood = if (PetService.running) PuppyView.Mood.EXCITED else PuppyView.Mood.SLEEPY }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ui.stack(
                    ui.text(PuppyView.kindOf(store.petKind).label + " · " + PuppyView.kindOf(store.petKind).telugu, 18f, Ui.INK, bold = true),
                    ui.text(if (PetService.running) "● On your screen" else "○ Resting", 13.5f, if (PetService.running) Ui.GOOD else Ui.MUTED)
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(pet, LinearLayout.LayoutParams(dp(84), dp(84)))
            })
            put(ui.button(if (PetService.running) "Close my pet" else "🐾  Show my pet on screen", if (PetService.running) "ghost" else "primary") { togglePet() }, 12)
            put(ui.text("Tap it to talk · hold it for the menu · drag it down to ✕ to close", 12.5f, Ui.MUTED), 8)
        }, 14)

        add(ui.section(t("🎮 Play with your pet", "🎮 మీ పెట్‌తో ఆడుకోండి"),
            t("Quiz, riddles, memory cards, tic-tac-toe and quick maths. Or just tell your pet \"let's play\". No internet needed.",
              "క్విజ్, పొడుపు కథలు, జ్ఞాపకశక్తి ఆట, X - O ఆట, లెక్కల ఆట. లేదా మీ పెట్‌తో \"ఆట ఆడదాం\" అనండి. ఇంటర్నెట్ అవసరం లేదు.")).apply {
            put(ui.button(t("🎮  Play games", "🎮  ఆటలు ఆడండి"), "primary") { GamesActivity.open(this@MainActivity) }, 12)
        }, 12)
        add(brainCard(), 12)
        add(ui.section("Choose your pet").apply { petPicker(this) }, 12)

        add(ui.section("Language · భాష", "Auto: listens in Telugu and replies in the language you use.").apply {
            put(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("auto" to "Auto", "te" to "తెలుగు", "en" to "English").forEachIndexed { i, (code, label) ->
                    addView(ui.chip(label, store.lang == code) { store.lang = code; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
                }
            }, 12)
        }, 12)
        add(voiceCard(), 12)
        add(ui.section("👀 Pet's eyes · కళ్ళు", "Just ask your pet to look, and it looks through the camera by itself (no camera window, no photo to send):\n" +
            "• \"Look at this shirt, is it available online?\" · \"ఈ షర్ట్ ఆన్‌లైన్‌లో దొరుకుతుందా?\"\n" +
            "• \"What's written on this board?\" · \"ఈ బోర్డు మీద ఏం రాసి ఉంది?\"\n" +
            "• \"Translate this\" · \"ఇది ఏంటి?\" · \"Look again\"\n" +
            "• \"What colour is my shirt?\" · \"నేను ఎలా ఉన్నాను?\" (the front camera)\n" +
            "While it looks, it talks like a friend: \"hold still, don't shake\", \"too close\", it turns on the light when it's dark, " +
            "and if it can't see well it tells you how to hold the phone and looks again.\n" +
            "One photo, sent to Claude AI only to answer you, not saved. For your phone's screen, say \"my screen\".").apply {
            put(ui.button("😊 Look at me now", "primary") { lookAtMe(false) }, 10)
            put(ui.check("👀 Look at me when I open the app (at most every 3 hours)", store.mirrorOn && store.mirrorAsked) { on -> store.mirrorOn = on; store.mirrorAsked = true; if (on && !granted(Manifest.permission.CAMERA)) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_MIRROR) }, 8)
        }, 12)

        add(ui.section("Permissions · అనుమతులు").apply { permissionRows(this) }, 12)
        // Message checks, replies and danger alerts live in Tasks › 🛡️ Fake.
        add(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.line(ui.stack(ui.text("🛡️ Fake messages and danger alerts", 15f, Ui.TEAL_DARK, bold = true),
                ui.text("Tasks › 🛡️ Fake: check messages for fraud" + if (BuildConfig.MESSAGE_REPLIES) ", reply settings and auto-reply" else "", 12.5f, Ui.MUTED)),
                ui.button("Open", "primary") { go("safety") }))
        }, 12)
        if (BuildConfig.HANDS_FREE) add(handsFreeCard(), 12)
        add(briefingCard(), 12)

        // the tiny brain for no internet (Habit AI Lite: a separate, optional app)
        if (BuildConfig.SELF_UPDATE) add(ui.section("📴 Tiny brain for no internet", "An optional small AI that runs on this phone, so your pet can still talk when there is no internet at all. " +
            "It is a separate app, Habit Brain. It needs a 64-bit phone with about 6 GB memory, and the brain is about 2.6 GB (downloaded once, over Wi-Fi). Nothing you ask it leaves the phone.").apply {
            if (TinyBrain.installed(this@MainActivity)) {
                put(ui.text("✓ Habit Brain is installed. Open it to download the brain or try it.", 13.5f, Ui.TEAL_DARK), 6)
                put(ui.row(ui.button("🧠 Open Habit Brain", "primary") { leavingForSystemScreen = true; TinyBrain.open(this@MainActivity, store) }), 10)
            } else put(ui.row(ui.button("⬇ Get Habit Brain", "primary") {
                leavingForSystemScreen = true
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/download/brain"))) } catch (e: Exception) { }
            }), 10)
        }, 12)

        add(ui.section("Privacy", "Useful for office files. Photos are sent as they are.").apply {
            put(ui.check("Hide personal details before sending (Aadhaar, phone and PAN numbers become XXXX…1234)", store.hidePersonal) { store.hidePersonal = it }, 8)
            put(ui.check("Help Habit learn: general questions like \"What is PM Kisan?\" are counted (never who asked, never questions about you), and you can rate answers from Habit's own brain with 👍 / 👎", store.learnShare) { store.learnShare = it }, 8)
        }, 12)

        add(ui.section("Your Media profile", "Your public profile, posts, photos and comments in the Media tab.").apply {
            put(ui.row(ui.button("🗑 Delete my Media profile", "danger") { social.deleteProfile() }), 10)
            put(ui.text("Deletes your profile and everything in it, for ever. Also possible on the web: ${store.server}/delete-account", 12f, Ui.MUTED), 8)
        }, 12)

        add(ui.section("What your pet remembers", "Saved only on this phone. Say \"forget that\" or tap ✕.").apply {
            val mems = store.memories()
            if (mems.isEmpty()) put(ui.text("Nothing yet. Tell it about yourself, for example \"My daughter's name is Sita\".", 13.5f, Ui.MUTED))
            mems.forEachIndexed { i, m -> put(ui.line(ui.text(m, 15f), ui.button("✕", "ghost") { store.removeMemory(i); render() }), 8) }
        }, 12)

        add(ui.section("Advanced").apply {
            put(ui.text("Server address", 14f, Ui.INK, bold = true), 10)
            val box = ui.input("https://…", store.server).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            put(box, 6)
            put(ui.row(ui.button("Save address") {
                val v = box.text.toString().trim()
                if (!v.startsWith("https://")) toast("The address must start with https://") else { store.server = v; toast("Saved") }
            }), 8)
            put(ui.button("Show the setup guide again", "ghost") { store.onboarded = false; setupStep = 0; render() }, 12)
            put(ui.text("Habit team password (only for the app's team: post as the official accounts and remove posts)", 13f, Ui.MUTED), 14)
            val team = ui.input("Team password", store.teamKey).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            put(team, 6)
            put(ui.row(ui.button("Save password") { store.teamKey = team.text.toString(); socialPage?.refresh(); toast(if (store.teamKey.isBlank()) "Removed" else "Saved") }), 8)
        }, 12)
    }

    /** Natural voice on/off, its status on the server, and a test button. */
    // ---------- Habit's own brain (on our server): what it has learned, and whether our own AI model runs ----------
    private var brainStatus: JSONObject? = null
    private var brainAt = 0L
    private fun brainCard(): View = ui.section(t("🧠 Habit's own brain", "🧠 Habit సొంత మెదడు"),
        t("It learns from Claude every day, and keeps making files and answering when Claude can't.",
          "ఇది ప్రతిరోజూ Claude నుంచి నేర్చుకుంటుంది. Claude అందుబాటులో లేనప్పుడు సొంతంగా ఫైళ్లు చేస్తుంది, జవాబులు ఇస్తుంది.")).apply {
        if (System.currentTimeMillis() - brainAt > 60_000) {
            brainAt = System.currentTimeMillis()
            io.execute {
                val r = Net.get(store, "/api/brain/status")
                runOnUiThread { brainStatus = if (r.ok) r.json else JSONObject().put("offline", true); if (tab == "pet") render() }
            }
        }
        val s = brainStatus
        if (s == null) { put(ui.text(t("Checking…", "చూస్తోంది…"), 13.5f, Ui.MUTED), 8); return@apply }
        if (s.optBoolean("offline")) { put(ui.text(t("Can't reach Habit's server right now. Check the internet.", "ఇప్పుడు Habit సర్వర్ అందడం లేదు. ఇంటర్నెట్ చూడండి."), 13.5f, Ui.MUTED), 8); return@apply }
        fun row(icon: String, label: String, value: String) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.text("$icon  $label", 14f, Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text(value, 14f, Ui.TEAL_DARK, bold = true).apply { gravity = Gravity.END; maxWidth = dp(190) })
        }
        put(ui.text("● " + t("Active · learning", "యాక్టివ్ · నేర్చుకుంటోంది"), 14f, Ui.GOOD, bold = true), 8)
        put(row("📄", t("File skills", "ఫైల్ నైపుణ్యాలు"), t("${s.optInt("skills")} kinds", "${s.optInt("skills")} రకాలు")), 10)
        put(row("🎓", t("Learned from Claude", "Claude నుంచి నేర్చుకున్నవి"), s.optInt("learnedSkills").toString()), 6)
        put(row("📝", t("Files it made by itself", "సొంతంగా చేసిన ఫైళ్లు"), s.optInt("filesMade").toString()), 6)
        put(row("💬", t("Answers learned", "నేర్చుకున్న జవాబులు"), s.optInt("answers").toString()), 6)
        put(row("📚", t("Things you taught", "మీరు నేర్పినవి"), s.optInt("knowledge").toString()), 6)
        val md = s.optJSONObject("model")
        put(row("🤖", t("Own AI model", "సొంత AI మోడల్"), when {
            md?.optBoolean("running") == true -> t("running", "పని చేస్తోంది")
            md?.optBoolean("installed") == true -> t("installed, starting", "ఇన్‌స్టాల్ అయింది")
            else -> t("waiting for a bigger server", "పెద్ద సర్వర్ కోసం వేచి ఉంది")
        }), 6)
        put(row("✨", "Claude", when (s.optString("claude")) {
            "on" -> t("helping", "సహాయం చేస్తోంది")
            "resting" -> t("resting: Habit works by itself", "విశ్రాంతి: Habit సొంతంగా చేస్తోంది")
            else -> t("not set up", "సెట్ చేయలేదు")
        }), 6)
    }

    private fun voiceCard() = ui.section("Voice 🔊", "A clear, grown-up voice that speeds up a little when your pet is excited and slows down when it's sad.").apply {
        val status = ui.text("Checking the natural voice…", 12.5f, Ui.MUTED)
        put(ui.check("Natural voice (sounds like a real person, Telugu and English)", store.naturalVoice) { store.naturalVoice = it }, 10)
        put(status, 4)
        put(ui.check("🧸 Cute cartoon voice (higher, like a cartoon character)", store.cuteVoice) { store.cuteVoice = it }, 8)
        put(ui.check("🎉 Fun sounds when they fit (tada, laugh, wow, applause…)", store.funSounds) { store.funSounds = it; if (it) SoundFx.play(this@MainActivity, "tada") }, 8)
        put(ui.check(t("💬 Keep listening after answers (talk without tapping every time; say \"bye\" to stop)",
            "💬 జవాబు తర్వాత కాసేపు వింటూ ఉండు (ప్రతిసారీ తట్టనక్కర్లేదు; ఆపడానికి \"చాలు\" అనండి)"), store.convo == "on") { store.convo = if (it) "on" else "off" }, 8)
        put(ui.row(ui.button("▶ Test voice", "primary") {
            if (!Settings.canDrawOverlays(this@MainActivity)) { toast("First allow \"Show over other apps\" below."); return@button }
            if (!PetService.running) startForegroundService(Intent(this@MainActivity, PetService::class.java))
            val line = if (store.lang == "en") "Hi! This is my voice. Do you like it?" else "నమస్తే! ఇది నా గొంతు. నచ్చిందా?"
            content.postDelayed({ try { startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_SAY).putExtra("text", line).putExtra("mood", "excited").putExtra("prefix", "").putExtra("test", true)) } catch (e: Exception) { } }, 900)
        }), 10)
        val server = store.server
        io.execute {
            val on = try {
                val c = java.net.URL("$server/health").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect(); j.optBoolean("tts", false)
            } catch (e: Exception) { null }
            runOnUiThread {
                status.text = when (on) {
                    true -> "✅ Natural voice is ready on your server."
                    false -> "The natural voice isn't switched on yet: your server needs a free Google key. Until then your pet uses the phone's own voice."
                    null -> "Couldn't reach your server to check the natural voice."
                }
            }
        }
    }

    /** The pets to choose from (newest first). */
    private fun petPicker(box: LinearLayout) {
        val current = PuppyView.kindOf(store.petKind)
        // newest pets first
        val kinds = PuppyView.Kind.values().sortedBy { PuppyView.NEW_KINDS.indexOf(it).let { i -> if (i < 0) 99 else i } }
        kinds.chunked(3).forEach { rowKinds ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKinds.forEach { k ->
                val chosen = k == current
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(10))
                    background = ui.round(if (chosen) Ui.SUN_SOFT else Ui.FIELD, 18, if (chosen) Ui.SUN else null)
                    addView(PuppyView(this@MainActivity).apply { kind = k; mood = if (chosen) PuppyView.Mood.EXCITED else PuppyView.Mood.HAPPY }, LinearLayout.LayoutParams(dp(70), dp(70)))
                    addView(ui.text((if (chosen) "✓ " else "") + k.label, 13.5f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER })
                    addView(ui.text(k.telugu, 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })
                    if (k in PuppyView.NEW_KINDS) addView(ui.text("✨ New", 11f, Ui.TEAL_DARK, bold = true).apply {
                        gravity = Gravity.CENTER; background = ui.round(Ui.TEAL_SOFT, 10); setPadding(dp(8), dp(1), dp(8), dp(1))
                    }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
                    setOnClickListener {
                        store.petKind = k.key
                        if (PetService.running) startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_PET))
                        render()
                    }
                }
                r.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
            repeat(3 - rowKinds.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
            box.put(r, 10)
        }
    }

    /** Overlay, microphone, notifications, exact alarms, contacts and Telugu voice. */
    private fun permissionRows(box: LinearLayout) {
        fun step(done: Boolean, label: String, why: String, action: () -> Unit) {
            box.put(ui.line(ui.stack(ui.text((if (done) "✅  " else "◻️  ") + label, 15f, Ui.INK, bold = true), ui.text(why, 12.5f, Ui.MUTED)),
                if (done) null else ui.button("Allow") { leavingForSystemScreen = true; action() }), 12)
        }
        step(Settings.canDrawOverlays(this), "Show over other apps", "So your pet can float on your screen.") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step(granted(Manifest.permission.RECORD_AUDIO), "Microphone", "Only while you tap your pet to talk.") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) step(granted(Manifest.permission.POST_NOTIFICATIONS), "Notifications", "For reminders, timers and files.") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            step(am.canScheduleExactAlarms(), "Exact reminders", "So reminders ring at the exact minute.") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        step(granted(Manifest.permission.READ_CONTACTS), "Contacts (optional)", "Only to find a number when you say \"call Ravi\". Never sent to the server.") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3)
        }
        box.put(ui.line(ui.stack(ui.text("🔊  Telugu voice", 15f, Ui.INK, bold = true), ui.text("Install once so your pet can speak Telugu.", 12.5f, Ui.MUTED)),
            ui.button("Install") {
                leavingForSystemScreen = true
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch (e: Exception) { toast("Open Settings › Text-to-speech › Google › Install voice data › Telugu") }
            }), 12)
    }

    internal fun messagesCard() = ui.section("Reply to messages · మెసేజ్ రిప్లైలు",
        "When a WhatsApp or SMS message comes, your pet shows it and writes a reply. Nothing is sent until you tap Send, unless Auto-reply is on.").apply {
        val access = MessageHub.accessGranted(this@MainActivity)
        put(ui.line(ui.stack(ui.text((if (access) "✅  " else "◻️  ") + "Notification access", 15f, Ui.INK, bold = true),
            ui.text("Lets your pet read new message notifications from the apps you tick.", 12.5f, Ui.MUTED)),
            if (access) null else ui.button("Allow") { showMessageDisclosure() }), 12)
        if (!access) put(ui.text("If Android says \"Restricted setting\": open Settings › Apps › Habit AI › ⋮ › Allow restricted settings, then tap Allow again.", 12f, Ui.MUTED), 6)
        put(ui.check("Watch new messages", store.msgWatch) { store.msgWatch = it; render() }, 10)
        if (store.msgWatch) {
            put(ui.text("Apps to watch", 14f, Ui.INK, bold = true), 8)
            val apps = store.msgApps
            MessageHub.APPS.forEach { (key, label, _) -> put(ui.check(label, key in apps) { on -> store.msgApps = if (on) store.msgApps + key else store.msgApps - key }, 0) }
            put(ui.check("Read new messages aloud", store.msgReadAloud) { store.msgReadAloud = it }, 8)
            put(ui.text("Auto-reply 🤖", 14f, Ui.INK, bold = true), 14)
            put(ui.check("Auto-reply ON (replies by itself)", store.autoReply) { store.autoReply = it }, 2)
            val autoBox = ui.input("Auto-reply message", store.autoMessage, multi = true)
            put(autoBox, 6)
            put(ui.row(ui.button("Save message") { store.autoMessage = autoBox.text.toString(); toast("Saved") }), 8)
            put(ui.check("Smart: adapt the reply to each message (language, greeting)", store.autoSmart) { store.autoSmart = it }, 6)
            put(ui.check("Add \"(auto-reply)\" at the end", store.autoTag) { store.autoTag = it }, 0)
            put(ui.text("Auto-reply never answers group chats, and replies only once per chat every 30 minutes.", 12f, Ui.MUTED), 6)
        }
    }

    /** Plus edition: Hands-free control (Accessibility) with Google's required disclosure first. */
    private fun handsFreeCard() = ui.section("Hands-free control 🎮",
        "Scroll Instagram Reels, YouTube Shorts and more by voice or with small remote buttons. Also Back, Home and Lock by voice.").apply {
        val on = HandsFreeService.isOn(this@MainActivity)
        put(ui.line(ui.stack(ui.text((if (on) "✅  " else "◻️  ") + "Hands-free control", 15f, Ui.INK, bold = true),
            ui.text(if (on) "On. Say a command or use the remote." else "Off. Needs your permission in Accessibility settings.", 12.5f, Ui.MUTED)),
            if (on) ui.button("Settings", "ghost") { leavingForSystemScreen = true; HandsFreeService.openSettings(this@MainActivity) }
            else ui.button("Turn on", "primary") { showHandsFreeDisclosure() }), 12)
        if (on) {
            put(ui.check("Show the Reels remote (⏮ ⏯ ⏭ ❤) on the left edge", store.remoteOn) {
                store.remoteOn = it
                if (PetService.running) startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_REMOTE))
            }, 10)
            put(ui.row(ui.button("🎙 Start Reels voice mode", "sun") {
                if (!Settings.canDrawOverlays(this@MainActivity)) { toast("First allow \"Show over other apps\" above."); return@button }
                if (!PetService.running) startForegroundService(Intent(this@MainActivity, PetService::class.java))
                content.postDelayed({ try { startService(Intent(this@MainActivity, PetService::class.java).setAction(PetService.ACTION_VOICE_MODE)) } catch (e: Exception) { } }, 900)
                moveTaskToBack(true)
            }), 10)
        }
        put(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("Say to your pet", 14f, Ui.TEAL_DARK, bold = true))
            put(ui.text("• \"next\" / \"నెక్స్ట్\": next reel\n• \"previous\" / \"మునుపటి\": previous reel\n• \"pause\" / \"ఆపు\": pause or play\n• \"like\" / \"లైక్\": like\n• \"go back\", \"go home\", \"lock screen\", \"screenshot\"\n• \"reels mode\": keeps listening for next/previous/pause/like (tap the pet to stop)", 13.5f, Ui.INK), 6)
        }, 12)
        put(ui.text("Only these fixed commands are used. Your pet never acts on its own, and Hands-free control never reads your screen.", 12f, Ui.MUTED), 8)
    }

    /** Google Play requires this clear explanation and consent before sending the user to Accessibility settings. */
    private fun showHandsFreeDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Allow Hands-free control?")
            .setMessage("Habit AI uses Android's Accessibility service to control your phone hands-free.\n\n" +
                "What it does: when you say a command (for example \"next reel\", \"pause\", \"go back\") or tap a Habit remote button, it performs exactly that one swipe, tap or button press.\n\n" +
                "What it does not do: it does not read, collect, store or send what is on your screen, and the AI never decides to act on its own.\n\n" +
                "Next, Android opens Accessibility settings. Tap \"Habit AI Hands-free control\" and switch it on. You can switch it off there at any time.")
            .setPositiveButton("Agree and continue") { _, _ -> leavingForSystemScreen = true; HandsFreeService.openSettings(this) }
            .setNegativeButton("No thanks", null)
            .show()
    }

    private fun briefingCard() = ui.section("Good-morning briefing ☀️",
        "Every morning your pet tells you today's panchangam (tithi, nakshatram, Rahu kalam), the weather, your reminders and to-dos.").apply {
        put(ui.check("Morning briefing ON", store.briefOn) { store.briefOn = it; Briefing.schedule(this@MainActivity); render() }, 10)
        if (store.briefOn) {
            val h = store.briefHour; val m = store.briefMinute
            put(ui.line(ui.text("Time: %d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM"), 15f, Ui.INK, bold = true),
                ui.button("Change") { TimePickerDialog(this@MainActivity, { _, hh, mm -> store.briefHour = hh; store.briefMinute = mm; Briefing.schedule(this@MainActivity); render() }, h, m, false).show() }), 8)
            val cityBox = ui.input("Your town or city, e.g. Visakhapatnam", store.city)
            put(cityBox, 8)
            put(ui.row(ui.button("Save city") { store.city = cityBox.text.toString(); toast("Saved") },
                ui.button("Try it now", "primary") { store.city = cityBox.text.toString(); Briefing.runNow(this@MainActivity); toast("Your briefing will arrive in a few seconds.") }), 8)
        }
    }

    // =================== 3. TASKS ===================
    private fun tasksPage() {
        if (tasksTab == "notes") tasksTab = "rem"            // notes now sit under reminders
        pageHeader("Tasks", "To-dos, reminders and notes, and checking fake messages")
        // the bar: three options
        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("todo" to "✅ To-do", "rem" to "⏰ Reminders & 📝 Notes", "fake" to "🛡️ Fake messages").forEachIndexed { i, (k, label) ->
                addView(ui.chip(label, tasksTab == k) { tasksTab = k; render(); scroll.scrollTo(0, 0) }.apply { maxLines = 2; textSize = 13.5f; setPadding(dp(8), dp(9), dp(8), dp(9)) },
                    LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(6) })
            }
        }, 14)
        when (tasksTab) {
            "rem" -> { remindersSection(); add(ui.label("📝 Notes · నోట్స్"), 22); notesSection() }
            "fake" -> messages.buildSafety()
            else -> todoSection()
        }
    }

    private fun todoSection() {
        val box = ui.input("Add a to-do, e.g. Pay electricity bill")
        add(ui.card().apply {
            addView(ui.line(box, ui.button("Add", "primary") { if (box.text.isNotBlank()) { store.addTodo(box.text.toString()); render() } }))
        }, 12)
        val todos = store.todos()
        if (todos.isEmpty()) add(ui.text("Nothing to do. Or say \"add buy milk to my list\" to your pet.", 14f, Ui.MUTED), 14)
        add(ui.card().apply {
            todos.forEachIndexed { i, t ->
                put(ui.line(ui.check(t.text, t.done) { c -> store.setTodoDone(i, c); render() }.apply { if (t.done) setTextColor(Ui.MUTED) },
                    ui.button("✕", "ghost") { store.removeTodo(i); render() }), if (i == 0) 0 else 8)
            }
            if (todos.isEmpty()) visibility = View.GONE
        }, 10)
    }

    private fun remindersSection() {
        // new reminder form
        var date = LocalDate.now(); var time = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        var repeat = ""
        val what = ui.input("What should I remind you about?")
        val dateBtn = ui.button("") { }; val timeBtn = ui.button("") { }
        fun refreshButtons() {
            dateBtn.text = "📅 " + DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(date)
            timeBtn.text = "🕒 " + DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(time)
        }
        refreshButtons()
        dateBtn.setOnClickListener { DatePickerDialog(this, { _, y, mo, d -> date = LocalDate.of(y, mo + 1, d); refreshButtons() }, date.year, date.monthValue - 1, date.dayOfMonth).show() }
        timeBtn.setOnClickListener { TimePickerDialog(this, { _, h, m -> time = LocalTime.of(h, m); refreshButtons() }, time.hour, time.minute, false).show() }
        val repeatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val repeatScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(repeatRow) }
        fun drawRepeat() {
            repeatRow.removeAllViews()
            listOf("" to "Once", "daily" to "Daily", "weekdays" to "Mon–Fri", "weekly" to "Weekly", "monthly" to "Monthly").forEachIndexed { i, (k, l) ->
                repeatRow.addView(ui.chip(l, repeat == k) { repeat = k; drawRepeat() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
            }
        }
        drawRepeat()
        add(ui.section("New reminder").apply {
            put(what, 10); put(ui.row(dateBtn, timeBtn), 10); put(repeatScroll, 10)
            put(ui.button("Save reminder", "primary") {
                val text = what.text.toString().trim()
                if (text.isEmpty()) { toast("Write what to remind you about."); return@button }
                var at = ZonedDateTime.of(date, time, ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at < System.currentTimeMillis()) { if (repeat.isEmpty()) { toast("That time has already passed."); return@button } else at = Reminders.nextTime(at, repeat) }
                val (_, exact) = Reminders.add(this@MainActivity, at, text, repeat)
                toast("Reminder saved" + if (exact) "" else " (may ring a few minutes late)"); render()
            }, 12)
        }, 12)

        val rems = store.reminders().sortedBy { it.at }
        add(ui.label("Coming up"), 18)
        if (rems.isEmpty()) add(ui.text("No reminders. You can also say \"remind me to take medicine every day at 8 am\".", 14f, Ui.MUTED), 8)
        rems.forEach { r ->
            add(ui.card().apply {
                addView(ui.line(ui.stack(ui.text(r.text, 15.5f, Ui.INK, bold = true),
                    ui.text(fmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())) + Reminders.repeatLabel(r.repeat).let { if (it.isEmpty()) "" else "  ·  🔁 $it" }, 13f, Ui.MUTED)),
                    ui.button("Cancel", "danger") { Reminders.cancel(this@MainActivity, r); render() }))
            }, 8)
        }
    }

    private fun notesSection() {
        val box = ui.input("Write a note…", multi = true)
        add(ui.card().apply {
            addView(box)
            put(ui.row(ui.button("Save note", "primary") { if (box.text.isNotBlank()) { store.addNote(box.text.toString()); render() } }), 10)
        }, 12)
        val notes = store.notes()
        if (notes.isEmpty()) add(ui.text("No notes yet. Or say \"note this number 98480 12345\".", 14f, Ui.MUTED), 14)
        notes.withIndex().reversed().forEach { (i, n) ->
            add(ui.card(Ui.SUN_SOFT).apply {
                addView(ui.text(n, 15f).apply { setTextIsSelectable(true) })
                put(ui.row(ui.button("Copy", "ghost") {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("note", n)); toast("Copied")
                }, ui.button("Delete", "danger") { store.removeNote(i); render() }), 10)
            }, 10)
        }
    }

    // =================== 4. FILES ===================
    private fun filesPage() {
        pageHeader("Files", "Applications, office letters, reports and Excel sheets, made step by step with you")
        letter.build()

        val files = store.files().sortedByDescending { it.at }
        add(ui.label("Your files"), 18)
        if (files.isEmpty()) add(ui.text("No files yet. Ask above, or tell your pet \"make an Excel list of my monthly expenses\".", 14f, Ui.MUTED), 8)
        val dm = getSystemService(DownloadManager::class.java)
        files.forEach { f ->
            val xl = f.format == "xlsx"
            val badge = ui.text(if (xl) "X" else "W", 18f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; background = ui.round(Color.parseColor(if (xl) "#1D7A47" else "#2B579A"), 12)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }
            add(ui.card().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(badge)
                    addView(ui.stack(ui.text(f.name, 15f, Ui.INK, bold = true), ui.text(fmt.format(Instant.ofEpochMilli(f.at).atZone(ZoneId.systemDefault())), 12.5f, Ui.MUTED)),
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                })
                val mime = if (xl) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                put(ui.row(
                    ui.button("Open", "primary") {
                        val uri = dm.getUriForDownloadedFile(f.id) ?: run { toast("This file is not on the phone any more."); return@button }
                        try { leavingForSystemScreen = true; startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (e: Exception) { toast("Install Microsoft Word / Excel or Google Docs / Sheets to open it.") }
                    },
                    ui.button("Share") {
                        val uri = dm.getUriForDownloadedFile(f.id) ?: run { toast("This file is not on the phone any more."); return@button }
                        leavingForSystemScreen = true
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}"))
                    },
                    ui.button("Remove", "danger") {
                        AlertDialog.Builder(this@MainActivity).setTitle("Remove ${f.name}?").setMessage("The file will be deleted from this phone.")
                            .setPositiveButton("Remove") { _, _ -> try { dm.remove(f.id) } catch (e: Exception) { }; store.removeFile(f.id); render() }
                            .setNegativeButton("Keep", null).show()
                    }), 12)
            }, 8)
        }
    }

    // =================== 5. VAULT ===================
    private fun vaultPage() {
        pageHeader("Vault 🔒", "Your documents and cards, locked on this phone")
        if (!VaultLock.isOpen()) {
            add(ui.card().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.text("🔐", 46f).apply { gravity = Gravity.CENTER })
                put(ui.text("Locked", 20f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 6)
                put(ui.text("Open with your fingerprint or phone lock. Screenshots are blocked here, and the Vault locks again after 2 minutes or when you leave the app.", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 6)
                put(ui.button("Unlock Vault", "primary") { unlockVault() }, 14)
            }, 14)
            add(ui.card(Ui.TEAL_SOFT).apply {
                addView(ui.text("How your Vault is protected", 14.5f, Ui.TEAL_DARK, bold = true))
                put(ui.text("• Encrypted with a key kept in your phone's secure chip\n• Never sent to the Habit server or to the AI\n• Not included in phone backups\n• No CVV or PIN boxes: banks say never to store those anywhere", 13.5f, Ui.INK), 6)
            }, 12)
            return
        }
        val items = vault.items()
        add(ui.row(ui.button("＋ Add document", "primary") { editVaultItem(null, "aadhaar") }, ui.button("＋ Add card / bank", "sun") { editVaultItem(null, "card") }), 14)
        cardScanCard()
        add(ui.button("🔒 Lock now", "ghost") { VaultLock.lock(); render() }.apply { }, 10)
        if (items.isEmpty()) add(ui.text("Empty. Add your Aadhaar, PAN, licence, bank cards and accounts, or let me find them in your screenshots.", 14f, Ui.MUTED), 16)
        val money = items.filter { it.type == "card" || it.type == "bank" }
        if (money.isNotEmpty()) {
            add(ui.label("Bank cards & accounts"), 18)
            money.sortedBy { it.title.lowercase() }.forEach { add(vaultCard(it), 8) }
        }
        // documents: one folder for each person
        val people = LinkedHashMap<String, MutableList<VaultItem>>()
        items.filter { it.type != "card" && it.type != "bank" }.sortedBy { it.holder.lowercase() }.forEach { d ->
            val k = people.keys.firstOrNull { it.isNotBlank() && CardFinder.samePerson(it, d.holder) } ?: d.holder.trim()
            people.getOrPut(k) { mutableListOf() }.add(d)
        }
        people.entries.sortedBy { if (it.key.isBlank()) "~" else it.key.lowercase() }.forEach { (name, list) ->
            val open = vaultOpenFolders.contains(name) || people.size <= 1
            add(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.round(Ui.TEAL_SOFT, 16)
                addView(ui.text((if (name.isBlank()) "📄 Other documents" else "👤 $name") + "  ·  ${list.size}", 15.5f, Ui.TEAL_DARK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.text(if (open) "▲" else "▼", 14f, Ui.TEAL_DARK))
                setOnClickListener { if (!vaultOpenFolders.remove(name)) vaultOpenFolders.add(name); render() }
            }, 16)
            if (open) list.sortedBy { it.type }.forEach { add(vaultCard(it), 8) }
        }
    }

    // =================== the camera ===================
    private var pendingCamera: Pair<Intent, Int>? = null
    private var pendingMirror = false
    private var mirrorShowing = false
    private val mirrorBrain by lazy { Brain(store) }

    /** Opens the phone's camera app for a photo (the camera permission is asked first when needed). */
    internal fun startCamera(i: Intent, req: Int, noCamera: String) {
        if (!granted(Manifest.permission.CAMERA)) { pendingCamera = i to req; requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_INTENT); return }
        leavingForSystemScreen = true
        try { startActivityForResult(i, req) } catch (e: Exception) { leavingForSystemScreen = false; toast(noCamera) }
    }

    /** The pet looks at you: no camera window, just the pet's note at the top ("👀 Looking at you…"); one photo from the
     *  front camera, then a kind comment from Claude, read aloud. (Android shows its own green camera dot meanwhile.) */
    internal fun lookAtMe(auto: Boolean) {
        if (mirrorShowing || isFinishing) return
        if (auto && !store.mirrorAsked) {
            // the first time: ask before ever using the camera
            store.mirrorAsked = true
            AlertDialog.Builder(this).setTitle("👀 Can I look at you?")
                .setMessage("When you open Habit AI, I can take a quick look at you with the front camera and say something kind (and a tip if you look tired). No camera window opens; Android shows its small green camera dot.\n\n" +
                    "The photo goes to Claude AI only for this and is not saved. At most once every 3 hours. You can turn it off on the Pet page.")
                .setPositiveButton("Yes, look at me") { _, _ -> store.mirrorOn = true; if (granted(Manifest.permission.CAMERA)) lookAtMe(false) else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_MIRROR) }
                .setNegativeButton("No, thanks") { _, _ -> store.mirrorOn = false }
                .show()
            return
        }
        if (!granted(Manifest.permission.CAMERA)) { if (!auto) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_MIRROR); return }
        mirrorShowing = true; store.mirrorAt = System.currentTimeMillis()
        val te = store.lang == "te"
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(12))
            background = ui.round(Ui.SURFACE, 20, Ui.LINE); elevation = dp(10).toFloat()
            addView(TextView(this@MainActivity).apply { text = "🐾"; textSize = 26f })
            addView(ui.text(if (te) "👀 మిమ్మల్ని చూస్తున్నాను…" else "👀 Looking at you…", 15f, Ui.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(TextView(this@MainActivity).apply { text = "✕"; textSize = 18f; setTextColor(Ui.MUTED); setPadding(dp(12), dp(4), dp(6), dp(4)); contentDescription = "Close" })
            setOnClickListener { stopTalking(); hideMirror() }
        }
        mirrorBanner = banner
        rootFrame.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(10), dp(12), 0) })
        val cam = MirrorCam(this, null) { jpeg -> if (mirrorBanner === banner) mirrorPhoto(jpeg, banner) }
        mirrorCam = cam
        cam.open()
        content.postDelayed({ if (mirrorBanner === banner) cam.snap() }, 1800)     // a moment for the camera to adjust to the light
    }

    private var mirrorBanner: LinearLayout? = null
    private var mirrorCam: MirrorCam? = null
    private fun mirrorSay(text: String) { (mirrorBanner?.getChildAt(1) as? TextView)?.text = text }
    private fun hideMirror() {
        mirrorCam?.cancel(); mirrorCam = null
        mirrorBanner?.let { try { rootFrame.removeView(it) } catch (e: Exception) { } }
        mirrorBanner = null; mirrorShowing = false
    }

    private fun mirrorPhoto(jpeg: ByteArray?, banner: LinearLayout) {
        mirrorCam = null
        val te = store.lang == "te"
        val gone = { content.postDelayed({ if (mirrorBanner === banner) hideMirror() }, 5000) }
        if (jpeg == null) { mirrorSay(if (te) "📷 కెమెరా తెరవలేకపోయాను. వేరే కెమెరా యాప్ మూసి మళ్ళీ అడగండి." else "📷 I couldn't use the camera. Close other camera apps and ask me again."); gone(); return }
        mirrorSay(if (te) "🐾 ఆలోచిస్తున్నాను…" else "🐾 Thinking…")
        io.execute {
            // upright, smaller, and without hidden details, before it goes anywhere
            val f = java.io.File(cacheDir, "mirror.jpg")
            val small = try { f.writeBytes(jpeg); Images.prepare(this, Uri.fromFile(f), 1024) } catch (e: Exception) { null } finally { f.delete() }
            runOnUiThread {
                if (mirrorBanner !== banner) return@runOnUiThread
                if (small == null) { mirrorSay("📷 Couldn't use that photo."); gone(); return@runOnUiThread }
                val ask = (if (te) "[కెమెరా] ఇది ఇప్పుడు నా ఫోన్ ముందు కెమెరాలో నేను. " else "[Camera] This is me right now, from my phone's front camera. ") +
                    "If you know my name from what you remember about me, call me by my name (don't try to recognise faces). " +
                    "Look at me kindly like a caring friend and answer in 2 or 3 short sentences: say something positive and genuine about how I look or my mood today (a nice smile, a bright colour, looking fresh or calm). " +
                    "If I look tired, sad or unwell, say it gently and give one caring tip (water, rest, a short walk, talk to someone). Then one small fun or useful idea for today. " +
                    "Never comment negatively on my body, weight, skin colour, age or looks, nothing private, and don't describe the background." +
                    (if (te) " తెలుగులో చెప్పు." else "")
                mirrorBrain.ask(ask, onReply = { json ->
                    if (mirrorBanner !== banner) return@ask
                    val reply = json.optString("reply")
                    mirrorSay(reply)
                    val snd = json.optString("sound")
                    val wait = if (snd.isNotEmpty() && store.voiceOn && store.funSounds) SoundFx.play(this, snd) else 0L
                    content.postDelayed({ if (mirrorBanner === banner) speakOut(reply) { content.postDelayed({ if (mirrorBanner === banner) hideMirror() }, 4000) } }, wait)
                }, onError = { err -> if (mirrorBanner === banner) { mirrorSay(err); gone() } },
                    photo = "image/jpeg" to android.util.Base64.encodeToString(small, android.util.Base64.NO_WRAP), local = false)
            }
        }
    }

    // =================== finding cards in screenshots (on this phone only) ===================
    private val vaultOpenFolders = HashSet<String>()
    private class CardScan(var total: Int) { var done = 0; val added = ArrayList<VaultItem>(); var same = 0; var none = 0; var problem = ""; var finished = false; var stop = false }
    private var cardScan: CardScan? = null

    private fun cardScanCard() {
        val sc = cardScan
        if (sc == null) {
            val choose = ui.text("🖼️ Or choose pictures yourself", 13.5f, Ui.TEAL_DARK, bold = true).apply { setPadding(0, dp(6), 0, dp(6)); setOnClickListener { pickCardPictures() } }
            if (BuildConfig.AUTO_CARDS && store.autoCards && AutoCards.allowed(this)) {
                val last = if (store.autoCardsAt > 0) " Last check: " + fmt.format(Instant.ofEpochMilli(store.autoCardsAt).atZone(ZoneId.systemDefault())) + "." else ""
                add(ui.card(Ui.TEAL_SOFT).apply {
                    addView(ui.text("🔍 Finding cards by itself: ON", 15.5f, Ui.TEAL_DARK, bold = true))
                    put(ui.text("I look at new screenshots and WhatsApp pictures while the phone is charging, and file the cards for each person. Found so far: ${store.autoCardsFound}.$last", 13.5f, Ui.INK), 4)
                    put(ui.row(ui.button("🔄 Check now", "primary") { startAutoScan() }, ui.button("Turn off", "ghost") { store.autoCards = false; AutoCards.cancel(this@MainActivity); render() }), 10)
                    put(choose, 6)
                }, 12)
            } else if (BuildConfig.AUTO_CARDS) {
                add(ui.card(Ui.SUN_SOFT).apply {
                    addView(ui.text("🔍 Find my cards automatically", 15.5f, Ui.INK, bold = true))
                    put(ui.text("I read your screenshots and WhatsApp pictures on this phone, find Aadhaar, PAN, voter ID, licence, passport and ration cards, and make a folder for each person. Nothing is sent anywhere. After the first look, new pictures are checked only while the phone charges.", 13.5f, Ui.MUTED), 4)
                    put(ui.button("🔍 Start", "primary") { turnOnAutoCards() }, 10)
                    put(choose, 6)
                }, 12)
            } else {
                add(ui.card(Ui.SUN_SOFT).apply {
                    addView(ui.text("🔍 Find my cards in screenshots", 15.5f, Ui.INK, bold = true))
                    put(ui.text("Choose screenshots or photos of Aadhaar, PAN, voter ID, licence, passport or ration cards (many at once). The phone reads them itself, nothing is sent anywhere, and each person's cards go in their own folder. (Google Play lets only gallery apps read all your photos, so please choose them here.)", 13.5f, Ui.MUTED), 4)
                    put(ui.button("🖼️ Choose screenshots", "primary") { pickCardPictures() }, 10)
                }, 12)
            }
            return
        }
        val added = synchronized(sc.added) { ArrayList(sc.added) }
        add(ui.card(if (sc.finished) Ui.TEAL_SOFT else Ui.SUN_SOFT).apply {
            if (!sc.finished) {
                addView(ui.text("🔍 Reading your pictures on this phone… " + (if (sc.total > 0) "${sc.done} / ${sc.total}" else ""), 15f, Ui.INK, bold = true))
                put(ui.text("Found so far: ${added.size}. You can use other pages meanwhile.", 13.5f, Ui.MUTED), 4)
                put(ui.button("Stop", "ghost") { sc.stop = true }, 8)
            } else {
                addView(ui.text(if (added.isEmpty()) "No new cards found" else "✅ Added ${added.size} card${if (added.size == 1) "" else "s"}", 15.5f, Ui.TEAL_DARK, bold = true))
                val byPerson = LinkedHashMap<String, MutableList<String>>()
                added.forEach { a -> val k = byPerson.keys.firstOrNull { CardFinder.samePerson(it, a.holder) } ?: a.holder.ifBlank { "Name not read" }; byPerson.getOrPut(k) { mutableListOf() }.add(VaultItem.label(a.type)) }
                if (byPerson.isNotEmpty()) put(ui.text(byPerson.entries.joinToString("\n") { "👤 ${it.key}: ${it.value.joinToString(", ")}" }, 14f, Ui.INK), 6)
                val rest = listOfNotNull(if (sc.same > 0) "${sc.same} already in your Vault" else null, if (sc.none > 0) "${sc.none} had no card" else null).joinToString(" · ")
                if (rest.isNotBlank()) put(ui.text(rest, 13f, Ui.MUTED), 6)
                if (sc.problem.isNotBlank()) put(ui.text("⚠️ " + sc.problem, 13f, Ui.DANGER), 6)
                put(ui.text("Check each card: tap it to fix the name or number if the phone read it wrong. Bank cards are not added this way; add them with ＋ Add card.", 12.5f, Ui.MUTED), 6)
                put(ui.row(ui.button("OK", "primary") { cardScan = null; render() }), 10)
            }
        }, 12)
    }

    /** Habit AI Lite: allow Photos once, then the phone finds the cards by itself. */
    private fun turnOnAutoCards() {
        if (AutoCards.allowed(this)) { store.autoCards = true; AutoCards.schedule(this); startAutoScan(); return }
        val perms = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            else arrayOf(AutoCards.permission())
        requestPermissions(perms, REQ_CARD_PERM)
    }

    private fun startAutoScan() {
        if (cardScan?.finished == false) return
        val sc = CardScan(0); cardScan = sc; render()
        Thread {
            AutoCards.scan(this, store, vault, if (store.autoCardsUpTo == 0L) AutoCards.FIRST_LOOK else 300) { done, total, o ->
                sc.total = total; sc.done = done; sc.same = o.same; sc.none = o.none; sc.problem = o.problem
                synchronized(sc.added) { sc.added.clear(); sc.added.addAll(o.added) }
                if (resumedNow && tab == "vault") VaultLock.markOpen()          // still here, using the Vault
                runOnUiThread { if (tab == "vault") render() }
                !sc.stop
            }
            sc.finished = true
            runOnUiThread { if (tab == "vault") render() }
        }.start()
    }

    private fun pickCardPictures() {
        leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_VAULT_CARDS) }
        catch (e: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_VAULT_CARDS) }
            catch (e2: Exception) { leavingForSystemScreen = false; toast("Couldn't open your photos.") }
        }
    }

    /** Reads each chosen picture on the phone; a card becomes a Vault item (with its photo), filed under the person's name. */
    private fun findCards(uris: List<Uri>) {
        if (uris.isEmpty() || !VaultLock.isOpen()) { render(); return }
        val sc = CardScan(uris.size); cardScan = sc; render()
        Thread {
            for (u in uris) {
                if (sc.stop) break
                when (val r = CardFinder.find(this, u)) {
                    is CardFinder.Result.Found -> try { if (AutoCards.keep(vault, r.card, r.jpeg)?.let { synchronized(sc.added) { sc.added.add(it) } } == null) sc.same++ } catch (e: Exception) { sc.problem = "Couldn't save one card." }
                    is CardFinder.Result.NotACard -> sc.none++
                    is CardFinder.Result.Problem -> { sc.problem = r.message; if (r.message.contains("text reader")) { sc.none += uris.size - sc.done - 1; sc.done = uris.size; break } }
                }
                sc.done++
                if (resumedNow && tab == "vault") VaultLock.markOpen()   // still here, using the Vault
                runOnUiThread { if (tab == "vault") render() }
            }
            sc.finished = true
            runOnUiThread { if (tab == "vault") render() }
        }.start()
    }

    private fun unlockVault() {
        leavingForSystemScreen = true
        VaultLock.unlock(this) { err -> leavingForSystemScreen = false; if (err == null) render() else toast(err) }
    }

    private fun vaultCard(item: VaultItem): View {
        val isCard = item.type == "card"
        val card = if (isCard) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(AppLook.mix(AppLook.PRIMARY, Color.BLACK, 0.5f), AppLook.PRIMARY)).apply { cornerRadius = ui.dpf(20) }
        } else ui.card()
        val fg = if (isCard) Color.WHITE else Ui.INK
        val sub = if (isCard) Color.parseColor("#CCFFFFFF") else Ui.MUTED
        var shown = false
        val num = ui.text(VaultItem.masked(item.number), if (isCard) 20f else 16f, fg, bold = true).apply { letterSpacing = 0.06f }
        card.addView(ui.line(ui.text(VaultItem.icon(item.type) + "  " + item.title, 15.5f, fg, bold = true), ui.text(VaultItem.label(item.type), 12f, sub)))
        card.addView(num, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (isCard) 14 else 8) })
        val details = listOfNotNull(
            item.holder.takeIf { s -> s.isNotBlank() }?.let { s -> (if (item.type == "bank") "Holder: " else "Name: ") + s },
            item.bank.takeIf { s -> s.isNotBlank() }?.let { s -> "Bank: $s" },
            item.extra.takeIf { s -> s.isNotBlank() }?.let { s -> when (item.type) { "card" -> "Valid thru: $s"; "bank" -> "IFSC: $s"; else -> "Date / validity: $s" } },
            item.notes.takeIf { s -> s.isNotBlank() }
        )
        if (details.isNotEmpty()) card.addView(ui.text(details.joinToString("\n"), 13f, sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val showBtn = ui.button("Show", if (isCard) "sun" else "soft") { }
        showBtn.setOnClickListener {
            if (!VaultLock.isOpen()) { render(); return@setOnClickListener }
            shown = !shown; num.text = if (shown) item.number else VaultItem.masked(item.number); showBtn.text = if (shown) "Hide" else "Show"
        }
        val buttons = mutableListOf<View>(showBtn,
            ui.button("Copy", if (isCard) "ghost" else "soft") { if (VaultLock.isOpen()) { VaultLock.copySecret(this, item.title, item.number); toast("Copied. It will be cleared from the clipboard in 1 minute.") } else render() })
        if (item.photo) buttons.add(ui.button("Photo", if (isCard) "ghost" else "soft") { showVaultPhoto(item) })
        buttons.add(ui.button("⋯", if (isCard) "ghost" else "soft") { vaultMenu(item) })
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ui.row(*buttons.toTypedArray())) }
        card.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return card
    }

    private fun vaultMenu(it: VaultItem) {
        if (!VaultLock.isOpen()) { render(); return }
        val opts = arrayOf("Edit", if (it.photo) "Change photo" else "Add photo", "Delete")
        AlertDialog.Builder(this).setTitle(it.title).setItems(opts) { _, w ->
            when (w) {
                0 -> editVaultItem(it, it.type)
                1 -> { pickingPhotoFor = it.id; startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO) }
                2 -> AlertDialog.Builder(this).setTitle("Delete ${it.title}?").setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> vault.remove(it.id); render() }.setNegativeButton("Keep", null).show()
            }
        }.show()
    }

    private fun showVaultPhoto(it: VaultItem) {
        if (!VaultLock.isOpen()) { render(); return }
        val bytes = vault.photo(it.id) ?: run { toast("Photo not found."); return }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run { toast("Couldn't open the photo."); return }
        val img = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val d = AlertDialog.Builder(this).setTitle(it.title).setView(img).setPositiveButton("Close", null).create()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.show()
    }

    /** Add or edit a vault item. Type decides which boxes show. */
    private fun editVaultItem(existing: VaultItem?, startType: String) {
        if (!VaultLock.isOpen()) { render(); return }
        var type = existing?.type ?: startType
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(typeRow) }
        val title = ui.input("Name, e.g. \"My Aadhaar\" or \"SBI Debit card\"", existing?.title ?: "")
        val number = ui.input("Number", existing?.number ?: "", number = true)
        val holder = ui.input("Name on it", existing?.holder ?: "")
        val bank = ui.input("Bank name", existing?.bank ?: "")
        val extra = ui.input("", existing?.extra ?: "")
        val notes = ui.input("Notes (optional)", existing?.notes ?: "", multi = true)
        val warn = ui.text("For your safety there is no box for CVV or PIN. Never store or share them.", 12.5f, Ui.DANGER)
        fun layout() {
            typeRow.removeAllViews()
            VaultItem.TYPES.forEachIndexed { i, (k, l, ic) ->
                typeRow.addView(ui.chip("$ic $l", type == k) { type = k; layout() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
            }
            val money = type == "card" || type == "bank"
            bank.visibility = if (money) View.VISIBLE else View.GONE
            warn.visibility = if (type == "card") View.VISIBLE else View.GONE
            number.hint = when (type) { "card" -> "Card number"; "bank" -> "Account number"; "aadhaar" -> "Aadhaar number"; "pan" -> "PAN number"; else -> "Document number" }
            holder.hint = when (type) { "card" -> "Name on card"; "bank" -> "Account holder"; else -> "Name on document" }
            extra.hint = when (type) { "card" -> "Valid thru (MM/YY)"; "bank" -> "IFSC code"; else -> "Date of birth / validity (optional)" }
        }
        layout()
        listOf<View>(typeScroll, title, number, holder, bank, extra, notes, warn).forEach { form.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        val sv = ScrollView(this).apply { addView(form) }
        val d = AlertDialog.Builder(this).setTitle(if (existing == null) "Add to Vault" else "Edit").setView(sv)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!VaultLock.isOpen()) { d.dismiss(); render(); return@setOnClickListener }
                val n = number.text.toString().trim()
                if (n.isEmpty()) { number.error = "Enter the number"; return@setOnClickListener }
                val t = title.text.toString().trim().ifEmpty { VaultItem.label(type) }
                vault.put(VaultItem(existing?.id ?: UUID.randomUUID().toString(), type, t, n, holder.text.toString().trim(),
                    extra.text.toString().trim(), if (type == "card" || type == "bank") bank.text.toString().trim() else "", notes.text.toString().trim(), existing?.photo ?: false))
                d.dismiss(); VaultLock.markOpen(); render()
            }
        }
        d.show()
    }

    // =================== 7. MEDIA (see Social.kt and MediaViewer.kt) ===================
    /** Opens the phone's photo picker for Media (up to [max] photos). */
    internal fun pickPhotos(max: Int) {
        leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").apply {
                if (max > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(max, MediaStore.getPickImagesMaxLimit()))
            }
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, max > 1)
        try { startActivityForResult(i, REQ_SOCIAL_PHOTO) }
        catch (e: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_SOCIAL_PHOTO) }
            catch (e2: Exception) { leavingForSystemScreen = false; toast("Couldn't open your photos.") }
        }
    }

    /** "Report" for an AI answer (letter helper, message check): sends the AI's answer to the Habit team after asking. */
    internal fun reportAi(where: String, answer: String, question: String) {
        AlertDialog.Builder(this).setTitle("Report this AI answer?")
            .setMessage(if (question.isBlank()) "The AI's answer is sent to the Habit team so they can check it and make Habit AI better."
                else "The AI's answer and your request are sent to the Habit team so they can check it and make Habit AI better.")
            .setPositiveButton("Report") { _, _ ->
                val body = JSONObject().put("reason", "Reported from $where").put("reply", answer.take(4000)).put("question", question.take(2000))
                io.execute { Net.post(store, "/api/report", body, emptyMap(), 20_000) }
                toast("Reported. Thank you!")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Opens the phone's picker for one video for Media. */
    internal fun pickVideo() {
        leavingForSystemScreen = true
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")
            else Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_SOCIAL_VIDEO) }
        catch (e: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_SOCIAL_VIDEO) }
            catch (e2: Exception) { leavingForSystemScreen = false; toast("Couldn't open your videos.") }
        }
    }

    /** "Share Habit AI" and "About", at the end of the Media page. */
    internal fun shareAndAbout() {
        add(ui.section("Share Habit AI", "Know someone who would like a pet friend on their phone?").apply {
            put(ui.row(ui.button("Share with friends", "sun") {
                leavingForSystemScreen = true
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Try Habit AI 🐾 – a cute pet on your phone screen that talks in Telugu and English, " +
                        (if (BuildConfig.MESSAGE_REPLIES) "sets reminders, writes letters, warns you about fraud messages and replies to WhatsApp." else "sets reminders, writes letters and warns you about fraud messages.") +
                        " " + (if (BuildConfig.SELF_UPDATE) "Download: " + Updates.link(store) else store.server)), "Share Habit AI"))
            }), 10)
        }, 18)

        add(ui.section("About").apply {
            put(ui.text("Habit AI is an AI and can make mistakes. Check important things before you rely on them.", 13.5f, Ui.MUTED), 6)
            put(ui.row(ui.button("Privacy policy", "ghost") {
                leavingForSystemScreen = true
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store.server + "/privacy"))) } catch (e: Exception) { }
            }), 10)
            put(ui.text("Version and updates: at the bottom of the Home page.", 12.5f, Ui.MUTED), 10)
        }, 12)
    }

    internal fun sendFeedback(rating: Int, kind: String, text: String, done: (Boolean) -> Unit) {
        val body = JSONObject().put("rating", rating).put("kind", kind).put("text", text)
            .put("version", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
            .put("android", Build.VERSION.RELEASE).put("pet", store.petKind).toString()
        val server = store.server
        io.execute {
            val ok = try {
                val c = java.net.URL("$server/api/feedback").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setRequestProperty("X-Device", store.deviceId)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode; c.disconnect(); code in 200..299
            } catch (e: Exception) { false }
            runOnUiThread { done(ok) }
        }
    }

    // =================== voice typing and reading aloud (letter helper, media) ===================
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false

    /** One time of listening. In [continuous] mode it keeps listening through pauses until you tap stop. */
    private class VoiceSession(val onPartial: (String) -> Unit, val onDone: (String?) -> Unit, val lang: String, val continuous: Boolean) {
        var text = ""        // the words heard so far (continuous mode adds each part)
        var quiet = 0        // restarts in a row with no words
        var errors = 0       // problems in a row (network, a busy voice service…)
    }
    private var voice: VoiceSession? = null
    private fun joinWords(a: String, b: String) = if (a.isBlank()) b.trim() else if (b.isBlank()) a else a.trimEnd() + " " + b.trim()

    /**
     * Voice typing. [onPartial] gets all the words so far while you speak; [onDone] gets the final text, or null.
     * [lang] (e.g. "hi-IN") overrides the app language. With [continuous], a pause doesn't stop it: it goes on
     * listening (and adds to the text) until you tap stop, or after about half a minute of silence.
     */
    internal fun listen(onPartial: (String) -> Unit, onDone: (String?) -> Unit, lang: String? = null, continuous: Boolean = false) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); toast("Allow the microphone, then tap 🎤 again."); onDone(null); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Voice typing isn't available on this phone. Please type instead."); onDone(null); return }
        stopTalking(); stopListening()
        val s = VoiceSession(onPartial, onDone, lang ?: if (store.lang == "en") "en-IN" else "te-IN", continuous)
        voice = s
        startVoice(s)
    }

    /** Starts (or starts again) listening for [s], with a fresh recognizer each time: a reused one often gets stuck. */
    private fun startVoice(s: VoiceSession) {
        if (voice !== s) return
        try { rec?.destroy() } catch (e: Exception) { }
        val r = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (e: Exception) { null }
        rec = r
        if (r == null) { toast("Voice typing isn't available right now. Please type instead."); finishVoice(s); return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (voice !== s) return
                when (error) {
                    // nothing heard: in continuous mode just listen again (a few times)
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (s.continuous && ++s.quiet < 4) againVoice(s, 120) else finishVoice(s)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        if (++s.errors <= 2) againVoice(s, 700) else { toast("Voice typing needs internet. Check your connection and tap 🎤 again."); finishVoice(s) }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { toast("Allow the microphone for Habit AI, then tap 🎤 again."); finishVoice(s) }
                    // the voice service was busy or disconnected: start it again
                    else -> if (++s.errors <= 3) againVoice(s, 450) else { toast("Voice typing stopped. Tap 🎤 to go on."); finishVoice(s) }
                }
            }
            override fun onPartialResults(partial: Bundle?) {
                if (voice !== s) return
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) s.onPartial(joinWords(s.text, it)) }
            }
            override fun onResults(results: Bundle?) {
                if (voice !== s) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) { s.text = joinWords(s.text, t); s.quiet = 0; s.errors = 0; s.onPartial(s.text) }
                else s.quiet++
                if (s.continuous && s.quiet < 4) againVoice(s, 120) else finishVoice(s)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, s.lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // wait longer before deciding you have finished (some phones follow these, others keep their own timing)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
        try { r.startListening(i) } catch (e: Exception) { if (++s.errors <= 2) againVoice(s, 500) else finishVoice(s) }
    }

    private fun againVoice(s: VoiceSession, delayMs: Long) { content.postDelayed({ startVoice(s) }, delayMs) }

    private fun finishVoice(s: VoiceSession) {
        if (voice !== s) return
        voice = null
        try { rec?.cancel() } catch (e: Exception) { }
        s.onDone(s.text.ifBlank { null })
    }

    /** Stops listening; whoever was listening gets the words heard so far (or null). */
    internal fun stopListening() {
        val s = voice
        if (s != null) finishVoice(s) else try { rec?.cancel() } catch (e: Exception) { }
    }

    /** Reads [text] aloud with the phone's voice, then calls [onDone]. */
    internal fun speakOut(text: String, onDone: (() -> Unit)? = null) {
        val t = tts
        if (t == null) {
            tts = TextToSpeech(this) { st -> ttsOk = st == TextToSpeech.SUCCESS; runOnUiThread { if (ttsOk) speakOut(text, onDone) else onDone?.invoke() } }
            return
        }
        if (!ttsOk) { onDone?.invoke(); return }
        val te = text.any { it in 'ఀ'..'౿' }
        val hi = !te && text.any { it in 'ऀ'..'ॿ' }
        val res = t.setLanguage(Locale.forLanguageTag(if (te) "te-IN" else if (hi) "hi-IN" else "en-IN"))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (te) toast("To hear Telugu, install the Telugu voice: Pet page › Permissions › Telugu voice.")
            else if (hi) toast("To hear Hindi, add the Hindi voice: phone Settings › Text-to-speech › Google › Install voice data › Hindi.")
            onDone?.invoke(); return
        }
        t.setPitch(1f); t.setSpeechRate(0.95f)
        // all of it is read: long text goes in parts, one after another (the voice reads at most ~4000 letters at once)
        val parts = Speech.chunks(text, 3800)
        if (parts.isEmpty()) { onDone?.invoke(); return }
        val base = "say" + System.nanoTime()
        val id = base + "_" + parts.lastIndex
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { if (u == id) runOnUiThread { onDone?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(u: String?) { if (u == id) runOnUiThread { onDone?.invoke() } }
        })
        parts.forEachIndexed { i, piece -> t.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, base + "_" + i) }
    }

    internal fun stopTalking() { try { tts?.stop() } catch (e: Exception) { } }

    // =================== first-time setup guide ===================
    private var setupStep = 0

    private fun renderSetup() {
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            for (i in 0..3) addView(View(this@MainActivity).apply { background = ui.round(if (i <= setupStep) Ui.TEAL else Ui.LINE, 4) },
                LinearLayout.LayoutParams(dp(if (i == setupStep) 26 else 10), dp(8)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        add(dots, 8)
        fun nav(nextLabel: String = "Next", onNext: () -> Unit = { setupStep++; render(); scroll.scrollTo(0, 0) }) {
            add(ui.line(if (setupStep > 0) ui.button("Back", "ghost") { setupStep--; render() } else View(this), ui.button(nextLabel, "primary") { onNext() }), 22)
        }
        fun bigPet(m: PuppyView.Mood) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(PuppyView(this@MainActivity).apply { kind = PuppyView.kindOf(store.petKind); mood = m }, LinearLayout.LayoutParams(dp(176), dp(176)))
        }
        when (setupStep) {
            0 -> {
                add(bigPet(PuppyView.Mood.EXCITED), 16)
                add(ui.text("నమస్తే! I'm Habit AI", 27f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 8)
                add(ui.text("Your pet friend on your phone. I talk in Telugu and English, set reminders, make Word and Excel files, " +
                    (if (BuildConfig.MESSAGE_REPLIES) "reply to WhatsApp and keep your cards safe." else "and keep your cards safe."), 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, 8)
                add(ui.section("Choose your language · మీ భాష").apply {
                    put(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        listOf("te" to "తెలుగు", "en" to "English", "auto" to "Both").forEachIndexed { i, (code, label) ->
                            addView(ui.chip(label, store.lang == code) { store.lang = code; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
                        }
                    }, 12)
                }, 18)
                nav()
            }
            1 -> {
                add(ui.title("Choose your pet"), 14); add(ui.text("మీ పెంపుడు జంతువు · you can change it any time", 14f, Ui.MUTED), 4)
                add(ui.card().apply { petPicker(this) }, 14)
                nav()
            }
            2 -> {
                add(ui.title("Allow a few things"), 14)
                add(ui.text("Only \"Show over other apps\" is needed to start. You can allow the others later.", 14f, Ui.MUTED), 4)
                add(ui.card().apply { permissionRows(this) }, 14)
                nav()
            }
            else -> {
                add(bigPet(PuppyView.Mood.LOVE), 16)
                add(ui.text("All set! 🎉", 27f, Ui.INK, bold = true).apply { gravity = Gravity.CENTER }, 8)
                add(ui.card().apply {
                    listOf("👆" to "Tap me to talk", "✋" to "Hold me for the menu: type, photo, lists", "↕️" to "Drag me anywhere; drag me down to ✕ to close",
                        "📱" to "Open this app for tasks, files, your Vault and settings").forEachIndexed { i, (ic, t) ->
                        put(ui.row(ui.text(ic, 20f), ui.text(t, 15f), gap = 12), if (i == 0) 0 else 12)
                    }
                }, 14)
                nav("Start! Show my pet") {
                    store.onboarded = true; tab = "home"
                    if (Settings.canDrawOverlays(this)) { startForegroundService(Intent(this, PetService::class.java)); moveTaskToBack(true) }
                    else toast("Allow \"Show over other apps\" on the Pet page, then tap Show my pet.")
                    content.postDelayed({ render() }, 400)
                }
            }
        }
    }

    // =================== system results ===================
    internal fun showMessageDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Read message notifications?")
            .setMessage("To warn you about fraud and to suggest replies, Habit AI reads new message notifications from the chat apps you choose (for example WhatsApp or SMS).\n\n" +
                "Every new message is first checked on your phone. Messages with warning signs (links, OTP or money requests…) are sent to the Habit AI server and to Anthropic's Claude AI for a safety check. " +
                "To write a reply, the sender's name and message text are sent the same way. They are not stored on the server. The list of checked messages stays only on your phone.\n\n" +
                "Nothing is sent to the other person unless you tap Send or switch on Auto-reply. You can turn this off at any time.")
            .setPositiveButton("Agree and continue") { _, _ -> store.msgWatch = true; leavingForSystemScreen = true; MessageHub.openAccessSettings(this) }
            .setNegativeButton("Not now", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VaultLock.REQ_UNLOCK -> { if (resultCode == RESULT_OK) VaultLock.markOpen(); render() }
            REQ_SOCIAL_VIDEO -> { if (resultCode == RESULT_OK) data?.data?.let { social.onVideo(it) } }
            REQ_TR_PHOTO -> { if (resultCode == RESULT_OK) data?.data?.let { translate.onPhoto(it) } }
            REQ_TR_CAMERA -> { if (resultCode == RESULT_OK) translate.onCamera() }
            REQ_CONTACT -> { if (resultCode == RESULT_OK) data?.data?.let { family.onContact(it) } }
            REQ_LETTER_CAMERA -> { if (resultCode == RESULT_OK) letter.onCamera() }
            REQ_MSG_CAMERA -> { if (resultCode == RESULT_OK) messages.onCamera() }
            REQ_MSG_PHOTO -> { if (resultCode == RESULT_OK) data?.data?.let { messages.onPicture(it) } }
            REQ_VAULT_CARDS -> {
                if (resultCode != RESULT_OK || data == null) { render(); return }
                val list = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { list.add(it) } }
                if (list.isEmpty()) data.data?.let { list.add(it) }
                findCards(list)
            }
            REQ_LETTER_PDF -> { if (resultCode == RESULT_OK) data?.data?.let { letter.onPdf(it) } }
            REQ_LETTER_PHOTOS -> {
                if (resultCode != RESULT_OK || data == null) return
                val list = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { list.add(it) } }
                if (list.isEmpty()) data.data?.let { list.add(it) }
                if (list.isNotEmpty()) letter.onPhotos(list)
            }
            REQ_SOCIAL_PHOTO -> {
                if (resultCode != RESULT_OK || data == null) return
                val list = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { list.add(it) } }
                if (list.isEmpty()) data.data?.let { list.add(it) }
                if (list.isNotEmpty()) social.onPhotos(list)
            }
            REQ_PHOTO -> {
                val id = pickingPhotoFor; pickingPhotoFor = null
                val uri = data?.data
                if (resultCode != RESULT_OK || id == null || uri == null || !VaultLock.isOpen()) { render(); return }
                io.execute {
                    val ok = try {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                        if (bmp == null) false else {
                            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            vault.savePhoto(id, out.toByteArray())
                            vault.items().firstOrNull { it.id == id }?.let { vault.put(it.copy(photo = true, updated = System.currentTimeMillis())) }
                            true
                        }
                    } catch (e: Exception) { false }
                    runOnUiThread { toast(if (ok) "Photo saved in your Vault." else "Couldn't save that photo."); render() }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val yes = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_CAM_INTENT) {
            val p = pendingCamera; pendingCamera = null
            if (yes && p != null) { leavingForSystemScreen = true; try { startActivityForResult(p.first, p.second) } catch (e: Exception) { leavingForSystemScreen = false; toast("No camera app found.") } }
            else if (!yes) toast("Allow the camera to take a photo here.")
        }
        if (requestCode == REQ_CHAT_CONTACTS) { if (tab == "messages") render() }
        if (requestCode == REQ_CARD_PERM) {
            if (AutoCards.allowed(this)) { store.autoCards = true; AutoCards.schedule(this); startAutoScan() }
            else toast("Allow Photos so I can look at your screenshots, or tap \"choose pictures yourself\".")
        }
        if (requestCode == REQ_CAM_MIRROR) { if (yes) content.postDelayed({ lookAtMe(false) }, 300) else { store.mirrorOn = false; toast("OK. I won't use the camera.") } }
        if (requestCode == REQ_LOCATION) family.onLocationAnswer()
        if (requestCode == REQ_CALL && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
            toast("OK. SOS will open the phone app with the number ready, and you tap Call.")
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && PetService.running) {
            stopService(Intent(this, PetService::class.java))
            content.postDelayed({ startForegroundService(Intent(this, PetService::class.java)) }, 500)
        }
        render()
    }
}
