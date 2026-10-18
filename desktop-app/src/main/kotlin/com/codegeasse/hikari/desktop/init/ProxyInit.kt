package com.codegeasse.hikari.desktop.init

import com.codegeasse.hikari.player.impl.proxy.LocalStreamProxy

/**
 * Starts the local stream proxy server used for media playback.
 */
fun initProxy() {
    LocalStreamProxy.start()
}
