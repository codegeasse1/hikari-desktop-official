package com.hikari.desktop.ui.screens.extensions.contract

import com.hikari.desktop.repo.SitePlugin
import com.hikari.desktop.ui.base.UiState
import com.hikari.desktop.ui.screens.extensions.LocalPlugin

data class SourceRef(val id: String, val name: String)

data class ExtensionsUiState(
    val isFetching: Boolean = false,
    val isUninstalling: Boolean = false,
    val statusText: String = "Press Sync (sidebar) or Fetch below to load plugins from your repositories.",
    val plugins: List<Pair<String, SitePlugin>> = emptyList(),
    val installedPlugins: List<LocalPlugin> = emptyList(),
    val pluginRequiringBypass: Triple<String, SitePlugin, String>? = null,
    val pluginRequiringPermission: Triple<String, SitePlugin, String>? = null,
    val inspectedRepoName: String? = null,
    val savedRepositories: List<com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData> = emptyList(),
    val remotePluginIcons: Map<String, String?> = emptyMap(),
    val syncGeneration: Int = 0,
    val extensionsDir: java.io.File = java.io.File("."),
    val isSyncing: Boolean = false,
    val installingPlugins: Set<String> = emptySet(),
    val isDialogInstalling: Boolean = false,
    val updatesHistory: List<com.hikari.common.storage.PluginUpdateRecord> = emptyList(),
    val sourceItemsNuvio: List<SourceRef> = emptyList(),
    val sourceItemsSky: List<SourceRef> = emptyList(),
    val sourceItemsHiki: List<SourceRef> = emptyList(),
    val sourceItemsAniyomi: List<SourceRef> = emptyList(),
    val sourceReposNuvio: List<String> = emptyList(),
    val sourceReposSky: List<String> = emptyList(),
    val sourceReposAniyomi: List<String> = emptyList(),
    val sourceTabStatus: String = "",
    val sourceTabBusy: Boolean = false,
) : UiState
