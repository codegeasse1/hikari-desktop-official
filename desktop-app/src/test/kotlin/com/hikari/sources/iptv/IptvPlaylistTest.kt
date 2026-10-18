package com.hikari.sources.iptv

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IptvPlaylistTest {

    private val sample = "#EXTM3U\n" +
        "#EXTINF:-1 tvg-id=\"sky1\" tvg-logo=\"https://example.com/sky1.png\" group-title=\"Sports\",Sky Sports 1\n" +
        "http://example.com/live/1.m3u8\n" +
        "#EXTINF:-1 group-title=\"News\",News, Live\n" +
        "https://example.com/live/2.ts\n"

    @Test
    fun `parses channels with attributes`() {
        val channels = IptvPlaylist.parse(sample)
        assertEquals(2, channels.size)
        assertEquals("Sky Sports 1", channels[0].name)
        assertEquals("Sports", channels[0].group)
        assertEquals("https://example.com/sky1.png", channels[0].logo)
        assertEquals("News, Live", channels[1].name)
    }

    @Test
    fun `empty playlist parses to empty`() {
        assertTrue(IptvPlaylist.parse("").isEmpty())
        assertTrue(IptvPlaylist.parse("#EXTM3U\n").isEmpty())
    }

    @Test
    fun `duplicate urls are dropped`() {
        val text = sample + "#EXTINF:-1 group-title=\"Sports\",Mirror\nhttp://example.com/live/1.m3u8\n"
        val channels = IptvPlaylist.parse(text)
        assertEquals(2, channels.size)
    }
}
