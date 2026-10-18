package com.codegeasse.hikari.sources.aniyomi

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import com.codegeasse.hikari.common.logging.AppLogger
import com.codegeasse.hikari.common.platform.PlatformPaths
import com.codegeasse.hikari.common.storage.DesktopDataStore
import com.codegeasse.hikari.desktop.aniyomi.ApkManifest
import com.codegeasse.hikari.desktop.core.LoadGate
import com.codegeasse.hikari.desktop.core.preference.PreferenceKeys
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object AniyomiManager {

    private const val TAG = "AniyomiManager"
    const val MAX_BYTES = 96 * 1024 * 1024

    private const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val META_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val META_NAME = "aniyomix.name"
    private const val META_EXT_LIB = "aniyomix.extensionLib"

    private val SUPPORTED_LIB_VERSIONS = listOf(14.0, 16.0, 17.0)
    private const val FAIL_RETRY_MS = 60_000L

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
            "Aniyomi",
            "The official Aniyomi extension repo",
        ),
    )

    data class AniEntry(
        val name: String,
        val pkg: String,
        val apkUrl: String,
        val version: Int,
        val iconUrl: String?,
        val lang: String,
    )

    class Extension(
        val file: File,
        val pkgName: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val libVersion: Double,
        val lang: String,
        val sources: List<AnimeSource>,
    ) {
        val labels: List<String> = disambiguateSources(sources, name)
    }

    fun extensionDir(): File =
        File(PlatformPaths.appDataDir, "aniyomi/exts").apply { mkdirs() }

    fun extFile(pkgName: String): File =
        File(extensionDir(), safe(pkgName) + ".ext")

    fun installedExts(): List<File> =
        extensionDir().listFiles()
            ?.filter { it.isFile && it.extension.equals("ext", true) }
            ?.sortedBy { it.name } ?: emptyList()

    fun sidecarFor(file: File): File =
        File(file.parentFile, file.nameWithoutExtension + ".meta.json")

    fun repoUrls(): List<String> =
        runCatching { DesktopDataStore.getKey<List<String>>(PreferenceKeys.PREF_ANIYOMI_REPOS) }.getOrNull()
            ?: DEFAULT_REPOS.map { it.first }

    fun setRepoUrls(urls: List<String>) {
        runCatching { DesktopDataStore.setKey(PreferenceKeys.PREF_ANIYOMI_REPOS, urls.distinct()) }
    }

    fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "extension" }

    fun repoEntries(manifestText: String, baseUrl: String): List<AniEntry> {
        val trimmed = manifestText.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }.getOrNull()
                ?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { repoPlugin(it, baseUrl) } } }
                ?: emptyList()
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val arr = runCatching { root.getJSONArray("extensions") }.getOrNull()
            ?: runCatching { root.getJSONArray("plugins") }.getOrNull()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { repoPlugin(it, baseUrl) } }
    }

    fun repoPlugin(o: JSONObject, baseUrl: String = ""): AniEntry? {
        val pkg = o.optString("pkg").trim()
        val name = o.optString("name").removePrefix("Aniyomi: ").trim().ifBlank { pkg }
        val apk = o.optString("apk").trim()
        if (name.isBlank() || apk.isBlank()) return null
        val root = baseUrl.trimEnd('/')
        val url = when {
            apk.startsWith("http://") || apk.startsWith("https://") -> apk
            root.isBlank() -> return null
            else -> root + "/apk/" + apk.trimStart('/')
        }
        val icon = o.optString("icon").ifBlank { o.optString("iconUrl") }
            .ifBlank {
                if (pkg.isNotBlank() && root.isNotBlank()) root + "/icon/" + pkg + ".png" else ""
            }
            .ifBlank { null }
        return AniEntry(
            name = name,
            pkg = pkg,
            apkUrl = url,
            version = o.optInt("code", 1).takeIf { it > 0 } ?: 1,
            iconUrl = icon,
            lang = o.optString("lang").trim(),
        )
    }

    fun resolveRepoUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val withScheme = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.startsWith("//") -> "https:" + text
            else -> {
                val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(.+)$").find(text)
                when {
                    scheme != null -> "https://" + scheme.groupValues[1]
                    text.contains("/") ||
                        Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+").containsMatchIn(text) ->
                        "https://" + text
                    else -> return null
                }
            }
        }
        val trimmed = withScheme.trimEnd('/')
        if (trimmed.endsWith(".json", ignoreCase = true)) return trimmed
        return trimmed + "/index.min.json"
    }

    fun displayName(info: PackageInfo): String {
        metaString(info, META_NAME)?.let { return it }
        val label = runCatching { info.applicationInfo?.nonLocalizedLabel?.toString() }.getOrNull().orEmpty()
        return label.removePrefix("Aniyomi: ").trim().ifBlank { info.packageName ?: "extension" }
    }

    fun isExtension(info: PackageInfo): Boolean =
        info.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    fun libVersionOf(info: PackageInfo): Double? {
        metaInt(info, META_EXT_LIB).takeIf { it != 0 }?.let { return it.toDouble() }
        return info.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    fun metaString(info: PackageInfo, key: String): String? = runCatching {
        info.applicationInfo?.metaData?.getString(key)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun metaInt(info: PackageInfo, key: String): Int = runCatching {
        info.applicationInfo?.metaData?.getInt(key) ?: 0
    }.getOrDefault(0)

    fun labelsFor(file: File): List<String> {
        val sidecar = runCatching { JSONObject(sidecarFor(file).readText()) }.getOrNull()
        val arr = sidecar?.optJSONArray("labels")
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }
        return listOf(sidecar?.optString("name")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension)
    }

    fun labelFor(file: File, index: Int): String =
        labelsFor(file).getOrNull(index) ?: (file.nameWithoutExtension + " #" + (index + 1))

    fun sourceCountFor(file: File): Int =
        labelsFor(file).size.coerceAtLeast(1)

    fun baseUrlFor(file: File, index: Int): String? {
        val arr = runCatching { JSONObject(sidecarFor(file).readText()) }.getOrNull()?.optJSONArray("baseUrls")
            ?: return null
        return arr.optString(index).ifBlank { null }
    }

    fun langFor(file: File, index: Int): String {
        val arr = runCatching { JSONObject(sidecarFor(file).readText()) }.getOrNull()?.optJSONArray("langs")
            ?: return "en"
        return arr.optString(index).ifBlank { "en" }
    }

    internal fun fetchBytes(url: String, cap: Int = MAX_BYTES): ByteArray? {
        return runCatching {
            URL(url).openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 45_000
            }.getInputStream().use { it.readBytes().take(cap + 1).toByteArray().takeIf { b -> b.size <= cap } }
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
            val bytes = fetchBytes(e.apkUrl)
            if (bytes == null || bytes.isEmpty()) {
                lastErr = "download failed for " + e.name
                continue
            }
            val r = install(bytes, e.apkUrl, e.iconUrl)
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
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.failure(Exception("The downloaded file is empty"))
        if (bytes.size > MAX_BYTES) return@withContext Result.failure(Exception("File too large (max 96MB)"))
        val dir = extensionDir()
        val temp = File(dir, "incoming-" + System.nanoTime() + ".apk")
        try {
            runCatching {
                temp.setWritable(true)
                temp.writeBytes(bytes)
                temp.setReadOnly()
            }.onFailure { return@withContext Result.failure(Exception("Could not write the extension to storage")) }
            val info = ApkManifest.read(temp)
            if (info == null || !isExtension(info)) {
                return@withContext Result.failure(Exception("Not an anime extension (missing extension marker)"))
            }
            val pkgName = info.packageName?.trim()
                ?: return@withContext Result.failure(Exception("The extension package has no name"))
            val newCode = PackageInfoCompat.getLongVersionCode(info)
            val target = extFile(pkgName)
            val installed = if (target.exists()) ApkManifest.read(target) else null
            if (installed != null && isExtension(installed) &&
                PackageInfoCompat.getLongVersionCode(installed) > newCode
            ) {
                return@withContext Result.failure(Exception("A newer build of this extension is installed"))
            }
            target.delete()
            if (!temp.renameTo(target)) {
                runCatching { temp.copyTo(target, overwrite = true) }
                    .onFailure { return@withContext Result.failure(Exception("Could not store the extension file")) }
                temp.delete()
            }
            target.setReadOnly()
            cache.remove(target.absolutePath)
            val ext = extensionOf(target, force = true)
            if (ext == null || ext.sources.isEmpty()) {
                runCatching { target.delete() }
                runCatching { sidecarFor(target).delete() }
                cache.remove(target.absolutePath)
                return@withContext Result.failure(Exception(lastError ?: "The extension could not be loaded"))
            }
            runCatching {
                val sidecar = JSONObject()
                    .put("name", ext.name)
                    .put("pkg", ext.pkgName)
                    .put("versionCode", ext.versionCode)
                    .put("labels", JSONArray(ext.labels))
                    .put("baseUrls", JSONArray(ext.sources.map { (it as? AnimeHttpSource)?.baseUrl.orEmpty() }))
                    .put("langs", JSONArray(ext.sources.map { runCatching { it.lang }.getOrNull().orEmpty() }))
                if (!iconUrl.isNullOrBlank()) sidecar.put("iconUrl", iconUrl)
                if (!sourceUrl.isNullOrBlank()) sidecar.put("sourceUrl", sourceUrl)
                sidecarFor(target).writeText(sidecar.toString())
            }
            AppLogger.i(TAG, "Installed " + pkgName + " (" + ext.sources.size + " sources)")
            Result.success(ext.sources.size)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    suspend fun uninstall(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) return@withContext false
        cache.remove(file.absolutePath)
        runCatching { sidecarFor(file).delete() }
        runCatching { file.delete() }.getOrDefault(false)
    }

    private val cache = ConcurrentHashMap<String, Extension>()
    private val lastFail = ConcurrentHashMap<String, Long>()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var injektReady = false

    private fun ensureInjekt() {
        if (injektReady) return
        synchronized(this) {
            if (injektReady) return
            val app = Application()
            val network = NetworkHelper(app)
            val js = JavaScriptEngine(app)
            runCatching { Injekt.addSingleton(app) }
            runCatching { Injekt.addSingleton<Context>(app) }
            runCatching { Injekt.addSingleton(network) }
            runCatching { Injekt.addSingleton(js) }
            injektReady = true
        }
    }

    fun extensionOf(file: File, force: Boolean = false): Extension? {
        val path = file.absolutePath
        if (!force) cache[path]?.let { return it }
        if (!file.exists()) {
            lastError = "Extension file missing"
            return null
        }
        if (!force) {
            val failAt = lastFail[path]
            if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) return null
        }
        val lock = LoadGate.lockFor(path)
        if (!LoadGate.acquire(lock)) {
            lastError = "The extension loader is busy"
            return null
        }
        try {
            if (!force) cache[path]?.let { return it }
            val ext = try {
                LoadGate.withSlot { load(file) }
            } catch (e: LoadGate.LoadQueueBusyException) {
                lastError = "The extension loader is busy"
                null
            }
            if (ext != null) {
                cache[path] = ext
                lastFail.remove(path)
            } else {
                lastFail[path] = System.currentTimeMillis()
            }
            return ext
        } finally {
            lock.unlock()
        }
    }

    fun sourceOf(file: File, index: Int): AnimeSource? =
        extensionOf(file)?.sources?.getOrNull(index)

    private fun load(file: File): Extension? {
        lastError = null
        if (file.canWrite()) runCatching { file.setReadOnly() }
        val info = ApkManifest.read(file)
        if (info == null || !isExtension(info)) {
            lastError = "Not an anime extension"
            return null
        }
        val appInfo = info.applicationInfo
        if (appInfo == null) {
            lastError = "The extension package is malformed"
            return null
        }
        if (appInfo.sourceDir == null) appInfo.sourceDir = file.absolutePath
        if (appInfo.publicSourceDir == null) appInfo.publicSourceDir = file.absolutePath
        val pkgName = info.packageName
        if (pkgName.isNullOrBlank()) {
            lastError = "The extension package has no name"
            return null
        }
        val versionName = info.versionName.orEmpty()
        val libVersion = libVersionOf(info)
        if (libVersion == null) {
            lastError = "Cannot determine the extension library version"
            return null
        }
        if (libVersion !in SUPPORTED_LIB_VERSIONS) {
            lastError = "Unsupported extension library version"
            return null
        }
        val name = displayName(info)
        ensureInjekt()
        val parent = AniyomiManager::class.java.classLoader
        val apkPath = file.absolutePath
        val child = runCatching {
            ChildFirstPathClassLoader(apkPath, null, parent)
        }.getOrElse {
            lastError = "Could not open the extension"
            return null
        }
        val classNames = metaString(info, META_SOURCE_CLASS)
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (classNames.isEmpty()) {
            lastError = "The extension declares no sources"
            return null
        }
        val sources = mutableListOf<AnimeSource>()
        for (entry in classNames) {
            val className = if (entry.startsWith(".")) pkgName + entry else entry
            instantiate(className, apkPath, child, parent)?.let { sources += it }
        }
        if (sources.isEmpty()) {
            if (lastError == null) lastError = "None of the extension sources could be loaded"
            return null
        }
        val seen = LinkedHashSet<String>()
        val unique = sources.filter { seen.add(sourceKey(it)) }
        val langs = unique.map { runCatching { it.lang }.getOrNull().orEmpty() }.filter { it.isNotBlank() }.toSet()
        return Extension(
            file = file,
            pkgName = pkgName,
            name = name,
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            libVersion = libVersion,
            lang = when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            },
            sources = unique,
        )
    }

    private fun instantiate(
        className: String,
        apkPath: String,
        child: ClassLoader,
        parent: ClassLoader,
    ): List<AnimeSource>? {
        val loaders = listOf(child, PathClassLoader(apkPath, null, parent))
        for ((index, loader) in loaders.withIndex()) {
            var result: List<AnimeSource>? = null
            try {
                val instance = Class.forName(className, false, loader)
                    .getDeclaredConstructor()
                    .newInstance()
                result = when (instance) {
                    is AnimeSource -> listOf(instance)
                    is AnimeSourceFactory -> instance.createSources()
                    else -> {
                        lastError = "Invalid extension source class"
                        return null
                    }
                }
            } catch (e: LinkageError) {
                if (index == 0) continue
                lastError = "Extension source could not be linked"
                return null
            } catch (e: Throwable) {
                lastError = "Extension source could not be created"
                return null
            }
            if (result.isNullOrEmpty()) {
                lastError = "Extension source produced no sources"
                return null
            }
            return result
        }
        return null
    }

    internal fun disambiguateSources(sources: List<AnimeSource>, fallback: String): List<String> {
        val base = sources.map { s ->
            runCatching { s.name }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: fallback
        }
        val collisions = base.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (collisions.isEmpty()) return base
        val out = base.toMutableList()
        for (name in collisions) {
            val idxs = base.indices.filter { base[it] == name }
            val kinds = listOf<(AnimeSource) -> String?>(
                { languageLabel(it) },
                { siteLabel(it) },
            )
            val picked = kinds.firstOrNull { kind ->
                val details = idxs.map { kind(sources[it]) }
                details.none { it.isNullOrBlank() } && details.distinct().size == details.size
            }
            idxs.forEachIndexed { k, i ->
                val detail = picked?.invoke(sources[i])
                out[i] = when {
                    detail.isNullOrBlank() -> name + " (" + (k + 1) + ")"
                    squashName(name).contains(squashName(detail)) -> name
                    else -> name + " | " + detail
                }
            }
        }
        return out
    }

    internal fun languageLabel(s: AnimeSource): String? {
        val code = runCatching { s.lang }.getOrNull()?.trim()?.lowercase().orEmpty()
        return when (code) {
            "en", "eng", "english" -> "English"
            "hi", "hin", "hindi" -> "Hindi"
            "ta", "tam", "tamil" -> "Tamil"
            "te", "tel", "telugu" -> "Telugu"
            "ml", "mal", "malayalam" -> "Malayalam"
            "bn", "ben", "bengali" -> "Bengali"
            "ur", "urd", "urdu" -> "Urdu"
            "ja", "jp", "jpn", "japanese" -> "Japanese"
            "ko", "kor", "korean" -> "Korean"
            "zh", "chi", "chinese" -> "Chinese"
            "es", "spa", "spanish" -> "Spanish"
            "pt", "por", "portuguese" -> "Portuguese"
            "fr", "fre", "french" -> "French"
            "de", "ger", "german" -> "German"
            "it", "ita", "italian" -> "Italian"
            "ru", "rus", "russian" -> "Russian"
            "ar", "ara", "arabic" -> "Arabic"
            "id", "ind", "indonesian" -> "Indonesian"
            "th", "tha", "thai" -> "Thai"
            "vi", "vie", "vietnamese" -> "Vietnamese"
            "fil", "tl", "tgl", "tagalog", "filipino" -> "Filipino"
            else -> null
        }
    }

    internal fun siteLabel(s: AnimeSource): String? {
        val raw = (s as? AnimeHttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }
            ?.trim().orEmpty()
        if (raw.isBlank()) return null
        val withScheme = if (raw.startsWith("http")) raw else "https://" + raw
        return runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull()
            ?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    internal fun squashName(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    internal fun sourceKey(s: AnimeSource): String {
        val id = runCatching { s.id }.getOrNull()
        val name = runCatching { s.name }.getOrNull()
        val lang = runCatching { s.lang }.getOrNull()
        val base = (s as? AnimeHttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }
        return id.toString() + "|" + name + "|" + lang + "|" + base
    }
    suspend fun fetchEntries(repoUrl: String): List<AniEntry> = withContext(Dispatchers.IO) {
        val raw = fetchBytes(repoUrl, 2 * 1024 * 1024) ?: return@withContext emptyList()
        val text = runCatching { raw.toString(Charsets.UTF_8) }.getOrNull() ?: return@withContext emptyList()
        repoEntries(text, repoUrl.substringBeforeLast('/'))
    }

    suspend fun fetchRepoName(repoUrl: String): String = withContext(Dispatchers.IO) {
        runCatching { URL(repoUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: repoUrl
    }

}
