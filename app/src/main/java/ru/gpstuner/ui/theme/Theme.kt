package ru.gpstuner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.gpstuner.ubx.Gnss

object Palette {
    val Bg = Color(0xFF0D1115)
    val Surface = Color(0xFF151B21)
    val SurfaceHigh = Color(0xFF1D252D)
    val SurfaceHigher = Color(0xFF26303A)
    val Outline = Color(0xFF2E3943)
    val Accent = Color(0xFF3DD6C3)
    val OnAccent = Color(0xFF00201C)
    val Text = Color(0xFFE8EEF2)
    val TextDim = Color(0xFF8E9BA7)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
}

fun gnssColor(gnssId: Int): Color = when (gnssId) {
    Gnss.GPS.id -> Color(0xFF60A5FA)
    Gnss.GLONASS.id -> Color(0xFFF87171)
    Gnss.GALILEO.id -> Color(0xFFA78BFA)
    Gnss.BEIDOU.id -> Color(0xFFFBBF24)
    Gnss.QZSS.id -> Color(0xFF34D399)
    Gnss.SBAS.id -> Color(0xFF94A3B8)
    else -> Color(0xFF64748B)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Color(0xFF113B36),
    onPrimaryContainer = Palette.Accent,
    secondaryContainer = Palette.SurfaceHigher,
    onSecondaryContainer = Palette.Text,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigher,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Bad,
    onError = Color(0xFF2A0606),
)

private val base = Typography()

/** Компактная шкала: экран ГУ невысокий (≈480 dp), помещаться должно без прокрутки. */
private val type = base.copy(
    displaySmall = base.displaySmall.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = base.titleLarge.copy(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

/** Интерфейс свёрстан так, чтобы без прокрутки влезать в ≈1210×456 dp (1920×720 при 240 dpi за вычетом статус-бара). */
private const val DESIGN_WIDTH_DP = 1210f
private const val DESIGN_HEIGHT_DP = 456f

@Composable
fun GpsTunerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type) {
        ScaledForScreen(content)
    }
}

/**
 * ГУ — 1920×720 при 160 dpi (окно 1760×670 dp): стандартные размеры там мелкие.
 * Увеличиваем, пока окно не сожмётся до расчётной площади; на телефонах и при 240 dpi масштаб 1.
 * Диалоги и всплывающие меню рисуются в своих окнах с системной плотностью — их содержимое оборачиваем отдельно.
 */
@Composable
fun ScaledForScreen(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val cfg = LocalConfiguration.current
    val scale = minOf(cfg.screenWidthDp / DESIGN_WIDTH_DP, cfg.screenHeightDp / DESIGN_HEIGHT_DP).coerceIn(1f, 1.6f)
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale), content = content)
}
