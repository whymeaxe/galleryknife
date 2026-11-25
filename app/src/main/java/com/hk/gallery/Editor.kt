package com.hk.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageTools {
    fun decode(ctx: Context, m: Media, maxDim: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, m.uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val s = min(1f, maxDim.toFloat() / max(info.size.width, info.size.height))
            dec.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** On-device text recognition at a resolution good enough for documents. */
    suspend fun ocr(ctx: Context, m: Media): String = withContext(Dispatchers.Default) {
        val bmp = decode(ctx, m, 2400)
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { rec.process(InputImage.fromBitmap(bmp, 0)).await().text.trim() } finally { rec.close() }
    }

    fun saveCopy(ctx: Context, bmp: Bitmap): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GalleryKnife_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GalleryKnife")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: return false
        return true
    }
}

private class Ratio(val label: String, val r: Float?, val original: Boolean = false)

@Composable
fun CropScreen(m: Media, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = onClose)

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f)) } // normalised to the image
    var ratio by remember { mutableStateOf<Ratio?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(m.id) { bmp = withContext(Dispatchers.IO) { ImageTools.decode(ctx, m, 3200) } }

    val ratios = remember {
        listOf(Ratio("Free", null), Ratio("Original", null, true), Ratio("1:1", 1f), Ratio("4:3", 4f / 3f), Ratio("3:4", 3f / 4f), Ratio("16:9", 16f / 9f), Ratio("9:16", 9f / 16f))
    }

    val b = bmp
    // Fit rectangle of the image inside the canvas area.
    val fit = remember(b, area) {
        if (b == null || area.width == 0) null else {
            val s = min(area.width.toFloat() / b.width, area.height.toFloat() / b.height)
            val w = b.width * s; val h = b.height * s
            androidx.compose.ui.geometry.Rect((area.width - w) / 2, (area.height - h) / 2, (area.width + w) / 2, (area.height + h) / 2)
        }
    }

    fun effectiveRatio(r: Ratio?): Float? = when {
        r == null -> null
        r.original && b != null -> b.width.toFloat() / b.height
        else -> r.r
    }

    fun applyRatio(r: Ratio?) {
        ratio = r
        val f = fit ?: return
        val rr = effectiveRatio(r) ?: return
        val dw = f.width; val dh = f.height
        var w = 1f; var h = dw / rr / dh
        if (h > 1f) { h = 1f; w = dh * rr / dw }
        crop = androidx.compose.ui.geometry.Rect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text("Crop", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = b != null && !saving,
                onClick = {
                    val src = b ?: return@TextButton
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val x = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 1)
                        val y = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 1)
                        val w = (crop.width * src.width).roundToInt().coerceIn(1, src.width - x)
                        val h = (crop.height * src.height).roundToInt().coerceIn(1, src.height - y)
                        val ok = runCatching { ImageTools.saveCopy(ctx, Bitmap.createBitmap(src, x, y, w, h)) }.getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, if (ok) "Saved a copy to Pictures/GalleryKnife" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            saving = false
                            if (ok) onSaved()
                        }
                    }
                },
            ) { Text("Save copy", color = cs.secondary, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
        }

        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { area = it }) {
            val f = fit
            if (b == null || f == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                val img = remember(b) { b.asImageBitmap() }
                var active by remember { mutableIntStateOf(-1) } // 0 TL 1 TR 2 BL 3 BR 4 move
                var finger by remember { mutableStateOf(Offset.Zero) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(b, f, ratio) {
                        val slop = 56.dp.toPx()
                        detectDragGestures(
                            onDragStart = { p ->
                                val c = crop
                                val corners = listOf(
                                    Offset(f.left + c.left * f.width, f.top + c.top * f.height), Offset(f.left + c.right * f.width, f.top + c.top * f.height),
                                    Offset(f.left + c.left * f.width, f.top + c.bottom * f.height), Offset(f.left + c.right * f.width, f.top + c.bottom * f.height),
                                )
                                val near = corners.withIndex().minByOrNull { (it.value - p).getDistance() }!!
                                active = if ((near.value - p).getDistance() < slop) near.index
                                else if (p.x in (f.left + c.left * f.width)..(f.left + c.right * f.width) && p.y in (f.top + c.top * f.height)..(f.top + c.bottom * f.height)) 4 else -1
                                finger = if (active in 0..3) corners[active] else p
                            },
                            onDragEnd = { active = -1 }, onDragCancel = { active = -1 },
                        ) { change, drag ->
                            change.consume()
                            val c = crop
                            if (active == 4) {
                                val dx = drag.x / f.width; val dy = drag.y / f.height
                                val nl = (c.left + dx).coerceIn(0f, 1f - c.width); val nt = (c.top + dy).coerceIn(0f, 1f - c.height)
                                crop = androidx.compose.ui.geometry.Rect(nl, nt, nl + c.width, nt + c.height)
                            } else if (active in 0..3) {
                                finger += drag
                                val px = ((finger.x - f.left) / f.width).coerceIn(0f, 1f)
                                val py = ((finger.y - f.top) / f.height).coerceIn(0f, 1f)
                                // anchor = opposite corner
                                val ax = if (active == 0 || active == 2) c.right else c.left
                                val ay = if (active == 0 || active == 1) c.bottom else c.top
                                val rr = effectiveRatio(ratio)
                                val minS = 0.08f
                                var nx = px; var ny = py
                                if (rr != null) {
                                    val sx = if (px >= ax) 1f else -1f; val sy = if (py >= ay) 1f else -1f
                                    val maxW = if (sx > 0) 1f - ax else ax; val maxH = if (sy > 0) 1f - ay else ay
                                    var w = min(abs(px - ax), maxW)
                                    var h = w * f.width / rr / f.height
                                    if (h > maxH) { h = maxH; w = h * f.height * rr / f.width }
                                    w = max(w, minS); h = w * f.width / rr / f.height
                                    nx = ax + sx * w; ny = ay + sy * h
                                } else {
                                    if (abs(nx - ax) < minS) nx = ax + (if (nx >= ax) minS else -minS)
                                    if (abs(ny - ay) < minS) ny = ay + (if (ny >= ay) minS else -minS)
                                }
                                crop = androidx.compose.ui.geometry.Rect(min(ax, nx).coerceIn(0f, 1f), min(ay, ny).coerceIn(0f, 1f), max(ax, nx).coerceIn(0f, 1f), max(ay, ny).coerceIn(0f, 1f))
                            }
                        }
                    },
                ) {
                    drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(f.left.roundToInt(), f.top.roundToInt()), dstSize = IntSize(f.width.roundToInt(), f.height.roundToInt()))
                    val l = f.left + crop.left * f.width; val t = f.top + crop.top * f.height
                    val r = f.left + crop.right * f.width; val bt = f.top + crop.bottom * f.height
                    val dim = Color.Black.copy(alpha = 0.58f)
                    drawRect(dim, Offset(f.left, f.top), Size(f.width, t - f.top))
                    drawRect(dim, Offset(f.left, bt), Size(f.width, f.bottom - bt))
                    drawRect(dim, Offset(f.left, t), Size(l - f.left, bt - t))
                    drawRect(dim, Offset(r, t), Size(f.right - r, bt - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, bt - t), style = Stroke(1.dp.toPx()))
                    val grid = Color.White.copy(alpha = 0.32f)
                    for (i in 1..2) {
                        drawLine(grid, Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, bt), 0.5.dp.toPx())
                        drawLine(grid, Offset(l, t + (bt - t) * i / 3), Offset(r, t + (bt - t) * i / 3), 0.5.dp.toPx())
                    }
                    val len = 20.dp.toPx(); val sw = 3.dp.toPx()
                    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(Color.White, Offset(x - sw / 2 * dx, y), Offset(x + len * dx, y), sw)
                        drawLine(Color.White, Offset(x, y - sw / 2 * dy), Offset(x, y + len * dy), sw)
                    }
                    bracket(l, t, 1f, 1f); bracket(r, t, -1f, 1f); bracket(l, bt, 1f, -1f); bracket(r, bt, -1f, -1f)
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ratios.forEach { r ->
                    val on = ratio?.label == r.label || (ratio == null && r.label == "Free")
                    Text(
                        r.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color.Black else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color(0xFF232325))
                            .clickable { applyRatio(r) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Outlined.RotateRight, "Rotate") {
                    val src = bmp ?: return@IconBtn
                    val mx = Matrix().apply { postRotate(90f) }
                    bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mx, true)
                    crop = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f); ratio = null
                }
                IconBtn(Icons.Outlined.Refresh, "Reset") { crop = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f); ratio = null }
                Spacer(Modifier.weight(1f))
                Text("Saves a copy. The original stays untouched.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}
