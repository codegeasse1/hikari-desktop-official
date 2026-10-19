package com.codegeasse.hikari.sources.hiki

import com.codegeasse.hikari.common.logging.AppLogger
import com.codegeasse.hikari.common.platform.PlatformPaths
import com.codegeasse.hikari.common.storage.DesktopDataStore
import com.codegeasse.hikari.desktop.core.preference.PreferenceKeys
import com.codegeasse.hikari.desktop.dex.DexJar
import com.codegeasse.hikari.runtime.loader.ExtensionLoader
import com.hikari.ext.HikariProvider
import com.lagradost.cloudstream3.plugins.BasePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile

object HikiManager {

    private const val TAG = "HikiManager"
    const val MAX_BYTES = 32L * 1024 * 1024

    const val MOBILE_REPO_URL = "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json"

    val DEFAULT_REPOS = listOf(
        Triple(
            MOBILE_REPO_URL,
            "Hikari Extensions",
            "Official .hiki extensions for Hikari",
        ),
    )

    data class MobileEntry(
        val name: String,
        val description: String,
        val url: String,
        val version: Int,
        val tvTypes: List<String>,
    )

    fun extDir(): File = PlatformPaths.extensionsDir

    fun isPluginFile(f: File): Boolean =
        f.isFile && (f.extension.equals("jar", true) || f.extension.equals("cs3", true) || f.extension.equals("hiki", true)) &&
            !f.name.endsWith("-jvm.jar") && !f.name.contains("-secure") && !f.name.endsWith(".dex")

    fun installedFiles(): List<File> =
        extDir().walkTopDown().filter { isPluginFile(it) }.sortedBy { it.name }.toList()

    fun isLoaded(file: File): Boolean = ExtensionLoader.isPluginLoaded(file.absolutePath)

    fun load(file: File): BasePlugin = ExtensionLoader.loadAndInit(file, forceRetry = true)

    fun unload(file: File) {
        runCatching { ExtensionLoader.unloadPlugin(file.absolutePath) }
    }

    fun mobileDir(): File =
        File(PlatformPaths.appDataDir, "hiki/mobile").apply { mkdirs() }

    fun installedMobile(): List<File> =
        mobileDir().listFiles()?.filter { it.isFile && it.extension.equals("hiki", true) }?.sortedBy { it.name } ?: emptyList()

    fun mobileSidecar(file: File): File =
        File(file.parentFile, file.nameWithoutExtension + ".meta.json")

    fun displayNameForMobile(file: File): String =
        runCatching { JSONObject(mobileSidecar(file).readText()).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { JSONObject(ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
            }).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension

    fun repoUrls(): List<String> =
        runCatching { DesktopDataStore.getKey<List<String>>(PreferenceKeys.PREF_HIKI_REPOS) }.getOrNull()
            ?: DEFAULT_REPOS.map { it.first }

    fun setRepoUrls(urls: List<String>) {
        runCatching { DesktopDataStore.setKey(PreferenceKeys.PREF_HIKI_REPOS, urls.distinct()) }
    }

    fun mobileEntries(repoText: String): List<MobileEntry> {
        val root = runCatching { JSONObject(repoText) }.getOrNull() ?: return emptyList()
        val arr = runCatching { root.getJSONArray("plugins") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<MobileEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { continue }
            val url = o.optString("url").ifBlank { continue }
            val desc = o.optString("description")
            val version = o.optInt("version", 1).takeIf { it > 0 } ?: 1
            val types = runCatching { o.getJSONArray("tvTypes") }.getOrNull()
                ?.let { a -> (0 until a.length()).mapNotNull { k -> a.optString(k).ifBlank { null } } } ?: emptyList()
            out += MobileEntry(name, desc, url, version, types)
        }
        return out
    }

    private fun prettyFallback(repoUrl: String): String {
        val last = repoUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').substringAfterLast('\\').removeSuffix(".json").replace('-', ' ').replace('_', ' ').trim()
        if (last.isNotBlank() && !last.equals("main", true) && last.length > 2) return last.split(' ').map { it.replaceFirstChar { c -> c.uppercase() } }.joinToString(" ").take(48)
        return runCatching { java.net.URL(repoUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: repoUrl
    }
    fun repoNameOf(repoText: String, fallback: String): String =
        runCatching { JSONObject(repoText).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private const val FETCH_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 HikariDesktop/1.0"
    internal fun fetchBytes(url: String, cap: Long = MAX_BYTES): ByteArray? {
        return runCatching {
            val c = URL(url).openConnection()
            c.connectTimeout = 20_000
            c.readTimeout = 45_000
            c.setRequestProperty("User-Agent", FETCH_UA)
            c.setRequestProperty("Accept", "*/*")
            (c as? java.net.HttpURLConnection)?.instanceFollowRedirects = true
            c.getInputStream().use { it.readBytes().take((cap + 1).toInt()).toByteArray().takeIf { b -> b.size <= cap } }
        }.getOrNull()
    }

    suspend fun fetchMobileEntries(repoUrl: String): List<MobileEntry> = withContext(Dispatchers.IO) {
        val raw = fetchBytes(repoUrl, 2 * 1024 * 1024) ?: return@withContext emptyList()
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext emptyList()
        mobileEntries(text)
    }

    suspend fun fetchRepoName(repoUrl: String): String = withContext(Dispatchers.IO) {
        val fallback = prettyFallback(repoUrl)
        val raw = fetchBytes(repoUrl, 512 * 1024) ?: return@withContext fallback
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext fallback
        repoNameOf(text, fallback)
    }

    fun readManifestClass(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).use { ins ->
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.ifBlank { null }
                        ?: Regex("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.ifBlank { null }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private data class Probe(val pluginClassName: String?, val mainClass: String?)

    private fun probeBytes(bytes: ByteArray): Probe? {
        return try {
            val tmp = File.createTempFile("hiki-probe", ".zip")
            try {
                tmp.writeBytes(bytes)
                ZipFile(tmp).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: return null
                    zip.getInputStream(entry).use { ins ->
                        val text = ins.readBytes().toString(Charsets.UTF_8)
                        val pluginClass = Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.ifBlank { null }
                        val mainClass = Regex("\"mainClass\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.ifBlank { null }
                        if (pluginClass == null && mainClass == null) return null
                        Probe(pluginClass, mainClass)
                    }
                }
            } finally {
                runCatching { tmp.delete() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeName(raw: String, fallbackExt: String = ".hiki"): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "extension$fallbackExt" }
        val clean = base.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val withExt = when {
            clean.endsWith(".hiki", true) || clean.endsWith(".cs3", true) || clean.endsWith(".jar", true) -> clean
            clean.contains('.') -> clean + fallbackExt
            else -> "$clean$fallbackExt"
        }
        return withExt
    }

    suspend fun install(bytes: ByteArray, fileName: String? = null, sourceUrl: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext Result.failure(Exception("The downloaded file is empty"))
            if (bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("File too large (max ${MAX_BYTES / 1024 / 1024}MB)"))
            }
            val probe = probeBytes(bytes)
                ?: return@withContext Result.failure(Exception("Not a valid extension: manifest.json is missing (same requirement as CloudStream plugins)"))
            if (probe.pluginClassName != null) {
                return@withContext installJar(bytes, fileName, sourceUrl, probe.pluginClassName)
            }
            val mobile = installMobile(bytes, fileName, sourceUrl, probe.mainClass)
            if (mobile.isSuccess) return@withContext mobile
            installJar(bytes, fileName, sourceUrl, probe.mainClass)
        }

    private suspend fun installJar(bytes: ByteArray, fileName: String?, sourceUrl: String?, pluginClass: String?): Result<String> =
        withContext(Dispatchers.IO) {
            val dir = File(extDir(), "Local_Sandbox").apply { mkdirs() }
            val target = File(dir, safeName(fileName ?: sourceUrl?.substringAfterLast('/') ?: "extension.hiki"))
            runCatching {
                target.setWritable(true)
                target.writeBytes(bytes)
            }.onFailure {
                return@withContext Result.failure(Exception("Could not write the extension to storage"))
            }
            try {
                ExtensionLoader.clearFailedMarker(target)
                ExtensionLoader.unloadPlugin(target.absolutePath)
                val plugin = ExtensionLoader.loadAndInit(target, forceRetry = true)
                AppLogger.i(TAG, "Installed ${target.name} ($pluginClass)")
                Result.success(plugin.javaClass.simpleName)
            } catch (e: Throwable) {
                runCatching {
                    ExtensionLoader.unloadPlugin(target.absolutePath)
                    ExtensionLoader.clearFailedMarker(target)
                    target.delete()
                }
                Result.failure(Exception("Could not load '${target.name}': ${e.message ?: e.javaClass.simpleName}"))
            }
        }

    suspend fun installMobile(bytes: ByteArray, fileName: String? = null, sourceUrl: String? = null, mainClass: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext Result.failure(Exception("The downloaded file is empty"))
            if (bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("File too large (max ${MAX_BYTES / 1024 / 1024}MB)"))
            }
            val probe = probeBytes(bytes)
            val main = mainClass ?: probe?.mainClass
                ?: return@withContext Result.failure(Exception("Not a Hikari extension: manifest.json needs mainClass"))
            val dir = mobileDir()
            val target = File(dir, safeName(fileName ?: sourceUrl?.substringAfterLast('/') ?: "extension.hiki"))
            runCatching {
                target.setWritable(true)
                target.writeBytes(bytes)
            }.onFailure {
                return@withContext Result.failure(Exception("Could not write the extension to storage"))
            }
            val provider = loadMobileProvider(target, main)
            if (provider == null) {
                runCatching { target.delete() }
                return@withContext Result.failure(Exception("Not a Hikari extension: $main could not be loaded as a provider"))
            }
            runCatching {
                val meta = JSONObject()
                    .put("name", provider.name)
                    .put("mainClass", main)
                    .put("sourceUrl", sourceUrl ?: fileName.orEmpty())
                mobileSidecar(target).writeText(meta.toString())
            }
            AppLogger.i(TAG, "Installed mobile ${target.name} (${provider.name})")
            Result.success(provider.name)
        }

    fun loadMobileProvider(file: File, mainClass: String? = null): HikariProvider? {
        return try {
            val main = mainClass ?: readManifestClass(file) ?: return null
            val jvmJar = runCatching { DexJar.ensureJvmJar(file) }.getOrNull() ?: file
            val parent = HikariProvider::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            val cl = URLClassLoader(arrayOf(jvmJar.toURI().toURL()), parent)
            val cls = cl.loadClass(main)
            val inst = cls.getDeclaredConstructor().newInstance()
            inst as? HikariProvider
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun uninstall(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) return@withContext false
        if (file.extension.equals("hiki", true) && file.parentFile?.name == "mobile") {
            runCatching { mobileSidecar(file).delete() }
            return@withContext runCatching { file.delete() }.getOrDefault(false)
        }
        runCatching { ExtensionLoader.unloadPlugin(file.absolutePath) }
        runCatching { ExtensionLoader.removeTrusted(file) }
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val stem = file.nameWithoutExtension
        val parent = file.parentFile
        val extra = listOfNotNull(
            parent?.let { File(it, stem + "-jvm.jar") },
            parent?.let { File(it, stem + "-secure.jar") },
            parent?.let { File(it, stem + ".dex") },
        )
        var ok = runCatching { if (file.exists()) file.delete() else true }.getOrDefault(false)
        for (f in extra) {
            runCatching { if (f.exists()) f.delete() }
        }
        ok
    }
}
