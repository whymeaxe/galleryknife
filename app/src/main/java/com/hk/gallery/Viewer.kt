package com.hk.gallery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Viewer(
    items: List<Media>, start: Int, store: Store,
    onClose: () -> Unit, onDelete: (Media) -> Unit, onEdit: (Media) -> Unit = {},
) {
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var info by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    BackHandler(onBack = onClose)
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(
        Modifier.fillMaxSize()
            .background(bg.copy(alpha = (1f - abs(dragY) / 900f).coerceIn(0f, 1f)))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (abs(dragY) > 260f) onClose() else dragY = 0f },
                    onDragCancel = { dragY = 0f },
                ) { _, d -> dragY += d }
            }
    ) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize()
                .offset { IntOffset(0, dragY.roundToInt()) }
                .graphicsLayer { val s = 1f - abs(dragY) / 3500f; scaleX = s; scaleY = s },
            pageSpacing = 16.dp, beyondViewportPageCount = 1, key = { items[it].id },
        ) { page -> ZoomPage(items[page], page == pager.currentPage) { chrome = !chrome } }

        AnimatedVisibility(chrome && dragY == 0f, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glass(Modifier.size(44.dp), CircleShape) {
                    Box(Modifier.clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ArrowBackIosNew, "Back", Modifier.size(18.dp))
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(current.localDate), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        Instant.ofEpochMilli(current.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(44.dp))
            }
        }

        AnimatedVisibility(chrome && dragY == 0f, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Glass(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarButton(Icons.Outlined.IosShare, "Share") {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = if (current.isVideo) "video/*" else "image/*"
                            putExtra(Intent.EXTRA_STREAM, current.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, null))
                    }
                    val fav = current.id in Prefs.favs
                    BarButton(if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", active = fav) { Prefs.toggleFav(current.id) }
                    BarButton(if (current.isVideo) Icons.Outlined.ContentCut else Icons.Outlined.Crop, if (current.isVideo) "Trim" else "Crop") { onEdit(current) }
                    BarButton(Icons.Outlined.TextFields, "Copy text", enabled = !current.isVideo && !ocrBusy) {
                        ocrBusy = true
                        scope.launch { ocrText = runCatching { ImageTools.ocr(ctx, current) }.getOrDefault(""); ocrBusy = false }
                    }
                    BarButton(Icons.Outlined.Info, "Info") { info = true }
                    BarButton(Icons.Outlined.DeleteOutline, "Delete") { onDelete(current) }
                }
            }
        }
    }

    ocrText?.let { t ->
        ModalBottomSheet(onDismissRequest = { ocrText = null }, containerColor = MaterialTheme.colorScheme.background) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Text in photo", style = MaterialTheme.typography.titleLarge)
                if (t.isBlank()) Text("No text found in this photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(t, Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(t)); android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show() }, shape = RoundedCornerShape(12.dp)) { Text("Copy") }
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
                        }, shape = RoundedCornerShape(12.dp)) { Text("Share") }
                    }
                }
            }
        }
    }

    if (info) {
        ModalBottomSheet(onDismissRequest = { info = false }, containerColor = MaterialTheme.colorScheme.background) {
            InfoSheet(current, store)
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        desc == "Delete" -> Color(0xFFFF453A)
        active -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(Modifier.size(46.dp).clip(CircleShape).bouncy(onClick, enabled), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
private fun ZoomPage(m: Media, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember(m.id) { mutableStateOf(false) }
    if (!isCurrent && playing) playing = false
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val req = remember(m.id) { ImageRequest.Builder(ctx).data(m.uri).size(2400).crossfade(true).build() }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(m.id) {
                detectTapGestures(
                    onTap = { if (m.isVideo && !playing) playing = true else if (!m.isVideo) onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f },
                )
            }
            .pointerInput(m.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.size > 1 || scale > 1f) {
                            scale = (scale * e.calculateZoom()).coerceIn(1f, 6f)
                            off = if (scale == 1f) Offset.Zero else clamp(off + e.calculatePan(), scale)
                            e.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                    if (scale < 1.02f) { scale = 1f; off = Offset.Zero }
                }
            }
    ) {
        AsyncImage(
            req, null,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
            contentScale = ContentScale.Fit,
        )
        if (m.isVideo && playing) {
            VideoPlayer(m.uri, Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp).background(Color.Black), insets = true)
        } else if (m.isVideo) {
            Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

private fun openVideo(ctx: android.content.Context, m: Media) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(i) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoSheet(m: Media, store: Store) {
    val md = store.metaOf(m.id)
    var fix by remember { mutableStateOf<PersonUi?>(null) }
    val people = remember(m.id, store.persons.collectAsState().value) { store.peopleOf(m.id) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(m.name, style = MaterialTheme.typography.titleLarge)
        Text(
            Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · h:mm a")),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (people.isNotEmpty()) {
            Text("People  ·  tap a face if it is the wrong person", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(people, key = { it.id }) { p ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { fix = p }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FaceCircle(p.cover, 56.dp)
                        Text(p.name.ifBlank { "Unnamed" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (md != null && md.labels.isNotEmpty()) {
            Text("Detected", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                md.labels.forEach {
                    Text(
                        it.replaceFirstChar { c -> c.uppercase() }, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (md != null && md.ocr.isNotBlank()) {
            Text("Text in photo", style = MaterialTheme.typography.titleMedium)
            Text(md.ocr.take(300), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        fix?.let { p ->
            AlertDialog(
                onDismissRequest = { fix = null },
                title = { Text("Not ${p.name.ifBlank { "this person" }}?") },
                text = { Text("This photo's face will be taken out of this person and shown as someone new.") },
                confirmButton = { TextButton(onClick = { store.detachFace(p.id, m.id); fix = null }) { Text("Remove", fontWeight = FontWeight.SemiBold) } },
                dismissButton = { TextButton(onClick = { fix = null }) { Text("Cancel") } },
            )
        }
        Text(
            "${m.width} × ${m.height} · ${"%.1f".format(m.size / 1_048_576.0)} MB · ${m.folder}",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
