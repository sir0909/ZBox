// Adapted from Fluoro-Antimonic-Acid/Shotdeck (GPL-3.0-or-later), Copyright (C) 2026 Shotdeck contributors.
// Adapted and integrated with the Zbox PTP/IP transport.
package com.zbox.ai.ptpip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured, redaction-safe diagnostic log.
 *
 * Passwords, serial numbers and photo bytes never reach this log. Everything is kept
 * in memory and can be exported by the user as text.
 */
class DiagnosticLog(private val limit: Int = 600) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val stamp = java.time.LocalTime.now().withNano(0).toString()
        lines.addLast("$stamp  $line")
        while (lines.size > limit) lines.removeFirst()
        // Also go to logcat so a wired test can be read without touching the screen:
        //   adb logcat -s Shotdeck:V
        runCatching { android.util.Log.i(TAG, line) }
        runCatching { com.zbox.ai.ptp.HexLog.info("PTP/IP",line) }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    private companion object {
        const val TAG = "ZboxPTPIP"
    }
}

/** Lets the transport put a socket on the camera's Wi-Fi network before connecting. */
fun interface SocketBinder {
    fun bind(socket: Socket)
}

/** One PTP operation result. */
internal data class PtpResponse(
    val code: Int,
    val transactionId: Long,
    val params: List<Long> = emptyList(),
) {
    val ok: Boolean get() = code == PtpIp.RC_OK
}

internal data class PtpData(
    val response: PtpResponse,
    val payload: ByteArray,
)

internal data class PtpEvent(
    val code: Int,
    val transactionId: Long,
    val params: List<Long>,
)

/**
 * The PTP/IP byte channel: one command socket, one event socket, both on the same
 * camera network.
 *
 * Session establishment, file enumeration and live-view JPEGs have been verified
 * with the user's Z30. Individual controls and recovery paths need their own checks.
 *
 * Transport guarantees:
 *  - command and event channels must be bound to the camera's network, otherwise a
 *    phone with mobile data routes the second socket out of the wrong interface;
 *  - every operation is validated by response code, never by "no exception";
 *  - the class never retries a non-idempotent operation by itself.
 */
internal class PtpIpTransport(
    private val host: String,
    private val port: Int,
    private val log: DiagnosticLog,
    private val binder: SocketBinder = SocketBinder { },
    private val initiatorGuid: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
    private val initiatorName: String = "Zbox",
    private val connectTimeoutMs: Int = 6_000,
    private val commandTimeoutMs: Int = 60_000,
    private val receiveBufferBytes: Int = 0,
    private val lowCopy: Boolean = true,
    private val staMode: Boolean = false,
) {

    private var commandSocket: Socket? = null
    private var commandInput: InputStream? = null
    private var commandOutput: OutputStream? = null
    private var eventSocket: Socket? = null
    private var eventInput: InputStream? = null
    private var eventOutput: OutputStream? = null

    /** Set once the camera has accepted the init command request. */
    var cameraName: String = ""
        private set

    var connectionNumber: Long = 0
        private set

    val ptpSessionId:Long=NikonConnectionPolicy.sessionId(staMode,SecureRandom().nextInt())
    private val transaction = AtomicInteger(0)
    private val commandLock = Mutex()

    private val _events = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 32)
    val events: Flow<PtpEvent> = _events.asSharedFlow()

    val isOpen: Boolean
        get() = commandSocket?.let { it.isConnected && !it.isClosed } == true &&
            eventSocket?.let { it.isConnected && !it.isClosed } == true

    /**
     * PTP/IP handshake: init command channel, init event channel, then OpenSession.
     * A failure here is reported with the stage name so the report says which of the
     * two channels was the problem.
     */
    suspend fun connect(): PtpResponse = withContext(Dispatchers.IO) {
        close()
        transaction.set(0)
        var stage="CommandTCP"
        log.add("PTP/IP → $host:$port  建立命令通道")
        val command = openSocket()
        commandSocket = command
        try {
            commandInput = BufferedInputStream(command.getInputStream())
            commandOutput = BufferedOutputStream(command.getOutputStream())
            stage="CommandSettle"
            settleBeforeInit(command,"命令")

            // Keep the initiator identity stable across reconnects. Some camera modes
            // use the GUID to recognize the same client after a previous connection.
            stage="InitCommand"
            val init = PtpPacketWriter()
                .bytes(initiatorGuid.copyOf())
                .utf16z(initiatorName.trim())
                .u32(PtpIp.PROTOCOL_VERSION)
                .toByteArray()
            writePacket(commandOutput!!, PtpIp.PACKET_INIT_COMMAND_REQUEST, init)

            val ack = readPacket(commandInput!!)
            if (ack.type == PtpIp.PACKET_INIT_FAIL) {
                val reason = ack.data.takeIf { it.size >= 12 }?.let { u32(it, 8).toInt() }
                val reasonText = when (reason) {
                    PtpIp.INIT_FAIL_REJECTED -> "相机未接受当前 PTP/IP 客户端身份（GUID/名称与机身已登记主机不一致，常见于重装或更换包名后）。可在下方点「客户端身份设置」恢复原配对 GUID/名称（如 Z-Cloud AI），或在相机菜单「连接到计算机 → 配对」重新配对一次"
                    PtpIp.INIT_FAIL_BUSY -> "相机忙或仍在释放上一条连接；等待数秒后重试"
                    PtpIp.INIT_FAIL_UNSPECIFIED -> "相机未说明拒绝原因"
                    null -> "InitFail 包未包含完整原因码"
                    else -> "未知 InitFail 原因"
                }
                log.add("相机返回 InitFail：reason=${reason ?: "missing"} ($reasonText)")
                throw PtpIpException(
                    stage = "InitCommand",
                    code = reason,
                    message = "相机拒绝 PTP/IP 初始化：$reasonText${reason?.let { " [reason=$it]" } ?: ""}",
                )
            }
            if (ack.type != PtpIp.PACKET_INIT_COMMAND_ACK) {
                throw PtpIpException(
                    stage = "握手",
                    message = "命令通道返回了非 Init Command Ack 的包（type=${ack.type}）",
                )
            }
            if (ack.data.size < 28) {
                throw PtpIpException(
                    stage = "握手",
                    message = "Init Command Ack 长度异常：${ack.data.size} 字节",
                )
            }
            connectionNumber = u32(ack.data, 8)
            cameraName = readPtpIpString(ack.data, 28)
            log.add("命令通道已确认，连接号=$connectionNumber，相机自称=\"$cameraName\"")

            log.add("PTP/IP → 建立事件通道")
            stage="EventTCP"
            val event = openSocket()
            eventSocket = event
            eventInput = BufferedInputStream(event.getInputStream())
            eventOutput = BufferedOutputStream(event.getOutputStream())
            stage="EventSettle"
            settleBeforeInit(event,"事件")
            event.soTimeout=8_000
            val eventInit = PtpPacketWriter().u32(connectionNumber).toByteArray()
            stage="InitEvent"
            writePacket(eventOutput!!, PtpIp.PACKET_INIT_EVENT_REQUEST, eventInit)
            val eventAck = readPacket(eventInput!!)
            if (eventAck.type != PtpIp.PACKET_INIT_EVENT_ACK) {
                throw PtpIpException(
                    stage = "握手",
                    message = "相机未确认事件通道（收到 type=${eventAck.type}）",
                )
            }
            log.add("事件通道已确认")
            if(staMode) {
                // Do not consume a possibly partial packet with an optional timed read here.
                // The existing event reader processes Pong and camera events after session open.
                writePacket(eventOutput!!,PtpIp.PACKET_PROBE_REQUEST,ByteArray(0))
                log.add("STA 事件通道主动保活已发送")
            }

            commandSocket = command
            eventSocket = event

            // Preserve the negotiated STA/non-STA session policy. An existing session
            // response is not proof that we own that session; never take it over.
            stage="OpenSession"
            val response = execute(PtpIp.OP_OPEN_SESSION, listOf(ptpSessionId),timeoutMs=12_000)
            if (!response.ok) {
                throw PtpIpException(
                    stage = "OpenSession",
                    code = response.code,
                    opName = PtpIp.operationName(PtpIp.OP_OPEN_SESSION),
                    message = if(response.code==PtpIp.RC_SESSION_ALREADY_OPEN)
                        "相机报告会话仍打开(0x201E)；本应用不能确认其归属，不会抢占、关闭旧会话或重复拍摄。请在机身结束旧连接后重新扫描"
                    else "相机拒绝建立会话：${PtpIp.responseName(response.code)}；请核对连接模式和机身提示，不会自动抢占旧会话",
                )
            }
            log.add("会话已建立")
            response
        } catch (error: Exception) {
            log.add("连接失败 stage=$stage：${error.javaClass.simpleName}: ${error.message}")
            close()
            if(error is kotlinx.coroutines.CancellationException)throw error
            if(error is PtpIpException)throw error
            throw PtpIpException(stage=stage,message="连接阶段[$stage]失败：${error.javaClass.simpleName}；请核对计算机连接向导、热点及配对身份")
        }
    }

    /** Independently implemented protocol timing precaution; no identity spoofing or retry. */
    private suspend fun settleBeforeInit(socket:Socket,channel:String) {
        log.add("${channel}TCP已建立，等待${NikonConnectionPolicy.CONNECT_SETTLE_MS}ms后发送初始化")
        delay(NikonConnectionPolicy.CONNECT_SETTLE_MS)
        check(!socket.isClosed){"等待初始化时连接已关闭"}
    }

    private fun openSocket(): Socket {
        val socket = Socket()
        try {
            binder.bind(socket)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            if (receiveBufferBytes > 0) runCatching { socket.receiveBufferSize = receiveBufferBytes }
            socket.soTimeout = commandTimeoutMs
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            log.add("TCP tune requestedRx=$receiveBufferBytes actualRx=${socket.receiveBufferSize} lowCopy=$lowCopy")
            return socket
        } catch (error: Exception) {
            log.add("TCP 连接 $host:$port 失败：${error.javaClass.simpleName}: ${error.message}")
            runCatching { socket.close() }
            throw error
        }
    }

    fun close() {
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandSocket = null
        eventSocket = null
        commandInput = null
        commandOutput = null
        eventInput = null
        eventOutput = null
    }

    // ---------------------------------------------------------------- operations

    /** Operation with no data phase. */
    suspend fun execute(operation: Int, params: List<Long> = emptyList(), timeoutMs: Int = commandTimeoutMs): PtpResponse =
        runOperation(operation, params, dataOut = null, sink = null, collectData = false, timeoutMs = timeoutMs).response

    /** Operation with a data-in phase, e.g. GetDeviceInfo. */
    suspend fun executeReadAll(
        operation: Int,
        params: List<Long> = emptyList(),
        timeoutMs: Int = commandTimeoutMs,
    ): PtpData = runOperation(
        operation, params, dataOut = null, sink = null, collectData = true, timeoutMs = timeoutMs,
    )

    /** Operation with a data-out phase, e.g. SetDevicePropValue. */
    suspend fun executeWrite(
        operation: Int,
        params: List<Long>,
        data: ByteArray,
        timeoutMs: Int = commandTimeoutMs,
    ): PtpResponse = runOperation(
        operation, params, dataOut = data, sink = null, collectData = false, timeoutMs = timeoutMs,
    ).response

    /**
     * Operation with a data-in phase streamed straight to [sink]. Used for GetObject
     * so a 25 MB NEF never has to sit in memory.
     */
    suspend fun executeStreamTo(
        operation: Int,
        params: List<Long>,
        sink: OutputStream,
        timeoutMs: Int = 60_000,
    ): PtpData = runOperation(
        operation = operation,
        params = params,
        dataOut = null,
        sink = sink,
        collectData = false,
        timeoutMs = timeoutMs,
    )

    private suspend fun runOperation(
        operation: Int,
        params: List<Long>,
        dataOut: ByteArray?,
        sink: OutputStream?,
        collectData: Boolean,
        timeoutMs: Int = commandTimeoutMs,
    ): PtpData = withContext(Dispatchers.IO) {
        commandLock.withLock {
            val input = commandInput
            val output = commandOutput
            val socket = commandSocket
            if (input == null || output == null || socket == null) {
                throw PtpIpException(stage = "命令", message = "会话尚未建立")
            }
            try {
            socket.soTimeout = timeoutMs
            val txId = transaction.incrementAndGet().toLong()
            val dataPhase = when {
                dataOut != null -> PtpIp.DATA_PHASE_OUT
                sink != null || collectData -> PtpIp.DATA_PHASE_IN
                else -> PtpIp.DATA_PHASE_NONE
            }

            val writer = PtpPacketWriter().u32(dataPhase.toLong()).u16(operation).u32(txId)
            params.forEach { writer.u32(it) }
            writePacket(output, PtpIp.PACKET_OPERATION_REQUEST, writer.toByteArray())
            log.add("→ ${PtpIp.operationName(operation)} tx=$txId params=${params.joinToString(",")}")

            if (dataOut != null) {
                writePacket(output, PtpIp.PACKET_START_DATA, PtpPacketWriter().u32(txId).u64(dataOut.size.toLong()).toByteArray())
                writePacket(output, PtpIp.PACKET_END_DATA, PtpPacketWriter().u32(txId).bytes(dataOut).toByteArray())
            }

            var collected = if (collectData) ByteArrayOutputStream() else null
            val perfStart = System.nanoTime()
            var packetReadNs = 0L
            var payloadCopyNs = 0L
            var packetCount = 0
            var dataBytes = 0L
            var response: PtpResponse? = null

            while (response == null) {
                val readStart = System.nanoTime()
                val packet = readPacket(input)
                packetReadNs += System.nanoTime() - readStart
                packetCount++
                when (packet.type) {
                    PtpIp.PACKET_OPERATION_RESPONSE -> {
                        if (packet.data.size < 14) {
                            throw PtpIpException(
                                stage = "命令",
                                opName = PtpIp.operationName(operation),
                                message = "响应包长度异常：${packet.data.size}",
                            )
                        }
                        val code = u16(packet.data, 8)
                        val responseTx = u32(packet.data, 10)
                        val responseParams = buildList {
                            var offset = 14
                            while (offset + 4 <= packet.data.size) {
                                add(u32(packet.data, offset))
                                offset += 4
                            }
                        }
                        if (responseTx != txId) {
                            throw PtpIpException(stage = "命令", message = "响应事务号不匹配：期望 $txId，收到 $responseTx")
                        }
                        response = PtpResponse(code, responseTx, responseParams)
                    }

                    PtpIp.PACKET_START_DATA, PtpIp.PACKET_DATA, PtpIp.PACKET_END_DATA -> {
                        if (packet.data.size < if (packet.type == PtpIp.PACKET_START_DATA) 20 else 12) {
                            throw PtpIpException(stage = "数据", message = "数据包长度异常：${packet.data.size}")
                        }
                        val dataTx = u32(packet.data, 8)
                        if (dataTx != txId) {
                            throw PtpIpException(stage = "数据", message = "数据包事务号不匹配：期望 $txId，收到 $dataTx")
                        }
                        if (packet.type == PtpIp.PACKET_START_DATA) {
                            val declared = u32(packet.data, 12) or (u32(packet.data, 16) shl 32)
                            log.add("← StartData 声明长度=$declared")
                            if (lowCopy && collected != null && collected.size() == 0 && declared in 1..(16L * 1024 * 1024)) {
                                collected = ByteArrayOutputStream(declared.toInt())
                            }
                            continue
                        }
                        val offset = PtpIp.HEADER_BYTES + 4
                        val copyStart = System.nanoTime()
                        val length = packet.data.size - offset
                        if (lowCopy) {
                            collected?.write(packet.data, offset, length)
                            sink?.write(packet.data, offset, length)
                        } else {
                            val payload = if (length > 0) packet.data.copyOfRange(offset, packet.data.size) else ByteArray(0)
                            collected?.write(payload)
                            sink?.write(payload)
                        }
                        dataBytes += length
                        payloadCopyNs += System.nanoTime() - copyStart
                    }

                    PtpIp.PACKET_PROBE_REQUEST -> {
                        // The camera may probe the event channel; answering keeps it alive.
                        writePacket(
                            output,
                            PtpIp.PACKET_PROBE_RESPONSE,
                            ByteArray(0),
                        )
                    }

                    else -> log.add("← 未预期的包类型 ${packet.type}（${packet.data.size} 字节）")
                }
            }

            val finalCopyStart = System.nanoTime()
            val body = collected?.toByteArray() ?: ByteArray(0)
            val finalCopyNs = System.nanoTime() - finalCopyStart
            if (dataBytes >= 512 * 1024) log.add("PERF op=0x${operation.toString(16)} bytes=$dataBytes packets=$packetCount totalMs=${(System.nanoTime()-perfStart)/1000000} readMs=${packetReadNs/1000000} payloadCopyMs=${payloadCopyNs/1000000} finalCopyMs=${finalCopyNs/1000000}")
            log.add("← ${PtpIp.operationName(operation)} ${PtpIp.responseName(response.code)}（${body.size} 字节数据）")
            sink?.flush()
            PtpData(response, body)
            } catch (error: Exception) {
                // A timed-out/partial transaction cannot be reused: a late response
                // would otherwise be mistaken for the next command's result.
                close()
                throw error
            }
        }
    }

    /**
     * Reads one event from the event channel. Returns null on timeout, which is a
     * normal idle result and must not be treated as a disconnection.
     */
    suspend fun pollEvent(timeoutMs: Int = 1_000): PtpEvent? = withContext(Dispatchers.IO) {
        val input = eventInput
        val output = eventOutput
        val socket = eventSocket
        if (input == null || output == null || socket == null) return@withContext null
        try {
            socket.soTimeout = timeoutMs
            val packet = readPacket(input)
            when (packet.type) {
                PtpIp.PACKET_PROBE_RESPONSE -> null
                PtpIp.PACKET_EVENT -> {
                    if (packet.data.size < 14) return@withContext null
                    val code = u16(packet.data, 8)
                    val tx = u32(packet.data, 10)
                    val params = buildList {
                        var offset = 14
                        while (offset + 4 <= packet.data.size) {
                            add(u32(packet.data, offset))
                            offset += 4
                        }
                    }
                    val event = PtpEvent(code, tx, params)
                    _events.tryEmit(event)
                    event
                }

                PtpIp.PACKET_PROBE_REQUEST -> {
                    log.add("事件通道保活探测 → 已响应 ProbeResponse")
                    writePacket(output, PtpIp.PACKET_PROBE_RESPONSE, ByteArray(0))
                    null
                }

                else -> {
                    log.add("事件通道收到非事件包 type=${packet.type}")
                    null
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    // ------------------------------------------------------------------ framing

    private data class Packet(val type: Int, val data: ByteArray)

    private fun writePacket(output: OutputStream, type: Int, payload: ByteArray) {
        val length = payload.size + PtpIp.HEADER_BYTES
        val header = ByteArray(PtpIp.HEADER_BYTES)
        putU32(header, 0, length.toLong())
        putU32(header, 4, type.toLong())
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: InputStream): Packet {
        val header = readExactly(input, PtpIp.HEADER_BYTES)
        val length = NikonConnectionPolicy.packetLength(u32(header, 0))
        val type = u32(header, 4).toInt()
        if (length < PtpIp.HEADER_BYTES) {
            throw PtpIpException(stage = "读取", message = "包长度非法：$length")
        }
        if (lowCopy) {
            val frame = ByteArray(length)
            System.arraycopy(header, 0, frame, 0, header.size)
            try { readIntoExactly(input, frame, header.size, length - header.size) }
            catch (error: java.net.SocketTimeoutException) { throw IOException("包体读取超时，通道不能继续复用", error) }
            return Packet(type, frame)
        }
        val body = try { readExactly(input, length - PtpIp.HEADER_BYTES) }
        catch (error: java.net.SocketTimeoutException) { throw IOException("包体读取超时，通道不能继续复用", error) }
        return Packet(type, header + body)
    }

    private fun readIntoExactly(input: InputStream, target: ByteArray, offset: Int, count: Int) {
        var read = 0
        while (read < count) {
            val n = try { input.read(target, offset + read, count - read) }
            catch (error: java.net.SocketTimeoutException) {
                if (read > 0) throw IOException("包读取不完整：$read/$count 字节", error)
                throw error
            }
            if (n < 0) throw EOFException("连接在对端关闭前只读到 $read/$count 字节")
            read += n
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        if (count < 0) throw PtpIpException(stage = "读取", message = "请求读取负数字节：$count")
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val result = try { input.read(buffer, read, count - read) }
            catch (error: java.net.SocketTimeoutException) {
                if (read > 0) throw IOException("包读取不完整：$read/$count 字节", error)
                throw error
            }
            if (result < 0) {
                throw EOFException("连接在对端关闭前只读到 $read/$count 字节")
            }
            read += result
        }
        return buffer
    }
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

private fun putU32(target: ByteArray, offset: Int, value: Long) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    target[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

/** Little-endian writer for the PTP/IP handshake and request payloads. */
internal class PtpPacketWriter {
    private val buffer = ByteArrayOutputStream(64)

    fun u16(value: Int): PtpPacketWriter = apply {
        buffer.write(value and 0xFF)
        buffer.write((value shr 8) and 0xFF)
    }

    fun u32(value: Long): PtpPacketWriter = apply {
        repeat(4) { shift -> buffer.write(((value shr (8 * shift)) and 0xFF).toInt()) }
    }

    fun u64(value: Long): PtpPacketWriter = apply {
        repeat(8) { shift -> buffer.write(((value shr (8 * shift)) and 0xFF).toInt()) }
    }

    fun bytes(value: ByteArray): PtpPacketWriter = apply { buffer.write(value) }

    /** PTP/IP friendly names are UTF-16LE with a terminating NUL character. */
    fun utf16z(value: String): PtpPacketWriter = apply {
        value.forEach { char ->
            buffer.write(char.code and 0xFF)
            buffer.write((char.code shr 8) and 0xFF)
        }
        buffer.write(0)
        buffer.write(0)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

/**
 * Reads a NUL-terminated UTF-16LE string that starts at [offset] inside a packet.
 * Used for the camera's self-reported name in the init ack.
 */
internal fun readPtpIpString(bytes: ByteArray, offset: Int): String {
    val builder = StringBuilder()
    var index = offset
    while (index + 1 < bytes.size) {
        val code = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
        if (code == 0) break
        builder.append(code.toChar())
        index += 2
    }
    return builder.toString()
}

/** PTP string format: one length byte (characters, including the NUL), then UTF-16LE. */
internal fun readPtpString(bytes: ByteArray, offset: Int): Pair<String, Int> {
    if (offset >= bytes.size) return "" to offset
    val length = bytes[offset].toInt() and 0xFF
    if (length == 0) return "" to (offset + 1)
    val builder = StringBuilder()
    var index = offset + 1
    var produced = 0
    while (produced < length - 1 && index + 1 < bytes.size) {
        val code = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
        if (code == 0) break
        builder.append(code.toChar())
        index += 2
        produced++
    }
    return builder.toString() to (offset + 1 + length * 2)
}

internal fun IOException.isIdleTimeout(): Boolean = this is java.net.SocketTimeoutException
