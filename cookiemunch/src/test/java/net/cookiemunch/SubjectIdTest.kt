package net.cookiemunch

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Linking a decision to a signed-in account, so one person's consent correlates across
 * web, Android, iOS and desktop. The server has always accepted `subjectId` — validated,
 * stored and bound into the hash chain — but only the React Native client ever sent it,
 * and only as a constructor argument, which is close to useless: an app builds its
 * consent client at launch, before anyone has signed in.
 */
private class BodyRecorder : ConsentTransport {
    val bodies = mutableListOf<String>()
    override fun post(url: String, region: String, jsonBody: String) {
        bodies += jsonBody
    }

    fun lastSubjectId(): String? {
        val o = JSONObject(bodies.last())
        return if (o.has("subjectId")) o.getString("subjectId") else null
    }
}

class SubjectIdTest {

    private fun client(transport: ConsentTransport, subjectId: String? = null) =
        CookieMunchConsent(
            cbid = "cb-1",
            apiUrl = "https://cmp.example.com/",
            transport = transport,
            region = "de",
            subjectId = subjectId,
            now = { 1_700_000_000_000 },
            stamp = { "fixed" },
        )

    @Test
    fun `no subject id is sent when none is set`() = runTest {
        val t = BodyRecorder()
        client(t).accept()
        assertNull(t.lastSubjectId())
    }

    @Test
    fun `an id set after sign-in is attached to later decisions`() = runTest {
        val t = BodyRecorder()
        val c = client(t)

        c.accept()
        assertNull(t.lastSubjectId())

        c.setSubjectId("account-42")
        c.decline()
        assertEquals("account-42", t.lastSubjectId())
    }

    @Test
    fun `the constructor option still works`() = runTest {
        val t = BodyRecorder()
        client(t, subjectId = "account-42").accept()
        assertEquals("account-42", t.lastSubjectId())
    }

    @Test
    fun `setting it overrides the constructor value`() = runTest {
        val t = BodyRecorder()
        val c = client(t, subjectId = "from-init")
        c.setSubjectId("after-sign-in")
        c.accept()
        assertEquals("after-sign-in", t.lastSubjectId())
    }

    /**
     * Signing out must detach the id. Continuing to send it would attribute the next
     * person's decisions on a shared device to the account that just left.
     */
    @Test
    fun `clearing it stops the id being sent`() = runTest {
        val t = BodyRecorder()
        val c = client(t, subjectId = "account-42")
        c.setSubjectId(null)
        c.accept()
        assertNull(t.lastSubjectId())
    }

    @Test
    fun `an empty string clears it rather than sending an empty id`() = runTest {
        val t = BodyRecorder()
        val c = client(t, subjectId = "account-42")
        c.setSubjectId("")
        c.accept()
        assertNull(t.lastSubjectId())
        assertNull(c.getSubjectId())
    }

    @Test
    fun `it is readable back`() {
        val c = client(BodyRecorder())
        assertNull(c.getSubjectId())
        c.setSubjectId("account-42")
        assertEquals("account-42", c.getSubjectId())
    }

    /**
     * Who is signed in is the app's business and can change between launches, so a stale
     * account id baked into a restored record would attribute one person's consent to
     * another.
     */
    @Test
    fun `it is not persisted with the decision`() = runTest {
        val storage = InMemoryConsentStorage()
        CookieMunchConsent(
            cbid = "cb-1",
            apiUrl = "https://cmp.example.com/",
            storage = storage,
            transport = BodyRecorder(),
            region = "de",
            subjectId = "account-42",
            now = { 1_700_000_000_000 },
            stamp = { "fixed" },
        ).accept()
        assertFalse(storage.read()!!.contains("account-42"))
    }
}
