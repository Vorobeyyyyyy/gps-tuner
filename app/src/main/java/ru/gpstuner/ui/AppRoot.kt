package ru.gpstuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.gpstuner.AppViewModel
import ru.gpstuner.ReceiverSession
import ru.gpstuner.ui.theme.Palette

@Composable
fun AppRoot(vm: AppViewModel) {
    val app by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.messages.collect { text ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = {
            // на экране приёмника снизу панель кнопок — поднимаем сообщения над ней
            SnackbarHost(snackbar, Modifier.padding(bottom = if (session != null) 60.dp else 0.dp)) {
                Snackbar(containerColor = Palette.SurfaceHigher, contentColor = Palette.Text, modifier = Modifier.padding(12.dp)) {
                    Text(it.visuals.message)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val s = session
            if (s == null) {
                ConnectScreen(
                    app,
                    ConnectActions(
                        onOpenUsbGps = vm::openUsbGps,
                        onRefresh = vm::refresh,
                        onConnect = vm::connect,
                        onDemo = vm::connectDemo,
                        onDismissBanner = vm::dismissBanner,
                    ),
                )
            } else {
                SessionScreen(s, vm::disconnect)
            }
        }
    }
}

@Composable
private fun SessionScreen(session: ReceiverSession, onDisconnect: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDisconnect)
    ReceiverScreen(
        state,
        ReceiverActions(
            onDisconnect = onDisconnect,
            onRate = session::setRate,
            onDynModel = session::setDynModel,
            onGnss = session::toggleGnss,
            onNmea = session::toggleNmea,
            onBaud = session::setBaud,
            onPreset = session::applyPreset,
            onUnloadPort = session::unloadPort,
            onRelievePort = session::relievePort,
            onReadAnyway = session::readAnyway,
            onDiscard = session::discardDraft,
            onApply = session::apply,
            onSave = session::save,
            onReset = session::resetToFactory,
        ),
    )
}
