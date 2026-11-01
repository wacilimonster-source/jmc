package com.jmread.core.scramble

/** 图片真实编码（按魔数识别，不信任下载文件名）。 */
data class ImageFormat(val extension: String, val mimeType: String)

object ImageFormatDetector {

    fun detect(bytes: ByteArray): ImageFormat {
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return ImageFormat("webp", "image/webp")
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return ImageFormat("jpg", "image/jpeg")
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return ImageFormat("png", "image/png")
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return ImageFormat("gif", "image/gif")
        }
        // 禁漫章节图线上以 webp 为主；未知格式仍按 webp 处理
        return ImageFormat("webp", "image/webp")
    }
}
