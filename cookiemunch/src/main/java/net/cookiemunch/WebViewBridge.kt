package net.cookiemunch

import android.net.Uri
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Carrying a decision made in the app into a [android.webkit.WebView].
 *
 * A hybrid app collects consent natively, then opens web content — a help centre, a
 * checkout, an article. That page runs the web embed, finds no stored decision, and asks
 * again. The person has now been asked twice for the same thing, and the answer the web
 * side keeps is the second one.
 *
 * Two ways across, matching `@cookiemunch/core`'s `webview-bridge.ts`:
 *
 * ```kotlin
 * webView.webViewClient = object : WebViewClient() {
 *     override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
 *         view.evaluateJavascript(WebViewBridge.javaScript(state), null)
 *     }
 * }
 * ```
 *
 * Inject at page start: the embed reads storage as it boots, so anything that runs after
 * the document is ready has already lost the race.
 */
object WebViewBridge {
    /** Query parameter carrying a serialised decision. */
    const val QUERY_PARAMETER = "cm_consent"

    /** Cookie the web embed reads. */
    private const val COOKIE_NAME = "CookieMunch"
    /** Cookiebot's name, for apps migrating from it. */
    private const val LEGACY_COOKIE_NAME = "CookieConsent"
    /** Twelve months, matching the embed's own default. */
    const val DEFAULT_MAX_AGE = 60 * 60 * 24 * 365

    /** The serialised, URL-encoded value the web side stores. */
    internal fun serialize(state: ConsentState): String {
        val json = JSONObject().apply {
            put("necessary", true)
            put("preferences", state.preferences)
            put("statistics", state.statistics)
            put("marketing", state.marketing)
            put("method", state.method)
            put("stamp", state.stamp)
            put("ver", state.ver)
            put("utc", state.utc)
            put("region", state.region)
        }.toString()
        // Matches encodeURIComponent: URLEncoder is form-encoding, so correct the
        // three places the two disagree or a cookie value would be mangled.
        return URLEncoder.encode(json, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("*", "%2A")
    }

    /** Escape for embedding inside a single-quoted JavaScript string literal. */
    internal fun jsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        // `</script` would close an inline tag if this were ever inlined.
        .replace("<", "\\x3c")

    /** Two-label public suffixes the web embed refuses as a cookie domain (`cookie-domain.ts`). */
    internal const val TWO_LABEL_SUFFIXES = " co.uk org.uk me.uk gov.uk ac.uk com.au net.au org.au co.jp or.jp ne.jp co.nz co.za com.br com.mx co.in com.cn com.sg "

    /**
     * A single JavaScript statement that seeds a web view with this decision.
     *
     * One line and expression-only on purpose: [android.webkit.WebView.evaluateJavascript]
     * takes a string, and a multi-line program is a common source of silent failures.
     *
     * It writes the cookie with the web embed's own attributes: `Secure` on https, and —
     * pass the site's `cookie.domain` as [domain] — on that domain when the page's host
     * belongs to it, clearing the host-only copy. A host-only copy next to the embed's
     * domain-wide one would shadow the visitor's later decisions.
     */
    @JvmStatic
    @JvmOverloads
    fun javaScript(
        state: ConsentState,
        maxAge: Int = DEFAULT_MAX_AGE,
        alsoLegacyCookie: Boolean = false,
        domain: String? = null,
    ): String {
        val value = jsString(serialize(state))
        val names = if (alsoLegacyCookie) listOf(COOKIE_NAME, LEGACY_COOKIE_NAME) else listOf(COOKIE_NAME)
        val attrs = ";path=/;max-age=$maxAge;SameSite=Lax"
        if (domain.isNullOrEmpty()) {
            return names.joinToString("") {
                "document.cookie='$it='+'$value'+'$attrs'+(location.protocol==='https:'?';Secure':'');"
            }
        }
        val writes = names.joinToString("") {
            "document.cookie='$it='+'$value'+'$attrs'+s+m;if(m)document.cookie='$it=;path=/;max-age=0;SameSite=Lax';"
        }
        return "(function(d,h){d=d.trim().replace(/^\\.+/,'').toLowerCase();" +
            "var m=d.indexOf('.')>0&&'$TWO_LABEL_SUFFIXES'.indexOf(' '+d+' ')<0&&(h===d||h.slice(-d.length-1)==='.'+d)?';domain=.'+d:''," +
            "s=location.protocol==='https:'?';Secure':'';$writes})('${jsString(domain)}',location.hostname.toLowerCase());"
    }

    /**
     * A URL parameter carrying this decision, for when script evaluation is unavailable.
     * Append it to the URL you are about to load. The page ignores it unless the site has
     * `webview.urlConsent` enabled (off by default); prefer [javaScript].
     */
    @JvmStatic
    fun queryString(state: ConsentState): String =
        "$QUERY_PARAMETER=" + URLEncoder.encode(serialize(state), "UTF-8").replace("+", "%20")

    /** Append the decision to a URL, preserving any query it already has. */
    @JvmStatic
    fun url(url: String, state: ConsentState): String {
        val parsed = Uri.parse(url)
        val builder = parsed.buildUpon().clearQuery()
        // Replace rather than duplicate, so re-seeding the same URL stays idempotent.
        for (name in parsed.queryParameterNames) {
            if (name == QUERY_PARAMETER) continue
            for (v in parsed.getQueryParameters(name)) builder.appendQueryParameter(name, v)
        }
        builder.appendQueryParameter(QUERY_PARAMETER, serialize(state))
        return builder.build().toString()
    }
}
