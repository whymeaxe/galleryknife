package com.hk.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalGlass = compositionLocalOf<GraphicsLayer?> { null }

/**
 * Hosts content and lets overlays (tab bar, action bars) show a REAL blurred copy of what is behind them.
 * Needs Android 12+ (RenderEffect); otherwise bars fall back to a translucent surface.
 */
@Composable
fun GlassRoot(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit, overlay: @Composable BoxScope.() -> Unit) {
    val src = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val radius = Prefs.glassBlur
    val enabled = radius > 0 && Build.VERSION.SDK_INT >= 31
    SideEffect { blurred.renderEffect = if (enabled) BlurEffect(radius * 1.6f, radius * 1.6f, TileMode.Clamp) else null }
    Box(modifier) {
        Box(
            Modifier.matchParentSize().drawWithContent {
                val sz = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                if (enabled) {
                    src.record(this, layoutDirection, sz) { this@drawWithContent.drawContent() }
                    drawLayer(src)
                    blurred.record(this, layoutDirection, sz) { drawLayer(src) }
                } else drawContent()
            },
            content = background,
        )
        CompositionLocalProvider(LocalGlass provides (if (enabled) blurred else null)) { overlay() }
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    val layer = LocalGlass.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val dark = isDarkTheme()
    val tint = if (layer != null) (if (dark) Color(0xFF19191B).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.58f))
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val rim = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f))
        else listOf(Color.White, Color.Black.copy(alpha = 0.08f))
    )
    Box(
        modifier
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            .drawBehind { if (layer != null) translate(-pos.x, -pos.y) { drawLayer(layer) } }
            .background(tint)
            .border(0.5.dp, rim, shape),
    ) { content() }
}

/** Soft spring "press" feedback for custom buttons. */
@Composable
fun Modifier.bouncy(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }.clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Footer(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            "Designed and made by Himanshu Khandelwal",
            Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://himanshukhandelwal.in"))) } }.padding(12.dp),
            style = androidx.compose.ui.text.TextStyle(fontFamily = Fonts.serif, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, textAlign = TextAlign.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Mono meta line with the blade slash from the logo between parts. */
@Composable
fun MetaLine(vararg parts: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.size(1.dp, 12.dp).graphicsLayer { rotationZ = 24f }.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)))
            Text(p, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 36dp quiet icon button used in headers. */
@Composable
fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).bouncy(onClick),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, desc, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary) }
}

/** Thin progress line in the accent. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
    }
}
