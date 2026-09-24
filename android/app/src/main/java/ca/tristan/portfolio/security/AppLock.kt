package ca.tristan.portfolio.security

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App lock: either the phone's biometric/PIN (MainActivity is a
 * FragmentActivity for androidx.biometric), or a separate 6-digit app PIN
 * stored as PBKDF2-SHA256 + a per-install random salt, with escalating
 * lockout after repeated failures. Screenshots/app-switcher preview are
 * blocked with FLAG_SECURE while the lock is enabled, with an
 * "Allow screenshots" escape hatch in Settings.
 */
object AppLock {
    private const val PREFS = "app_lock_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode" // "biometric" or "pin"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    enum class Mode { BIOMETRIC, PIN }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun mode(context: Context): Mode =
        if (prefs(context).getString(KEY_MODE, "biometric") == "pin") Mode.PIN else Mode.BIOMETRIC

    fun allowScreenshots(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_SCREENSHOTS, false)

    fun setAllowScreenshots(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_SCREENSHOTS, allow).apply()
    }

    fun enableBiometric(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).putString(KEY_MODE, "biometric").apply()
    }

    fun enablePin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_MODE, "pin")
            .putString(KEY_PIN_SALT, salt.joinToString(",") { it.toString() })
            .putString(KEY_PIN_HASH, hash.joinToString(",") { it.toString() })
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0)
            .apply()
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    fun lockoutRemainingMillis(context: Context): Long {
        val until = prefs(context).getLong(KEY_LOCKOUT_UNTIL, 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun verifyPin(context: Context, attempt: String): Boolean {
        if (lockoutRemainingMillis(context) > 0) return false
        val p = prefs(context)
        val saltStr = p.getString(KEY_PIN_SALT, null) ?: return false
        val hashStr = p.getString(KEY_PIN_HASH, null) ?: return false
        val salt = saltStr.split(",").map { it.toByte() }.toByteArray()
        val expected = hashStr.split(",").map { it.toByte() }.toByteArray()
        val actual = hashPin(attempt, salt)
        val ok = actual.contentEquals(expected)
        if (ok) {
            p.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LOCKOUT_UNTIL, 0).apply()
        } else {
            val fails = p.getInt(KEY_FAIL_COUNT, 0) + 1
            // Escalating lockout: 3 fails -> 30s, 5 -> 2min, 8+ -> 10min
            val lockoutMs = when {
                fails >= 8 -> 10 * 60_000L
                fails >= 5 -> 2 * 60_000L
                fails >= 3 -> 30_000L
                else -> 0L
            }
            p.edit()
                .putInt(KEY_FAIL_COUNT, fails)
                .putLong(KEY_LOCKOUT_UNTIL, if (lockoutMs > 0) System.currentTimeMillis() + lockoutMs else 0)
                .apply()
        }
        return ok
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
