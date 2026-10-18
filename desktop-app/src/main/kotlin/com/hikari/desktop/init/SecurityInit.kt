package com.hikari.desktop.init

import com.hikari.desktop.DesktopErrorReporter
import com.hikari.common.logging.AppLogger
import com.hikari.common.storage.DesktopDataStore

/**
 * Initializes security-related subsystems:
 * - Uncaught exception handler
 * - BouncyCastle security provider (Android AES-GCM compat)
 * - DataStore pre-initialization (prevents SecurityManager issues)
 */
fun initSecurity() {
    // Uncaught exception handler
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        DesktopErrorReporter.report("Unhandled exception in ${thread.name}", throwable)
        if (thread.name.contains("Plugin", ignoreCase = true) || thread.name.contains("Worker", ignoreCase = true)) {
            com.hikari.desktop.ui.components.AppToastManager.showWarning("⚠️ Background task failed: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    // Conscrypt security provider (BoringSSL - matches Chrome JA3 TLS fingerprint)
    try {
        java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        AppLogger.i("Registered Conscrypt Security Provider (BoringSSL)")
    } catch (e: Exception) {
        AppLogger.e("Failed to register Conscrypt: ${e.message}")
    }

    // BouncyCastle security provider
    java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 2)
    AppLogger.i("Registered BouncyCastle Security Provider")

    // Pre-initialize DataStore & ProfileManager
    DesktopDataStore.init()
    com.hikari.desktop.profile.ProfileManager.init()
    DesktopDataStore.activeProfileProvider = { com.hikari.desktop.profile.ProfileManager.activeProfileId }
    com.hikari.desktop.ui.theme.AppearanceConfig.reloadFromDataStore()
    com.hikari.desktop.metadata.MetadataConfig.reloadFromDataStore()

    // Rhino JavaScript Security ClassShutter (prevents plugins from reflecting/importing Java classes in JS)
    com.hikari.runtime.security.RhinoSecurity.init()
}
