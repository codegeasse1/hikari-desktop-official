package com.codegeasse.hikari.desktop.domain.plugins.interactor

import com.codegeasse.hikari.desktop.domain.plugins.repository.PluginRepository
import com.codegeasse.hikari.desktop.repo.DesktopRepositoryManager

class SyncPluginRepositories(
    private val repository: PluginRepository,
) {
    suspend fun await(onProgress: (suspend (completed: Int, total: Int) -> Unit)? = null): DesktopRepositoryManager.SyncReport {
        val report = repository.syncAll(onProgress, force = true)
        repository.incrementSyncGeneration()
        return report
    }
}
