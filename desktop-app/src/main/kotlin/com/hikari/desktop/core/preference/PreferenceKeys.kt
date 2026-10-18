package com.hikari.desktop.core.preference

/**
 * Centralized registry of preference keys across the desktop client.
 * Prevents magic strings and protects against silent typographical errors.
 */
object PreferenceKeys {
    // Providers & Plugins
    const val USER_PROVIDER_API = "USER_PROVIDER_API"
    const val PREFERRED_PLAYER = "preferred_player"
    const val PREF_ACTIVE_PROVIDERS = "home_active_providers"
    const val PREF_SELECTED_PROVIDER = "preferred_provider_name"
    const val LAST_PLUGIN_SYNC_TIMESTAMP = "last_plugin_sync_timestamp"

    // Hikari sources
    const val PREF_HIKI_ENABLED = "hikari_hiki_enabled"
    const val PREF_NUVIO_REPOS = "hikari_nuvio_repo_urls"
    const val PREF_SKY_REPOS = "hikari_sky_repo_urls"
    const val PREF_ANIYOMI_REPOS = "hikari_aniyomi_repo_urls"
    const val PREF_IPTV_PLAYLIST_URL = "hikari_iptv_playlist_url"
    const val PREF_STREMIO_ADDON_URLS = "hikari_stremio_addon_urls"

    // Search
    const val PREF_SEARCH_HISTORY = "search_history"

    // Details & Episodes View
    const val PREF_EPISODES_STACKED_VIEW = "pref_episodes_stacked_view"
    const val PREF_EPISODES_VIEW_MODE = "pref_episodes_view_mode"
    const val DETAILS_RIGHT_COLUMN_PINNED = "DETAILS_RIGHT_COLUMN_PINNED"

    // Catalogs
    fun disabledCatalogsKey(providerName: String): String = "disabled_catalogs_$providerName"
    fun orderedCatalogsKey(providerName: String): String = "ordered_catalogs_$providerName"
}
