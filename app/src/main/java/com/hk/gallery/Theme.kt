package com.hk.gallery

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Graphite ramp (dark) and neutral ramp (light). Surfaces stay neutral; colour lives in the photos and one accent.
private val DarkBg = Color(0xFF141415)
private val DarkCard = Color(0xFF19191B)
private val DarkRaise = Color(0xFF232325)
private val DarkT1 = Color(0xFFEDEDED)
private val DarkT2 = Color(0xFFA1A1A6)
private val DarkT3 = Color(0xFF85858B)
private val LightBg = Color(0xFFFAFAFA)
private val LightRaise = Color(0xFFF1F1F2)
private val LightT1 = Color(0xFF171717)
private val LightT2 = Color(0xFF525252)
private val LightT3 = Color(0xFF737373)

/** Safelight amber by default (selection and progress only). null = default. */
val AccentChoices: List<Pair<String, Color?>> = listOf(
    "Safelight" to null, "Champagne" to Color(0xFFB9A27A), "Sage" to Color(0xFF7C9A86), "Slate" to Color(0xFF5B7FA6),
    "Plum" to Color(0xFF8A6A9C), "Clay" to Color(0xFFC0704F), "Rose" to Color(0xFFC98494),
)

object Prefs {
    private lateinit var sp: SharedPreferences
    var accent by mutableStateOf<Color?>(null)
    var mode by mutableIntStateOf(0) // 0 system, 1 light, 2 dark
    var glassBlur by mutableIntStateOf(22) // 0 = solid, otherwise blur strength
    var favs by mutableStateOf<Set<Long>>(emptySet())
    var faceThreshold by mutableIntStateOf(58) // cosine similarity x100 needed to call two faces the same person

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("hk_prefs", Context.MODE_PRIVATE)
        accent = if (sp.contains("accent3")) Color(sp.getInt("accent3", 0)) else null
        mode = sp.getInt("mode", 0)
        glassBlur = sp.getInt("glass", 22)
        faceThreshold = sp.getInt("face_t", 58)
        favs = sp.getStringSet("favs", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun updateAccent(c: Color?) {
        accent = c
        sp.edit().apply { if (c == null) remove("accent3") else putInt("accent3", c.toArgb()) }.apply()
    }
    fun updateMode(m: Int) { mode = m; sp.edit().putInt("mode", m).apply() }
    fun updateFaceThreshold(v: Int) { faceThreshold = v; sp.edit().putInt("face_t", v).apply() }
    fun updateGlass(v: Int) { glassBlur = v; sp.edit().putInt("glass", v).apply() }
    fun toggleFav(id: Long) {
        favs = if (id in favs) favs - id else favs + id
        sp.edit().putStringSet("favs", favs.map { it.toString() }.toSet()).apply()
    }
    fun str(k: String) = sp.getString(k, "") ?: ""
    fun setStr(k: String, v: String) { sp.edit().putString(k, v).apply() }
    fun lng(k: String) = sp.getLong(k, 0L)
    fun setLng(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
    fun flag(k: String) = sp.getBoolean(k, false)
    fun setFlag(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
}

@OptIn(ExperimentalTextApi::class)
object Fonts {
    lateinit var sans: FontFamily
    lateinit var mono: FontFamily
    lateinit var serif: FontFamily

    private fun variable(a: AssetManager, path: String) = FontFamily(
        listOf(400, 500, 600).map {
            Font(path, a, FontWeight(it), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(it)))
        }
    )

    fun init(a: AssetManager) {
        sans = variable(a, "fonts/geist.ttf")
        mono = variable(a, "fonts/geistmono.ttf")
        serif = variable(a, "fonts/newsreader.ttf")
    }
}

private fun on(c: Color) = if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.62f) Color(0xFF111111) else Color.White

private fun light(a: Color?): androidx.compose.material3.ColorScheme {
    val acc = a ?: Color(0xFFA35C0C)
    return lightColorScheme(
        primary = LightT1, onPrimary = Color.White,
        secondary = acc, onSecondary = on(acc),
        background = LightBg, onBackground = LightT1,
        surface = Color.White, onSurface = LightT1,
        surfaceVariant = LightRaise, onSurfaceVariant = LightT3,
        outline = Color(0x14171717), outlineVariant = Color(0x14171717), secondaryContainer = Color(0xFFE8E8EA),
        tertiary = LightT2,
    )
}

private fun dark(a: Color?): androidx.compose.material3.ColorScheme {
    val acc = a ?: Color(0xFFE7A14A)
    return darkColorScheme(
        primary = DarkT1, onPrimary = Color(0xFF111111),
        secondary = acc, onSecondary = on(acc),
        background = DarkBg, onBackground = DarkT1,
        surface = DarkCard, onSurface = DarkT1,
        surfaceVariant = DarkRaise, onSurfaceVariant = DarkT3,
        outline = Color(0x14EDEDED), outlineVariant = Color(0x14EDEDED), secondaryContainer = Color(0xFF2A2A2D),
        tertiary = DarkT2,
    )
}

private fun sans(size: Int, w: FontWeight, ls: Float = 0f) = TextStyle(fontFamily = Fonts.sans, fontSize = size.sp, fontWeight = w, letterSpacing = ls.sp, lineHeight = (size + 6).sp)

private fun buildType() = Typography(
    displayLarge = sans(32, FontWeight.Medium, -0.6f).copy(lineHeight = 36.sp),
    headlineMedium = sans(28, FontWeight.Medium, -0.4f).copy(lineHeight = 32.sp),
    titleLarge = sans(20, FontWeight.Medium, -0.2f),
    titleMedium = sans(15, FontWeight.Medium, 0f),
    bodyLarge = sans(15, FontWeight.Normal, 0f).copy(lineHeight = 20.sp),
    bodyMedium = sans(14, FontWeight.Normal, 0f).copy(lineHeight = 20.sp),
    bodySmall = sans(12, FontWeight.Normal, 0f).copy(lineHeight = 16.sp),
    labelLarge = sans(14, FontWeight.Medium, 0f).copy(lineHeight = 20.sp),
    labelMedium = sans(12, FontWeight.Medium, 0f).copy(lineHeight = 16.sp),
    labelSmall = sans(11, FontWeight.Medium, 0f).copy(lineHeight = 14.sp),
)

@Composable
fun isDarkTheme(): Boolean = when (Prefs.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

@Composable
fun HkTheme(content: @Composable () -> Unit) {
    val scheme = if (isDarkTheme()) dark(Prefs.accent) else light(Prefs.accent)
    MaterialTheme(colorScheme = scheme, typography = remember { buildType() }) {
        Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}

/** Mono style for counts and meta. */
val MonoStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 16.sp)
