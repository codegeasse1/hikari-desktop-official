package com.hikari.sources.hiki

import com.hikari.ext.HikariCatalog
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariProvider
import com.hikari.ext.HikariStream
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeHiki : HikariProvider {
    override val id = "fake"
    override val name = "Fake"
    override val mainUrl = "https://fake.test"
    override val tvTypes = setOf(HikariMediaType.MOVIE, HikariMediaType.SERIES)
    private val movie = HikariMedia("m1", "Fake Movie", HikariMediaType.MOVIE, year = 2020)
    private val show = HikariMedia("s1", "Fake Show", HikariMediaType.SERIES)
    override fun catalogs() = listOf(HikariCatalog("all", "All", HikariMediaType.MOVIE))
    override suspend fun getCatalog(catalog: HikariCatalog, page: Int) = listOf(movie)
    override suspend fun search(query: String, page: Int) = listOf(show)
    override suspend fun getMeta(media: HikariMedia) = if (media.id == "s1") show else movie
    override suspend fun getEpisodes(media: HikariMedia) =
        if (media.id == "s1") listOf(HikariEpisode(1, "e1", "Pilot")) else null
    override suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?) =
        listOf(HikariStream(name = "1080p", url = "https://cdn.test/v.mp4"))
}

class HikiMainApiTest {

    @Test
    fun identityAndHomeSearchLoadAndLinks(): Unit = runBlocking {
        val api = HikiMainApi(FakeHiki())
        assertEquals("Fake", api.name)
        assertEquals("https://fake.test", api.mainUrl)
        assertTrue(api.supportedTypes.contains(TvType.Movie))
        val home = api.getMainPage(1, MainPageRequest("All", "all", false))
        assertEquals(1, home.items.firstOrNull()?.list?.size ?: 0)
        val found = api.search("show") ?: emptyList()
        assertEquals(1, found.size)
        val seriesUrl = found.first().url
        val series = api.load(seriesUrl) as com.lagradost.cloudstream3.TvSeriesLoadResponse
        assertEquals(1, series.episodes.size)
        var links = 0
        val ok = api.loadLinks(series.episodes.first().data, false, {}, { links++ })
        assertTrue(ok)
        assertEquals(1, links)
    }
}
