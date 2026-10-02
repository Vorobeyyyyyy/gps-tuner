package ru.gpstuner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gpstuner.ubx.Gnss
import ru.gpstuner.ubx.Sat
import ru.gpstuner.ui.theme.Palette
import ru.gpstuner.ui.theme.gnssColor

/** Высота кнопок: компактно, но под палец. */
val TouchHeight = 44.dp

/** Варианты в строках настроек чуть ниже кнопок — чтобы все строки влезали без прокрутки. */
private val PillHeight = 40.dp

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Palette.Surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

private val PillRadius = 12.dp

/** Пунктирная рамка поверх содержимого — знак «изменится после «Применить»». */
fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp, dash: Dp = 5.dp, gap: Dp = 4.dp) = drawWithContent {
    drawContent()
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()))),
    )
}

/**
 * Вариант настройки. Заливка — каким значение будет после «Применить».
 * [pending] — состояние кнопки изменится при применении: рамка пунктирная.
 */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Accent,
    dot: Color? = null,
    check: Boolean = false,
    pending: Boolean = false,
) {
    val border = when {
        pending -> null
        selected -> BorderStroke(1.5.dp, color)
        else -> BorderStroke(1.dp, Palette.Outline)
    }
    // пунктир рисуем внутри плашки: снаружи Surface расширяет зону нажатия до 48 dp, и рамка разъехалась бы с заливкой
    val pendingBorder = if (pending) Modifier.dashedBorder(if (selected) color else color.copy(alpha = 0.45f), 1.5.dp, PillRadius) else Modifier
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(PillRadius),
        color = if (selected) color.copy(alpha = 0.16f) else Palette.SurfaceHigh,
        border = border,
        modifier = modifier.heightIn(min = PillHeight).alpha(if (enabled) 1f else 0.38f),
    ) {
        Row(
            pendingBorder.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                // уходящее значение чуть ярче обычного невыбранного — чтобы было видно, что оно меняется
                color = when {
                    selected -> Palette.Text
                    pending -> Palette.Text.copy(alpha = 0.7f)
                    else -> Palette.TextDim
                },
                maxLines = 1,
            )
            if (check && selected) Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

enum class Level { INFO, WARN, BAD }

private fun Level.color() = when (this) {
    Level.INFO -> Palette.TextDim
    Level.WARN -> Palette.Warn
    Level.BAD -> Palette.Bad
}

@Composable
fun Hint(text: String, level: Level, modifier: Modifier = Modifier) {
    val color = level.color()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (level == Level.INFO) Icons.Default.Info else Icons.Default.Warning, null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun Banner(text: String, level: Level, modifier: Modifier = Modifier, actions: (@Composable () -> Unit)? = null) {
    val color = level.color()
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.heightIn(min = TouchHeight + 8.dp).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            actions?.invoke()
        }
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, color = color)
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sub: String? = null,
    valueColor: Color = Palette.Text,
) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Palette.SurfaceHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1)
            Row {
                Text(value, style = MaterialTheme.typography.displaySmall, color = valueColor, maxLines = 1, modifier = Modifier.alignByBaseline())
                if (unit != null) {
                    Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
            }
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val GNSS_ORDER = listOf(Gnss.GPS, Gnss.GLONASS, Gnss.GALILEO, Gnss.BEIDOU, Gnss.QZSS, Gnss.SBAS).map { it.id }

private fun letter(gnssId: Int) = Gnss.byId(gnssId)?.letter ?: "?"

/** Столбики уровня сигнала (дБГц) по спутникам; используемые в решении — сплошные. */
@Composable
fun SignalChart(sats: List<Sat>, modifier: Modifier = Modifier, barArea: Dp = 76.dp) {
    val sorted = sats.sortedWith(compareBy({ GNSS_ORDER.indexOf(it.gnssId).let { i -> if (i < 0) 99 else i } }, { it.svId }))
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (s in sorted) {
            val c = gnssColor(s.gnssId)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                Text("${s.cno}", fontSize = 10.sp, color = if (s.used) Palette.Text else Palette.TextDim)
                Box(Modifier.height(barArea).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                    val frac = (s.cno.coerceIn(0, 50) / 50f).coerceAtLeast(0.04f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(barArea * frac)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (s.used) c else c.copy(alpha = 0.28f)),
                    )
                }
                Text(
                    "${letter(s.gnssId)}${s.svId}",
                    fontSize = 10.sp,
                    fontWeight = if (s.used) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (s.used) c else Palette.TextDim,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
fun SignalLegend(sats: List<Sat>, modifier: Modifier = Modifier) {
    PillRow(modifier) {
        for (id in GNSS_ORDER) {
            val list = sats.filter { it.gnssId == id }
            if (list.isEmpty()) continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(gnssColor(id)))
                Text(
                    "${Gnss.byId(id)?.title} ${list.count { it.used }}/${list.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextDim,
                )
            }
        }
    }
}
