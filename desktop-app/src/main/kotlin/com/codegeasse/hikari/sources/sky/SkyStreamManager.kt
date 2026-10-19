package com.codegeasse.hikari.sources.sky

import com.codegeasse.hikari.common.logging.AppLogger
import com.codegeasse.hikari.common.platform.PlatformPaths
import com.codegeasse.hikari.common.storage.DesktopDataStore
import com.codegeasse.hikari.desktop.core.preference.PreferenceKeys
import com.codegeasse.hikari.desktop.skystream.SkyStreamRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object SkyStreamManager {

    private const val TAG = "SkyStreamManager"
    private const val FETCH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 HikariDesktop/1.0"
    const val MAX_BYTES = 5 * 1024 * 1024

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/repo.json",
            "SkyStream official",
            "The official SkyStream plugin repo (Akash's providers)",
        ),
        Triple(
            "https://raw.githubusercontent.com/rougegz/SkystreamPlugins/main/repo.json",
            "RougegAvengers SkyStream",
            "Community SkyStream plugins",
        ),
    )

    data class SkyEntry(
        val name: String,
        val packageName: String,
        val url: String,
        val version: Int,
        val iconUrl: String?,
        val tvTypes: List<String>,
    )

    fun pluginsDir(): File =
        File(PlatformPaths.appDataDir, "skystream/plugins").apply { mkdirs() }

    fun pluginDir(packageName: String): File =
        File(pluginsDir(), safe(packageName))

    fun scriptFile(packageName: String): File =
        File(pluginDir(packageName), "plugin.js")

    fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "plugin" }

    fun installedPlugins(): List<File> =
        pluginsDir().listFiles()
            ?.filter { it.isDirectory && File(it, "plugin.js").isFile }
            ?.sortedBy { it.name } ?: emptyList()

    fun manifestOf(pluginDir: File): JSONObject? {
        val f = File(pluginDir, "plugin.json")
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    fun displayNameFor(pluginDir: File): String =
        manifestOf(pluginDir)?.optString("name")?.takeIf { it.isNotBlank() } ?: pluginDir.name

    fun repoUrls(): List<String> =
        runCatching { DesktopDataStore.getKey<List<String>>(PreferenceKeys.PREF_SKY_REPOS) }.getOrNull()
            ?: DEFAULT_REPOS.map { it.first }

    fun setRepoUrls(urls: List<String>) {
        runCatching { DesktopDataStore.setKey(PreferenceKeys.PREF_SKY_REPOS, urls.distinct()) }
    }

    fun fallbackName(sourceUrl: String?): String {
        val file = sourceUrl?.substringBefore('?')?.substringAfterLast('/') ?: return ""
        return file.removeSuffix(".sky").removeSuffix(".zip")
    }

    fun skyEntry(o: JSONObject, baseUrl: String = ""): SkyEntry? {
        val packageName = o.optString("packageName").ifBlank { o.optString("id") }
        val name = o.optString("name").ifBlank { packageName }
        if (name.isBlank()) return null
        val raw = o.optString("url")
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.isNotBlank() && baseUrl.isNotBlank() -> "$baseUrl/$raw"
            raw.isNotBlank() -> raw
            baseUrl.isNotBlank() && packageName.isNotBlank() -> "$baseUrl/$packageName.sky"
            else -> return null
        }
        val versionStr = o.optString("version")
        val version = versionStr.takeWhile { it.isDigit() }.toIntOrNull()
            ?: if (versionStr.isNotBlank()) 1 else 1
        val types = runCatching { o.getJSONArray("categories") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: runCatching { o.getJSONArray("types") }.getOrNull()
                ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: emptyList()
        val icon = o.optString("iconUrl").ifBlank { o.optString("logo") }.ifBlank { o.optString("icon") }.ifBlank { null }
        return SkyEntry(name, packageName, url, version, icon, types)
    }

    fun repoEntries(manifestText: String, baseUrl: String): List<SkyEntry> {
        val root = runCatching { JSONObject(manifestText) }.getOrNull() ?: return emptyList()
        val arr = runCatching { root.getJSONArray("plugins") }.getOrNull()
            ?: runCatching { root.getJSONArray("entries") }.getOrNull()
            ?: runCatching { root.getJSONArray("extensions") }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { skyEntry(it, baseUrl) } }
    }

    internal fun fetchBytes(url: String, cap: Int = MAX_BYTES): ByteArray? {
        return runCatching {
            val c = URL(url).openConnection()
            c.connectTimeout = 20_000
            c.readTimeout = 45_000
            c.setRequestProperty("User-Agent", FETCH_UA)
            c.setRequestProperty("Accept", "*/*")
            (c as? java.net.HttpURLConnection)?.instanceFollowRedirects = true
            c.getInputStream().use { it.readBytes().take(cap + 1).toByteArray().takeIf { b -> b.size <= cap } }
        }.getOrNull()
    }

    suspend fun installFromManifest(manifestUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        val raw = fetchBytes(manifestUrl, 2 * 1024 * 1024)
            ?: return@withContext Result.failure(Exception("Could not download the repository manifest"))
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull()
            ?: return@withContext Result.failure(Exception("Could not read the repository manifest"))
        val entries = repoEntries(text, manifestUrl.substringBeforeLast('/'))
        if (entries.isEmpty()) return@withContext Result.failure(Exception("The manifest lists no extensions"))
        var ok = 0
        var lastErr: String? = null
        for (e in entries) {
            val bytes = fetchBytes(e.url)
            if (bytes == null || bytes.isEmpty()) {
                lastErr = "download failed for " + e.name
                continue
            }
            val r = install(bytes, e.url, e.iconUrl)
            if (r.isSuccess) ok++ else lastErr = r.exceptionOrNull()?.message
        }
        if (ok == 0) Result.failure(Exception(lastErr ?: "no extensions could be installed"))
        else {
            AppLogger.i(TAG, "Installed " + ok + " extension(s) from " + manifestUrl)
            Result.success(ok)
        }
    }

    suspend fun install(
        bytes: ByteArray,
        sourceUrl: String? = null,
        iconUrl: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.failure(Exception("The downloaded file is empty"))
        if (bytes.size > MAX_BYTES) return@withContext Result.failure(Exception("File too large (max 5MB)"))
        val parts = unzip(bytes)
            ?: return@withContext Result.failure(Exception("Not a SkyStream extension (unreadable .sky archive)"))
        val js = parts.js
            ?: return@withContext Result.failure(Exception("Not a SkyStream extension (no plugin.js in the .sky)"))
        val manifest = parts.manifest
            ?: return@withContext Result.failure(Exception("Not a SkyStream extension (no plugin.json in the .sky)"))
        val packageName = manifest.optString("packageName")
            .ifBlank { manifest.optString("id") }
            .ifBlank { fallbackName(sourceUrl) }
            .trim()
        if (packageName.isBlank()) return@withContext Result.failure(Exception("Extension has no package name"))
        val dir = pluginDir(packageName)
        runCatching { dir.mkdirs() }
        val jsFile = File(dir, "plugin.js")
        val jsonFile = File(dir, "plugin.json")
        val wrote = runCatching {
            jsFile.setWritable(true)
            jsFile.writeBytes(js)
            jsonFile.writeText(manifest.toString())
            true
        }.getOrDefault(false)
        if (!wrote) return@withContext Result.failure(Exception("Could not write the extension to storage"))
        val verdict = runCatching { SkyStreamRuntime.validate(packageName, jsFile) }.getOrNull()
        if (verdict == null || !verdict.startsWith("OK")) {
            runCatching { dir.deleteRecursively() }
            val detail = verdict?.removePrefix("ERR:")?.take(300)?.ifBlank { null }
                ?: "it doesn't export getHome/search/load/loadStreams"
            return@withContext Result.failure(Exception("Not a valid SkyStream extension: " + detail))
        }
        AppLogger.i(TAG, "Installed " + packageName)
        Result.success(packageName)
    }

    suspend fun uninstall(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = pluginDir(packageName)
        if (!dir.exists()) return@withContext false
        runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    suspend fun resolveRepoUrl(raw: String): String? = withContext(Dispatchers.IO) {
        val text = raw.trim()
        if (text.isBlank()) return@withContext null
        if (text.startsWith("http://") || text.startsWith("https://")) return@withContext text
        val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(.+)$").find(text)
        if (scheme != null) return@withContext "https://" + scheme.groupValues[1]
        if (text.contains("/") || Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+").containsMatchIn(text)) {
            return@withContext "https://$text"
        }
        if (!Regex("^[a-zA-Z0-9!_-]+$").matches(text)) return@withContext null
        for (candidate in listOf("https://cutt.ly/sky-$text", "https://cutt.ly/$text")) {
            val resolved = runCatching {
                val c = URL(candidate).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.connect()
                val loc = c.getHeaderField("Location")
                c.disconnect()
                loc?.takeIf { it.isNotBlank() && !it.contains("cutt.ly/404") && !it.trimEnd('/').endsWith("cutt.ly") }
            }.getOrNull()
            if (!resolved.isNullOrBlank()) return@withContext resolved
        }
        null
    }

    private fun prettyFallback(repoUrl: String): String {
        val last = repoUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json").replace('-', ' ').replace('_', ' ').trim()
        if (last.isNotBlank() && !last.equals("main", true) && last.length > 2) return last.split(' ').map { it.replaceFirstChar { c -> c.uppercase() } }.joinToString(" ").take(48)
        return runCatching { java.net.URL(repoUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: repoUrl
    }
    internal class Parts(val js: ByteArray?, val manifest: JSONObject?)

    internal fun unzip(bytes: ByteArray): Parts? {
        var js: ByteArray? = null
        var jsDepth = Int.MAX_VALUE
        var json: JSONObject? = null
        var jsonDepth = Int.MAX_VALUE
        val zin = runCatching { ZipInputStream(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        try {
            zin.use { z ->
                while (true) {
                    val e = runCatching { z.nextEntry }.getOrNull() ?: break
                    if (e.isDirectory) continue
                    val norm = e.name.replace('\\', '/').trimStart('/')
                    val lower = norm.lowercase()
                    val depth = norm.count { it == '/' }
                    val isJs = lower == "plugin.js" || lower.endsWith("/plugin.js")
                    val isJson = lower == "plugin.json" || lower.endsWith("/plugin.json")
                    if (!isJs && !isJson) continue
                    val data = z.readBytes()
                    if (data.isEmpty()) continue
                    if (isJs && depth < jsDepth) {
                        js = data
                        jsDepth = depth
                    }
                    if (isJson && depth < jsonDepth) {
                        json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
                        jsonDepth = depth
                    }
                }
            }
        } catch (_: Throwable) {
            return null
        }
        return Parts(js, json)
    }
    fun repoNameOf(manifestText: String, fallback: String): String =
        runCatching { JSONObject(manifestText).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    suspend fun fetchEntries(repoUrl: String): List<SkyEntry> = withContext(Dispatchers.IO) {
        val raw = fetchBytes(repoUrl, 2 * 1024 * 1024) ?: return@withContext emptyList()
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext emptyList()
        val base = repoUrl.substringBeforeLast('/')
        val direct = repoEntries(text, base)
        if (direct.isNotEmpty()) return@withContext direct
        val lists = runCatching { JSONObject(text).getJSONArray("pluginLists") }.getOrNull() ?: return@withContext emptyList()
        val out = mutableListOf<SkyEntry>()
        for (i in 0 until lists.length()) {
            var listUrl = lists.optString(i).ifBlank { continue }
            if (!listUrl.startsWith("http://") && !listUrl.startsWith("https://")) {
                listUrl = if (base.isNotBlank()) "$base/${listUrl.trimStart('/')}" else continue
            }
            val bytes = fetchBytes(listUrl, 2 * 1024 * 1024) ?: continue
            val body = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            out += repoEntries(body, listUrl.substringBeforeLast('/'))
        }
        out
    }

    suspend fun fetchRepoName(repoUrl: String): String = withContext(Dispatchers.IO) {
        val fallback = prettyFallback(repoUrl)
        val raw = fetchBytes(repoUrl, 512 * 1024) ?: return@withContext fallback
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext fallback
        repoNameOf(text, fallback)
    }

}
