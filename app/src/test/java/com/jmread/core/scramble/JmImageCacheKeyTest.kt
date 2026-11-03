package com.jmread.core.scramble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** P0-02：重试次数不进入持久缓存身份；自动/强制模式缓存隔离。 */
class JmImageCacheKeyTest {

    private val photo = "https://img.example/media/photos/123/00001.webp"

    @Test
    fun `自动模式使用 de2 身份`() {
        assertEquals("$photo#de2", jmImageCacheKey(photo))
    }

    @Test
    fun `重试后缀不进入缓存身份`() {
        assertEquals(
            jmImageCacheKey(photo),
            jmImageCacheKey("$photo#retry3"),
        )
    }

    @Test
    fun `强制倒序与自动模式缓存隔离`() {
        val auto = jmImageCacheKey(photo)
        val forced = jmImageCacheKey("$photo#m1")
        assertNotEquals(auto, forced)
        assertEquals("$photo#de2#m1", forced)
    }

    @Test
    fun `强制原样使用独立缓存身份`() {
        assertEquals("$photo#raw", jmImageCacheKey("$photo#m0"))
    }

    @Test
    fun `重试后缀与强制模式组合仍稳定`() {
        assertEquals(
            jmImageCacheKey("$photo#m1"),
            jmImageCacheKey("$photo#retry2#m1"),
        )
    }

    @Test
    fun `非章节图保持原始身份`() {
        val cover = "https://img.example/media/albums/1.jpg"
        assertEquals(cover, jmImageCacheKey(cover))
    }

    @Test
    fun `预取标记不进入缓存身份`() {
        assertEquals(jmImageCacheKey(photo), jmImageCacheKey(jmPrefetchUrl(photo)))
        assertEquals(
            jmImageCacheKey("$photo#m1"),
            jmImageCacheKey(jmPrefetchUrl("$photo#m1")),
        )
    }

    @Test
    fun `预取 URL 保留手动后缀在最后`() {
        assertEquals("$photo#prefetch", jmPrefetchUrl(photo))
        assertEquals("$photo#prefetch#m1", jmPrefetchUrl("$photo#m1"))
        assertEquals("$photo#prefetch#m0", jmPrefetchUrl("$photo#m0"))
    }

    @Test
    fun `干净 URL 剥掉重试与预取标记`() {
        assertEquals(photo, jmCleanUrl(photo))
        assertEquals(photo, jmCleanUrl("$photo#retry3"))
        assertEquals(photo, jmCleanUrl("$photo#prefetch"))
        assertEquals(photo, jmCleanUrl("$photo#retry3#prefetch#m1"))
    }
}
