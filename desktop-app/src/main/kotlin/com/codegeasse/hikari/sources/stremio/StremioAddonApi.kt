package com.codegeasse.hikari.sources.stremio

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.codegeasse.hikari.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Base64

/**
 * A single Stremio addon as a first-class provider (Hikari source).
 *
 * Full addon protocol (manifest / catalog / meta / stream / subtitles),
 * ported from Hikari's StremioAddon onto MainAPI: addon catalogs become Home
 * rows, addon search feeds global search, meta builds details + episodes, and
 * direct/http streams play. Torrent-only rows are skipped for now (the app's
 * player resolves those through its own TorrServer path in a follow-up).
 */
class StremioAddonApi(
    private val addonBase: String,
    addonName: String? = null,
) : MainAPI() {

    override var name = if (!addonName.isNullOrBlank()) "Stremio: $addonName" else "Stremio addon"
    override var mainUrl = addonBase
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val base: String
    private val query: String
    init {
        val u = addonBase.trim().trimEnd('/')
        val qi = u.indexOf('?')
        val path = if (qi >= 0) u.substring(0, qi) else u
        query = if (qi >= 0) u.substring(qi + 1) else ""
        val clean = if (path.lowercase().endsWith("/manifest.json")) path.dropLast("/manifest.json".length) else path
        base = clean.trimEnd('/')
    }

    private fun resUrl(resource: String, type: String, id: String, extra: String? = null): String {
        val e = extra?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""
        val s = "$base/$resource/$type/$id$e.json"
        return if (query.isBlank()) s else "$s?$query"
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JSONObject? {
        for (u in listOf(url, url.replaceFirst("https://", "http://")).distinct()) {
            repeat(2) {
                runCatching {
                    val conn = URL(u).openConnection()
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                    conn.setRequestProperty("User-Agent", "HikariDesktop/1.0")
                    val body = conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
                    return JSONObject(body)
                }
            }
        }
        return null
    }

    private var manifest: JSONObject? = null
    private fun loadManifest(): JSONObject? {
        manifest?.let { return it }
        val m = getJson("$base/manifest.json")
        manifest = m
        return m
    }

    private data class Catalog(val type: String, val id: String, val name: String)

    private fun catalogsOf(m: JSONObject): List<Catalog> {
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        val out = mutableListOf<Catalog>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id")
            if (id.isBlank()) continue
            out += Catalog(c.optString("type").lowercase().ifBlank { "series" }, id, c.optString("name").ifBlank { id })
        }
        return out
    }

    private fun isMovieType(t: String): Boolean = t == "movie" || t == "movies" || t == "film"

    private fun encId(type: String, id: String): String =
        Base64.getUrlEncoder().encodeToString(base.toByteArray()).trimEnd('=') + "|" + type + "|" + id

    private fun decId(url: String): Triple<String, String, String>? {
        val parts = url.split("|")
        if (parts.size != 3) return null
        val pad = "=".repeat((4 - parts[0].length % 4) % 4)
        val b = runCatching { Base64.getUrlDecoder().decode(parts[0] + pad).toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return Triple(b, parts[1], parts[2])
    }

    private fun toResponse(meta: JSONObject, catalogType: String): SearchResponse? {
        val id = meta.optString("id")
        val title = meta.optString("name")
        if (id.isBlank() || title.isBlank()) return null
        val raw = meta.optString("type").ifBlank { catalogType }
        val url = encId(raw, id)
        return if (isMovieType(raw)) {
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = meta.optString("poster").ifBlank { null }
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                posterUrl = meta.optString("poster").ifBlank { null }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val m = loadManifest() ?: return@withContext newHomePageResponse(emptyList(), false)
        val lists = catalogsOf(m).take(12).mapNotNull { c ->
            val extra = if (page > 1) "skip=${(page - 1) * 100}" else null
            val json = getJson(resUrl("catalog", c.type, c.id, extra)) ?: return@mapNotNull null
            val arr = json.optJSONArray("metas") ?: return@mapNotNull null
            val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { meta -> toResponse(meta, c.type) } }
            if (items.isEmpty()) null else HomePageList(c.name, items)
        }
        newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        val m = loadManifest() ?: return@withContext emptyList()
        val out = mutableListOf<SearchResponse>()
        for (c in catalogsOf(m)) {
            val json = getJson(resUrl("catalog", c.type, c.id, "search=" + encode(query.trim()))) ?: continue
            val arr = json.optJSONArray("metas") ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { meta -> toResponse(meta, c.type)?.let { out += it } }
            }
        }
        out.distinctBy { it.url }.take(200)
    }

    private fun fetchMeta(type: String, id: String): JSONObject? {
        val json = getJson(resUrl("meta", type, id)) ?: return null
        return json.optJSONObject("meta") ?: json.optJSONArray("meta")?.optJSONObject(0)
    }

    private fun yearOf(releaseInfo: String): Int? = Regex("""(19|20)\\d{2}""").find(releaseInfo)?.value?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse? = withContext(Dispatchers.IO) {
        val (_, type, id) = decId(url) ?: return@withContext null
        val meta = fetchMeta(type, id) ?: return@withContext null
        val title = meta.optString("name").ifBlank { return@withContext null }
        val poster = meta.optString("poster").ifBlank { null }
        val bg = meta.optString("background").ifBlank { null }
        val plot = meta.optString("description").ifBlank { null }
        val year = yearOf(meta.optString("releaseInfo"))
        if (isMovieType(type)) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = bg
                this.plot = plot
                this.year = year
            }
        } else {
            val videos = meta.optJSONArray("videos")
            val episodes = mutableListOf<Episode>()
            val seen = HashSet<String>()
            if (videos != null) {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val ep = v.optInt("episode", -1)
                    val season = v.optInt("season", 1)
                    if (ep < 0 || !seen.add("$season:$ep")) continue
                    val vid = v.optString("id").ifBlank { "$id:$season:$ep" }
                    episodes += this@StremioAddonApi.newEpisode(encId(type, vid), fix = false, initializer = {
                        name = v.optString("title").ifBlank { "Episode $ep" }
                        this.season = season
                        this.episode = ep
                        posterUrl = v.optString("thumbnail").ifBlank { null }
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode ?: 0 }) {
                posterUrl = poster
                backgroundPosterUrl = bg
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val decoded = decId(data) ?: return@withContext false
        val type = decoded.second
        val id = decoded.third
        val attempts = linkedSetOf(resUrl("stream", type, id))
        val bare = id.replace(Regex(":\\d+:\\d+$"), "")
        if (bare != id) attempts += resUrl("stream", type, bare)
        for (u in attempts) {
            val json = getJson(u) ?: continue
            val arr = json.optJSONArray("streams") ?: continue
            var any = false
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                if (st.optString("infoHash").isNotBlank()) continue
                val sUrl = st.optString("url").ifBlank { continue }
                val subs = st.optJSONArray("subtitles")
                if (subs != null) {
                    for (s in 0 until subs.length()) {
                        val sub = subs.optJSONObject(s) ?: continue
                        val su = sub.optString("url")
                        if (su.isNotBlank()) subtitleCallback(newSubtitleFile(sub.optString("lang").ifBlank { "Subtitle" }, su))
                    }
                }
                val isM3u8 = sUrl.contains(".m3u8", true)
                callback(
                    newExtractorLink(
                        this@StremioAddonApi.name,
                        st.optString("name").ifBlank { if (isM3u8) "HLS" else "Direct" },
                        sUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        referer = ""
                        quality = Qualities.Unknown.value
                    }
                )
                any = true
            }
            if (any) return@withContext true
        }
        false
    }

}
