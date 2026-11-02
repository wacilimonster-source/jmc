package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jmread.core.AppScope
import com.jmread.core.log.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.favSyncDataStore by preferencesDataStore(name = "jm_favsync")

/**
 * 收藏同步队列（docs/unified-library-design.md §4.2）。
 *
 * 收藏双写中云端侧失败（断网/401/超时）时，把「意图」落盘到这里；
 * flush 时以云端收藏列表为真相做对账补差，绝不盲目重放翻转请求。
 * 同一 id 的新意图直接覆盖旧意图——翻转端点下这是唯一安全的合并方式。
 */
object FavouriteSyncQueue {

    @kotlinx.serialization.Serializable
    data class PendingOp(
        val comicId: String,
        /** true=意图收藏，false=意图取消收藏 */
        val add: Boolean,
        val ts: Long,
        /** 意图所属账号（账号名优先，回退 uid）；空 = 旧版本遗留，按当前账号处理 */
        val owner: String = "",
    )

    private val KEY = stringPreferencesKey("pending_json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<List<PendingOp>>(emptyList())
    val pending: StateFlow<List<PendingOp>> = _pending.asStateFlow()

    /** 队列读改写互斥：flush 的删除不能覆盖 flush 期间新写入的意图 */
    private val mutex = Mutex()
    /** 串行化整轮 flush，避免两个入口同时对同一 id 翻转 */
    private val flushMutex = Mutex()

    /** 必须由 JmApp.onCreate 调用（与 Bookmarks 同款守卫：漏 init 只退化为内存态，不崩） */
    private var context: Context? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
        AppScope.launch { load() }
    }

    private fun currentOwner(): String =
        SourcePrefs.current().jmAccount.orEmpty()
            .ifBlank { SourcePrefs.current().jmUid.orEmpty() }

    private suspend fun load() {
        val ctx = context ?: return
        runCatching {
            val raw = ctx.favSyncDataStore.data.first()[KEY]
            val list = if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<PendingOp>>(raw) }.getOrDefault(emptyList())
            mutex.withLock {
                // 预热期间新增的意图优先保留
                _pending.value = (_pending.value + list).distinctBy { it.comicId }
            }
        }
    }

    /** 记录一条待同步意图；同 id 只保留最新意图 */
    suspend fun enqueue(comicId: String, add: Boolean) {
        val op = PendingOp(comicId, add, System.currentTimeMillis(), currentOwner())
        mutex.withLock {
            val updated = listOf(op) + _pending.value.filterNot { it.comicId == comicId }
            _pending.value = updated
            persist(updated)
        }
    }

    /**
     * 出队。带 [ts] 时只删除同一版意图：
     * flush 期间用户又点了同一个 id 时，新意图不会被旧 flush 误删。
     */
    private suspend fun remove(comicId: String, ts: Long? = null) {
        mutex.withLock {
            val updated = _pending.value.filterNot {
                it.comicId == comicId && (ts == null || it.ts == ts)
            }
            if (updated.size == _pending.value.size) return@withLock
            _pending.value = updated
            persist(updated)
        }
    }

    private suspend fun persist(list: List<PendingOp>) {
        val ctx = context ?: return
        runCatching {
            ctx.favSyncDataStore.edit { it[KEY] = json.encodeToString(list) }
        }
    }

    data class FlushReport(
        /** 云端已符合意图、零请求出队的条数 */
        val dropped: Int,
        /** 实际翻转成功并出队的条数 */
        val toggled: Int,
        /** 仍留在队列的条数（下次 flush 再试） */
        val failed: Int,
        val error: String? = null,
    ) {
        val settled: Int get() = dropped + toggled
    }

    /**
     * 对账 flush：拉云端收藏 id 集合 → 按 [FavouriteSyncPlan] 补差。
     *
     * @param reconcile 强制拉取云端收藏 id 集合；失败应抛异常（此时整批保留）
     * @param toggle 翻转端点调用，返回翻转后的收藏态（成功时 add 意图应为 true / remove 应为 false）
     */
    suspend fun flush(
        reconcile: suspend () -> Set<String>,
        toggle: suspend (String) -> Boolean,
    ): FlushReport = flushMutex.withLock {
        val owner = currentOwner()
        val ops = mutex.withLock { _pending.value }
        if (ops.isEmpty()) return@withLock FlushReport(0, 0, 0)
        // 只处理当前账号的意图；其他账号的留在队列等它下次登录，绝不在换账号后误翻转
        val matching = ops.filter { it.owner.isBlank() || it.owner == owner }
        val skipped = ops.size - matching.size
        if (matching.isEmpty()) return@withLock FlushReport(0, 0, skipped)
        val cloudIds = try {
            reconcile()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 对账失败时一张牌都不能出：没有真相就翻转 = 可能翻错方向
            LogStore.log("Library", "W", "同步队列 flush 失败（对账不可用）：${e.message}")
            return@withLock FlushReport(0, 0, ops.size, e.message)
        }
        var dropped = 0
        var toggled = 0
        var failed = skipped
        var lastError: String? = null
        for (step in FavouriteSyncPlan.plan(cloudIds, matching)) {
            when (step.action) {
                FavouriteSyncPlan.Action.DROP -> {
                    remove(step.comicId, step.ts)
                    dropped++
                }
                FavouriteSyncPlan.Action.TOGGLE_ADD -> {
                    val now = runCatching { toggle(step.comicId) }.getOrNull()
                    if (now == true) {
                        remove(step.comicId, step.ts)
                        toggled++
                    } else {
                        failed++
                        lastError = if (now == null) "网络失败" else "服务端仍返回未收藏"
                    }
                }
                FavouriteSyncPlan.Action.TOGGLE_REMOVE -> {
                    val now = runCatching { toggle(step.comicId) }.getOrNull()
                    if (now == false) {
                        remove(step.comicId, step.ts)
                        toggled++
                    } else {
                        failed++
                        lastError = if (now == null) "网络失败" else "服务端仍返回收藏态"
                    }
                }
            }
        }
        LogStore.log("Library", "I", "同步队列 flush：出队 ${dropped + toggled}（补翻转 $toggled），留 $failed")
        FlushReport(dropped, toggled, failed, lastError)
    }
}
