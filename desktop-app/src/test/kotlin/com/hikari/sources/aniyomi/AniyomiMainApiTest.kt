package com.hikari.sources.aniyomi

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import eu.kanade.tachiyomi.animesource.model.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniyomiMainApiTest {

    @Test
    fun tokenRoundTrip() {
        val anime = AniyomiMainApi.encodeAnime("my.pkg", 2, "/anime/slug-123?x=1")
        assertTrue(anime.startsWith("aniyomi://my.pkg/2/"))
        val dec = AniyomiMainApi.decodeToken(anime)
        assertNotNull(dec)
        assertEquals("my.pkg", dec.pkg)
        assertEquals(2, dec.index)
        assertEquals("/anime/slug-123?x=1", dec.animeUrl)
        assertNull(dec.episodeUrl)
        val ep = AniyomiMainApi.encodeEpisode("my.pkg", 0, "/a/1", "/e/5")
        val decEp = AniyomiMainApi.decodeToken(ep)
        assertNotNull(decEp)
        assertEquals("/a/1", decEp.animeUrl)
        assertEquals("/e/5", decEp.episodeUrl)
        assertNull(AniyomiMainApi.decodeToken("https://other.test/"))
        assertNull(AniyomiMainApi.decodeToken("aniyomi://only"))
        assertNull(AniyomiMainApi.decodeToken("aniyomi://p/x/notb64!!"))
    }

    @Test
    fun videoMapping() {
        val named = AniyomiMainApi.mapVideoName("VidCloud", Video(videoUrl = "https://c.test/v.mp4", videoTitle = "1080p"))
        assertEquals("VidCloud - 1080p", named)
        val res = AniyomiMainApi.mapVideoName("", Video(videoUrl = "https://c.test/v.mp4", resolution = 720))
        assertEquals("720p", res)
        assertEquals(ExtractorLinkType.TORRENT, AniyomiMainApi.videoKind("magnet:?xt=urn:btih:abc"))
        assertEquals(ExtractorLinkType.M3U8, AniyomiMainApi.videoKind("https://c.test/s.m3u8"))
        assertEquals(ExtractorLinkType.DASH, AniyomiMainApi.videoKind("https://c.test/s.mpd"))
        assertEquals(ExtractorLinkType.VIDEO, AniyomiMainApi.videoKind("https://c.test/v.mp4"))
    }

    @Test
    fun driveNormalization() {
        assertEquals(
            "https://drive.usercontent.google.com/download?id=ABC&export=download&confirm=t",
            AniyomiMainApi.normalizeDriveUrl("https://drive.google.com/file/d/ABC/view"),
        )
        assertEquals(
            "https://drive.usercontent.google.com/download?id=XYZ&export=download&confirm=t",
            AniyomiMainApi.normalizeDriveUrl("https://drive.google.com/uc?id=XYZ&export=download"),
        )
        assertEquals("https://c.test/v.mp4", AniyomiMainApi.normalizeDriveUrl("https://c.test/v.mp4"))
    }
}
