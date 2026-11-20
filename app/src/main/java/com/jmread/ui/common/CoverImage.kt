package com.jmread.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/**
 * 可重试封面（对标 PiKA v1.12.3-9「封面加载失败可重试」）。
 *
 * 弱网/坏封面下不再长期空白：失败后显示半透明「点击重试」覆盖层，
 * 点击以 #retryN 重新发起请求。
 *
 * PiKA 事故教训（perf-arch P0-2）：重试请求必须显式固定 memoryCacheKey/diskCacheKey
 * 为裸 URL —— 否则重试成功后的位图落在 #retryN 键下，列表回滚重建时缓存全 miss。
 * JM 的 jmImageCacheKey 本身按裸 URL 归一（#retry 不进缓存身份），这里仍显式固定，双保险。
 */
@Composable
fun RetryableCoverImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape? = null,
) {
    var retryTick by remember(url) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(url, retryTick) {
        ImageRequest.Builder(context)
            .data(if (retryTick > 0) "$url#retry$retryTick" else url)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val imageModifier = if (shape != null) Modifier.fillMaxSize().clip(shape) else Modifier.fillMaxSize()

    Box(modifier) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = imageModifier,
        )
        if (painter.state is AsyncImagePainter.State.Error) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x66000000))
                    .clickable { retryTick++ },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "点击重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}
