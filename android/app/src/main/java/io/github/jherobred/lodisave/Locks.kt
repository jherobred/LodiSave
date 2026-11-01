package io.github.jherobred.lodisave

import android.content.Context
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Photos and videos locked with a password, as on Windows and in the same file format, so a file
 * locked on one opens on the other with its password. Each lock makes a random 256-bit file key that
 * seals the name and bytes (AES-GCM); the key is sealed with the password (PBKDF2-SHA256 at 600,000
 * rounds) and, when an authenticator is linked, with the recovery public key (RSA-OAEP, SHA-256). The
 * file is "LODILOCK", 2, flags (1: the recovery key can open it), salt, the password's IV and sealed
 * key, the recovery key's length and sealed key, the data IV and the sealed name and bytes.
 *
 * Locked files live in LodiSave's own storage, where other apps can't reach them, with the folder each
 * came from. Passwords and the recovery key opened this session stay in memory until Lock again.
 */
object Locks {
    private const val MAGIC = "LODILOCK"
    private const val ROUNDS = 600_000
    /** Whole files go through memory. */
    const val MAX = 512L shl 20
    private val random = SecureRandom()

    /** Goes up when locked files or what's unlocked change, so screens look again. */
    var changes by mutableIntStateOf(0)
        private set

    val sessions = mutableListOf<String>()
    var recoveryKey: PrivateKey? = null
        private set
    fun unlocked() = sessions.isNotEmpty() || recoveryKey != null

    /** A locked file, with what it opened to this session, if anything. */
    class Entry(val file: File, val folder: String, val at: Long) {
        val id get() = file.nameWithoutExtension
        val bytes get() = file.length()
    }

    class Opened(val name: String, val bytes: ByteArray, val key: ByteArray, val parts: Parts) {
        val mime get() = Saver.mimeOf(name)
        val kind get() = when {
            mime == "image/gif" -> "gif"
            mime.startsWith("image/") -> "photo"
            mime.startsWith("audio/") -> "audio"
            else -> "video"
        }
        var thumb: ImageBitmap? = null
    }

    private val opened = HashMap<String, Opened>()
    private val keys = HashMap<String, SecretKey>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "locked").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")
    private fun readIndex(ctx: Context) = runCatching { JSONObject(index(ctx).readText()) }.getOrElse { JSONObject() }

    fun list(ctx: Context): List<Entry> {
        val idx = readIndex(ctx)
        return dir(ctx).listFiles { f -> f.extension == "lodilock" }.orEmpty().map { f ->
            val o = idx.optJSONObject(f.nameWithoutExtension)
            Entry(f, o?.optString("folder").orEmpty(), o?.optLong("at") ?: f.lastModified())
        }.sortedByDescending { it.at }
    }

    fun openedOf(e: Entry) = opened[e.id]

    // Format ------------------------------------------------------------------------------------

    class Parts(val salt: ByteArray, val ivP: ByteArray, val wP: ByteArray, val wR: ByteArray, val ivD: ByteArray, val data: ByteArray)

    private fun rand(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun pack(p: Parts): ByteArray = ByteBuffer.allocate(100 + p.wR.size + p.data.size).apply {
        put(MAGIC.toByteArray()); put(2); put(if (p.wR.isNotEmpty()) 1 else 0)
        put(p.salt); put(p.ivP); put(p.wP); putShort(p.wR.size.toShort()); put(p.wR); put(p.ivD); put(p.data)
    }.array()

    fun parse(b: ByteArray): Parts? {
        if (b.size < 100 || String(b, 0, 8) != MAGIC || b[8].toInt() != 2) return null
        val n = ((b[86].toInt() and 0xff) shl 8) or (b[87].toInt() and 0xff)
        if (b.size < 100 + n) return null
        return Parts(b.copyOfRange(10, 26), b.copyOfRange(26, 38), b.copyOfRange(38, 86), b.copyOfRange(88, 88 + n),
            b.copyOfRange(88 + n, 100 + n), b.copyOfRange(100 + n, b.size))
    }

    private fun aes(mode: Int, key: SecretKey, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, iv)) }.doFinal(data)

    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    private fun rsa(mode: Int, key: java.security.Key, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(mode, key, OAEP) }.doFinal(data)

    private fun keyFor(password: String, salt: ByteArray): SecretKey = synchronized(keys) {
        keys.getOrPut("${salt.toHex()}\u0000$password") {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)).encoded
            SecretKeySpec(raw, "AES")
        }
    }

    fun seal(bytes: ByteArray, name: String, password: String, salt: ByteArray, pub: PublicKey?): ByteArray {
        val k = rand(32)
        val title = name.toByteArray()
        val plain = ByteBuffer.allocate(4 + title.size + bytes.size).putInt(title.size).put(title).put(bytes).array()
        val ivD = rand(12)
        val ivP = rand(12)
        return pack(Parts(salt, ivP, aes(Cipher.ENCRYPT_MODE, keyFor(password, salt), ivP, k), pub?.let { rsa(Cipher.ENCRYPT_MODE, it, k) } ?: ByteArray(0),
            ivD, aes(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), ivD, plain)))
    }

    /** What a locked file holds, opened with one of `passwords` or the recovery key, or null. */
    fun unseal(b: ByteArray, passwords: List<String>, recovery: PrivateKey? = null): Opened? {
        val p = parse(b) ?: return null
        var k: ByteArray? = null
        for (pw in passwords) {
            k = runCatching { aes(Cipher.DECRYPT_MODE, keyFor(pw, p.salt), p.ivP, p.wP) }.getOrNull()
            if (k != null) break
        }
        if (k == null && recovery != null && p.wR.isNotEmpty()) k = runCatching { rsa(Cipher.DECRYPT_MODE, recovery, p.wR) }.getOrNull()
        if (k == null) return null
        val plain = aes(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), p.ivD, p.data)
        val n = ByteBuffer.wrap(plain).int
        return Opened(String(plain, 4, n), plain.copyOfRange(4 + n, plain.size), k, p)
    }

    // Actions -----------------------------------------------------------------------------------

    /** Locks the files: each is sealed, written, read back with the password, and only then removed
     *  from the gallery (other apps' files after Android's yes). The number locked. */
    suspend fun lock(ctx: Context, items: List<Saved>, password: String, progress: (Int) -> Unit): Int {
        val salt = rand(16)
        val pub = Recovery.public(ctx)
        val written = mutableMapOf<Long, File>()
        items.forEachIndexed { i, item ->
            progress(i + 1)
            if (item.bytes > MAX) return@forEachIndexed
            runCatching {
                val data = ctx.contentResolver.openInputStream(item.uri)!!.use { it.readBytes() }
                val out = File(dir(ctx), "${rand(6).toHex()}.lodilock")
                out.writeBytes(seal(data, item.name, password, salt, pub))
                check(unseal(out.readBytes(), listOf(password))?.bytes?.size == data.size) { "check" }
                remember(ctx, out, item.path)
                written[item.id] = out
            }.onFailure { android.util.Log.w("LodiLocks", "${item.name}: $it") }
        }
        val failed = Library.each(items.filter { it.id in written }) { Library.delete(ctx, it) }
        // A file Android wouldn't let go of keeps its original; its locked copy goes.
        if (failed > 0) {
            val gone = Saver.gallery(ctx).map { it.id }.toSet() + Saver.media(ctx).map { it.id }.toSet()
            for (id in written.keys.filter { it in gone }) written.remove(id)?.let { forget(ctx, it) }
        }
        if (password !in sessions) sessions += password
        changes++
        return written.size
    }

    private fun remember(ctx: Context, f: File, folder: String) = synchronized(this) {
        val idx = readIndex(ctx)
        idx.put(f.nameWithoutExtension, JSONObject().put("folder", folder).put("at", System.currentTimeMillis()))
        index(ctx).writeText(idx.toString())
    }

    private fun forget(ctx: Context, f: File) = synchronized(this) {
        f.delete()
        val idx = readIndex(ctx)
        idx.remove(f.nameWithoutExtension)
        index(ctx).writeText(idx.toString())
    }

    /** Opens a locked file with this session's passwords or recovery key, in memory. */
    fun open(e: Entry): Opened? {
        opened[e.id]?.let { return it }
        if (!unlocked()) return null
        val o = runCatching { unseal(e.file.readBytes(), sessions.toList(), recoveryKey) }.getOrNull() ?: return null
        if (o.kind == "photo" || o.kind == "gif") o.thumb = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(o.bytes, 0, o.bytes.size, it) }
            var step = 1
            while (bounds.outWidth / (step * 2) >= 480) step *= 2
            BitmapFactory.decodeByteArray(o.bytes, 0, o.bytes.size, BitmapFactory.Options().apply { inSampleSize = step })?.asImageBitmap()
        }.getOrNull()
        opened[e.id] = o
        return o
    }

    /** Tries a password on a file: kept for this session when it opens it. */
    fun tryPassword(e: Entry, password: String): Boolean {
        val ok = runCatching { unseal(e.file.readBytes(), listOf(password)) }.getOrNull() != null
        if (ok && password !in sessions) { sessions += password; changes++ }
        return ok
    }

    /** Tries the recovery key on a file: kept for this session when it opens it. */
    fun tryRecovery(e: Entry, key: PrivateKey): Boolean {
        val ok = runCatching { unseal(e.file.readBytes(), emptyList(), key) }.getOrNull() != null
        if (ok) { recoveryKey = key; changes++ }
        return ok
    }

    /** Forgets this session's passwords and recovery key, and anything unlocked. */
    fun lockAgain(ctx: Context) {
        sessions.clear()
        recoveryKey = null
        synchronized(keys) { keys.clear() }
        opened.clear()
        File(ctx.cacheDir, "unlocked").deleteRecursively()
        changes++
    }

    /** A video unlocked to play: a file in LodiSave's own cache, gone at Lock again. */
    fun playable(ctx: Context, e: Entry): File? {
        val o = open(e) ?: return null
        val f = File(File(ctx.cacheDir, "unlocked").apply { mkdirs() }, "${e.id}.${o.name.substringAfterLast('.', "mp4")}")
        if (!f.exists()) f.writeBytes(o.bytes)
        return f
    }

    /** Takes the password off: the file goes back to the gallery, in the folder it came from. */
    fun removePassword(ctx: Context, e: Entry): Boolean {
        val o = open(e) ?: return false
        return runCatching {
            Saver.publish(ctx, o.name, o.mime, folder = e.folder) { it.write(o.bytes) }
            opened.remove(e.id)
            forget(ctx, e.file)
            Library.changed()
            changes++
        }.isSuccess
    }

    /** A new password: only the sealed key changes, and the recovery key still opens it. */
    fun newPassword(ctx: Context, e: Entry, password: String): Boolean {
        val o = open(e) ?: return false
        val salt = rand(16)
        val ivP = rand(12)
        val p = o.parts
        val out = pack(Parts(salt, ivP, aes(Cipher.ENCRYPT_MODE, keyFor(password, salt), ivP, o.key), p.wR, p.ivD, p.data))
        val tmp = File(e.file.path + ".part")
        tmp.writeBytes(out)
        if (unseal(tmp.readBytes(), listOf(password)) == null) { tmp.delete(); return false }
        tmp.renameTo(e.file)
        opened.remove(e.id)
        if (password !in sessions) sessions += password
        changes++
        return true
    }

    /** Deletes a locked file for good. */
    fun delete(ctx: Context, e: Entry) {
        opened.remove(e.id)
        forget(ctx, e.file)
        changes++
    }
}

/**
 * Password recovery through an authenticator app (Google Authenticator or any other TOTP app). A
 * six-digit code can't hold a key, so it guards one: the recovery private key and the authenticator's
 * secret are sealed by a key in Android's Keystore, and the private key is handed over only for a
 * correct, unused code. It keeps locked files closed to anyone without the phone's authenticator who
 * opens LodiSave, and works only on this phone.
 */
object Recovery {
    private const val ALIAS = "lodisave-recovery"
    private const val STEP = 30L
    private const val TRIES = 5
    private const val WAIT_MS = 60_000L
    private var pending: ByteArray? = null
    private var used = 0L
    private var wrong = 0
    private var waitUntil = 0L

    class Failure(val code: String) : Exception(code)

    private fun file(ctx: Context) = File(ctx.filesDir, "recovery.json")
    private fun saved(ctx: Context) = runCatching { JSONObject(file(ctx).readText()) }.getOrNull()
    fun isSet(ctx: Context) = file(ctx).exists()

    fun public(ctx: Context): PublicKey? = saved(ctx)?.let {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(it.getString("public"), Base64.NO_WRAP)))
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun seal(b: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(b), Base64.NO_WRAP)
    }

    private fun open(s: String): ByteArray {
        val b = Base64.decode(s, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, b, 0, 12)) }.doFinal(b, 12, b.size - 12)
    }

    private fun step() = System.currentTimeMillis() / 1000 / STEP

    fun code(secret: ByteArray, step: Long): Int {
        val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h[19].toInt() and 0xf
        return (ByteBuffer.wrap(h, o, 4).int and 0x7fffffff) % 1_000_000
    }

    fun matches(secret: ByteArray, typed: String, step: Long): Long? {
        val digits = typed.filter { !it.isWhitespace() }
        if (digits.length != 6 || !digits.all { it.isDigit() }) return null
        val n = digits.toInt()
        return listOf(step - 1, step, step + 1).firstOrNull { code(secret, it) == n }
    }

    fun base32(b: ByteArray): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buf = 0
        var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { out.append(a[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(a[(buf shl (5 - bits)) and 31])
        return out.toString()
    }

    /** A new secret: the setup key to type, and the otpauth link authenticator apps open. */
    fun begin(): Pair<String, String> {
        val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
        pending = secret
        val b32 = base32(secret)
        return b32.chunked(4).joinToString(" ") to "otpauth://totp/LodiSave:Locked%20files?secret=$b32&issuer=LodiSave&algorithm=SHA1&digits=6&period=$STEP"
    }

    /** Turns recovery on once the authenticator shows a matching code. */
    fun finish(ctx: Context, typed: String) {
        val secret = pending ?: throw Failure("expired")
        val s = matches(secret, typed, step()) ?: throw Failure("code")
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val json = JSONObject()
            .put("public", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .put("private", seal(pair.private.encoded))
            .put("secret", seal(secret))
        val tmp = File(file(ctx).path + ".part")
        tmp.writeText(json.toString())
        tmp.renameTo(file(ctx))
        pending = null
        used = s
    }

    private fun check(ctx: Context, typed: String) {
        if (System.currentTimeMillis() < waitUntil) throw Failure("wait")
        val secret = open((saved(ctx) ?: throw Failure("off")).getString("secret"))
        val s = matches(secret, typed, step())
        if (s == null) {
            if (++wrong >= TRIES) { waitUntil = System.currentTimeMillis() + WAIT_MS; wrong = 0 }
            throw Failure("code")
        }
        if (s <= used) throw Failure("used")
        used = s
        wrong = 0
    }

    /** The recovery private key, for a correct code. */
    fun unlock(ctx: Context, typed: String): PrivateKey {
        check(ctx, typed)
        val raw = open(saved(ctx)!!.getString("private"))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(raw))
    }

    /** Turns recovery off, for a correct code. Files locked so far keep only their passwords. */
    fun turnOff(ctx: Context, typed: String) {
        check(ctx, typed)
        file(ctx).delete()
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
