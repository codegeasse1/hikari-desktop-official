package com.codegeasse.hikari.desktop.player.webview

import com.codegeasse.hikari.common.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

object NativePlayerBridge {
    private typealias Compat = com.lagradost.cloudstream3.desktop.player.webview.NativePlayerBridge
    private inline fun <T> safe(default: T, block: () -> T): T {
        return try { block() } catch (_: UnsatisfiedLinkError) { default } catch (_: NoSuchMethodError) { default } catch (_: Throwable) { default }
    }
    private val preloadStarted = AtomicBoolean(false)

    init {
        try {
            val resourcesDir = System.getProperty("compose.application.resources.dir")

            // Try absolute paths first (Release mode / AppImage)
            val webviewDll = if (resourcesDir != null) java.io.File(resourcesDir, "jni/WebView2Loader.dll") else null
            val playerDll = if (resourcesDir != null) java.io.File(resourcesDir, "jni/player_bridge.dll") else null

            if (webviewDll?.exists() == true && playerDll?.exists() == true) {
                System.load(webviewDll.absolutePath)
                System.load(playerDll.absolutePath)
            } else {
                // Fallback to java.library.path (Dev mode)
                System.loadLibrary("WebView2Loader")
                System.loadLibrary("player_bridge")
            }
            AppLogger.i("Successfully loaded player_bridge native library")
        } catch (e: Throwable) {
            AppLogger.e("Failed to load player_bridge native library: ${e.message}")
        }
    }

    /**
     * Initializes the native child window.
     * @param hostHwnd The HWND of the AWT Canvas.
     * @return The HWND of the new child window, or 0 if failed.
     */
    fun initWebView(hostHwnd: Long, width: Int, height: Int): Long = safe(0L) { Compat.initWebView(hostHwnd, width, height) }

    /**
     * Resizes the native child window.
     */
    fun resizeWebView(width: Int, height: Int) = safe(Unit) { Compat.resizeWebView(width, height) }

    /**
     * Enables or disables true borderless fullscreen on the native window.
     * Uses per-window state tracking (thread-safe).
     */
    @JvmStatic
    fun setFullscreen(hwnd: Long, fullscreen: Boolean, x: Int, y: Int, width: Int, height: Int) = safe(Unit) { Compat.setFullscreen(hwnd, fullscreen, x, y, width, height) }

    /**
     * Installs/removes the PiP-only top-level window subclass that blocks
     * WM_DPICHANGED to prevent AWT from resizing the PiP window on monitor change.
     */
    @JvmStatic
    fun setPipSubclass(hwnd: Long, enable: Boolean) = safe(Unit) { Compat.setPipSubclass(hwnd, enable) }

    /**
     * Applies DWM window chrome: dark mode title bar and optional caption/border/text colours.
     * No-op on Windows versions that don't support these DWM attributes.
     */
    @JvmStatic
    fun applyWindowChrome(hwnd: Long, darkMode: Boolean, captionColorRgb: Int, borderColorRgb: Int, textColorRgb: Int) = safe(Unit) { Compat.applyWindowChrome(hwnd, darkMode, captionColorRgb, borderColorRgb, textColorRgb) }

    /**
     * Destroys the native child window.
     */
    fun destroyWebView() = safe(Unit) { Compat.destroyWebView() }

    /**
     * Forces OS focus onto the WebView container so keyboard events route properly.
     */
    fun focusWebView() = safe(Unit) { Compat.focusWebView() }

    /**
     * Sends a JSON state string to the WebView.
     */
    fun executeScript(script: String) = safe(Unit) { Compat.executeScript(script) }

    /**
     * Posts a JSON message directly to the WebView2 control using postWebMessageAsJson.
     */
    fun postMessage(json: String) = safe(Unit) { Compat.postMessage(json) }

    /**
     * Posts a JSON message directly to the WebView2 control using postWebMessageAsJson.
     */
    fun notifyThemeChange(isDarkMode: Boolean) = safe(Unit) { Compat.notifyThemeChange(isDarkMode) }

    /**
     * Initializes an invisible WebView2 instance in the background to warm up Chromium.
     */
    fun warmupWebView2(controlsUrl: String? = null) = safe(Unit) { Compat.warmupWebView2(controlsUrl) }

    /**
     * Shuts down the background warmup thread.
     */
    fun shutdownWebView2Warmup() = safe(Unit) { Compat.shutdownWebView2Warmup() }

    fun loadPlayerUiResource(path: String): String {
        val devFile = java.io.File("desktop-app/src/main/resources$path")
        if (devFile.exists()) {
            val content = runCatching { devFile.readText(Charsets.UTF_8) }.getOrNull()
            if (!content.isNullOrEmpty()) return content
        }
        return NativePlayerBridge::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    /**
     * Asynchronously warms up the WebView2 environment if running on Windows.
     * Prevents the 2-second stutter and unrendered DOM flashes when opening the player.
     */
    fun preloadAsync() {
        if (!preloadStarted.compareAndSet(false, true)) return

        Thread {
            runCatching {
                AppLogger.i("Starting NativePlayerBridge warmup...")
                val webView2DataDir = java.io.File(System.getProperty("java.io.tmpdir"), "HikariWebView2")
                webView2DataDir.mkdirs()
                val tempFile = java.io.File(webView2DataDir, "hikari_controls.html")

                val htmlTemplate = loadPlayerUiResource("/player-ui/player.html")
                val cssContent = loadPlayerUiResource("/player-ui/player.css")
                val jsContent = loadPlayerUiResource("/player-ui/player.js")

                val dynamicFontsCss = com.codegeasse.hikari.desktop.ui.theme.CustomFontManager.getDynamicFontFaceCss()
                val finalCss = if (dynamicFontsCss.isNotEmpty()) "$dynamicFontsCss\n$cssContent" else cssContent

                val htmlContent = htmlTemplate
                    .replace("/* CSS_INJECT */", finalCss)
                    .replace("/* JS_INJECT */", jsContent)
                    .replace("{{ACCENT_COLOR}}", "#7C4DFF")
                    .replace("{{ACCENT_COLOR_RGB}}", "124, 77, 255")
                    .replace("{{INITIAL_BACKDROP_URL}}", "")
                    .replace("{{INITIAL_BACKDROP_CLASS}}", "")
                    .replace("{{INITIAL_LOGO_URL}}", "")
                    .replace("{{INITIAL_LOGO_STYLE}}", "display: none;")
                    .replace("{{INITIAL_TITLE}}", "Hikari")
                    .replace("{{INITIAL_TITLE_STYLE}}", "display: block;")
                    .replace("{{INITIAL_SUBTITLE}}", "")
                    .replace("{{INITIAL_SUBTITLE_STYLE}}", "display: none;")

                if (htmlContent.isNotEmpty()) {
                    tempFile.writeText(htmlContent, Charsets.UTF_8)
                }
                val url = if (tempFile.exists()) tempFile.toURI().toString() else null
                warmupWebView2(url)
            }.onFailure {
                AppLogger.e("Failed to warmup NativePlayerBridge: ${it.message}")
            }
        }.apply {
            name = "hikari-native-player-preload"
            isDaemon = true
            start()
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { shutdownWebView2Warmup() }
            }.apply {
                name = "hikari-webview2-warmup-shutdown"
            },
        )
    }

    /**
     * Navigates the WebView to a specific URL (like file:///...)
     */
    fun loadUrl(url: String) = safe(Unit) { Compat.loadUrl(url) }

    /**
     * Starts a direct C++ sync timer for mpv properties (bypassing Kotlin loop overhead).
     */
    fun startMpvSync(mpvHandle: Long) = safe(Unit) { Compat.startMpvSync(mpvHandle) }

    /**
     * Stops the direct C++ sync timer before destroying the mpv handle to prevent dangling pointer crashes.
     */
    fun stopMpvSync() = safe(Unit) { Compat.stopMpvSync() }

    /**
     * Opens the WebView devtools.
     */
    fun openDevTools() = safe(Unit) { Compat.openDevTools() }

    /**
     * Registers a listener to receive events from the WebView JS bridge.
     */
    fun setEventListener(listener: NativePlayerEventListener?) = safe(Unit) { Compat.setEventListener(listener) }

    interface NativePlayerEventListener {
        fun onPlayerEvent(type: String, value: String)
    }
}
