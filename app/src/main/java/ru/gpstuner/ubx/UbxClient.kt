package ru.gpstuner.ubx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.gpstuner.transport.GpsTransport

enum class Ack { ACK, NAK, TIMEOUT }

/** Запрос-ответ по UBX поверх транспорта. Запросы идут строго по одному. */
class UbxClient(private val transport: GpsTransport) {
    private val _packets = MutableSharedFlow<Packet>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val packets: SharedFlow<Packet> = _packets

    private val parser = StreamParser {
        if (it is Packet.Nmea) nmeaBytesReceived += it.sentence.length + 2
        _packets.tryEmit(it)
    }
    private val lock = Mutex()

    @Volatile
    var lastDataNanos = 0L
        private set

    /** Всего принято байт — для оценки загрузки порта. Пишет только поток чтения. */
    @Volatile
    var bytesReceived = 0L
        private set

    /** Принято байт NMEA — это и получит USB GPS, без наших UBX-опросов. */
    @Volatile
    var nmeaBytesReceived = 0L
        private set

    val errors: Int get() = parser.errors

    /** Вызывается из потока чтения транспорта. */
    fun onData(data: ByteArray) {
        lastDataNanos = System.nanoTime()
        bytesReceived += data.size
        parser.feed(data)
    }

    /**
     * Опрос: отправляет пустой (или с [payload]) запрос и ждёт сообщение того же класса и ID.
     * null — нет ответа или приёмник ответил NAK (сообщение не поддерживается).
     */
    suspend fun poll(
        cls: Int,
        id: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long = 1500,
        retries: Int = 1,
        match: (UbxMessage) -> Boolean = { true },
    ): UbxMessage? {
        repeat(retries + 1) {
            val reply = lock.withLock {
                withTimeoutOrNull(timeoutMs) {
                    _packets
                        .onSubscription { send(Ubx.frame(cls, id, payload)) }
                        .mapNotNull { (it as? Packet.Ubx)?.msg }
                        .first { (it.cls == cls && it.id == id && match(it)) || (it.id == Ubx.ACK_NAK && it.isAck(cls, id)) }
                }
            }
            if (reply != null) return if (reply.cls == Ubx.ACK) null else reply
        }
        return null
    }

    /**
     * Команда CFG: ждёт ACK-ACK или ACK-NAK. При отсутствии ответа повторяет ([attempts] попыток).
     * [afterSend] выполняется сразу после отправки, до ожидания ответа — например, смена скорости:
     * ACK на CFG-PRT приходит уже на новой скорости.
     */
    suspend fun command(
        cls: Int,
        id: Int,
        payload: ByteArray,
        timeoutMs: Long = 1500,
        attempts: Int = 2,
        afterSend: (suspend () -> Unit)? = null,
    ): Ack {
        repeat(attempts) {
            val reply = lock.withLock {
                withTimeoutOrNull(timeoutMs) {
                    _packets
                        .onSubscription {
                            send(Ubx.frame(cls, id, payload))
                            afterSend?.invoke()
                        }
                        .mapNotNull { (it as? Packet.Ubx)?.msg }
                        .first { it.isAck(cls, id) }
                }
            }
            if (reply != null) return if (reply.id == Ubx.ACK_ACK) Ack.ACK else Ack.NAK
        }
        return Ack.TIMEOUT
    }

    private suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) { transport.write(frame) }
}
