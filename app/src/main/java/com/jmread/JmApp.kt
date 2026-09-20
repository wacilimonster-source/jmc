package com.jmread

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.jmread.core.JmRepository
import com.jmread.core.download.DownloadManager
import com.jmread.core.netconfig.DomainPool
import com.jmread.core.scramble.JmImageFetcher
import com.jmread.core.scramble.JmUrlKeyer
import com.jmread.core.scramble.ScrambleCache
import com.jmread.data.AuthorFavourites
import com.jmread.data.CategorySettings
import com.jmread.data.FollowFeedCache
import com.jmread.data.FollowSettings
import com.jmread.data.GridSettings
import com.jmread.data.ReaderPrefs
import com.jmread.data.ReaderStatus
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
        SourcePrefs.init(this)
        ReaderPrefs.init(this)
        GridSettings.init(this)
        // 已读/读完状态全量预热可能较慢，放到后台，UI 用状态位等待
        appScope.launch { ReaderStatus.loadAll(this@JmApp) }
        CategorySettings.init(this)
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
        // 安装 BouncyCastle TLS（绕过 Cloudflare 对 BoringSSL 的指纹拦截）
        BcTls.install()
        // Coil：图片走 BC TLS + 章节图乱序还原 Fetcher（唯一图片管线装配点）
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(BcTls.imageLoaderClient)
                .components {
                    add(JmUrlKeyer())
                    add(JmImageFetcher.Factory())
                }
                .build(),
        )
    }
}
