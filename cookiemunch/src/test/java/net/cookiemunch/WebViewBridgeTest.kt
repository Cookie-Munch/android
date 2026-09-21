package net.cookiemunch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge must produce what the web embed reads. If this drifts from
 * `packages/core/src/webview-bridge.ts`, a hybrid app silently asks twice.
 */
class WebViewBridgeTest {
    private val state = ConsentState(
        preferences = true,
        statistics = false,
        marketing = true,
        method = "explicit",
        stamp = "abc-123",
        ver = 1,
        utc = 1_700_000_000_000L,
        region = "de",
    )

    @Test
    fun writesTheCookieTheEmbedReads() {
        val js = WebViewBridge.javaScript(state)
        assertTrue(js.contains("document.cookie"))
        assertTrue(js.contains("CookieMunch="))
        assertTrue(js.contains("path=/"))
    }

    @Test
    fun isASingleLine() {
        assertFalse(WebViewBridge.javaScript(state).contains("\n"))
    }

    @Test
    fun carriesTheRealDecision() {
        val decoded = java.net.URLDecoder.decode(WebViewBridge.serialize(state), "UTF-8")
        assertTrue(decoded.contains("\"marketing\":true"))
        assertTrue(decoded.contains("\"statistics\":false"))
        assertTrue(decoded.contains("\"region\":\"de\""))
    }

    @Test
    fun honoursMaxAge() {
        assertTrue(WebViewBridge.javaScript(state, maxAge = 60).contains("max-age=60"))
    }

    @Test
    fun writesTheLegacyCookieForMigratingApps() {
        val js = WebViewBridge.javaScript(state, alsoLegacyCookie = true)
        assertTrue(js.contains("CookieConsent="))
        assertTrue(js.contains("CookieMunch="))
    }

    /** The value goes inside a single-quoted literal; a quote would break out of it. */
    @Test
    fun escapesValuesThatCouldBreakTheStatement() {
        val tricky = state.copy(stamp = "a'\";alert(1)//")
        assertFalse(WebViewBridge.javaScript(tricky).contains("';alert(1)"))
    }

    @Test
    fun serializedValueIsCookieSafe() {
        val v = WebViewBridge.serialize(state)
        // A raw '+' in a cookie value decodes as a space on the other side.
        assertFalse(v.contains("+"))
        assertFalse(v.contains(" "))
        assertFalse(v.contains(";"))
    }

    @Test
    fun queryStringIsPrefixed() {
        assertTrue(WebViewBridge.queryString(state).startsWith("cm_consent="))
    }
}
