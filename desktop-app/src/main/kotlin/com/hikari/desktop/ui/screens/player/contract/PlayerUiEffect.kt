package com.hikari.desktop.ui.screens.player.contract

import com.hikari.desktop.ui.base.UiEffect

sealed interface PlayerUiEffect : UiEffect {
    data class ShowToast(val message: String) : PlayerUiEffect
    data object ClosePlayer : PlayerUiEffect
    data class ShowError(val message: String) : PlayerUiEffect
}
