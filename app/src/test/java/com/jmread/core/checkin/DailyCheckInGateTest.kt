package com.jmread.core.checkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日自动签到的发起门禁：登录中且「当日还没成功过」才发起。
 * 当日成功落盘后，同日内的每次启动 / 进入「我的」页都不再重复请求。
 */
class DailyCheckInGateTest {

    private val today = "2026-10-25"

    @Test
    fun `登录且当日未成功——发起`() {
        assertTrue(DailyCheckIn.due(loggedIn = true, lastSuccessDate = "", today = today))
        assertTrue(DailyCheckIn.due(loggedIn = true, lastSuccessDate = "2026-10-24", today = today))
    }

    @Test
    fun `当日已成功——不再发起`() {
        assertFalse(DailyCheckIn.due(loggedIn = true, lastSuccessDate = today, today = today))
    }

    @Test
    fun `未登录——不发起`() {
        assertFalse(DailyCheckIn.due(loggedIn = false, lastSuccessDate = "", today = today))
    }
}
