package net.cookiemunch

import org.json.JSONObject
import java.util.Locale

/**
 * The banner's words in the visitor's language, resolved by the server.
 *
 * Forty languages will not fit in an app, and five native SDKs each shipping their own
 * catalogue is five chances to disagree about what one banner says. So the platform resolves
 * the copy the same way it resolves the regulatory regime — once, server-side — and this is
 * that answer. It is null until [CookieMunchConsent.refreshRegulation] has run; the banners
 * fall back to their English strings, so an app that has never reached the network still asks.
 */
data class LocalizedCopy(
    /** The language actually used, which may be the site's default rather than the one asked for. */
    val language: String,
    /** Written right to left. A banner mirrors its layout, not merely its text. */
    val rtl: Boolean,
    val title: String?,
    val body: String?,
    val acceptAll: String?,
    val rejectAll: String?,
    val save: String?,
    val customize: String?,
    /** Keyed by category id: necessary, preferences, statistics, marketing. */
    val categories: Map<String, CategoryText>,
    /** The label on the affordance that reopens the prompt. */
    val reopen: String?,
) {
    data class CategoryText(val label: String, val description: String)

    companion object {
        /** The language tag to ask the server for: what this device actually reads in. */
        fun preferredLanguage(): String = Locale.getDefault().toLanguageTag()

        /** Read the `copy` block of a `/config/:cbid` response, or null when absent. */
        fun fromConfigJson(json: String): LocalizedCopy? {
            return try {
                val copy = JSONObject(json).optJSONObject("copy") ?: return null
                val banner = copy.optJSONObject("banner")
                val categories = mutableMapOf<String, CategoryText>()
                copy.optJSONObject("categories")?.let { cats ->
                    for (key in cats.keys()) {
                        val entry = cats.optJSONObject(key) ?: continue
                        categories[key] = CategoryText(
                            label = entry.optString("label", ""),
                            description = entry.optString("description", ""),
                        )
                    }
                }
                LocalizedCopy(
                    language = copy.optString("language", "en"),
                    rtl = copy.optBoolean("rtl", false),
                    title = banner?.optStringOrNull("title"),
                    body = banner?.optStringOrNull("body"),
                    acceptAll = banner?.optStringOrNull("acceptAll"),
                    rejectAll = banner?.optStringOrNull("rejectAll"),
                    save = banner?.optStringOrNull("save"),
                    customize = banner?.optStringOrNull("customize"),
                    categories = categories,
                    reopen = copy.optStringOrNull("reopen"),
                )
            } catch (_: Exception) {
                // A malformed body is no copy, never a crash: the banner falls back to English.
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
    }
}
