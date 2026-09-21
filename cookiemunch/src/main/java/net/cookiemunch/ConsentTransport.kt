package net.cookiemunch

import java.net.HttpURLConnection
import java.net.URL

/**
 * Injectable HTTP layer so [CookieMunchConsent] can be unit-tested without a network.
 * Implementations may throw on failure; the client swallows all errors (offline-safe).
 */
interface ConsentTransport {
    /** POSTs [jsonBody] to [url] with the `X-CookieMunch-Region` header set to [region]. */
    fun post(url: String, region: String, jsonBody: String)

    /**
     * GETs [url] and returns the body. Only used to refresh the applicable regulation
     * from `/config/:cbid`. Defaulted so a transport written before that feature
     * existed keeps compiling; returning null means "no answer", and the client then
     * stays with the regime it resolved locally.
     */
    fun get(url: String, region: String): String? = null
}

/**
 * Default transport using `java.net.HttpURLConnection` — no extra dependencies.
 * Always called from an IO dispatcher by the client.
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : ConsentTransport {
    override fun post(url: String, region: String, jsonBody: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-CookieMunch-Region", region)
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            // Drain the response so the socket can be released back to the pool.
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, region: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-CookieMunch-Region", region)
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
