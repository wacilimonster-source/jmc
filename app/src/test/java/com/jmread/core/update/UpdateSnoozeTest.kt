package com.jmread.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新免打扰（snooze）依赖的版本比较语义：
 * checkOnce 里「远端版本 > 已静默版本」才重新提醒——同版本不再打扰，回滚也不打扰。
 */
class UpdateSnoozeTest {

    @Test
    fun `同版本不算更新（免打扰后不再提醒）`() {
        assertFalse(UpdateManager.isNewer("0.1.6", "0.1.6"))
    }

    @Test
    fun `更高版本触发提醒`() {
        assertTrue(UpdateManager.isNewer("0.1.7", "0.1.6"))
        assertTrue(UpdateManager.isNewer("0.2.0", "0.1.9"))
    }

    @Test
    fun `更低版本（服务端回滚）不提醒`() {
        assertFalse(UpdateManager.isNewer("0.1.5", "0.1.6"))
    }

    @Test
    fun `位数不齐按 0 补齐比较`() {
        assertTrue(UpdateManager.isNewer("0.1.6.1", "0.1.6"))
        assertFalse(UpdateManager.isNewer("0.1", "0.1.0"))
    }
}
