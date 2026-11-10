package com.soulfriend.pet

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BigD Chat's lock, end to end. Every phone has its own key pair (P-256); only the public half goes to the server.
 * A message is locked with a fresh random key (AES-256-GCM), and that key is locked once for each person in the chat
 * (including the sender) with a key both sides can work out (ECDH with a one-time key pair, then HKDF-SHA256). So the
 * server, which only passes messages on, can't read them. Photos and voice notes are locked with their own random key,
 * which travels inside the locked message. Plain Java crypto: nothing extra in the app, and it runs on any Android 8+.
 */
object ChatCrypto {
    private val rnd = SecureRandom()
    private val INFO = "bigd-chat-v1".toByteArray(Charsets.UTF_8)
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
    fun pub(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(b64)))
    fun priv(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    fun pubB64(k: PublicKey): String = b64(k.encoded)
    /** A short name for a public key (the same as the server's). */
    fun kid(pubB64: String): String = MessageDigest.getInstance("SHA-256").digest(pubB64.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); doFinal(data) }
    private fun ecdh(mine: PrivateKey, theirs: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(theirs, true); generateSecret() }
    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte()); t = mac.doFinal()
            val n = minOf(t.size, len - pos); System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }

    /** A locked message: the one-time public key, the message (iv + text), its key locked for each person, and which of their keys was used. */
    class Sealed(val epk: String, val iv: String, val ct: String, val keys: Map<String, String>, val kids: Map<String, String>)

    /** Locks [plain] for these people: { uid: their public key (base64) }. */
    fun seal(plain: ByteArray, to: Map<String, String>): Sealed {
        val msgKey = bytes(32); val iv = bytes(12)
        val ct = gcm(Cipher.ENCRYPT_MODE, msgKey, iv, plain)
        val eph = newKeyPair(); val epk = eph.public.encoded
        val keys = HashMap<String, String>(); val kids = HashMap<String, String>()
        for ((uid, pb) in to) {
            val wrap = hkdf(ecdh(eph.private, pub(pb)), epk, INFO)
            val iv2 = bytes(12)
            keys[uid] = b64(iv2 + gcm(Cipher.ENCRYPT_MODE, wrap, iv2, msgKey))
            kids[uid] = kid(pb)
        }
        return Sealed(b64(epk), b64(iv), b64(ct), keys, kids)
    }

    /** Opens a message locked for me ([key] = the message key locked for me). null when it can't be opened. */
    fun open(epk: String, iv: String, ct: String, key: String, mine: PrivateKey): ByteArray? = try {
        val e = unb64(epk); val w = unb64(key)
        val wrap = hkdf(ecdh(mine, KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(e))), e, INFO)
        val msgKey = gcm(Cipher.DECRYPT_MODE, wrap, w.copyOfRange(0, 12), w.copyOfRange(12, w.size))
        gcm(Cipher.DECRYPT_MODE, msgKey, unb64(iv), unb64(ct))
    } catch (e: Exception) { null }

    /** A photo or voice note: (the locked file, its key, its iv). */
    fun lockFile(data: ByteArray): Triple<ByteArray, String, String> {
        val k = bytes(32); val iv = bytes(12)
        return Triple(gcm(Cipher.ENCRYPT_MODE, k, iv, data), b64(k), b64(iv))
    }
    fun openFile(data: ByteArray, key: String, iv: String): ByteArray? = try { gcm(Cipher.DECRYPT_MODE, unb64(key), unb64(iv), data) } catch (e: Exception) { null }
}
