package net.cookiemunch

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Pluggable persistence for the consent record, stored as a single JSON string.
 * Implementations must be safe to read on the main thread (cheap key/value access).
 */
interface ConsentStorage {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/**
 * Default, process-lifetime store. Nothing is written to disk. Useful for tests and
 * for hosts that persist consent elsewhere.
 */
class InMemoryConsentStorage(initial: String? = null) : ConsentStorage {
    @Volatile
    private var value: String? = initial

    override fun read(): String? = value
    override fun write(value: String) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}

/**
 * Persists the consent record in EncryptedSharedPreferences (androidx.security-crypto),
 * so the record is encrypted at rest with a Keystore-backed master key. Requires
 * `androidx.security:security-crypto` (see the module README for the coordinate).
 *
 * The preferences handle is created lazily so constructing the store never touches the
 * Keystore off the intended thread.
 */
class EncryptedPrefsConsentStorage(
    context: Context,
    private val key: String = CookieMunchConsent.DEFAULT_KEY,
    prefsFileName: String = "cookiemunch_secure",
) : ConsentStorage {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            prefsFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): String? = prefs.getString(key, null)

    override fun write(value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun clear() {
        prefs.edit().remove(key).apply()
    }
}
