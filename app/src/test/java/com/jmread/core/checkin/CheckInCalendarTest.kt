package com.jmread.core.checkin

import com.jmread.network.JmDailyDay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到判据回归：月历里“今天”这一格未签时 signed=false，
 * 不能只凭日期存在就返回“今日已签到”（否则自动签到永远不发 /daily_chk）。
 */
class CheckInCalendarTest {

    private fun day(date: String, signed: Boolean) = JmDailyDay(date = date, signed = signed)

    @Test
    fun `今天未签不算已签到`() {
        val record = listOf(listOf(day("01", true), day("02", false)))
        assertFalse(isSignedOn(record, "02"))
        assertFalse(isSignedOn(record, "2"))
    }

    @Test
    fun `今天已签才算已签到`() {
        val record = listOf(listOf(day("01", true), day("2", true)))
        assertTrue(isSignedOn(record, "02"))
        assertTrue(isSignedOn(record, "2"))
    }

    @Test
    fun `空月历不算已签到`() {
        assertFalse(isSignedOn(emptyList(), "02"))
    }
}
