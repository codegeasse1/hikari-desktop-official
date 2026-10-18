package com.codegeasse.hikari.desktop.domain.player.interactor

import com.codegeasse.hikari.desktop.data.history.WatchHistoryRepositoryImpl
import com.codegeasse.hikari.desktop.domain.history.interactor.UpsertWatchHistory
import com.codegeasse.hikari.desktop.domain.history.repository.WatchHistoryRepository
import com.codegeasse.hikari.common.storage.WatchHistory

class SavePlaybackProgress(
    private val watchHistoryRepo: WatchHistoryRepository = WatchHistoryRepositoryImpl(),
    private val upsertWatchHistory: UpsertWatchHistory = UpsertWatchHistory(watchHistoryRepo),
) {
    suspend fun await(history: WatchHistory, forceNotify: Boolean = false) {
        upsertWatchHistory.await(history, forceNotify = forceNotify)
    }
}
