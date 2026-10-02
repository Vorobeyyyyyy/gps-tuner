package ru.gpstuner.transport

/** Канал байт к приёмнику: USB-адаптер или эмулятор. */
interface GpsTransport {
    val title: String
    val subtitle: String

    /** Индекс порта в ответе UBX-CFG-MSG: 1 — UART1 (адаптер с мостом), 3 — USB. */
    val msgPort: Int

    /** Адаптер с USB-UART мостом: у него есть скорость порта, и она ограничивает поток данных. */
    val isBridge: Boolean get() = msgPort == 1

    /** Текущая скорость на стороне ГУ; null — родной USB, скорость не важна. */
    val baud: Int? get() = null

    /** Сменить скорость на стороне ГУ (только для мостов). Блокирующий вызов. */
    fun setBaud(baud: Int) {}

    /** Запускает чтение; колбэки вызываются из фонового потока. */
    fun start(onData: (ByteArray) -> Unit, onError: (Exception) -> Unit)

    /** Блокирующая запись. */
    fun write(data: ByteArray)

    fun close()
}

class BusyException(cause: Throwable) : Exception(cause.message, cause)
