package com.codegeasse.hikari.desktop.ui.screens.extensions.contract

import com.codegeasse.hikari.desktop.repo.SitePlugin
import com.codegeasse.hikari.desktop.ui.base.UiState
import com.codegeasse.hikari.desktop.ui.screens.extensions.LocalPlugin

data class SourceRef(val id: String, val name: String)

data class SourceEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val iconUrl: String?,
    val tvTypes: List<String>,
    val downloadUrl: String,
    val installed: Boolean,
)

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
    val updatesHistory: List<com.codegeasse.hikari.common.storage.PluginUpdateRecord> = emptyList(),
    val sourceItemsNuvio: List<SourceRef> = emptyList(),
    val sourceItemsSky: List<SourceRef> = emptyList(),
    val sourceItemsHiki: List<SourceRef> = emptyList(),
    val sourceItemsAniyomi: List<SourceRef> = emptyList(),
    val sourceReposNuvio: List<String> = emptyList(),
    val sourceReposSky: List<String> = emptyList(),
    val sourceReposHiki: List<String> = emptyList(),
    val sourceReposAniyomi: List<String> = emptyList(),
    val sourceRepoNames: Map<String, String> = emptyMap(),
    val sourceBrowseNuvio: Map<String, List<SourceEntry>> = emptyMap(),
    val sourceBrowseSky: Map<String, List<SourceEntry>> = emptyMap(),
    val sourceBrowseHiki: Map<String, List<SourceEntry>> = emptyMap(),
    val sourceBrowseAniyomi: Map<String, List<SourceEntry>> = emptyMap(),
    val inspectedSource: Pair<String, String>? = null,
    val sourceBrowseBusy: Boolean = false,
    val sourceTabStatus: String = "",
    val sourceTabBusy: Boolean = false,
) : UiState
