package com.codegeasse.hikari.desktop.ui.screens.extensions.contract

import com.codegeasse.hikari.desktop.ui.base.UiEffect

sealed interface ExtensionsUiEffect : UiEffect {
    data class ShowNotification(val message: String) : ExtensionsUiEffect

    /** Fired when an uninstalled plugin was the currently active provider. The UI layer clears the stored preference. */
    data class ClearActiveProvider(val removedProviderName: String) : ExtensionsUiEffect
}
