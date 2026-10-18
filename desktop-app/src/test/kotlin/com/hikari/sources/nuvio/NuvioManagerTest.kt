package com.hikari.sources.nuvio

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NuvioManagerTest {

    @Test
    fun safeNameKeepsJs() {
        assertEquals("vixsrc.js", NuvioManager.safeName("providers/vixsrc.js"))
        assertEquals("my_provider.js", NuvioManager.safeName("my provider"))
    }

    @Test
    fun manifestEntriesParse() {
        val text = "{\"scrapers\":[{\"name\":\"VixSrc\",\"filename\":\"providers/vixsrc.js\",\"version\":\"1.0.2\",\"logo\":\"https://x.test/i.png\",\"supportedTypes\":[\"movie\",\"tv\"]},{\"name\":\"Bad\"}]}"
        val entries = NuvioManager.manifestEntries(text, "https://host.test/base")
        assertEquals(1, entries.size)
        assertEquals("https://host.test/base/providers/vixsrc.js", entries[0].url)
        assertEquals(1, entries[0].version)
        assertEquals("https://x.test/i.png", entries[0].iconUrl)
        assertTrue(NuvioManager.manifestEntries("not json", "https://h/").isEmpty())
    }

    @Test
    fun installRejectsEmptyAndOversize(): Unit = runBlocking {
        assertTrue(NuvioManager.installSingle(ByteArray(0), "x.js").isFailure)
        assertTrue(NuvioManager.installSingle(ByteArray(NuvioManager.MAX_BYTES + 1), "x.js").isFailure)
        assertTrue(NuvioManager.installSingle("hello world".toByteArray(), "junk.js").isFailure)
    }

    @Test
    fun patchGating() {
        assertEquals(null, NuvioManager.patchedBytes(null, "x".toByteArray()))
        assertEquals(null, NuvioManager.patchedBytes("https://other.test/4khdhub.js", "unrelated bytes".toByteArray()))
    }
}
