package dev.raaid.hopshare

import android.content.Context
import java.security.SecureRandom

/**
 * Network name + passphrase for the Wi-Fi Direct group, persisted so clients can reconnect.
 *
 * Wi-Fi Direct requires the SSID to start with "DIRECT-xy"; we use "DIRECT-hs-" and let the
 * user choose the rest.
 */
data class Credentials(val name: String, val passphrase: String) {
    val ssid get() = PREFIX + name

    companion object {
        const val PREFIX = "DIRECT-hs-"
        /** SSIDs are max 32 bytes; names are ASCII-only, so 1 char = 1 byte. */
        const val MAX_NAME = 32 - PREFIX.length // 22
        const val MIN_PASS = 8
        const val MAX_PASS = 63

        private const val PREFS = "hopshare"
        private const val KEY_NAME = "network_name"
        private const val KEY_PASS = "passphrase"
        private const val KEY_LEGACY_SUFFIX = "ssid_suffix" // v0.1.0

        private val NAME_REGEX = Regex("^[A-Za-z0-9 ._-]+$")
        // No 0/O/1/l/I so it's easy to type on another device.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        fun load(context: Context): Credentials {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var name = prefs.getString(KEY_NAME, null)
            var pass = prefs.getString(KEY_PASS, null)
            if (name == null) {
                name = prefs.getString(KEY_LEGACY_SUFFIX, null)?.let { "HopShare-$it" }
                    ?: "HopShare-${randomString(4)}"
            }
            if (pass == null) pass = randomString(10)
            prefs.edit().putString(KEY_NAME, name).putString(KEY_PASS, pass).apply()
            return Credentials(name, pass)
        }

        fun save(context: Context, name: String, passphrase: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NAME, name.trim())
                .putString(KEY_PASS, passphrase)
                .apply()
        }

        /** Returns an error message, or null if valid. */
        fun validateName(name: String): String? {
            val n = name.trim()
            return when {
                n.isEmpty() -> "Enter a name"
                n.length > MAX_NAME -> "Max $MAX_NAME characters"
                !NAME_REGEX.matches(n) -> "Letters, numbers, space, . _ - only"
                else -> null
            }
        }

        fun validatePassphrase(pass: String): String? = when {
            pass.length < MIN_PASS -> "At least $MIN_PASS characters"
            pass.length > MAX_PASS -> "Max $MAX_PASS characters"
            pass.any { it.code !in 0x20..0x7E } -> "Use standard keyboard characters only"
            else -> null
        }

        fun randomPassphrase() = randomString(10)

        private fun randomString(len: Int) =
            String(CharArray(len) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }
}
