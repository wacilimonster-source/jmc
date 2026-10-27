package com.jmread.scramble

import com.jmread.core.scramble.isJmImageUrl
import com.jmread.core.scramble.jmImageCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coil 接管范围回归。
 *
 * 自定义 Fetcher/Keyer 注册为 Uri 后会看到所有图片 URL；只有禁漫章节图/封面
 * 才允许走「CDN 换线 + 乱序判定」，头像、外链图、本地文件必须放回 Coil 默认实现，
 * 否则会被误改写到图片 CDN host（非 /media 路径会 404）。
 */
class JmImageFetcherRouteTest {

    @Test
    fun `章节图由自定义管线接管`() {
        assertTrue(isJmImageUrl("https://cdn-msp.jmapiproxy1.cc/media/photos/254931/00001.webp"))
        assertTrue(isJmImageUrl("https://cdn-msp3.jmapinodeudzn.net/media/photos/254931/00001.webp#m1"))
    }

    @Test
    fun `封面由自定义管线接管`() {
        assertTrue(isJmImageUrl("https://cdn-msp.jmapiproxy1.cc/media/albums/254931.jpg"))
        assertTrue(isJmImageUrl("https://cdn-msp.jmapiproxy1.cc/media/albums/254931_3x4.jpg"))
    }

    @Test
    fun `本地已下载页交给默认 FileUriFetcher`() {
        assertFalse(isJmImageUrl("file:/data/user/0/com.jmread/files/downloads/254931/1/page_1.jpg"))
        assertFalse(isJmImageUrl("content://media/external/images/1"))
    }

    @Test
    fun `头像与外链图不会被改写 host`() {
        assertFalse(isJmImageUrl("https://example.com/avatar/1.png"))
        assertFalse(isJmImageUrl("https://cdn-msp.jmapiproxy1.cc/static/resources/images/badge.png"))
    }

    @Test
    fun `非 http 协议不接管`() {
        assertFalse(isJmImageUrl("data:image/png;base64,AAAA"))
        assertFalse(isJmImageUrl("not a url"))
    }

    @Test
    fun `章节图缓存键带 de 后缀`() {
        val url = "https://cdn-msp.jmapiproxy1.cc/media/photos/254931/00001.webp"
        assertEquals("$url#de", jmImageCacheKey(url))
    }

    @Test
    fun `手动覆盖请求使用独立缓存键`() {
        val url = "https://cdn-msp.jmapiproxy1.cc/media/photos/254931/00001.webp"
        assertEquals("$url#m1", jmImageCacheKey("$url#m1"))
        assertEquals("$url#m0", jmImageCacheKey("$url#m0"))
    }

    @Test
    fun `封面缓存键保持原 URL`() {
        val url = "https://cdn-msp.jmapiproxy1.cc/media/albums/254931.jpg"
        assertEquals(url, jmImageCacheKey(url))
    }
}
