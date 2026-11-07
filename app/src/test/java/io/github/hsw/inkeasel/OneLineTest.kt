package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class OneLineTest {
    @Test
    fun dropsATrailingLineBreak() {
        assertEquals("Landscape with a Windmill", oneLine("Landscape with a Windmill\r\n"))
    }

    @Test
    fun joinsInnerLineBreaksAndWhitespaceRuns_withOneSpace() {
        assertEquals("Spring Landscape after Rain", oneLine("  Spring\nLandscape \t after\r\n\r\nRain "))
    }

    @Test
    fun keepsPlainAndEmptyText() {
        assertEquals("Vale of Kashmir", oneLine("Vale of Kashmir"))
        assertEquals("", oneLine(" \r\n "))
    }
}
