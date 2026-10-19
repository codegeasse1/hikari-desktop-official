package com.codegeasse.hikari.sources.nuvio

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
import com.codegeasse.hikari.desktop.nuvio.NuvioRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class NuvioMainApi(
    private val scraperFile: File,
) : MainAPI() {

    private val stem: String = scraperFile.nameWithoutExtension
    private val displayName: String = NuvioManager.displayNameFor(scraperFile)

    override var name: String = displayName
    override var mainUrl: String =
        NuvioManager.metaFor(scraperFile)?.optString("sourceUrl")?.takeIf { it.isNotBlank() }
            ?: TmdbApi.API_BASE
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData> =
        rowsFor(displayName, NuvioManager.installedScrapers().map { NuvioManager.displayNameFor(it) })
            .map { MainPageData(name = it.title, data = it.id) }

    private fun rowFor(request: MainPageRequest): RowDef? =
        rowsFor(displayName, NuvioManager.installedScrapers().map { NuvioManager.displayNameFor(it) })
            .firstOrNull { it.id == request.data }

    private fun toSearchResponse(o: JSONObject): SearchResponse? {
        val id = o.optString("id").ifBlank { return null }
        val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { return null }
        val isTv = o.optString("media_type") == "tv" || o.has("name") || o.has("first_air_date")
        val type = if (isTv) "tv" else "movie"
        val year = listOf("release_date", "first_air_date")
            .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull() }
        val url = "nuvio://" + stem + "/" + type + "/" + id
        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                posterUrl = TmdbApi.imageUrl(o.optString("poster_path"))
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = TmdbApi.imageUrl(o.optString("poster_path"))
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val def = rowFor(request) ?: return newHomePageResponse(emptyList(), false)
        val params = LinkedHashMap(def.params)
        params["page"] = page.coerceAtLeast(1).toString()
        val data = TmdbApi.apiGet(def.path, params) ?: return newHomePageResponse(emptyList(), false)
        val arr = data.optJSONArray("results") ?: return newHomePageResponse(emptyList(), false)
        val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { toSearchResponse(it) } }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val data = TmdbApi.apiGet("/search/multi", mapOf("query" to query.trim(), "page" to "1")) ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mt = o.optString("media_type")
            if (mt != "movie" && mt != "tv") return@mapNotNull null
            toSearchResponse(o)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseMediaRef(url) ?: return null
        val type = if (ref.isTv) "tv" else "movie"
        val d = TmdbApi.apiGet("/" + type + "/" + ref.tmdbId, emptyMap())
        if (d == null) {
            return if (!ref.isTv) {
                newMovieLoadResponse("TMDB " + ref.tmdbId, url, TvType.Movie, url) {
                    posterUrl = null
                }
            } else {
                val fb = seriesEpisodes(ref.tmdbId).ifEmpty {
                    listOf(newEpisode("nuvio://" + stem + "/tv/" + ref.tmdbId + "/s/1/e/1", { name = "Episode 1"; season = 1; episode = 1 }, false))
                }
                newTvSeriesLoadResponse("TMDB " + ref.tmdbId, url, TvType.TvSeries, fb) {
                    posterUrl = null
                }
            }
        }
        val title = d.optString("title").ifBlank { d.optString("name") }.ifBlank { ref.tmdbId }
        val year = listOf("release_date", "first_air_date")
            .firstNotNullOfOrNull { k -> d.optString(k).takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull() }
        val poster = TmdbApi.imageUrl(d.optString("poster_path"))
        val backdrop = d.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TmdbApi.IMG_L + it }
        val plot = d.optString("overview").ifBlank { null }
        val genres = (0 until (d.optJSONArray("genres")?.length() ?: 0)).mapNotNull { i ->
            d.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }
        if (!ref.isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                backgroundPosterUrl = backdrop
            }
        }
        val episodes = seriesEpisodes(ref.tmdbId).ifEmpty {
            listOf(newEpisode("nuvio://" + stem + "/tv/" + ref.tmdbId + "/s/1/e/1", { name = "Episode 1"; season = 1; episode = 1 }, false))
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            backgroundPosterUrl = backdrop
        }
    }

    private suspend fun seriesEpisodes(tmdbId: String): List<Episode> = withContext(Dispatchers.IO) {
        val tv = TmdbApi.apiGet("/tv/" + tmdbId, emptyMap()) ?: return@withContext emptyList()
        val seasons = tv.optJSONArray("seasons") ?: return@withContext emptyList()
        val nums = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }.take(24)
        val today = runCatching { LocalDate.now().toString() }.getOrNull() ?: "9999-12-31"
        val out = mutableListOf<Episode>()
        for (sn in nums) {
            val sd = TmdbApi.apiGet("/tv/" + tmdbId + "/season/" + sn, emptyMap()) ?: continue
            val eps = sd.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number")
                if (en <= 0) continue
                val air = e.optString("air_date").trim().takeIf { it.length == 10 }
                if (air != null && air > today) continue
                out += newEpisode("nuvio://" + stem + "/tv/" + tmdbId + "/s/" + sn + "/e/" + en, {
                    name = e.optString("name").takeIf { it.isNotBlank() && it != "null" }
                    season = sn
                    episode = en
                    posterUrl = TmdbApi.imageUrl(e.optString("still_path"))
                }, false)
            }
        }
        out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ep = parseEpisodeData(data) ?: return false
        if (ep.stem != stem) return false
        val source = runCatching { scraperFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return false
        val payload = runCatching {
            NuvioRuntime.getStreams(
                source = source,
                providerId = stem,
                tmdbId = ep.tmdbId,
                mediaType = if (ep.isTv) "tv" else "movie",
                season = ep.season,
                episode = ep.episode ?: 1,
            )
        }.getOrNull() ?: return false
        val parsed = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        if (!parsed.optBoolean("ok", false)) return false
        val arr = parsed.optJSONArray("data") ?: return false
        var any = false
        for (i in 0 until arr.length()) {
            val mapped = mapStreamRow(arr.optJSONObject(i) ?: continue, displayName) ?: continue
            for ((lang, subUrl) in mapped.subtitles) {
                runCatching { subtitleCallback(newSubtitleFile(lang, subUrl)) }
            }
            val streamUrl = when {
                mapped.url.isNotBlank() -> mapped.url
                mapped.infoHash != null -> "magnet:?xt=urn:btih:" + mapped.infoHash.trim() + "&dn=" + java.net.URLEncoder.encode(mapped.name.ifBlank { displayName }, "UTF-8").replace("+", "%20")
                mapped.ytId != null -> "https://www.youtube.com/watch?v=" + mapped.ytId
                else -> continue
            }
            val type = when (mapped.kind) {
                StreamKind.TORRENT -> ExtractorLinkType.TORRENT
                StreamKind.M3U8 -> ExtractorLinkType.M3U8
                StreamKind.DASH -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = displayName,
                    name = mapped.name,
                    url = streamUrl,
                    type = type,
                ) {
                    referer = mapped.headers["referer"] ?: mapped.headers["Referer"] ?: ""
                    quality = qualityValue(mapped.qualityH)
                    headers = mapped.headers
                }
            )
            any = true
        }
        return any
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        data class MediaRef(val stem: String, val isTv: Boolean, val tmdbId: String)
        data class EpisodeData(
            val stem: String,
            val isTv: Boolean,
            val tmdbId: String,
            val season: Int?,
            val episode: Int?,
        )

        enum class StreamKind { VIDEO, M3U8, DASH, TORRENT }

        data class MappedStream(
            val url: String,
            val name: String,
            val kind: StreamKind,
            val headers: Map<String, String>,
            val subtitles: List<Pair<String, String>>,
            val ytId: String?,
            val infoHash: String? = null,
            val qualityH: Int? = null,
        )
        internal fun parseStreamQuality(value: Any?): Int? {
            val s = when (value) {
                is Number -> value.toInt().toString()
                is String -> value
                else -> return null
            }.trim().lowercase()
            if (s.isEmpty() || s == "auto") return 0
            if (s == "4k" || s == "uhd") return 2160
            if (s == "2k" || s == "qhd") return 1440
            val num = Regex("(\\d{3,4})").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (num == 400) return null
            return num
        }
        internal fun qualityValue(height: Int?): Int {
            return when (height) {
                2160 -> com.lagradost.cloudstream3.utils.Qualities.P2160.value
                1440 -> com.lagradost.cloudstream3.utils.Qualities.P1440.value
                1080 -> com.lagradost.cloudstream3.utils.Qualities.P1080.value
                720 -> com.lagradost.cloudstream3.utils.Qualities.P720.value
                480 -> com.lagradost.cloudstream3.utils.Qualities.P480.value
                360 -> com.lagradost.cloudstream3.utils.Qualities.P360.value
                0 -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                else -> height ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            }
        }

        internal data class RowDef(
            val id: String,
            val isTv: Boolean,
            val path: String,
            val params: Map<String, String>,
            val title: String,
        )

        internal enum class Niche { ANIME, HINDI, KDRAMA, FRENCH, PERSIAN, GENERAL }

        private val GENERAL_ROWS = listOf(
            RowDef("g0", false, "/trending/all/week", emptyMap(), "Trending"),
            RowDef("g1", false, "/movie/popular", emptyMap(), "Popular Movies"),
            RowDef("g2", false, "/movie/top_rated", emptyMap(), "Top Rated Movies"),
            RowDef("g3", false, "/movie/now_playing", emptyMap(), "In Cinemas"),
            RowDef("g4", false, "/movie/upcoming", emptyMap(), "Coming Soon"),
            RowDef("g5", true, "/tv/popular", emptyMap(), "Popular Series"),
            RowDef("g6", true, "/tv/top_rated", emptyMap(), "Top Rated Series"),
            RowDef("g7", true, "/tv/airing_today", emptyMap(), "Airing Today"),
            RowDef("g8", true, "/tv/on_the_air", emptyMap(), "On TV"),
        )

        private val ANIME_ROWS = listOf(
            RowDef("a0", true, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime"),
            RowDef("a1", true, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime"),
            RowDef("a2", true, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "first_air_date.desc"), "New Anime"),
            RowDef("a3", false, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime Movies"),
            RowDef("a4", false, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime Movies"),
            RowDef("a5", false, "/discover/movie", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Movies"),
            RowDef("a6", true, "/discover/tv", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Series"),
        )

        private val HINDI_ROWS = listOf(
            RowDef("h0", false, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Movies"),
            RowDef("h1", true, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Series"),
            RowDef("h2", false, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Movies"),
            RowDef("h3", true, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "first_air_date.desc"), "New Hindi Series"),
        )

        private val KDRAMA_ROWS = listOf(
            RowDef("k0", true, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Dramas"),
            RowDef("k1", true, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Korean Dramas"),
            RowDef("k2", true, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "first_air_date.desc"), "New Korean Dramas"),
        )

        private val FRENCH_ROWS = listOf(
            RowDef("f0", false, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Movies"),
            RowDef("f1", true, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Series"),
            RowDef("f2", false, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Movies"),
        )

        private val PERSIAN_ROWS = listOf(
            RowDef("p0", false, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Movies"),
            RowDef("p1", true, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Series"),
            RowDef("p2", false, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Persian Movies"),
        )

        internal fun nicheOf(name: String): Niche {
            val n = name.lowercase()
            return when {
                listOf("anime", "hianime", "anidb", "anikoto", "kurage", "cartoon").any { n.contains(it) } -> Niche.ANIME
                listOf("desi", "hindi", "hindmoviez", "einthusan", "hdhub", "moviebox", "vegamovies", "bollywood").any { n.contains(it) } -> Niche.HINDI
                listOf("kdrama", "kisskh").any { n.contains(it) } -> Niche.KDRAMA
                listOf("movix", "wiflix", "vostfr", "frenchstream", "papadustream").any { n.contains(it) } -> Niche.FRENCH
                n.contains("persian") -> Niche.PERSIAN
                else -> Niche.GENERAL
            }
        }

        internal fun window(pool: List<RowDef>, take: Int, offset: Int): List<RowDef> {
            if (pool.isEmpty()) return emptyList()
            val start = (offset % pool.size + pool.size) % pool.size
            return List(take) { i -> pool[(start + i) % pool.size] }
        }

        internal fun rowsFor(name: String, allNames: List<String>): List<RowDef> {
            val niche = nicheOf(name)
            val ordinal = allNames.filter { nicheOf(it) == niche }.sortedBy { it.lowercase() }.indexOfFirst { it == name }
            val offset = if (ordinal >= 0) ordinal else (name.hashCode() and 0x7fffffff)
            return when (niche) {
                Niche.GENERAL -> window(GENERAL_ROWS, 5, offset)
                Niche.ANIME -> window(ANIME_ROWS, 4, offset)
                Niche.HINDI -> window(HINDI_ROWS, 3, offset)
                Niche.KDRAMA -> window(KDRAMA_ROWS, 3, offset)
                Niche.FRENCH -> window(FRENCH_ROWS, 3, offset)
                Niche.PERSIAN -> window(PERSIAN_ROWS, 3, offset)
            }
        }

        internal fun parseMediaRef(ref: String): MediaRef? {
            val norm = if (ref.contains("nuvio://")) ref.substring(ref.lastIndexOf("nuvio://")) else ref
            val parts = norm.removePrefix("nuvio://").split("/")
            if (parts.size != 3) return null
            val type = parts[1].lowercase()
            if (type != "movie" && type != "tv") return null
            val id = parts[2].takeIf { it.isNotBlank() } ?: return null
            return MediaRef(parts[0], type == "tv", id)
        }

        internal fun parseEpisodeData(data: String): EpisodeData? {
            val norm = if (data.contains("nuvio://")) data.substring(data.lastIndexOf("nuvio://")) else data
            val mediaPart = norm.substringBefore("/s/")
            val ref = parseMediaRef(mediaPart) ?: return null
            if (!norm.contains("/s/")) return EpisodeData(ref.stem, ref.isTv, ref.tmdbId, null, null)
            val rest = norm.substringAfter("/s/").split("/")
            if (rest.size != 3 || rest[1] != "e") return null
            val season = rest[0].toIntOrNull() ?: return null
            val episode = rest[2].toIntOrNull() ?: return null
            return EpisodeData(ref.stem, ref.isTv, ref.tmdbId, season, episode)
        }

        internal fun mapStreamRow(s: JSONObject, providerName: String): MappedStream? {
            val nested = s.optJSONObject("url")
            val client = s.optJSONObject("clientResolve")
            var url = if (nested != null) nested.optString("url") else s.optString("url")
            if (url.isBlank()) url = client?.optString("url").orEmpty()
            var headers = (if (nested != null) nested.optJSONObject("headers") else null)
                ?: s.optJSONObject("headers")
            val ytId = s.optString("ytId").ifBlank { client?.optString("ytId")?.ifBlank { null } }
            val external = s.optString("externalUrl").ifBlank { client?.optString("externalUrl").orEmpty() }
            val infoHash = s.optString("infoHash").ifBlank { null }
            if (url.isBlank() && external.isBlank() && ytId == null && infoHash == null) return null
            val finalUrl = url.ifBlank { external }
            if (isGarbageUrl(finalUrl) || isGarbageUrl(external)) return null
            val label = s.optString("name").ifBlank { s.optString("title") }.ifBlank { s.optString("source") }.ifBlank { providerName }
            val quality = s.optString("quality")
            val displayName = if (quality.isNotBlank() && !label.contains(quality, true)) label + " " + quality else label
            val h = LinkedHashMap<String, String>()
            fun putHeaders(obj: JSONObject?) {
                if (obj == null) return
                obj.keys().forEach { k ->
                    val v = obj.optString(k).filter { it.code < 128 }
                    if (v.isNotBlank()) h[k] = v
                }
            }
            val hints = s.optJSONObject("behaviorHints")
            val proxy = hints?.optJSONObject("proxyHeaders")
            putHeaders(proxy?.optJSONObject("request"))
            if (proxy != null) {
                proxy.keys().forEach { k ->
                    if (k == "request") return@forEach
                    val v = proxy.opt(k)
                    if (v is String && v.isNotBlank()) h.putIfAbsent(k, v.filter { it.code < 128 })
                }
            }
            putHeaders(hints?.optJSONObject("headers"))
            putHeaders(headers)
            h.putIfAbsent("User-Agent", UA)
            val subs = mutableListOf<Pair<String, String>>()
            val subArr = runCatching { s.getJSONArray("subtitles") }.getOrNull()
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val st = subArr.optJSONObject(i) ?: continue
                    val su = st.optString("url")
                    if (su.isBlank()) continue
                    subs += (st.optString("lang").ifBlank { st.optString("language") }.ifBlank { st.optString("label") }.ifBlank { "Sub" } to su)
                }
            }
            val typeStr = s.optString("type").lowercase()
            val isTorrent = s.optBoolean("isTorrent", false) || infoHash != null ||
                finalUrl.startsWith("magnet:", true) || finalUrl.startsWith("torrent:", true)
            val kind = when {
                isTorrent -> StreamKind.TORRENT
                s.optBoolean("isM3u8", false) || typeStr == "m3u8" || typeStr == "hls" || finalUrl.contains(".m3u8", true) -> StreamKind.M3U8
                s.optBoolean("isMpd", false) || typeStr == "mpd" || typeStr == "dash" || finalUrl.contains(".mpd", true) -> StreamKind.DASH
                else -> StreamKind.VIDEO
            }
            return MappedStream(finalUrl, displayName, kind, h, subs, ytId, infoHash, parseStreamQuality(s.opt("quality")))
        }

        internal fun isGarbageUrl(url: String): Boolean {
            if (url.isBlank()) return false
            if (url.contains("w3.org/2000/svg", ignoreCase = true)) return true
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
            return host == "w3.org" || host.endsWith(".w3.org")
        }

        internal fun normalizeDriveUrl(url: String): String {
            val u = url.trim().trim('"', '\'')
            if (u.isBlank()) return u
            val id = Regex("""drive\.google\.com/(?:uc|open)\?(?:.*&)?id=([^&\s"']+)""").find(u)?.groupValues?.get(1)
                ?: Regex("""drive\.google\.com/file/d/([^/\s"']+)""").find(u)?.groupValues?.get(1)
            return if (id != null) "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t" else u
        }
    }
}
