package com.hikari.sources.sky

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyStreamMainApiTest {

    private fun api(): SkyStreamMainApi {
        val dir = java.nio.file.Files.createTempDirectory("sky-api").toFile()
        dir.deleteOnExit()
        JSONObject().put("name", "TestSky").put("packageName", "test.sky").writeTo(File(dir, "plugin.json"))
        return SkyStreamMainApi(dir)
    }

    @Test
    fun tokenRoundTrip() {
        val enc = encodeToken("my.pkg", "https://site.test/watch/abc 123?x=1&y=2")
        assertTrue(enc.startsWith("sky://my.pkg/"))
        val dec = decodeToken(enc)
        assertNotNull(dec)
        assertEquals("my.pkg", dec.pkg)
        assertEquals("https://site.test/watch/abc 123?x=1&y=2", dec.token)
        assertNull(decodeToken("https://other.test/"))
        assertNull(decodeToken("sky://"))
    }

    @Test
    fun itemMapping() {
        val item = toItem("p", JSONObject().put("url", "https://s.test/m/1").put("title", "Some Movie").put("type", "movie").put("year", 2021))
        assertNotNull(item)
        assertTrue(item.url.startsWith("sky://p/"))
        assertEquals(false, item.isTv)
        assertEquals(2021, item.year)
        val series = toItem("p", JSONObject().put("url", "u").put("name", "Show").put("type", "anime"))
        assertNotNull(series)
        assertTrue(series.isTv)
        assertNull(toItem("p", JSONObject().put("title", "No url")))
    }

    @Test
    fun magicDecoding() {
        val h = LinkedHashMap<String, String>()
        assertEquals("https://cdn.test/v.m3u8", decodeMagic("https://cdn.test/v.m3u8", h))
        assertTrue(decodeMagic("magic_m3u8:https://x.test/p", h).startsWith("data:application/vnd.apple.mpegurl;base64,"))
        val v1 = "MAGIC_PROXY_v1" + java.util.Base64.getEncoder().encodeToString("https://real.test/v.mp4".toByteArray())
        assertEquals("https://real.test/v.mp4", decodeMagic(v1, h))
        val v2json = JSONObject().put("url", "https://v2.test/s.m3u8").put("headers", JSONObject().put("Referer", "https://r.test/")).toString()
        val v2 = "MAGIC_PROXY_v2" + java.util.Base64.getEncoder().encodeToString(v2json.toByteArray())
        assertEquals("https://v2.test/s.m3u8", decodeMagic(v2, h))
        assertEquals("https://r.test/", h["Referer"])
        assertEquals("MAGIC_PROXY_v1!!!", decodeMagic("MAGIC_PROXY_v1!!!", LinkedHashMap()))
    }

    @Test
    fun streamMappingAndCap() {
        val arr = JSONArray()
        arr.put(JSONObject().put("url", "https://c.test/a.mp4").put("source", "CDN").put("quality", "1080p"))
        val mapped = mapStreams("p", "Disp", arr)
        assertEquals(1, mapped.size)
        assertEquals("CDN 1080p", mapped[0].name)
        assertEquals(StreamKind.VIDEO, mapped[0].kind)
        assertTrue(mapped[0].headers["User-Agent"]?.contains("Chrome") == true)
    }

    @Test
    fun episodesFromArray() {
        val api = api()
        val arr = JSONArray()
        arr.put(JSONObject().put("url", "https://s.test/e1").put("season", 2).put("episode", 5).put("name", "Pilot"))
        val eps = with(api) { toEpisodes("p", arr) }
        assertEquals(1, eps.size)
        assertEquals(2, eps[0].season)
        assertEquals(5, eps[0].episode)
        assertEquals("Pilot", eps[0].name)
    }

    private fun encodeToken(pkg: String, token: String) = SkyStreamMainApi.encodeToken(pkg, token)
    private fun decodeToken(url: String) = SkyStreamMainApi.decodeToken(url)
    private fun toItem(pkg: String, o: JSONObject) = SkyStreamMainApi.toItem(pkg, o)
    private fun decodeMagic(raw: String, h: MutableMap<String, String>) = SkyStreamMainApi.decodeMagic(raw, h)
    private fun mapStreams(pkg: String, name: String, arr: JSONArray) = SkyStreamMainApi.mapStreams(pkg, name, arr)
}

class SkyStreamManagerTest {

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bout = ByteArrayOutputStream()
        ZipOutputStream(bout).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bout.toByteArray()
    }

    @Test
    fun unzipFindsNestedEntries() {
        val bytes = zipOf(
            mapOf(
                "bundle/plugin.js" to "globalThis.getHome = () => ({});",
                "bundle/plugin.json" to "{\"packageName\":\"a.b\",\"name\":\"AB\"}",
                "readme.txt" to "ignore",
            )
        )
        val parts = unzip(bytes)
        assertNotNull(parts)
        assertNotNull(parts.js)
        assertEquals("a.b", parts.manifest?.optString("packageName"))
        assertNull(unzip("not a zip".toByteArray())?.js)
    }

    @Test
    fun entryMapping() {
        val o = JSONObject().put("packageName", "a.b").put("name", "AB").put("url", "ext.sky").put("version", "2.1")
        val e = skyEntry(o, "https://host.test/base")
        assertNotNull(e)
        assertEquals("https://host.test/base/ext.sky", e.url)
        assertEquals(2, e.version)
        assertNull(skyEntry(JSONObject(), "https://h/"))
        assertTrue(repoEntries("{\"plugins\":[]}", "https://h/").isEmpty())
        assertTrue(repoEntries("garbage", "https://h/").isEmpty())
    }

    @Test
    fun safeAndFallback() {
        assertEquals("a.b_c", SkyStreamManager.safe("a.b/c"))
        assertEquals("pkg", SkyStreamManager.fallbackName("https://h.test/x/pkg.sky?dl=1"))
    }

    private fun unzip(b: ByteArray) = SkyStreamManager.unzip(b)
    private fun skyEntry(o: JSONObject, base: String) = SkyStreamManager.skyEntry(o, base)
    private fun repoEntries(t: String, base: String) = SkyStreamManager.repoEntries(t, base)
}

private fun JSONObject.writeTo(f: File) = f.writeText(toString())
