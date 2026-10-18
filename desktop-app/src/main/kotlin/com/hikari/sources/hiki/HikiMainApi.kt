package com.hikari.sources.hiki

import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariProvider
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HikiMainApi(
    private val provider: HikariProvider,
) : MainAPI() {

    override var name: String = provider.name
    override var mainUrl: String = provider.mainUrl
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes: Set<TvType> = buildSet {
        if (HikariMediaType.MOVIE in provider.tvTypes) add(TvType.Movie)
        if (HikariMediaType.SERIES in provider.tvTypes) add(TvType.TvSeries)
        if (isEmpty()) {
            add(TvType.Movie)
            add(TvType.TvSeries)
        }
    }

    override val mainPage: List<MainPageData> = runCatching { provider.catalogs() }
        .getOrDefault(emptyList())
        .map { MainPageData(name = it.name, data = it.id) }

    fun mediaRef(media: HikariMedia): String =
        "hiki://" + provider.id + "/" + media.type.name.lowercase() + "/" + media.id

    fun episodeRef(media: HikariMedia, episode: HikariEpisode): String =
        mediaRef(media) + "/ep/" + episode.id

    fun parseMedia(ref: String): HikariMedia? {
        val parts = ref.removePrefix("hiki://").split("/")
        if (parts.size < 3) return null
        if (parts[0] != provider.id) return null
        val type = when (parts[1].lowercase()) {
            "movie" -> HikariMediaType.MOVIE
            "series" -> HikariMediaType.SERIES
            else -> HikariMediaType.UNKNOWN
        }
        val id = parts.subList(2, 3).firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return HikariMedia(id = id, title = id, type = type)
    }

    fun parseEpisodeData(data: String): Pair<HikariMedia, HikariEpisode?> {
        val mediaPart = data.substringBefore("/ep/")
        val media = parseMedia(mediaPart) ?: HikariMedia(id = data, title = data, type = HikariMediaType.UNKNOWN)
        val epId = if (data.contains("/ep/")) data.substringAfter("/ep/").substringBefore("/").takeIf { it.isNotBlank() } else null
        val ep = epId?.let { HikariEpisode(number = 1, id = it) }
        return media to ep
    }

    private fun toSearchResponse(media: HikariMedia): SearchResponse {
        val url = mediaRef(media)
        return if (media.type == HikariMediaType.SERIES) {
            newTvSeriesSearchResponse(media.title.ifBlank { media.id }, url, TvType.TvSeries, fix = false) {
                posterUrl = media.posterUrl
                year = media.year
            }
        } else {
            newMovieSearchResponse(media.title.ifBlank { media.id }, url, TvType.Movie, fix = false) {
                posterUrl = media.posterUrl
                year = media.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalog = runCatching { provider.catalogs() }.getOrDefault(emptyList())
            .firstOrNull { it.id == request.data } ?: return newHomePageResponse(emptyList(), false)
        val items = runCatching { provider.getCatalog(catalog, page) }.getOrDefault(emptyList())
        return newHomePageResponse(HomePageList(request.name, items.map { toSearchResponse(it) }), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        return runCatching { provider.search(query.trim(), 1) }.getOrDefault(emptyList())
            .map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val stub = parseMedia(url) ?: return null
        val meta = runCatching { provider.getMeta(stub) }.getOrNull() ?: stub
        val full = meta.copy(title = meta.title.ifBlank { stub.id })
        return if (full.type == HikariMediaType.SERIES) {
            val episodes = runCatching { provider.getEpisodes(full) }.getOrNull().orEmpty()
            val eps: List<Episode> = episodes.map { e ->
                newEpisode(episodeRef(full, e), { name = e.name; season = e.season; episode = e.number; posterUrl = e.image }, false)
            }
            newTvSeriesLoadResponse(full.title, url, TvType.TvSeries, eps) {
                posterUrl = full.posterUrl
                year = full.year
                plot = full.overview
                tags = full.genres
            }
        } else {
            newMovieLoadResponse(full.title, url, TvType.Movie, mediaRef(full)) {
                posterUrl = full.posterUrl
                year = full.year
                plot = full.overview
                tags = full.genres
            }
        }
    }

    private fun magnetFor(hash: String, trackers: List<String>, name: String): String {
        val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val tr = trackers.filter { it.isNotBlank() }.distinct()
            .joinToString("") { "&tr=" + java.net.URLEncoder.encode(it.trim(), "UTF-8") }
        return "magnet:?xt=urn:btih:" + hash.trim() + tr + "&dn=" + enc
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (media, ep) = parseEpisodeData(data)
        val full = runCatching { provider.getMeta(media) }.getOrNull() ?: media
        val streams = runCatching { provider.getStreams(full, ep) }.getOrDefault(emptyList())
        if (streams.isEmpty()) return false
        var any = false
        for (s in streams) {
            for (sub in s.subtitles) {
                if (sub.url.isBlank()) continue
                runCatching { subtitleCallback(newSubtitleFile(sub.lang.ifBlank { "und" }, sub.url)) }
            }
            val type = when {
                s.isTorrent || !s.infoHash.isNullOrBlank() -> ExtractorLinkType.TORRENT
                s.isM3u8 -> ExtractorLinkType.M3U8
                s.isMpd -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            val hash = s.infoHash?.takeIf { it.isNotBlank() }
            val streamUrl = when {
                !s.url.isBlank() -> s.url
                hash != null -> magnetFor(hash, s.trackers, s.name.ifBlank { full.title })
                !s.ytId.isNullOrBlank() -> "https://www.youtube.com/watch?v=" + s.ytId
                else -> continue
            }
            callback(
                newExtractorLink(
                    source = provider.name,
                    name = s.name.ifBlank { full.title },
                    url = streamUrl,
                    type = type,
                ) {
                    referer = ""
                    quality = Qualities.Unknown.value
                    headers = s.headers
                }
            )
            any = true
        }
        return any
    }
}
