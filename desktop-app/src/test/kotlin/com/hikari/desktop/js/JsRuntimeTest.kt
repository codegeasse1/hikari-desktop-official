package com.hikari.desktop.js

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsRuntimeTest {

    @Test
    fun `evaluates arithmetic`() {
        val js = JsRuntime.create()
        try {
            assertEquals(2, js.evaluate<Int>("1 + 1", "t", false))
        } finally {
            js.close()
        }
    }

    @Test
    fun `passes strings through`() {
        val js = JsRuntime.create()
        try {
            assertEquals("hi", js.evaluate<String>("'hi'", "t", false))
        } finally {
            js.close()
        }
    }

    @Test
    fun `host functions are callable`() {
        val js = JsRuntime.create()
        try {
            js.function("__add") { args ->
                (args.getOrNull(0) as? Number)?.toInt()?.plus(1) ?: 0
            }
            assertEquals(41, js.evaluate<Int>("__add(40)", "t", false))
        } finally {
            js.close()
        }
    }

    @Test
    fun `thrown errors surface as failures`() {
        val js = JsRuntime.create()
        try {
            val threw = runCatching {
                js.evaluate<Any?>("throw new Error('boom')", "t", false)
            }.isFailure
            assertTrue(threw)
        } finally {
            js.close()
        }
    }

    @Test
    fun `non primitive results come back null`() {
        val js = JsRuntime.create()
        try {
            assertNull(js.evaluate<Any?>("({a: 1})", "t", false))
        } finally {
            js.close()
        }
    }
}
