package ru.gpstuner

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.gpstuner.transport.BusyException
import ru.gpstuner.transport.DemoTransport
import ru.gpstuner.transport.GpsTransport
import ru.gpstuner.transport.UsbTransport
import ru.gpstuner.transport.registerReceiverCompat

/** Пакет UsbGps4Droid — приложение, которое обычно держит адаптер. */
const val USB_GPS_PACKAGE = "org.broeuschmeul.android.gps.usb.provider"

data class DeviceItem(val id: Int, val name: String, val kind: String, val ids: String, val driver: UsbSerialDriver)

data class AppState(
    val devices: List<DeviceItem> = emptyList(),
    val usbGpsInstalled: Boolean = false,
    /** Какое устройство подключаем и что сейчас происходит. */
    val connectingId: Int? = null,
    val connecting: String? = null,
    val error: String? = null,
    /** Ошибка «адаптер занят» — рядом показываем кнопку «Открыть USB GPS». */
    val errorBusy: Boolean = false,
    /** Показать напоминание запустить USB GPS после отключения. */
    val remindRestart: Boolean = false,
    /** Скорость порта менялась — в USB GPS надо выставить эту же. */
    val remindBaud: Int? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(UsbManager::class.java)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _session = MutableStateFlow<ReceiverSession?>(null)
    val session: StateFlow<ReceiverSession?> = _session.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var transport: GpsTransport? = null

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val current = transport as? UsbTransport ?: return
            if (device?.deviceId == current.device.deviceId) {
                disconnect()
                message("Адаптер отключён от USB")
            }
        }
    }

    init {
        registerReceiverCompat(
            app,
            usbEvents,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
        )
        refresh()
    }

    fun refresh() {
        val devices = UsbTransport.findDrivers(usb).map {
            val d = it.device
            DeviceItem(d.deviceId, UsbTransport.nameOf(d), UsbTransport.kindOf(d), UsbTransport.idsOf(d), it)
        }
        val installed = getApplication<Application>().packageManager.getLaunchIntentForPackage(USB_GPS_PACKAGE) != null
        _state.update { it.copy(devices = devices, usbGpsInstalled = installed) }
    }

    fun dismissBanner() = _state.update { it.copy(remindRestart = false, remindBaud = null, error = null, errorBusy = false) }

    fun openUsbGps() {
        val ctx = getApplication<Application>()
        val intent = ctx.packageManager.getLaunchIntentForPackage(USB_GPS_PACKAGE)
        if (intent == null) {
            message("USB GPS не установлен")
            return
        }
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun connect(item: DeviceItem) {
        if (_state.value.connecting != null || _session.value != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(connectingId = item.id, connecting = "Запрашиваю доступ…", error = null, errorBusy = false, remindRestart = false)
            }
            try {
                val ctx = getApplication<Application>()
                if (!UsbTransport.requestPermission(ctx, usb, item.driver.device)) {
                    _state.update { it.copy(error = "Доступ к адаптеру не разрешён — нажмите «Подключить» и разрешите") }
                    return@launch
                }
                _state.update { it.copy(connecting = "Подключаюсь…") }
                val t = withContext(Dispatchers.IO) {
                    UsbTransport.open(usb, item.driver) { text -> _state.update { it.copy(connecting = text) } }
                }
                startSession(t)
            } catch (e: BusyException) {
                _state.update { it.copy(error = "Адаптер занят USB GPS — остановите его и подключитесь снова", errorBusy = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Не удалось подключиться: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(connecting = null, connectingId = null) }
            }
        }
    }

    fun connectDemo() {
        if (_session.value != null) return
        _state.update { it.copy(error = null, errorBusy = false, remindRestart = false) }
        startSession(DemoTransport())
    }

    /** Демо адаптера с мостом на 9600 бод — для проверки строки «Порт» (adb: --ez demo_bridge true). */
    fun connectDemoBridge() {
        if (_session.value != null) return
        startSession(DemoTransport(bridge = true))
    }

    private fun startSession(t: GpsTransport) {
        transport = t
        val s = ReceiverSession(
            t,
            viewModelScope,
            onMessage = ::message,
            onLost = { text ->
                disconnect()
                message(text)
            },
        )
        _session.value = s
        s.start()
    }

    fun disconnect() {
        val s = _session.value ?: return
        val wasUsb = transport is UsbTransport
        val baud = s.state.value.baudChanged
        s.close()
        _session.value = null
        transport = null
        _state.update { it.copy(remindRestart = wasUsb, remindBaud = baud.takeIf { wasUsb }) }
        refresh()
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    override fun onCleared() {
        _session.value?.close()
        runCatching { getApplication<Application>().unregisterReceiver(usbEvents) }
    }
}
