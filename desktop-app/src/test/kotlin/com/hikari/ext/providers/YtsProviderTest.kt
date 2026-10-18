package com.hikari.ext.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class YtsProviderTest {

    @Test
    fun `bundled provider has an identity`() {
        val p = YtsProvider()
        assertTrue(p.id.isNotBlank())
        assertTrue(p.name.isNotBlank())
        assertTrue(p.mainUrl.startsWith("https://"))
        assertTrue(p.catalogs().isNotEmpty())
    }
}
