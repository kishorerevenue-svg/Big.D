package com.soulfriend.pet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Which voice typing the pet listens with. Some phones' usual voice typing gives up a second after it starts (before
 * people begin to talk) or doesn't know Telugu; then the pet tries the Google app's voice typing (when the phone has
 * it) and remembers the one that worked. Nothing is recorded or kept: this only chooses the phone's voice typing.
 */
object Ears {
    private const val GSA = "com.google.android.googlequicksearchbox"

    /** The phone's usual voice typing (Settings), when the phone says which. */
    fun usual(ctx: Context): ComponentName? = try {
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }
    } catch (e: Exception) { null }

    /** Every voice typing on this phone. */
    fun all(ctx: Context): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { r -> r.serviceInfo?.let { ComponentName(it.packageName, it.name) } }.distinct()
    } catch (e: Exception) { emptyList() }

    /** The one that worked last time (if it's still on the phone), else the usual one. */
    fun start(ctx: Context): ComponentName? {
        val saved = try { Store(ctx).ears } catch (e: Exception) { "" }
        val c = saved.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }
        return if (c != null && c in all(ctx)) c else usual(ctx)
    }

    /**
     * Another voice typing to try when [now] keeps giving up: the Google app's (it knows Telugu well); with [anyKind]
     * (this one can't work at all: busy, broken, doesn't know the language) any other one too. Null: nothing else.
     */
    fun other(ctx: Context, now: ComponentName?, anyKind: Boolean): ComponentName? {
        val cur = now ?: usual(ctx)
        val list = all(ctx).filter { it != cur }
        return list.firstOrNull { it.packageName == GSA } ?: (if (anyKind) list.firstOrNull() else null)
    }

    /** It heard words with [c]: start with it next time (nothing to keep when it's the usual one). */
    fun worked(ctx: Context, c: ComponentName?) {
        try {
            val s = Store(ctx)
            val v = if (c == null || c == usual(ctx)) "" else c.flattenToString()
            if (s.ears != v) s.ears = v
        } catch (e: Exception) { }
    }

    fun create(ctx: Context, c: ComponentName?): SpeechRecognizer = try {
        if (c == null) SpeechRecognizer.createSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx, c)
    } catch (e: Exception) { SpeechRecognizer.createSpeechRecognizer(ctx) }
}
