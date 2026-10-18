package com.codegeasse.hikari.desktop.ui.screens.details.contract

import com.codegeasse.hikari.desktop.ui.base.UiEffect

sealed interface DetailsUiEffect : UiEffect {
    data class ShowToast(val message: String) : DetailsUiEffect
    data class NavigateToPlayer(val launchData: com.codegeasse.hikari.desktop.ui.VideoLaunchData) : DetailsUiEffect
    data class ShowErrorDialog(val message: String) : DetailsUiEffect
}
