package com.hikari.desktop.ui.screens.links.contract

import com.hikari.desktop.ui.base.UiEffect

sealed interface LinksUiEffect : UiEffect {
    data class ShowToast(val message: String) : LinksUiEffect
    data class LaunchVlc(
        val link: com.lagradost.cloudstream3.utils.ExtractorLink,
        val displayTitle: String,
        val subtitles: List<String>,
        val startMs: Long,
    ) : LinksUiEffect
    data class LaunchEmbeddedPlayer(
        val launchData: com.hikari.desktop.ui.VideoLaunchData,
    ) : LinksUiEffect
}
