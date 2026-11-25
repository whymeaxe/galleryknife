package com.hk.gallery

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class Sel {
    val list = mutableStateListOf<Media>()
    val active get() = list.isNotEmpty()
    fun has(id: Long) = list.any { it.id == id }
    fun toggle(m: Media) { val i = list.indexOfFirst { it.id == m.id }; if (i >= 0) list.removeAt(i) else list.add(m) }
    fun clear() = list.clear()
}

@Composable
fun Thumb(m: Media, sel: Sel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val req = remember(m.id) { ImageRequest.Builder(ctx).data(m.uri).size(360).crossfade(150).build() }
    val selected = sel.has(m.id)
    val inset by animateDpAsState(if (selected) 5.dp else 0.dp, tween(160), label = "inset")
    val acc = MaterialTheme.colorScheme.secondary
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val shape = RoundedCornerShape(if (selected) 6.dp else 0.dp)
        AsyncImage(
            req, null,
            Modifier.fillMaxSize().padding(inset).clip(shape).then(if (selected) Modifier.border(2.dp, acc, shape) else Modifier),
            contentScale = ContentScale.Crop,
        )
        if (m.id in Prefs.favs) {
            Icon(Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(13.dp), tint = Color.White)
        }
        if (m.isVideo) {
            Text(
                fmtDuration(m.duration), Modifier.align(Alignment.BottomEnd).padding(horizontal = 6.dp, vertical = 5.dp),
                style = MonoStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Color.White,
            )
        }
        if (sel.active) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).clip(CircleShape)
                    .background(if (selected) acc else Color(0x2E000000))
                    .then(if (selected) Modifier else Modifier.border(1.5.dp, Color(0xD9FFFFFF), CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(12.dp)) }
        }
    }
}

@Composable
fun FaceCircle(file: File, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val req = remember(file.path) { ImageRequest.Builder(ctx).data(file).crossfade(true).build() }
    AsyncImage(req, null, modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
        else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
    }
}
