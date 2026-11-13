package donat.teledrive

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File

/** Telegram API credentials, encrypted with Windows DPAPI (current user). Never log these. */
object Secrets {
    private val file = File(appDataDir, "credentials.bin")

    fun save(apiId: Int, apiHash: String) = file.writeBytes(Crypt32Util.cryptProtectData("$apiId\n$apiHash".encodeToByteArray()))

    fun load(): Pair<Int, String>? = runCatching {
        val (id, hash) = Crypt32Util.cryptUnprotectData(file.readBytes()).decodeToString().split('\n')
        id.toInt() to hash
    }.getOrNull()

    fun clear() {
        file.delete()
    }
}

/** Optional app lock. Only a salted PBKDF2 hash of the PIN is stored, never the PIN. */
object AppLock {
    fun isSet() = Prefs["pinHash"] != null

    fun set(pin: String?) {
        Prefs.remove("pinFails"); Prefs.remove("pinWait") // a new / no PIN starts with a clean guessing brake
        if (pin == null) { Prefs.remove("pinHash"); Prefs.remove("pinSalt"); return }
        val (salt, hash) = make(pin)
        Prefs["pinSalt"] = salt
        Prefs["pinHash"] = hash
    }

    /** Right PIN also resets the guessing brake. */
    fun check(pin: String): Boolean =
        matches(pin, Prefs["pinSalt"] ?: return true, Prefs["pinHash"] ?: return true).also { if (it) { Prefs.remove("pinFails"); Prefs.remove("pinWait") } }

    fun failures() = Prefs["pinFails"]?.toIntOrNull() ?: 0

    /** Unlocking is refused until this time (0 = not blocked). */
    fun blockedUntil() = Prefs["pinWait"]?.toLongOrNull() ?: 0L

    /**
     * A wrong PIN. Persisted, so restarting the app doesn't reset the brake: 30 s after every 5th miss, doubling each
     * round (capped at 16 min). Returns [blockedUntil].
     */
    fun failed(now: Long = System.currentTimeMillis()): Long {
        val n = failures() + 1
        Prefs["pinFails"] = "$n"
        if (n % 5 == 0) Prefs["pinWait"] = "${now + 30_000L * (1L shl minOf(n / 5 - 1, 5))}"
        return blockedUntil()
    }

    /** (salt, hash) as Base64 for a new PIN. */
    internal fun make(pin: String): Pair<String, String> {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return b64(salt) to b64(hash(pin, salt))
    }

    internal fun matches(pin: String, salt: String, hash: String): Boolean {
        val d = java.util.Base64.getDecoder()
        return java.security.MessageDigest.isEqual(hash(pin, d.decode(salt)), d.decode(hash))
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
}