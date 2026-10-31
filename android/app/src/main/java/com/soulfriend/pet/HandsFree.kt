package com.soulfriend.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Hands-free control (Plus edition only). Google Play rules: this service only performs
 * FIXED actions that the user asks for, one command = one action. The AI never decides to
 * act, and the service never reads what is on the screen (canRetrieveWindowContent=false).
 */
class HandsFreeService : AccessibilityService() {

    companion object {
        @Volatile var instance: HandsFreeService? = null

        fun isOn(ctx: Context): Boolean {
            if (instance != null) return true
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, HandsFreeService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }

        fun openSettings(ctx: Context) {
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }

        /** Runs one command. Returns false if Hands-free control is off or the phone can't do it. */
        fun run(cmd: HandsFree.Cmd): Boolean = instance?.perform(cmd) ?: false
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used: we never read the screen */ }
    override fun onInterrupt() {}

    private fun perform(cmd: HandsFree.Cmd): Boolean = when (cmd) {
        HandsFree.Cmd.NEXT -> swipe(up = true)
        HandsFree.Cmd.PREVIOUS -> swipe(up = false)
        HandsFree.Cmd.PAUSE -> tap(1)
        HandsFree.Cmd.LIKE -> tap(2)
        HandsFree.Cmd.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        HandsFree.Cmd.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        HandsFree.Cmd.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        HandsFree.Cmd.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        HandsFree.Cmd.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        HandsFree.Cmd.LOCK -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        HandsFree.Cmd.SCREENSHOT -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun size(): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    /** up = finger moves up = next reel / scroll down the page. */
    private fun swipe(up: Boolean): Boolean {
        val (w, h) = size()
        val x = w * 0.5f
        val (y1, y2) = if (up) h * 0.72f to h * 0.28f else h * 0.28f to h * 0.72f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null)
    }

    /** One tap = pause/play, two quick taps = like (in the middle of the screen). */
    private fun tap(times: Int): Boolean {
        val (w, h) = size()
        val b = GestureDescription.Builder()
        for (i in 0 until times) {
            val p = Path().apply { moveTo(w * 0.5f, h * 0.45f) }
            b.addStroke(GestureDescription.StrokeDescription(p, i * 120L, 40))
        }
        return dispatchGesture(b.build(), null, null)
    }
}

/** The fixed voice commands, matched on the phone (never sent to the AI). */
object HandsFree {
    enum class Cmd(val label: String) {
        NEXT("Next ⏭"), PREVIOUS("Previous ⏮"), PAUSE("Pause / play ⏯"), LIKE("Like ❤️"),
        BACK("Back"), HOME("Home"), RECENTS("Recent apps"), NOTIFICATIONS("Notifications"),
        QUICK_SETTINGS("Quick settings"), LOCK("Lock screen"), SCREENSHOT("Screenshot")
    }

    private val WORDS: List<Pair<Cmd, List<String>>> = listOf(
        Cmd.NEXT to listOf("next", "next reel", "next video", "next one", "skip", "scroll down", "swipe up", "down",
            "నెక్స్ట్", "నెక్స్ట్ రీల్", "తరువాత", "తర్వాత", "తర్వాతది", "తరువాతి రీల్", "కిందకి", "స్కిప్"),
        Cmd.PREVIOUS to listOf("previous", "previous reel", "previous video", "last reel", "go up", "scroll up", "swipe down", "up",
            "ప్రీవియస్", "ముందుది", "మునుపటి", "మునుపటి రీల్", "పైకి", "వెనకటి రీల్"),
        Cmd.PAUSE to listOf("pause", "play", "stop", "stop video", "resume", "పాజ్", "ప్లే", "ఆపు", "ఆపేయ్"),
        Cmd.LIKE to listOf("like", "like it", "like this", "లైక్", "లైక్ చెయ్"),
        Cmd.BACK to listOf("go back", "back", "బ్యాక్", "వెనక్కి", "వెనక్కి వెళ్ళు"),
        Cmd.HOME to listOf("go home", "home", "home screen", "హోమ్", "హోం"),
        Cmd.RECENTS to listOf("recent apps", "recents", "రీసెంట్ యాప్స్"),
        Cmd.NOTIFICATIONS to listOf("notifications", "open notifications", "show notifications", "నోటిఫికేషన్లు", "నోటిఫికేషన్స్"),
        Cmd.QUICK_SETTINGS to listOf("quick settings", "క్విక్ సెట్టింగ్స్"),
        Cmd.LOCK to listOf("lock screen", "lock the phone", "lock phone", "lock", "లాక్", "లాక్ చెయ్"),
        Cmd.SCREENSHOT to listOf("take screenshot", "take a screenshot", "screenshot", "స్క్రీన్‌షాట్", "స్క్రీన్ షాట్")
    )

    /** Only short, exact commands count, so normal chat ("what's next today?") is never taken as a command. */
    fun match(text: String): Cmd? {
        var t = text.lowercase().replace(Regex("[.,!?।]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.removePrefix("please ").removePrefix("bigd ").removePrefix("big d ").removeSuffix(" please").trim()
        if (t.isEmpty() || t.split(" ").size > 4) return null
        return WORDS.firstOrNull { (_, words) -> t in words }?.first
    }
}
