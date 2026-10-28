package com.soulfriend.pet

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One saved document, bank card or bank account. Never leaves the phone. */
data class VaultItem(
    val id: String,
    val type: String,
    val title: String,
    val number: String,
    val holder: String = "",
    val extra: String = "",
    val bank: String = "",
    val notes: String = "",
    val photo: Boolean = false,
    val updated: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("title", title).put("number", number)
        .put("holder", holder).put("extra", extra).put("bank", bank).put("notes", notes).put("photo", photo).put("updated", updated)

    companion object {
        fun from(o: JSONObject) = VaultItem(o.getString("id"), o.optString("type"), o.optString("title"), o.optString("number"),
            o.optString("holder"), o.optString("extra"), o.optString("bank"), o.optString("notes"), o.optBoolean("photo"), o.optLong("updated"))

        /** type key -> (label, icon) */
        val TYPES = listOf(
            Triple("aadhaar", "Aadhaar card", "🪪"), Triple("pan", "PAN card", "🧾"), Triple("voter", "Voter ID", "🗳️"),
            Triple("dl", "Driving licence", "🚗"), Triple("passport", "Passport", "🛂"), Triple("ration", "Ration card", "🌾"),
            Triple("doc", "Other document", "📄"), Triple("card", "Bank card", "💳"), Triple("bank", "Bank account", "🏦")
        )
        fun label(type: String) = TYPES.firstOrNull { it.first == type }?.second ?: "Document"
        fun icon(type: String) = TYPES.firstOrNull { it.first == type }?.third ?: "📄"

        /** •••• •••• 1234 */
        fun masked(n: String): String {
            val digits = n.filter { it.isLetterOrDigit() }
            if (digits.length <= 4) return "••••"
            return "•••• •••• " + digits.takeLast(4)
        }
    }
}

/** AES-256-GCM with a key kept inside the phone's secure hardware (Android Keystore). */
object VaultCrypto {
    private const val ALIAS = "bigd_vault_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** Encrypted storage for vault items and their photos. */
class VaultStore(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("bigd_vault", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "vault").apply { mkdirs() }

    fun items(): List<VaultItem> {
        val enc = p.getString("data", null) ?: return emptyList()
        return try {
            val a = JSONArray(String(VaultCrypto.decrypt(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8))
            (0 until a.length()).map { VaultItem.from(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(list: List<VaultItem>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        p.edit().putString("data", Base64.encodeToString(VaultCrypto.encrypt(a.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }

    fun put(item: VaultItem) {
        val l = items().toMutableList(); val i = l.indexOfFirst { it.id == item.id }
        if (i >= 0) l[i] = item else l.add(item); save(l)
    }

    fun remove(id: String) { save(items().filter { it.id != id }); photoFile(id).delete() }

    private fun photoFile(id: String) = File(dir, id.replace(Regex("[^\\w-]"), "") + ".bin")
    fun savePhoto(id: String, jpeg: ByteArray) = photoFile(id).writeBytes(VaultCrypto.encrypt(jpeg))
    fun photo(id: String): ByteArray? = try { VaultCrypto.decrypt(photoFile(id).readBytes()) } catch (e: Exception) { null }
}

/** Fingerprint / face / phone-lock gate. The vault stays open for 2 minutes, and locks when you leave the app. */
object VaultLock {
    const val REQ_UNLOCK = 4401
    private const val OPEN_MS = 2 * 60_000L
    @Volatile private var openedAt = 0L

    fun isOpen() = System.currentTimeMillis() - openedAt < OPEN_MS
    fun lock() { openedAt = 0L }
    fun markOpen() { openedAt = System.currentTimeMillis() }

    fun hasScreenLock(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Shows the phone's own fingerprint / PIN screen. [done] gets null on success, or a message. */
    fun unlock(act: Activity, done: (String?) -> Unit) {
        if (!hasScreenLock(act)) { done("Set a screen lock (PIN, pattern or fingerprint) in your phone's Settings first. The Vault needs it."); return }
        if (Build.VERSION.SDK_INT >= 28) {
            val b = BiometricPrompt.Builder(act).setTitle("Open your Vault").setSubtitle("Use your fingerprint or phone lock")
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            b.build().authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { markOpen(); done(null) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(errString?.toString() ?: "Not unlocked") }
            })
        } else {
            @Suppress("DEPRECATION")
            val i = act.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Open your Vault", "Use your phone lock")
            if (i == null) { markOpen(); done(null) } else @Suppress("DEPRECATION") act.startActivityForResult(i, REQ_UNLOCK)
        }
    }

    /** Copies a number, marks it sensitive (hidden in the clipboard preview) and clears it after 60 seconds. */
    fun copySecret(ctx: Context, label: String, value: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            try { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == value) { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", "")) } } catch (e: Exception) { }
        }, 60_000)
    }
}
