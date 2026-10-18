package com.codegeasse.hikari.desktop.domain.bookmarks.interactor

import com.codegeasse.hikari.desktop.domain.bookmarks.repository.BookmarksRepository
import com.codegeasse.hikari.common.storage.DesktopBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class GetBookmarks(
    private val repository: BookmarksRepository,
) {
    fun subscribeAll(): StateFlow<Map<String, DesktopBookmark>> {
        return repository.subscribeAll()
    }

    suspend fun awaitAll(): List<DesktopBookmark> = withContext(Dispatchers.IO) {
        repository.getAll()
    }

    suspend fun awaitById(id: String): DesktopBookmark? = withContext(Dispatchers.IO) {
        repository.getById(id)
    }
}
