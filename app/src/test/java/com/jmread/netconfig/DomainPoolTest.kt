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

    /**
     * 图片 CDN 的失败回避。早期版本图片只请求一个散列 host，失败即「加载失败」且永不恢复；
     * 而 /setting 下发的 img_host 每次请求都可能不同、各 CDN 又会被 DNS 按区域屏蔽。
     */
    @Test
    fun `imageHostOrder 把当前 host 排最前`() {
        val current = DomainPool.imageHosts[2]
        assertEquals(current, DomainPool.imageHostOrder(current).first())
        // 候选不重复且覆盖全部图片 host
        val order = DomainPool.imageHostOrder(current)
        assertEquals(order.distinct().size, order.size)
        assertEquals(DomainPool.imageHosts.toSet(), order.toSet())
    }

    @Test
    fun `标记为坏的图片 host 被排到最后`() {
        val current = DomainPool.imageHosts[2]
        DomainPool.markImageBad(current)
        val order = DomainPool.imageHostOrder(current)
        assertEquals("坏 host 必须排到最后兜底", current, order.last())
        assertTrue("坏 host 仍保留在候选里（全坏时总得试一个）", current in order)
    }

    @Test
    fun `坏图片 host 不再被散列选中`() {
        // 把所有 host 都标坏后，散列仍应返回某个 host（不能抛也不能返回空）
        DomainPool.imageHosts.forEach { DomainPool.markImageBad(it) }
        assertTrue(DomainPool.imageHostFor("646603") in DomainPool.imageHosts)

        // 只留一个健康 host 时，散列必然选中它
        DomainPool.resetForTest()
        val healthy = DomainPool.imageHosts[1]
        DomainPool.imageHosts.filter { it != healthy }.forEach { DomainPool.markImageBad(it) }
        assertEquals(healthy, DomainPool.imageHostFor("646603"))
    }

    @Test
    fun `图片 host 恢复健康后回到候选首位`() {
        val host = DomainPool.imageHosts[2]
        DomainPool.markImageBad(host)
        assertEquals(host, DomainPool.imageHostOrder(host).last())
        DomainPool.markImageGood(host)
        assertEquals(host, DomainPool.imageHostOrder(host).first())
    }
}
