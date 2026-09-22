package net.cookiemunch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native app's first question is not "what did they consent to" but "do I have to
 * ask at all". Getting that wrong in either direction is expensive: prompt a Texan
 * under GDPR rules and you have tanked your opt-in rate for nothing; skip the prompt
 * for a German and you are non-compliant.
 *
 * The table here is the same one in `packages/geo/src/index.ts` and
 * `native/ios/Sources/CookieMunch/Regulation.swift`.
 */
class RegulationTest {

    @Test
    fun `europe is gdpr opt-in`() {
        val reg = Regulation.resolve("de")
        assertEquals(RegionClass.EU, reg.regionClass)
        assertTrue(reg.gdprApplies)
        assertFalse(reg.ccpaApplies)
        assertEquals(ConsentModel.OPT_IN, reg.model)
        assertFalse(reg.defaultGranted)
        assertEquals(SignalFramework.TCF, reg.framework)
    }

    @Test
    fun `the united kingdom counts as europe`() {
        assertEquals(RegionClass.EU, Regulation.resolve("gb").regionClass)
        assertEquals(RegionClass.EU, Regulation.resolve("uk").regionClass)
    }

    @Test
    fun `california is ccpa opt-out`() {
        val reg = Regulation.resolve("us-ca")
        assertEquals(RegionClass.US, reg.regionClass)
        assertTrue(reg.ccpaApplies)
        assertEquals(ConsentModel.OPT_OUT, reg.model)
        assertTrue(reg.defaultGranted)
        assertEquals(SignalFramework.GPP, reg.framework)
    }

    @Test
    fun `brazil is lgpd`() {
        val reg = Regulation.resolve("br")
        assertTrue(reg.lgpdApplies)
        assertEquals(ConsentModel.OPT_IN, reg.model)
    }

    @Test
    fun `region is case insensitive and tolerant of subdivisions`() {
        assertEquals(RegionClass.EU, Regulation.resolve("FR").regionClass)
        assertEquals(RegionClass.US, Regulation.resolve("US-NY").regionClass)
    }

    /** A geo lookup that fails must never silently downgrade someone's protections. */
    @Test
    fun `unknown region falls back to opt-in`() {
        for (region in listOf(null, "", "zz", "unknown")) {
            val reg = Regulation.resolve(region)
            assertEquals("region $region", ConsentModel.OPT_IN, reg.model)
            assertFalse("region $region", reg.defaultGranted)
        }
    }

    @Test
    fun `gpc forces opt-out only where collection would otherwise proceed`() {
        assertTrue(Regulation.resolve("us-ca", gpc = true).forcedOptOut)
        // Under GDPR nothing fires before consent, so there is nothing for GPC to stop.
        assertFalse(Regulation.resolve("de", gpc = true).forcedOptOut)
    }

    @Test
    fun `do not track forces opt-out and can be disabled`() {
        assertTrue(Regulation.resolve("us-tx", dnt = true).forcedOptOut)
        assertFalse(Regulation.resolve("us-tx", dnt = true, honorDnt = false).forcedOptOut)
    }

    @Test
    fun `consent required is false only when they signalled already`() {
        assertTrue(Regulation.resolve("de").consentRequired)
        assertTrue(Regulation.resolve("us-ca").consentRequired)
        assertFalse(Regulation.resolve("us-ca", gpc = true).consentRequired)
    }

    // --- Parsing the server's answer -----------------------------------------

    @Test
    fun `parses the config payload`() {
        val reg = Regulation.fromConfigJson(
            """
            {"cbid":"x","region":"us-ca","regulation":{
              "region":"us-ca","class":"us",
              "regulations":{"gdprApplies":false,"ccpaApplies":true,"lgpdApplies":false},
              "model":"opt-out","defaultState":"granted","framework":"gpp",
              "forcedOptOut":true,"consentRequired":false}}
            """.trimIndent(),
        )!!
        assertEquals("us-ca", reg.region)
        assertEquals(RegionClass.US, reg.regionClass)
        assertTrue(reg.ccpaApplies)
        assertEquals(ConsentModel.OPT_OUT, reg.model)
        assertTrue(reg.defaultGranted)
        assertTrue(reg.forcedOptOut)
        assertFalse(reg.consentRequired)
    }

    /** A server that learns a new jurisdiction tomorrow must not crash an app built today. */
    @Test
    fun `unrecognised class parses as other rather than throwing`() {
        val reg = Regulation.fromConfigJson(
            """{"regulation":{"region":"jp","class":"apac","regulations":{},
               "model":"opt-in","defaultState":"denied","framework":"none",
               "forcedOptOut":false,"consentRequired":true}}""",
        )!!
        assertEquals(RegionClass.OTHER, reg.regionClass)
        assertEquals(ConsentModel.OPT_IN, reg.model)
    }

    /**
     * An older server omits `consentRequired` entirely. Defaulting a missing boolean to
     * false would suppress every prompt on the planet, so it is derived instead.
     */
    @Test
    fun `a missing consentRequired is derived, not defaulted to false`() {
        val reg = Regulation.fromConfigJson(
            """{"regulation":{"region":"de","class":"eu","regulations":{"gdprApplies":true},
               "model":"opt-in","defaultState":"denied","framework":"tcf","forcedOptOut":false}}""",
        )!!
        assertTrue(reg.consentRequired)
    }

    @Test
    fun `absent or malformed payloads parse to null rather than throwing`() {
        assertNull(Regulation.fromConfigJson("""{"cbid":"x"}"""))
        assertNull(Regulation.fromConfigJson("not json at all"))
        assertNull(Regulation.fromConfigJson(""))
    }
}

/** Serves a canned `/config/:cbid` body and records what was asked for. */
private class StubConfigTransport(private val payload: String?) : ConsentTransport {
    val gets = mutableListOf<Pair<String, String>>()
    override fun post(url: String, region: String, jsonBody: String) {}
    override fun get(url: String, region: String): String? {
        gets += url to region
        return payload ?: throw RuntimeException("offline")
    }
}

class ConsentRegulationTest {

    private fun client(region: String, transport: ConsentTransport = StubConfigTransport(null)) =
        CookieMunchConsent(
            cbid = "test-cbid",
            apiUrl = "https://cmp.example.com/",
            transport = transport,
            region = region,
            now = { 1_700_000_000_000 },
            stamp = { "fixed-stamp" },
        )

    @Test
    fun `resolves from the configured region without any network call`() {
        assertTrue(client("de").applicableRegulation().gdprApplies)
        assertTrue(client("us-ca").applicableRegulation().ccpaApplies)
    }

    @Test
    fun `consent is required before the user has answered`() {
        assertTrue(client("de").isConsentRequired())
    }

    /**
     * The single most useful call in the whole API: once they have answered, stop
     * asking. An app that re-prompts on every cold start is why people install blockers.
     */
    @Test
    fun `consent is not required once the user has answered`() = runTest {
        val c = client("de")
        c.accept()
        assertFalse(c.isConsentRequired())
    }

    @Test
    fun `consent is not required when an opt-out signal already answered for them`() {
        val c = client("us-ca")
        assertTrue(c.isConsentRequired())
        c.setGlobalPrivacyControl(true)
        assertFalse(c.isConsentRequired())
        assertTrue(c.applicableRegulation().forcedOptOut)
    }

    /** GPC is a refusal, not a regime change — a GDPR prompt is still owed. */
    @Test
    fun `global privacy control does not suppress a gdpr prompt`() {
        val c = client("de")
        c.setGlobalPrivacyControl(true)
        assertTrue(c.isConsentRequired())
    }

    @Test
    fun `refresh adopts the server answer over the local guess`() = runTest {
        // A German-locale phone, physically in California. Locale says GDPR; the
        // server, which sees the IP, says CCPA — and the server is right.
        val transport = StubConfigTransport(
            """{"regulation":{"region":"us-ca","class":"us",
               "regulations":{"gdprApplies":false,"ccpaApplies":true,"lgpdApplies":false},
               "model":"opt-out","defaultState":"granted","framework":"gpp",
               "forcedOptOut":false,"consentRequired":true}}""",
        )
        val c = client("de", transport)
        assertTrue(c.applicableRegulation().gdprApplies)

        c.refreshRegulation()

        assertTrue(c.applicableRegulation().ccpaApplies)
        assertFalse(c.applicableRegulation().gdprApplies)
        assertEquals(ConsentModel.OPT_OUT, c.applicableRegulation().model)
        // The same call asks for this device's language, so the response also carries the
        // banner's words — the SDK never ships forty catalogues of its own.
        val (url, sentRegion) = transport.gets.single()
        assertEquals("https://cmp.example.com/config/test-cbid", url.substringBefore("?"))
        assertTrue(url.contains("lang="))
        assertEquals("de", sentRegion)
    }

    /** Offline, or a server not yet upgraded. Either way the app keeps an answer. */
    @Test
    fun `refresh failure leaves the local regulation intact`() = runTest {
        val c = client("de", StubConfigTransport(null))
        c.refreshRegulation()
        assertTrue(c.applicableRegulation().gdprApplies)
        assertTrue(c.isConsentRequired())
    }

    @Test
    fun `refresh ignores a response with no regulation block`() = runTest {
        val c = client("de", StubConfigTransport("""{"cbid":"test-cbid"}"""))
        c.refreshRegulation()
        assertTrue(c.applicableRegulation().gdprApplies)
    }

    /** A transport written before this feature existed still works for everything else. */
    @Test
    fun `a transport without get support does not crash the app`() = runTest {
        val legacy = object : ConsentTransport {
            override fun post(url: String, region: String, jsonBody: String) {}
        }
        val c = client("de", legacy)
        c.refreshRegulation()
        assertTrue(c.applicableRegulation().gdprApplies)
    }
}

/**
 * Around twenty US states have comprehensive privacy laws that differ on what a consent UI
 * must do. Only the server can say which applies — a device's locale is a country at best —
 * so the SDK reads it from the config rather than guessing.
 */
class UsStateLawTest {
    @Test
    fun `parses the state law the server resolved`() {
        val json = """
            {"regulation":{"region":"us-tx","class":"us","regulations":{"gdprApplies":false,"ccpaApplies":true,"lgpdApplies":false},
            "model":"opt-out","defaultState":"granted","framework":"gpp","forcedOptOut":false,"consentRequired":true,
            "stateLaw":{"id":"tdpsa","state":"TX","name":"Texas Data Privacy and Security Act",
            "universalOptOut":true,"universalOptOutInForce":true,"sensitiveOptIn":true,"minorOptInUnder":13}}}
        """.trimIndent()
        val law = Regulation.fromConfigJson(json)?.stateLaw
        assertEquals("tdpsa", law?.id)
        assertEquals("TX", law?.state)
        assertEquals(true, law?.sensitiveOptIn)
        assertEquals(13, law?.minorOptInUnder)
    }

    @Test
    fun `is null when the server named no state law`() {
        val json = """
            {"regulation":{"region":"de","class":"eu","regulations":{"gdprApplies":true,"ccpaApplies":false,"lgpdApplies":false},
            "model":"opt-in","defaultState":"denied","framework":"tcf","forcedOptOut":false,"consentRequired":true}}
        """.trimIndent()
        assertNull(Regulation.fromConfigJson(json)?.stateLaw)
    }

    @Test
    fun `local resolution does not invent one`() {
        assertNull(Regulation.resolve("us-tx").stateLaw)
    }
}
