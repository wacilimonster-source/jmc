package com.jmread.core.netconfig

import com.jmread.data.SourcePrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
    /** 各 host 连续失败次数。此前拿 badUntil 的时间戳 +1 当次数，未标记过的 host 永远是 1，阈值形同虚设 */
    @Volatile private var failCounts: Map<String, Int> = emptyMap()
    @Volatile private var badUntil: Map<String, Long> = emptyMap()

    /** 上一次生效的手填域名（null/空 = 自动）。用于「清空手填后恢复自动线路」 */
    @Volatile private var lastManualHost: String? = null

    /** 已提醒过切换的手填域名：同一故障不重复弹 Snackbar（离线时各 host 反复失败） */
    @Volatile private var notifiedManualHost: String? = null

    @Volatile private var imgHosts: List<String> = BUILTIN_IMAGE_HOSTS

    /** 默认手填域名读取：规整规则与候选表重建保持一致 */
    private val defaultManualHostResolver: () -> String? = {
        runCatching { SourcePrefs.current().jmBaseUrl }.getOrNull()
            ?.let { normalizeHost(it) }
            ?.takeIf { it.isNotBlank() }
    }

    /** 去掉 scheme/尾斜杠，得到候选表使用的 host 形态（设置页标签比较也用它） */
    fun normalizeHost(raw: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * 手填域名读取（含规整：去 scheme/尾斜杠、空白判空）。
     * SourcePrefs 未初始化（JVM 单测 / 进程极早期）时返回 null。
     * 抽成可替换的 resolver 是为了单测能注入手填值（DataStore 单例依赖 Android Context）。
     */
    internal var manualHostResolver: () -> String? = defaultManualHostResolver

    /**
     * 生效域名变化信号：rotate / 手填变更 / /setting 下发重建候选表时自增。
     * 设置页「当前生效域名」用它保持实时，避免自愈切走后仍显示旧域名。
     */
    private val _hostVersion = MutableStateFlow(0)
    val hostVersion: StateFlow<Int> = _hostVersion

    /** 手填域名失败被打入回避期（即将被自动切走）时发出，负载为该手填域名。
     *  MainScreen 订阅弹一次 Snackbar：自愈行为正确，但设置页的手填框仍留着坏域名，
     *  不说明一句用户会以为坏域名还在生效（全量实测观察项）。 */
    private val _manualHostFailover = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val manualHostFailover: SharedFlow<String> = _manualHostFailover

    /** 图片 CDN 的回避期表（与 API 的 badUntil 分开：图片走独立 CDN，故障互不相关） */
    @Volatile private var badImageUntil: Map<String, Long> = emptyMap()

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
     *
     * 散列池排除处于回避期的 host：否则散列可能选中一个已知不可用的 CDN，
     * 该书的图会一直加载失败（实测 /setting 下发的 img_host 每次请求都可能不同，
     * 而 CDN 会按区域/线路被 DNS 或运营商屏蔽）。
     */
    fun imageHostFor(id: String): String {
        val hosts = imgHosts
        if (hosts.isEmpty()) return BUILTIN_IMAGE_HOSTS.first()
        val now = System.currentTimeMillis()
        val healthy = hosts.filter { (badImageUntil[it] ?: 0L) <= now }
        val pool = healthy.ifEmpty { hosts }
        return pool[id.hashCodeMod(pool.size)]
    }

    /**
     * 图片 host 的尝试顺序：当前 host 优先（保持缓存命中），其余按健康度排序，
     * 坏 host 排到最后当兜底（不删——所有 host 都坏时总得试一个）。
     *
     * 存在的理由：图片 CDN 与 API 是两套域名，[markBad] 只管 API 域。
     * 早期版本图片只请求一个 host，失败就直接报「加载失败」，
     * 导致某个 CDN 被屏蔽时整本书的图都打不开且永不恢复。
     */
    fun imageHostOrder(current: String): List<String> {
        val now = System.currentTimeMillis()
        val (healthy, dead) = imgHosts.partition { (badImageUntil[it] ?: 0L) <= now }
        fun put(head: List<String>, h: String) = head.filter { it == h } + head.filter { it != h }
        val ordered = put(healthy, current) + put(dead, current)
        return ordered.distinct().ifEmpty { listOf(current) }
    }

    /** 图片 host 请求成功：解除回避 */
    fun markImageGood(host: String) {
        synchronized(this) {
            if (badImageUntil.isNotEmpty()) badImageUntil = badImageUntil - host
        }
    }

    /** 图片 host 请求失败：立即打入回避期（图片是高频请求，不累计阈值） */
    fun markImageBad(host: String) {
        synchronized(this) {
            badImageUntil = badImageUntil + (host to System.currentTimeMillis() + BAD_HOST_BACKOFF_MS)
        }
    }

    /** 换域成功 / 请求成功时调用：记下可用 host */
    fun markGood(host: String) {
        synchronized(this) {
            if (failCounts.isNotEmpty()) failCounts = failCounts - host
            if (badUntil.isNotEmpty()) badUntil = badUntil - host
        }
    }

    /**
     * 请求失败时调用：累计到阈值把当前 host 打入回避期。
     * 不在这里 rotate——调用方（JmClient）失败后本来就会轮换，
     * 这里再转一次会跳过下一条可用线路。
     */
    fun markBad(host: String) {
        // 手填域名首次失败即提醒：JmClient 失败后会立即 rotate 切走，
        // 该域名在本次进程内不会再被尝试，等不到「连续 2 次」阈值
        notifyManualFailoverIfNeeded(host)
        synchronized(this) {
            val next = (failCounts[host] ?: 0) + 1
            if (next >= FAILS_BEFORE_SWITCH) {
                badUntil = badUntil + (host to System.currentTimeMillis() + BAD_HOST_BACKOFF_MS)
                failCounts = failCounts - host
            } else {
                failCounts = failCounts + (host to next)
            }
        }
    }

    /** 失败的 host 是当前手填域名时发一次提醒（见 [manualHostFailover]）。 */
    private fun notifyManualFailoverIfNeeded(host: String) {
        if (host != manualHostResolver()) return
        synchronized(this) {
            // 同一次故障只弹一次：全线路离线时各 host 会反复失败，避免刷屏
            if (notifiedManualHost == host) return
            notifiedManualHost = host
        }
        _manualHostFailover.tryEmit(host)
    }

    /** 立即切换到下一个可用 host（换域重试用）；跳过处于回避期的 host */
    fun rotate() {
        refreshHostList()
        synchronized(this) {
            if (apiHosts.isEmpty()) return
            val now = System.currentTimeMillis()
            val size = apiHosts.size
            for (step in 1..size) {
                val idx = (currentIdx + step).mod(size)
                if ((badUntil[apiHosts[idx]] ?: 0L) <= now) {
                    currentIdx = idx
                    _hostVersion.value = _hostVersion.value + 1
                    return
                }
            }
            // 所有 host 都在回避期：仍要前进一格当兜底，不能永远卡在同一个坏 host
            currentIdx = (currentIdx + 1).mod(size)
            _hostVersion.value = _hostVersion.value + 1
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
                // 手填域名优先级最高；/setting 下发值插在手填之后（没有手填时才放首位）。
                // 旧实现无条件把下发域名放首位，用户手填的域名会被悄悄顶掉。
                val manual = manualHostResolver()
                apiHosts = if (manual != null) {
                    (listOf(manual, host) + apiHosts.filterNot { it == manual || it == host })
                        .distinct()
                } else {
                    (listOf(host) + apiHosts.filterNot { it == host }).distinct()
                }
                currentIdx = 0
                lastManualHost = manual
                badUntil = badUntil - host
                failCounts = failCounts - host
                if (manual != null) {
                    // 用户重新指定了该域名：给它一次全新的尝试机会
                    badUntil = badUntil - manual
                    failCounts = failCounts - manual
                }
                _hostVersion.value = _hostVersion.value + 1
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
            failCounts = emptyMap()
            badUntil = emptyMap()
            imgHosts = BUILTIN_IMAGE_HOSTS
            badImageUntil = emptyMap()
            lastManualHost = null
            notifiedManualHost = null
            manualHostResolver = defaultManualHostResolver
        }
    }

    /**
     * 手填域名的生效/撤销检测。
     *
     * 旧实现只在「手填不是首位」时重建列表，用户把设置里的手填域名清空后，
     * 旧手填域名会一直留在首位（直到进程重启），UI 却显示「自动」。
     * 这里记住上一次生效的手填值：变更时重建候选表，清空时回到内置镜像。
     */
    private fun refreshHostList() {
        // SourcePrefs 未初始化时（JVM 单测 / 早期）resolver 返回 null，走内置表
        val manual = manualHostResolver()
        if (manual == lastManualHost) return
        lastManualHost = manual
        synchronized(this) {
            // 手填值变更 = 新一轮配置：允许对新值再次提醒
            notifiedManualHost = null
            apiHosts = if (manual != null) {
                listOf(manual) + BUILTIN_API_HOSTS
            } else {
                BUILTIN_API_HOSTS
            }
            currentIdx = 0
            failCounts = failCounts.filterKeys { it in apiHosts }
            badUntil = badUntil.filterKeys { it in apiHosts }
            if (manual != null) {
                // 手填值刚生效/刚变化：之前对该域名的回避不再沿用
                failCounts = failCounts - manual
                badUntil = badUntil - manual
            }
            _hostVersion.value = _hostVersion.value + 1
        }
    }

    private fun String.hashCodeMod(size: Int): Int {
        // 数字 id 场景下与 Java String.hashCode 一致
        var h = 0
        for (c in this) h = (31 * h + c.code)
        return Math.floorMod(h, size)
    }
}
