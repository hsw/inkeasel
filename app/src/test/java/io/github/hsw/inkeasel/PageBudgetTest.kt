package io.github.hsw.inkeasel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBudgetTest {
    private val budget = PageBudget(maxPages = 3, target = 120)

    @Test
    fun `asks for the next page while under both limits`() {
        assertTrue(budget.more(pagesDone = 1, collected = 70, hasNext = true))
        assertTrue(budget.more(pagesDone = 2, collected = 119, hasNext = true))
    }

    @Test
    fun `stops when the source has no next page`() {
        assertFalse(budget.more(pagesDone = 1, collected = 10, hasNext = false))
    }

    @Test
    fun `stops after the page limit even when short of the target`() {
        assertFalse(budget.more(pagesDone = 3, collected = 5, hasNext = true))
        assertFalse(budget.more(pagesDone = 3, collected = 0, hasNext = true))
    }

    @Test
    fun `stops once the target is reached`() {
        assertFalse(budget.more(pagesDone = 1, collected = 120, hasNext = true))
        assertFalse(budget.more(pagesDone = 2, collected = 150, hasNext = true))
    }
}
