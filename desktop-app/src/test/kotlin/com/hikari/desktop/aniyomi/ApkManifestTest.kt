package com.hikari.desktop.aniyomi

import com.hikari.desktop.dex.DexJar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkManifestTest {

    @Test
    fun `garbage file reads as null`(@TempDir dir: File) {
        val f = File(dir, "junk.apk")
        f.writeText("definitely not an apk")
        assertNull(ApkManifest.read(f))
    }

    @Test
    fun `zip without manifest reads as null`(@TempDir dir: File) {
        val f = File(dir, "empty.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex"))
            z.write(ByteArray(16))
            z.closeEntry()
        }
        assertTrue(DexJar.isDexArchive(f))
        assertNull(ApkManifest.read(f))
    }
}
