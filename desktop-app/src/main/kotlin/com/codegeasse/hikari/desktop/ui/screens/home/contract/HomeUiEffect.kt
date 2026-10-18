package com.codegeasse.hikari.desktop.ui.screens.home.contract

import com.codegeasse.hikari.desktop.ui.base.UiEffect

sealed interface HomeUiEffect : UiEffect {
    data class ShowToast(val message: String) : HomeUiEffect
}
