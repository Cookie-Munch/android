package net.cookiemunch

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Records POSTs (or fails on demand) so the client can be tested without a network. */
private class RecordingTransport : ConsentTransport {
    data class Call(val url: String, val region: String, val body: String)

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile
    var failNext = false

    override fun post(url: String, region: String, jsonBody: String) {
        if (failNext) {
            failNext = false
            throw IOException("network down")
        }
        calls.add(Call(url, region, jsonBody))
    }
}

class CookieMunchConsentTest {
    private fun client(
        storage: ConsentStorage = InMemoryConsentStorage(),
        transport: RecordingTransport = RecordingTransport(),
        region: String = "EU",
    ) = CookieMunchConsent(
        cbid = "cb_test",
        apiUrl = "https://api.example.com/", // trailing slash exercises trimEnd
        storage = storage,
        transport = transport,
        region = region,
        now = { 1_700_000_000_000L },
        stamp = { "stamp-1" },
    )

    @Test
    fun defaultStateIsImpliedAndNecessaryOnly() {
        val s = client().getState()
        assertTrue(s.necessary)
        assertEquals("implied", s.method)
        assertFalse(s.hasResponse)
        assertFalse(s.preferences)
        assertFalse(s.statistics)
        assertFalse(s.marketing)
        assertEquals("EU", s.region)
    }

    @Test
    fun acceptGrantsAllPersistsAndSyncs() = runTest {
        val transport = RecordingTransport()
        val storage = InMemoryConsentStorage()
        val c = client(storage = storage, transport = transport)

        val s = c.accept()

        assertTrue(s.preferences && s.statistics && s.marketing)
        assertEquals("explicit", s.method)
        assertTrue(s.hasResponse)

        // Local persist happened.
        val stored = JSONObject(storage.read()!!)
        assertEquals("explicit", stored.getString("method"))
        assertTrue(stored.getBoolean("marketing"))

        // Network sync happened with the correct URL, header and payload.
        assertEquals(1, transport.calls.size)
        val call = transport.calls[0]
        assertEquals("https://api.example.com/api/v1/consent", call.url)
        assertEquals("EU", call.region)
        val body = JSONObject(call.body)
        assertEquals("cb_test", body.getString("cbid"))
        assertEquals("stamp-1", body.getString("stamp"))
        assertEquals("app://cb_test", body.getString("url"))
        assertEquals("explicit", body.getString("method"))
        val choices = body.getJSONObject("choices")
        assertTrue(choices.getBoolean("preferences"))
        assertTrue(choices.getBoolean("statistics"))
        assertTrue(choices.getBoolean("marketing"))
    }

    @Test
    fun declineDeniesAllButIsExplicit() = runTest {
        val transport = RecordingTransport()
        val c = client(transport = transport)

        val s = c.decline()

        assertFalse(s.consented)
        assertEquals("explicit", s.method)
        assertTrue(s.hasResponse)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun submitCustomSetsExactChoices() = runTest {
        val c = client()
        val s = c.submitCustom(Choices(preferences = true, statistics = false, marketing = true))
        assertTrue(s.preferences)
        assertFalse(s.statistics)
        assertTrue(s.marketing)
        assertEquals("explicit", s.method)
    }

    @Test
    fun setTogglesSingleCategoryKeepingOthers() = runTest {
        val c = client()
        c.set(Category.STATISTICS, true)
        var s = c.getState()
        assertTrue(s.statistics)
        assertFalse(s.preferences)
        assertFalse(s.marketing)
        assertEquals("explicit", s.method)

        c.set(Category.STATISTICS, false)
        s = c.getState()
        assertFalse(s.statistics)
    }

    @Test
    fun persistedRecordReloadsIntoNewClient() = runTest {
        val storage = InMemoryConsentStorage()
        client(storage = storage).submitCustom(
            Choices(preferences = true, statistics = false, marketing = true),
        )

        val loaded = client(storage = storage).load()
        assertTrue(loaded.hasResponse)
        assertTrue(loaded.preferences)
        assertFalse(loaded.statistics)
        assertTrue(loaded.marketing)
        assertEquals("EU", loaded.region)
        assertEquals("stamp-1", loaded.stamp)
    }

    @Test
    fun corruptStorageFallsBackToImpliedDefault() {
        val c = client(storage = InMemoryConsentStorage("{not valid json"))
        val s = c.load()
        assertEquals("implied", s.method)
        assertFalse(s.hasResponse)
    }

    @Test
    fun failedPostNeverThrowsButStillPersists() = runTest {
        val transport = RecordingTransport().apply { failNext = true }
        val storage = InMemoryConsentStorage()
        val c = client(storage = storage, transport = transport)

        val s = c.accept() // must not throw despite the transport error

        assertTrue(s.hasResponse)
        assertTrue(s.consented)
        assertEquals(0, transport.calls.size) // the POST threw, nothing recorded
        assertEquals("explicit", JSONObject(storage.read()!!).getString("method"))
    }

    @Test
    fun onChangeFiresOnCommitAndStopsAfterUnsubscribe() = runTest {
        val c = client()
        val seen = mutableListOf<ConsentState>()
        val unsubscribe = c.onChange { seen.add(it) }

        c.accept()
        assertEquals(1, seen.size)
        assertTrue(seen.last().marketing)

        unsubscribe()
        c.decline()
        assertEquals(1, seen.size) // no further callbacks after unsubscribe
    }

    @Test
    fun gateRunsBlockOnlyWhenCategoryGranted() = runTest {
        val c = client()

        // Necessary is always granted.
        assertEquals("ok", c.gate(Category.NECESSARY) { "ok" })
        // Marketing not yet granted.
        assertNull(c.gate(Category.MARKETING) { "ran" })

        c.accept()
        assertEquals("ran", c.gate(Category.MARKETING) { "ran" })
    }

    @Test
    fun regionHeaderReflectsConfiguredRegion() = runTest {
        val transport = RecordingTransport()
        val c = client(transport = transport, region = "US-CA")
        c.accept()
        assertEquals("US-CA", transport.calls[0].region)
    }
}
