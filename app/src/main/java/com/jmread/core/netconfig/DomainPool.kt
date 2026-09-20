package com.jmread.core.netconfig

import com.jmread.data.SourcePrefs

/**
 * API 域名池：手填 > /setting 下发 > 内置镜像，失败即换。
 *
 * 禁漫的主要断服原因是镜像域名轮换（实测 4 镜像 + /setting 动态下发），
 * 而不是限流——所以这里只做「失败换下一个」，不做冷却计数。
 */
object DomainPool {

    /** 编译期内置镜像（2026-09-19 实测全部 /setting 200） */
    val BUILTIN_API_HOSTS = listOf(
        "www.cdngwc.cc",
        "www.cdnhjk.net",
        "www.cdngwc.net",
        "www.cdngwc.club",
    )

    /** 图片 CDN host 候选表。/setting.img_host 刷新后置顶 */
    val BUILTIN_IMAGE_HOSTS = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
        "cdn-msp.jmdanjonproxy.xyz",
    )

    /** 单 host 连续失败达到该次数即标记为坏并换下一个 */
    private const val FAILS_BEFORE_SWITCH = 2
    /** 被标记为坏的 host 的回避时长 */
    private const val BAD_HOST_BACKOFF_MS = 10 * 60 * 1000L

    @Volatile private var apiHosts: List<String> = BUILTIN_API_HOSTS
    @Volatile private var currentIdx = 0
    @Volatile private var failCount = 0
    @Volatile private var badUntil: Map<String, Long> = emptyMap()

    @Volatile private var imgHosts: List<String> = BUILTIN_IMAGE_HOSTS

    /** 供 UI 展示的当前生效域名（不含 scheme） */
    val currentApiHost: String
        get() {
            refreshHostList()
            return apiHosts[currentIdx.coerceAtMost(apiHosts.lastIndex)]
        }

    val allApiHosts: List<String> get() = apiHosts

    val imageHosts: List<String> get() = imgHosts

    /** API 请求完整基址 */
    fun apiBase(): String = "https://${currentApiHost}"

    /**
     * 取图片 host：按 id 的稳定散列分流 + host 存活顺序表。
     * 与服务端无关的稳定选择，保证同一本书的图稳定走同一 host，利于磁盘缓存与排查。
     */
    fun imageHostFor(id: String): String {
        val hosts = imgHosts
        val idx = (id.hashCodeMod(hosts.size) + badUntil.count { System.currentTimeMillis() < it.value })
            .mod(hosts.size)
        return hosts[idx]
    }

    /** 换域成功 / 请求成功时调用：记下可用 host */
    fun markGood(host: String) {
        synchronized(this) {
            failCount = 0
            if (badUntil.isNotEmpty()) badUntil = badUntil - host
        }
    }

    /** 请求失败时调用：累计到阈值把当前 host 打入回避期，游标切到下一个 */
    fun markBad(host: String) {
        synchronized(this) {
            val next = (badUntil[host] ?: 0L) + 1
            if (next >= FAILS_BEFORE_SWITCH) {
                badUntil = badUntil + (host to System.currentTimeMillis() + BAD_HOST_BACKOFF_MS)
                failCount = 0
                rotate()
            } else {
                failCount = next.toInt()
            }
        }
    }

    /** 立即切换到下一个可用 host（换域重试用） */
    fun rotate() {
        refreshHostList()
        synchronized(this) {
            currentIdx = (currentIdx + 1).mod(apiHosts.size)
            failCount = 0
        }
    }

    /**
     * /setting 解析成功后调用：
     * 下发 base_url 插到候选表次位（手填仍最优先），img_host 插到图片表首位。
     */
    fun applySetting(baseUrl: String?, imgHost: String?, version: String?) {
        synchronized(this) {
            if (!baseUrl.isNullOrBlank()) {
                val host = baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                apiHosts = (listOf(host) + apiHosts.filterNot { it.equals(host, true) })
                // 下发域名放首位但不动游标所在 host（可能是刚验证可用的）
                currentIdx = apiHosts.indexOfFirst { it.equals(host, true) }.coerceAtLeast(0)
                badUntil = badUntil - host
            }
            if (!imgHost.isNullOrBlank()) {
                val h = imgHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                imgHosts = (listOf(h) + imgHosts.filterNot { it.equals(h, true) }).distinct()
            }
        }
    }

    /** 仅供单测复位状态（单例在 JVM 内跨测试共享） */
    fun resetForTest() {
        synchronized(this) {
            apiHosts = BUILTIN_API_HOSTS
            currentIdx = 0
            failCount = 0
            badUntil = emptyMap()
            imgHosts = BUILTIN_IMAGE_HOSTS
        }
    }

    private fun refreshHostList() {
        // SourcePrefs 未初始化时（JVM 单测 / 早期）跳过手填合并，用内置表
        val manual = runCatching { SourcePrefs.current().jmBaseUrl }.getOrNull()
        if (!manual.isNullOrBlank()) {
            val host = manual.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (apiHosts.firstOrNull() != host) {
                apiHosts = listOf(host) + BUILTIN_API_HOSTS
                currentIdx = 0
            }
        }
    }

    private fun String.hashCodeMod(size: Int): Int {
        // 数字 id 场景下与 Java String.hashCode 一致
        var h = 0
        for (c in this) h = (31 * h + c.code)
        return Math.floorMod(h, size)
    }
}
