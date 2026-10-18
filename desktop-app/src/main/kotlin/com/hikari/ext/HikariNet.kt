package com.hikari.ext

import com.codegeasse.hikari.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HikariWebViewResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class HikariResponse(
    val status: Int,
    val url: String,
    val body: String? = null,
)

object HikariNet {

    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                (browserHeaders + headers).forEach { (k, v) ->
                    runCatching { builder.header(k, v) }
                }
                client.newCall(builder.get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            }.getOrNull()
        }

    suspend fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(url)
            (browserHeaders + headers).forEach { (k, v) ->
                runCatching { builder.header(k, v) }
            }
            val mediaType = contentType.toMediaTypeOrNull()
            client.newCall(builder.post(body.toRequestBody(mediaType)).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.string()
            }
        }.getOrNull()
    }

    suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = withContext(Dispatchers.IO) {
        postString(url, body.toString(), headers + mapOf("Content-Type" to "application/json; charset=utf-8"))
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun base64Decode(s: String): ByteArray? = runCatching {
        android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    }.getOrNull()

    fun base64Encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    suspend fun getStringSmart(url: String, headers: Map<String, String> = emptyMap()): String? {
        val plain = getString(url, headers)
        if (plain != null && !looksLikeChallengePage(plain)) return plain
        AppLogger.w("HikariNet: $url looks like a challenge page; no embedded browser available")
        return plain
    }

    suspend fun getStringRendered(url: String, timeoutMs: Long = 25_000): String? = null

    private fun looksLikeChallengePage(html: String): Boolean {
        val probe = html.take(40_000)
        return CHALLENGE_MARKERS.any { probe.contains(it, ignoreCase = true) }
    }

    private val CHALLENGE_MARKERS = listOf(
        "cf-chl-",
        "challenge-platform",
        "cdn-cgi/challenge-platform",
        "cf-browser-verification",
        "cf-mitigated",
        "cf-turnstile",
        "Just a moment",
        "Attention Required!",
        "Pardon Our Interruption",
        "Checking your browser",
        "checking your browser",
        "Verify you are human",
        "verify you are human",
        "Access Denied",
        "datadome",
    )

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            getString(url, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                (browserHeaders + headers).forEach { (k, v) ->
                    runCatching { builder.header(k, v) }
                }
                client.newCall(builder.get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.bytes()
                }
            }.getOrNull()
        }

    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): HikariResponse? =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                (browserHeaders + headers).forEach { (k, v) ->
                    runCatching { builder.header(k, v) }
                }
                client.newCall(builder.get().build()).execute().use { r ->
                    HikariResponse(r.code, r.request.url.toString(), r.body?.string())
                }
            } catch (t: Throwable) {
                null
            }
        }

    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex> = emptyList(),
        timeoutMs: Long = 60_000,
        script: String? = null,
    ): List<HikariWebViewResult> = emptyList()
}
