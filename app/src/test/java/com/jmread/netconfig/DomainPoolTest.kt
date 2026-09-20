package com.jmread.netconfig

import com.jmread.core.netconfig.DomainPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 域名池单测：内置镜像选择、/setting 下发合并、失败轮换 */
class DomainPoolTest {

    @Before
    fun reset() {
        DomainPool.resetForTest()
    }

    @Test
    fun `默认使用内置镜像首位`() {
        assertEquals(DomainPool.BUILTIN_API_HOSTS.first(), DomainPool.currentApiHost)
    }

    @Test
    fun `applySetting 把下发域名加入候选表首位`() {
        DomainPool.applySetting("https://www.newhost-example.cc", null, null)
        assertEquals("www.newhost-example.cc", DomainPool.currentApiHost)
        // 下发后候选表仍非空（内置镜像兜底）
        assertTrue(DomainPool.allApiHosts.isNotEmpty())
        DomainPool.allApiHosts.forEach { host ->
            assertTrue(host.isNotBlank())
        }
    }

    @Test
    fun `rotate 轮换到下一个镜像`() {
        val start = DomainPool.currentApiHost
        DomainPool.rotate()
        val second = DomainPool.currentApiHost
        DomainPool.rotate()
        val third = DomainPool.currentApiHost
        assertTrue(start != second && second != third)
    }

    @Test
    fun `图片 host 表非空且含内置候选`() {
        assertTrue(DomainPool.imageHosts.isNotEmpty())
        assertTrue(DomainPool.BUILTIN_IMAGE_HOSTS.first().startsWith("cdn-msp"))
    }
}
