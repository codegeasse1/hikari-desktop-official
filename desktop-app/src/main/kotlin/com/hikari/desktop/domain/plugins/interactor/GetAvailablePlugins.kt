package com.hikari.desktop.domain.plugins.interactor

import com.hikari.desktop.domain.plugins.repository.PluginRepository
import com.hikari.desktop.repo.SitePlugin

class GetAvailablePlugins(
    private val repository: PluginRepository,
) {
    fun get(): List<Pair<String, SitePlugin>> {
        return repository.getAllPlugins()
    }
}
