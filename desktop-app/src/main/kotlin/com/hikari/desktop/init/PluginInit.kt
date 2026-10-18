package com.hikari.desktop.init

import com.lagradost.cloudstream3.APIHolder
import com.hikari.desktop.core.preference.PreferenceKeys
import com.hikari.desktop.repo.DesktopRepositoryManager
import com.hikari.desktop.utils.appScope
import com.hikari.desktop.stremio.StremioApi
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.metaproviders.TraktProvider
import com.hikari.common.logging.AppLogger
import com.hikari.common.platform.PlatformPaths
import com.hikari.runtime.loader.ExtensionLoader
import com.hikari.sources.hiki.HikiMainApi
import com.hikari.ext.providers.YtsProvider
import com.hikari.sources.iptv.IptvMainApi
import com.hikari.sources.stremio.StremioAddonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Register the built-in meta-providers that ship with the client.
 */
fun initProviders() {
    val builtIns = listOf(
        TmdbProvider(),
        TraktProvider(),
        CrossTmdbProvider(),
        StremioApi(),
    ) + hikariBuiltIns()
    synchronized(APIHolder.allProviders) {
        builtIns.forEach { provider ->
            provider.sourcePlugin = "built-in"
            APIHolder.allProviders.add(provider)
            APIHolder.addPluginMapping(provider)
        }
    }
    AppLogger.i("Registered ${builtIns.size} built-in meta-providers")
}

/**
 * Hikari sources beyond Hikari plugins: each is a MainAPI so Home,
 * Search, Details and the Player pick it up with no UI changes. A source is
 * registered only when it is configured (e.g. an IPTV playlist URL is set).
 */
fun hikariBuiltIns(): List<com.lagradost.cloudstream3.MainAPI> {
    val out = mutableListOf<com.lagradost.cloudstream3.MainAPI>()
    runCatching {
        val iptvUrl = com.hikari.common.storage.DesktopDataStore
            .getKey<String>(PreferenceKeys.PREF_IPTV_PLAYLIST_URL).orEmpty().trim()
        if (iptvUrl.isNotBlank()) out.add(IptvMainApi(iptvUrl))
        runCatching {
            val addons = com.hikari.common.storage.DesktopDataStore
                .getKey<List<String>>(PreferenceKeys.PREF_STREMIO_ADDON_URLS) ?: emptyList()
            for (raw in addons) {
                val u = raw.trim().trimEnd('/')
                if (u.isBlank()) continue
                out.add(StremioAddonApi(u))
            }
        }
        runCatching {
            val hikiEnabled = com.hikari.common.storage.DesktopDataStore
                .getKey<Boolean>(PreferenceKeys.PREF_HIKI_ENABLED) ?: true
            if (hikiEnabled) out.add(HikiMainApi(YtsProvider()))
        }
    }
    return out
}

private val isPluginsInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
private val pluginInitLock = Any()

/**
 * Load installed plugins and cloned sites.
 */
fun initPlugins() {
    synchronized(pluginInitLock) {
        if (isPluginsInitialized.getAndSet(true)) {
            AppLogger.i("initPlugins() already executed; skipping duplicate invocation.")
            return
        }
        if (SafeModeState.isSafeMode.value) {
            AppLogger.w("[SafeMode] Third-party plugins bypassed. Only built-in providers will be loaded.")
            try {
                com.hikari.desktop.repo.ActiveProviderRepository.refreshProviders()
            } catch (e: Throwable) {
                AppLogger.e("Failed to refresh ActiveProviderRepository in Safe Mode", e)
            }
            return
        }
        loadInstalledPlugins()
        loadClonedSites()
        try {
            com.hikari.desktop.repo.ActiveProviderRepository.refreshProviders()
            AppLogger.i("ActiveProviderRepository refreshed after plugin initialization.")
        } catch (e: Throwable) {
            AppLogger.e("Failed to refresh ActiveProviderRepository after plugin init", e)
        }
    }
}

/**
 * Scan the Extensions directory and load all installed plugin JARs.
 */
private fun loadInstalledPlugins() {
    val extensionsDir = PlatformPaths.extensionsDir
    if (!extensionsDir.exists()) {
        AppLogger.w("No extensions directory found at ${extensionsDir.absolutePath}")
        return
    }

    val jarFiles = extensionsDir.walkTopDown()
        .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3" || it.extension == "hiki") }
        .filter { !it.name.endsWith("-jvm.jar") }
        .sortedBy { it.lastModified() }
        .toList()

    if (jarFiles.isEmpty()) {
        AppLogger.i("No plugins found in ${extensionsDir.absolutePath}")
        return
    }

    val retryQueue = mutableListOf<java.io.File>()
    var loaded = 0
    var failed = 0

    // First pass
    for (jarFile in jarFiles) {
        try {
            ExtensionLoader.loadAndInit(jarFile)
            loaded++
        } catch (e: Throwable) {
            val isDependencyError = e is NoClassDefFoundError || e is ClassNotFoundException
            if (isDependencyError) {
                retryQueue.add(jarFile)
                AppLogger.d("Deferred loading of ${jarFile.name} (dependency not met yet?)")
            } else {
                failed++
                AppLogger.e("Failed to load plugin: ${jarFile.name}", e)
                com.hikari.desktop.ui.components.AppToastManager.showPluginQuarantined(
                    pluginName = jarFile.nameWithoutExtension.removeSuffix("-jvm"),
                    reason = e.message ?: e.javaClass.simpleName,
                )
            }
            ExtensionLoader.unloadPlugin(jarFile.absolutePath)
        }
    }

    // Second pass for plugins that depend on others
    for (jarFile in retryQueue) {
        try {
            ExtensionLoader.loadAndInit(jarFile)
            loaded++
            failed--
        } catch (e: Throwable) {
            failed++
            AppLogger.e("Failed to load plugin: ${jarFile.name}", e)
            ExtensionLoader.unloadPlugin(jarFile.absolutePath)
            com.hikari.desktop.ui.components.AppToastManager.showPluginQuarantined(
                pluginName = jarFile.nameWithoutExtension.removeSuffix("-jvm"),
                reason = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    AppLogger.i("Plugin loading complete: $loaded loaded, $failed failed (of ${jarFiles.size} total)")
    AppLogger.i("Loaded $loaded plugins successfully! Extractor count: ${com.lagradost.cloudstream3.utils.extractorApis.size}")
}

/**
 * Reads CustomSite configurations from DesktopDataStore and instantiates cloned providers with custom URLs.
 */
fun loadClonedSites() {
    try {
        val clonedSitesJson = com.hikari.common.storage.DesktopDataStore.getKey<String>(PreferenceKeys.USER_PROVIDER_API)
        if (clonedSitesJson != null) {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val list = mapper.readValue<List<com.hikari.desktop.models.CustomSite>>(
                clonedSitesJson,
                object : com.fasterxml.jackson.core.type.TypeReference<List<com.hikari.desktop.models.CustomSite>>() {},
            )
            if (list.isEmpty()) return

            synchronized(com.lagradost.cloudstream3.APIHolder.allProviders) {
                list.distinctBy { "${it.name}_${it.url.trimEnd('/')}" }.forEach { custom ->
                    val cleanUrl = custom.url.trimEnd('/')
                    com.lagradost.cloudstream3.APIHolder.allProviders.firstOrNull { it.javaClass.simpleName == custom.parentJavaClass }?.let { baseProvider ->
                        // If the clone points to the exact same URL as the base provider, skip it!
                        if (baseProvider.mainUrl.trimEnd('/') == cleanUrl) {
                            AppLogger.i("Skipping redundant clone '${custom.name}' (matches base provider URL: $cleanUrl)")
                            return@forEach
                        }
                        val alreadyExists = com.lagradost.cloudstream3.APIHolder.allProviders.any {
                            it.name == custom.name && it.mainUrl.trimEnd('/') == cleanUrl
                        }
                        if (!alreadyExists) {
                            val clone = baseProvider.javaClass.getDeclaredConstructor().newInstance()
                            clone.name = custom.name
                            clone.lang = custom.lang
                            clone.mainUrl = cleanUrl
                            clone.canBeOverridden = false
                            com.lagradost.cloudstream3.APIHolder.allProviders.add(clone)
                            com.lagradost.cloudstream3.APIHolder.addPluginMapping(clone)
                        }
                    }
                }
            }
            AppLogger.i("Loaded ${list.size} cloned sites")
        }
    } catch (e: Exception) {
        AppLogger.e("Failed to load cloned sites", e)
    }
}

/**
 * Launches the background auto-updater that syncs all repositories.
 */
fun launchAutoUpdater(force: Boolean = false) {
    appScope.launch(Dispatchers.IO) {
        try {
            DesktopRepositoryManager.syncAll(force = force)
        } catch (e: Exception) {
            AppLogger.e("Startup sync failed", e)
        }
    }
}
