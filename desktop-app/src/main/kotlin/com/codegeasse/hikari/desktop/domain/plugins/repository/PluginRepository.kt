package com.codegeasse.hikari.desktop.domain.plugins.repository

import com.codegeasse.hikari.desktop.repo.DesktopRepositoryManager
import com.codegeasse.hikari.desktop.repo.Repository
import com.codegeasse.hikari.desktop.repo.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface PluginRepository {
    val savedRepositories: StateFlow<List<RepositoryData>>
    val remotePluginIcons: StateFlow<Map<String, String>>
    val syncGeneration: StateFlow<Int>

    fun getSavedRepositories(): List<RepositoryData>
    suspend fun addRepository(input: String): List<Repository>?
    suspend fun removeRepository(url: String)
    fun getAllPlugins(): List<Pair<String, SitePlugin>>
    suspend fun syncAll(onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null, force: Boolean = false): DesktopRepositoryManager.SyncReport
    suspend fun downloadPlugin(repoName: String, plugin: SitePlugin): File?
    fun getPluginIcon(providerName: String?): String?
    fun incrementSyncGeneration()
    fun getExtensionsDir(): File
    fun readPluginManifest(jarFile: File): Map<String, Any>?
}
