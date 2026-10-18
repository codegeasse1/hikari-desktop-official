package com.codegeasse.hikari.desktop.ui.screens.library.contract

import com.codegeasse.hikari.desktop.ui.base.UiEffect
import com.codegeasse.hikari.desktop.ui.navigation.Config

sealed interface LibraryUiEffect : UiEffect {
    data class Navigate(val screen: Config) : LibraryUiEffect
}
