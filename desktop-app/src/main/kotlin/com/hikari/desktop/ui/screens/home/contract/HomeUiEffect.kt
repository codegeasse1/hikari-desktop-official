package com.hikari.desktop.ui.screens.home.contract

import com.hikari.desktop.ui.base.UiEffect

sealed interface HomeUiEffect : UiEffect {
    data class ShowToast(val message: String) : HomeUiEffect
}
