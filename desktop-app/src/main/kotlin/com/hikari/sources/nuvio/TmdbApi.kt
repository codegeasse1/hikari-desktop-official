package com.hikari.sources.nuvio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

object TmdbApi {
    const val API_BASE = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/w500"
    const val IMG_L = "https://image.tmdb.org/t/p/w1280"

    private val API_KEYS = listOf(
        "e6333b32409e02a4a6eba6fb7ff866bb",
        "68e094699525b18a70bab2f86b1fa706",
        "439c478a771f35c05022f9feabcca01c",
    )

    private const val MAX_BYTES = 8 * 1024 * 1024

    suspend fun apiGet(path: String, query: Map<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        for (key in API_KEYS) {
            val qs = ((query + ("api_key" to key)).entries.joinToString("&") { (k, v) ->
                k + "=" + URLEncoder.encode(v, "UTF-8")
            })
            val text = runCatching {
                URL(API_BASE + path + "?" + qs).openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }.getInputStream().use { it.readBytes().take(MAX_BYTES).toByteArray().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (obj.has("success") && !obj.optBoolean("success", true)) continue
            if (obj.has("status_code") && obj.has("status_message") && !obj.has("id") && !obj.has("results")) continue
            return@withContext obj
        }
        null
    }

    fun imageUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { IMG + it }
}
