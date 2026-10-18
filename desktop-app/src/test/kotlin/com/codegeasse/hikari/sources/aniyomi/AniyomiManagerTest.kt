package com.codegeasse.hikari.sources.aniyomi

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniyomiManagerTest {

    @Test
    fun indexEntriesParse() {
        val text = "[{\"name\":\"Aniyomi: Jellyfin\",\"pkg\":\"eu.kanade.jellyfin\",\"apk\":\"aniyomi-all.jellyfin-v14.17.apk\",\"lang\":\"all\",\"code\":17,\"version\":\"14.17\"}]"
        val entries = AniyomiManager.repoEntries(text, "https://host.test/repo")
        assertEquals(1, entries.size)
        assertEquals("Jellyfin", entries[0].name)
        assertEquals("eu.kanade.jellyfin", entries[0].pkg)
        assertEquals("https://host.test/repo/apk/aniyomi-all.jellyfin-v14.17.apk", entries[0].apkUrl)
        assertEquals("https://host.test/repo/icon/eu.kanade.jellyfin.png", entries[0].iconUrl)
        assertEquals(17, entries[0].version)
        assertTrue(AniyomiManager.repoEntries("garbage", "https://h/").isEmpty())
        assertTrue(AniyomiManager.repoEntries("", "https://h/").isEmpty())
        assertTrue(AniyomiManager.repoEntries("{\"plugins\":[]}", "https://h/").isEmpty())
    }

    @Test
    fun repoUrlResolvesToIndex() {
        assertEquals(
            "https://host.test/repo/index.min.json",
            AniyomiManager.resolveRepoUrl("https://host.test/repo"),
        )
        assertEquals(
            "https://host.test/repo/index.min.json",
            AniyomiManager.resolveRepoUrl("https://host.test/repo/index.min.json"),
        )
        assertNull(AniyomiManager.resolveRepoUrl("   "))
    }

    @Test
    fun safeAndLabels() {
        assertEquals("a.b_c", AniyomiManager.safe("a.b/c"))
        assertEquals("extension", AniyomiManager.safe(""))
    }

    @Test
    fun installRejectsEmptyAndGarbage(): Unit = runBlocking {
        assertTrue(AniyomiManager.install(ByteArray(0)).isFailure)
        assertTrue(AniyomiManager.install("not an apk".toByteArray()).isFailure)
        assertNotNull(AniyomiManager.DEFAULT_REPOS.firstOrNull())
    }
}
