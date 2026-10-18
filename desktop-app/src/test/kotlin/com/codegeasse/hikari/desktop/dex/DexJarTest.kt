package com.codegeasse.hikari.desktop.dex

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DexJarTest {

    private fun plainJar(dir: File, name: String): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            z.write("Manifest-Version: 1.0\n".toByteArray())
            z.closeEntry()
        }
        return f
    }

    @Test
    fun `plain jar is not a dex archive`(@TempDir dir: File) {
        assertFalse(DexJar.isDexArchive(plainJar(dir, "plain.jar")))
    }

    @Test
    fun `plain jar passes through unchanged`(@TempDir dir: File) {
        val f = plainJar(dir, "plain.jar")
        assertEquals(f.absolutePath, DexJar.ensureJvmJar(f)?.absolutePath)
    }

    @Test
    fun `missing file converts to null`() {
        assertEquals(null, DexJar.ensureJvmJar(File("does-not-exist-12345.jar")))
    }
}
