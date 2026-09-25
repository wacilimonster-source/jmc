package com.jmread.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jmread.core.AppScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "jm_prefs")

private object Keys {
    /** 登录会话（/login 返回的 data.s，请求时作 Cookie: AVS={s}） */
    val JM_TOKEN = stringPreferencesKey("jm_token")
    /** 登录账号名（仅展示用） */
    val JM_ACCOUNT = stringPreferencesKey("jm_account")
    /** 登录用户 uid（/daily?user_id= 等端点必传；2026-09-25 实测） */
    val JM_UID = stringPreferencesKey("jm_uid")
    /** 用户手填 API 域名（空 = 自动） */
    val JM_BASE = stringPreferencesKey("jm_base")
    /** /setting 下发的 base_url（最近一次成功解析值） */
    val JM_RESOLVED_BASE = stringPreferencesKey("jm_resolved_base")
    /** /setting 下发的 img_host（最近一次成功解析值） */
    val JM_RESOLVED_IMG = stringPreferencesKey("jm_resolved_img")
    /** /setting 下发的 jm3_version（签名头携带） */
    val JM_VERSION = stringPreferencesKey("jm_version")
    /** 18+ 确认（首启一次性） */
    val AGE_CONFIRMED = booleanPreferencesKey("age_confirmed")
}

/**
 * DataStore 封装：会话 / 域名 / 版本号。
 *
 * 访问范式：
 * - `init()` 在后台协程预热内存缓存；预热完成后所有 getter 都是纯内存读取，零阻塞。
 * - getter 保留 `runBlocking` 兜底分支，仅在"预热未完成 / 登出清空缓存"的极小窗口内触发。
 * - 网络层等已在协程上下文的调用方请使用 suspend 版本。
 */
class SourcePrefs private constructor(private val appContext: Context) {

    companion object {
        private lateinit var instance: SourcePrefs

        fun init(context: Context) {
            instance = SourcePrefs(context.applicationContext)
            AppScope.launch { instance.loadCache() }
        }

        fun current(): SourcePrefs = instance
    }

    // ---------- 内存缓存（热点值） ----------
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAccount: String? = null
    @Volatile private var cachedUid: String? = null
    @Volatile private var cachedManualBase: String? = null
    @Volatile private var cachedResolvedBase: String? = null
    @Volatile private var cachedResolvedImg: String? = null
    @Volatile private var cachedVersion: String? = null
    @Volatile private var cachedAgeConfirmed: Boolean? = null

    private fun loadCache() {
        runCatching {
            runBlocking {
                val prefs = appContext.dataStore.data.first()
                cachedToken = prefs[Keys.JM_TOKEN]?.takeIf { it.isNotEmpty() }
                cachedAccount = prefs[Keys.JM_ACCOUNT]
                cachedUid = prefs[Keys.JM_UID]?.takeIf { it.isNotEmpty() }
                cachedManualBase = prefs[Keys.JM_BASE]?.takeIf { it.isNotEmpty() }
                cachedResolvedBase = prefs[Keys.JM_RESOLVED_BASE]?.takeIf { it.isNotEmpty() }
                cachedResolvedImg = prefs[Keys.JM_RESOLVED_IMG]?.takeIf { it.isNotEmpty() }
                cachedVersion = prefs[Keys.JM_VERSION]?.takeIf { it.isNotEmpty() }
                cachedAgeConfirmed = prefs[Keys.AGE_CONFIRMED] ?: false
            }
        }
    }

    // ---------- 登录态 ----------

    val isLoggedIn: Boolean get() = !jmToken.isNullOrEmpty()

    val jmToken: String?
        get() = cachedToken
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.JM_TOKEN]?.takeIf { it.isNotEmpty() }
            }

    val jmAccount: String? get() = cachedAccount

    /** 登录用户 uid（未登录/旧会话为 null——签到月历需要它） */
    val jmUid: String?
        get() = cachedUid
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.JM_UID]?.takeIf { it.isNotEmpty() }
            }

    suspend fun setJmLogin(token: String, account: String, uid: String = "") {
        cachedToken = token
        cachedAccount = account
        cachedUid = uid.takeIf { it.isNotEmpty() }
        appContext.dataStore.edit {
            it[Keys.JM_TOKEN] = token
            it[Keys.JM_ACCOUNT] = account
            if (uid.isNotEmpty()) it[Keys.JM_UID] = uid
        }
    }

    suspend fun clearJmLogin() {
        cachedToken = null
        cachedAccount = null
        cachedUid = null
        appContext.dataStore.edit {
            it.remove(Keys.JM_TOKEN)
            it.remove(Keys.JM_ACCOUNT)
            it.remove(Keys.JM_UID)
        }
    }

    // ---------- 域名与版本 ----------

    /** 用户手填 API 域名（null/空 = 自动模式） */
    val jmBaseUrl: String?
        get() = cachedManualBase
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.JM_BASE]?.takeIf { it.isNotEmpty() }
            }

    suspend fun setJmBaseUrl(value: String?) {
        cachedManualBase = value?.trim()?.takeIf { it.isNotEmpty() }
        appContext.dataStore.edit {
            if (cachedManualBase == null) it.remove(Keys.JM_BASE) else it[Keys.JM_BASE] = cachedManualBase!!
        }
    }

    val resolvedBase: String? get() = cachedResolvedBase

    suspend fun setResolvedBase(value: String) {
        cachedResolvedBase = value
        appContext.dataStore.edit { it[Keys.JM_RESOLVED_BASE] = value }
    }

    val resolvedImgHost: String? get() = cachedResolvedImg

    suspend fun setResolvedImgHost(value: String) {
        cachedResolvedImg = value
        appContext.dataStore.edit { it[Keys.JM_RESOLVED_IMG] = value }
    }

    /** 签名头携带的客户端版本号：/setting.jm3_version 刷新，无下发时用编译期兜底 */
    val jmVersion: String?
        get() = cachedVersion
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.JM_VERSION]?.takeIf { it.isNotEmpty() }
            }

    suspend fun setJmVersion(value: String) {
        cachedVersion = value
        appContext.dataStore.edit { it[Keys.JM_VERSION] = value }
    }

    // ---------- 18+ 确认 ----------

    val ageConfirmed: Boolean
        get() = cachedAgeConfirmed
            ?: runBlocking {
                appContext.dataStore.data.first()[Keys.AGE_CONFIRMED] ?: false
            }.also { cachedAgeConfirmed = it }

    suspend fun setAgeConfirmed() {
        cachedAgeConfirmed = true
        appContext.dataStore.edit { it[Keys.AGE_CONFIRMED] = true }
    }
}
