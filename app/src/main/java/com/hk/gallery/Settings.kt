package com.hk.gallery

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(store: Store, allFiles: Boolean, askAll: () -> Unit, onCheckUpdate: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hex by remember { mutableStateOf("") }
    val faceErr by store.faceError.collectAsState()
    val blurSupported = Build.VERSION.SDK_INT >= 31

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 12.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ChevronLeft, null, Modifier.size(20.dp), tint = cs.tertiary)
            Text("Back", color = cs.tertiary, fontSize = 15.sp)
        }
        Text("Settings", Modifier.padding(start = 20.dp, top = 4.dp), style = MaterialTheme.typography.displayLarge)

        Label("Appearance")
        Group { Segmented(listOf("System", "Light", "Dark"), Prefs.mode) { Prefs.updateMode(it) } }

        Label("Frosted glass")
        Group {
            Line(last = true) {
                Text("Blur", Modifier.width(48.dp), fontSize = 15.sp)
                Slider(
                    Prefs.glassBlur.toFloat(), { Prefs.updateGlass(it.roundToInt()) }, Modifier.weight(1f), valueRange = 0f..40f, enabled = blurSupported,
                    colors = SliderDefaults.colors(
                        thumbColor = cs.onBackground, activeTrackColor = cs.onBackground, inactiveTrackColor = cs.onBackground.copy(alpha = 0.14f),
                        disabledThumbColor = cs.onSurfaceVariant, disabledActiveTrackColor = cs.onSurfaceVariant,
                    ),
                )
                Text(if (blurSupported) "${Prefs.glassBlur}" else "—", Modifier.width(28.dp), style = MonoStyle, color = cs.onSurfaceVariant)
            }
        }
        if (!blurSupported) Text("Needs Android 12 or newer.", Modifier.padding(start = 20.dp, top = 8.dp), fontSize = 12.sp, color = cs.onSurfaceVariant)

        Label("Accent")
        Group {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AccentChoices.forEach { (_, c) ->
                    val chosen = Prefs.accent == c
                    val ring by animateDpAsState(if (chosen) 2.dp else 0.dp, tween(160), label = "ring")
                    val shown = c ?: if (isDarkTheme()) Color(0xFFE7A14A) else Color(0xFFA35C0C)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (chosen) cs.onBackground else Color.Transparent).padding(ring + 0.dp)
                            .clickable { Prefs.updateAccent(c) }.padding(if (chosen) 2.dp else 0.dp),
                    ) { Box(Modifier.fillMaxSize().clip(CircleShape).background(shown)) }
                }
            }
            Line(last = true) {
                OutlinedTextField(
                    hex, { hex = it.take(7) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Custom hex, e.g. #C9A66B", fontSize = 14.sp) },
                    shape = RoundedCornerShape(10.dp), textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    runCatching { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }.onSuccess { Prefs.updateAccent(it) }
                }) { Text("Apply", color = cs.onBackground, fontWeight = FontWeight.Medium) }
            }
        }

        Label("People")
        Group {
            Line {
                Text("Matching", Modifier.width(72.dp), fontSize = 15.sp)
                Slider(
                    Prefs.faceThreshold.toFloat(), { Prefs.updateFaceThreshold(it.roundToInt()) }, Modifier.weight(1f), valueRange = 45f..75f,
                    colors = SliderDefaults.colors(thumbColor = cs.onBackground, activeTrackColor = cs.onBackground, inactiveTrackColor = cs.onBackground.copy(alpha = 0.14f)),
                )
            }
            Line(onClick = { store.regroupFaces { n -> android.widget.Toast.makeText(ctx, "$n people found", android.widget.Toast.LENGTH_SHORT).show() } }) {
                Text("Regroup faces with this setting", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
            Line(last = faceErr == null, onClick = { store.rescanFaces() }) {
                Text("Rescan every photo", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
            if (faceErr != null) Line(last = true) { Text("Face model error: $faceErr", fontSize = 13.sp, color = cs.onSurfaceVariant) }
        }

        Label("Access")
        Group {
            Line(onClick = if (allFiles) null else askAll) {
                Text("All files access", Modifier.weight(1f), fontSize = 15.sp)
                Text(if (allFiles) "On" else "Off", style = MonoStyle, color = cs.onSurfaceVariant)
            }
            Line(last = true) {
                Text("Converted files", Modifier.weight(1f), fontSize = 15.sp)
                Text("Documents/GalleryKnife", style = MonoStyle, color = cs.onSurfaceVariant)
            }
        }

        Label("Default app")
        Group {
            Line(last = true, onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }) {
                Text("Set as default gallery", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        Text(
            "You can also open any photo from another app and choose GalleryKnife with Always. Say \"Hey Google, open GalleryKnife\" to launch it by voice.",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
        )

        Label("About")
        Group {
            Line {
                Text("Version", Modifier.weight(1f), fontSize = 15.sp)
                Text(Updater.currentVersion(ctx), style = MonoStyle, color = cs.onSurfaceVariant)
            }
            Line(last = true, onClick = onCheckUpdate) {
                Text("Check for updates", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }

        Footer(Modifier.padding(top = 20.dp, bottom = 16.dp))
    }
}

@Composable
private fun Label(t: String) {
    Text(t, Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape),
        content = content,
    )
}

@Composable
private fun Line(last: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { if (!last) drawLine(line, Offset(16.dp.toPx(), size.height), Offset(size.width, size.height), 1f) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** Segmented control: tap, or press and slide across the options. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.padding(8.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.background)
            .border(0.5.dp, cs.outline, RoundedCornerShape(10.dp)).padding(2.dp),
    ) {
        val w = maxWidth / options.size
        val wPx = constraints.maxWidth.toFloat()
        val x by animateDpAsState(w * selected, tween(220, easing = FastOutSlowInEasing), label = "seg")
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant)
                .drawBehind { drawLine(Color.White.copy(alpha = 0.10f), Offset(8.dp.toPx(), 0f), Offset(size.width - 8.dp.toPx(), 0f), 1f) },
        )
        Row(
            Modifier.fillMaxSize()
                .pointerInput(options.size) { detectTapGestures { onSelect(((it.x / wPx) * options.size).toInt().coerceIn(0, options.lastIndex)) } }
                .pointerInput(options.size) { detectHorizontalDragGestures { c, _ -> onSelect(((c.position.x / wPx) * options.size).toInt().coerceIn(0, options.lastIndex)) } },
        ) {
            options.forEachIndexed { i, o ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(o, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (i == selected) cs.onSurface else cs.tertiary)
                }
            }
        }
    }
}
