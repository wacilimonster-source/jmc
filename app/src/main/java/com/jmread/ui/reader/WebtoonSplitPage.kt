package com.jmread.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.jmread.core.scramble.ImageScrambler
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 滚动流（条漫）单页渲染。
 *
 * 支持「条漫长图自动分割」：超过阈值的长图被切分为多个屏高切片，每个切片作为
 * LazyColumn 的一个 item，逐屏平铺，贴合竖屏、避免单张超长图无尽滚动。
 *
 * - 同一页的所有切片必须共用**同一个**高宽比：比例如按切片各自测量，后组合出来的
 *   切片会先用「切片数 × 视口比」占位、再各自测出真值，一页之内相邻切片按不同比例
 *   摆放，内容就会互相错位重叠（条漫错位根因）。因此比例由外层按页持有，
 *   本组件只上报、不自己记忆。
 * - 图片加载成功后，任何一个已组合的切片都会上报实测比例；不能只让
 *   [sliceIndex] == 0 的切片上报——用户直接滚到后续切片时，0 号片可能不在
 *   组合范围内，旧缓存/估算值无法被纠正。
 * - 未超阈值时整图显示。
 */
@Composable
fun WebtoonSplitPage(
    pageIndex: Int,
    imageUrl: String,
    sliceIndex: Int,
    sliceCount: Int,
    /** 外层按页持有的实测高宽比（null = 尚未测到，用切片数 × 视口比占位） */
    measuredRatio: Float?,
    viewportAspect: Float,
    splitEnabled: Boolean,
    onPageMeasured: (pageIndex: Int, ratio: Float, count: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val safeSliceCount = sliceCount.coerceAtLeast(1)

    // 失败重试：改变 model（追加 fragment）强制 Coil 重新请求（HTTP 请求不受 fragment 影响）。
    // 手动覆盖后缀必须留在最后：JmImageFetcher 靠 endsWith("#m1/#m0") 识别强制翻转。
    var retryTick by remember(imageUrl) { mutableIntStateOf(0) }
    val manualSuffix = when {
        imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RESTORE) -> ImageScrambler.SUFFIX_FORCE_RESTORE
        imageUrl.endsWith(ImageScrambler.SUFFIX_FORCE_RAW) -> ImageScrambler.SUFFIX_FORCE_RAW
        else -> ""
    }
    val retryUrl = if (retryTick == 0) {
        imageUrl
    } else {
        val base = imageUrl.dropLast(manualSuffix.length)
        "$base#retry$retryTick$manualSuffix"
    }
    // 解码像素上限：宽高双封顶，防超大图整图解码后绘制时触发
    // 「Canvas: trying to draw too large bitmap」（Android Canvas 位图上限约 100MB）。
    // 只限制高度时，超宽长图（跨页彩图/扫描长条）会按原宽解码成巨图。
    // 宽度上限 2160 覆盖 1080 屏宽的 2 倍余量，与高度上限组合最坏约 70MB。
    // 超出的部分按比例缩略，分割渲染仍完整展示，仅清晰度略降。
    // 上限按设备内存等级自适应（lowMemory 设备降到 4k，减轻位图压力）
    val context = LocalContext.current
    val decodeHeightCap = remember(context) { maxDecodeHeightPx(context) }
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(retryUrl)
            .size(coil.size.Size(
                width = MAX_DECODE_WIDTH_PX,
                height = decodeHeightCap,
            ))
            .build(),
    )
    // Coil 2.7 的 painter.state 是快照属性（getter 读内部 mutableStateOf），读取即订阅重组
    val state = painter.state

    // 图片"高/宽"比：优先用外层按页共享的实测值（同页所有切片一致），
    // 其次是本切片画师已解出的真实尺寸（图片已在内存/磁盘缓存时首帧即准确），
    // 最后才是「切片数 × 视口比」占位。估算值对同页所有切片一致，
    // 只影响总高（至多多出不到一屏），不会让相邻切片互相叠压。
    val liveRatio = ((state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize)
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?.let { it.height / it.width }
    val heightRatio: Float = measuredRatio ?: liveRatio ?: (safeSliceCount * viewportAspect)

    // 图片加载成功后上报真实比例与切片数。任一已组合的切片都可以上报：
    // 比例由外层按页统一持有，重复上报是幂等的。
    val intrinsicSize = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
    LaunchedEffect(intrinsicSize) {
        if (intrinsicSize != null && intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
            val ratio = intrinsicSize.height / intrinsicSize.width
            if (splitEnabled) {
                val n = computeSliceCount(ratio, viewportAspect)
                android.util.Log.d(
                    "WebtoonSlice",
                    "page=$pageIndex slice=$sliceIndex cache=$safeSliceCount " +
                        "intrinsic=${intrinsicSize.width}x${intrinsicSize.height} " +
                        "ratio=$ratio viewport=$viewportAspect count=$n",
                )
                onPageMeasured(pageIndex, ratio, n)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        // 全部按整数像素计算切片边界：第 i 片 = [round(total*i/n), round(total*(i+1)/n))，
        // 相邻切片首尾严格相接，不会因 dp 取整产生 1px 缝或重叠
        val totalHeightPx = (widthPx * heightRatio).roundToInt().coerceAtLeast(1)
        val sliceIdx = sliceIndex.coerceIn(0, safeSliceCount - 1)
        val sliceTopPx = (totalHeightPx.toLong() * sliceIdx / safeSliceCount).toInt()
        val sliceBottomPx = (totalHeightPx.toLong() * (sliceIdx + 1) / safeSliceCount).toInt()
        val sliceHeightPx = (sliceBottomPx - sliceTopPx).coerceAtLeast(1)

        if (!splitEnabled || safeSliceCount <= 1) {
            // 整图显示（普通页 / 未开启分割）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { totalHeightPx.toDp() })
                    .clipToBounds()
                    .background(Color.Gray.copy(alpha = 0.1f)),
            ) {
                PageImage(
                    painter = painter,
                    state = state,
                    pageIndex = pageIndex,
                    fullHeightPx = totalHeightPx,
                    sliceTopPx = 0,
                    onRetry = { retryTick++ },
                )
            }
        } else {
            // 长图切分：本 item 只显示第 sliceIndex 屏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { sliceHeightPx.toDp() })
                    .clipToBounds()
                    .background(Color.Gray.copy(alpha = 0.1f)),
            ) {
                PageImage(
                    painter = painter,
                    state = state,
                    pageIndex = pageIndex,
                    fullHeightPx = totalHeightPx,
                    sliceTopPx = sliceTopPx,
                    onRetry = { retryTick++ },
                )
            }
        }
    }
}

/** 单切片内容：成功画图，加载中占位，失败可点击重试。 */
@Composable
private fun PageImage(
    painter: AsyncImagePainter,
    state: AsyncImagePainter.State,
    pageIndex: Int,
    fullHeightPx: Int,
    sliceTopPx: Int,
    onRetry: () -> Unit,
) {
    when (state) {
        is AsyncImagePainter.State.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }

        is AsyncImagePainter.State.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "第 ${pageIndex + 1} 页加载失败\n点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }

        else -> {
            Image(
                painter = painter,
                contentDescription = "第 ${pageIndex + 1} 页",
                // FillBounds：盒高 = 宽 × 同页共享比例，图片按页统一缩放铺满，
                // 不会因比例微差在盒内居中留边而让各切片内容起点不一致
                contentScale = ContentScale.FillBounds,
                alignment = Alignment.TopStart,
                modifier = Modifier.layout { measurable, constraints ->
                    // 不能用 requiredHeight(整页高) 放进单屏高的父盒：子项超出父约束时
                    // Compose 会把它居中，额外上移 (整页高 − 切片高)/2，相邻切片因此错位重叠。
                    // 这里显式按整页尺寸测量，再把第 sliceTopPx 行对齐到切片顶部。
                    val w = constraints.maxWidth
                    val placeable = measurable.measure(
                        androidx.compose.ui.unit.Constraints.fixed(w, fullHeightPx),
                    )
                    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else fullHeightPx
                    layout(w, h) { placeable.place(0, -sliceTopPx) }
                },
            )
        }
    }
}

/** 计算单页应切分为多少屏（超出一屏 1.4 倍才切；上限 6 屏防位图过大）。 */
internal fun computeSliceCount(imageHeightRatio: Float, viewportAspect: Float): Int {
    if (imageHeightRatio <= 0f || viewportAspect <= 0f) return 1
    val raw = imageHeightRatio / viewportAspect
    return if (raw <= 1.4f) 1 else ceil(raw).toInt().coerceIn(1, 6)
}

/** 单张位图解码高度上限（px）：1080 宽 × 8192 高 ≈ 35MB */
private const val MAX_DECODE_HEIGHT_PX = 8192

/** 单张位图解码宽度上限（px）：超宽长图必须同样封顶，否则会解码成巨图触发 Canvas 上限 */
internal const val MAX_DECODE_WIDTH_PX = 2160

/**
 * 按设备内存等级自适应解码上限：
 * - 大内存设备（memoryClass ≥ 256MB）维持 8192，长图切片清晰度最优；
 * - 小内存设备降到 4096（约 17MB/张），显著降低缓存驱逐导致的重复解码与 GC 抖动。
 */
private fun maxDecodeHeightPx(context: android.content.Context): Int {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
        as? android.app.ActivityManager
    val memClass = am?.memoryClass ?: 192
    return when {
        memClass >= 256 -> MAX_DECODE_HEIGHT_PX
        memClass >= 128 -> 6144
        else -> 4096
    }
}
