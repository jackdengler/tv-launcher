package com.jackdengler.quiet.net

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Minimal blocking HTTP GET helpers (call from Dispatchers.IO / workers only). */
object Http {
    private const val TIMEOUT_MS = 15_000
    // Wikimedia asks API clients to identify themselves with a contact URL.
    private const val USER_AGENT = "Quiet/1.0 (Android TV launcher; https://github.com/jackdengler/tv-launcher)"

    class Response(val code: Int, val body: String?, val etag: String?)

    fun get(url: String, etag: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val c = open(url)
        try {
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            return Response(code, body, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    /** Streams [url] into [dest]; returns false on any non-2xx response. */
    fun download(url: String, dest: File): Boolean {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return false
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
    }
}
