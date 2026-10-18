package com.hikari.desktop.domain.plugins.interactor

import com.hikari.desktop.domain.plugins.repository.PluginRepository
import com.hikari.desktop.repo.SitePlugin
import java.io.File

class InstallPlugin(
    private val repository: PluginRepository,
) {
    suspend fun await(repoName: String, plugin: SitePlugin): File? {
        val jar = repository.downloadPlugin(repoName, plugin)
        if (jar != null) {
            repository.incrementSyncGeneration()
        }
        return jar
    }
}
