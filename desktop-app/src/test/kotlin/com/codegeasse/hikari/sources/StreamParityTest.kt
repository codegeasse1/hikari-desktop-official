package com.codegeasse.hikari.sources

import com.codegeasse.hikari.sources.nuvio.NuvioMainApi
import com.codegeasse.hikari.sources.sky.SkyStreamMainApi
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamParityTest {
    @Test
    fun nuvioExternalUrlRowIsPlayable() {
        val row = JSONObject().put("name", "Vixsrc").put("externalUrl", "https://cdn.example.com/v.mp4")
        val m = NuvioMainApi.mapStreamRow(row, "prov")
        assertNotNull(m)
        assertEquals("https://cdn.example.com/v.mp4", m.url)
    }
    @Test
    fun nuvioInfoHashRowMapsMagnet() {
        val row = JSONObject().put("name", "Cache").put("infoHash", "abc123")
        val m = NuvioMainApi.mapStreamRow(row, "prov")
        assertNotNull(m)
        assertEquals("abc123", m.infoHash)
    }
    @Test
    fun nuvioQualityParses() {
        assertEquals(1080, NuvioMainApi.parseStreamQuality("1080p"))
        assertEquals(2160, NuvioMainApi.parseStreamQuality("4K"))
        assertEquals(0, NuvioMainApi.parseStreamQuality("Auto"))
    }
    @Test
    fun skyItemAcceptsContentTypeAndImageKey() {
        val o = JSONObject().put("url", "https://site.example.com/w/1").put("name", "Demo &amp; Co").put("contentType", "anime").put("image", "https://img.example.com/p.jpg")
        val item = SkyStreamMainApi.toItem("pkg.test", o)
        assertNotNull(item)
        assertTrue(item.isTv)
        assertEquals("Demo & Co", item.title)
        assertEquals("https://img.example.com/p.jpg", item.poster)
    }
    @Test
    fun skyStreamQualityAndSubs() {
        val o = JSONObject().put("url", "https://cdn.example.com/s.m3u8").put("providerName", "SRC").put("quality", "4K")
            .put("subtitles", org.json.JSONArray().put(JSONObject().put("url", "https://sub.example.com/a.srt").put("label", "English")))
        val m = SkyStreamMainApi.toStreamSource("Demo", o)
        assertNotNull(m)
        assertEquals(2160, m.qualityH)
        assertEquals(1, m.subtitles.size)
    }
}
