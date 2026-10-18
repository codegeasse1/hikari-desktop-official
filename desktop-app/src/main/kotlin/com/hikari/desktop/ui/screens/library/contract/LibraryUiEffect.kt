package com.hikari.desktop.ui.screens.library.contract

import com.hikari.desktop.ui.base.UiEffect
import com.hikari.desktop.ui.navigation.Config

sealed interface LibraryUiEffect : UiEffect {
    data class Navigate(val screen: Config) : LibraryUiEffect
}
