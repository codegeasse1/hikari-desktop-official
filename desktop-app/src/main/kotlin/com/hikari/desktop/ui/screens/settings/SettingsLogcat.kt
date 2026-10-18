package com.hikari.desktop.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hikari.desktop.ui.screens.dev.DevStudioView

@Composable
fun SettingsLogcat() {
    Box(modifier = Modifier.fillMaxSize()) {
        DevStudioView(
            isDetached = false,
            onClose = { /* Keep embedded in settings */ },
        )
    }
}
