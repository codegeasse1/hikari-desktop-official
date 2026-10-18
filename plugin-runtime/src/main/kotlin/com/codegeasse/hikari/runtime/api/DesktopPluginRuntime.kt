package com.codegeasse.hikari.runtime.api

import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

class DesktopPluginRuntime : PluginRuntime {
    override fun loadPlugin(file: File): Plugin {
        return com.codegeasse.hikari.runtime.loader.ExtensionLoader.loadJar(file) as Plugin
    }
}
