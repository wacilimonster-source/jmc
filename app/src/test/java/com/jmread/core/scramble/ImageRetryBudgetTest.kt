package com.jmread.core.scramble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERF-03：单个逻辑请求的传输次数与 deadline 预算。 */
class ImageRetryBudgetTest {

    @After
    fun tearDown() {
        ImageRetryLedger.resetForTest()
    }

    @Test
    fun `最多 4 次实际传输`() {
        val budget = ImageRetryBudget(maxTransports = 4, deadlineMs = 20_000, nowMs = { 0L })
        repeat(4) { assertTrue(budget.tryConsumeTransport()) }
        assertFalse(budget.tryConsumeTransport())
        assertEquals(0, budget.remainingTransports())
        assertEquals(4, budget.usedTransports())
    }

    @Test
    fun `超过 deadline 立即停止`() {
        var now = 0L
        val budget = ImageRetryBudget(maxTransports = 4, deadlineMs = 1_000, nowMs = { now })
        assertTrue(budget.tryConsumeTransport())
        now = 999
        assertFalse(budget.expired())
        assertEquals(1, budget.remainingMs())
        now = 1_000
        assertTrue(budget.expired())
        assertEquals(0, budget.remainingMs())
        assertFalse(budget.tryConsumeTransport())
    }

    @Test
    fun `同 key 共享同一份预算`() {
        val key = "https://cdn/x/media/photos/1/a.webp#de2"
        val a = ImageRetryLedger.budgetFor(key)
        val b = ImageRetryLedger.budgetFor(key)
        assertSame(a, b)
        assertTrue(a.tryConsumeTransport())
        assertTrue(b.tryConsumeTransport())
        assertEquals(2, a.usedTransports())
        assertSame(a, ImageRetryLedger.existingBudget(key))
    }

    @Test
    fun `手动重试清空后获得全新预算`() {
        val key = "https://cdn/x/media/photos/1/a.webp#de2"
        val old = ImageRetryLedger.budgetFor(key)
        repeat(4) { old.tryConsumeTransport() }
        assertFalse(old.tryConsumeTransport())

        ImageRetryLedger.clear(key)
        val fresh = ImageRetryLedger.budgetFor(key)
        assertNotSame(old, fresh)
        assertEquals(4, fresh.remainingTransports())
    }

    @Test
    fun `过期预算会被替换`() {
        val key = "k"
        val old = ImageRetryLedger.budgetFor(key, nowMs = 0L)
        repeat(4) { old.tryConsumeTransport() }
        // 未过期：继续复用（预算已耗尽）
        assertSame(old, ImageRetryLedger.budgetFor(key, nowMs = 10_000L))
        // 超过 20s deadline：替换为新预算
        val fresh = ImageRetryLedger.budgetFor(key, nowMs = 20_001L)
        assertNotSame(old, fresh)
        assertEquals(4, fresh.remainingTransports())
    }
}
