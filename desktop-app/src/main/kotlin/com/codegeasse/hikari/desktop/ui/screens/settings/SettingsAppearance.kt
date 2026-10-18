package com.codegeasse.hikari.desktop.ui.screens.settings

import androidx.compose.runtime.Composable
import com.codegeasse.hikari.desktop.ui.screens.settings.appearance.*

@Composable
fun SettingsAppearanceScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsAppearanceScreen(onNavigateToSubScreen)

@Composable
fun SettingsNavDockScreen() =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsNavDockScreen()

@Composable
fun SettingsThemeWallpaperScreen() =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsThemeWallpaperScreen()

@Composable
fun SettingsPostersBadgesScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsPostersBadgesScreen(onNavigateToSubScreen)

@Composable
fun SettingsHomeFeedScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit = {}) =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsHomeFeedScreen(onNavigateToSubScreen)

@Composable
fun SettingsDetailsSectionsScreen() =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsDetailsSectionsScreen()

@Composable
fun SettingsPosterEditorScreen(onBack: () -> Unit = {}) =
    com.codegeasse.hikari.desktop.ui.screens.settings.appearance.SettingsPosterEditorScreen(onBack)

// Backward compatibility aliases
@Composable
fun SettingsAppearanceThemeScreen() = SettingsThemeWallpaperScreen()

@Composable
fun SettingsAppearanceLayoutScreen(onNavigateToSubScreen: (SettingsSubScreen) -> Unit) = SettingsNavDockScreen()

@Composable
fun SettingsAppearanceEffectsScreen() = SettingsHomeFeedScreen()
