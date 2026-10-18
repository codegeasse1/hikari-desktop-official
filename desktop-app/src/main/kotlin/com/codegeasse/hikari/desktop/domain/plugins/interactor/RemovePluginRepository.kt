package com.codegeasse.hikari.desktop.domain.plugins.interactor

import com.codegeasse.hikari.desktop.domain.plugins.repository.PluginRepository

class RemovePluginRepository(
    private val repository: PluginRepository,
) {
    suspend fun await(url: String) {
        repository.removeRepository(url)
        repository.incrementSyncGeneration()
    }
}
