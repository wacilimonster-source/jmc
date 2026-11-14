package com.jmread.netconfig

import com.jmread.core.netconfig.DomainPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * API host 的失败计数必须是「次数」而不是时间戳：
     * 旧实现 `(badUntil[host] ?: 0) + 1` 对从未被标记的 host 永远算出 1，
     * 连续失败达到阈值也不会进入回避期。
     */
    @Test
    fun `API host 连续失败达到阈值后被 rotate 跳过`() {
        val bad = DomainPool.currentApiHost
        repeat(2) { DomainPool.markBad(bad) }
        DomainPool.rotate()
        assertNotEquals(bad, DomainPool.currentApiHost)
    }

    @Test
    fun `API host 单次失败不会立即拉黑`() {
        val host = DomainPool.currentApiHost
        DomainPool.markBad(host)
        val seen = mutableSetOf<String>()
        repeat(DomainPool.allApiHosts.size) {
            seen += DomainPool.currentApiHost
            DomainPool.rotate()
        }
        assertTrue("单次失败仍应在轮换中出现", host in seen)
    }

    /**
     * 全量实测观察项：手填坏域名被自愈切走后没有任何用户可见说明。
     * JmClient 一次失败就会 rotate 切走，手填域名等不到「连续 2 次」阈值——
     * 提醒必须挂在首次失败上，且同一故障只弹一次。
     */
    @Test
    fun `手填域名首次失败即发出切换提醒且不重复`() = runTest {
        // DataStore 依赖 Android Context：单测注入 resolver 替身
        DomainPool.manualHostResolver = { "bad.invalid" }
        assertEquals("手填域名应位于候选首位", "bad.invalid", DomainPool.currentApiHost)
        val events = mutableListOf<String>()
        // UNDISPATCHED 先完成订阅：replay=0 的事件不会因订阅未就绪而丢
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            DomainPool.manualHostFailover.collect { events += it }
        }
        DomainPool.markBad("bad.invalid")
        runCurrent()
        assertEquals("首次失败就应提醒", listOf("bad.invalid"), events)
        DomainPool.markBad("bad.invalid")
        runCurrent()
        assertEquals("同一故障只提醒一次（离线时各 host 会反复失败）", 1, events.size)
        job.cancel()
    }

    @Test
    fun `非手填域名失败不发出切换提醒`() = runTest {
        DomainPool.manualHostResolver = { "manual.example" }
        assertEquals("manual.example", DomainPool.currentApiHost)
        val received = CompletableDeferred<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            DomainPool.manualHostFailover.collect { received.complete(it) }
        }
        // 内置镜像失败两次：进入回避期但不触发手填切换提醒
        repeat(2) { DomainPool.markBad(DomainPool.BUILTIN_API_HOSTS.first()) }
        assertFalse("内置镜像失败与手填切换无关", received.isCompleted)
        job.cancel()
    }

    /** 设置页「当前生效域名」依赖 hostVersion 实时刷新 */
    @Test
    fun `生效域名变化信号在换域与手填变更时自增`() {
        val v0 = DomainPool.hostVersion.value
        DomainPool.rotate()
        assertTrue("rotate 换域必须广播版本", DomainPool.hostVersion.value > v0)
        val v1 = DomainPool.hostVersion.value
        DomainPool.manualHostResolver = { "manual.example" }
        // currentApiHost 内部触发 refreshHostList：手填生效即候选表重建
        DomainPool.currentApiHost
        assertTrue("手填变更必须广播版本", DomainPool.hostVersion.value > v1)
    }

    @Test
    fun `normalizeHost 规整手填输入`() {
        assertEquals("www.a.cc", DomainPool.normalizeHost(" https://www.a.cc/ "))
        assertEquals("www.b.cc", DomainPool.normalizeHost("http://www.b.cc"))
        assertEquals("", DomainPool.normalizeHost("  "))
    }

    @Test
    fun `resetForTest 还原手填 resolver`() {
        DomainPool.manualHostResolver = { "manual.example" }
        DomainPool.resetForTest()
        assertEquals("复位后应回到内置镜像首位", DomainPool.BUILTIN_API_HOSTS.first(), DomainPool.currentApiHost)
    }
}
