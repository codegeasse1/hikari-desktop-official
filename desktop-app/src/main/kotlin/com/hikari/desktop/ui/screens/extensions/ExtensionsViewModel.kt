package com.hikari.desktop.ui.screens.extensions

import com.hikari.desktop.di.AppContainerHolder
import com.hikari.desktop.domain.plugins.interactor.*
import com.hikari.desktop.domain.plugins.repository.PluginRepository
import com.hikari.desktop.repo.DesktopRepositoryManager
import com.hikari.desktop.repo.SitePlugin
import com.hikari.desktop.ui.base.BaseMviViewModel
import com.hikari.desktop.ui.screens.extensions.contract.ExtensionsUiEffect
import com.hikari.desktop.ui.screens.extensions.contract.ExtensionsUiEvent
import com.hikari.desktop.ui.screens.extensions.contract.ExtensionsUiState
import com.hikari.runtime.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.hikari.sources.aniyomi.AniyomiMainApi
import com.hikari.sources.aniyomi.AniyomiManager
import com.hikari.sources.hiki.HikiManager
import com.hikari.sources.nuvio.NuvioMainApi
import com.hikari.sources.nuvio.NuvioManager
import com.hikari.sources.sky.SkyStreamMainApi
import com.hikari.sources.sky.SkyStreamManager
import com.lagradost.cloudstream3.APIHolder
import com.hikari.desktop.ui.screens.extensions.contract.SourceRef

data class LocalPlugin(
    val file: File,
    val name: String,
    val internalName: String,
    val version: Int,
    val iconUrl: String?,
    val repoName: String,
    val language: String?,
    val tvTypes: List<String>?,
    val description: String? = null,
    val fileSize: Long = 0L,
)

class ExtensionsViewModel(
    private val pluginRepo: PluginRepository = AppContainerHolder.container.pluginRepository,
    private val getPluginRepositories: GetPluginRepositories = GetPluginRepositories(pluginRepo),
    private val addPluginRepository: AddPluginRepository = AddPluginRepository(pluginRepo),
    private val removePluginRepository: RemovePluginRepository = RemovePluginRepository(pluginRepo),
    private val getAvailablePlugins: GetAvailablePlugins = GetAvailablePlugins(pluginRepo),
    private val installPluginUseCase: InstallPlugin = InstallPlugin(pluginRepo),
    private val syncPluginRepositories: SyncPluginRepositories = SyncPluginRepositories(pluginRepo),
    private val getPluginIconUseCase: GetPluginIcon = GetPluginIcon(pluginRepo),
) : BaseMviViewModel<ExtensionsUiState, ExtensionsUiEvent, ExtensionsUiEffect>(
    initialState = ExtensionsUiState(),
) {
    init {
        viewModelScope.launch {
            getPluginRepositories.subscribe().collect { repos ->
                updateState { copy(savedRepositories = repos) }
            }
        }
        viewModelScope.launch {
            getPluginIconUseCase.subscribeIcons().collect { icons ->
                updateState { copy(remotePluginIcons = icons) }
            }
        }
        viewModelScope.launch {
            pluginRepo.syncGeneration.collect { gen ->
                val allPlugins = getAvailablePlugins.get()
                updateState { copy(syncGeneration = gen, plugins = allPlugins) }
            }
        }
        // Immediately populate both the Catalog and Installed tabs from local cache (0ms instant render)
        viewModelScope.launch(Dispatchers.IO) {
            loadPluginsFromManager()
            refreshInstalled()
            refreshSourceTabs()
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.common.storage.DesktopDataStore.pluginUpdatesFlow.collect {
                val history = com.hikari.common.storage.DesktopDataStore.getUpdatesHistory()
                updateState { copy(updatesHistory = history) }
            }
        }
    }

    override fun handleEvent(event: ExtensionsUiEvent) {
        when (event) {
            is ExtensionsUiEvent.OnFetchPlugins -> fetchPlugins()
            is ExtensionsUiEvent.OnLoadPluginsFromManager -> loadPluginsFromManager()
            is ExtensionsUiEvent.OnRefreshInstalled -> refreshInstalled()
            is ExtensionsUiEvent.OnInspectRepository -> inspectRepository(event.repoName)
            is ExtensionsUiEvent.OnInstallPlugin -> installPlugin(event.repoName, event.plugin)
            is ExtensionsUiEvent.OnUninstallPlugins -> uninstallPlugins(event.plugins)
            is ExtensionsUiEvent.OnUninstallByInternalName -> uninstallByInternalName(event.internalName)
            is ExtensionsUiEvent.OnUninstallPlugin -> uninstallPlugin(event.repoName, event.internalName)
            is ExtensionsUiEvent.OnLoadLocalPlugin -> loadLocalPlugin(event.file)
            is ExtensionsUiEvent.OnRemoveRepository -> removeRepository(event.url)
            is ExtensionsUiEvent.OnClearBypass -> clearBypass()
            is ExtensionsUiEvent.OnBypassSecurityAndInstall -> bypassSecurityAndInstall(event.repoName, event.plugin)
            is ExtensionsUiEvent.OnClearPermissionRequest -> clearPermissionRequest()
            is ExtensionsUiEvent.OnGrantPermissionAndInstall -> grantPermissionAndInstall(event.repoName, event.plugin, event.permissionName)
            is ExtensionsUiEvent.OnAddRepositoryFromInput -> addRepositoryFromInput(event.input)
            is ExtensionsUiEvent.OnSyncAllRepos -> syncAllRepos()
            is ExtensionsUiEvent.OnClearUpdateHistory -> clearUpdateHistory()
            is ExtensionsUiEvent.OnReloadPluginAfterSettings -> reloadPluginAfterSettings(event.file, event.pluginName)
            is ExtensionsUiEvent.OnAddStremioAddon -> addStremioAddon(event.url, event.onResult)
            is ExtensionsUiEvent.OnRemoveStremioAddon -> removeStremioAddon(event.manifestUrl)
            is ExtensionsUiEvent.OnSetStremioAddonEnabled -> setStremioAddonEnabled(event.manifestUrl, event.enabled)
            is ExtensionsUiEvent.OnRefreshStremioAddon -> refreshStremioAddon(event.manifestUrl)
                        is ExtensionsUiEvent.OnMoveStremioAddon -> moveStremioAddon(event.fromIndex, event.toIndex)
            is ExtensionsUiEvent.OnRefreshSourceTabs -> refreshSourceTabs()
            is ExtensionsUiEvent.OnAddSourceRepo -> addSourceRepo(event.kind, event.input)
            is ExtensionsUiEvent.OnRemoveSourceRepo -> removeSourceRepo(event.kind, event.url)
            is ExtensionsUiEvent.OnUninstallSourceItem -> uninstallSourceItem(event.kind, event.id)
            is ExtensionsUiEvent.OnInstallSourceFile -> installSourceFile(event.kind, event.file)
        }
    }

    private fun addRepositoryFromInput(input: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val addedRepos = addPluginRepository.await(input)
                if (!addedRepos.isNullOrEmpty()) {
                    val repoNames = addedRepos.take(2).joinToString { it.name } + if (addedRepos.size > 2) " and ${addedRepos.size - 2} more" else ""
                    val allPlugins = getAvailablePlugins.get()
                    updateState { copy(plugins = allPlugins, statusText = "Added ${addedRepos.size} repository(s): $repoNames.") }
                    refreshInstalled()
                } else {
                    updateState { copy(statusText = "Failed to load repository. Check the URL and try again.") }
                }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error: ${e.message}") }
            }
        }
    }

    private fun removeRepository(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            removePluginRepository.await(url)
            pluginRepo.incrementSyncGeneration()
        }
    }

    private fun syncAllRepos() {
        viewModelScope.launch {
            updateState { copy(isSyncing = true, isFetching = true, statusText = "Syncing repositories...") }
            try {
                withContext(Dispatchers.IO) {
                    syncPluginRepositories.await { done, total ->
                        val currentPlugins = getAvailablePlugins.get()
                        updateState {
                            copy(
                                plugins = currentPlugins,
                                statusText = "Syncing repositories ($done/$total)...",
                            )
                        }
                    }
                }
                val allPlugins = getAvailablePlugins.get()
                updateState { copy(plugins = allPlugins, statusText = "Sync completed successfully.") }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error syncing: ${e.message}") }
            } finally {
                updateState { copy(isSyncing = false, isFetching = false) }
            }
        }
    }

    private fun inspectRepository(repoName: String?) {
        updateState { copy(inspectedRepoName = repoName) }
    }

    private fun fetchPlugins() {
        updateState { copy(isFetching = true, statusText = "Fetching plugins from repositories...") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    syncPluginRepositories.await { done, total ->
                        val currentPlugins = getAvailablePlugins.get()
                        updateState {
                            copy(
                                plugins = currentPlugins,
                                statusText = "Fetching repositories ($done/$total)...",
                            )
                        }
                    }
                }
                val allPlugins = getAvailablePlugins.get()
                val text = "Fetched ${allPlugins.size} plugins from ${getPluginRepositories.get().size} repositories."
                updateState { copy(plugins = allPlugins, statusText = text) }
            } catch (e: Throwable) {
                updateState { copy(statusText = "Error: ${e.message}") }
            } finally {
                updateState { copy(isFetching = false) }
            }
        }
    }

    private fun loadPluginsFromManager() {
        val allPlugins = getAvailablePlugins.get()
        val text = "Showing ${allPlugins.size} plugins from ${getPluginRepositories.get().size} repositories."
        updateState { copy(plugins = allPlugins, statusText = text) }
    }

    private fun refreshInstalled() {
        val list = mutableListOf<LocalPlugin>()
        val extensionsDir = pluginRepo.getExtensionsDir()
        val allRemote = getAvailablePlugins.get()
        val savedRepos = getPluginRepositories.get()
        if (extensionsDir.exists()) {
            extensionsDir.walkTopDown()
                .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3" || it.extension == "hiki") }
                .filter {
                    !it.name.endsWith("-jvm.jar") &&
                    !it.name.contains("-secure") &&
                    !it.name.contains("-jvm") &&
                    !it.name.endsWith(".dex")
                }
                .distinctBy { it.nameWithoutExtension.substringBefore("-jvm").substringBefore("-secure") }
                .forEach { jar ->
                    val manifest = pluginRepo.readPluginManifest(jar)
                    val name = manifest?.get("name") as? String ?: jar.nameWithoutExtension
                    val internalName = manifest?.get("internalName") as? String ?: name
                    val version = manifest?.get("version")?.toString()?.toIntOrNull() ?: 0
                    val iconUrl = manifest?.get("iconUrl") as? String

                    // Exact repository matching based on folder structure on disk
                    val folderName = jar.parentFile?.name ?: ""
                    val matchingSavedRepo = savedRepos.find {
                        val cleanName = it.name.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                        cleanName.equals(folderName, ignoreCase = true)
                    }
                    val remoteMatch = allRemote.find { (rName, p) ->
                        val cleanRName = rName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                        p.internalName == internalName && cleanRName.equals(folderName, ignoreCase = true)
                    }

                    val repoName = matchingSavedRepo?.name
                        ?: remoteMatch?.first
                        ?: folderName.replace("_", " ").ifBlank { "Local" }

                    val rawTvTypes = manifest?.get("tvTypes")
                    val tvTypes = when (rawTvTypes) {
                        is List<*> -> rawTvTypes.filterIsInstance<String>()
                        is String -> listOf(rawTvTypes)
                        else -> remoteMatch?.second?.tvTypes ?: emptyList()
                    }
                    val language = manifest?.get("language") as? String ?: remoteMatch?.second?.language

                    val description = manifest?.get("description") as? String ?: remoteMatch?.second?.description
                    list.add(LocalPlugin(jar, name, internalName, version, iconUrl, repoName, language, tvTypes, description, jar.length()))
                }
        }
        updateState { copy(installedPlugins = list) }
    }

    private fun installPlugin(repoName: String, plugin: SitePlugin) {
        viewModelScope.launch {
            updateState { copy(installingPlugins = installingPlugins + plugin.internalName) }
            val repoCleanName = repoName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val targetDir = File(pluginRepo.getExtensionsDir(), repoCleanName)
            val jarFile = File(targetDir, "${plugin.internalName}.jar")
            val jvmJarFile = File(targetDir, "${plugin.internalName}-jvm.jar")
            val dexFile = File(targetDir, "${plugin.internalName}.dex")

            val cleanupFailedArtifacts = {
                try {
                    ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                    ExtensionLoader.clearFailedMarker(jarFile)
                    if (jarFile.exists()) jarFile.delete()
                    if (jvmJarFile.exists()) jvmJarFile.delete()
                    if (dexFile.exists()) dexFile.delete()
                } catch (_: Throwable) {}
            }

            try {
                val downloadedFile = withContext(Dispatchers.IO) {
                    installPluginUseCase.await(repoName, plugin)
                }
                if (downloadedFile != null) {
                    withContext(Dispatchers.IO) {
                        ExtensionLoader.clearFailedMarker(downloadedFile)
                        ExtensionLoader.unloadPlugin(downloadedFile.absolutePath)
                        ExtensionLoader.loadAndInit(downloadedFile, forceRetry = true)
                    }
                    refreshInstalled()
                    pluginRepo.incrementSyncGeneration()
                    com.hikari.desktop.ui.components.AppToastManager.showSuccess(
                        "Installed '${plugin.name}' (v${plugin.version})"
                    )
                } else {
                    cleanupFailedArtifacts()
                    com.hikari.desktop.ui.components.AppToastManager.showError(
                        "Failed to download '${plugin.name}': Network or server error"
                    )
                }
            } catch (e: com.hikari.runtime.security.RequiresPermissionException) {
                com.hikari.common.logging.AppLogger.e("Permission required for plugin", e)
                updateState { copy(pluginRequiringPermission = Triple(repoName, plugin, e.permissionName)) }
            } catch (e: java.lang.SecurityException) {
                cleanupFailedArtifacts()
                com.hikari.common.logging.AppLogger.e("Security notice installing plugin", e)
                val reason = e.message ?: "Suspicious bytecode or unverified class access detected."
                updateState { copy(pluginRequiringBypass = Triple(repoName, plugin, reason)) }
            } catch (e: Throwable) {
                cleanupFailedArtifacts()
                com.hikari.common.logging.AppLogger.e("Error loading plugin", e)
                com.hikari.desktop.ui.components.AppToastManager.showError(
                    "Failed to install '${plugin.name}': ${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                updateState { copy(installingPlugins = installingPlugins - plugin.internalName) }
            }
        }
    }

    private fun bypassSecurityAndInstall(repoName: String, plugin: SitePlugin) {
        viewModelScope.launch {
            updateState { copy(isDialogInstalling = true, installingPlugins = installingPlugins + plugin.internalName) }
            val repoCleanName = repoName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val targetDir = File(pluginRepo.getExtensionsDir(), repoCleanName)
            val jarFile = File(targetDir, "${plugin.internalName}.jar")
            val jvmJarFile = File(targetDir, "${plugin.internalName}-jvm.jar")
            val dexFile = File(targetDir, "${plugin.internalName}.dex")

            val cleanupFailedArtifacts = {
                try {
                    ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                    ExtensionLoader.clearFailedMarker(jarFile)
                    if (jarFile.exists()) jarFile.delete()
                    if (jvmJarFile.exists()) jvmJarFile.delete()
                    if (dexFile.exists()) dexFile.delete()
                } catch (_: Throwable) {}
            }

            try {
                // Persist trust with repository namespacing and all alias variants
                ExtensionLoader.addTrusted(jarFile, plugin.internalName, manifestName = plugin.name)

                val downloadedFile = withContext(Dispatchers.IO) {
                    installPluginUseCase.await(repoName, plugin)
                }
                if (downloadedFile != null) {
                    withContext(Dispatchers.IO) {
                        ExtensionLoader.clearFailedMarker(downloadedFile)
                        ExtensionLoader.unloadPlugin(downloadedFile.absolutePath)
                        ExtensionLoader.loadAndInit(downloadedFile, forceBypassSecurity = true, forceRetry = true)
                    }
                    refreshInstalled()
                    pluginRepo.incrementSyncGeneration()
                    com.hikari.desktop.ui.components.AppToastManager.showSuccess(
                        "Trusted and installed '${plugin.name}'"
                    )
                } else {
                    cleanupFailedArtifacts()
                    com.hikari.desktop.ui.components.AppToastManager.showError(
                        "Failed to download '${plugin.name}'"
                    )
                }
            } catch (e: Throwable) {
                cleanupFailedArtifacts()
                com.hikari.common.logging.AppLogger.e("Error loading plugin", e)
                com.hikari.desktop.ui.components.AppToastManager.showError(
                    "Failed to load '${plugin.name}': ${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                updateState { copy(pluginRequiringBypass = null, isDialogInstalling = false, installingPlugins = installingPlugins - plugin.internalName) }
            }
        }
    }

    private fun clearBypass() {
        updateState { copy(pluginRequiringBypass = null, isDialogInstalling = false) }
    }

    private fun grantPermissionAndInstall(repoName: String, plugin: SitePlugin, permissionName: String) {
        com.hikari.runtime.permission.PluginPermissionAPI.grantPermission(plugin.internalName, permissionName)
        updateState { copy(pluginRequiringPermission = null) }
        installPlugin(repoName, plugin)
    }

    private fun clearPermissionRequest() {
        updateState { copy(pluginRequiringPermission = null, isDialogInstalling = false) }
    }

    private fun uninstallPlugins(plugins: List<LocalPlugin>) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(isUninstalling = true) }
            for (plugin in plugins) {
                try {
                    // Step 1: Gracefully unload (calls beforeUnload, removes providers from APIHolder).
                    ExtensionLoader.unloadPlugin(plugin.file.absolutePath)

                    // Step 2: Unload releases URLClassLoader handles immediately via ExtensionLoader.
                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()

                    // Step 3: Check if this plugin owned the active provider.
                    val pluginProviders = com.lagradost.cloudstream3.APIHolder.allProviders
                        .filter { it.sourcePlugin == plugin.file.absolutePath }
                        .map { it.name }
                    val activeKeys = com.hikari.desktop.repo.ActiveProviderRepository.activeProviderKeys.value
                    val updatedKeys = activeKeys.filter { key -> !pluginProviders.any { p -> key.contains(p) } }
                    if (updatedKeys.size != activeKeys.size) {
                        com.hikari.desktop.repo.ActiveProviderRepository.setActiveProviders(updatedKeys)
                    }

                    // Step 4: Delete ONLY this plugin's own files.
                    val stem = plugin.file.nameWithoutExtension
                    val parentDir = plugin.file.parentFile
                    val filesToDelete = listOfNotNull(
                        plugin.file,
                        parentDir?.let { File(it, "$stem-jvm.jar") },
                        parentDir?.let { File(it, "$stem.dex") },
                        parentDir?.let { File(it, "$stem-secure.jar") },
                    )
                    for (f in filesToDelete) {
                        if (f.exists()) {
                            val ok = try {
                                java.nio.file.Files.deleteIfExists(f.toPath())
                                true
                            } catch (_: Throwable) {
                                f.delete()
                            }
                            if (!ok) f.deleteOnExit()
                            com.hikari.common.logging.AppLogger.i("Delete '${f.name}': ok=$ok")
                        }
                    }

                    // Step 5: Revoke persistent trust so future fresh re-installs require re-verification
                    ExtensionLoader.removeTrusted(plugin.file, plugin.internalName, manifestName = plugin.name)

                    com.hikari.common.logging.AppLogger.i("Uninstalled plugin '${plugin.name}' successfully.")
                } catch (e: Throwable) {
                    com.hikari.common.logging.AppLogger.e("Error uninstalling plugin '${plugin.name}'", e)
                }
            }
            refreshInstalled()
            pluginRepo.incrementSyncGeneration()
            updateState { copy(isUninstalling = false) }
        }
    }

    private fun uninstallPlugin(repoName: String, internalName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanRepo = repoName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val installedMatch = uiState.value.installedPlugins.find {
                it.internalName == internalName && (
                    it.file.parentFile?.name?.equals(cleanRepo, ignoreCase = true) == true ||
                    it.repoName.equals(repoName, ignoreCase = true)
                )
            }
            if (installedMatch != null) {
                uninstallPlugins(listOf(installedMatch))
            }
        }
    }

    private fun uninstallByInternalName(internalName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val installedMatch = uiState.value.installedPlugins.find { it.internalName == internalName }
            if (installedMatch != null) {
                uninstallPlugins(listOf(installedMatch))
            }
        }
    }

    private fun loadLocalPlugin(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetDir = File(pluginRepo.getExtensionsDir(), "Local_Sandbox")
            targetDir.mkdirs()
            val targetFile = File(targetDir, file.name)
            file.copyTo(targetFile, overwrite = true)
            try {
                ExtensionLoader.clearFailedMarker(targetFile)
                ExtensionLoader.loadAndInit(targetFile, forceRetry = true)
            } catch (e: Exception) {
                com.hikari.common.logging.AppLogger.e("Error loading local plugin", e)
            }
            refreshInstalled()
            pluginRepo.incrementSyncGeneration()
        }
    }

    private fun reloadPluginAfterSettings(file: File, pluginName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ExtensionLoader.clearFailedMarker(file)
                ExtensionLoader.unloadPlugin(file.absolutePath)
                ExtensionLoader.loadAndInit(file, forceBypassSecurity = true, forceRetry = true)
                refreshInstalled()
                pluginRepo.incrementSyncGeneration()
                com.hikari.common.logging.AppLogger.i("Reloaded plugin $pluginName after settings update")
                com.hikari.desktop.ui.components.AppToastManager.showSuccess("Reloaded '$pluginName'")
            } catch (e: Throwable) {
                com.hikari.common.logging.AppLogger.e("Failed to reload plugin $pluginName", e)
                com.hikari.desktop.ui.components.AppToastManager.showError(
                    "Failed to reload '$pluginName': ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun clearUpdateHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.common.storage.DesktopDataStore.clearUpdatesHistory()
        }
    }

    private fun addStremioAddon(url: String, onResult: (Result<com.hikari.desktop.stremio.ManagedStremioAddon>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.hikari.desktop.stremio.StremioAddonManager.addAddon(url)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private fun removeStremioAddon(manifestUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.desktop.stremio.StremioAddonManager.removeAddon(manifestUrl)
        }
    }

    private fun setStremioAddonEnabled(manifestUrl: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.desktop.stremio.StremioAddonManager.setAddonEnabled(manifestUrl, enabled)
        }
    }

    private fun refreshStremioAddon(manifestUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.desktop.stremio.StremioAddonManager.refreshAddon(manifestUrl)
        }
    }

    private fun moveStremioAddon(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            com.hikari.desktop.stremio.StremioAddonManager.moveAddon(fromIndex, toIndex)
        }
    }

    private fun refreshSourceTabs() {
        viewModelScope.launch(Dispatchers.IO) {
            val nuvio = runCatching {
                NuvioManager.installedScrapers().map { f -> SourceRef(f.absolutePath, NuvioManager.displayNameFor(f)) }
            }.getOrDefault(emptyList())
            val sky = runCatching {
                SkyStreamManager.installedPlugins().map { d -> SourceRef(d.absolutePath, SkyStreamManager.displayNameFor(d)) }
            }.getOrDefault(emptyList())
            val hiki = runCatching {
                HikiManager.installedFiles().map { f -> SourceRef(f.absolutePath, f.name) }
            }.getOrDefault(emptyList())
            val aniyomi = runCatching {
                AniyomiManager.installedExts().flatMap { f ->
                    AniyomiManager.labelsFor(f).mapIndexed { i, label -> SourceRef(f.absolutePath + "|" + i, label) }
                }
            }.getOrDefault(emptyList())
            updateState {
                copy(
                    sourceItemsNuvio = nuvio,
                    sourceItemsSky = sky,
                    sourceItemsHiki = hiki,
                    sourceItemsAniyomi = aniyomi,
                    sourceReposNuvio = NuvioManager.repoUrls(),
                    sourceReposSky = SkyStreamManager.repoUrls(),
                    sourceReposAniyomi = AniyomiManager.repoUrls(),
                )
            }
        }
    }

    private fun addSourceRepo(kind: String, input: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = input.trim()
            if (text.isBlank()) {
                updateState { copy(sourceTabStatus = "Paste a URL first.") }
                return@launch
            }
            updateState { copy(sourceTabBusy = true, sourceTabStatus = "Installing...") }
            try {
                val result: Result<*> = when (kind) {
                    "nuvio" -> if (text.endsWith(".js", ignoreCase = true)) {
                        val bytes = NuvioManager.fetchBytes(text)
                        if (bytes == null) Result.failure(Exception("Could not download the file"))
                        else NuvioManager.installSingle(bytes, text.substringAfterLast('/'), text)
                    } else {
                        NuvioManager.installFromManifest(text)
                    }
                    "sky" -> if (text.endsWith(".sky", ignoreCase = true)) {
                        val bytes = SkyStreamManager.fetchBytes(text)
                        if (bytes == null) Result.failure(Exception("Could not download the file"))
                        else SkyStreamManager.install(bytes, text)
                    } else {
                        val resolved = SkyStreamManager.resolveRepoUrl(text)
                        if (resolved == null) Result.failure(Exception("Could not resolve the repository URL"))
                        else SkyStreamManager.installFromManifest(resolved)
                    }
                    "hiki" -> {
                        val bytes = runCatching {
                            java.net.URL(text).openConnection().apply {
                                connectTimeout = 20_000
                                readTimeout = 45_000
                            }.getInputStream().use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) Result.failure(Exception("Could not download the file"))
                        else HikiManager.install(bytes, null, text)
                    }
                    "aniyomi" -> if (text.endsWith(".apk", ignoreCase = true)) {
                        val bytes = AniyomiManager.fetchBytes(text)
                        if (bytes == null) Result.failure(Exception("Could not download the file"))
                        else AniyomiManager.install(bytes, text)
                    } else {
                        val resolved = AniyomiManager.resolveRepoUrl(text)
                        if (resolved == null) Result.failure(Exception("Could not resolve the repository URL"))
                        else AniyomiManager.installFromManifest(resolved)
                    }
                    else -> Result.failure(Exception("Unknown source kind"))
                }
                result.onSuccess { count ->
                    resyncSourceApis(kind)
                    refreshSourceTabs()
                    val label = if (count is Int) "$count item(s)" else "item(s)"
                    updateState { copy(sourceTabStatus = "Installed $label.") }
                }.onFailure { e ->
                    updateState { copy(sourceTabStatus = e.message ?: "Install failed") }
                }
            } catch (e: Throwable) {
                updateState { copy(sourceTabStatus = e.message ?: "Install failed") }
            } finally {
                updateState { copy(sourceTabBusy = false) }
            }
        }
    }

    private fun removeSourceRepo(kind: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            when (kind) {
                "nuvio" -> NuvioManager.setRepoUrls(NuvioManager.repoUrls().filterNot { it == url })
                "sky" -> SkyStreamManager.setRepoUrls(SkyStreamManager.repoUrls().filterNot { it == url })
                "aniyomi" -> AniyomiManager.setRepoUrls(AniyomiManager.repoUrls().filterNot { it == url })
            }
            refreshSourceTabs()
            updateState { copy(sourceTabStatus = "Repository removed.") }
        }
    }

    private fun uninstallSourceItem(kind: String, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(sourceTabBusy = true) }
            try {
                val ok = when (kind) {
                    "nuvio" -> NuvioManager.uninstall(java.io.File(id))
                    "sky" -> SkyStreamManager.uninstall(java.io.File(id).name)
                    "hiki" -> HikiManager.uninstall(java.io.File(id))
                    "aniyomi" -> AniyomiManager.uninstall(java.io.File(id.substringBefore("|")))
                    else -> false
                }
                resyncSourceApis(kind)
                if (kind == "hiki") {
                    refreshInstalled()
                    pluginRepo.incrementSyncGeneration()
                }
                refreshSourceTabs()
                updateState { copy(sourceTabStatus = if (ok) "Uninstalled." else "Nothing was removed.") }
            } catch (e: Throwable) {
                updateState { copy(sourceTabStatus = e.message ?: "Uninstall failed") }
            } finally {
                updateState { copy(sourceTabBusy = false) }
            }
        }
    }

    private fun installSourceFile(kind: String, file: java.io.File) {
        viewModelScope.launch(Dispatchers.IO) {
            updateState { copy(sourceTabBusy = true, sourceTabStatus = "Installing...") }
            try {
                if (kind == "hiki") {
                    onEvent(ExtensionsUiEvent.OnLoadLocalPlugin(file))
                    refreshSourceTabs()
                    updateState { copy(sourceTabStatus = "Installed '${file.name}'.") }
                    return@launch
                }
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    updateState { copy(sourceTabStatus = "Could not read the file") }
                    return@launch
                }
                val result: Result<*> = when (kind) {
                    "nuvio" -> NuvioManager.installSingle(bytes, file.name)
                    "sky" -> SkyStreamManager.install(bytes, file.absolutePath)
                    "aniyomi" -> AniyomiManager.install(bytes, file.absolutePath)
                    else -> Result.failure(Exception("Unknown source kind"))
                }
                result.onSuccess {
                    resyncSourceApis(kind)
                    refreshSourceTabs()
                    updateState { copy(sourceTabStatus = "Installed '${file.name}'.") }
                }.onFailure { e ->
                    updateState { copy(sourceTabStatus = e.message ?: "Install failed") }
                }
            } catch (e: Throwable) {
                updateState { copy(sourceTabStatus = e.message ?: "Install failed") }
            } finally {
                updateState { copy(sourceTabBusy = false) }
            }
        }
    }

    private fun resyncSourceApis(kind: String) {
        try {
            synchronized(APIHolder.allProviders) {
                when (kind) {
                    "nuvio" -> {
                        APIHolder.allProviders.removeAll { it is NuvioMainApi && it.sourcePlugin == "built-in" }
                        for (f in NuvioManager.installedScrapers()) {
                            val api = NuvioMainApi(f)
                            api.sourcePlugin = "built-in"
                            APIHolder.allProviders.add(api)
                            APIHolder.addPluginMapping(api)
                        }
                    }
                    "sky" -> {
                        APIHolder.allProviders.removeAll { it is SkyStreamMainApi && it.sourcePlugin == "built-in" }
                        for (d in SkyStreamManager.installedPlugins()) {
                            val api = SkyStreamMainApi(d)
                            api.sourcePlugin = "built-in"
                            APIHolder.allProviders.add(api)
                            APIHolder.addPluginMapping(api)
                        }
                    }
                    "aniyomi" -> {
                        APIHolder.allProviders.removeAll { it is AniyomiMainApi && it.sourcePlugin == "built-in" }
                        for (f in AniyomiManager.installedExts()) {
                            val n = AniyomiManager.sourceCountFor(f)
                            for (i in 0 until n) {
                                val api = AniyomiMainApi(f, i)
                                api.sourcePlugin = "built-in"
                                APIHolder.allProviders.add(api)
                                APIHolder.addPluginMapping(api)
                            }
                        }
                    }
                }
            }
            runCatching { com.hikari.desktop.repo.ActiveProviderRepository.refreshProviders() }
        } catch (e: Throwable) {
            com.hikari.common.logging.AppLogger.e("Failed to resync source providers", e)
        }
    }
}
