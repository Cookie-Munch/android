package net.cookiemunch

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** The three toggleable consent categories. `necessary` is always granted. */
data class Choices(
    val preferences: Boolean = false,
    val statistics: Boolean = false,
    val marketing: Boolean = false,
)

/** Consent categories usable with [CookieMunchConsent.gate] and [CookieMunchConsent.set]. */
enum class Category { NECESSARY, PREFERENCES, STATISTICS, MARKETING }

/**
 * Consent state — mirrors the web/iOS/RN SDKs so records are uniform across platforms.
 * `necessary` is always true; the visitor can only ever toggle the other three.
 */
data class ConsentState(
    val necessary: Boolean = true,
    val preferences: Boolean = false,
    val statistics: Boolean = false,
    val marketing: Boolean = false,
    val method: String = "implied",
    val stamp: String = UUID.randomUUID().toString(),
    val ver: Int = 1,
    val utc: Long = System.currentTimeMillis(),
    val region: String = "unknown",
) {
    /** True once the visitor has made an explicit choice (accept / decline / custom). */
    val hasResponse: Boolean get() = method == "explicit"

    /** True if any non-necessary category is granted. */
    val consented: Boolean get() = preferences || statistics || marketing

    val choices: Choices get() = Choices(preferences, statistics, marketing)

    fun granted(category: Category): Boolean = when (category) {
        Category.NECESSARY -> true
        Category.PREFERENCES -> preferences
        Category.STATISTICS -> statistics
        Category.MARKETING -> marketing
    }
}

/**
 * Cookie Munch Android consent client — the React-Native client's design, in Kotlin.
 *
 * Persists the consent record to a pluggable [ConsentStorage] and syncs it to the
 * self-hosted Cookie Munch REST API (`POST /api/v1/consent`). API failures are
 * swallowed: a consent decision is NEVER lost just because the device is offline.
 *
 * All state-changing calls are `suspend` and run the network sync on [ioDispatcher];
 * they never throw for a failed POST. The current state is also exposed as a [state]
 * `StateFlow` for Compose/coroutine collectors and via [onChange] callbacks.
 *
 * Construct directly with an [InMemoryConsentStorage] (default) or a
 * [EncryptedPrefsConsentStorage], or use [CookieMunchConsent.secure] to get an
 * EncryptedSharedPreferences-backed client that has already loaded persisted state.
 */
class CookieMunchConsent(
    private val cbid: String,
    apiUrl: String,
    private val storage: ConsentStorage = InMemoryConsentStorage(),
    private val transport: ConsentTransport = HttpUrlConnectionTransport(),
    private val region: String = "unknown",
    private val storageKey: String = DEFAULT_KEY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val stamp: () -> String = { UUID.randomUUID().toString() },
) {
    private val apiBase: String = apiUrl.trimEnd('/')
    private val listeners = CopyOnWriteArraySet<(ConsentState) -> Unit>()

    private val _state = MutableStateFlow(
        ConsentState(method = "implied", stamp = stamp(), utc = now(), region = region),
    )

    /** Reactive consent state. Collect it from Compose or any coroutine. */
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    /** Synchronous snapshot of the current state. */
    fun getState(): ConsentState = _state.value

    /** True once the visitor has made an explicit choice. */
    fun hasResponse(): Boolean = _state.value.hasResponse

    /** True if [category] is currently granted (`NECESSARY` is always granted). */
    fun granted(category: Category): Boolean = _state.value.granted(category)

    /**
     * Registers a callback fired on every state change. Returns an unsubscribe function.
     * Listener exceptions are isolated and never break the consent flow.
     */
    fun onChange(listener: (ConsentState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * Loads any persisted consent record from [storage] into [state] and returns it.
     * A corrupt or absent record leaves the implied default in place. Safe to call at
     * startup on the main thread — [storage] reads are expected to be cheap/synchronous.
     */
    fun load(): ConsentState {
        val raw = storage.read()
        if (raw != null) {
            val parsed = parse(raw)
            if (parsed != null) emit(parsed)
        }
        return _state.value
    }

    /** Grants every category (explicit). */
    suspend fun accept(): ConsentState = commit(Choices(preferences = true, statistics = true, marketing = true))

    /** Denies every non-necessary category (explicit). */
    suspend fun decline(): ConsentState = commit(Choices(preferences = false, statistics = false, marketing = false))

    /** Sets all three categories at once (explicit). */
    suspend fun submitCustom(choices: Choices): ConsentState = commit(choices)

    /** Toggles a single category, keeping the others, and records an explicit decision. */
    suspend fun set(category: Category, granted: Boolean): ConsentState {
        val c = _state.value.choices
        val next = when (category) {
            Category.NECESSARY -> c // necessary can never be revoked
            Category.PREFERENCES -> c.copy(preferences = granted)
            Category.STATISTICS -> c.copy(statistics = granted)
            Category.MARKETING -> c.copy(marketing = granted)
        }
        return commit(next)
    }

    /**
     * Runs [block] only if [category] is granted, returning its result (or null).
     * Use to guard code that must not run before consent, e.g.:
     * `consent.gate(Category.STATISTICS) { analytics.start() }`.
     */
    inline fun <T> gate(category: Category, block: () -> T): T? =
        if (granted(category)) block() else null

    private suspend fun commit(choices: Choices): ConsentState {
        val s = _state.value.copy(
            necessary = true,
            preferences = choices.preferences,
            statistics = choices.statistics,
            marketing = choices.marketing,
            method = "explicit",
            utc = now(),
        )
        storage.write(toJson(s)) // local persist first — this is the durable record
        emit(s)
        sync(s) // best-effort; offline-safe
        return s
    }

    private fun emit(s: ConsentState) {
        _state.value = s
        for (listener in listeners) {
            try {
                listener(s)
            } catch (_: Throwable) {
                // A listener's failure must never break the consent flow.
            }
        }
    }

    private suspend fun sync(s: ConsentState) {
        try {
            withContext(ioDispatcher) {
                transport.post("$apiBase/api/v1/consent", s.region, requestBody(s))
            }
        } catch (_: Throwable) {
            // Offline / server error — the local persist already captured the decision.
        }
    }

    private fun toJson(s: ConsentState): String = JSONObject().apply {
        put("necessary", true)
        put("preferences", s.preferences)
        put("statistics", s.statistics)
        put("marketing", s.marketing)
        put("method", s.method)
        put("stamp", s.stamp)
        put("ver", s.ver)
        put("utc", s.utc)
        put("region", s.region)
    }.toString()

    private fun requestBody(s: ConsentState): String = JSONObject().apply {
        put("cbid", cbid)
        put("stamp", s.stamp)
        put(
            "choices",
            JSONObject().apply {
                put("preferences", s.preferences)
                put("statistics", s.statistics)
                put("marketing", s.marketing)
            },
        )
        put("method", s.method)
        put("ver", s.ver)
        put("utc", s.utc)
        put("url", "app://$cbid")
    }.toString()

    private fun parse(raw: String): ConsentState? = try {
        val o = JSONObject(raw)
        ConsentState(
            necessary = true,
            preferences = o.optBoolean("preferences", false),
            statistics = o.optBoolean("statistics", false),
            marketing = o.optBoolean("marketing", false),
            method = if (o.optString("method", "implied") == "explicit") "explicit" else "implied",
            stamp = o.optString("stamp", _state.value.stamp),
            ver = o.optInt("ver", 1),
            utc = if (o.has("utc")) o.optLong("utc", now()) else now(),
            region = o.optString("region", region),
        )
    } catch (_: Throwable) {
        null // corrupt record — keep the implied default
    }

    companion object {
        const val DEFAULT_KEY = "CookieMunch"

        /**
         * Builds a client backed by EncryptedSharedPreferences and immediately loads
         * any persisted record. Call once at app startup.
         */
        fun secure(
            context: Context,
            cbid: String,
            apiUrl: String,
            region: String = "unknown",
            storageKey: String = DEFAULT_KEY,
        ): CookieMunchConsent = CookieMunchConsent(
            cbid = cbid,
            apiUrl = apiUrl,
            storage = EncryptedPrefsConsentStorage(context, storageKey),
            region = region,
            storageKey = storageKey,
        ).also { it.load() }
    }
}
