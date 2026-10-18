package com.hikari.sources.stremio

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class StremioAddonApiTest {

    private val dead = StremioAddonApi("http://127.0.0.1:9")

    @Test
    fun `unreachable addon returns empty home without throwing`() = runBlocking {
        val req = com.lagradost.cloudstream3.MainPageRequest("test", "", false)
        val home = dead.getMainPage(1, req)
        assertTrue(home.items.all { it.list.isEmpty() })
    }

    @Test
    fun `unreachable addon search is empty`() = runBlocking {
        assertTrue((dead.search("test") ?: emptyList()).isEmpty())
    }

    @Test
    fun `unreachable addon load is null`() = runBlocking {
        assertTrue(dead.load("bogus") == null)
    }
}
