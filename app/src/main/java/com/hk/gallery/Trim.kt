package com.hk.gallery

import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(m: Media, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    val total = m.duration.coerceAtLeast(1000L).toFloat()
    var range by remember { mutableStateOf(0f..total) }
    var saving by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(m.uri)); prepare(); playWhenReady = false }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // Stop at the end handle while previewing.
    LaunchedEffect(range) {
        while (true) {
            delay(80)
            if (player.isPlaying && player.currentPosition >= range.endInclusive) { player.pause(); player.seekTo(range.start.toLong()) }
        }
    }

    fun save() {
        saving = true
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.start.toLong()).setEndPositionMs(range.endInclusive.toLong()).build()
        val item = MediaItem.Builder().setUri(m.uri).setClippingConfiguration(clip).build()
        val out = File(ctx.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val o = Compat.openOut(ctx, "GalleryKnife_trim_$stamp.mp4", "video/mp4", true, System.currentTimeMillis())!!
                            o.stream.use { os -> out.inputStream().use { it.copyTo(os) } }
                            o.finish()
                        }.isSuccess
                        out.delete()
                        withContext(Dispatchers.Main) {
                            saving = false
                            Toast.makeText(ctx, if (ok) "Saved a trimmed copy to Movies/GalleryKnife" else "Couldn't save the video", Toast.LENGTH_SHORT).show()
                            if (ok) onSaved()
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    saving = false
                    Toast.makeText(ctx, "Couldn't trim this video", Toast.LENGTH_SHORT).show()
                }
            })
            .build()
        transformer.start(EditedMediaItem.Builder(item).build(), out.absolutePath)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text("Trim", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !saving, onClick = { player.pause(); save() }) {
                Text("Save copy", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c -> (android.view.LayoutInflater.from(c).inflate(R.layout.player_texture, null) as PlayerView).also { it.player = player; it.useController = false } },
                modifier = Modifier.fillMaxSize(),
            )
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Trimming…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            RangeSlider(
                value = range,
                onValueChange = { n ->
                    if (n.start != range.start) player.seekTo(n.start.toLong()) else if (n.endInclusive != range.endInclusive) player.seekTo(n.endInclusive.toLong())
                    range = n
                },
                valueRange = 0f..total, enabled = !saving,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.secondary, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtDuration(range.start.toLong()), style = MonoStyle, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text("Length " + fmtDuration((range.endInclusive - range.start).toLong()), style = MonoStyle, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(fmtDuration(range.endInclusive.toLong()), style = MonoStyle, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconBtn(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Preview") {
                    if (player.isPlaying) player.pause() else {
                        if (player.currentPosition !in range.start.toLong()..range.endInclusive.toLong()) player.seekTo(range.start.toLong())
                        player.play()
                    }
                }
            }
            Text("Saves a copy. The original video stays untouched.", Modifier.fillMaxWidth().padding(top = 10.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
