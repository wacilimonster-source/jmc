package com.jmread

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.jmread.core.JmRepository
import com.jmread.core.download.DownloadManager
import com.jmread.core.log.AppSessionState
import com.jmread.core.log.CrashReporter
import com.jmread.core.log.MainThreadWatchdog
import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.JmImageFetcher
import com.jmread.core.scramble.JmUrlKeyer
import com.jmread.core.scramble.ScrambleCache
import com.jmread.data.AuthorFavourites
import com.jmread.data.Bookmarks
import com.jmread.data.FollowFeedCache
import com.jmread.data.FollowSettings
import com.jmread.data.GridSettings
import com.jmread.data.ReaderPrefs
import com.jmread.data.ReaderStatus
import com.jmread.data.SearchHistory
import com.jmread.data.SecureAccountStore
import com.jmread.data.SourcePrefs
import com.jmread.data.UpdatedAtCache
import com.jmread.network.BcTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JmApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 崩溃取证最先装：先汇总上次会话的诊断证据（退出原因/冻结栈），再挂崩溃钩子
        CrashReporter.install(this)
        MainThreadWatchdog.start(this)
        AppSessionState.register(this)
        SourcePrefs.init(this)
        ReaderPrefs.init(this)
        GridSettings.init(this)
        SearchHistory.init(this)
        // 本地书架：未登录态详情心形直接写这里，漏初始化会让点击直接闪退
        Bookmarks.init(this)
        // 收藏同步队列：双写失败时的意图落盘（启动后统一 flush 对账补差）
        com.jmread.data.FavouriteSyncQueue.init(this)
        com.jmread.core.update.UpdateState.init(this)
        com.jmread.core.checkin.DailyCheckIn.init(this)
        // 已读/读完状态全量预热可能较慢，放到后台，UI 用状态位等待
        appScope.launch { ReaderStatus.loadAll(this@JmApp) }
        // 统一书架：装配并集条目流；已登录则启动时 flush 一次收藏同步队列
        com.jmread.core.library.LibraryRepository.init()
        appScope.launch { com.jmread.core.library.LibraryRepository.flushPendingSync() }
        AuthorFavourites.init(this)
        FollowSettings.init(this)
        FollowFeedCache.init(this)
        UpdatedAtCache.init(this)
        SecureAccountStore.init(this)
        ScrambleCache.init(this)
        DownloadManager.init(this)
        // 401 静默重登钩子装配 + 启动刷新一次 /setting（域名/版本/img_host 自愈）
        JmRepository.init()
        appScope.launch { JmRepository.launchSettingRefresh() }
        // 登录态下每日自动签到（幂等：当日成功即跳过；失败进「我的」页可见可重试）
        com.jmread.core.checkin.DailyCheckIn.maybeCheckIn()
        // 安装 BouncyCastle TLS（绕过 Cloudflare 对 BoringSSL 的指纹拦截）
        BcTls.install()
        // Coil：图片走 BC TLS + 章节图乱序还原 Fetcher（唯一图片管线装配点）
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(BcTls.imageLoaderClient)
                .components {
                    add(JmUrlKeyer())
                    add(JmImageFetcher.Factory())
                    // 管线拦截器顺序：错误取证在最外，记录最终结果；随后是管线级
                    // 重试（覆盖响应体中断/解码失败）；最内层合流，减少同图重复下载。
                    add(com.jmread.core.scramble.ImageErrorLoggingInterceptor())
                    add(com.jmread.core.scramble.CoilRetryInterceptor())
                    add(com.jmread.core.scramble.CoalescingInterceptor())
                }
                .build(),
        )
    }
}
