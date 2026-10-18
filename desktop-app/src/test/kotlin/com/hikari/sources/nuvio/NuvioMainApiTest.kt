package com.hikari.sources.nuvio

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioMainApiTest {

    @Test
    fun mediaRefRoundTrip() {
        val api = NuvioMainApi(File("vixsrc.js"))
        assertEquals("vixsrc", api.name)
        val ref = parseMediaRef("nuvio://vixsrc/tv/12345")
        assertNotNull(ref)
        assertEquals("vixsrc", ref.stem)
        assertTrue(ref.isTv)
        assertEquals("12345", ref.tmdbId)
        assertNull(parseMediaRef("https://example.com/x"))
        assertNull(parseMediaRef("nuvio://vixsrc/tv"))
    }

    @Test
    fun episodeDataRoundTrip() {
        val ep = parseEpisodeData("nuvio://vixsrc/tv/12345/s/2/e/5")
        assertNotNull(ep)
        assertEquals(2, ep.season)
        assertEquals(5, ep.episode)
        val movie = parseEpisodeData("nuvio://vixsrc/movie/99")
        assertNotNull(movie)
        assertEquals(null, movie.season)
        assertNull(parseEpisodeData("nuvio://vixsrc/tv/1/s/x/e/2"))
    }

    @Test
    fun nicheRowsDifferByProvider() {
        val a = rowsFor("vixsrc", listOf("moviebox", "vixsrc"))
        val b = rowsFor("moviebox", listOf("moviebox", "vixsrc"))
        assertEquals(5, a.size)
        assertTrue(a.map { it.id } != b.map { it.id })
        assertEquals(NuvioMainApi.Companion.Niche.GENERAL, nicheOf("vixsrc"))
        assertEquals(NuvioMainApi.Companion.Niche.ANIME, nicheOf("hianime-to"))
        assertEquals(NuvioMainApi.Companion.Niche.HINDI, nicheOf("vegamovies-nuvio"))
    }

    @Test
    fun streamRowMapping() {
        val magnet = mapStreamRow(JSONObject().put("name", "Vix").put("url", "magnet:?xt=urn:btih:abc123").put("quality", "1080p"), "vixsrc")
        assertNotNull(magnet)
        assertEquals(NuvioMainApi.Companion.StreamKind.TORRENT, magnet.kind)
        assertEquals("Vix 1080p", magnet.name)
        assertTrue(magnet.headers["User-Agent"]?.contains("Chrome") == true)
        val nested = mapStreamRow(JSONObject().put("url", JSONObject().put("url", "https://cdn.test/v.m3u8").put("headers", JSONObject().put("Referer", "https://x.test/"))), "p")
        assertNotNull(nested)
        assertEquals(NuvioMainApi.Companion.StreamKind.M3U8, nested.kind)
        assertEquals("https://x.test/", nested.headers["Referer"])
        assertNull(mapStreamRow(JSONObject().put("url", "http://www.w3.org/2000/svg"), "p"))
        assertNull(mapStreamRow(JSONObject(), "p"))
        assertEquals("https://drive.usercontent.google.com/download?id=ABC&export=download&confirm=t", normalizeDriveUrl("https://drive.google.com/file/d/ABC/view"))
    }

    private fun parseMediaRef(ref: String) = NuvioMainApi.parseMediaRef(ref)
    private fun parseEpisodeData(data: String) = NuvioMainApi.parseEpisodeData(data)
    private fun rowsFor(name: String, all: List<String>) = NuvioMainApi.rowsFor(name, all)
    private fun nicheOf(name: String) = NuvioMainApi.nicheOf(name)
    private fun mapStreamRow(o: JSONObject, p: String) = NuvioMainApi.mapStreamRow(o, p)
    private fun normalizeDriveUrl(u: String) = NuvioMainApi.normalizeDriveUrl(u)
}
