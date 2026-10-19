package com.lagradost.cloudstream3.desktop.player.webview

import com.codegeasse.hikari.common.logging.AppLogger

object NativePlayerBridge {
    init {
        try {
            val resourcesDir = System.getProperty("compose.application.resources.dir")
            val webviewDll = if (resourcesDir != null) java.io.File(resourcesDir, "jni/WebView2Loader.dll") else null
            val playerDll = if (resourcesDir != null) java.io.File(resourcesDir, "jni/player_bridge.dll") else null
            if (webviewDll?.exists() == true && playerDll?.exists() == true) {
                System.load(webviewDll.absolutePath)
                System.load(playerDll.absolutePath)
            } else {
                System.loadLibrary("WebView2Loader")
                System.loadLibrary("player_bridge")
            }
            AppLogger.i("Compat bridge loaded player_bridge native library")
        } catch (e: Throwable) {
            AppLogger.e("Compat bridge failed to load native library: " + e.message)
        }
    }
    external fun initWebView(hostHwnd: Long, width: Int, height: Int): Long
    external fun resizeWebView(width: Int, height: Int)
    external fun setFullscreen(hwnd: Long, fullscreen: Boolean, x: Int, y: Int, width: Int, height: Int)
    external fun setPipSubclass(hwnd: Long, enable: Boolean)
    external fun applyWindowChrome(hwnd: Long, darkMode: Boolean, captionColorRgb: Int, borderColorRgb: Int, textColorRgb: Int)
    external fun destroyWebView()
    external fun focusWebView()
    external fun executeScript(script: String)
    external fun postMessage(json: String)
    external fun notifyThemeChange(isDarkMode: Boolean)
    external fun warmupWebView2(controlsUrl: String?)
    external fun shutdownWebView2Warmup()
    external fun loadUrl(url: String)
    external fun startMpvSync(mpvHandle: Long)
    external fun stopMpvSync()
    external fun openDevTools()
    external fun setEventListener(listener: Any?)
    interface NativePlayerEventListener {
        fun onPlayerEvent(type: String, value: String)
    }
}
