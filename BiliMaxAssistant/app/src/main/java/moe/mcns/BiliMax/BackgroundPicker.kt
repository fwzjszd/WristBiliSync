package moe.mcns.BiliMax

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================== 自定义背景图 ==============================
//
// 背景图存储与两个页面（选择页 / 裁切页）。背景图统一裁成当前屏幕比例后
// 才写入 filesDir/backgrounds，避免运行时再拉伸变形。

/** 背景图文件管理：解码用户选的图片、裁剪存盘、读取预览。 */
internal object BackgroundStore {

    private const val DIR_NAME = "backgrounds"
    private const val MAX_DECODE_DIM = 2560
    private const val OUTPUT_WIDTH = 1080

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 磁盘上实际存在的背景文件名（按添加时间升序） */
    fun list(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()

    /** 与偏好记录对齐：文件被清理掉的条目同步移除 */
    fun reconcile(context: Context): List<String> {
        val existing = list(context)
        val prefs = AppPrefs.get(context)
        val kept = prefs.customBackgrounds.filter { existing.contains(it) }
        if (kept != prefs.customBackgrounds) prefs.customBackgrounds = kept
        if (prefs.backgroundSelection.isNotBlank() && !existing.contains(prefs.backgroundSelection)) {
            prefs.backgroundSelection = ""
        }
        return kept
    }

    fun load(context: Context, name: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(File(dir(context), name).absolutePath)
    }.getOrNull()

    /** 解码用户选择的图片（自动按 EXIF 方向摆正，超长边按 2560 采样） */
    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_DECODE_DIM) {
                    val sample = (longest.toFloat() / MAX_DECODE_DIM).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSampleSize(sample)
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    /** 按 rect 从原图裁出目标比例，缩放到固定宽度后存为 jpg */
    fun crop(source: Bitmap, rect: RectF, aspect: Float): Bitmap {
        val outW = OUTPUT_WIDTH
        val outH = (outW / aspect).roundToInt().coerceAtLeast(1)
        val left = rect.left.roundToInt().coerceIn(0, source.width - 1)
        val top = rect.top.roundToInt().coerceIn(0, source.height - 1)
        val right = rect.right.roundToInt().coerceIn(left + 1, source.width)
        val bottom = rect.bottom.roundToInt().coerceIn(top + 1, source.height)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(
            source,
            android.graphics.Rect(left, top, right, bottom),
            RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    fun save(context: Context, bitmap: Bitmap): String {
        val name = "bg_" + System.currentTimeMillis() + ".jpg"
        runCatching {
            File(dir(context), name).outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
        }
        return name
    }

    fun delete(context: Context, name: String) {
        runCatching { File(dir(context), name).delete() }
    }
}

/** 当前屏幕宽高比（宽/高），裁切框与落盘尺寸都以它为准 */
@Composable
private fun rememberScreenAspect(): Float {
    val context = LocalContext.current
    return remember(context) {
        val dm = context.resources.displayMetrics
        if (dm.heightPixels > 0) dm.widthPixels.toFloat() / dm.heightPixels else 0.46f
    }
}

@Composable
private fun rememberBackgroundBitmap(name: String): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, name) {
        value = withContext(Dispatchers.IO) { BackgroundStore.load(context, name) }
    }
    return bitmap
}

/** 设置页卡片右侧的小预览：当前选中的背景缩略图 */
@Composable
internal fun BackgroundThumbnail(selection: String, modifier: Modifier = Modifier) {
    val bitmap = if (selection.isBlank()) null else rememberBackgroundBitmap(selection)
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF6E6E73), Color(0xFF2C2C2E)))
            )
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 应用背景层：自定义图片铺满（已按屏幕比例裁切）或回退主题底色 */
@Composable
internal fun AppBackground(
    selection: String,
    color: Color,
    blurDp: Float = 0f,
    modifier: Modifier = Modifier
) {
    val bitmap = if (selection.isBlank()) null else rememberBackgroundBitmap(selection)
    Box(modifier.background(color)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurDp > 0f) Modifier.blur(blurDp.dp) else Modifier),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 背景图选择页：默认主题 + 已添加的自定义图 + 末尾的「+」添加页 */
@Composable
internal fun BackgroundScreen(
    selection: String,
    onSelectionChange: (String) -> Unit,
    blur: Float,
    onBlurChange: (Float) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(BackgroundStore.reconcile(context)) }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val aspect = rememberScreenAspect()

    // 系统相册选择器（Android 13+ 走照片选择器，低版本自动回退文档选择）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) { BackgroundStore.decode(context, uri) }
                if (decoded == null) {
                    Toast.makeText(context, "无法读取该图片", Toast.LENGTH_SHORT).show()
                } else {
                    pending = decoded
                }
            }
        }
    }

    // 选好图先进裁切页，裁成屏幕比例后才写入设置
    val pendingBitmap = pending
    if (pendingBitmap != null) {
        BackgroundCropScreen(
            source = pendingBitmap,
            aspect = aspect,
            onCancel = { pending = null },
            onConfirm = { cropped ->
                scope.launch {
                    val name = withContext(Dispatchers.IO) { BackgroundStore.save(context, cropped) }
                    val next = items + name
                    AppPrefs.get(context).customBackgrounds = next
                    items = next
                    pending = null
                    onSelectionChange(name)
                }
            }
        )
        return
    }

    val pageCount = items.size + 2
    val pagerState = rememberPagerState(
        initialPage = items.indexOf(selection).let { if (selection.isBlank() || it < 0) 0 else it + 1 }
    ) { pageCount }

    // 新增 / 切换选中项后，把大图滑到对应页
    LaunchedEffect(selection, items) {
        val index = items.indexOf(selection)
        if (index >= 0 && pagerState.currentPage != index + 1 && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(index + 1)
        }
    }

    SubPageScaffold(title = "背景图", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                pageSpacing = 16.dp
            ) { index ->
                // 页内容按比例缩放后居中，避免被 pager 槽位贴左摆放
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        index == 0 -> DefaultThemePage(aspect = aspect)
                        index == pageCount - 1 -> AddImagePage(aspect = aspect) {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        else -> {
                            val name = items.getOrNull(index - 1)
                            if (name != null) {
                                CustomImagePage(
                                    name = name,
                                    aspect = aspect,
                                    blurDp = blur,
                                    onDelete = {
                                        BackgroundStore.delete(context, name)
                                        val next = items - name
                                        AppPrefs.get(context).customBackgrounds = next
                                        items = next
                                        if (selection == name) onSelectionChange("")
                                        Toast.makeText(context, "已删除该背景", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            PageDots(count = pageCount, current = pagerState.currentPage)
            Spacer(Modifier.height(10.dp))
            // 模糊化调节：拖动时上方卡片与本页背景实时预览
            BlurSlider(
                label = "背景图模糊化处理",
                value = blur,
                onValueChange = { onBlurChange(it) },
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(12.dp))

            val page = pagerState.currentPage
            val isAddPage = page == pageCount - 1
            val selectedName = when {
                page == 0 -> ""
                page - 1 < items.size -> items[page - 1]
                else -> null
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        isAddPage -> "添加自定义背景"
                        selectedName.isNullOrBlank() -> "默认主题 · 灰色"
                        else -> "自定义背景"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(10.dp))
                if (!isAddPage && selectedName != null) {
                    val already = selectedName == selection
                    Button(
                        onClick = { onSelectionChange(selectedName) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !already
                    ) {
                        Text(
                            if (already) "当前使用中" else "使用此背景",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // 与按钮等高，避免翻到「+」页时下方内容跳动
                    Spacer(Modifier.height(44.dp))
                }
            }
        }
    }
}

/** 默认主题（灰色）预览页 */
@Composable
private fun DefaultThemePage(aspect: Float) {
    Box(
        modifier = Modifier
            // 只占可用高度的 88%，让卡片与下方调节滑块在同一个屏幕内完整显示
            .fillMaxHeight(0.88f)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF6E6E73), Color(0xFF2C2C2E)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("默认", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f))
    }
}

/** 已添加的自定义背景页，右上角可删除 */
@Composable
private fun CustomImagePage(
    name: String,
    aspect: Float,
    blurDp: Float,
    onDelete: () -> Unit
) {
    val bitmap = rememberBackgroundBitmap(name)
    Box(
        modifier = Modifier
            .fillMaxHeight(0.88f)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141416))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                // 与首页背景层同步模糊，拖动下方滑块即时预览
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurDp > 0f) Modifier.blur(blurDp.dp) else Modifier),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除背景",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 末尾的加号页：点击进入相册选择 */
@Composable
private fun AddImagePage(aspect: Float, onClick: () -> Unit) {
    val borderColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .fillMaxHeight(0.88f)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                cornerRadius = CornerRadius(24.dp.toPx()),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                )
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(StateAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = StateAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "添加图片",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (active) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) StateAccent
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

/**
 * 模糊化调节滑块：自绘轨道 + 圆钮，按 1dp 步进取整后再上抛，
 * 避免拖动过程中逐帧给整张大图重设模糊半径。
 */
@Composable
private fun BlurSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..30f
) {
    // pointerInput 会缓存首次组合的闭包，用 rememberUpdatedState 保证始终调用最新回调
    val latest by rememberUpdatedState(onValueChange)
    val accent = StateAccent
    val trackColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = if (value <= valueRange.start) "关闭" else "${value.roundToInt()} dp",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(valueRange) {
                    val knobRadius = 8.dp.toPx()
                    fun emit(posX: Float) {
                        val usable = (size.width - knobRadius * 2f).coerceAtLeast(1f)
                        val ratio = ((posX - knobRadius) / usable).coerceIn(0f, 1f)
                        val raw = valueRange.start + (valueRange.endInclusive - valueRange.start) * ratio
                        latest(raw.roundToInt().toFloat().coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                    awaitEachGesture {
                        // Initial 阶段先接管手势，避免被外层 pager 的横向滑动抢走
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        emit(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            emit(change.position.x)
                        }
                    }
                }
        ) {
            val centerY = size.height / 2f
            val knobRadius = 8.dp.toPx()
            val trackHeight = 6.dp.toPx()
            val usable = size.width - knobRadius * 2f
            val ratio =
                ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val knobX = knobRadius + usable * ratio
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f)
            )
            if (ratio > 0f) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(knobX, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f)
                )
            }
            drawCircle(color = Color.White, radius = knobRadius, center = Offset(knobX, centerY))
            drawCircle(
                color = accent.copy(alpha = 0.45f),
                radius = knobRadius,
                center = Offset(knobX, centerY),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

/**
 * 裁切页：把用户选的图限制在当前屏幕比例的取景框内，双指缩放 + 拖动取景，
 * 确认后按取景框内容落盘。取景框外的区域不可见，保证成品不会变形。
 */
@Composable
internal fun BackgroundCropScreen(
    source: Bitmap,
    aspect: Float,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val density = LocalDensity.current
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frameSize by remember { mutableStateOf(Size.Zero) }

    fun baseScale(w: Float, h: Float): Float =
        max(w / source.width, h / source.height)

    fun clampedOffset(raw: Offset, scale: Float, w: Float, h: Float): Offset {
        val maxX = max(0f, (source.width * scale - w) / 2f)
        val maxY = max(0f, (source.height * scale - h) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "取消",
                fontSize = 15.sp,
                color = Color.White,
                modifier = Modifier.clickable(onClick = onCancel)
            )
            Text("裁切为屏幕比例", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Text(
                "完成",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = StateAccent,
                modifier = Modifier.clickable {
                    val frame = frameSize
                    if (frame.width > 0f && frame.height > 0f) {
                        val scale = baseScale(frame.width, frame.height) * zoom
                        val center = clampedOffset(offset, scale, frame.width, frame.height)
                        val halfW = frame.width / 2f / scale
                        val halfH = frame.height / 2f / scale
                        val cx = source.width / 2f - center.x / scale
                        val cy = source.height / 2f - center.y / scale
                        val rect = RectF(
                            (cx - halfW).coerceIn(0f, source.width.toFloat()),
                            (cy - halfH).coerceIn(0f, source.height.toFloat()),
                            (cx + halfW).coerceIn(0f, source.width.toFloat()),
                            (cy + halfH).coerceIn(0f, source.height.toFloat())
                        )
                        onConfirm(BackgroundStore.crop(source, rect, aspect))
                    }
                }
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val availableW = with(density) { maxWidth.toPx() }
            val availableH = with(density) { maxHeight.toPx() }
            val frameW = min(availableW, availableH * aspect)
            val frameH = frameW / aspect
            LaunchedEffect(frameW, frameH) { frameSize = Size(frameW, frameH) }

            Box(
                modifier = Modifier
                    .size(
                        width = with(density) { frameW.toDp() },
                        height = with(density) { frameH.toDp() }
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .pointerInput(frameW, frameH, source) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            val scale = baseScale(frameW, frameH) * nextZoom
                            zoom = nextZoom
                            offset = clampedOffset(
                                Offset(offset.x + pan.x, offset.y + pan.y),
                                scale,
                                frameW,
                                frameH
                            )
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val scale = baseScale(size.width, size.height) * zoom
                    val shown = clampedOffset(offset, scale, size.width, size.height)
                    val drawW = source.width * scale
                    val drawH = source.height * scale
                    drawImage(
                        image = source.asImageBitmap(),
                        dstOffset = IntOffset(
                            (size.width / 2f - drawW / 2f + shown.x).roundToInt(),
                            (size.height / 2f - drawH / 2f + shown.y).roundToInt()
                        ),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                    // 三分构图参考线
                    val lineColor = Color.White.copy(alpha = 0.22f)
                    for (i in 1..2) {
                        val x = size.width * i / 3f
                        val y = size.height * i / 3f
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                }
            }
        }

        Text(
            "双指缩放 · 拖动调整位置",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 28.dp)
        )
    }
}
