package com.hikari.desktop.ui.screens.details.contract

import com.hikari.desktop.ui.base.UiEffect

sealed interface DetailsUiEffect : UiEffect {
    data class ShowToast(val message: String) : DetailsUiEffect
    data class NavigateToPlayer(val launchData: com.hikari.desktop.ui.VideoLaunchData) : DetailsUiEffect
    data class ShowErrorDialog(val message: String) : DetailsUiEffect
}
