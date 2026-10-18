package com.codegeasse.hikari.desktop.repo

import com.codegeasse.hikari.desktop.di.AppContainerHolder
import com.codegeasse.hikari.desktop.domain.bookmarks.repository.BookmarksRepository as DomainBookmarksRepository
import com.codegeasse.hikari.desktop.utils.appScope
import com.codegeasse.hikari.common.logging.AppLogger
import com.codegeasse.hikari.common.storage.DesktopBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object BookmarksRepository {
    val instance: DomainBookmarksRepository
        get() = AppContainerHolder.container.bookmarksRepository

    val bookmarksFlow: StateFlow<Map<String, DesktopBookmark>>
        get() = instance.subscribeAll()

    fun addBookmark(bookmark: DesktopBookmark) {
        appScope.launch(Dispatchers.IO) {
            try {
                instance.addBookmark(bookmark)
            } catch (e: Exception) {
                AppLogger.e("BookmarksRepository: Failed to add bookmark '${bookmark.name}': ${e.message}")
            }
        }
    }

    fun removeBookmark(id: String) {
        appScope.launch(Dispatchers.IO) {
            try {
                instance.removeBookmark(id)
            } catch (e: Exception) {
                AppLogger.e("BookmarksRepository: Failed to remove bookmark '$id': ${e.message}")
            }
        }
    }
}
