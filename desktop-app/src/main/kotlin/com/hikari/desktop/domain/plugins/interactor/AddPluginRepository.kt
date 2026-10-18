package com.hikari.desktop.domain.plugins.interactor

import com.hikari.desktop.domain.plugins.repository.PluginRepository
import com.hikari.desktop.repo.Repository

class AddPluginRepository(
    private val repository: PluginRepository,
) {
    suspend fun await(input: String): List<Repository>? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val added = repository.addRepository(trimmed)
        if (!added.isNullOrEmpty()) {
            repository.incrementSyncGeneration()
        }
        return added
    }
}
