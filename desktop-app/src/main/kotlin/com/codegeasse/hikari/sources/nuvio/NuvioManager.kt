package com.codegeasse.hikari.sources.nuvio

import com.codegeasse.hikari.common.logging.AppLogger
import com.codegeasse.hikari.common.platform.PlatformPaths
import com.codegeasse.hikari.common.storage.DesktopDataStore
import com.codegeasse.hikari.desktop.core.preference.PreferenceKeys
import com.codegeasse.hikari.desktop.nuvio.NuvioRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

object NuvioManager {

    private const val TAG = "NuvioManager"
    private const val FETCH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 HikariDesktop/1.0"
    const val MAX_BYTES = 5 * 1024 * 1024

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
            "Yoru's Nuvio Repo",
            "English nuvio providers (vixsrc, moviebox, showbox)",
        ),
        Triple(
            "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
            "Gowaru's Nuvio Repo",
            "Nuvio providers by Gowaru (French-oriented)",
        ),
        Triple(
            "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/manifest.json",
            "Phisher's Nuvio Repo",
            "Nuvio providers by Phisher98",
        ),
        Triple(
            "https://plugin.eclipsia.dpdns.org/manifest.json",
            "Eclipsia",
            "Eclipsia nuvio providers (HDHub4u, VegaMovies, AnimeWorld)",
        ),
    )

    data class ScraperEntry(
        val name: String,
        val filename: String,
        val url: String,
        val version: Int,
        val iconUrl: String?,
        val tvTypes: List<String>,
    )

    fun scrapersDir(): File =
        File(PlatformPaths.appDataDir, "nuvio/scrapers").apply { mkdirs() }

    fun installedScrapers(): List<File> =
        scrapersDir().listFiles()?.filter { it.isFile && it.extension.equals("js", true) }?.sortedBy { it.name } ?: emptyList()

    fun metaFor(scraper: File): JSONObject? {
        val meta = File(scraper.parentFile, scraper.nameWithoutExtension + ".meta.json")
        return runCatching { JSONObject(meta.readText()) }.getOrNull()
    }

    fun displayNameFor(scraper: File): String =
        metaFor(scraper)?.optString("name")?.takeIf { it.isNotBlank() } ?: scraper.nameWithoutExtension

    fun repoUrls(): List<String> =
        runCatching { DesktopDataStore.getKey<List<String>>(PreferenceKeys.PREF_NUVIO_REPOS) }.getOrNull()
            ?: DEFAULT_REPOS.map { it.first }

    fun setRepoUrls(urls: List<String>) {
        runCatching { DesktopDataStore.setKey(PreferenceKeys.PREF_NUVIO_REPOS, urls.distinct()) }
    }

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "provider.js" }
        val clean = base.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        return if (clean.endsWith(".js", true)) clean else "$clean.js"
    }

    fun manifestEntries(manifestText: String, baseUrl: String): List<ScraperEntry> {
        val root = runCatching { JSONObject(manifestText) }.getOrNull() ?: return emptyList()
        val arr = runCatching { root.getJSONArray("scrapers") }.getOrNull() ?: runCatching { root.getJSONArray("providers") }.getOrNull() ?: runCatching { root.getJSONArray("entries") }.getOrNull() ?: runCatching { root.getJSONArray("plugins") }.getOrNull() ?: runCatching { root.getJSONArray("extensions") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ScraperEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { continue }
            val filename = o.optString("filename").ifBlank { o.optString("file") }.ifBlank { o.optString("path") }.ifBlank { o.optString("url").substringAfterLast("/") }.ifBlank { continue }
            val versionStr = o.optString("version").ifBlank { o.optInt("versionCode").takeIf { it > 0 }?.toString() ?: "" }
            val version = versionStr.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
            val url = if (filename.startsWith("http://") || filename.startsWith("https://")) filename else "$baseUrl/$filename"
            val icon = o.optString("logo").ifBlank { o.optString("iconUrl") }.ifBlank { o.optString("icon") }.ifBlank { null }
            val types = runCatching { o.getJSONArray("supportedTypes") }.getOrNull()
                ?.let { a -> (0 until a.length()).mapNotNull { k -> a.optString(k).ifBlank { null } } } ?: emptyList()
            out += ScraperEntry(name, filename.substringAfterLast('/'), url, version, icon, types)
        }
        return out
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
        val base = manifestUrl.substringBeforeLast('/')
        val entries = manifestEntries(text, base)
        if (entries.isEmpty()) return@withContext Result.failure(Exception("The manifest lists no scrapers"))
        var ok = 0
        var lastErr: String? = null
        for (e in entries) {
            val bytes = fetchBytes(e.url)
            if (bytes == null || bytes.isEmpty()) {
                lastErr = "download failed for " + e.name
                continue
            }
            val r = installSingle(bytes, e.filename, e.url, e.iconUrl, e.name)
            if (r.isSuccess) ok++ else lastErr = r.exceptionOrNull()?.message
        }
        if (ok == 0) Result.failure(Exception(lastErr ?: "no scrapers could be installed"))
        else {
            AppLogger.i(TAG, "Installed " + ok + " scraper(s) from " + manifestUrl)
            Result.success(ok)
        }
    }

    suspend fun installSingle(
        bytes: ByteArray,
        fileName: String,
        sourceUrl: String? = null,
        iconUrl: String? = null,
        displayName: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.failure(Exception("The downloaded file is empty"))
        if (bytes.size > MAX_BYTES) return@withContext Result.failure(Exception("File too large (max 5MB)"))
        val effective = patchedBytes(sourceUrl, bytes) ?: bytes
        val text = runCatching { effective.toString(Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) return@withContext Result.failure(Exception("Scraper file is empty"))
        val verdict = runCatching { NuvioRuntime.validate(text) }.getOrNull()
        if (verdict == null || !verdict.startsWith("OK")) {
            val detail = verdict?.removePrefix("ERR:")?.take(300)?.ifBlank { null } ?: "no getStreams export found"
            return@withContext Result.failure(Exception("Not a valid nuvio provider: " + detail))
        }
        val target = File(scrapersDir(), safeName(fileName))
        runCatching {
            target.setWritable(true)
            target.writeBytes(effective)
        }.onFailure {
            return@withContext Result.failure(Exception("Could not write the scraper to storage"))
        }
        runCatching {
            val meta = JSONObject()
                .put("name", displayName ?: target.nameWithoutExtension)
                .put("sourceUrl", sourceUrl ?: fileName)
            if (!iconUrl.isNullOrBlank()) meta.put("iconUrl", iconUrl)
            File(target.parentFile, target.nameWithoutExtension + ".meta.json").writeText(meta.toString())
        }
        AppLogger.i(TAG, "Installed " + target.name)
        Result.success(target.nameWithoutExtension)
    }

    suspend fun uninstall(scraper: File): Boolean = withContext(Dispatchers.IO) {
        if (!scraper.exists() || !scraper.isFile) return@withContext false
        runCatching { File(scraper.parentFile, scraper.nameWithoutExtension + ".meta.json").delete() }
        runCatching { scraper.delete() }.getOrDefault(false)
    }

    private data class ProviderPatch(
        val fragment: String,
        val asset: String,
        val sourceMustContain: String? = null,
        val contentMustContain: String? = null,
    )

    private fun prettyFallback(repoUrl: String): String {
        val last = repoUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json").replace('-', ' ').replace('_', ' ').trim()
        if (last.isNotBlank() && !last.equals("main", true) && last.length > 2) return last.split(' ').map { it.replaceFirstChar { c -> c.uppercase() } }.joinToString(" ").take(48)
        return runCatching { java.net.URL(repoUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: repoUrl
    }
    private val PROVIDER_PATCHES = listOf(
        ProviderPatch("vornix.js", "nuvio/patches/vornix.js"),
        ProviderPatch("streamflix.js", "nuvio/patches/streamflix.js"),
        ProviderPatch(
            fragment = "4khdhub.js",
            asset = "nuvio/patches/4khdhub.js",
            sourceMustContain = "tapframe/nuvio-providers",
            contentMustContain = "4khdhub.click",
        ),
        ProviderPatch("zevran.js", "nuvio/patches/zevran.js"),
    )

    private fun assetBytes(asset: String): ByteArray? = runCatching {
        javaClass.getResourceAsStream("/" + asset)?.use { it.readBytes() }
    }.getOrNull()

    internal fun patchedBytes(sourceUrl: String?, original: ByteArray? = null): ByteArray? {
        val url = sourceUrl ?: return null
        val patch = PROVIDER_PATCHES.firstOrNull {
            url.endsWith(it.fragment, ignoreCase = true) || url.contains("/" + it.fragment, ignoreCase = true)
        } ?: return null
        val marker = patch.contentMustContain
        if (marker != null) {
            val text = original?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
            if (text == null || !text.contains(marker)) return null
        }
        return assetBytes(patch.asset)
    }
    fun repoNameOf(manifestText: String, fallback: String): String =
        runCatching { JSONObject(manifestText).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    suspend fun fetchEntries(repoUrl: String): List<ScraperEntry> = withContext(Dispatchers.IO) {
        val raw = fetchBytes(repoUrl, 2 * 1024 * 1024) ?: return@withContext emptyList()
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext emptyList()
        manifestEntries(text, repoUrl.substringBeforeLast('/'))
    }

    suspend fun fetchRepoName(repoUrl: String): String = withContext(Dispatchers.IO) {
        val fallback = prettyFallback(repoUrl)
        val raw = fetchBytes(repoUrl, 512 * 1024) ?: return@withContext fallback
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext fallback
        repoNameOf(text, fallback)
    }

}
