package ru.gpstuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import ru.gpstuner.LiveStatus
import ru.gpstuner.Preset
import ru.gpstuner.SessionState
import ru.gpstuner.USB_GPS_NMEA
import ru.gpstuner.ubx.DynModel
import ru.gpstuner.ubx.Gnss
import ru.gpstuner.ubx.NmeaMsg
import ru.gpstuner.ubx.PortCfg
import ru.gpstuner.ui.theme.Palette
import ru.gpstuner.ui.theme.ScaledForScreen
import ru.gpstuner.ui.theme.gnssColor

class ReceiverActions(
    val onDisconnect: () -> Unit,
    val onRate: (Int) -> Unit,
    val onDynModel: (DynModel) -> Unit,
    val onGnss: (Gnss) -> Unit,
    val onNmea: (NmeaMsg) -> Unit,
    val onBaud: (Int) -> Unit,
    val onPreset: (Preset) -> Unit,
    val onUnloadPort: () -> Unit,
    val onRelievePort: () -> Unit,
    val onReadAnyway: () -> Unit,
    val onDiscard: () -> Unit,
    val onApply: () -> Unit,
    val onSave: () -> Unit,
    val onReset: () -> Unit,
)

private val RATES_MS = listOf(1000, 500, 200, 100)
private val MAIN_MODELS = setOf(DynModel.AUTOMOTIVE, DynModel.PORTABLE, DynModel.PEDESTRIAN, DynModel.STATIONARY)

private fun hzText(hz: Double) =
    if (hz >= 0.995 && abs(hz - hz.roundToLong()) < 0.05) "${hz.roundToLong()} Гц" else fmt("%.1f Гц", hz)

private fun fmt(pattern: String, v: Double) = String.format(Locale.getDefault(), pattern, v)

@Composable
fun ReceiverScreen(s: SessionState, actions: ReceiverActions) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(s, actions.onDisconnect)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                        SettingsContent(s, actions)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { LiveContent(s.live, s.overloadGate, s.maxRateHz()) }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiveContent(s.live, s.overloadGate, s.maxRateHz())
                    SettingsContent(s, actions)
                }
            }
        }
        ActionBar(s, actions, onReset = { confirmReset = true })
    }
    if (confirmReset) ResetDialog(onDismiss = { confirmReset = false }, onConfirm = {
        confirmReset = false
        actions.onReset()
    })
}

/** Свой диалог вместо AlertDialog: содержимое масштабируется вместе с остальным интерфейсом. */
@Composable
private fun ResetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScaledForScreen {
            Surface(shape = RoundedCornerShape(24.dp), color = Palette.SurfaceHigh, modifier = Modifier.widthIn(max = 560.dp).padding(24.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Сбросить к заводским?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Сбросятся все настройки приёмника, в том числе те, которых нет в приложении: SBAS, энергосбережение, " +
                            "протоколы портов и т. д. Сохранённое во flash будет стёрто.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, modifier = Modifier.height(TouchHeight)) { Text("Отмена") }
                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Bad, contentColor = Palette.Bg),
                            modifier = Modifier.height(TouchHeight),
                        ) { Text("Сбросить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(s: SessionState, onDisconnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onDisconnect, contentPadding = PaddingValues(start = 10.dp, end = 14.dp), modifier = Modifier.height(TouchHeight)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Отключить")
        }
        Column(Modifier.weight(1f)) {
            val info = s.info
            Text(
                listOfNotNull(info?.chipName, info?.module).joinToString(" · ").ifEmpty { s.title },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(info?.firmware, info?.protocol?.let { "протокол $it" }, s.subtitle).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.unsaved) StatusChip("Не сохранено", Palette.Warn)
        FixChip(s)
    }
}

@Composable
private fun FixChip(s: SessionState) {
    val live = s.live
    when {
        live.noData -> StatusChip("Нет данных", Palette.Bad)
        live.fixType == null && s.loading -> StatusChip("Подключение…", Palette.TextDim)
        live.fixOk && (live.fixType ?: 0) >= 3 -> StatusChip("3D-фикс", Palette.Good)
        live.fixOk && live.fixType == 2 -> StatusChip("2D-фикс", Palette.Warn)
        else -> StatusChip("Нет фикса", Palette.Bad)
    }
}

// --- настройки: одна карточка, по строке на параметр ---

@Composable
private fun SettingsContent(s: SessionState, a: ReceiverActions) {
    val draft = s.draft
    when {
        s.unsupported != null -> SectionCard("Не поддерживается") { Hint(s.unsupported, Level.WARN) }
        s.overloadGate -> OverloadCard(s, a)
        s.loading || draft == null -> SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Text(s.busy ?: "Читаю настройки приёмника…", style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            }
        }
        else -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Palette.Surface) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                var first = true
                fun divider(): Boolean = (!first).also { first = false }
                if (draft.rate != null) {
                    if (divider()) RowDivider()
                    RateRow(s, a)
                }
                if (draft.dynModel != null) {
                    if (divider()) RowDivider()
                    DynModelRow(s, a)
                }
                if (draft.gnss != null) {
                    if (divider()) RowDivider()
                    GnssRow(s, a)
                }
                if (draft.nmea.isNotEmpty()) {
                    if (divider()) RowDivider()
                    NmeaRow(s, a)
                }
                if (draft.port != null) {
                    if (divider()) RowDivider()
                    PortRow(s, a)
                }
            }
        }
    }
}

@Composable
private fun OverloadCard(s: SessionState, a: ReceiverActions) {
    SectionCard("Порт перегружен") {
        Text(
            "На ${s.initialBaud} бод данные приёмника не помещаются в порт: часть сообщений теряется, " +
                "настройки будут читаться долго и с пропусками.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Разгрузить — 1 Гц и только RMC + GGA, больше USB GPS не нужно; скорость в нём менять не придётся. " +
                "Поднять до 115200 — тогда ту же скорость надо выставить в USB GPS.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = a.onUnloadPort, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(TouchHeight)) {
                Text("Разгрузить порт")
            }
            OutlinedButton(onClick = a.onRelievePort, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(TouchHeight)) {
                Text("Поднять до 115200")
            }
            TextButton(onClick = a.onReadAnyway, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(TouchHeight)) {
                Text("Прочитать как есть")
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = Palette.Outline.copy(alpha = 0.6f))

/**
 * Подпись слева, варианты справа. Что изменится по «Применить», видно по самим вариантам (пунктир),
 * а у подписи строки с изменениями — точка.
 */
@Composable
private fun SettingRow(
    label: String,
    changed: Boolean,
    hints: @Composable () -> Unit = {},
    options: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.width(88.dp).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (changed) Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PillRow { options() }
            hints()
        }
    }
}

/** Для одиночного выбора: вариант меняется, если он выбран в черновике, но не в приёмнике, или наоборот. */
private fun <T> pending(value: T, draft: T, current: T?) = current != null && (value == draft) != (value == current)

@Composable
private fun RateRow(s: SessionState, a: ReceiverActions) {
    val draft = s.draft?.rate ?: return
    val cur = s.current?.rate
    // предел зависит от прошивки и включённых систем: с Galileo у NEO-M8N всего 3 Гц
    val max = s.info?.maxRateHz(s.draft?.gnss)
    val draftMs = draft.measRateMs.takeIf { draft.navRate == 1 }
    val curMs = cur?.measRateMs?.takeIf { cur.navRate == 1 }
    val options = (RATES_MS + listOfNotNull(draftMs, curMs).filter { it !in RATES_MS }).distinct()
    SettingRow(
        "Частота",
        changed = cur != null && cur != draft,
        hints = {
            if (max != null && draft.hz > max + 0.01) {
                Hint("Предел M8 с этими системами — $max Гц: точность упадёт, скорость будет скакать", Level.BAD)
            }
        },
    ) {
        for (ms in options) {
            Pill(
                hzText(1000.0 / ms),
                selected = ms == draftMs,
                pending = pending(ms, draftMs, curMs),
                enabled = s.editable,
                onClick = { a.onRate(ms) },
            )
        }
    }
}

@Composable
private fun DynModelRow(s: SessionState, a: ReceiverActions) {
    val draft = s.draft?.dynModel ?: return
    val cur = s.current?.dynModel
    SettingRow("Модель", changed = cur != null && cur != draft) {
        // «Море» и «Воздух» машине не нужны — показываем, только если уже выбраны
        for (m in DynModel.entries.filter { it in MAIN_MODELS || it.code == draft || it.code == cur }) {
            Pill(
                m.title,
                selected = m.code == draft,
                pending = pending(m.code, draft, cur),
                enabled = s.editable,
                onClick = { a.onDynModel(m) },
            )
        }
    }
}

@Composable
private fun GnssRow(s: SessionState, a: ReceiverActions) {
    val draft = s.draft?.gnss ?: return
    val cur = s.current?.gnss
    val changed = cur != null && Gnss.entries.any { draft.isEnabled(it) != cur.isEnabled(it) }
    SettingRow(
        "Системы",
        changed = changed,
        hints = { if (changed) Hint("Приёмник перезапустится, фикс пропадёт на 30–60 с", Level.WARN) },
    ) {
        for (g in Gnss.entries) {
            if (!draft.has(g)) continue
            Pill(
                g.title,
                selected = draft.isEnabled(g),
                pending = cur != null && draft.isEnabled(g) != cur.isEnabled(g),
                enabled = s.editable && s.supports(g),
                color = gnssColor(g.id),
                dot = gnssColor(g.id),
                onClick = { a.onGnss(g) },
            )
        }
    }
}

@Composable
private fun NmeaRow(s: SessionState, a: ReceiverActions) {
    val draft = s.draft?.nmea ?: return
    val cur = s.current?.nmea
    fun on(map: Map<NmeaMsg, Int>?, m: NmeaMsg) = (map?.get(m) ?: 0) > 0
    SettingRow(
        "NMEA",
        changed = cur != null && NmeaMsg.entries.any { on(draft, it) != on(cur, it) },
        hints = {
            when {
                USB_GPS_NMEA.any { (draft[it] ?: 1) == 0 } -> Hint("Без RMC и GGA USB GPS не получит координаты", Level.BAD)
                draft.any { (m, r) -> m !in USB_GPS_NMEA && r > 0 } -> Hint("USB GPS читает только RMC и GGA — остальное лишний трафик", Level.INFO)
            }
        },
    ) {
        for (m in NmeaMsg.entries) {
            if (m !in draft) continue
            Pill(
                m.name,
                selected = on(draft, m),
                pending = cur != null && on(draft, m) != on(cur, m),
                enabled = s.editable,
                onClick = { a.onNmea(m) },
            )
        }
    }
}

@Composable
private fun PortRow(s: SessionState, a: ReceiverActions) {
    val draft = s.draft?.port ?: return
    val cur = s.current?.port
    val load = s.live.portLoad
    val options = (PortCfg.BAUDS + listOfNotNull(draft.baud, cur?.baud).filter { it !in PortCfg.BAUDS }).distinct()
    SettingRow(
        "Порт",
        changed = cur != null && cur != draft,
        hints = {
            when {
                load != null && load > 0.9 -> Hint("Порт перегружен (${(load * 100).toInt()}%) — данные теряются", Level.BAD)
                load != null && load > 0.7 -> Hint("Порт загружен на ${(load * 100).toInt()}%", Level.WARN)
            }
            val target = draft.baud.takeIf { s.initialBaud != null && it != s.initialBaud }
            if (target != null) Hint("Выставьте $target и в настройках USB GPS, иначе он не получит данные", Level.WARN)
        },
    ) {
        for (b in options) {
            Pill(
                "$b",
                selected = b == draft.baud,
                pending = pending(b, draft.baud, cur?.baud),
                enabled = s.editable,
                onClick = { a.onBaud(b) },
            )
        }
    }
}

// --- живой статус ---

@Composable
private fun LiveContent(live: LiveStatus, overloadGate: Boolean, maxRate: Int?) {
    if (live.noData) Banner("Нет данных от приёмника", Level.BAD)
    val load = live.portLoad
    if (!live.noData && !overloadGate && load != null && load > 0.9) {
        Banner("Порт перегружен — данные теряются. Поднимите скорость порта или снизьте частоту", Level.BAD)
    }
    BoxWithConstraints {
        if (maxWidth >= 480.dp) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val m = Modifier.weight(1f).fillMaxHeight()
                RateTile(live, maxRate, m)
                SpeedTile(live, m)
                AccuracyTile(live, m)
                SatsTile(live, m)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RateTile(live, maxRate, Modifier.weight(1f).fillMaxHeight())
                    SpeedTile(live, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccuracyTile(live, Modifier.weight(1f).fillMaxHeight())
                    SatsTile(live, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
    SectionCard(null) {
        val sats = live.sats
        if (sats.isNullOrEmpty()) {
            Text(
                if (sats == null) "Нет данных о спутниках" else "Спутники пока не видны",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
            )
        } else {
            SignalLegend(sats)
            SignalChart(sats)
        }
    }
    if (live.errors > 0) {
        Text("Ошибок в потоке: ${live.errors}", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
    }
}

@Composable
private fun RateTile(live: LiveStatus, maxRate: Int?, modifier: Modifier) {
    val rate = live.nmeaRate
    StatTile(
        "Частота NMEA",
        rate?.let { fmt("%.1f", it) } ?: "—",
        modifier,
        unit = "/с",
        sub = live.portLoad?.let { "порт ${(it * 100).toInt().coerceAtMost(999)}%" } ?: "строк RMC",
        // зелёный — в пределах возможностей приёмника, красный — выше предела
        valueColor = when {
            rate == null -> Palette.Text
            maxRate == null || rate <= maxRate + 0.3 -> Palette.Good
            else -> Palette.Bad
        },
    )
}

/** Предел частоты для систем, которые сейчас стоят в приёмнике. */
private fun SessionState.maxRateHz(): Int? = info?.maxRateHz(current?.gnss)

@Composable
private fun SpeedTile(live: LiveStatus, modifier: Modifier) = StatTile(
    "Скорость",
    live.speedKmh?.let { fmt("%.0f", it) } ?: "—",
    modifier,
    unit = "км/ч",
    sub = live.sAccKmh?.let { fmt("±%.1f км/ч", it) } ?: " ",
)

@Composable
private fun AccuracyTile(live: LiveStatus, modifier: Modifier) {
    val h = live.hAccM
    StatTile(
        "Точность",
        h?.let { fmt("±%.1f", it) } ?: "—",
        modifier,
        unit = "м",
        sub = live.hdop?.let { fmt("HDOP %.1f", it) } ?: " ",
        valueColor = when {
            h == null -> Palette.Text
            h <= 3.5 -> Palette.Good
            h <= 8.0 -> Palette.Warn
            else -> Palette.Bad
        },
    )
}

@Composable
private fun SatsTile(live: LiveStatus, modifier: Modifier) = StatTile(
    "Спутники",
    live.numSvUsed?.toString() ?: "—",
    modifier,
    unit = live.sats?.size?.let { "из $it" },
    sub = "в решении",
)

// --- кнопки ---

private val Preset.details: String
    get() = "${hzText(1000.0 / rateMs)}, " + gnss.filter { it in Gnss.MAJOR }.joinToString(" + ") { it.title }

@Composable
private fun PresetMenu(s: SessionState, a: ReceiverActions, pad: PaddingValues) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = s.editable, contentPadding = pad, modifier = Modifier.height(TouchHeight)) {
            Text("Пресет")
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceHigh) {
            for (p in Preset.entries) ScaledForScreen {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.title, style = MaterialTheme.typography.labelLarge)
                            Text(p.details, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                        }
                    },
                    onClick = {
                        open = false
                        a.onPreset(p)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionBar(s: SessionState, a: ReceiverActions, onReset: () -> Unit) {
    Surface(color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val compact = maxWidth < 840.dp
            val pad = PaddingValues(horizontal = if (compact) 12.dp else 18.dp)
            Row(
                Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            ) {
                if (compact) {
                    PresetMenu(s, a, pad)
                } else {
                    Text("Пресет", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
                    for (p in Preset.entries) {
                        OutlinedButton(onClick = { a.onPreset(p) }, enabled = s.editable, contentPadding = pad, modifier = Modifier.height(TouchHeight)) {
                            Text("${p.title} · ${hzText(1000.0 / p.rateMs)}")
                        }
                    }
                }
                if (s.dirty && !compact) {
                    TextButton(onClick = a.onDiscard, enabled = s.busy == null, contentPadding = pad, modifier = Modifier.height(TouchHeight)) {
                        Text("Отменить")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onReset,
                    enabled = s.editable,
                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.Bad),
                    contentPadding = pad,
                    modifier = Modifier.height(TouchHeight),
                ) { Text(if (compact) "Сброс" else "Сброс к заводским") }
                FilledTonalButton(
                    onClick = a.onSave,
                    enabled = s.editable && s.unsaved && !s.dirty,
                    contentPadding = pad,
                    modifier = Modifier.height(TouchHeight),
                ) { Text(if (compact) "Сохранить" else "Сохранить в приёмник") }
                Button(
                    onClick = a.onApply,
                    enabled = s.editable && s.dirty,
                    contentPadding = pad,
                    modifier = Modifier.height(TouchHeight),
                ) {
                    if (s.busy != null) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Palette.OnAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(s.busy)
                    } else {
                        Text("Применить")
                        val n = s.pendingChanges
                        if (n > 0 && s.editable) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(Palette.OnAccent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$n", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Palette.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
