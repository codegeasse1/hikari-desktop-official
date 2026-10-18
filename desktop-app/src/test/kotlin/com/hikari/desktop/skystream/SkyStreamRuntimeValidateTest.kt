package com.hikari.desktop.skystream

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse

class SkyStreamRuntimeValidateTest {

    @Test
    fun `garbage script does not validate`(@TempDir dir: File) {
        val f = File(dir, "plugin.js")
        f.writeText("this is not a plugin")
        val verdict = kotlinx.coroutines.runBlocking {
            SkyStreamRuntime.validate("test", f)
        }
        assertFalse(verdict.startsWith("OK"), "unexpected verdict: $verdict")
    }
}
