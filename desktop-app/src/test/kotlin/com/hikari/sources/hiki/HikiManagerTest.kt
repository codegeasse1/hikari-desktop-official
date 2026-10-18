package com.hikari.sources.hiki

import com.hikari.common.platform.PlatformPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HikiManagerTest {

    @Test
    fun extDirMatchesCloudStreamExtensionsDir() {
        assertEquals(PlatformPaths.extensionsDir.absolutePath, HikiManager.extDir().absolutePath)
    }

    @Test
    fun pluginFileFilterMatchesCloudStreamPlusHiki() {
        val dir = java.nio.file.Files.createTempDirectory("hiki-filter").toFile()
        try {
            val good = listOf("a.jar", "b.cs3", "c.hiki").map { File(dir, it).apply { writeText("x") } }
            val bad = listOf("a-jvm.jar", "b-secure.jar", "c.dex", "d.txt").map { File(dir, it).apply { writeText("x") } }
            for (f in good) assertTrue(HikiManager.isPluginFile(f), f.name)
            for (f in bad) assertFalse(HikiManager.isPluginFile(f), f.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipBytes(manifest: String?): ByteArray {
        val bout = java.io.ByteArrayOutputStream()
        ZipOutputStream(bout).use { z ->
            if (manifest != null) {
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(manifest.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bout.toByteArray()
    }

    @Test
    fun installRejectsGarbageExactlyLikeCloudStreamRequiresManifest(): Unit = runBlocking {
        val before = HikiManager.installedFiles().map { it.name }.toSet()
        val r1: Result<String> = HikiManager.install(ByteArray(0), "empty.hiki")
        assertTrue(r1.isFailure)
        val r2: Result<String> = HikiManager.install("not a zip".toByteArray(), "junk.hiki")
        assertTrue(r2.isFailure)
        val r3: Result<String> = HikiManager.install(zipBytes("{\"name\":\"x\"}"), "nomanifest.hiki")
        assertTrue(r3.isFailure)
        val after = HikiManager.installedFiles().map { it.name }.toSet()
        assertEquals(before, after)
    }
}
