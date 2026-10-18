package com.codegeasse.hikari.desktop.ui.screens.history

import com.codegeasse.hikari.desktop.data.history.WatchHistoryRepositoryImpl
import com.codegeasse.hikari.desktop.domain.history.interactor.GetContinueWatching
import com.codegeasse.hikari.desktop.domain.history.interactor.RemoveWatchHistory
import com.codegeasse.hikari.desktop.domain.history.repository.WatchHistoryRepository
import com.codegeasse.hikari.desktop.ui.base.BaseMviViewModel
import com.codegeasse.hikari.desktop.ui.screens.history.contract.HistoryUiEffect
import com.codegeasse.hikari.desktop.ui.screens.history.contract.HistoryUiEvent
import com.codegeasse.hikari.desktop.ui.screens.history.contract.HistoryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryViewModel(
    watchHistoryRepo: WatchHistoryRepository = WatchHistoryRepositoryImpl(),
    private val getContinueWatching: GetContinueWatching = GetContinueWatching(watchHistoryRepo),
    private val removeWatchHistory: RemoveWatchHistory = RemoveWatchHistory(watchHistoryRepo),
) : BaseMviViewModel<HistoryUiState, HistoryUiEvent, HistoryUiEffect>(
    initialState = HistoryUiState(),
) {
    init {
        viewModelScope.launch {
            getContinueWatching.subscribe().collect { list ->
                updateState { copy(historyList = list, isLoading = false) }
            }
        }
    }

    override fun handleEvent(event: HistoryUiEvent) {
        when (event) {
            is HistoryUiEvent.ClearAll -> {
                viewModelScope.launch(Dispatchers.IO) {
                    removeWatchHistory.clearAll()
                    sendEffect(HistoryUiEffect.ShowToast("Watch history cleared"))
                }
            }
            is HistoryUiEvent.RemoveItem -> {
                viewModelScope.launch(Dispatchers.IO) {
                    removeWatchHistory.awaitByParent(event.parentId)
                }
            }
        }
    }
}
