package net.cookiemunch

import org.junit.Assert.assertEquals
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

    /**
     * The statement with the decision replaced by V, pinned to the web embed's
     * `webViewJavaScript` so every platform writes the engine's attributes (CKM-S-01).
     */
    private fun skeleton(js: String) = js.replace(Regex("""\+'[^']+'\+"""), "+'V'+")

    @Test
    fun writesSecureOnHttpsLikeTheEngine() {
        assertEquals(
            "document.cookie='CookieMunch='+'V'+';path=/;max-age=31536000;SameSite=Lax'+(location.protocol==='https:'?';Secure':'');",
            skeleton(WebViewBridge.javaScript(state)),
        )
    }

    @Test
    fun withCookieDomainWritesItAndClearsTheHostOnlyCopy() {
        assertEquals(
            """(function(d,h){d=d.trim().replace(/^\.+/,'').toLowerCase();var m=d.indexOf('.')>0&&' co.uk org.uk me.uk gov.uk ac.uk com.au net.au org.au co.jp or.jp ne.jp co.nz co.za com.br com.mx co.in com.cn com.sg '.indexOf(' '+d+' ')<0&&(h===d||h.slice(-d.length-1)==='.'+d)?';domain=.'+d:'',s=location.protocol==='https:'?';Secure':'';document.cookie='CookieMunch='+'V'+';path=/;max-age=60;SameSite=Lax'+s+m;if(m)document.cookie='CookieMunch=;path=/;max-age=0;SameSite=Lax';document.cookie='CookieConsent='+'V'+';path=/;max-age=60;SameSite=Lax'+s+m;if(m)document.cookie='CookieConsent=;path=/;max-age=0;SameSite=Lax';})('example.com',location.hostname.toLowerCase());""",
            skeleton(WebViewBridge.javaScript(state, maxAge = 60, alsoLegacyCookie = true, domain = "example.com")),
        )
    }

    @Test
    fun escapesTheDomain() {
        val js = WebViewBridge.javaScript(state, domain = "x.com');alert(1);//")
        assertTrue(js.contains("""('x.com\');alert(1);//',location.hostname"""))
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
