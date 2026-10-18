package com.hikari.sources.hiki

import com.hikari.common.logging.AppLogger
import com.hikari.common.platform.PlatformPaths
import com.hikari.runtime.loader.ExtensionLoader
import com.lagradost.cloudstream3.plugins.BasePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object HikiManager {

    private const val TAG = "HikiManager"
    const val MAX_BYTES = 32L * 1024 * 1024

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

    fun readManifestClass(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).use { ins ->
                    val text = ins.readBytes().toString(Charsets.UTF_8)
                    val m = Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    m?.ifBlank { null }
                }
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
            val dir = File(extDir(), "Local_Sandbox").apply { mkdirs() }
            val target = File(dir, safeName(fileName ?: sourceUrl?.substringAfterLast('/') ?: "extension.hiki"))
            val pluginClass = try {
                val tmp = File.createTempFile("hiki-probe", ".zip", dir)
                try {
                    tmp.writeBytes(bytes)
                    ZipFile(tmp).use { zip ->
                        val entry = zip.getEntry("manifest.json")
                            ?: return@withContext Result.failure(Exception("Not a valid extension: manifest.json is missing (same requirement as CloudStream plugins)"))
                        zip.getInputStream(entry).use { ins ->
                            val text = ins.readBytes().toString(Charsets.UTF_8)
                            Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.ifBlank { null }
                                ?: return@withContext Result.failure(Exception("Not a valid extension: manifest.json needs pluginClassName, exactly like CloudStream plugins"))
                        }
                    }
                } finally {
                    runCatching { tmp.delete() }
                }
            } catch (e: Throwable) {
                return@withContext Result.failure(Exception("Not a valid extension: ${e.message ?: e.javaClass.simpleName}"))
            }
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

    suspend fun uninstall(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) return@withContext false
        runCatching { ExtensionLoader.unloadPlugin(file.absolutePath) }
        runCatching { ExtensionLoader.removeTrusted(file) } catch (_: Throwable) { }
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
