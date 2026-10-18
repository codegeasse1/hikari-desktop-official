package com.hikari.sources.iptv

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * IPTV playlists as a first-class provider (Hikari source).
 *
 * An M3U/M3U8 playlist URL (or a local file path) becomes groups on Home,
 * searchable channels, and direct stream links. [IptvPlaylist] does the
 * parsing (ported 1:1 from Hikari); this class is the MainAPI seam so every
 * screen — Home, Search, Details, Player — works with zero UI changes.
 */
class IptvMainApi(
    private val playlistUrl: String,
) : MainAPI() {

    override var name = "IPTV"
    override var mainUrl = playlistUrl
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        const val PAGE_SIZE = 240
        const val MAX_GROUPS = 24

        @Volatile private var cached: List<IptvChannel>? = null
        @Volatile private var cachedFor: String? = null
        @Volatile private var cachedAt: Long = 0L
        const val TTL_MS = 30 * 60 * 1000L
        private const val MAX_BYTES = 16 * 1024 * 1024

        suspend fun channelsFor(url: String): List<IptvChannel> = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (url == cachedFor && cached != null && now - cachedAt < TTL_MS) return@withContext cached!!
            val text = readSource(url) ?: return@withContext cached?.takeIf { url == cachedFor } ?: emptyList()
            val list = IptvPlaylist.parse(text)
            cached = list
            cachedFor = url
            cachedAt = now
            list
        }

        private fun readSource(url: String): String? {
            val u = url.trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                return runCatching {
                    URL(u).openConnection().apply { connectTimeout = 20_000; readTimeout = 45_000 }.getInputStream().use {
                        it.readBytes().take(MAX_BYTES).toByteArray().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val f = File(u)
            if (!f.exists() || !f.isFile) return null
            return runCatching { f.inputStream().use { it.readBytes().take(MAX_BYTES).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
        }

        fun clearCache() {
            cached = null
            cachedFor = null
            cachedAt = 0L
        }
    }

    private fun channelToResponse(c: IptvChannel): SearchResponse = newLiveSearchResponse(c.name.ifBlank { c.url }, c.url, TvType.Live) {
        posterUrl = c.logo
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = channelsFor(playlistUrl)
        if (channels.isEmpty()) return newHomePageResponse(emptyList(), false)
        val groups = channels.groupBy { it.group.ifBlank { "All channels" } }.entries.sortedByDescending { it.value.size }.take(MAX_GROUPS)
        val start = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val lists = groups.map { (group, items) ->
            val slice = items.drop(start).take(PAGE_SIZE)
            HomePageList(group, slice.map { channelToResponse(it) })
        }
        val hasNext = channels.size > start + PAGE_SIZE
        return newHomePageResponse(lists, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return channelsFor(playlistUrl).filter { it.name.lowercase().contains(q) || it.group.lowercase().contains(q) }.take(200).map { channelToResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = channelsFor(playlistUrl).firstOrNull { it.url == url } ?: return null
        return newLiveStreamLoadResponse(channel.name.ifBlank { channel.url }, url, channel.url) {
            posterUrl = channel.logo
            plot = if (channel.group.isNotBlank()) "Group: " + channel.group else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        val isM3u8 = data.contains(".m3u8", ignoreCase = true)
        callback(
            ExtractorLink(
                source = name,
                name = "IPTV",
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8,
            )
        )
        return true
    }
}
