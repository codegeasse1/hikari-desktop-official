package com.codegeasse.hikari.desktop.nuvio

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class NuvioRuntimeValidateTest {

    @Test
    fun `garbage source does not validate`() {
        val verdict = kotlinx.coroutines.runBlocking {
            NuvioRuntime.validate("this is not a provider")
        }
        assertFalse(verdict.startsWith("OK"), "unexpected verdict: $verdict")
    }
}
