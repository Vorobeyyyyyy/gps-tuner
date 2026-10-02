package ru.gpstuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.gpstuner.AppState
import ru.gpstuner.DeviceItem
import ru.gpstuner.ui.theme.Palette

class ConnectActions(
    val onOpenUsbGps: () -> Unit,
    val onRefresh: () -> Unit,
    val onConnect: (DeviceItem) -> Unit,
    val onDemo: () -> Unit,
    val onDismissBanner: () -> Unit,
)

private val ButtonPad = PaddingValues(horizontal = 16.dp)

/** Стартовый экран без прокрутки: шапка, сообщение (если есть) и карточка адаптеров на всю оставшуюся высоту. */
@Composable
fun ConnectScreen(state: AppState, actions: ConnectActions) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(state, actions)
            when {
                state.error != null -> Banner(state.error, if (state.errorBusy) Level.WARN else Level.BAD) {
                    if (state.errorBusy && state.usbGpsInstalled) UsbGpsButton(actions)
                    CloseButton(actions)
                }
                state.remindRestart -> Banner(
                    "Адаптер свободен — запустите USB GPS снова" +
                        (state.remindBaud?.let { " и выставьте в нём скорость $it" } ?: ""),
                    Level.WARN,
                ) {
                    if (state.usbGpsInstalled) UsbGpsButton(actions)
                    CloseButton(actions)
                }
            }
            Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(16.dp), color = Palette.Surface) {
                if (state.devices.isEmpty()) EmptyState(state, actions) else DeviceList(state, actions)
            }
        }
    }
}

@Composable
private fun Header(state: AppState, actions: ConnectActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.LocationOn, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("GPS Tuner", style = MaterialTheme.typography.titleLarge)
            Text("Настройка USB GPS-приёмника u-blox", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
        IconButton(onClick = actions.onRefresh, enabled = state.connecting == null) {
            Icon(Icons.Default.Refresh, "Обновить", tint = Palette.TextDim)
        }
    }
}

@Composable
private fun UsbGpsButton(actions: ConnectActions) {
    TextButton(onClick = actions.onOpenUsbGps, contentPadding = ButtonPad, modifier = Modifier.height(TouchHeight)) {
        Text("Открыть USB GPS")
    }
}

@Composable
private fun CloseButton(actions: ConnectActions) {
    IconButton(onClick = actions.onDismissBanner) {
        Icon(Icons.Default.Close, "Скрыть", tint = Palette.TextDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EmptyState(state: AppState, actions: ConnectActions) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.LocationOn, null, tint = Palette.TextDim, modifier = Modifier.size(26.dp))
        }
        Text("Адаптер не найден", style = MaterialTheme.typography.titleMedium)
        Text(
            "Подключите GPS к USB с передачей данных и остановите USB GPS — адаптер доступен только одному приложению.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.usbGpsInstalled) {
                OutlinedButton(onClick = actions.onOpenUsbGps, contentPadding = ButtonPad, modifier = Modifier.height(TouchHeight)) {
                    Text("Открыть USB GPS")
                }
            }
            TextButton(onClick = actions.onDemo, contentPadding = ButtonPad, modifier = Modifier.height(TouchHeight)) {
                Text("Демо-режим")
            }
        }
    }
}

@Composable
private fun DeviceList(state: AppState, actions: ConnectActions) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.id }) { d ->
                DeviceRow(d, state) { actions.onConnect(d) }
            }
        }
        TextButton(onClick = actions.onDemo, enabled = state.connecting == null, contentPadding = ButtonPad, modifier = Modifier.height(TouchHeight)) {
            Text("Демо-режим без адаптера")
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceItem, state: AppState, onConnect: () -> Unit) {
    val busy = state.connectingId == d.id
    val enabled = state.connecting == null
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.SurfaceHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(enabled = enabled, onClick = onConnect).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.LocationOn, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${d.kind} · ${d.ids}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Text(state.connecting ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            } else {
                Button(onClick = onConnect, enabled = enabled, contentPadding = ButtonPad, modifier = Modifier.height(TouchHeight)) {
                    Text("Подключить")
                }
            }
        }
    }
}
