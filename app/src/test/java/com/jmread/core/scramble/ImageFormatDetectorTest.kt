package com.jmread.core.scramble

import org.junit.Assert.assertEquals
import org.junit.Test

/** P0-06：导出/保存按魔数识别真实编码。 */
class ImageFormatDetectorTest {

    @Test
    fun `识别 webp`() {
        val bytes = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        assertEquals("webp", ImageFormatDetector.detect(bytes).extension)
    }

    @Test
    fun `识别 jpeg`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("jpg", ImageFormatDetector.detect(bytes).extension)
        assertEquals("image/jpeg", ImageFormatDetector.detect(bytes).mimeType)
    }

    @Test
    fun `识别 png`() {
        val bytes = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals("png", ImageFormatDetector.detect(bytes).extension)
    }

    @Test
    fun `识别 gif`() {
        val bytes = "GIF8".toByteArray() + byteArrayOf(0, 0)
        assertEquals("gif", ImageFormatDetector.detect(bytes).extension)
    }

    @Test
    fun `未知格式回退 webp`() {
        assertEquals("webp", ImageFormatDetector.detect(byteArrayOf(1, 2, 3)).extension)
    }
}
