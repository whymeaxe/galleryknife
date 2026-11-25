package com.hk.gallery

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

val AUDIO_EXT = setOf("mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "amr", "oga", "mka", "mid")

private class Tags(val title: String, val artist: String, val art: ImageBitmap?)

@Composable
fun AudioPlayer(file: File) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare(); playWhenReady = true }
    }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var loop by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { player.seekTo(0); player.pause() } }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) { while (true) { if (!dragging) pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(250) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    val tags by produceState(Tags(file.nameWithoutExtension, "", null), file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(file.path)
                val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
                val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                val art = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                r.release()
                Tags(t, a, art)
            }.getOrDefault(Tags(file.nameWithoutExtension, "", null))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(240.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
            tags.art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Outlined.MusicNote, null, Modifier.size(64.dp), tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
        Text(tags.title, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (tags.artist.isNotBlank()) Text(tags.artist, Modifier.padding(top = 4.dp), color = cs.onSurfaceVariant, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        Slider(
            value = if (dragging) dragPos else if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { dragging = true; dragPos = it },
            onValueChangeFinished = { player.seekTo((dragPos * dur).toLong()); pos = (dragPos * dur).toLong(); dragging = false },
            colors = SliderDefaults.colors(thumbColor = cs.onBackground, activeTrackColor = cs.onBackground, inactiveTrackColor = cs.onBackground.copy(alpha = 0.14f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtDuration(if (dragging) (dragPos * dur).toLong() else pos), style = MonoStyle, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(fmtDuration(dur), style = MonoStyle, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).bouncy({ player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Replay10, "Back 10 seconds", Modifier.size(26.dp))
            }
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(cs.primary).bouncy({ if (player.isPlaying) player.pause() else player.play() }),
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp), tint = cs.onPrimary) }
            Box(Modifier.size(44.dp).clip(CircleShape).bouncy({ player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration.coerceAtLeast(0))) }), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Forward10, "Forward 10 seconds", Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).bouncy({ loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Repeat, "Repeat", Modifier.size(22.dp), tint = if (loop) cs.secondary else cs.onSurfaceVariant)
        }
    }
}
