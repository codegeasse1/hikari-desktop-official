@file:OptIn(com.lagradost.cloudstream3.Prerelease::class, com.lagradost.cloudstream3.UnsafeSSL::class, androidx.compose.animation.ExperimentalAnimationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hikari.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.hikari.desktop.init.AppUpdateDialog
import com.hikari.desktop.init.initCoil
import com.hikari.desktop.init.initCrashHandler
import com.hikari.desktop.init.initNetwork
import com.hikari.desktop.init.initPlugins
import com.hikari.desktop.init.initProviders
import com.hikari.desktop.init.initProxy
import com.hikari.desktop.init.initSecurity
import com.hikari.desktop.init.initWindowsEnvironment
import com.hikari.desktop.init.launchAutoUpdater
import com.hikari.desktop.init.launchPeriodicPluginUpdater
import com.hikari.desktop.init.rememberFullscreenHelper
import com.hikari.desktop.init.setupWindowBackgroundAndListeners
import com.hikari.desktop.player.ShaderManager
import com.hikari.desktop.ui.HikariApp
import com.hikari.desktop.ui.components.PreAlphaNoticeDialog
import com.hikari.desktop.ui.LocalFullscreenController
import com.hikari.desktop.ui.navigation.DefaultRootComponent
import com.hikari.desktop.ui.screens.dev.DevStudioState
import com.hikari.desktop.ui.screens.dev.DevStudioView
import com.hikari.common.logging.AppLogger
import com.hikari.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.awt.Toolkit

/**
 * Single unified entry point for Hikari Desktop Client.
 */
fun main(args: Array<String> = emptyArray()) {
    initCrashHandler()
    initWindowsEnvironment()

    val safeModeSentinel = java.io.File(PlatformPaths.appDataDir, ".safe_mode")
    val isSafeModeRequested = args.any { it.equals("--safe-mode", ignoreCase = true) || it.equals("--safe", ignoreCase = true) } ||
        System.getProperty("cloudstream.safe") != null ||
        safeModeSentinel.exists()

    if (isSafeModeRequested) {
        com.hikari.desktop.init.SafeModeState.enable()
        try { safeModeSentinel.delete() } catch (_: Throwable) {}
        AppLogger.w("Running in SAFE MODE: 3rd-party plugins and non-essential startup routines bypassed.")
    }

    val isDevMode = args.any { it.equals("--dev", ignoreCase = true) || it.equals("--dev-logger", ignoreCase = true) } ||
        System.getProperty("cloudstream.dev") != null

    AppLogger.i("Launching Hikari Desktop...")
    AppLogger.i("Platform: ${PlatformPaths.currentOS}")
    AppLogger.i("App data directory: ${PlatformPaths.appDataDir.absolutePath}")

    if (isDevMode) {
        AppLogger.i("Dev Mode enabled via startup arguments.")
        DevStudioState.open(detached = true)
    }

    if (!isSafeModeRequested) {
        ShaderManager.extractBundledShaders()
        com.hikari.desktop.ui.theme.CustomFontManager.extractBundledFonts()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(30_000L)
            com.hikari.desktop.player.webview.NativePlayerBridge.preloadAsync()
        }
    }
    com.hikari.desktop.discord.DiscordRpcManager.init()

    // Initialize SQLite Database, Profiles, AppearanceConfig & MetadataConfig synchronously before Compose starts
    com.hikari.common.storage.DesktopDataStore.init()
    com.hikari.desktop.profile.ProfileManager.init()
    com.hikari.desktop.ui.theme.AppearanceConfig.reloadFromDataStore()
    com.hikari.desktop.metadata.MetadataConfig.reloadFromDataStore()

    // Pre-warm theme presets, search indexes, and custom font cache on background thread
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        com.hikari.desktop.ui.theme.CustomFontManager.getAvailableFonts()
        com.hikari.desktop.ui.theme.BuiltInPresets.presets.size
        com.hikari.desktop.ui.screens.settings.SettingsSearchIndex.searchIndex.size
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            com.hikari.desktop.discord.DiscordRpcManager.shutdown()
        },
    )

    application {
        initCoil()
        if (!com.hikari.desktop.init.SafeModeState.isSafeMode.value) {
            launchPeriodicPluginUpdater()
        }

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val windowWidth = (screenSize.width * 0.7).toInt().coerceAtLeast(1000).dp
        val windowHeight = (screenSize.height * 0.7).toInt().coerceAtLeast(700).dp
        val state = rememberWindowState(
            width = windowWidth,
            height = windowHeight,
            position = WindowPosition.Aligned(Alignment.Center),
            placement = WindowPlacement.Maximized,
        )

        val fullscreenHelper = rememberFullscreenHelper()
        val isDevOpen by DevStudioState.isOpen.collectAsState()
        val isDevDetached by DevStudioState.isDetachedWindow.collectAsState()

        val isPipMode by com.hikari.desktop.ui.PipState.isPipMode.collectAsState()

        Window(
            onCloseRequest = {
                com.hikari.desktop.discord.DiscordRpcManager.shutdown()
                exitApplication()
            },
            title = "Hikari Desktop (Pre-Alpha)",
            state = state,
            icon = painterResource("app_icon_small.png"),
            onKeyEvent = fullscreenHelper.onKeyEvent,
        ) {
            LaunchedEffect(isPipMode) {
                com.hikari.desktop.init.setNativePipMode(window, isPipMode)
            }

            window.minimumSize = if (isPipMode) java.awt.Dimension(280, 180) else java.awt.Dimension(980, 640)
            fullscreenHelper.attachToWindow(window)
            setupWindowBackgroundAndListeners(fullscreenHelper.controller)
            CompositionLocalProvider(
                com.hikari.desktop.ui.LocalWindowState provides state,
                LocalFullscreenController provides fullscreenHelper.controller,
                com.hikari.desktop.ui.LocalComposeWindow provides window,
            ) {
                var isAppReady by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val startupJob = launch(Dispatchers.IO) {
                        val proxyJob = async { initProxy() }

                        // Strict dependency: Security (DataStore, Conscrypt) must init first
                        initSecurity()

                        // Network and Providers can initialize simultaneously
                        val networkJob = async { initNetwork() }
                        val providersJob = async { initProviders() }

                        networkJob.await()
                        providersJob.await()

                        // Plugins require network and providers to be ready
                        initPlugins()

                        // API and Repository init can run simultaneously
                        val repoJob = async { com.hikari.desktop.repo.DesktopRepositoryManager.initialize() }
                        val apiJob = async { com.lagradost.cloudstream3.APIHolder.initAll() }

                        repoJob.await()
                        apiJob.await()
                        proxyJob.await()

                        // Pre-warm settings and appearance classes in background
                        try {
                            Class.forName("com.hikari.desktop.ui.screens.settings.SettingsSession")
                            Class.forName("com.hikari.desktop.ui.screens.settings.AppearanceConfig")
                        } catch (_: Throwable) {}
                    }

                    // Wait for core startup (security, providers, local plugins) to finish before dismissing splash
                    startupJob.join()

                    isAppReady = true

                    // If offline at boot, alert user that offline mode is active
                    val initiallyOnline = com.hikari.desktop.network.NetworkMonitor.isOnline.value
                    if (!initiallyOnline) {
                        com.hikari.desktop.ui.components.AppToastManager.showInfo("Offline Mode: Local library and downloaded content available.")
                    }

                    // Background updater: wait until startupJob finishes completely and defer non-urgent checks
                    // so the user has immediate, smooth zero-I/O responsiveness during startup.
                    launch(Dispatchers.IO) {
                        startupJob.join()
                        kotlinx.coroutines.delay(45_000L)

                        if (initiallyOnline && !com.hikari.desktop.init.SafeModeState.isSafeMode.value) {
                            launchAutoUpdater()
                            com.hikari.desktop.updates.UnifiedUpdateManager.checkAllUpdates()
                            com.hikari.desktop.AppUpdater.checkForUpdates()
                        }

                        // Reactive connection listener: instantly sync as soon as internet is restored
                        var wasOffline = !initiallyOnline
                        com.hikari.desktop.network.NetworkMonitor.isOnline.collect { online ->
                            if (online) {
                                if (wasOffline) {
                                    com.hikari.desktop.ui.components.AppToastManager.showSuccess("Internet connection restored. Synchronizing online content...")
                                    wasOffline = false
                                    launchAutoUpdater()
                                    com.hikari.desktop.updates.UnifiedUpdateManager.checkAllUpdates()
                                    com.hikari.desktop.AppUpdater.checkForUpdates()
                                }
                            } else {
                                wasOffline = true
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Crossfade<Boolean>(
                        targetState = isAppReady,
                        animationSpec = tween(500),
                    ) { ready ->
                        if (ready) {
                            val (root, rootLifecycle) = remember {
                                val lifecycle = LifecycleRegistry()
                                lifecycle.resume() // Start it immediately
                                Pair(DefaultRootComponent(DefaultComponentContext(lifecycle)), lifecycle)
                            }

                            DisposableEffect(rootLifecycle) {
                                onDispose {
                                    rootLifecycle.destroy()
                                }
                            }

                            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                                HikariApp(rootComponent = root)

                                val isSafeMode by com.hikari.desktop.init.SafeModeState.isSafeMode.collectAsState()
                                if (isSafeMode) {
                                    com.hikari.desktop.ui.components.SafeModeRecoveryBanner(
                                        onOpenExtensions = {
                                            root.bringToFront(com.hikari.desktop.ui.navigation.Config.Extensions(initialTab = 1))
                                        },
                                        onDismiss = {
                                            com.hikari.desktop.init.SafeModeState.disable()
                                        },
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                }

                                PreAlphaNoticeDialog()

                                // In-app Docked Dev Studio Overlay
                                if (isDevOpen && !isDevDetached) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(0.50f)
                                            .align(Alignment.BottomCenter),
                                    ) {
                                        DevStudioView(isDetached = false)
                                    }
                                }
                            }
                        } else {
                            com.hikari.desktop.ui.components.AppStartupSplashScreen()
                        }
                    }
                }
            }
        }

        // Secondary Standalone Floating Window for Dev Studio
        if (isDevOpen && isDevDetached) {
            val devWindowState = rememberWindowState(
                width = 1100.dp,
                height = 700.dp,
                position = WindowPosition.Aligned(Alignment.Center),
            )
            Window(
                onCloseRequest = { DevStudioState.close() },
                title = "Hikari Dev Studio & Live LogCat",
                state = devWindowState,
                icon = painterResource("app_icon_small.png"),
            ) {
                DevStudioView(
                    isDetached = true,
                    onClose = { DevStudioState.close() },
                )
            }
        }
    }
}
