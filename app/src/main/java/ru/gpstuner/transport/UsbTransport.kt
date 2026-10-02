package ru.gpstuner.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.gpstuner.ubx.StreamParser
import java.io.IOException
import kotlin.coroutines.resume

class UsbTransport private constructor(
    val device: UsbDevice,
    private val port: UsbSerialPort,
    override val title: String,
    override val subtitle: String,
    override val msgPort: Int,
    initialBaud: Int?,
) : GpsTransport {
    private var io: SerialInputOutputManager? = null

    @Volatile
    override var baud: Int? = initialBaud
        private set

    override fun setBaud(baud: Int) {
        if (this.baud == null) return
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        this.baud = baud
    }

    override fun start(onData: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = onData(data)
            override fun onRunError(e: Exception) = onError(e)
        }).also { it.start() }
    }

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun close() {
        io?.stop()
        io = null
        runCatching { port.close() }
    }

    companion object {
        const val UBLOX_VID = 0x1546
        private const val WRITE_TIMEOUT_MS = 1000
        private val BAUDS = listOf(9600, 38400, 115200, 57600, 19200, 4800)

        /** u-blox 5…9 с родным USB (CDC-ACM) — на случай, если стандартный пробер их не узнает. */
        private val ubloxProber by lazy {
            val table = ProbeTable()
            for (pid in 0x01a4..0x01a9) table.addProduct(UBLOX_VID, pid, CdcAcmSerialDriver::class.java)
            UsbSerialProber(table)
        }

        fun findDrivers(usb: UsbManager): List<UsbSerialDriver> {
            val found = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
            val extra = ubloxProber.findAllDrivers(usb).filter { e -> found.none { it.device.deviceId == e.device.deviceId } }
            return found + extra
        }

        fun kindOf(device: UsbDevice): String = when (device.vendorId) {
            UBLOX_VID -> "u-blox, родной USB"
            0x1a86 -> "мост CH340"
            0x10c4 -> "мост CP210x"
            0x067b -> "мост PL2303"
            0x0403 -> "мост FTDI"
            else -> "USB-serial"
        }

        fun nameOf(device: UsbDevice): String =
            device.productName?.takeIf { it.isNotBlank() } ?: device.manufacturerName ?: "USB-устройство"

        fun idsOf(device: UsbDevice) = "%04X:%04X".format(device.vendorId, device.productId)

        /**
         * Открывает порт. Блокирующий вызов: для адаптеров с мостом перебирает скорости.
         * [BusyException] — интерфейс держит другое приложение (обычно USB GPS).
         */
        fun open(usb: UsbManager, driver: UsbSerialDriver, onProgress: (String) -> Unit): UsbTransport {
            val device = driver.device
            val connection = usb.openDevice(device) ?: throw IOException("Нет доступа к устройству")
            val port = driver.ports[0]
            try {
                port.open(connection)
            } catch (e: IOException) {
                runCatching { connection.close() }
                throw BusyException(e)
            }
            try {
                val native = device.vendorId == UBLOX_VID
                val baud = if (native) {
                    port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    null
                } else {
                    detectBaud(port, onProgress)
                }
                runCatching { port.dtr = true }
                runCatching { port.rts = true }
                val subtitle = listOfNotNull(kindOf(device), idsOf(device)).joinToString(" · ")
                return UsbTransport(device, port, nameOf(device), subtitle, if (native) 3 else 1, baud)
            } catch (e: Exception) {
                runCatching { port.close() }
                throw e
            }
        }

        private fun detectBaud(port: UsbSerialPort, onProgress: (String) -> Unit): Int {
            val buf = ByteArray(4096)
            for (baud in BAUDS) {
                onProgress("Определяю скорость порта: $baud…")
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                runCatching { port.purgeHwBuffers(true, true) }
                var good = 0
                val parser = StreamParser { good++ }
                val until = System.currentTimeMillis() + 2200
                while (System.currentTimeMillis() < until && good < 2) {
                    val n = port.read(buf, 200)
                    if (n > 0) parser.feed(buf, n)
                }
                if (good >= 2) return baud
            }
            throw IOException("Приёмник молчит ни на одной скорости")
        }

        /** Стандартный запрос Android на доступ к USB-устройству. */
        suspend fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
            if (usb.hasPermission(device)) return true
            val action = context.packageName + ".USB_PERMISSION"
            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                registerReceiverCompat(context, receiver, IntentFilter(action))
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
                usb.requestPermission(device, pi)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            }
        }
    }
}

fun registerReceiverCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }
}
