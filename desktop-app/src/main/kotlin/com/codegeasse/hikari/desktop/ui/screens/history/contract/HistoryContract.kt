package com.codegeasse.hikari.desktop.ui.screens.history.contract

import androidx.compose.runtime.Immutable
import com.codegeasse.hikari.desktop.ui.base.UiEffect
import com.codegeasse.hikari.desktop.ui.base.UiEvent
import com.codegeasse.hikari.desktop.ui.base.UiState
import com.codegeasse.hikari.common.storage.WatchHistory

@Immutable
data class HistoryUiState(
    val historyList: List<WatchHistory> = emptyList(),
    val isLoading: Boolean = true,
) : UiState

sealed interface HistoryUiEvent : UiEvent {
    object ClearAll : HistoryUiEvent
    data class RemoveItem(val parentId: String) : HistoryUiEvent
}

sealed interface HistoryUiEffect : UiEffect {
    data class ShowToast(val message: String) : HistoryUiEffect
}
