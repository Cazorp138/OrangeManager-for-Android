package com.orangemanager.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 首页底图的调整参数。会被持久化，所以新增字段时记得给默认值。
 *
 * scale   1.0 ~ 3.0，1.0 就是原始大小（fill 决定它是"填满"还是"完整显示"）
 * offsetX / offsetY  -1 ~ 1，表示在**可用余量**里平移的比例（不是绝对像素）
 * dim     0 ~ 1，压暗遮罩的强度
 * fill    true = 裁剪填满屏幕（超出的部分被切掉）；false = 完整显示（整张图都在，可能留边）
 */
data class BgTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val dim: Float = 1f,
    val fill: Boolean = false
)

/** 目标解码宽度：留一点放大余量，又不至于让大图把内存吃满。 */
private const val TARGET_WIDTH = 1440
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 3f

/** 解码一次并缓存，换图或清空时自动重算。首页/专注页/设置页预览共用同一份。 */
@Composable
fun rememberBackgroundBitmap(imageUri: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imageUri) {
        bitmap = if (imageUri == null) null
        else withContext(Dispatchers.IO) { decodeDownsampled(context, Uri.parse(imageUri), TARGET_WIDTH) }
    }
    return bitmap
}

/** 某个方向上的"图片比容器大出多少"的一半（像素）。≤0 表示这个方向没有可拖空间。 */
private fun slackOf(container: Float, imagePx: Int, base: Float, scale: Float): Float =
    ((imagePx * base * scale - container) / 2f).coerceAtLeast(0f)

/**
 * 底图的统一渲染器 —— 首页、专注页和设置页预览共用它，
 * 所以"预览里看到的"和"实际得到的"一定是同一套裁切与位移。
 *
 * fill = false 时用 Fit：整张图都显示出来，不会为了填满而切掉内容，
 * 代价是比例不合时上下（或左右）会留边。留边的区域露出容器底色。
 */
@Composable
fun BackgroundImage(
    bitmap: Bitmap?,
    transform: BgTransform,
    modifier: Modifier = Modifier,
    scrim: Boolean = true
) {
    BoxWithConstraints(modifier) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
            containerW > 0f && containerH > 0f

        if (bitmap == null || !bounded) {
            // 没有图片、解码失败、或容器尺寸不定的兜底：渐变占位
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFFFFD7A0), Color(0xFFFF9F1C), Color(0xFF4B8C8A)))
                )
            )
        } else {
            val base = if (transform.fill) {
                maxOf(containerW / bitmap.width, containerH / bitmap.height)
            } else {
                minOf(containerW / bitmap.width, containerH / bitmap.height)
            }
            val slackX = slackOf(containerW, bitmap.width, base, transform.scale)
            val slackY = slackOf(containerH, bitmap.height, base, transform.scale)
            val drawable = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = drawable,
                contentDescription = null,
                contentScale = if (transform.fill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX * slackX
                        translationY = transform.offsetY * slackY
                    }
            )
        }

        if (scrim) {
            val d = transform.dim.coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.30f * d),
                            Color.Black.copy(alpha = 0.35f * d),
                            Color.Black.copy(alpha = 0.78f * d)
                        )
                    )
                )
            )
        }
    }
}

/**
 * 设置页里的底图编辑区。
 *
 * 预览做成**手机屏幕比例**，和首页一致。缩放和位置用手势调：
 * 单指拖动平移、双指捏合缩放（1x ~ 3x）。「背景明暗」和「裁剪填满」用手势表达不了，所以留了控件。
 */
@Composable
fun BackgroundImageEditor(
    bitmap: Bitmap?,
    imageUri: String?,
    transform: BgTransform,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onTransformChange: (BgTransform) -> Unit
) {
    val config = LocalConfiguration.current
    val screenAspect =
        if (config.screenWidthDp > 0) config.screenHeightDp.toFloat() / config.screenWidthDp else 2f
    val previewWidth = 140.dp
    val previewHeight = (140f * screenAspect).coerceIn(180f, 320f).dp

    // 手势回调里必须读最新的值和最新的回调，否则会拿到重组前的旧快照
    val latest = rememberUpdatedState(transform)
    val emit = rememberUpdatedState(onTransformChange)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(previewWidth).height(previewHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF24332D))
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val bmp = bitmap ?: return@detectTransformGestures
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTransformGestures

                            val base = latest.value
                            val nextScale = (base.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            // 用与渲染完全相同的公式算余量，再把手指位移换算成比例
                            val unit = if (base.fill) {
                                maxOf(w / bmp.width, h / bmp.height)
                            } else {
                                minOf(w / bmp.width, h / bmp.height)
                            }
                            val slackX = slackOf(w, bmp.width, unit, nextScale)
                            val slackY = slackOf(h, bmp.height, unit, nextScale)

                            emit.value(
                                base.copy(
                                    scale = nextScale,
                                    offsetX = if (slackX > 1f) (base.offsetX + pan.x / slackX).coerceIn(-1f, 1f) else 0f,
                                    offsetY = if (slackY > 1f) (base.offsetY + pan.y / slackY).coerceIn(-1f, 1f) else 0f
                                )
                            )
                        }
                    }
            ) {
                BackgroundImage(bitmap, transform, Modifier.fillMaxSize())
                if (imageUri == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未选择图片", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                Text(if (imageUri == null) "从相册选择" else "更换图片")
            }
            if (imageUri != null) {
                OutlinedButton(onClick = onClearImage, modifier = Modifier.weight(1f)) { Text("移除图片") }
            }
        }

        if (imageUri != null) {
            // 默认关闭 = 完整显示。打开才会为了填满屏幕把超出的部分切掉。
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "裁剪填满屏幕",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = transform.fill,
                    onCheckedChange = { onTransformChange(transform.copy(fill = it)) }
                )
            }

            Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("背景明暗", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(percentLabel(transform.dim), color = Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = transform.dim,
                    onValueChange = { onTransformChange(transform.copy(dim = it)) },
                    valueRange = 0f..1f
                )
            }
            TextButton(onClick = { onTransformChange(BgTransform()) }, modifier = Modifier.fillMaxWidth()) {
                Text("恢复默认调整")
            }
        }
    }
}

private fun percentLabel(value: Float): String = "${(value * 100).roundToInt()}%"

/** 读两次流：先只读尺寸算采样率，再真正解码。失败一律返回 null（例如权限被撤销）。 */
private fun decodeDownsampled(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}
