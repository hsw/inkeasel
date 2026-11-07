package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class OneLineTest {
    @Test
    fun `drops a trailing line break`() {
        assertEquals("Landscape with a Windmill", oneLine("Landscape with a Windmill\r\n"))
    }

    @Test
    fun `joins inner line breaks and whitespace runs with one space`() {
        assertEquals("Spring Landscape after Rain", oneLine("  Spring\nLandscape \t after\r\n\r\nRain "))
    }

    @Test
    fun `keeps plain text and empty text as they are`() {
        assertEquals("Vale of Kashmir", oneLine("Vale of Kashmir"))
        assertEquals("", oneLine(" \r\n "))
    }
}
