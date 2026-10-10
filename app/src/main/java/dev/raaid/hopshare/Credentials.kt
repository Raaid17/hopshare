package dev.raaid.hopshare

import android.content.Context
import java.security.SecureRandom

/** Persisted group name + passphrase, so client devices can reconnect without re-typing. */
data class Credentials(val ssid: String, val passphrase: String) {
    companion object {
        private const val PREFS = "hopshare"
        private const val KEY_SUFFIX = "ssid_suffix"
        private const val KEY_PASS = "passphrase"
        // No 0/O/1/l/I to keep it easy to type on another device.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        fun load(context: Context): Credentials {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var suffix = prefs.getString(KEY_SUFFIX, null)
            var pass = prefs.getString(KEY_PASS, null)
            if (suffix == null || pass == null) {
                suffix = randomString(4)
                pass = randomString(10)
                prefs.edit().putString(KEY_SUFFIX, suffix).putString(KEY_PASS, pass).apply()
            }
            // Wi-Fi Direct requires the "DIRECT-xy" prefix.
            return Credentials("DIRECT-hs-HopShare-$suffix", pass)
        }

        fun regenerate(context: Context): Credentials {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_SUFFIX).remove(KEY_PASS).commit()
            return load(context)
        }

        private fun randomString(len: Int) =
            String(CharArray(len) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }
}
