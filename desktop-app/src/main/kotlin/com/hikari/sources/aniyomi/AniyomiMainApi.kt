package com.hikari.sources.aniyomi

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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class AniyomiMainApi(
    private val extFile: File,
    private val sourceIndex: Int,
) : MainAPI() {

    private val pkg: String = extFile.nameWithoutExtension
    private val displayName: String = AniyomiManager.labelFor(extFile, sourceIndex)

    override var name: String = displayName
    override var mainUrl: String =
        AniyomiManager.baseUrlFor(extFile, sourceIndex)?.takeIf { it.isNotBlank() }
            ?: ("https://" + pkg)
    override var lang: String = AniyomiManager.langFor(extFile, sourceIndex)
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData> = listOf(
        MainPageData(name = "Popular", data = "popular"),
        MainPageData(name = "Latest", data = "latest"),
    )

    private val animeCache = ConcurrentHashMap<String, SAnime>()
    private val episodeCache = ConcurrentHashMap<String, SEpisode>()
    private val episodesByAnime = ConcurrentHashMap<String, List<Episode>>()

    private fun source(): AnimeSource? =
        AniyomiManager.sourceOf(extFile, sourceIndex)

    private fun toSearchResponse(anime: SAnime): SearchResponse? {
        val animeUrl = runCatching { anime.url }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val title = runCatching { anime.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: animeUrl
        animeCache[animeUrl] = anime
        val poster = runCatching { anime.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() }
        return newTvSeriesSearchResponse(title, encodeAnime(pkg, sourceIndex, animeUrl), TvType.TvSeries, fix = false) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        if (page < 1) return@withContext newHomePageResponse(emptyList(), false)
        val src = source() ?: return@withContext newHomePageResponse(emptyList(), false)
        val paged = runCatching {
            when (request.data) {
                "latest" -> src.getLatestUpdates(page)
                else -> src.getPopularAnime(page)
            }
        }.getOrNull() ?: return@withContext newHomePageResponse(emptyList(), false)
        if (page > 1 && !paged.hasNextPage) return@withContext newHomePageResponse(emptyList(), false)
        val items = paged.animes.take(MAX_ITEMS_PER_ROW).mapNotNull { toSearchResponse(it) }
        newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val src = source() ?: return@withContext emptyList()
        val paged = runCatching {
            src.getSearchAnime(1, query.trim(), AnimeFilterList())
        }.getOrNull() ?: return@withContext emptyList()
        paged.animes.take(MAX_ITEMS_PER_ROW).mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse? = withContext(Dispatchers.IO) {
        val token = decodeToken(url) ?: return@withContext null
        if (token.pkg != pkg || token.index != sourceIndex) return@withContext null
        val src = source() ?: return@withContext null
        val stub = animeCache[token.animeUrl] ?: SAnimeImpl().apply {
            this.url = token.animeUrl
            this.title = token.animeUrl
        }
        val detailed = runCatching {
            src.getAnimeEpisodeUpdate(stub, emptyList(), true, false).anime
        }.getOrNull()
            ?: runCatching { src.getAnimeDetails(stub) }.getOrNull()
            ?: stub
        if (detailed !== stub) animeCache[token.animeUrl] = detailed
        val title = runCatching { detailed.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: token.animeUrl
        val poster = runCatching { detailed.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() }
        val plot = runCatching { detailed.description }.getOrNull()?.takeIf { it.isNotBlank() }
        val genres = runCatching { detailed.getGenres() }.getOrNull().orEmpty()
        val backdrop = runCatching { detailed.background_url }.getOrNull()?.takeIf { it.isNotBlank() }
        val episodes = episodesFor(src, detailed, token.animeUrl)
        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            tags = genres
            backgroundPosterUrl = backdrop
        }
    }

    private suspend fun episodesFor(src: AnimeSource, anime: SAnime, animeUrl: String): List<Episode> {
        episodesByAnime[animeUrl]?.takeIf { it.isNotEmpty() }?.let { return it }
        var raw = fetchEpisodeList(src, anime)
        if (raw.isEmpty()) {
            val detailed = runCatching { src.getAnimeDetails(anime) }.getOrNull()
            if (detailed != null && detailed !== anime) {
                animeCache[animeUrl] = detailed
                raw = fetchEpisodeList(src, detailed)
            }
        }
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Episode>(minOf(raw.size, MAX_EPISODES))
        raw.take(MAX_EPISODES).forEachIndexed { index, ep ->
            val epUrl = runCatching { ep.url }.getOrDefault("")
            if (epUrl.isBlank()) return@forEachIndexed
            episodeCache[epUrl] = ep
            val number = runCatching { ep.episode_number }.getOrDefault(-1f)
            out += newEpisode(encodeEpisode(pkg, sourceIndex, animeUrl, epUrl), {
                name = runCatching { ep.name }.getOrNull()?.takeIf { it.isNotBlank() }
                season = 1
                episode = if (number > 0f) number.toInt().coerceAtLeast(1) else index + 1
                posterUrl = runCatching { ep.preview_url }.getOrNull()?.takeIf { it.isNotBlank() }
            }, false)
        }
        if (out.isNotEmpty()) episodesByAnime[animeUrl] = out
        return out
    }

    private suspend fun fetchEpisodeList(src: AnimeSource, anime: SAnime): List<SEpisode> {
        val attempts: List<suspend () -> List<SEpisode>> = listOf(
            { src.getAnimeEpisodeUpdate(anime, emptyList(), false, true).episodes },
            { src.getEpisodeList(anime) },
            { src.getAnimeEpisodeUpdate(anime, emptyList(), true, true).episodes },
        )
        for (attempt in attempts) {
            val got = runCatching { attempt() }.getOrDefault(emptyList())
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val token = decodeToken(data) ?: return@withContext false
        if (token.pkg != pkg || token.index != sourceIndex) return@withContext false
        val src = source() ?: return@withContext false
        val epUrl: String = token.episodeUrl ?: run {
            val stub = animeCache[token.animeUrl] ?: SAnimeImpl().apply {
                url = token.animeUrl
                title = token.animeUrl
            }
            val eps = episodesFor(src, stub, token.animeUrl)
            if (eps.isEmpty()) return@withContext false
            decodeToken(eps.first().data)?.episodeUrl ?: return@withContext false
        }
        val se = episodeCache[epUrl] ?: SEpisodeImpl().apply {
            url = epUrl
            name = ""
            episode_number = -1f
        }
        val videos = loadVideos(src, se)
        if (videos.isEmpty()) return@withContext false
        var any = false
        for ((hosterName, video) in videos) {
            val raw = video.videoUrl
            if (raw.isBlank() || raw == "null") continue
            for (track in video.subtitleTracks) {
                if (track.url.isBlank()) continue
                runCatching { subtitleCallback(newSubtitleFile(track.lang.ifBlank { "Sub" }, track.url)) }
            }
            val headers = LinkedHashMap<String, String>()
            video.headers?.toMultimap()?.forEach { (k, values) ->
                val v = values.firstOrNull()?.filter { it.code < 128 }.orEmpty()
                if (v.isNotBlank()) headers.putIfAbsent(k, v)
            }
            headers.putIfAbsent("User-Agent", UA)
            val streamUrl = if (raw.startsWith("magnet:", true) || raw.startsWith("torrent:", true) || raw.startsWith("data:")) {
                raw
            } else {
                normalizeDriveUrl(raw)
            }
            val type = videoKind(raw)
            callback(
                newExtractorLink(
                    source = displayName,
                    name = mapVideoName(hosterName, video),
                    url = streamUrl,
                    type = type,
                ) {
                    referer = ""
                    quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            any = true
            if (any && videos.size > MAX_STREAMS) break
        }
        any
    }

    private suspend fun loadVideos(src: AnimeSource, episode: SEpisode): List<Pair<String, Video>> {
        val http = src as? AnimeHttpSource
        if (http == null) {
            val videos = runCatching { src.getVideoList(episode) }.getOrDefault(emptyList())
            return videos.take(MAX_VIDEOS_PER_HOSTER).map { "" to it }
        }
        val hosters: List<Hoster> = if (hasHosters(http)) {
            val list = runCatching { http.getHosterList(episode) }.getOrElse { return emptyList() }
            runCatching { with(http) { list.sortHosters() } }.getOrDefault(list)
        } else {
            val list = runCatching { http.getVideoList(episode) }.getOrElse { return emptyList() }
            val sorted = runCatching { with(http) { list.sortVideos() } }.getOrDefault(list)
            sorted.toHosterList()
        }
        val out = ArrayList<Pair<String, Video>>()
        for (hoster in hosters.take(MAX_HOSTERS)) {
            val fromHoster = hoster.videoList
            val videos = if (fromHoster != null) {
                val parsed = if (fromHoster.any { it.videoUrl == "null" }) parseVideoUrls(http, fromHoster) else fromHoster
                runCatching { with(http) { parsed.sortVideos() } }.getOrDefault(parsed)
            } else {
                val list = runCatching { http.getVideoList(hoster) }.getOrElse { continue }
                val sorted = runCatching { with(http) { list.sortVideos() } }.getOrDefault(list)
                parseVideoUrls(http, sorted)
            }
            val name = hoster.hosterName.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST }.orEmpty()
            for (video in videos.take(MAX_VIDEOS_PER_HOSTER)) {
                val resolved = resolve(http, video) ?: continue
                if (resolved.videoUrl.isBlank() || resolved.videoUrl == "null") continue
                out += name to resolved
                if (out.size >= MAX_STREAMS) return out
            }
        }
        return out
    }

    private suspend fun resolve(http: AnimeHttpSource, video: Video): Video? {
        if (video.initialized) return video
        val resolved = runCatching { http.resolveVideo(video) }.getOrNull()
        val out = resolved ?: return if (video.videoUrl.isNotBlank() && video.videoUrl != "null") video else null
        return runCatching { out.copy(initialized = true) }.getOrDefault(out)
    }

    private suspend fun parseVideoUrls(http: AnimeHttpSource, videos: List<Video>): List<Video> =
        videos.map { video ->
            if (video.videoUrl != "null") return@map video
            val url = runCatching { http.getVideoUrl(video) }.getOrNull()
            if (url.isNullOrBlank()) video else video.copy(videoUrl = url)
        }

    private fun hasHosters(source: AnimeHttpSource): Boolean = runCatching {
        var current: Class<*>? = source.javaClass
        while (current != null) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) return@runCatching false
            if (current.declaredMethods.any {
                    it.name == "getHosterList" || it.name == "hosterListRequest" || it.name == "hosterListParse"
                }
            ) return@runCatching true
            current = current.superclass
        }
        false
    }.getOrDefault(false)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_ITEMS_PER_ROW = 60
        private const val MAX_EPISODES = 2000
        private const val MAX_HOSTERS = 8
        private const val MAX_VIDEOS_PER_HOSTER = 12
        private const val MAX_STREAMS = 60

        data class Token(val pkg: String, val index: Int, val animeUrl: String, val episodeUrl: String?)

        enum class StreamKind { VIDEO, M3U8, DASH, TORRENT }

        internal fun b64enc(s: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

        internal fun b64dec(s: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
        }.getOrNull()

        internal fun encodeAnime(pkg: String, index: Int, animeUrl: String): String =
            "aniyomi://" + pkg + "/" + index + "/" + b64enc(animeUrl)

        internal fun encodeEpisode(pkg: String, index: Int, animeUrl: String, episodeUrl: String): String =
            encodeAnime(pkg, index, animeUrl) + "/e/" + b64enc(episodeUrl)

        internal fun decodeToken(url: String): Token? {
            if (!url.startsWith("aniyomi://")) return null
            val parts = url.removePrefix("aniyomi://").split("/")
            if (parts.size < 3) return null
            val pkg = parts[0].takeIf { it.isNotBlank() } ?: return null
            val index = parts[1].toIntOrNull() ?: return null
            val animeUrl = b64dec(parts[2])?.takeIf { it.isNotBlank() } ?: return null
            val episodeUrl = if (parts.size >= 5 && parts[3] == "e") {
                b64dec(parts[4])?.takeIf { it.isNotBlank() } ?: return null
            } else null
            return Token(pkg, index, animeUrl, episodeUrl)
        }

        internal fun mapVideoName(hosterName: String, video: Video): String {
            val title = video.videoTitle.ifBlank { video.resolution?.let { it.toString() + "p" }.orEmpty() }
            return listOf(hosterName, title).filter { it.isNotBlank() }.joinToString(" - ")
        }

        internal fun videoKind(raw: String): ExtractorLinkType {
            return when {
                raw.startsWith("magnet:", true) || raw.startsWith("torrent:", true) -> ExtractorLinkType.TORRENT
                raw.startsWith("data:application/vnd.apple.mpegurl") || raw.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                raw.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
        }

        internal fun normalizeDriveUrl(url: String): String {
            val u = url.trim().trim('"', '\'')
            if (u.isBlank()) return u
            if (u.contains("drive.google.com/uc") || u.contains("drive.google.com/open")) {
                val id = u.substringAfter("?", "").split("&")
                    .firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
                    ?.takeIf { it.isNotBlank() }
                if (id != null) return "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t"
                return u
            }
            val marker = "/file/d/"
            val at = u.indexOf(marker)
            if (u.contains("drive.google.com") && at >= 0) {
                val id = u.substring(at + marker.length).substringBefore("/").substringBefore("?").substringBefore("#").trim()
                if (id.isNotBlank()) return "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t"
            }
            return u
        }
    }
}
