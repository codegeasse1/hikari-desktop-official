package com.codegeasse.hikari.sources.sky

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
import com.codegeasse.hikari.desktop.skystream.SkyStreamRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SkyStreamMainApi(
    private val pluginDir: File,
) : MainAPI() {

    private val packageName: String = pluginDir.name
    private val scriptFile: File = File(pluginDir, "plugin.js")
    private val displayName: String = SkyStreamManager.displayNameFor(pluginDir)

    override var name: String = displayName
    override var mainUrl: String =
        SkyStreamManager.manifestOf(pluginDir)?.optString("baseUrl")
            ?.takeIf { it.isNotBlank() } ?: ("https://" + packageName)
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData> =
        readCatsCache().ifEmpty { listOf("Home") }.map { MainPageData(name = it, data = "cat:" + it) }

    private val lock = Mutex()

    private class Home(val at: Long, val categories: List<Pair<String, JSONArray>>)

    @Volatile
    private var homeCache: Home? = null

    private val loadedMeta = ConcurrentHashMap<String, JSONObject>()
    private val loadedEpisodes = ConcurrentHashMap<String, List<Episode>>()
    private val instantStreams = ConcurrentHashMap<String, List<MappedStream>>()

    private fun readCatsCache(): List<String> {
        val f = File(pluginDir, "cats.json")
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.getOrNull() ?: emptyList()
    }

    private fun writeCatsCache(names: List<String>) {
        runCatching {
            File(pluginDir, "cats.json").writeText(org.json.JSONArray(names).toString())
        }
    }

    private suspend fun invoke(fn: String, args: List<String>): JSONObject? = withContext(Dispatchers.IO) {
        if (!scriptFile.exists()) return@withContext null
        val argsJson = JSONArray().apply { args.forEach { put(it) } }.toString()
        val payload = runCatching { SkyStreamRuntime.invoke(packageName, scriptFile, fn, argsJson) }.getOrNull()
        runCatching { payload?.let { JSONObject(it) } }.getOrNull()
    }

    private suspend fun home(): Home? {
        val cached = homeCache
        if (cached != null && System.currentTimeMillis() - cached.at < HOME_TTL_MS) return cached
        return lock.withLock {
            val again = homeCache
            if (again != null && System.currentTimeMillis() - again.at < HOME_TTL_MS) return@withLock again
            val res = invoke("getHome", emptyList()) ?: return@withLock null
            if (!res.optBoolean("ok", false)) return@withLock null
            val data = res.opt("data")
            val cats = mutableListOf<Pair<String, JSONArray>>()
            if (data is JSONObject) {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = data.opt(k)
                    if (v is JSONArray && v.length() > 0) cats += (k to v)
                }
            } else if (data is JSONArray) {
                if (data.length() > 0) cats += ("Home" to data)
            }
            if (cats.isEmpty()) return@withLock null
            writeCatsCache(cats.map { it.first })
            Home(System.currentTimeMillis(), cats).also { homeCache = it }
        }
    }

    private fun toSearchResponse(o: JSONObject): SearchResponse? {
        val item = toItem(packageName, o) ?: return null
        return if (item.isTv) {
            newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        } else {
            newMovieSearchResponse(item.title, item.url, TvType.Movie, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val home = home() ?: return newHomePageResponse(emptyList(), false)
        val wanted = request.data.removePrefix("cat:")
        val arr = if (wanted == "Home") {
            home.categories.firstOrNull { it.first == "Home" }?.second
                ?: home.categories.firstOrNull()?.second
        } else {
            home.categories.firstOrNull { it.first == wanted }?.second
        } ?: return newHomePageResponse(emptyList(), false)
        val items = (0 until minOf(arr.length(), MAX_ITEMS_PER_ROW))
            .mapNotNull { i -> arr.optJSONObject(i)?.let { toSearchResponse(it) } }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return emptyList()
        val res = invoke("search", listOf(query.trim())) ?: return emptyList()
        if (!res.optBoolean("ok", false)) return emptyList()
        val arr = when (val data = res.opt("data")) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("results") ?: data.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        return (0 until minOf(arr.length(), MAX_ITEMS_PER_ROW))
            .mapNotNull { i -> arr.optJSONObject(i)?.let { toSearchResponse(it) } }
    }

    override suspend fun load(url: String): LoadResponse? {
        val token = decodeToken(url) ?: return null
        if (token.pkg != packageName) return null
        val cached = loadedMeta[token.token]
        val d: JSONObject = cached ?: run {
            val res = invoke("load", listOf(token.token)) ?: return null
            if (!res.optBoolean("ok", false)) return null
            (res.opt("data") as? JSONObject) ?: return null
        }
        loadedMeta[token.token] = d
        runCatching {
            val ds = d.optJSONArray("streams")
            if (ds != null && ds.length() > 0) {
                val mapped = mapStreams(packageName, displayName, ds)
                if (mapped.isNotEmpty()) instantStreams[token.token] = mapped
            }
        }
        val episodes = toEpisodes(packageName, d.optJSONArray("episodes"))
        if (episodes.isNotEmpty()) loadedEpisodes[token.token] = episodes
        val title = d.optString("title").ifBlank { token.token }
        val poster = d.optString("posterUrl").ifBlank { null }
        val year = d.optInt("year").takeIf { it > 1800 }
        val plot = d.optString("description").ifBlank { null }
        val backdrop = d.optString("bannerUrl").ifBlank { null }
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                backgroundPosterUrl = backdrop
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                backgroundPosterUrl = backdrop
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val token = decodeToken(data) ?: return false
        if (token.pkg != packageName) return false
        val cached = instantStreams[token.token]?.takeIf { it.isNotEmpty() }
        val streams: List<MappedStream> = cached ?: run {
            val res = invoke("loadStreams", listOf(token.token)) ?: return false
            if (!res.optBoolean("ok", false)) return false
            val arr = when (val v = res.opt("data")) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("streams") ?: v.optJSONArray("sources")
                else -> null
            } ?: return false
            val mapped = mapStreams(packageName, displayName, arr)
            if (mapped.isEmpty()) return false
            instantStreams[token.token] = mapped
            mapped
        }
        var any = false
        for (s in streams) {
            for ((lang, subUrl) in s.subtitles) {
                runCatching { subtitleCallback(newSubtitleFile(lang, subUrl)) }
            }
            val type = when (s.kind) {
                StreamKind.TORRENT -> ExtractorLinkType.TORRENT
                StreamKind.M3U8 -> ExtractorLinkType.M3U8
                StreamKind.DASH -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = displayName,
                    name = s.name,
                    url = s.url,
                    type = type,
                ) {
                    referer = s.headers["referer"] ?: s.headers["Referer"] ?: ""
                    quality = qualityValue(s.qualityH)
                    headers = s.headers
                }
            )
            any = true
        }
        return any
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_ITEMS_PER_ROW = 80
        private const val MAX_EPISODES = 800
        private const val MAX_STREAMS = 60
        private const val HOME_TTL_MS = 8 * 60 * 1000L

        data class Token(val pkg: String, val token: String)

        enum class StreamKind { VIDEO, M3U8, DASH, TORRENT }

        data class MappedStream(
            val url: String,
            val name: String,
            val kind: StreamKind,
            val headers: Map<String, String>,
            val subtitles: List<Pair<String, String>>,
            val qualityH: Int? = null,
        )

        internal data class SkyItem(
            val url: String,
            val title: String,
            val isTv: Boolean,
            val poster: String?,
            val year: Int?,
        )

        internal fun encodeToken(pkg: String, token: String): String =
            "sky://" + pkg + "/" + URLEncoder.encode(token, "UTF-8").replace("+", "%20")

        internal fun decodeToken(url: String): Token? {
            val norm = if (url.contains("sky://")) url.substring(url.lastIndexOf("sky://")) else url
            if (!norm.startsWith("sky://")) return null
            val rest = norm.removePrefix("sky://")
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val pkg = rest.substring(0, slash)
            val token = runCatching { java.net.URLDecoder.decode(rest.substring(slash + 1), "UTF-8") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return null
            if (pkg.isBlank()) return null
            return Token(pkg, token)
        }

        internal fun toItem(pkg: String, o: JSONObject): SkyItem? {
            val token = o.optString("url").ifBlank { o.optString("link") }.ifBlank { o.optString("file") }
            val rawTitle = o.optString("title").ifBlank { o.optString("name") }
            if (token.isBlank() || rawTitle.isBlank()) return null
            val title = htmlUnescape(rawTitle)
            val rawType = o.optString("type").ifBlank { o.optString("contentType") }.ifBlank { "movie" }
            val isTv = when (rawType.lowercase()) {
                "series", "anime", "tv", "tvseries", "tvshow" -> true
                else -> false
            }
            return SkyItem(
                url = encodeToken(pkg, token),
                title = title,
                isTv = isTv,
                poster = o.optString("posterUrl").ifBlank { o.optString("poster") }.ifBlank { o.optString("image") }.ifBlank { o.optString("thumbnail") }.ifBlank { o.optString("bannerUrl") }.ifBlank { null },
                year = o.optInt("year").takeIf { it > 1800 } ?: o.optString("year").take(4).toIntOrNull()?.takeIf { it > 1800 },
            )
        }
        internal fun htmlUnescape(s: String): String {
            return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        }
        internal fun parseStreamQuality(value: Any?): Int? {
            val str = when (value) {
                is Number -> value.toInt().toString()
                is String -> value
                else -> return null
            }.trim().lowercase()
            if (str.isEmpty() || str == "auto") return 0
            if (str == "4k" || str == "uhd") return 2160
            if (str == "2k" || str == "qhd") return 1440
            val num = Regex("(\\d{3,4})").find(str)?.groupValues?.get(1)?.toIntOrNull() ?: return null
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

        internal fun intOrNull(o: JSONObject, vararg keys: String): Int? {
            for (k in keys) {
                val v = o.opt(k) ?: continue
                when (v) {
                    is Number -> { val n = v.toInt(); if (n > 0) return n }
                    is String -> { val n = v.take(6).toIntOrNull(); if (n != null && n > 0) return n }
                }
            }
            return null
        }
        internal fun MainAPI.toEpisodes(pkg: String, arr: JSONArray?): List<Episode> {
            arr ?: return emptyList()
            val out = mutableListOf<Episode>()
            for (i in 0 until minOf(arr.length(), MAX_EPISODES)) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").ifBlank { o.optString("link") }.ifBlank { o.optString("file") }
                if (url.isBlank()) continue
                val season = intOrNull(o, "season") ?: 1
                val number = intOrNull(o, "episode", "number") ?: (i + 1)
                out += newEpisode(encodeToken(pkg, url), {
                    name = htmlUnescape(o.optString("name").ifBlank { o.optString("title") }).ifBlank { null }
                    this.season = season
                    episode = number
                    posterUrl = o.optString("image").ifBlank { o.optString("posterUrl") }.ifBlank { o.optString("poster") }.ifBlank { o.optString("thumbnail") }.ifBlank { null }
                }, false)
            }
            return out
        }

        internal fun mapStreams(pkg: String, displayName: String, arr: JSONArray?): List<MappedStream> {
            arr ?: return emptyList()
            val out = mutableListOf<MappedStream>()
            for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
                val o = arr.optJSONObject(i) ?: continue
                toStreamSource(displayName, o)?.let { out.add(it) }
            }
            return out
        }

        internal fun toStreamSource(displayName: String, o: JSONObject): MappedStream? {
            var url = o.optString("url").ifBlank { o.optString("file") }.ifBlank { o.optString("link") }
            if (url.isBlank()) return null
            val headers = LinkedHashMap<String, String>()
            url = decodeMagic(url, headers)
            val source = o.optString("source").ifBlank { o.optString("providerName") }.ifBlank { o.optString("name") }
            val qualityRaw: Any? = if (o.isNull("quality")) null else o.opt("quality")
            val qualityStr = qualityRaw?.toString().orEmpty()
            val base = source.ifBlank { displayName }
            val name = if (qualityStr.isNotBlank() && !base.contains(qualityStr, true)) base + " " + qualityStr else base
            val providerHeaders = o.optJSONObject("headers")
            providerHeaders?.keys()?.forEach { k ->
                val v = providerHeaders.optString(k).filter { it.code < 128 }
                if (v.isNotBlank()) headers.putIfAbsent(k, v)
            }
            headers.putIfAbsent("User-Agent", UA)
            val subs = mutableListOf<Pair<String, String>>()
            val subArr = o.optJSONArray("subtitles")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val st = subArr.optJSONObject(i) ?: continue
                    val su = st.optString("url")
                    if (su.isBlank()) continue
                    subs += (st.optString("lang").ifBlank { st.optString("language") }.ifBlank { st.optString("label") }.ifBlank { "Sub" } to su)
                }
            }
            val typeStr = o.optString("type").lowercase()
            val isTorrent = url.startsWith("magnet:", true) || url.startsWith("torrent:", true)
            val kind = when {
                isTorrent -> StreamKind.TORRENT
                typeStr == "m3u8" || typeStr == "hls" || url.startsWith("data:application/vnd.apple.mpegurl") || url.contains(".m3u8", true) -> StreamKind.M3U8
                typeStr == "mpd" || typeStr == "dash" || url.contains(".mpd", true) -> StreamKind.DASH
                else -> StreamKind.VIDEO
            }
            val finalUrl = if (isTorrent || url.startsWith("data:")) url else normalizeDriveUrl(url)
            return MappedStream(finalUrl, name, kind, headers, subs, parseStreamQuality(qualityRaw))
        }

        internal fun decodeMagic(raw: String, headers: MutableMap<String, String>): String {
            if (raw.startsWith("magic_m3u8:")) {
                return "data:application/vnd.apple.mpegurl;base64," + raw.removePrefix("magic_m3u8:").trim()
            }
            if (raw.startsWith("MAGIC_PROXY_v2")) {
                val json = base64Json(raw.substring("MAGIC_PROXY_v2".length)) ?: return raw
                json.optJSONObject("headers")?.keys()?.forEach { k ->
                    val v = json.optJSONObject("headers")?.optString(k) ?: return@forEach
                    if (v.isNotBlank()) headers.putIfAbsent(k, v)
                }
                return json.optString("url").takeIf { it.startsWith("http") } ?: raw
            }
            if (raw.startsWith("MAGIC_PROXY_v1") || raw.startsWith("MAGIC_PROXY:")) {
                val isV1 = raw.startsWith("MAGIC_PROXY_v1")
                val b64 = raw.substring(if (isV1) "MAGIC_PROXY_v1".length else "MAGIC_PROXY:".length)
                return base64Text(b64)?.takeIf { it.startsWith("http") } ?: raw
            }
            return raw
        }

        internal fun base64Text(b64: String): String? = runCatching {
            val clean = b64.trim()
            val bytes = runCatching { java.util.Base64.getDecoder().decode(clean) }.getOrElse {
                java.util.Base64.getUrlDecoder().decode(clean)
            }
            String(bytes, Charsets.UTF_8)
        }.getOrNull()

        internal fun base64Json(b64: String): JSONObject? =
            base64Text(b64)?.let { runCatching { JSONObject(it) }.getOrNull() }

        internal fun normalizeDriveUrl(url: String): String {
            val u = url.trim().trim('"', '\'')
            if (u.isBlank()) return u
            val id = Regex("""drive\.google\\.com/(?:uc|open)\?(?:.*&)?id=([^&\s"']+)""").find(u)?.groupValues?.get(1)
                ?: Regex("""drive\.google\\.com/file/d/([^/\s"']+)""").find(u)?.groupValues?.get(1)
            return if (id != null) "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t" else u
        }
    }
}
