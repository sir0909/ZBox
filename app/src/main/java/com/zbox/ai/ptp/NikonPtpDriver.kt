package com.zbox.ai.ptp

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.ptpip.cameraLinkFrequencyMhz
import com.zbox.ai.ptpip.AndroidCameraNetworkBinder
import com.zbox.ai.ptpip.DiagnosticLog
import com.zbox.ai.ptpip.HotspotSocketBinder
import com.zbox.ai.ptpip.PtpIp
import com.zbox.ai.ptpip.PtpIpException
import com.zbox.ai.ptpip.NikonConnectionPolicy
import com.zbox.ai.ptpip.PtpIpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Nikon Z30 (EXPEED 6) PTP / MTP 驱动。
 *
 * ── 相比 v1.0.0 的关键修正（对照 ZENCHE `PtpCamera.java` 与 libgphoto2）──────
 * 1. **PTP class reset (bRequest=0x66)**：打开接口后必须先发这个 control transfer，
 *    否则相机侧 PTP 状态机停在上一轮（用户上一次用其他 App / 电脑连过）的残留态，
 *    OpenSession 会被静默吞掉或返回 DeviceBusy。这是「装了但连不上」的头号原因。
 * 2. **clearEndpointHalt**：两端点各清一次 halt，防止上一次异常退出留下的 stall。
 * 3. **OpenSession 事务号强制 0**（ISO 15740 规定），之后从 1 开始自增。
 * 4. **三种握手重试 + 传输恢复**：失败后 reset → 断开 → 退避 sleep → 重开，最多 3 轮。
 * 5. **异步 UsbRequest 优先、失败后同步粘性降级**：部分 Android 设备/集线器上
 *    异步 bulk 会挂住，降级一次后整个会话不再探异步。
 * 6. **64 KB 分块**（API 28+），老的 16 KB 在相机回大包时会被切成很多小 transfer。
 * 7. **优化校准操作码纠错**：0x90CC 读 / 0x90CD 写 / 0x90CE 删，均为 (slot, 0) 两参。
 * 8. **不再依赖 Interrupt-In**：ZENCHE 全程不读事件端点，读了反而会和 bulk 抢时序。
 * 9. **读回载荷归一化**：机身可能回 84 或 85 字节（带 1 字节 item 前缀），统一裁成 84。
 *
 * 线程模型：所有 USB 事务串行化在 [ioMutex] 内（PTP 通道本身不支持并发），
 * 挂起函数跑在 Dispatchers.IO。
 */
class NikonPtpDriver(private val appContext: Context) {

    // ---------------- 连接状态 ----------------

    enum class State { DISCONNECTED, PERMISSION_PENDING, CONNECTED, SESSION_OPEN, ERROR }

    data class DeviceInfo(
        val vendorId: Int = 0,
        val productId: Int = 0,
        val manufacturer: String = "",
        val model: String = "",
        val serial: String = "",
        val firmwareVersion:String="",
        val ptpInterfaceIndex: Int = -1,
        val bulkInAddr: Int = -1,
        val bulkOutAddr: Int = -1,
        val interruptInAddr: Int = -1,
        val sessionId: Long = 0,
        /** 握手重试到第几轮才成功（1 = 一次通过） */
        val connectAttempts: Int = 0,
        /** 是否已降级为同步 bulk 传输 */
        val syncTransport: Boolean = false,
        /** 0x90C8 DeviceReady 是否成功 */
        val deviceReady: Boolean = false,
        /** DeviceInfo 广告了 Nikon PTP/IP 的 PIN 配对操作 */
        val supportsNikonPinPairing: Boolean = false,
        val supportsNikonHostRegistration:Boolean=false,
        val supportedOperations:Set<Int> = emptySet(),
    )

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _info = MutableStateFlow(DeviceInfo())
    val info: StateFlow<DeviceInfo> = _info

    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    /** Identity, not a reusable PTP session number. Used to reject stale burst cleanup. */
    val burstConnectionToken: Any? get() = ptpIpTransport ?: connection
    @Volatile private var ptpIpTransport: PtpIpTransport? = null
    /** Enabled only after GetObjectInfo returns Nikon's STA-mode 0x200F lock. */
    @Volatile private var staDirectAlbumMode: Boolean = false
    private val staObjectSizes = ConcurrentHashMap<Long, Long>()
    private val staFileNumbers = ConcurrentHashMap<Long, Int>()
    private var eventJob: Job? = null
    private var ptpIpFrequencyMhz: Int = 0
    val hasCompleteStaAlbumIndex: Boolean get() = staDirectAlbumMode

    /** The PTP/IP event socket is independent of the serialized command socket. */
    private fun startEventReader(channel: PtpIpTransport) {
        eventJob?.cancel()
        eventJob = CoroutineScope(Dispatchers.IO).launch {
            HexLog.info("PTP/IP 事件通道", "持续读取已启动，响应相机保活探测")
            while (isActive && ptpIpTransport === channel && channel.isOpen) {
                try {
                    channel.pollEvent(1_000)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isActive && ptpIpTransport === channel && channel.isOpen) {
                        HexLog.error("PTP/IP 事件通道中断", "${error.javaClass.simpleName}: ${error.message}")
                        channel.close()
                        _state.value = State.DISCONNECTED
                    }
                    break
                }
            }
        }
    }
    private val ptpIpLog = DiagnosticLog()
    private var pairingTransport:PtpIpTransport?=null
    private var pairingPreparedAt:Long=0L
    private var ptpInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var epInterrupt: UsbEndpoint? = null

    private var transactionId: Long = 0
    private var sessionId: Long = 0
    private var connectAttempts: Int = 0
    private var deviceReadyOk: Boolean = false

    private val ioMutex = Mutex()

    /**
     * 单参数兼容模式。
     * false（默认）= 0x90CC/0x90CD 传 (slot, 0) 两个参数（digiCamControl 实证的写法）
     * true         = 只传 (slot) 一个参数（个别老固件）
     */
    @Volatile
    var singleParamMode: Boolean = false

    /** 一旦异步传输失败过一次就粘住同步模式，整个会话不再探异步。 */
    @Volatile
    private var syncTransport: Boolean = false

    val opGetPicCtrl: Int get() = Ptp.OP_NIKON_GET_PICCTRL
    val opSetPicCtrl: Int get() = Ptp.OP_NIKON_SET_PICCTRL
    val opDelCustomPicCtrl: Int get() = Ptp.OP_NIKON_DEL_CUSTOM_PICCTRL

    /** 旧名字保留，避免外部调用点全炸；语义见 [singleParamMode]。 */
    var useAltOpcodeProfile: Boolean
        get() = singleParamMode
        set(value) {
            singleParamMode = value
        }

    val isOpen: Boolean get() = (connection != null || ptpIpTransport?.isOpen == true) && _state.value == State.SESSION_OPEN

    /** True only for a live PTP/IP session (USB unplug detection remains event-driven). */
    val isPtpIpSession: Boolean
        get() = ptpIpTransport?.isOpen == true && _state.value == State.SESSION_OPEN

    /**
     * Lightweight health probe for a quiet PTP/IP session. Any PTP response is evidence that
     * the peer is still reachable; transport exceptions close the stale socket pair in transact().
     */
    suspend fun pingPtpIpSession(timeoutMs: Int = 4_000) {
        check(isPtpIpSession) { "PTP/IP 会话未建立" }
        transact(Ptp.OP_GET_STORAGE_IDS, IntArray(0), null, expectData = true, timeoutMs = timeoutMs)
    }

    data class PtpIpClientIdentity(val name: String, val guidHex: String)

    /** Persistent PTP/IP identity. Nikon computer profiles may bind authorization to GUID + client name. */
    fun ptpIpClientIdentity(): PtpIpClientIdentity {
        val prefs = appContext.getSharedPreferences("ptpip_client", Context.MODE_PRIVATE)
        val storedGuid = prefs.getString("initiator_guid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("initiator_guid", it).apply()
        }
        val uuid = UUID.fromString(storedGuid)
        val generated = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val custom = prefs.getString("initiator_guid_hex", null)
        val bytes = custom?.let(::parseGuidHex) ?: generated
        val name = prefs.getString("initiator_name", "Zbox")?.trim().orEmpty().ifBlank { "Zbox" }
        return PtpIpClientIdentity(name, bytes.joinToString("") { "%02X".format(it) })
    }

    /** Empty GUID restores this install's stable generated identity; otherwise requires 32 hex digits. */
    fun savePtpIpClientIdentity(name: String, guidHex: String) {
        require(name.trim().isNotEmpty()) { "客户端名称不能为空" }
        require(name.trim().length <= 32) { "客户端名称不能超过 32 个字符" }
        val normalized = guidHex.filterNot(Char::isWhitespace).replace("-", "").uppercase()
        if (normalized.isNotEmpty()) parseGuidHex(normalized)
        val prefs = appContext.getSharedPreferences("ptpip_client", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("initiator_name", name.trim())
            .apply {
                if (normalized.isEmpty()) remove("initiator_guid_hex") else putString("initiator_guid_hex", normalized)
            }
            .commit()
    }

    private fun alternateBrandClientName(currentName: String): String? = when (currentName.trim()) {
        "Zbox" -> "Z-Cloud AI"
        "Z-Cloud AI" -> "Zbox"
        else -> null
    }

    private fun parseGuidHex(text: String): ByteArray {
        val normalized = text.filterNot(Char::isWhitespace).replace("-", "").uppercase()
        require(normalized.length == 32 && normalized.all { it in '0'..'9' || it in 'A'..'F' }) {
            "客户端 GUID 必须是 32 位十六进制（16 字节）"
        }
        return ByteArray(16) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    // ---------------- 设备发现 ----------------

    /** 扫描所有已连接 USB 设备，返回 Nikon 设备（Z30 的 PID 优先排在前面）。 */
    fun findNikonDevices(): List<UsbDevice> =
        usbManager.deviceList.values
            .filter { it.vendorId == Ptp.NIKON_VID }
            .sortedByDescending { it.productId == Ptp.NIKON_Z30_PID }

    fun hasPermission(dev: UsbDevice): Boolean = usbManager.hasPermission(dev)

    /** Connect to the camera's PTP/IP server; bindLocalAddress is the phone hotspot-side IP. */
    suspend fun openWifi(host: String, port: Int = 15740, bindLocalAddress: String? = null): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            runCatching {
                closeInternal()
                pairingTransport=null;pairingPreparedAt=0L
                require(host.isNotBlank()) { "请输入相机 IP 地址" }
                require(port in 1..65535) { "PTP/IP 端口无效" }
                val binder = if (bindLocalAddress.isNullOrBlank()) {
                    AndroidCameraNetworkBinder(appContext)
                } else {
                    HotspotSocketBinder(InetAddress.getByName(bindLocalAddress))
                }
                _state.value = State.CONNECTED
                ptpIpFrequencyMhz = cameraLinkFrequencyMhz(appContext, host, bindLocalAddress)
                val identity = ptpIpClientIdentity()
                val initiatorGuidBytes = parseGuidHex(identity.guidHex)
                val isStaMode = !bindLocalAddress.isNullOrBlank()
                ptpIpLog.add("PTP/IP 持久客户端身份已加载（不记录名称/GUID值）")
                fun buildTransport(clientName: String) = PtpIpTransport(
                    host.trim(), port, ptpIpLog, binder,
                    initiatorGuid = initiatorGuidBytes,
                    initiatorName = clientName,
                    receiveBufferBytes = 0, // Keep OS TCP autotuning; explicit 4MiB did not improve the A/B test.
                    lowCopy = true,
                    staMode = isStaMode,
                    commandTimeoutMs = 15_000,
                )
                var transport = buildTransport(identity.name)
                ptpIpTransport = transport
                val opened = try {
                    transport.connect()
                } catch (rejected: PtpIpException) {
                    val fallbackName = if (rejected.stage == "InitCommand" && rejected.code == PtpIp.INIT_FAIL_REJECTED) {
                        alternateBrandClientName(identity.name)
                    } else {
                        null
                    }
                    if (fallbackName == null) throw rejected
                    runCatching { transport.close() }
                    ptpIpLog.add("PTP/IP InitCommand 被拒绝 [reason=1]，尝试兼容客户端名称重试一次")
                    delay(400L)
                    val retryTransport = buildTransport(fallbackName)
                    ptpIpTransport = retryTransport
                    val retryOpened = retryTransport.connect()
                    transport = retryTransport
                    appContext.getSharedPreferences("ptpip_client", Context.MODE_PRIVATE)
                        .edit()
                        .putString("initiator_name", fallbackName)
                        .commit()
                    ptpIpLog.add("PTP/IP 兼容客户端名称握手成功，已持久化当前名称")
                    retryOpened
                }
                if (!opened.ok) throw IllegalStateException("PTP/IP OpenSession 返回 0x%04X".format(opened.code))
                val deviceResponse = transport.executeReadAll(PtpIp.OP_GET_DEVICE_INFO)
                if (!deviceResponse.response.ok) {
                    throw IllegalStateException("GetDeviceInfo 返回 0x%04X".format(deviceResponse.response.code))
                }
                val deviceIdentity = readPtpIpDeviceInfo(deviceResponse.payload)
                val supportsPinPairing = supportsNikonPinPairing(deviceResponse.payload)
                val operations=runCatching{NativeBurstProtocol.operations(deviceResponse.payload)}.getOrDefault(emptySet())
                val isNikon=deviceIdentity.manufacturer.contains("Nikon",ignoreCase=true)
                require(isNikon){"目标端口可达，但设备未识别为Nikon；不会发送厂商命令"}
                ptpIpLog.add("GetDeviceInfo：Nikon型号=${NikonConnectionPolicy.model(deviceIdentity.model)}，固件=${deviceIdentity.firmware.take(40)}，操作数=${operations.size}，主机注册通告=$supportsPinPairing")
                val info = DeviceInfo(
                    vendorId = Ptp.NIKON_VID,
                    manufacturer = deviceIdentity.manufacturer.ifBlank { "Nikon" },
                    model = NikonConnectionPolicy.model(deviceIdentity.model).ifBlank { "Nikon camera" },
                    serial = deviceIdentity.serial,
                    firmwareVersion=deviceIdentity.firmware,
                    sessionId = transport.ptpSessionId,
                    connectAttempts = 1,
                    deviceReady = true,
                    supportsNikonPinPairing = supportsPinPairing,
                    supportsNikonHostRegistration=supportsPinPairing,
                    supportedOperations=operations,
                )
                _info.value = info
                _state.value = State.SESSION_OPEN
                startEventReader(transport)
                HexLog.info("PTP/IP HANDSHAKE OK", "${info.manufacturer} ${info.model} @ $host:$port")
                info
            }.onFailure { error ->
                runCatching { ptpIpTransport?.close() }
                ptpIpTransport = null
                _state.value = State.ERROR
                HexLog.error("PTP/IP OPEN FAILED", "${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private data class WifiDeviceIdentity(val manufacturer:String="",val model:String="",val serial:String="",val firmware:String="")
    private fun readPtpIpDeviceInfo(bytes: ByteArray): WifiDeviceIdentity = runCatching {
        var offset = 0
        fun skip(n: Int) { require(n >= 0 && offset + n <= bytes.size); offset += n }
        fun u32(): Long {
            require(offset + 4 <= bytes.size)
            val v = (bytes[offset].toLong() and 255) or ((bytes[offset + 1].toLong() and 255) shl 8) or
                ((bytes[offset + 2].toLong() and 255) shl 16) or ((bytes[offset + 3].toLong() and 255) shl 24)
            offset += 4
            return v
        }
        fun ptpString(): String {
            require(offset < bytes.size)
            val count = bytes[offset++].toInt() and 255
            if (count == 0) return ""
            require(offset + count * 2 <= bytes.size)
            val value = String(bytes, offset, (count - 1) * 2, Charsets.UTF_16LE)
            offset += count * 2
            return value
        }
        skip(2); u32(); skip(2); ptpString(); skip(2)
        repeat(5) { val n = u32().toInt(); require(n in 0..65535); skip(n * 2) }
        val manufacturer=ptpString();val model=ptpString();val firmware=ptpString()
        WifiDeviceIdentity(manufacturer,model,ptpString(),firmware)
    }.getOrDefault(WifiDeviceIdentity())

    /** Parse DeviceInfo's operation list before any optional datasets. */
    private fun supportsNikonPinPairing(bytes: ByteArray): Boolean = runCatching {
        var offset = 0
        fun u16(): Int {
            require(offset + 2 <= bytes.size)
            val v = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            offset += 2
            return v
        }
        fun u32(): Int {
            require(offset + 4 <= bytes.size)
            val v = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
            offset += 4
            return v
        }
        fun skipPtpString() {
            require(offset < bytes.size)
            val count = bytes[offset++].toInt() and 255
            val size = count * 2
            require(offset + size <= bytes.size)
            offset += size
        }
        u16(); u32(); u16(); skipPtpString(); u16()
        val count = u32()
        require(count in 1..2048 && offset + count * 2 <= bytes.size)
        val operations = buildSet { repeat(count) { add(u16()) } }
        PtpIp.OP_NIKON_GET_PAIRING_CODE in operations && PtpIp.OP_NIKON_CONFIRM_PAIRING in operations
    }.getOrDefault(false)

    /** User-triggered host registration; advertised private ops do not imply pairing is pending. */
    suspend fun readNikonPairingCode(): Result<String> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            runCatching {
                check(_info.value.supportsNikonHostRegistration){"仅Nikon PTP/IP会话可请求主机注册"}
                val transport=ptpIpTransport ?: error("PTP/IP 会话已关闭")
                pairingTransport=null;pairingPreparedAt=0L
                val result=transport.executeReadAll(PtpIp.OP_NIKON_GET_PAIRING_CODE,timeoutMs=15_000)
                check(result.response.ok){"主机注册准备返回0x%04X；0x201F通常表示未进入计算机配置向导，0x2005表示不支持".format(result.response.code)}
                val code=NikonConnectionPolicy.pairingCode(result.payload)
                pairingTransport=transport;pairingPreparedAt=android.os.SystemClock.elapsedRealtime()
                ptpIpLog.add(if(code.isEmpty()) "主机注册准备成功：无PIN数据，等待机身应用配置及用户确认" else "主机注册准备成功：PIN已读取（不记录值）")
                code
            }
        }
    }

    /** Submit one host-registration confirmation after the caller-authorized pairing action. */
    suspend fun confirmNikonPairingCode(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            runCatching {
                check(_info.value.supportsNikonHostRegistration){"当前不是Nikon Wi-Fi会话"}
                val transport = ptpIpTransport ?: error("PTP/IP 会话已关闭")
                check(pairingTransport===transport && pairingPreparedAt>0L){"请先在本次连接准备注册"}
                delay(NikonConnectionPolicy.remainingSettle(android.os.SystemClock.elapsedRealtime()-pairingPreparedAt))
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(ptpIpTransport===transport && pairingTransport===transport){"会话已更换，未发送旧配对确认"}
                pairingTransport=null // One confirmation per explicitly prepared session, never automatic retry.
                val result = transport.execute(PtpIp.OP_NIKON_CONFIRM_PAIRING, listOf(PtpIp.RC_OK.toLong()),timeoutMs=15_000)
                check(result.ok) { "ConfirmPairing 返回 0x%04X".format(result.code) }
                // Nikon may reset/drop the session after asking for on-camera confirmation; do not send CloseSession.
                transport.close()
                ptpIpTransport = null
                _state.value = State.DISCONNECTED
                _info.value = DeviceInfo()
                ptpIpLog.add("主机注册已提交；请在相机上批准此主机，随后使用相同名称/GUID重新连接")
            }
        }
    }

    /**
     * 打开设备并建立 PTP 会话。最多尝试 [CONNECT_ATTEMPTS] 轮，每轮之间做传输恢复。
     * 权限需由调用方先通过 UsbManager.requestPermission 获取。
     */
    suspend fun open(dev: UsbDevice): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            runCatching {
                closeInternal()

                if (!usbManager.hasPermission(dev)) {
                    _state.value = State.PERMISSION_PENDING
                    throw IllegalStateException("未授予 USB 权限，请先 requestPermission")
                }

                var lastError: Throwable? = null
                for (attempt in 1..CONNECT_ATTEMPTS) {
                    connectAttempts = attempt
                    // 恢复后设备可能已重新枚举，必须重新拿 UsbDevice 实例，
                    // 否则拿旧的 UsbDevice 调 openDevice 会直接返回 null。
                    val target = resolveDevice(dev) ?: dev
                    try {
                        val di = attemptOpen(target, attempt)
                        HexLog.info("HANDSHAKE OK", "第 $attempt 轮握手成功，session=${di.sessionId}")
                        return@runCatching di
                    } catch (t: Throwable) {
                        lastError = t
                        HexLog.warn(
                            "握手失败 第 $attempt/$CONNECT_ATTEMPTS 轮",
                            t.message ?: t.javaClass.simpleName,
                        )
                        if (attempt < CONNECT_ATTEMPTS) {
                            recoverTransport(target, attempt)
                        }
                    }
                }
                throw lastError ?: IllegalStateException("PTP 握手失败（未知原因）")
            }.onFailure { t ->
                HexLog.error("OPEN FAILED", "${t.javaClass.simpleName}: ${t.message}")
                _state.value = State.ERROR
            }
        }
    }

    /** 单轮尝试：找接口 → 打开 → claim → alt setting → 0x66 reset → clear halt → OpenSession。 */
    private fun attemptOpen(dev: UsbDevice, attempt: Int): DeviceInfo {
        dumpDeviceTree(dev)
        val iface = locatePtpInterface(dev)
            ?: throw IllegalStateException("未找到 PTP 接口 (class=6/sub=1/proto=1)")

        var bIn: UsbEndpoint? = null
        var bOut: UsbEndpoint? = null
        var iIn: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            when {
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN -> bIn = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT -> bOut = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.direction == UsbConstants.USB_DIR_IN -> iIn = ep
            }
        }
        if (bIn == null || bOut == null) {
            throw IllegalStateException("PTP 接口缺少 Bulk-In/Bulk-Out 端点")
        }

        val conn = usbManager.openDevice(dev)
            ?: throw IllegalStateException("openDevice 失败（权限被回收，或已被其他 App 占用）")

        // 先登记，后面 resetPtpDevice / clearEndpointHalt 要用
        device = dev
        connection = conn
        ptpInterface = iface
        epIn = bIn
        epOut = bOut
        epInterrupt = iIn
        transactionId = 0
        sessionId = 1
        syncTransport = false
        deviceReadyOk = false

        if (!conn.claimInterface(iface, true)) {
            throw IllegalStateException("claimInterface 失败（相机 PTP 接口被独占，换线/退后台重试）")
        }

        // 非 0 alt setting 需显式切回 0
        if (iface.alternateSetting != 0) {
            val alt0 = (0 until dev.interfaceCount)
                .map { dev.getInterface(it) }
                .firstOrNull { it.id == iface.id && it.alternateSetting == 0 }
            if (alt0 != null) {
                conn.setInterface(alt0)
                ptpInterface = alt0
                HexLog.info("setInterface", "alt ${iface.alternateSetting} → 0")
            }
        }

        // ★ 关键一步：PTP class reset（ZENCHE 的 resetPtpDevice，0x66 class 请求）
        resetPtpDevice()
        Thread.sleep(150)   // 相机重新初始化端点需要时间，太快发 OpenSession 会被吞掉

        // ★ 清 halt
        clearEndpointHalt(bIn)
        clearEndpointHalt(bOut)

        val di0 = DeviceInfo(
            vendorId = dev.vendorId,
            productId = dev.productId,
            manufacturer = safeString { dev.manufacturerName } ?: "",
            model = safeString { dev.productName } ?: "",
            serial = safeString { dev.serialNumber } ?: "",
            ptpInterfaceIndex = ptpInterface?.id ?: iface.id,
            bulkInAddr = bIn.address,
            bulkOutAddr = bOut.address,
            interruptInAddr = iIn?.address ?: -1,
            sessionId = sessionId,
            connectAttempts = attempt,
        )
        _info.value = di0
        _state.value = State.CONNECTED

        HexLog.info(
            "OPEN",
            "VID=0x%04X PID=0x%04X %s %s if=%d in=0x%02X out=0x%02X irq=0x%02X attempt=%d".format(
                dev.vendorId, dev.productId, di0.manufacturer, di0.model,
                di0.ptpInterfaceIndex, bIn.address, bOut.address, iIn?.address ?: 0, attempt,
            ),
        )

        // ★ OpenSession 事务号必须为 0
        val r = transactBlocking(
            Ptp.OP_OPEN_SESSION, intArrayOf(sessionId.toInt()), null,
            expectData = false, forceTx = 0L,
        )
        if (r.responseCode != Ptp.RC_OK && r.responseCode != Ptp.RC_SESSION_ALREADY_OPEN) {
            throw PtpException(
                r.responseCode,
                "OpenSession 失败: 0x%04X %s".format(r.responseCode, Ptp.responseName(r.responseCode)),
            )
        }
        if (r.responseCode == Ptp.RC_SESSION_ALREADY_OPEN) {
            HexLog.info("OpenSession", "相机会话已存在（0x201E），沿用当前 session")
        }

        _state.value = State.SESSION_OPEN

        // DeviceReady：ZENCHE 容忍 0x2009，失败不阻断
        deviceReadyOk = runCatching { deviceReady() }.getOrDefault(false)
        HexLog.info("DeviceReady 0x90C8", if (deviceReadyOk) "OK" else "无响应（不阻断）")

        val di = di0.copy(deviceReady = deviceReadyOk, syncTransport = syncTransport)
        _info.value = di
        return di
    }

    /** 传输恢复：reset → 只关连接 → 退避 → 等设备重新枚举。 */
    private fun recoverTransport(dev: UsbDevice, attempt: Int) {
        runCatching { resetPtpDevice() }
        Thread.sleep(120)
        runCatching { closeConnectionOnly() }
        Thread.sleep((350L * attempt).coerceAtMost(1500L))
        waitForDevice(dev, 3000)
        HexLog.info("传输恢复", "第 $attempt 轮恢复完成")
    }

    /** 用 VID/PID 在当前枚举里重新取一次 UsbDevice（重试时旧的实例可能已失效）。 */
    private fun resolveDevice(old: UsbDevice): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == old.vendorId && it.productId == old.productId
        }

    private fun waitForDevice(dev: UsbDevice, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (usbManager.deviceList.values.any {
                    it.vendorId == dev.vendorId && it.productId == dev.productId
                }
            ) return
            Thread.sleep(150)
        }
    }

    /** 把设备描述符树打进日志——真机连不上时这是第一手证据。 */
    private fun dumpDeviceTree(dev: UsbDevice) {
        val sb = StringBuilder()
        sb.appendLine("VID=0x%04X PID=0x%04X devClass=%d ifaces=%d".format(
            dev.vendorId, dev.productId, dev.deviceClass, dev.interfaceCount))
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            sb.append("  if[$i] id=%d alt=%d cls=%d sub=%d proto=%d eps=%d".format(
                itf.id, itf.alternateSetting, itf.interfaceClass,
                itf.interfaceSubclass, itf.interfaceProtocol, itf.endpointCount))
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                sb.append(" | ep%d addr=0x%02X type=%d dir=%d maxpkt=%d".format(
                    e, ep.address, ep.type, ep.direction, ep.maxPacketSize))
            }
            if (i != dev.interfaceCount - 1) sb.appendLine()
        }
        HexLog.info("USB 描述符", sb.toString())
    }

    private fun locatePtpInterface(dev: UsbDevice): UsbInterface? {
        // 优先精确匹配 class/subclass/protocol
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                itf.interfaceSubclass == 1 && itf.interfaceProtocol == 1
            ) return itf
        }
        // 退化：凡是 Still Image 类
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return itf
        }
        // 再退化：找一个自带 Bulk In+Out 的接口
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            var hasIn = false
            var hasOut = false
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                    if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                }
            }
            if (hasIn && hasOut) return itf
        }
        return null
    }

    private inline fun safeString(block: () -> String?): String? =
        runCatching { block() }.getOrNull()

    /** 关闭会话并释放接口。 */
    suspend fun close() = withContext(Dispatchers.IO) {
        // Drop PTP/IP sockets before waiting for the serialized transaction lock. This interrupts
        // a stale blocking read so a manual disconnect can clear a ghost "connected" state promptly.
        ptpIpTransport?.close()
        ioMutex.withLock { closeInternal() }
    }

    private fun closeInternal() {
        eventJob?.cancel()
        eventJob = null
        staFileNumbers.clear()
        runCatching {
            val ip = ptpIpTransport
            if (ip != null && ip.isOpen && _state.value == State.SESSION_OPEN) runBlocking { ip.execute(PtpIp.OP_CLOSE_SESSION) }
        }
        runCatching { ptpIpTransport?.close() }
        ptpIpTransport = null
        staDirectAlbumMode = false
        staObjectSizes.clear()
        runCatching {
            if (connection != null && _state.value == State.SESSION_OPEN) {
                // ISO 15740 的 CloseSession 不带参数。真机日志（ptp-20261027(2).log
                // 21:59:01.743）里带 [sessionId] 发出去会回 0x2006 ParameterNotSupported，
                // 每次都往日志里留一条假错误，干扰排查。
                transactBlocking(Ptp.OP_CLOSE_SESSION, IntArray(0), null, false)
            }
        }
        runCatching { closeConnectionOnly() }
        movieRecordingMayBeActive = false
        previousMovieSelector = null
        previousApplicationMode = null
        _state.value = State.DISCONNECTED
        _info.value = DeviceInfo()
    }

    private fun closeConnectionOnly() {
        runCatching { ptpInterface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null
        ptpInterface = null
        epIn = null
        epOut = null
        epInterrupt = null
        syncTransport = false
    }

    // ---------------- USB 控制请求 ----------------

    /**
     * PTP class reset：bRequest = 0x66，bmRequestType = 0x21 (OUT|CLASS|INTERFACE)。
     * 不清这个，相机会把新的 OpenSession 当成上一轮残留事务的一部分。
     */
    private fun resetPtpDevice() {
        val conn = connection ?: return
        val iface = ptpInterface ?: return
        val ret = runCatching {
            conn.controlTransfer(
                USB_BM_OUT_CLASS_INTERFACE,
                PTP_USB_REQUEST_RESET,
                0, iface.id,
                null, 0,
                2000,
            )
        }.getOrElse { -1 }
        HexLog.log(
            HexLogEntry.Direction.TX, -1,
            "PTP Class Reset (0x66)",
            ByteArray(0),
            note = "iface=${iface.id} ret=$ret",
        )
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT)：bmRequestType = 0x02 (OUT|STANDARD|ENDPOINT)。 */
    private fun clearEndpointHalt(ep: UsbEndpoint) {
        val conn = connection ?: return
        runCatching {
            conn.controlTransfer(
                USB_BM_OUT_STANDARD_ENDPOINT,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                ep.address,
                null, 0,
                1000,
            )
        }
    }

    // ---------------- 底层 USB 读写 ----------------

    private fun nextTxId(): Long {
        transactionId = (transactionId + 1) and 0xFFFFFFFFL
        if (transactionId == 0L) transactionId = 1
        return transactionId
    }

    /** 在 Bulk-OUT 上写满整块，失败先清 halt 重试一次。 */
    private fun bulkWriteFully(bytes: ByteArray, timeoutMs: Int = TIMEOUT_MS) {
        val out = epOut ?: throw IOException("Bulk-OUT 未就绪")
        var offset = 0
        var recovered = false
        while (offset < bytes.size) {
            val want = minOf(MAX_BULK_CHUNK, bytes.size - offset)
            val sent = try {
                transferOut(out, bytes, offset, want, timeoutMs)
            } catch (e: IOException) {
                if (recovered) throw e
                recovered = true
                clearEndpointHalt(out)
                -1
            }
            if (sent < 0) throw IOException("Bulk-OUT 写失败 @$offset/${bytes.size}")
            if (sent == 0) throw IOException("Bulk-OUT 写 0 字节 @$offset/${bytes.size}")
            offset += sent
        }
    }

    /** 一次读满 [size] 字节到 dst（offset 起），允许若干次 NAK 空转。 */
    private fun bulkReadInto(dst: ByteArray, offset: Int, size: Int, timeoutMs: Int = TIMEOUT_MS) {
        val inp = epIn ?: throw IOException("Bulk-IN 未就绪")
        var done = 0
        var idle = 0
        var recovered = false
        while (done < size) {
            val want = minOf(MAX_BULK_CHUNK, size - done)
            val got = try {
                transferIn(inp, dst, offset + done, want, timeoutMs)
            } catch (e: IOException) {
                if (recovered) throw e
                recovered = true
                clearEndpointHalt(inp)
                -1
            }
            if (got <= 0) {
                if (idle++ < 4) continue
                throw IOException("Bulk-IN 读超时 @${done}/${size}")
            }
            idle = 0
            done += got
        }
    }

    private fun bulkReadFully(size: Int, timeoutMs: Int = TIMEOUT_MS): ByteArray {
        val out = ByteArray(size)
        bulkReadInto(out, 0, size, timeoutMs)
        return out
    }

    // ---- 单向传输：异步优先 + 同步粘性降级 ----

    private fun transferIn(ep: UsbEndpoint, dst: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (!syncTransport) {
            try {
                val buf = ByteBuffer.allocateDirect(maxOf(length, ep.maxPacketSize))
                buf.order(ByteOrder.LITTLE_ENDIAN)
                buf.clear()
                buf.limit(length)
                val n = runUsbRequest(ep, buf, length, timeoutMs, isIn = true)
                if (n > 0) {
                    buf.flip()
                    val copy = minOf(n, length)
                    buf.get(dst, offset, copy)
                    return copy
                }
                if (n == 0) return 0
                throw IOException("异步 IN 返回 $n")
            } catch (t: Throwable) {
                if (!isAsyncDegradable(t)) throw t
                HexLog.warn("异步传输降级", "${t.message} → 改用同步 bulk")
                clearEndpointHalt(ep)
                syncTransport = true
                _info.value = _info.value.copy(syncTransport = true)
            }
        }
        val n = connection!!.bulkTransfer(ep, dst, offset, length, timeoutMs)
        if (n < 0) throw IOException("同步 bulkTransfer 读失败 ret=$n")
        return n
    }

    /**
     * OUT 方向一律用同步 bulkTransfer。
     * 异步 OUT 在部分 Android 主机控制器上「提交成功但没真正发出」，
     * 会让相机等不齐容器头而整体错位；同步写能拿到确切字节数，安全得多。
     */
    private fun transferOut(ep: UsbEndpoint, src: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val n = connection!!.bulkTransfer(ep, src, offset, length, timeoutMs)
        if (n < 0) throw IOException("同步 bulkTransfer 写失败 ret=$n")
        return n
    }

    /**
     * 一次异步 UsbRequest 传输。
     * @return IN 方向返回读到字节数；OUT 方向返回已发字节数（拿不到精确值时回 length）。
     */
    private fun runUsbRequest(
        ep: UsbEndpoint,
        buf: ByteBuffer,
        length: Int,
        timeoutMs: Int,
        isIn: Boolean,
    ): Int {
        val conn = connection ?: throw IOException("连接未建立")
        val request = UsbRequest()
        try {
            if (!request.initialize(conn, ep)) throw IOException("无法初始化异步 USB 请求")
            if (!request.queue(buf)) throw IOException("无法提交异步 USB 请求")
            val finished = try {
                conn.requestWait(timeoutMs.toLong())
            } catch (e: java.util.concurrent.TimeoutException) {
                throw IOException("等待异步 USB 请求 ${timeoutMs}ms 后超时", e)
            }
            if (finished !== request) throw IOException("异步 USB 请求未返回有效完成结果")
            return if (isIn) buf.position() else maxOf(buf.position(), length)
        } finally {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) request.close()
            }
        }
    }

    private fun isAsyncDegradable(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return t is IOException || t is java.util.concurrent.TimeoutException ||
            msg.contains("超时") || msg.contains("timeout") ||
            msg.contains("异步") || msg.contains("usb request")
    }

    /** 可选：读一次中断端点上的事件（仅调试台手动触发，正常运行不读）。 */
    fun readInterruptEventOnce(): ByteArray? {
        val ep = epInterrupt ?: return null
        val conn = connection ?: return null
        return runCatching {
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(16))
            val n = conn.bulkTransfer(ep, buf, buf.size, 20)
            if (n >= PTP_EVENT_HEADER) {
                val code = ((buf[8].toInt() and 0xFF) or ((buf[9].toInt() and 0xFF) shl 8))
                HexLog.log(
                    HexLogEntry.Direction.EVENT, -1,
                    "Event 0x%04X".format(code),
                    buf.copyOf(n),
                )
                buf.copyOf(n)
            } else null
        }.getOrNull()
    }

    // ---------------- PTP 事务 ----------------

    suspend fun transact(
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectData: Boolean,
        timeoutMs: Int = TIMEOUT_MS,
        expectedConnection: Any? = null,
    ): PtpResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if((code==Ptp.OP_NIKON_SET_PICCTRL || code==Ptp.OP_NIKON_DEL_CUSTOM_PICCTRL) &&
                !NikonConnectionPolicy.permitsLegacyPictureControlWrite(_info.value.model)) {
                HexLog.warn("机型写入保护","未验证此机型的Z30优化校准格式；已阻止写入/删除，不发送相机命令")
                return@withLock PtpResult(Ptp.RC_OPERATION_NOT_SUPPORTED,intArrayOf(),null,"","","")
            }
            if (expectedConnection != null) check(isOpen && burstConnectionToken === expectedConnection) {
                "旧连拍会话已结束，不向新连接发送快门或参数恢复命令"
            }
            val ip = ptpIpTransport
            if (ip == null) {
                // A just-closed PTP/IP session can leave older ViewModel jobs queued on ioMutex.
                // Never fall through to the USB transport when no USB connection/session exists.
                if (connection == null || _state.value != State.SESSION_OPEN) {
                    return@withLock PtpResult(Ptp.RC_SESSION_NOT_OPEN, intArrayOf(), null, "", "", "")
                }
                transactBlocking(code, params, dataOut, expectData, null, timeoutMs)
            } else {
                try {
                    val args = params.map { it.toLong() }
                    var payload: ByteArray? = null
                    val response = when {
                        dataOut != null -> ip.executeWrite(code, args, dataOut, timeoutMs)
                        expectData -> ip.executeReadAll(code, args, timeoutMs).also { payload = it.payload }.response
                        else -> ip.execute(code, args, timeoutMs)
                    }
                    PtpResult(response.code, response.params.map { it.toInt() }.toIntArray(), payload, "", "", "")
                } catch (error: Throwable) {
                    if (!ip.isOpen && ptpIpTransport === ip) {
                        ip.close()
                        ptpIpTransport = null
                        staDirectAlbumMode = false
                        staObjectSizes.clear()
                        _info.value = DeviceInfo()
                        _state.value = State.DISCONNECTED
                        HexLog.error("PTP/IP 会话中断", "0x%04X：%s".format(code, error.message ?: error.javaClass.simpleName))
                    }
                    throw error
                }
            }
        }
    }

    /**
     * 一次完整 PTP 事务（已持有 ioMutex 时直接调用，不要在外层再 transact）。
     *
     * 阶段顺序（PTP 规范）：
     *  - 无数据：Command → Response
     *  - 数据入：Command → Data → Response
     *  - 数据出：Command → Data → Response
     *
     * @param forceTx 强制事务号（OpenSession 必须 0），传 null 走自增
     */
    private fun transactBlocking(
        code: Int,
        params: IntArray,
        dataOut: ByteArray?,
        expectData: Boolean,
        forceTx: Long? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): PtpResult {
        if (connection == null) throw IllegalStateException("PTP 连接未建立")
        val tx = if (forceTx != null) {
            transactionId = forceTx
            forceTx
        } else {
            nextTxId()
        }

        // ---- Phase 1: Command ----
        val cmdBytes = PtpContainer.command(code, tx, params)
        HexLog.log(
            HexLogEntry.Direction.TX, tx,
            "CMD 0x%04X".format(code),
            cmdBytes,
            "params=[${params.joinToString(",") { "0x%X".format(it) }}]",
        )
        bulkWriteFully(cmdBytes, timeoutMs)

        // ---- Phase 2: Data-Out ----
        var dataPayload: ByteArray? = null
        var dataHex = ""
        if (dataOut != null) {
            val dataContainer = PtpContainer.data(code, tx, dataOut)
            HexLog.log(
                HexLogEntry.Direction.TX, tx,
                "DATA-OUT 0x%04X".format(code),
                dataContainer,
                "len=${dataOut.size}",
            )
            bulkWriteFully(dataContainer, timeoutMs)
            dataHex = dataOut.toHex(96)
        }

        // ---- Phase 3: Data-In（若期待） ----
        if (expectData && dataOut == null) {
            val raw = readContainerRaw(timeoutMs)
            if (raw.type == Ptp.TYPE_DATA) {
                if (raw.transactionId != tx) {
                    HexLog.warn(
                        "事务号不一致(Data)",
                        "期望 %d，收到 %d".format(tx, raw.transactionId),
                    )
                }
                dataPayload = raw.payload
                dataHex = raw.payload.toHex(96)
                HexLog.log(
                    HexLogEntry.Direction.RX, raw.transactionId,
                    "DATA-IN 0x%04X".format(raw.code),
                    raw.payload, "len=${raw.payload.size}",
                )
            } else if (raw.type == Ptp.TYPE_RESPONSE) {
                // 相机直接回错误响应，没有 Data 阶段
                val ps = raw.payload.toIntArrayLe()
                val r = PtpResult(
                    responseCode = raw.code,
                    responseParams = ps,
                    data = null,
                    rawCommandHex = cmdBytes.toHex(64),
                    rawDataHex = "",
                    rawResponseHex = raw.rawHeader.toHex(64),
                )
                HexLog.log(
                    HexLogEntry.Direction.RX, raw.transactionId,
                    "RESP(early) 0x%04X (${Ptp.responseName(raw.code)})".format(raw.code),
                    raw.rawHeader + raw.payload,
                )
                return r
            } else {
                throw IOException("意外的容器类型 0x%04X（期望 Data/Response）".format(raw.type))
            }
        }

        // ---- Phase 4: Response ----
        val resp = readContainerRaw(timeoutMs)
        if (resp.type != Ptp.TYPE_RESPONSE) {
            HexLog.warn("响应容器类型异常", "type=0x%04X code=0x%04X".format(resp.type, resp.code))
        }
        if (resp.transactionId != tx) {
            HexLog.warn(
                "事务号不一致(Resp)",
                "期望 %d，收到 %d".format(tx, resp.transactionId),
            )
        }
        HexLog.log(
            HexLogEntry.Direction.RX, resp.transactionId,
            "RESP 0x%04X (${Ptp.responseName(resp.code)})".format(resp.code),
            resp.rawHeader + resp.payload,
        )

        return PtpResult(
            responseCode = resp.code,
            responseParams = resp.payload.toIntArrayLe(),
            data = dataPayload,
            rawCommandHex = cmdBytes.toHex(64),
            rawDataHex = dataHex,
            rawResponseHex = (resp.rawHeader + resp.payload).toHex(64),
        )
    }

    private class RawContainer(
        val type: Int,
        val code: Int,
        val transactionId: Long,
        val payload: ByteArray,
        val rawHeader: ByteArray,
    )

    /** 读一个完整容器：先凑 12 字节头，再按 Length 读满载荷。 */
    /**
     * 读一个完整 PTP 容器。
     *
     * ★ 与 v1.0.0 的差别：不再「先精确读 12 字节头、再读 body」，而是像 ZENCHE 那样
     * 先读一整块（最多 MAX_BULK_CHUNK）到缓冲，从头 12 字节解析 Length，再补齐剩余。
     * 原因：部分相机/主机控制器组合下，请求长度刚好等于 12 时设备会以短包结束这次
     * bulk 传输并把剩余数据丢掉，表现为「头读到了、body 永远超时」。一次读大块可以
     * 把头和 body 一起收进来，也少一次 USB 往返。
     */
    private fun readContainerRaw(timeoutMs: Int): RawContainer {
        val inp = epIn ?: throw IOException("Bulk-IN 未就绪")
        val chunk = ByteArray(MAX_BULK_CHUNK)
        var filled = 0
        while (filled < PtpContainer.HEADER_SIZE) {
            val got = readOnce(inp, chunk, filled, chunk.size - filled, timeoutMs)
            if (got <= 0) throw IOException("Bulk-IN 容器头读超时（已收 $filled B）")
            filled += got
        }

        val hdr = PtpContainer.parseHeader(chunk.copyOf(PtpContainer.HEADER_SIZE))
            ?: throw IOException("容器头非法: ${chunk.copyOf(PtpContainer.HEADER_SIZE).toHex()}")

        val total = hdr.length
        if (total < PtpContainer.HEADER_SIZE) throw IOException("容器长度非法: $total")

        val out = ByteArray(total)
        val first = minOf(filled, total)
        System.arraycopy(chunk, 0, out, 0, first)
        var done = first
        while (done < total) {
            val got = readOnce(inp, out, done, total - done, timeoutMs)
            if (got <= 0) throw IOException("Bulk-IN 数据读超时 @$done/$total")
            done += got
        }

        val body = if (total > PtpContainer.HEADER_SIZE) {
            out.copyOfRange(PtpContainer.HEADER_SIZE, total)
        } else {
            ByteArray(0)
        }
        return RawContainer(
            hdr.type, hdr.code, hdr.transactionId, body,
            out.copyOf(PtpContainer.HEADER_SIZE),
        )
    }

    /** 单次 bulk 读，异常时清一次 halt 再重试，仍失败才抛。 */
    private fun readOnce(
        inp: UsbEndpoint,
        dst: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
    ): Int {
        val want = minOf(MAX_BULK_CHUNK, length)
        return try {
            transferIn(inp, dst, offset, want, timeoutMs)
        } catch (e: IOException) {
            HexLog.warn("Bulk-IN 异常", "${e.message} → clear halt 重试")
            clearEndpointHalt(inp)
            transferIn(inp, dst, offset, want, timeoutMs)
        }
    }

    private fun ByteArray.toIntArrayLe(): IntArray {
        val n = size / 4
        val out = IntArray(n)
        val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = bb.int
        return out
    }

    // ---------------- 尼康优化校准高层 API ----------------

    private fun picCtrlParams(slot: Int): IntArray =
        if (singleParamMode) intArrayOf(slot) else intArrayOf(slot, 0)

    /**
     * 读取指定槽位的优化校准原始数据（0x90CC，params = (slot, 0)）。
     *
     * 返回 **614 字节**原始记录（见 [com.zbox.ai.core.PcPayload614]）。
     * 机身对未注册的空槽位回 36 字节全零，这里翻译成 [Ptp.RC_LOCAL_EMPTY_SLOT]。
     */
    suspend fun getPicCtrlData(
        slot: Int,
        slotIndexHint: Int = 0,
        timeoutMs: Int = TIMEOUT_MS,
    ): Result<ByteArray> {
        val r = transact(
            Ptp.OP_NIKON_GET_PICCTRL, picCtrlParams(slot), null,
            expectData = true, timeoutMs = timeoutMs,
        )
        val raw = r.data
        if (!r.ok || raw == null) {
            return Result.failure(
                PtpException(
                    r.responseCode,
                    "GetPictCtrlData(slot=0x%04X) 失败: %s".format(
                        slot, Ptp.responseName(r.responseCode),
                    ),
                ),
            )
        }
        if (raw.all { it == 0.toByte() }) {
            return Result.failure(
                PtpException(
                    Ptp.RC_LOCAL_EMPTY_SLOT,
                    "空槽位（机身返回 ${raw.size}B 全零）",
                ),
            )
        }
        // 正常路径：正好 614 字节，或前面多几个字节的填充
        val payload = when {
            com.zbox.ai.core.PcPayload614.looksLike614(raw) -> raw
            raw.size > com.zbox.ai.core.PcPayload614.SIZE -> {
                val off = raw.size - com.zbox.ai.core.PcPayload614.SIZE
                val tail = raw.copyOfRange(off, raw.size)
                if (com.zbox.ai.core.PcPayload614.looksLike614(tail)) {
                    HexLog.info(
                        "载荷归一化",
                        "机身返回 ${raw.size}B → 丢掉 $off B 前导，取尾部 614B（slot=0x%04X）".format(slot),
                    )
                    tail
                } else {
                    raw
                }
            }
            else -> raw
        }
        if (payload.size != com.zbox.ai.core.PcPayload614.SIZE) {
            return Result.failure(
                PtpException(
                    -1,
                    "读回 ${raw.size}B，不是 614 字节优化校准记录" +
                        "（首 16B: ${raw.toHex(16)}）",
                ),
            )
        }
        return Result.success(payload)
    }

    /**
     * 写入优化校准数据（0x90CD，params = (slot, 0)，data-out = **614 字节**）。
     *
     * 614 字节是机身真实格式。之前按 84 字节发（固件内部 SDS 结构），
     * 长度对不上，机身一律拒 —— 这才是 AccessDenied/InvalidParameter 的根因。
     */
    suspend fun setPicCtrlData(slot: Int, payload: ByteArray): Result<Unit> {
        if (payload.size != com.zbox.ai.core.PcPayload614.SIZE) {
            return Result.failure(
                IllegalArgumentException(
                    "载荷必须 614 字节，实际 ${payload.size}",
                ),
            )
        }
        if (!Ptp.isCustomSlot(slot)) {
            HexLog.warn("SetPicCtrl 目标非自定义槽位", "slot=0x%04X".format(slot))
        }

        // 如果之前探测出可用机制，就按固化下来的配方走
        val mode = writeMode
        if (mode != null) {
            when (mode.prelude) {
                1 -> {
                    runCatching { changeCameraMode(true) }
                    runCatching { deviceReadyBlocking() }
                    kotlinx.coroutines.delay(200)
                }
                2 -> {
                    runCatching { changeCameraMode(true) }
                    runCatching { endLiveView() }
                    kotlinx.coroutines.delay(150)
                    runCatching { deviceReadyBlocking() }
                    kotlinx.coroutines.delay(150)
                }
                3 -> {
                    runCatching { changeCameraMode(true) }
                    runCatching { deleteCustomPicCtrl(slot) }
                    kotlinx.coroutines.delay(200)
                    runCatching { deviceReadyBlocking() }
                    kotlinx.coroutines.delay(150)
                }
                4 -> {
                    runCatching { changeCameraMode(true) }
                    runCatching { setActivePictureControl(slot) }
                    kotlinx.coroutines.delay(250)
                    runCatching { deviceReadyBlocking() }
                    kotlinx.coroutines.delay(150)
                }
            }
            val slotIndex = Ptp.customSlotIndex(slot)
            val body = when {
                mode.itemPrefix == 1 -> ByteArray(615).also {
                    it[0] = slotIndex.toByte()
                    System.arraycopy(payload, 0, it, 1, payload.size)
                }
                mode.itemPrefix == 2 -> ByteArray(615).also {
                    it[0] = (slot and 0xFF).toByte()
                    System.arraycopy(payload, 0, it, 1, payload.size)
                }
                mode.itemPrefix == 3 -> ByteArray(617).also {
                    // digiCamControl 反推的老机身布局：[item][mono][customFlag] + 载荷。
                    // 614 字节格式里没有独立的 pcd_type 字节，用名称区首字节之外的
                    // +0x02C（族标记）判断是否单色记录。
                    it[0] = slotIndex.toByte()
                    it[1] = if ((payload[com.zbox.ai.core.PcPayload614.OFF_FAMILY].toInt() and 0xFF) == 1) 1 else 0
                    it[2] = 1
                    System.arraycopy(payload, 0, it, 3, payload.size)
                }
                else -> payload
            }
            val params = when (mode.paramMode) {
                1 -> intArrayOf(slot, 1)
                2 -> intArrayOf(slot)
                3 -> intArrayOf(slot, payload.size)
                4 -> intArrayOf(slot, 1)
                else -> intArrayOf(slot, 0)
            }
            val r = transact(
                Ptp.OP_NIKON_SET_PICCTRL, params, body,
                expectData = false, timeoutMs = 15000,
            )
            return if (r.ok) Result.success(Unit)
            else Result.failure(
                PtpException(
                    r.responseCode,
                    "SetPictCtrlData(${mode.describe()}) 失败: ${Ptp.responseName(r.responseCode)}",
                ),
            )
        }

        val r = transact(
            Ptp.OP_NIKON_SET_PICCTRL, picCtrlParams(slot), payload,
            expectData = false, timeoutMs = 15000,
        )
        if (r.ok) return Result.success(Unit)

        HexLog.warn(
            "SetPicCtrl 失败",
            "slot=0x%04X RC=0x%04X %s".format(slot, r.responseCode, Ptp.responseName(r.responseCode)),
        )
        return Result.failure(
            PtpException(
                r.responseCode,
                "SetPictCtrlData(slot=0x%04X) 失败: %s".format(
                    slot, Ptp.responseName(r.responseCode),
                ),
            ),
        )
    }

    /** 清空指定自定义槽位（0x90CE，1 个参数）。 */
    suspend fun deleteCustomPicCtrl(slot: Int): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_DEL_CUSTOM_PICCTRL, intArrayOf(slot), null, expectData = false)
        return if (r.ok) Result.success(Unit)
        else Result.failure(
            PtpException(
                r.responseCode,
                "DelCstPicCtrl(slot=0x%04X) 失败: %s".format(slot, Ptp.responseName(r.responseCode)),
            ),
        )
    }

    /** 查询槽位能力（0x90CF）——调试台用来确认固件支不支持自定义槽位。 */
    suspend fun getPicCtrlCapability(slot: Int): Result<ByteArray> {
        val r = transact(Ptp.OP_NIKON_GET_PICCTRL_CAPS, intArrayOf(slot), null, expectData = true)
        val d = r.data
        return if (r.ok && d != null) Result.success(d)
        else Result.failure(
            PtpException(r.responseCode, "GetPicCtrlCapability 失败: 0x%04X".format(r.responseCode)),
        )
    }

    /** DeviceReady（0x90C8）。返回是否成功（容忍 0x2009）。 */
    suspend fun deviceReadyBlocking(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { deviceReady() }
    }

    private fun deviceReady(): Boolean {
        val r = runCatching {
            val ip = ptpIpTransport
            if (ip == null) transactBlocking(Ptp.OP_NIKON_DEVICE_READY, IntArray(0), null, false, null, 3000)
            else {
                val response = runBlocking { ip.execute(Ptp.OP_NIKON_DEVICE_READY, timeoutMs = 3000) }
                PtpResult(response.code, response.params.map { it.toInt() }.toIntArray(), null, "", "", "")
            }
        }.getOrNull() ?: return false
        return r.ok || r.responseCode == Ptp.RC_INVALID_OBJECT_HANDLE
    }

    /**
     * 探测哪个参数形态可用：先试 (slot,0) 两参，失败再试单参。
     * 两条都失败时再试一次「切 PC 控制模式(0x90C2) + DeviceReady」后重试，
     * 因为有些机身只有在 PC 控制模式下才允许读自定义槽位（digiCamControl 的 InitHostMode 就是这么干）。
     * @return true = 单参数模式
     */
    suspend fun probeOpcodeProfile(): Result<Boolean> {
        val saved = singleParamMode
        singleParamMode = false
        val two = getPicCtrlData(Ptp.CUSTOM_SLOTS[0])
        if (two.isSuccess) {
            HexLog.info("操作码探测", "0x90CC (slot,0) 两参数可用")
            return Result.success(false)
        }
        singleParamMode = true
        val one = getPicCtrlData(Ptp.CUSTOM_SLOTS[0])
        if (one.isSuccess) {
            HexLog.info("操作码探测", "0x90CC 单参数可用")
            return Result.success(true)
        }

        // 第三轮：先进 PC 控制模式
        HexLog.info("操作码探测", "两种形态均失败 → 尝试 0x90C2 进入 PC 控制模式后重试")
        runCatching { changeCameraMode(true) }
        runCatching { deviceReadyBlocking() }
        kotlinx.coroutines.delay(300)
        singleParamMode = false
        val two2 = getPicCtrlData(Ptp.CUSTOM_SLOTS[0])
        if (two2.isSuccess) return Result.success(false)
        singleParamMode = true
        val one2 = getPicCtrlData(Ptp.CUSTOM_SLOTS[0])
        if (one2.isSuccess) return Result.success(true)

        singleParamMode = saved
        return Result.failure(
            PtpException(
                (two2.exceptionOrNull() as? PtpException)?.responseCode ?: -1,
                "0x90CC 读不到槽位（两参: ${two2.exceptionOrNull()?.message} / 单参: ${one2.exceptionOrNull()?.message}）",
            ),
        )
    }

    /**
     * 一键诊断：把「会话是否真的通了」和「槽位操作码是否可用」分开定位。
     * 输出写进 Hex 日志，用户在调试台直接看得懂卡在哪一层。
     */
    suspend fun diagnose(): Result<String> = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext Result.failure(IllegalStateException("未连接"))
        val sb = StringBuilder()

        // 1) 标准 0x1001 GetDeviceInfo —— 这一条能过，说明 PTP 会话本身没问题
        val devInfo = getDeviceInfoRaw()
        if (devInfo.isSuccess) {
            val d = devInfo.getOrNull()!!
            val txt = parseDeviceInfoStrings(d)
            sb.appendLine("① 0x1001 GetDeviceInfo ✅ ${d.size}B")
            if (txt.isNotEmpty()) {
                sb.appendLine("   厂商/型号: $txt")
                HexLog.info("诊断 · DeviceInfo", txt)
            }
        } else {
            sb.appendLine("① 0x1001 GetDeviceInfo ❌ ${devInfo.exceptionOrNull()?.message}")
        }

        // 2) DeviceReady 0x90C8
        val dr = runCatching { deviceReadyBlocking() }.getOrDefault(false)
        sb.appendLine("② 0x90C8 DeviceReady ${if (dr) "✅" else "❌/容忍"}")

        // 3) 能力查询 0x90CF
        val caps = runCatching { getPicCtrlCapability(Ptp.CUSTOM_SLOTS[0]) }.getOrNull()
        sb.appendLine(
            "③ 0x90CF PicCtrlCapability(0x00CB) " +
                if (caps?.isSuccess == true) "✅ ${caps.getOrNull()?.size}B" else "❌ ${caps?.exceptionOrNull()?.message}"
        )

        // 4) 0x90CC 两种参数形态
        val savedMode = singleParamMode
        singleParamMode = false
        val twoParam = getPicCtrlData(Ptp.CUSTOM_SLOTS[0], timeoutMs = 5000)
        singleParamMode = true
        val oneParam = getPicCtrlData(Ptp.CUSTOM_SLOTS[0], timeoutMs = 5000)
        singleParamMode = savedMode
        sb.appendLine(
            "④ 0x90CC 读 C-1：" +
                "两参=" + if (twoParam.isSuccess) "✅${twoParam.getOrNull()?.size}B" else "❌${twoParam.exceptionOrNull()?.message}" +
                " / 单参=" + if (oneParam.isSuccess) "✅${oneParam.getOrNull()?.size}B" else "❌${oneParam.exceptionOrNull()?.message}"
        )

        val out = sb.toString().trim()
        HexLog.info("诊断结果", out.replace("\n", " | "))
        Result.success(out)
    }

    /** 从 GetDeviceInfo 数据集里抠出可打印的中英文型号串。 */
    private fun parseDeviceInfoStrings(d: ByteArray): String {
        // Dataset: 前若干为定长字段，之后 PTP 字符串 = u8 长度 + UTF-16LE + 0x00
        val out = StringBuilder()
        var i = 0
        while (i < d.size - 1 && out.length < 120) {
            val len = d[i].toInt() and 0xFF
            if (len in 1..63 && i + 1 + len * 2 <= d.size) {
                val chars = CharArray(len - 1)
                var ok = true
                for (c in 0 until len - 1) {
                    val lo = d[i + 1 + c * 2].toInt() and 0xFF
                    val hi = d[i + 2 + c * 2].toInt() and 0xFF
                    val code = lo or (hi shl 8)
                    if (code == 0) { ok = false; break }
                    chars[c] = code.toChar()
                }
                if (ok) {
                    val s = String(chars).trim { it <= ' ' || it == '\u0000' }
                    if (s.length >= 2 && s.all { it.code in 0x20..0x7E || it.code > 0x2000 }) {
                        if (out.isNotEmpty()) out.append(" / ")
                        out.append(s)
                    }
                }
                i += 1 + len * 2
            } else {
                i++
            }
        }
        return out.toString()
    }

    /** 读取设备信息字符串（标准 0x1001），用于调试台确认握手成功。 */
    suspend fun getDeviceInfoRaw(): Result<ByteArray> {
        val r = transact(Ptp.OP_GET_DEVICE_INFO, IntArray(0), null, expectData = true)
        return if (r.ok && r.data != null) Result.success(r.data)
        else Result.failure(PtpException(r.responseCode, "GetDeviceInfo 失败"))
    }

    // ---------------- 实时取景 ----------------

    /** 实时取景是否在跑（相机被独占操作时用来恢复）。 */
    @Volatile
    var liveViewRunning: Boolean = false
        private set

    /** 启动实时取景（0x9201）。 */
    suspend fun startLiveView(): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_START_LIVE_VIEW, IntArray(0), null, expectData = false, timeoutMs = 10000)
        if (r.ok) {
            liveViewRunning = true
            HexLog.info("StartLiveView 0x9201", "OK")
            return Result.success(Unit)
        }
        return Result.failure(
            PtpException(
                r.responseCode,
                "StartLiveView 失败: 0x%04X %s".format(r.responseCode, Ptp.responseName(r.responseCode)),
            ),
        )
    }

    /** 停止实时取景（0x9202），失败不抛。 */
    suspend fun endLiveView(): Result<Unit> {
        liveViewRunning = false
        lastLiveViewHeader=byteArrayOf()
        val r = runCatching {
            transact(Ptp.OP_NIKON_END_LIVE_VIEW, IntArray(0), null, expectData = false, timeoutMs = 5000)
        }.getOrNull()
        if (r == null || r.ok) {
            HexLog.info("EndLiveView 0x9202", if (r == null) "无响应" else "OK")
            return Result.success(Unit)
        }
        return Result.failure(PtpException(r.responseCode, "EndLiveView 失败"))
    }

    /** Read-only frame geometry evidence; never store camera JPEGs or infer coordinates from model names. */
    @Volatile var lastLiveViewHeader:ByteArray=byteArrayOf();private set
    /** 取一帧实时取景（0x9203），返回 JPEG 字节。 */
    suspend fun getLiveViewFrame(): Result<ByteArray> {
        val r = transact(Ptp.OP_NIKON_GET_LIVE_VIEW_IMG, IntArray(0), null, expectData = true, timeoutMs = 12000)
        val d = r.data
        if (!r.ok || d == null) {
            return Result.failure(
                PtpException(r.responseCode, "GetLiveViewImg 失败: 0x%04X".format(r.responseCode)),
            )
        }
        val headerEnd=(0 until d.size-1).firstOrNull{(d[it].toInt()and 255)==255 && (d[it+1].toInt()and 255)==216}?:0
        lastLiveViewHeader=d.copyOfRange(0,minOf(128,headerEnd))
        val jpeg = extractJpeg(d)
            ?: return Result.failure(IOException("返回数据中没有 JPEG（${d.size}B）"))
        return Result.success(jpeg)
    }

    /** 从任意二进制里截出 FFD8..FFD9 的 JPEG 段。 */
    private fun extractJpeg(data: ByteArray): ByteArray? {
        var start = -1
        for (i in 0 until data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                start = i
                break
            }
        }
        if (start < 0) return null
        var end = -1
        var i = data.size - 2
        while (i > start) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD9) {
                end = i + 2
                break
            }
            i--
        }
        if (end <= start) return null
        return data.copyOfRange(start, end)
    }

    /**
     * 轮询 DeviceReady（0x90C8）直到机身空闲或超时。
     *
     * 为什么需要它：刚停掉实时取景、刚写完属性、刚拍完一张，机身内部还在收尾，
     * 这期间发 0x9207 / 0x90CC 会被拒（`0x200F` / `0x2019`）。ZENCHE 在拍摄前
     * 固定 `waitUntilDeviceReady(6000)`（ref/zenche/PtpCamera.java:596 起），我们之前缺这一步。
     *
     * @return true = 拿到就绪应答
     */
    suspend fun waitUntilDeviceReady(timeoutMs: Int = 6000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            round++
            val ok = withContext(Dispatchers.IO) {
                ioMutex.withLock { deviceReady() }
            }
            if (ok) {
                if (round > 1) HexLog.info("DeviceReady", "第 $round 轮就绪")
                return@withContext true
            }
            delay(180)
        }
        HexLog.warn("DeviceReady", "${timeoutMs}ms 内未就绪，继续尝试后续操作")
        false
    }

    /** 切换相机模式（0x90C2）：1 = 进入 PC 控制模式，0 = 退出。 */
    suspend fun changeCameraMode(on: Boolean): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_CHANGE_CAMERA_MODE, intArrayOf(if (on) 1 else 0), null, expectData = false)
        if (r.ok) return Result.success(Unit)
        return Result.failure(PtpException(r.responseCode, "ChangeCameraMode 失败: 0x%04X".format(r.responseCode)))
    }

    // ================= 设备属性（曝光参数读写） =================

    /** Read-only native burst audit. No release, property write, or mode switch. */
    suspend fun probeNativeBurstReadOnly() {
        val raw = getDeviceInfoRaw().getOrNull()
        val ops = raw?.let { bytes -> runCatching {
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.short; b.int; b.short
            val chars = b.get().toInt() and 255
            require(chars * 2 + 2 <= b.remaining())
            b.position(b.position() + chars * 2); b.short
            val count = b.int
            require(count in 0..2048 && count * 2 <= b.remaining())
            buildSet { repeat(count) { add(b.short.toInt() and 65535) } }
        }.getOrNull() }
        HexLog.info("NativeBurstProbe", "READ_ONLY operations=" +
            (ops?.sorted()?.joinToString(",") { "0x%04X".format(it) } ?: "unavailable"))
        for (code in listOf(0x5013, 0x5018, 0xD0F6, 0xD069)) {
            if (!isOpen) break
            val result = getDevicePropDesc(code, timeoutMs = 2000)
            HexLog.info("NativeBurstProbe", "READ_ONLY desc 0x%04X=".format(code) +
                (result.getOrNull()?.toHex(128) ?: result.exceptionOrNull()?.message ?: "unavailable"))
        }
    }

    /** 读一个 DeviceProp 的原始值（0x1015）。 */
    suspend fun getDeviceProp(code: Int, timeoutMs: Int = 4000): Result<ByteArray> {
        val r = transact(Ptp.OP_GET_DEVICE_PROP_VALUE, intArrayOf(code), null, expectData = true, timeoutMs = timeoutMs)
        val d = r.data
        if (!r.ok || d == null) {
            return Result.failure(PtpException(r.responseCode, "GetDeviceProp 0x%04X 失败: %s".format(code, Ptp.responseName(r.responseCode))))
        }
        return Result.success(d)
    }

    /**
     * 写一个 DeviceProp 的原始值（0x1016）。
     *
     * **为什么不能只发一次**（真机证据 + 参考实现）：
     * - 用户在 A 档、实时取景开着时点「下发」，`0x500E` 直接回 `0x200F AccessDenied`，
     *   但同一批里的光圈/ISO 却写成功了 —— 说明通道是通的，是**取景独占**把它挡掉了。
     * - ZENCHE `PtpCamera.setParameter()`（ref/zenche/PtpCamera.java:790）的做法是：
     *   先把 `liveView` 停掉 → 最多重试 5 次、把 `0x200F`/`0x201C` 当瞬时错误退避
     *   `200*(attempt+1) ms` → finally 里恢复取景。这里照做。
     *
     * @param keepLiveView 写完后是否恢复实时取景（默认恢复；批量下发时由外层统一管）
     */
    suspend fun setDeviceProp(
        code: Int,
        value: ByteArray,
        timeoutMs: Int = 8000,
        keepLiveView: Boolean = true,
    ): Result<Unit> {
        val wasLive = liveViewRunning
        // 1) 停取景：尼康在 LV 期间把机身控制面锁给取景流，写属性会被判无权限
        if (wasLive) {
            runCatching { endLiveView() }
            delay(120)
        }
        var last: PtpException? = null
        try {
            // 2) 最多 5 次；只有 0x200F / 0x201C / 0x2019 这类瞬时错误才值得重试
            repeat(5) { attempt ->
                val r = runCatching {
                    transact(Ptp.OP_SET_DEVICE_PROP_VALUE, intArrayOf(code), value, expectData = false, timeoutMs = timeoutMs)
                }.getOrNull()
                if (r == null) {
                    last = PtpException(-1, "SetDeviceProp 0x%04X 传输异常".format(code))
                } else if (r.ok) {
                    HexLog.info("SetDeviceProp", "0x%04X OK%s".format(code, if (attempt > 0) "（第 ${attempt + 1} 次重试）" else ""))
                    return Result.success(Unit)
                } else {
                    last = PtpException(
                        r.responseCode,
                        "SetDeviceProp 0x%04X 失败: %s".format(code, Ptp.responseName(r.responseCode)),
                    )
                    if (!isTransientPropError(r.responseCode)) return Result.failure(last!!)
                }
                if (attempt < 4) {
                    HexLog.warn("SetDeviceProp", "0x%04X 第 ${attempt + 1} 次失败（%s），退避重试".format(code, last?.message ?: ""))
                    runCatching { deviceReadyBlocking() }
                    delay(200L * (attempt + 1))
                }
            }
            HexLog.warn("SetDeviceProp", "0x%04X 失败: %s  value=%s".format(code, Ptp.responseName(last?.responseCode ?: -1), value.toHex(16)))
            return Result.failure(last ?: PtpException(-1, "SetDeviceProp 0x%04X 失败".format(code)))
        } finally {
            // 3) 恢复取景：只有本来就是开着、且调用方没要求保持关闭时才恢复
            if (wasLive && keepLiveView && autoResumeLiveView) {
                delay(80)
                runCatching { startLiveView() }
            }
        }
    }

    /**
     * 批量下发参数时把「每条都停/开一次取景」压成「一次」。
     *
     * 7 个属性各停开一次取景 = 14 次 0x9202/0x9201，机身会明显卡顿、还容易中途被拒；
     * 关掉这个开关后由调用方在 batch 外层统一停、统一恢复。
     */
    @Volatile
    var autoResumeLiveView: Boolean = true

    /**
     * 瞬时错误判定：尼康在写卡 / 取景切换的瞬间会短暂拒绝属性写入，
     * 立刻重试就能过。参考 ZENCHE `isTransientPtpError`（0x200F、0x201C）。
     */
    private fun isTransientPropError(code: Int): Boolean =
        code == Ptp.RC_ACCESS_DENIED ||
            code == Ptp.RC_DEVICE_BUSY ||
            code == 0x201C ||
            code == Ptp.RC_INVALID_OBJECT_HANDLE

    // ================= 属性描述符诊断（0x1014） =================

    /**
     * 读设备属性描述符（0x1014）。
     *
     * **为什么需要它**：真机上 `0x500E`（曝光程序）回 `0x200F AccessDenied`，但同一批里的
     * 光圈 / ISO 却写成功了。光看错误码分不清「这条属性在机身上本来就只读」还是
     * 「当前状态被锁」——描述符是机身自己给出的权威答案。
     *
     * 返回数据前 5 字节是 PTP 标准结构：`[0..1]=属性码 [2..3]=数据类型 [4]=GetSet`，
     * `GetSet` 为 0 表示只读、1 表示读写。ZENCHE `refreshParameterCapabilities()`
     * （`ref/zenche/PtpCamera.java:1430`）用的判据也是 `descriptor.length >= 5 && descriptor[4] != 0`。
     */
    suspend fun getDevicePropDesc(code: Int, timeoutMs: Int = 4000): Result<ByteArray> {
        val r = transact(Ptp.OP_GET_DEVICE_PROP_DESC, intArrayOf(code), null, expectData = true, timeoutMs = timeoutMs)
        val d = r.data
        if (!r.ok || d == null || d.size < 5) {
            return Result.failure(
                PtpException(r.responseCode, "GetDevicePropDesc 0x%04X 失败: %s".format(code, Ptp.responseName(r.responseCode)))
            )
        }
        return Result.success(d)
    }

    /**
     * 一条属性的可写性结论。
     *
     * [writable] 为 null 表示描述符读不到（机身不支持 0x1014，或这条属性压根没实现）。
     */
    data class PropProbe(
        val code: Int,
        val label: String,
        val writable: Boolean?,
        val currentHex: String,
        val note: String,
    )

    /**
     * 把「这 8 条属性到底能不能写」一次问清机身。
     *
     * 全程只读：只发 0x1015（读当前值）和 0x1014（读描述符），不改任何机身状态，
     * 可以在任何连接状态下安全点。
     */
    suspend fun probeWritableProps(): List<PropProbe> = withContext(Dispatchers.IO) {
        val items = listOf(
            Ptp.DPC_EXPOSURE_PROGRAM_MODE to "曝光程序 0x500E",
            Ptp.DPC_ISO to "ISO 0x500F",
            Ptp.DPC_F_NUMBER to "光圈 0x5007",
            Ptp.DPC_EXPOSURE_TIME to "快门(标准) 0x500D",
            Ptp.DPC_NIKON_EXPOSURE_TIME to "快门(尼康) 0xD100",
            Ptp.DPC_EXPOSURE_BIAS_COMPENSATION to "曝光补偿 0x5010",
            Ptp.DPC_WHITE_BALANCE to "白平衡 0x5005",
            Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM to "优化校准项 0xD200",
        )
        items.map { (code, label) ->
            val cur = getDeviceProp(code).getOrNull()?.toHex(8) ?: "(读不到)"
            val desc = getDevicePropDesc(code)
            val bytes = desc.getOrNull()
            val writable = bytes?.let { (it[4].toInt() and 0xFF) != 0 }
            val note = when {
                bytes == null -> desc.exceptionOrNull()?.message ?: "描述符不可用"
                writable == true -> "GetSet=读写"
                else -> "GetSet=只读"
            }
            PropProbe(code, label, writable, cur, note)
        }
    }

    private fun u16Le(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun i16Le(v: Int): ByteArray = u16Le(v and 0xFFFF)
    private fun u32Le(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** 曝光程序：1=M 2=P 3=A 4=S（0x500E）。 */
    suspend fun setExposureProgram(program: Int): Result<Unit> =
        setDeviceProp(Ptp.DPC_EXPOSURE_PROGRAM_MODE, u16Le(program))

    /** ISO（0x500F，u16 放大 1 倍）。 */
    suspend fun setIso(iso: Int): Result<Unit> =
        setDeviceProp(Ptp.DPC_ISO, u16Le(iso.coerceIn(32, 409600)))

    /** 光圈（0x5007，u16 = F 值 × 100）。 */
    suspend fun setAperture(fNumber: Double): Result<Unit> =
        setDeviceProp(Ptp.DPC_F_NUMBER, u16Le(Math.round(fNumber * 100).toInt().coerceIn(100, 6400)))

    /** 曝光补偿（0x5010，i16 = EV × 1000）。 */
    suspend fun setExposureCompensation(ev: Double): Result<Unit> =
        setDeviceProp(Ptp.DPC_EXPOSURE_BIAS_COMPENSATION, i16Le(Math.round(ev * 1000).toInt().coerceIn(-5000, 5000)))

    /** 白平衡：0x0002 = 自动，0x8013 = 手动预设。 */
    suspend fun setWhiteBalance(mode: Int): Result<Unit> =
        setDeviceProp(Ptp.DPC_WHITE_BALANCE, u16Le(mode))

    /** 快门速度。优先写尼康私有 0xD100（分子/分母打包 u32），失败退回标准 0x500D（0.1ms 单位）。 */
    suspend fun setShutterSeconds(seconds: Double): Result<Unit> {
        if (!seconds.isFinite() || seconds <= 0.0) {
            return Result.failure(IllegalArgumentException("快门速度必须大于 0"))
        }
        val ratio = shutterRatio(seconds)
        val nikon = setDeviceProp(Ptp.DPC_NIKON_EXPOSURE_TIME, u32Le(ratio))
        if (nikon.isSuccess) return nikon
        val standard = setDeviceProp(Ptp.DPC_EXPOSURE_TIME, u32Le(Math.round(seconds * 10_000)))
        return standard
    }

    /** 把秒数转成尼康的 (分子 << 16 | 分母) 编码。 */
    private fun shutterRatio(seconds: Double): Long {
        var num: Long
        var den: Long
        if (seconds < 1.0) {
            num = 1
            den = Math.round(1.0 / seconds).coerceAtLeast(1)
        } else if (Math.abs(seconds - Math.rint(seconds)) < 1e-6) {
            num = Math.round(seconds)
            den = 1
        } else {
            den = 1000
            num = Math.round(seconds * den)
            var a = num
            var b = den
            while (b != 0L) { val t = a % b; a = b; b = t }
            val g = a.coerceAtLeast(1)
            num /= g
            den /= g
        }
        num = num.coerceIn(1, 0xFFFF)
        den = den.coerceIn(1, 0xFFFF)
        return (num shl 16) or den
    }

    /** 切换机身当前使用的优化校准项（0xD200）：1=标准 2=自然 3=鲜艳 4=单色 5=人像 6=风景 7=平直 8=自动。 */
    suspend fun setActivePictureControl(item: Int): Result<Unit> =
        setDeviceProp(Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM, u16Le(item))

    // ================= 对焦 / 事件 =================

    /** GetEvent（0x90C7）：拿相机事件队列。 */
    suspend fun getEvent(timeoutMs: Int = 3000): Result<ByteArray> {
        val r = transact(Ptp.OP_NIKON_GET_EVENT, IntArray(0), null, expectData = true, timeoutMs = timeoutMs)
        val d = r.data
        if (!r.ok || d == null) {
            return Result.failure(PtpException(r.responseCode, "GetEvent 失败: ${Ptp.responseName(r.responseCode)}"))
        }
        return Result.success(d)
    }

    /** i-menu: one explicit confirmed write, fresh typed descriptor + enumeration + session + readback. */
    suspend fun setCameraMenuOnce(id:String,value:com.zbox.ai.core.CameraIMenuPolicy.Value,token:Any?):Result<Unit>{
        val policy=com.zbox.ai.core.CameraIMenuPolicy
        val def=policy.definitions.firstOrNull{it.id==id && it.writeCode!=0}?:return Result.failure(IOException("未知或只读菜单项"))
        if(token==null || token !== burstConnectionToken || !isOpen)return Result.failure(IOException("菜单会话已失效"))
        val raw=getDevicePropDesc(def.writeCode).getOrElse{return Result.failure(it)}
        val desc=policy.descriptor(raw,def.writeCode)?:return Result.failure(IOException("属性类型未识别，禁止写入"))
        val encoded=policy.encode(desc,value)?:return Result.failure(IOException("机身未允许这个值，禁止写入"))
        val readDesc=if(def.readCode==def.writeCode)desc else policy.descriptor(getDevicePropDesc(def.readCode).getOrElse{return Result.failure(it)},def.readCode)?:return Result.failure(IOException("未核实读回类型"))
        if(readDesc.type!=desc.type)return Result.failure(IOException("读写属性类型不一致"))
        if(token !== burstConnectionToken || !isOpen)return Result.failure(IOException("菜单会话已改变"))
        val before=getDeviceProp(def.readCode).getOrNull()?.let{policy.value(it,readDesc.type)}
        if(before==null)return Result.failure(IOException("无法读取当前值，禁止写入"))
        if(token !== burstConnectionToken || !isOpen)return Result.failure(IOException("菜单会话已改变"))
        if(before==value)return Result.success(Unit)
        val response=transact(Ptp.OP_SET_DEVICE_PROP_VALUE,intArrayOf(def.writeCode),encoded,expectData=false,timeoutMs=6000,expectedConnection=token)
        if(!response.ok)return Result.failure(PtpException(response.responseCode,"机身拒绝修改：${Ptp.responseName(response.responseCode)}"))
        if(token !== burstConnectionToken)return Result.failure(IOException("写入后会话改变，尚未确认"))
        val actual=getDeviceProp(def.readCode).getOrNull()?.let{policy.value(it,readDesc.type)}
        if(token !== burstConnectionToken || !isOpen)return Result.failure(IOException("读回后会话改变，不能标为成功"))
        return if(actual==value)Result.success(Unit)else Result.failure(IOException("未获得匹配的机身读回，不能标为成功"))
    }

    /** ChangeAfArea only. No AF/shutter or retry, and no stale-session writes. */
    suspend fun changeAfArea(x:Int,y:Int,expectedSession:Any?):Result<Unit>{
        if(expectedSession==null || expectedSession !== burstConnectionToken || !isOpen || !liveViewRunning)return Result.failure(IOException("触点对焦会话已失效"))
        if(0x9205 !in _info.value.supportedOperations)return Result.failure(IOException("相机未通告移动对焦区域"))
        val h=lastLiveViewHeader
        if(h.size<32)return Result.failure(IOException("未取得取景坐标"))
        fun u(i:Int)=((h[i].toInt()and 255)shl 8)or(h[i+1].toInt()and 255)
        val g=com.zbox.ai.core.TouchFocusPolicy.geometry(h,u(8),u(10))?:return Result.failure(IOException("当前取景坐标/放大状态未验证"))
        if(x !in 0 until g.width || y !in 0 until g.height)return Result.failure(IOException("触点坐标越界"))
        val response=transact(0x9205,intArrayOf(x,y),null,expectData=false,timeoutMs=6000)
        return if(response.ok)Result.success(Unit)else Result.failure(PtpException(response.responseCode,"移动对焦区域失败: ${Ptp.responseName(response.responseCode)}"))
    }
    /** Explicitly confirmed AF-area change only. One write, descriptor/readback guarded; no exposure writes. */
    suspend fun setTouchAfMode(value:Int,expectedSession:Any?,shouldResume:()->Boolean={true}):Result<Unit>{
        if(expectedSession==null || expectedSession !== burstConnectionToken || !isOpen)return Result.failure(IOException("对焦会话失效"))
        if(value !in listOf(0x8010,0x8011))return Result.failure(IOException("该AF区域值未验证"))
        val descriptor=getDevicePropDesc(0xd05d).getOrElse{return Result.failure(it)}
        val mode=com.zbox.ai.core.TouchFocusPolicy.afModeDescriptor(descriptor)?:return Result.failure(IOException("AF区域描述符未知"))
        if(!mode.writable || value !in mode.values)return Result.failure(IOException("机身当前不允许该AF区域设置"))
        if(mode.current==value)return Result.success(Unit)
        val live=liveViewRunning
        if(live){endLiveView().getOrElse{return Result.failure(it)};delay(120)}
        return try{
            val response=transact(Ptp.OP_SET_DEVICE_PROP_VALUE,intArrayOf(0xd05d),byteArrayOf((value and 255).toByte(),(value shr 8).toByte()),expectData=false,timeoutMs=6000)
            if(!response.ok)Result.failure(PtpException(response.responseCode,"切换AF区域失败: ${Ptp.responseName(response.responseCode)}"))
            else{val read=getDeviceProp(0xd05d).getOrThrow();if(read.size>=2 && ((read[0].toInt()and 255)or((read[1].toInt()and 255)shl 8))==value)Result.success(Unit)else Result.failure(IOException("AF区域设置未得到机身读回确认"))}
        }catch(e:Exception){Result.failure(e)}finally{if(live && expectedSession === burstConnectionToken && shouldResume()){startLiveView();getLiveViewFrame()}}
    }

    /** One AF transaction only for the touch-focus chain; no automatic busy retry. */
    suspend fun touchAfDrive(expectedSession:Any?):Result<Unit>{
        if(expectedSession==null || expectedSession !== burstConnectionToken || !isOpen || !liveViewRunning)return Result.failure(IOException("触点对焦会话已失效"))
        val response=transact(Ptp.OP_NIKON_AF_DRIVE,IntArray(0),null,expectData=false,timeoutMs=8000)
        return if(response.ok)Result.success(Unit)else Result.failure(PtpException(response.responseCode,"AF失败: ${Ptp.responseName(response.responseCode)}"))
    }

    /** AfDrive（0x90C1）：触发一次单次自动对焦。 */
    suspend fun afDrive(): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_AF_DRIVE, IntArray(0), null, expectData = false, timeoutMs = 8000)
        if (r.ok) return Result.success(Unit)
        // 部分机型在实时取景里 AfDrive 会回 DeviceBusy，重试一次
        if (r.responseCode == Ptp.RC_DEVICE_BUSY) {
            Thread.sleep(200)
            val again = transact(Ptp.OP_NIKON_AF_DRIVE, IntArray(0), null, expectData = false, timeoutMs = 8000)
            if (again.ok) return Result.success(Unit)
            return Result.failure(PtpException(again.responseCode, "AfDrive 忙: ${Ptp.responseName(again.responseCode)}"))
        }
        return Result.failure(PtpException(r.responseCode, "AfDrive 失败: ${Ptp.responseName(r.responseCode)}"))
    }

    /** MfDrive（0x9204）：step 为负数往近，正数往远。 */
    suspend fun manualFocus(step: Int): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_MF_DRIVE, intArrayOf(step), null, expectData = false, timeoutMs = 6000)
        if (r.ok) return Result.success(Unit)
        return Result.failure(PtpException(r.responseCode, "MfDrive 失败: ${Ptp.responseName(r.responseCode)}"))
    }

    // ================= 拍摄 =================

    /**
     * 0x9207 是 Nikon InitiateCaptureRecInMedia：第二参数 1 指向临时 SDRAM，0 指向存储卡。
     * 为确保快门照片出现在机身回放和相册里，拍摄前后比较卡内句柄，并返回新增的 JPEG/NEF 句柄。
     */
    suspend fun captureToCard(timeoutMs: Int = 60_000): Result<Long> {
        return try {
            waitUntilDeviceReady(6000)
            val storageIds = getStorageIds().getOrElse { return Result.failure(it) }
            if (storageIds.isEmpty()) return Result.failure(IOException("没有可写入的相机存储卡"))
            ensureNikonStaDirectReadMode(storageIds)

            val before = linkedMapOf<Long, Set<Long>>()
            for (sid in storageIds) {
                val handles = getObjectHandles(sid, 0L).getOrElse { return Result.failure(it) }
                before[sid] = handles.toSet()
            }

            val cap = transact(
                Ptp.OP_NIKON_CAPTURE_TO_MEDIA,
                intArrayOf(0xFFFFFFFF.toInt(), 0),
                null, expectData = false, timeoutMs = timeoutMs,
            )
            if (!cap.ok) {
                return Result.failure(PtpException(cap.responseCode, "拍摄到相机卡失败: ${Ptp.responseName(cap.responseCode)}"))
            }
            HexLog.info("拍摄", "0x9207 已指定相机卡；等待新增 JPEG/NEF 句柄")

            val deadline = System.currentTimeMillis() + 30_000
            val newMedia = linkedMapOf<Long, NikonMediaKind>()
            suspend fun collectNewMedia() {
                for (sid in storageIds.asReversed()) {
                    val handles = getObjectHandles(sid, 0L).getOrElse { throw it }
                    for (handle in handles.asReversed()) {
                        if (handle in before[sid].orEmpty() || handle in newMedia) continue
                        val kind = captureCardMediaKind(handle) ?: continue
                        if (kind == NikonMediaKind.JPEG || kind == NikonMediaKind.RAW) {
                            newMedia[handle] = kind
                        }
                    }
                }
            }

            var pollDelayMs = 250L
            while (System.currentTimeMillis() < deadline) {
                collectNewMedia()
                val candidate = newMedia.entries.firstOrNull { it.value == NikonMediaKind.JPEG }?.key
                    ?: newMedia.entries.firstOrNull { it.value == NikonMediaKind.RAW }?.key
                if (candidate != null) {
                    val ready = try {
                        deviceReadyBlocking()
                    } catch (error: Exception) {
                        if (!isOpen) throw error
                        false
                    }
                    if (ready) {
                        // A paired JPEG can be registered just after the NEF. Re-scan once
                        // after DeviceReady before choosing the embedded-preview RAW fallback.
                        delay(300)
                        collectNewMedia()
                        val selected = newMedia.entries.firstOrNull { it.value == NikonMediaKind.JPEG }?.key
                            ?: newMedia.entries.firstOrNull { it.value == NikonMediaKind.RAW }?.key
                            ?: candidate
                        HexLog.info("拍摄", "新卡对象已确认：0x%08X（%s）".format(selected, newMedia[selected]))
                        return Result.success(selected)
                    }
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                delay(minOf(pollDelayMs, remain))
                pollDelayMs = (pollDelayMs * 2).coerceAtMost(1200L)
            }

            val lastSeen = newMedia.entries.firstOrNull { it.value == NikonMediaKind.JPEG }?.key
                ?: newMedia.entries.firstOrNull { it.value == NikonMediaKind.RAW }?.key
            if (lastSeen != null) {
                HexLog.warn("拍摄", "已发现新卡对象 0x%08X，但机身 30 秒内未报告就绪；继续尝试回传".format(lastSeen))
                Result.success(lastSeen)
            } else {
                Result.failure(IOException("快门命令已接受，但 30 秒内未在相机卡发现新增 JPEG/NEF；未确认落卡"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun captureCardMediaKind(handle: Long): NikonMediaKind? {
        when (val kind = NikonStaDirectRead.formatFromHandle(handle.toInt())) {
            NikonMediaKind.JPEG, NikonMediaKind.RAW -> return kind
            NikonMediaKind.VIDEO, NikonMediaKind.OTHER -> return null
            null -> Unit
        }
        if (ptpIpTransport != null && staDirectAlbumMode) {
            HexLog.warn("拍摄", "STA 模式遇到未知对象句柄 0x%08X；跳过会被 0x200F 拒绝的 GetObjectInfo".format(handle))
            return null
        }
        val infoResult = getObjectInfo(handle)
        val info = infoResult.getOrNull()
        if (info == null) {
            val error = infoResult.exceptionOrNull()
            if (ptpIpTransport != null &&
                (error as? PtpException)?.responseCode == Ptp.RC_ACCESS_DENIED
            ) {
                staDirectAlbumMode = true
                HexLog.warn("拍摄", "GetObjectInfo 被 Nikon STA 锁定（0x200F）；后续使用 0x9421/0x9431")
            }
            return null
        }
        val extension = info.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "jpg" || extension == "jpeg" -> NikonMediaKind.JPEG
            extension == "nef" || extension == "nrw" || extension == "dng" -> NikonMediaKind.RAW
            else -> null
        }
    }

    /** Download a newly-created card object, retrying only the camera's short write-card window. */
    suspend fun downloadCardObject(handle: Long, timeoutMs: Int = 60_000): Result<ByteArray> {
        var lastError: Throwable = IOException("相机卡对象尚未可读")
        val deadline = System.currentTimeMillis() + 20_000
        var attempt = 0
        while (true) {
            attempt++
            val result = try {
                getObject(handle, timeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.getOrNull()?.let { return Result.success(it) }
            lastError = result.exceptionOrNull() ?: lastError
            val code = (lastError as? PtpException)?.responseCode
            if (code != Ptp.RC_INVALID_OBJECT_HANDLE) return Result.failure(lastError)
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            val wait = minOf(300L shl minOf(attempt - 1, 4), 2000L, remain)
            HexLog.warn("拍摄", "新卡对象 0x%08X 仍在写入（第 %d 次），%d ms 后重试".format(handle, attempt, wait))
            delay(wait)
            runCatching { deviceReadyBlocking() }
        }
        return Result.failure(lastError)
    }

    /**
     * 拍一张到机身 SDRAM（0x9207，params = (0xFFFFFFFF, 1)），返回可下载的对象句柄。
     *
     * 流程参照 ZENCHE：DeviceReady → CaptureToSdram → 轮询 GetEvent 找
     * `OBJECT_ADDED_IN_SDRAM (0xC101)` 事件里的句柄 → 备用句柄 0xFFFF0001。
     */
    suspend fun captureToSdram(timeoutMs: Int = 60_000): Result<Long> {
        // 先等机身空闲：刚停取景/刚写完属性时立即发 0x9207 会被 0x200F 挡掉
        waitUntilDeviceReady(6000)
        val cap = transact(
            Ptp.OP_NIKON_CAPTURE_SDRAM,
            intArrayOf(0xFFFFFFFF.toInt(), 1),
            null, expectData = false, timeoutMs = timeoutMs,
        )
        if (!cap.ok) {
            return Result.failure(PtpException(cap.responseCode, "拍摄失败: ${Ptp.responseName(cap.responseCode)}"))
        }
        val deadline = System.currentTimeMillis() + 30_000
        var firstEventLogged = false
        while (System.currentTimeMillis() < deadline) {
            runCatching { deviceReadyBlocking() }
            val ev = getEvent(3000).getOrNull()
            // 首次拿到事件流就原样打进日志：万一 0xC101 的格式又和预期不同，
            // 用户发回来的 Hex 能直接定位（这是之前「拍完没图」查了三轮才查清的原因）
            if (!firstEventLogged && ev != null && ev.isNotEmpty()) {
                firstEventLogged = true
                HexLog.log(
                    HexLogEntry.Direction.RX, -1,
                    "GetEvent 0x90C7 原始事件流 ${ev.size}B",
                    ev,
                    note = "首 2 字节=事件个数，之后每 6 字节 = {u16 code, u32 param1}",
                )
            }
            val handle = ev?.let { findSdramObject(it) } ?: 0L
            if (handle != 0L) {
                HexLog.info("拍摄", "SDRAM 对象句柄 = 0x%08X".format(handle))
                return Result.success(handle)
            }
            delay(180)
        }
        // 拿不到事件也别急着用假句柄：先等机身把文件落盘，再给一次 GetEvent 的机会
        waitUntilDeviceReady(8000)
        val ev2 = getEvent(3000).getOrNull()
        val h2 = ev2?.let { findSdramObject(it) } ?: 0L
        if (h2 != 0L) {
            HexLog.info("拍摄", "补读事件拿到句柄 = 0x%08X".format(h2))
            return Result.success(h2)
        }
        HexLog.warn("拍摄", "未收到 0xC101 事件，回退到默认句柄 0xFFFF0001")
        return Result.success(0xFFFF0001L)
    }

    /**
     * 在事件流里找出 `OBJECT_ADDED_IN_SDRAM` 携带的对象句柄。
     *
     * **格式依据（这里之前写错过，导致「拍完永远显示不出照片」）**：
     * Nikon `GetEvent (0x90C7)` 返回的不是 PTP 标准容器流（u32 len + u16 type + u16 code），
     * 而是 `u16 事件个数` + N × `{u16 code, u32 param1}`，每个事件固定 6 字节。
     * 依据：libgphoto2 `ptp_unpack_Nikon_EC`（ptp-pack.c）与 ZENCHE
     * `PtpCamera.findSdramObject`（真机验证过）。按标准容器解析会一路错位，
     * 永远读不到 0xC101，于是每次都退到假句柄 0xFFFF0001，GetObject 只能回 0x2009。
     */
    private fun findSdramObject(events: ByteArray): Long {
        if (events.size < 2) return 0L
        val count = (events[0].toInt() and 0xFF) or ((events[1].toInt() and 0xFF) shl 8)
        if (count <= 0 || count > 64) return 0L
        var i = 2
        repeat(count) {
            if (i + 6 > events.size) return 0L
            val code = (events[i].toInt() and 0xFF) or ((events[i + 1].toInt() and 0xFF) shl 8)
            if (code == Ptp.EC_NIKON_OBJECT_ADDED_IN_SDRAM) {
                val p1 = CameraStorage.u32(events, i + 2)
                if (p1 != 0L) return p1
            }
            i += 6
        }
        return 0L
    }

    /**
     * 下载刚拍的对象，对 `0x2009 InvalidObjectHandle` 做有界退避重试。
     *
     * 相机按下快门到文件真正可读之间有几十到几百毫秒的写卡窗口，这段时间
     * GetObject 一定回 0x2009。只试一次会让用户以为「拍了但没存」。重试期间
     * 顺带再读一次 GetEvent——部分机型会在写完后用新的句柄替换临时句柄。
     */
    suspend fun downloadCaptured(handle: Long, timeoutMs: Int = 60_000): Result<ByteArray> {
        var h = handle
        val deadline = System.currentTimeMillis() + 20_000
        var lastErr: Throwable = IOException("下载拍摄对象失败")
        for (attempt in 1..9) {
            val r = getObject(h, timeoutMs)
            r.getOrNull()?.let { return Result.success(it) }
            val t = r.exceptionOrNull()
            if (t != null) lastErr = t
            val code = (t as? PtpException)?.responseCode ?: -1
            if (code != Ptp.RC_INVALID_OBJECT_HANDLE) break
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            val wait = minOf(300L shl minOf(attempt - 1, 4), 4000L, remain)
            HexLog.warn("拍摄", "对象 0x%08X 还在写卡（第 %d 次），%d ms 后重试".format(h, attempt, wait))
            delay(wait)
            runCatching { deviceReadyBlocking() }
            val ev = getEvent(3000).getOrNull()
            val nh = ev?.let { findSdramObject(it) } ?: 0L
            if (nh != 0L && nh != h) {
                HexLog.info("拍摄", "句柄刷新 0x%08X → 0x%08X".format(h, nh))
                h = nh
            }
        }
        return Result.failure(lastErr)
    }

    /** TerminateCapture（0x920C）：结束 B 门长曝光。 */
    suspend fun terminateCapture(): Result<Unit> {
        val r = transact(Ptp.OP_NIKON_TERMINATE_CAPTURE, intArrayOf(0, 0), null, expectData = false, timeoutMs = 15_000)
        if (r.ok) return Result.success(Unit)
        return Result.failure(PtpException(r.responseCode, "TerminateCapture 失败: ${Ptp.responseName(r.responseCode)}"))
    }

    @Volatile var movieRecordingMayBeActive: Boolean = false
        private set
    private var previousMovieSelector: Int? = null
    private var previousApplicationMode: Int? = null

    private suspend fun movieU8(code: Int): Int? = getDeviceProp(code).getOrNull()?.firstOrNull()?.toInt()?.and(255)

    private suspend fun setMovieU8(code: Int, value: Int) {
        val desc = getDevicePropDesc(code).getOrThrow()
        check(MoviePolicy.permitsU8(desc, code, value)) { "机身不允许切换 0x%04X，请在机身切换到录像模式".format(code) }
        setDeviceProp(code, byteArrayOf(value.toByte()), keepLiveView = false).getOrThrow()
        check(movieU8(code) == value) { "录像属性 0x%04X 回读不一致".format(code) }
    }

    private suspend fun restoreMovieMode() {
        runCatching { endLiveView() }
        previousMovieSelector?.let { old ->
            runCatching { setMovieU8(0xD1A6, old) }.onFailure { HexLog.warn("Movie", "取景模式恢复失败：${it.message}") }
        }
        previousMovieSelector = null
        previousApplicationMode?.let { old ->
            runCatching {
                val r = transact(0x9435, intArrayOf(old), null, expectData = false, timeoutMs = 6000)
                check(r.ok) { "应用模式恢复失败" }
            }.onFailure { HexLog.warn("Movie", it.message ?: "应用模式恢复失败") }
        }
        previousApplicationMode = null
    }

    /** Requires the VM to pause its frame worker before changing mode. No repeated movie-start command. */
    suspend fun startMovieRecording(): Result<Unit> {
        if (movieRecordingMayBeActive) return Result.failure(IllegalStateException("录像状态尚未确认，请先停止录像或检查机身"))
        return try {
            val ops = NativeBurstProtocol.operations(getDeviceInfoRaw().getOrThrow())
            check(0x920A in ops && 0x920B in ops) { "机身未声明远程录像指令" }
            val appMode = movieU8(0xD1F0)
            val selector = movieU8(0xD1A6)
            HexLog.info("Movie", "PREPARE application=$appMode selector=$selector")
            if (appMode != 1 && 0x9435 in ops) {
                check(0x9435 in ops) { "机身不允许切换录像应用模式" }
                // Only restore a value actually read from the camera; never infer that unknown means zero.
                previousApplicationMode = appMode
                val r = transact(0x9435, intArrayOf(1), null, expectData = false, timeoutMs = 6000)
                check(r.ok) { "切换录像应用模式失败：${Ptp.responseName(r.responseCode)}" }
                check(movieU8(0xD1F0)?.let { it == 1 } != false) { "录像应用模式回读失败" }
            }
            if (selector != null && selector != 1) {
                previousMovieSelector = selector
                setMovieU8(0xD1A6, 1)
            }
            startLiveView().getOrThrow()
            check(waitUntilDeviceReady(6000)) { "机身录像准备超时，请稍后重试" }
            val maskBytes = getDeviceProp(0xD0A4).getOrNull()
            val mask = maskBytes?.takeIf { it.size >= 4 }?.let { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
            HexLog.info("Movie", "PREREQUISITES prohibit=" + (mask?.let { "0x%08X".format(it) } ?: "unavailable"))
            check(mask == null || mask == 0L) { MoviePolicy.prohibitReason(mask!!) }
            // A lost ACK is ambiguous. Keep stop available rather than pretending no recording began.
            movieRecordingMayBeActive = true
            val response = transact(Ptp.OP_NIKON_START_MOVIE, IntArray(0), null, expectData = false, timeoutMs = 10000)
            if (!response.ok) {
                movieRecordingMayBeActive = false
                throw PtpException(response.responseCode, "开始录像失败：${Ptp.responseName(response.responseCode)}；请确认机身录像模式及存储卡")
            }
            HexLog.info("Movie", "START 0x920A OK")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HexLog.warn("Movie", "START FAILED ${e.message}; mayBeActive=$movieRecordingMayBeActive")
            Result.failure(e)
        } finally {
            if (!movieRecordingMayBeActive) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { restoreMovieMode() }
        }
    }

    suspend fun endMovieRecording(): Result<Unit> {
        return try {
            val r = transact(Ptp.OP_NIKON_END_MOVIE, IntArray(0), null, expectData = false, timeoutMs = 10000)
            if (!r.ok) return Result.failure(PtpException(r.responseCode, "停止录像未确认：${Ptp.responseName(r.responseCode)}，请检查机身，可重试停止"))
            movieRecordingMayBeActive = false
            waitUntilDeviceReady(10000)
            restoreMovieMode()
            HexLog.info("Movie", "STOP 0x920B OK")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    // ================= 存储 / 相册 =================

    suspend fun getStorageIds(): Result<List<Long>> {
        val r = transact(Ptp.OP_GET_STORAGE_IDS, IntArray(0), null, expectData = true, timeoutMs = 15_000)
        if (!r.ok) return Result.failure(PtpException(r.responseCode, "GetStorageIDs 失败: ${Ptp.responseName(r.responseCode)}"))
        return Result.success(CameraStorage.parseStorageIds(r.data))
    }

    suspend fun getStorageInfo(storageId: Long): Result<CameraStorage.Volume> {
        val r = transact(Ptp.OP_GET_STORAGE_INFO, intArrayOf(storageId.toInt()), null, expectData = true, timeoutMs = 15_000)
        if (!r.ok) return Result.failure(PtpException(r.responseCode, "GetStorageInfo 失败: ${Ptp.responseName(r.responseCode)}"))
        return Result.success(CameraStorage.parseStorageInfo(storageId, r.data))
    }

    suspend fun getObjectHandles(storageId: Long, parent: Long = 0L): Result<List<Long>> {
        val r = transact(
            Ptp.OP_GET_OBJECT_HANDLES,
            intArrayOf(storageId.toInt(), 0, parent.toInt()),
            null, expectData = true, timeoutMs = 30_000,
        )
        if (!r.ok) return Result.failure(PtpException(r.responseCode, "GetObjectHandles 失败: ${Ptp.responseName(r.responseCode)}"))
        return Result.success(CameraStorage.parseObjectHandles(r.data))
    }

    suspend fun getObjectInfo(handle: Long): Result<CameraStorage.Item?> {
        val r = transact(Ptp.OP_GET_OBJECT_INFO, intArrayOf(handle.toInt()), null, expectData = true, timeoutMs = 15_000)
        if (!r.ok) return Result.failure(PtpException(r.responseCode, "GetObjectInfo 失败: ${Ptp.responseName(r.responseCode)}"))
        return Result.success(CameraStorage.parseObjectInfo(handle, r.data))
    }

    suspend fun getThumb(handle: Long): Result<ByteArray> {
        if (staDirectAlbumMode && ptpIpTransport != null) {
            val direct = runCatching { getNikonStaThumbnail(handle) }.getOrNull()
            if (direct != null && direct.isNotEmpty()) return Result.success(direct)
            return Result.failure(IOException("尼康 STA 直读缩略图失败（句柄 0x%08X）".format(handle)))
        }
        val r = transact(Ptp.OP_GET_THUMB, intArrayOf(handle.toInt()), null, expectData = true, timeoutMs = 30_000)
        val d = r.data
        if (!r.ok || d == null) return Result.failure(PtpException(r.responseCode, "GetThumb 失败: ${Ptp.responseName(r.responseCode)}"))
        return Result.success(d)
    }

    /** Nikon STA-mode object size (0x9421), returned as a little-endian u64 data phase. */
    private suspend fun getNikonStaObjectSize(handle: Long): Long? {
        staObjectSizes[handle]?.let { return it }
        val r = transact(Ptp.OP_NIKON_GET_OBJECT_SIZE, intArrayOf(handle.toInt()), null, expectData = true, timeoutMs = 15_000)
        val bytes = r.data ?: return null
        if (!r.ok || bytes.size < 8) return null
        val size = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
        return size.takeIf { it > 0L }?.also { staObjectSizes[handle] = it }
    }

    /** Nikon 64-bit partial read (0x9431): handle, offset low/high, length low/high. */
    private suspend fun getNikonStaPartial(handle: Long, offset: Long, length: Int, timeoutMs: Int = 20_000): ByteArray? {
        if (length <= 0) return null
        val r = transact(
            Ptp.OP_NIKON_GET_PARTIAL_OBJECT_EX,
            intArrayOf(handle.toInt(), offset.toInt(), (offset ushr 32).toInt(), length, 0),
            null, expectData = true, timeoutMs = timeoutMs,
        )
        return r.data?.takeIf { r.ok && it.isNotEmpty() }
    }

    /**
     * Read only a small embedded JPEG for the album grid. Do not use 0x9530 here:
     * on the Z30 it can return a multi-megabyte preview despite a 10 KB chunk request,
     * monopolizing PTP/IP while the shutter or gallery is waiting.
     */
    private suspend fun getNikonStaThumbnail(handle: Long): ByteArray? {
        val head = getNikonStaPartial(handle, 0L, 256 * 1024) ?: return null
        val parsed = NikonStaDirectRead.parseHead(head)
        parsed.fileNumber?.let { staFileNumbers[handle] = it }
        val ranges = parsed.embeddedJpegs
        val selected = NikonStaDirectRead.pickGridJpeg(ranges, 8 * 1024L, 400 * 1024L)
            ?: return null
        val length = (selected.last - selected.first + 1).toInt()
        if (length !in 1..(400 * 1024)) return null
        val bytes = if (selected.first >= 0 && selected.last < head.size) {
            head.copyOfRange(selected.first.toInt(), selected.last.toInt() + 1)
        } else {
            getNikonStaPartial(handle, selected.first, length, 30_000) ?: return null
        }
        return bytes.takeIf { NikonStaDirectRead.isJpeg(it) }
    }

    /** 读文件头里的机身文件编号（DSC_xxxx 中的 xxxx），用来把占位名换成真实文件名；失败返回 null。 */
    suspend fun readFileNumber(handle: Long): Int? {
        if (!staDirectAlbumMode) return null
        staFileNumbers[handle]?.let { return it }
        val head = getNikonStaPartial(handle, 0L, 256 * 1024) ?: return null
        return NikonStaDirectRead.parseHead(head).fileNumber?.also { staFileNumbers[handle] = it }
    }

    private suspend fun getNikonStaObject(handle: Long, timeoutMs: Int): Result<ByteArray> {
        val size = getNikonStaObjectSize(handle)
            ?: return Result.failure(IOException("尼康 0x9421 未返回文件大小（句柄 0x%08X）".format(handle)))
        if (size > Int.MAX_VALUE) return Result.failure(IOException("文件过大，无法在内存中读取：$size bytes"))
        val result = ByteArray(size.toInt())
        // Z30 STA A/B: 16MiB was not consistently faster for a 10MiB JPEG; retain the smaller-file policy.
        // Larger objects can reduce command round trips with 16MiB; keep rejection/short-reply fallback.
        var chunkSize = when {
            size > 16L * 1024 * 1024 -> 16 * 1024 * 1024
            ptpIpFrequencyMhz in 4900..5895 || ptpIpFrequencyMhz >= 5925 -> 8 * 1024 * 1024
            else -> 4 * 1024 * 1024
        }
        val minimumChunk = 512 * 1024
        var offset = 0L
        var requests = 0
        HexLog.info("STA 原片传输", "开始 handle=0x%08X size=%d chunk=%dKiB cameraLink=%s".format(
            handle, size, chunkSize / 1024,
            if (ptpIpFrequencyMhz > 0) "${ptpIpFrequencyMhz}MHz" else "未知"))
        while (offset < size) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val want = minOf(chunkSize.toLong(), size - offset).toInt()
            requests++
            val chunk = getNikonStaPartial(handle, offset, want, timeoutMs)
            if (chunk == null || chunk.isEmpty()) {
                // Only completed, rejected/empty transactions can be retried at the SAME offset.
                // Socket timeout / framing errors close the session; never reuse that stream.
                if (!isOpen || chunkSize <= minimumChunk) {
                    return Result.failure(IOException("尼康 0x9431 分段读取失败：offset=$offset / size=$size"))
                }
                chunkSize = (chunkSize / 2).coerceAtLeast(minimumChunk)
                HexLog.warn("STA 原片传输", "相机拒绝/未返回数据，原 offset=$offset 降级为 ${chunkSize / 1024}KiB")
                continue
            }
            if (chunk.size > want) return Result.failure(IOException("尼康 0x9431 返回长度异常：${chunk.size}，请求 $want"))
            System.arraycopy(chunk, 0, result, offset.toInt(), chunk.size)
            offset += chunk.size
            if (chunk.size < want && offset < size) {
                // Some cameras cap partial responses despite accepting a larger request.
                // Advance only by verified bytes; do not skip the missing tail.
                chunkSize = minOf(chunkSize, chunk.size)
                HexLog.warn("STA 原片传输", "相机限制块长为 ${chunk.size}B，按实际 offset=$offset 继续")
            }
        }
        if (offset != size || result.size.toLong() != size) return Result.failure(IOException("原片长度校验失败：$offset / $size"))
        HexLog.info("STA 原片传输", "完成 handle=0x%08X bytes=%d requests=%d finalChunk=%dKiB".format(handle, offset, requests, chunkSize / 1024))
        return Result.success(result)
    }

    /**
     * 从存储卡尾部取最新对象，供用户用机身实体快门拍摄后手动回传预览。
     * App 快门不走此方法，而由 [captureToCard] 返回本次新增对象句柄。
     * 按「ObjectHandle 递增 ⇒ 最新的在尾部」这个 PTP 约定，倒序尝试有限个句柄。
     *
     * @param tries 每个存储卷最多试几个尾部句柄
     */
    suspend fun downloadNewestObject(tries: Int = 4, timeoutMs: Int = 60_000): Result<ByteArray> {
        val storages = getStorageIds().getOrNull().orEmpty()
        if (storages.isEmpty()) return Result.failure(IOException("没有可读存储卷"))
        // A fresh PTP/IP session may not have opened the gallery yet, so the usual
        // listStorage() probe has not enabled Nikon's STA direct-read path. Probe one
        // media handle before trying GetObject on the storage-card tail.
        ensureNikonStaDirectReadMode(storages)
        var lastErr: Throwable = IOException("存储卡里没有可下载对象")
        for (sid in storages.asReversed()) {
            val handles = getObjectHandles(sid, 0L).getOrNull().orEmpty()
            if (handles.isEmpty()) continue
            // 尾部是最新的；倒序试，命中即返回
            val tail = handles.takeLast(tries.coerceAtLeast(1)).asReversed()
            for (h in tail) {
                val info = getObjectInfo(h).getOrNull()
                if (info != null && info.sizeBytes <= 0L) continue
                val r = getObject(h, timeoutMs)
                val bytes = r.getOrNull()
                if (bytes != null && bytes.size > 512) {
                    HexLog.info("拍摄", "兜底下载成功：句柄 0x%08X（${bytes.size}B）".format(h))
                    return Result.success(bytes)
                }
                lastErr = r.exceptionOrNull() ?: IOException("句柄 0x%08X 下载为空".format(h))
            }
        }
        HexLog.warn("拍摄", "兜底下载也没拿到：${lastErr.message}")
        return Result.failure(lastErr)
    }

    /** 下载完整对象（照片/视频）。大文件用长超时；传输异常作为失败返回，不穿透 UI。 */
    suspend fun getObject(handle: Long, timeoutMs: Int = 180_000): Result<ByteArray> {
        return try {
            if (staDirectAlbumMode && ptpIpTransport != null) {
                getNikonStaObject(handle, timeoutMs)
            } else {
                val r = transact(Ptp.OP_GET_OBJECT, intArrayOf(handle.toInt()), null, expectData = true, timeoutMs = timeoutMs)
                val data = r.data
                if (!r.ok || data == null) Result.failure(PtpException(r.responseCode, "GetObject 失败: ${Ptp.responseName(r.responseCode)}"))
                else Result.success(data)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Explicit phone queue: bounded reads, stable source validation, no reconnect or write to camera. */
    suspend fun downloadPhoneFile(item:CameraStorage.Item,file:java.io.File,token:Any,onProgress:(Long,Long)->Unit):CameraStorage.Item {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(isOpen && burstConnectionToken===token){"相机会话已更换"}
        val sta=staDirectAlbumMode && ptpIpTransport!=null
        val fresh=run {
            val r=transact(Ptp.OP_GET_OBJECT_INFO,intArrayOf(item.handle.toInt()),null,true,timeoutMs=15_000,expectedConnection=token)
            if(sta && !item.filenameVerified && r.responseCode==Ptp.RC_ACCESS_DENIED && isOpen && burstConnectionToken===token) {
                // Known STA ObjectInfo lock only: retain the disclosed generated name, never claim it is original.
                item
            } else {
                check(r.ok){"无法重新验证对象信息；不使用旧句柄盲目下载"}
                CameraStorage.parseObjectInfo(item.handle,r.data)?:error("对象信息不足")
            }
        }
        if(!sta){check(fresh.filename==item.filename && (item.sizeBytes<=0 || fresh.sizeBytes==item.sizeBytes) &&
            (item.capturedAt.isBlank() || fresh.capturedAt==item.capturedAt)){"文件身份已变化，请刷新相册重新加入"}}
        check(fresh.isRaw==item.isRaw && fresh.isVideo==item.isVideo){"文件格式已变化，拒绝保存为另一种格式"}
        check(fresh.filename.isNotBlank() && '/' !in fresh.filename && '\\' !in fresh.filename){"机身未返回有效原文件名，停止保存"}
        val total=if(sta){val r=transact(Ptp.OP_NIKON_GET_OBJECT_SIZE,intArrayOf(item.handle.toInt()),null,true,timeoutMs=15_000,expectedConnection=token)
            val d=r.data;check(r.ok && d!=null && d.size>=8){"相机未返回可验证文件大小"}
            java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
        }else fresh.sizeBytes
        if(sta && fresh.sizeBytes in 1 until 0xffffffffL)check(fresh.sizeBytes==total){"机身文件大小信息不一致"}
        check(total>0 && total<=64L*1024*1024*1024){"文件大小未知或超出64GiB安全上限"}
        if(!sta)check(total<=0xffffffffL){"当前标准分块读取不支持大于4GiB的文件"}
        val metadata=java.io.File(file.path+".meta")
        val fingerprint=com.zbox.ai.core.PhoneToolsPolicy.hash("${_info.value.model}|${_info.value.serial}|${fresh.storageId}|${fresh.parentHandle}|${fresh.handle}|${fresh.filename}|${fresh.sortCapturedAt}|$total")
        val resume=!sta && _info.value.serial.isNotBlank() && metadata.exists() && runCatching{metadata.readText()==fingerprint}.getOrDefault(false) && file.length() in 1..total
        java.io.RandomAccessFile(file,"rw").use { out ->
            if(!resume)out.setLength(0)
            check(file.parentFile!!.usableSpace>total-out.length()+32L*1024*1024){"手机剩余空间不足（需额外保留32MiB）"}
            metadata.writeText(fingerprint);var offset=out.length();var chunkTarget=com.zbox.ai.core.PhoneToolsPolicy.CHUNK_BYTES
            out.seek(offset);onProgress(offset,total)
            while(offset<total){
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count=com.zbox.ai.core.PhoneToolsPolicy.chunkLength(total-offset,chunkTarget)
                val params=if(sta)intArrayOf(item.handle.toInt(),offset.toInt(),(offset ushr 32).toInt(),count,0)else intArrayOf(item.handle.toInt(),offset.toInt(),count)
                val r=transact(if(sta)Ptp.OP_NIKON_GET_PARTIAL_OBJECT_EX else 0x101B,params,null,true,timeoutMs=30_000,expectedConnection=token)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if(com.zbox.ai.core.PhoneToolsPolicy.canReduceReadChunk(r.responseCode,count,r.data?.size?:0) && isOpen && burstConnectionToken===token){
                    val smaller=com.zbox.ai.core.PhoneToolsPolicy.smallerReadChunk(count)
                    if(smaller>0){chunkTarget=smaller;HexLog.warn("分块兼容","机身明确拒绝大块参数；同偏移只读降至${smaller/1024}KiB，不重连/不测速");continue}
                }
                check(r.ok){"分块读取不支持或失败(0x%04X)；不自动重连/回退整文件".format(r.responseCode)}
                val data=r.data;check(data!=null && data.size in 1..count){"分块数据长度异常，停止保存"}
                check(burstConnectionToken===token){"旧会话结果已丢弃"};out.write(data);offset+=data.size;onProgress(offset,total)
            }
            check(out.length()==total){"最终文件大小不匹配"};out.fd.sync()
        }
        return fresh.copy(sizeBytes=total)
    }

    suspend fun deleteObject(handle: Long): Result<Unit> {
        val r = transact(Ptp.OP_DELETE_OBJECT, intArrayOf(handle.toInt(), 0), null, expectData = false, timeoutMs = 30_000)
        if (r.ok) return Result.success(Unit)
        return Result.failure(PtpException(r.responseCode, "DeleteObject 失败: ${Ptp.responseName(r.responseCode)}"))
    }

    /**
     * 列出机身所有存储卷与文件（会先停实时取景，结束后恢复）。
     * @param onProgress 进度回调：已发现的文件数
     */
    /**
     * 读取相册，只取最近 [limit] 个文件。
     *
     * **性能设计**（这是关键，之前吃过教训）：全卡遍历慢的根因不是文件多，而是
     * 每个文件都要发一次 `0x1008 GetObjectInfo`——一张 32G 卡有几千个对象时，
     * 光探测就要几分钟。ObjectHandle 按拍摄顺序递增，所以「最新」必然在**尾部**。
     * 于是每个目录只探测最后 [FOLDER_FANOUT] 个 handle，并且一旦攒够 [limit] 个
     * 文件就立刻收工，把探测次数从 O(全卡文件数) 压到 O(limit)。
     *
     * @param limit 想要的文件数，默认 100
     */
    suspend fun listStorage(
        limit: Int = 100,
        completeStaIndex: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
    ): Result<CameraStorage.Snapshot> {
        val wasLive = _state.value == State.SESSION_OPEN && liveViewRunning
        if (wasLive) runCatching { endLiveView() }
        try {
            val volumes = ArrayList<CameraStorage.Volume>()
            val items = ArrayList<CameraStorage.Item>()
            val ids = getStorageIds().getOrElse { return Result.failure(it) }
            if (ids.isEmpty()) return Result.failure(IOException("相机没有返回存储卷；请确认 Z30 已插入并识别 SD 卡"))
            if (ptpIpTransport != null && isNikonStaObjectInfoLocked(ids)) {
                staDirectAlbumMode = true
                return listNikonStaAlbum(ids, if (completeStaIndex) Int.MAX_VALUE else limit, onProgress)
            }
            staDirectAlbumMode = false
            var firstObjectError: Throwable? = null
            // 探测次数上限：正常情况远用不到，纯粹防止残缺固件返回乱序 handle 时死循环
            var probesLeft = limit * 8

            for (sid in ids) {
                getStorageInfo(sid).getOrNull()?.let { volumes.add(it) }
                if (items.size >= limit) break

                // 待遍历目录；根目录先放进去
                val folders = ArrayDeque<Long>()
                folders.addLast(0L)
                val visitedFolders = HashSet<Long>()

                while (folders.isNotEmpty() && items.size < limit && probesLeft > 0) {
                    val folder = folders.removeFirst()
                    if (!visitedFolders.add(folder)) continue
                    val children = getObjectHandles(sid, folder).getOrElse {
                        return Result.failure(IOException("读取目录句柄失败（storage 0x%08X / folder 0x%08X）：%s".format(sid, folder, it.message), it))
                    }
                    if (children.isEmpty()) continue
                    // 只认尾部（最新的）一批，这里是最省时间的一刀
                    val tail = if (children.size > FOLDER_FANOUT) {
                        children.subList(children.size - FOLDER_FANOUT, children.size)
                    } else {
                        children
                    }
                    // 从新到旧
                    for (i in tail.indices.reversed()) {
                        if (items.size >= limit || probesLeft <= 0) break
                        val handle = tail[i]
                        probesLeft--
                        val infoRaw = transact(
                            Ptp.OP_GET_OBJECT_INFO, intArrayOf(handle.toInt()), null,
                            expectData = true, timeoutMs = 15_000,
                        )
                        if (!infoRaw.ok) {
                            if (firstObjectError == null) firstObjectError = PtpException(
                                infoRaw.responseCode,
                                "GetObjectInfo 句柄 0x%08X 失败: %s".format(handle, Ptp.responseName(infoRaw.responseCode)),
                            )
                            continue
                        }
                        val raw = infoRaw.data
                        if (raw == null) {
                            if (firstObjectError == null) firstObjectError = IOException("GetObjectInfo 句柄 0x%08X 返回空数据".format(handle))
                            continue
                        }
                        if (CameraStorage.isAssociation(raw)) {
                            // 子目录也按「新→旧」入队，保证先探最新的 100NZ30 / 101NZ30
                            folders.addFirst(handle)
                            continue
                        }
                        CameraStorage.parseObjectInfo(handle, raw)?.let {
                            items.add(it)
                            onProgress?.invoke(items.size)
                        }
                    }
                }
            }

            if (items.isEmpty() && firstObjectError != null) {
                return Result.failure(IOException("未能读取任何照片/视频：${firstObjectError?.message}", firstObjectError))
            }
            items.sortWith(
                compareByDescending<CameraStorage.Item> { it.capturedAt }.thenBy { it.filename },
            )
            val trimmed = if (items.size > limit) ArrayList(items.subList(0, limit)) else items
            HexLog.info(
                "相册",
                "读取完成：${volumes.size} 个存储卷 / 取最近 ${trimmed.size} 个文件（上限 $limit，剩余探测预算 $probesLeft）",
            )
            return Result.success(CameraStorage.Snapshot(volumes, trimmed))
        } catch (t: Throwable) {
            return Result.failure(t)
        } finally {
            if (wasLive && isOpen) {
                runCatching { startLiveView() }
            }
        }
    }

    /** Detect Nikon's STA-mode object lock once, using one media handle only. */
    private suspend fun isNikonStaObjectInfoLocked(ids: List<Long>): Boolean {
        for (sid in ids) {
            val handles = getObjectHandles(sid, 0L).getOrNull().orEmpty()
            val sample = handles.asReversed().firstOrNull {
                NikonStaDirectRead.formatFromHandle(it.toInt()) != null
            } ?: continue
            val result = getObjectInfo(sample)
            if (result.isSuccess) return false
            val code = (result.exceptionOrNull() as? PtpException)?.responseCode
            if (code == Ptp.RC_ACCESS_DENIED) {
                HexLog.warn("相册", "GetObjectInfo 被 Nikon STA 固件锁定（0x200F）；改用私有直读协议")
                return true
            }
            return false
        }
        return false
    }

    /**
     * Enable Nikon's STA read path only for PTP/IP sessions where one standard
     * GetObjectInfo probe is explicitly rejected with 0x200F. Capture can be the
     * first feature used after connecting, before the gallery has set this flag.
     */
    suspend fun ensureNikonStaDirectReadMode(ids: List<Long>? = null): Boolean {
        if (ptpIpTransport == null) return false
        if (staDirectAlbumMode) return true
        val storageIds = ids ?: getStorageIds().getOrNull().orEmpty()
        if (storageIds.isEmpty() || !isNikonStaObjectInfoLocked(storageIds)) return false
        staDirectAlbumMode = true
        HexLog.warn("拍摄", "检测到 Nikon STA GetObjectInfo 锁定（0x200F），启用 0x9421/0x9431 直读回传")
        return true
    }

    /** Nikon Z camera STA gallery path: bulk metadata (0x9434) replaces locked GetObjectInfo. */
    private suspend fun listNikonStaAlbum(
        ids: List<Long>,
        limit: Int,
        onProgress: ((Int) -> Unit)?,
    ): Result<CameraStorage.Snapshot> {
        val volumes = ids.mapNotNull { getStorageInfo(it).getOrNull() }
        val all = ArrayList<Pair<CameraStorage.Item, Long?>>()
        var metadataCount = 0
        for (sid in ids) {
            val result = transact(
                Ptp.OP_NIKON_GET_OBJECTS_METADATA,
                intArrayOf(sid.toInt(), 0, 0), null,
                expectData = true, timeoutMs = 90_000,
            )
            if (!result.ok) {
                val message = "尼康 STA 相册直读 0x9434 失败：${Ptp.responseName(result.responseCode)}"
                HexLog.error("相册", message)
                return Result.failure(PtpException(result.responseCode, message))
            }
            val entries = NikonStaDirectRead.parseObjectsMetaData(result.data ?: ByteArray(0))
                ?: return Result.failure(IOException("尼康 STA 0x9434 元数据格式异常（${result.data?.size ?: 0} 字节）"))
            metadataCount += entries.size
            logMetaSample(result.data ?: ByteArray(0), entries)
            val companions = NikonStaDirectRead.pairRawWithJpeg(entries)
            val companionHandles = companions.values.mapTo(HashSet()) { it.handle }
            for (entry in entries) {
                val kind = NikonStaDirectRead.formatFromHandle(entry.handle) ?: continue
                // 0x9434 的 size 字段实测只有 0/1，不是文件大小：不过滤条目，也不用于展示。
                if (entry.size > 1L) staObjectSizes[entry.handle.toLong() and 0xFFFF_FFFFL] = entry.size
                if (entry.handle in companionHandles) continue // 已作为 JPEG 的 NEF 原片配对，不单独列出
                val partner = companions[entry.handle]
                val name = NikonStaDirectRead.placeholderName(entry.handle, kind)
                val format = when (kind) {
                    NikonMediaKind.RAW -> 0xB103
                    NikonMediaKind.JPEG -> 0x3801
                    NikonMediaKind.VIDEO -> 0x300D
                    else -> continue
                }
                val captured = entry.captureTimeMillis?.let {
                    SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date(it))
                } ?: "—"
                all += (CameraStorage.Item(
                    handle = entry.handle.toLong() and 0xFFFF_FFFFL,
                    storageId = sid,
                    format = format,
                    filename = name,
                    sizeBytes = 0L, // 0x9434 的 size 不是文件大小，未知时显示 "—"
                    width = 0,
                    height = 0,
                    capturedAt = captured,
                    protectedObject = false,
                    sequence = entry.handle.toLong() and 0xFFFF_FFFFL,
                    companionHandle = partner?.let { it.handle.toLong() and 0xFFFF_FFFFL },
                    companionFilename = partner?.let { NikonStaDirectRead.placeholderName(it.handle, NikonMediaKind.RAW) },
                    filenameVerified = false,
                ) to entry.captureTimeMillis)
                if (all.size % 100 == 0) onProgress?.invoke(all.size)
            }
        }
        // 按真实拍摄时间倒序；没有拍摄时间的排到最后。同一时间按句柄低 16 位倒序（机身编号递增）。
        val sorted = all.sortedWith(
            compareByDescending<Pair<CameraStorage.Item, Long?>> { it.second ?: Long.MIN_VALUE }
                .thenByDescending { it.first.handle and 0xFFFFL }
        ).map { it.first }
        val count = limit.coerceAtLeast(1)
        val trimmed = sorted.take(count)
        val noTime = all.count { it.second == null }
        trimmed.take(5).forEachIndexed { i, item ->
            HexLog.info("相册", "排序第${i + 1}: ${item.filename} 拍摄=${item.capturedAt} 大小=${item.sizeBytes}B 配对=${item.companionFilename ?: "无"}")
            android.util.Log.i("ZboxPTPIP", "相册诊断 排序第${i + 1}: ${item.filename} 拍摄=${item.capturedAt} 大小=${item.sizeBytes}B 配对=${item.companionFilename ?: "无"}")
        }
        if (noTime > 0) HexLog.warn("相册", "有 $noTime / ${all.size} 个媒体没有拍摄时间，已排到最后")
        HexLog.info("相册", "Nikon STA 直读完成：${volumes.size} 个存储卷 / 元数据 $metadataCount 个 / 媒体 ${all.size} 个 / 展示 ${trimmed.size} 张")
        return Result.success(CameraStorage.Snapshot(volumes, trimmed))
    }

    /** 诊断：记录 0x9434 载荷前两条原始记录（每条 16 字节），用于核对字段偏移。 */
    private fun logMetaSample(raw: ByteArray, entries: List<NikonStaDirectRead.MetaEntry>) {
        val hex = (0 until minOf(2, entries.size)).joinToString(" | ") { i ->
            val off = 8 + i * 16
            raw.copyOfRange(off, minOf(off + 16, raw.size)).joinToString(" ") { "%02X".format(it) }
        }
        val msg = "0x9434 原始记录前 2 条(16B/条)：$hex；无拍摄时间 ${entries.count { it.captureTimeMillis == null }}/${entries.size}"
        HexLog.info("相册", msg)
        android.util.Log.i("ZboxPTPIP", "相册诊断 $msg")
        entries.take(3).forEach { e ->
            android.util.Log.i("ZboxPTPIP", "相册诊断 handle=%08X size=%d time=%s".format(e.handle, e.size, e.captureTimeMillis?.let { java.util.Date(it).toString() } ?: "null"))
        }
        android.util.Log.i("ZboxPTPIP", "相册诊断 句柄高字节分布: " + NikonStaDirectRead.prefixHistogram(entries.map { it.handle }))
        entries.filter { it.captureTimeMillis != null }.sortedByDescending { it.captureTimeMillis }.take(6).forEach { e ->
            android.util.Log.i("ZboxPTPIP", "相册诊断 最新: handle=%08X size=%d time=%s kind=%s".format(e.handle, e.size, java.util.Date(e.captureTimeMillis!!).toString(), NikonStaDirectRead.formatFromHandle(e.handle)?.toString() ?: "未知"))
        }
        val sizes = entries.map { it.size }
        android.util.Log.i("ZboxPTPIP", "相册诊断 size分布: 0=${sizes.count { it == 0L }} 1=${sizes.count { it == 1L }} 2-9=${sizes.count { it in 2L..9L }} >9=${sizes.count { it > 9L }} max=${sizes.maxOrNull()}")
        entries.withIndex().filter { it.value.size == 1L || (it.value.handle and 0xFFFF) == 0xCEAE }.take(4).forEach { (i, e) ->
            val off = 8 + i * 16
            if (off + 16 <= raw.size) {
                val hx = raw.copyOfRange(off, off + 16).joinToString(" ") { "%02X".format(it) }
                android.util.Log.i("ZboxPTPIP", "相册诊断 raw#$i handle=%08X size=%d : $hx".format(e.handle, e.size))
            }
        }
    }

    // ---------------- 写入机制实测（AccessDenied 排查） ----------------

    /**
     * 直接用给定参数形态发一次 0x90CD，不做任何自动改写。
     * 给探测矩阵用，避免矩阵逻辑被高层 API 的默认行为干扰。
     */
    suspend fun setPicCtrlRaw(
        params: IntArray,
        payload: ByteArray,
        timeoutMs: Int = 15000,
    ): PtpResult = transact(
        Ptp.OP_NIKON_SET_PICCTRL, params, payload,
        expectData = false, timeoutMs = timeoutMs,
    )

    /**
     * 把相机切进「PC 控制模式」并等它就绪。
     * digiCamControl 的 InitHostMode 就是这么干的——有些机身只在 PC 控制模式下才允许写 PC 数据。
     */
    suspend fun armPcControlMode(): Boolean = withContext(Dispatchers.IO) {
        val a = runCatching { changeCameraMode(true) }.isSuccess &&
            runCatching { changeCameraMode(true) }.getOrNull()?.isSuccess == true
        runCatching { deviceReadyBlocking() }
        kotlinx.coroutines.delay(250)
        runCatching { endLiveView() }
        kotlinx.coroutines.delay(120)
        a
    }

    /** 一次写入尝试的结果。 */
    data class WriteProbe(
        val label: String,
        val params: String,
        val payloadSize: Int,
        val responseCode: Int,
        val ok: Boolean,
        val readBackName: String?,
    )

    /**
     * 探测成功后固化下来的写入配方。
     *
     * @param paramMode 0 = (slot,0)；1 = (slot,pcdType)；2 = (slot)
     * @param itemPrefix 载荷形态：0 = 纯 84B；1 = 85B（首字节 = C 序号 0..8）；
     *   2 = 85B（首字节 = PTP item 号低字节）；3 = 87B（[item][mono][customFlag] + 84B，
     *   digiCamControl 反推的老机身布局）
     * @param prelude 0 = 无前置；1 = 切 PC 控制模式；2 = 切 PC + 结束取景
     */
    data class WriteMode(
        val paramMode: Int,
        val itemPrefix: Int,
        val prelude: Int,
        val verifiedName: String,
    ) {
        fun describe(): String = buildString {
            append(
                when (paramMode) {
                    0 -> "(slot,0)"
                    1 -> "(slot,pcdType)"
                    2 -> "(slot)"
                    3 -> "(slot,len)"
                    4 -> "(slot,1)"
                    else -> "(slot,$paramMode)"
                }
            )
            append(
                when (itemPrefix) {
                    1 -> " + 85B(序号前缀)"
                    2 -> " + 85B(item号前缀)"
                    3 -> " + 87B(三字节前缀)"
                    else -> " + 84B"
                }
            )
            append(
                when (prelude) {
                    1 -> " + 切PC模式"
                    2 -> " + 切PC+停取景"
                    3 -> " + 先删槽位+切PC"
                    4 -> " + 先选中该槽位+切PC"
                    else -> ""
                }
            )
        }
    }

    /** 探测成功后固化的写入配方；null = 还没探测过，用默认行为。 */
    @Volatile
    var writeMode: WriteMode? = null
        private set

    /** 手动指定/清空写入配方（调试台可覆盖）。 */
    fun overrideWriteMode(mode: WriteMode?) {
        writeMode = mode
    }

    /**
     * 写入机制矩阵探测。
     *
     * 为什么需要：Z30 对 0x90CD 返回 AccessDenied 时，错误信息不告诉你缺什么。
     * 可能性有一堆——参数形态不对、载荷前面要带 item 前缀、必须先切 PC 控制模式、
     * 必须先结束取景、slot 必须先用别的 opcode 注册。逐个猜没意义，直接全跑一遍看哪个通。
     *
     * 组合数：3 种参数形态 × 2 种载荷形态 × 3 种前置动作 = 18，但会按「最可能先试」排序，
     * 命中即停，实际通常 2~6 次往返。
     *
     * @param slotPtp 目标槽位 PTP item 号（如 0x00C9 = C-1）
     * @param slotIndex 1..9
     * @param payload614 614 字节优化校准记录（见 [com.zbox.ai.core.PcPayload614]）
     * @param onProgress 每条尝试的回调，用于实时刷 UI
     * @return 第一个成功后能读回匹配名称的机制；全失败返回 null 并附完整尝试记录
     */
    suspend fun probeWriteMechanism(
        slotPtp: Int,
        slotIndex: Int,
        payload614: ByteArray,
        onProgress: (WriteProbe) -> Unit = {},
    ): Pair<WriteProbe?, List<WriteProbe>> = withContext(Dispatchers.IO) {
        require(payload614.size == com.zbox.ai.core.PcPayload614.SIZE) {
            "载荷必须 ${com.zbox.ai.core.PcPayload614.SIZE} 字节"
        }
        val attempts = mutableListOf<WriteProbe>()
        val size = com.zbox.ai.core.PcPayload614.SIZE

        // 载荷形态：
        //   0 = 纯 614 字节（真机 0x90CC 返回的就是这个长度，最可能直接可用）
        //   1 = 615B，首字节 = C 序号 0..8（「写回时前面要补一个 item」的假设）
        //   2 = 615B，首字节 = PTP item 号低字节（与读回的 item 号对齐）
        //   3 = 617B，[item][mono][customFlag] + 614B —— digiCamControl 解析出的老机身布局
        val sIdx = (slotIndex - 1).coerceIn(0, 8).toByte()
        val mono = if ((payload614[com.zbox.ai.core.PcPayload614.OFF_FAMILY].toInt() and 0xFF) == 1) 1.toByte() else 0.toByte()
        val payloadSets = listOf(
            "614B" to payload614,
            "615B(序号)" to ByteArray(size + 1).also {
                it[0] = sIdx
                System.arraycopy(payload614, 0, it, 1, size)
            },
            "615B(item号)" to ByteArray(size + 1).also {
                it[0] = (slotPtp and 0xFF).toByte()
                System.arraycopy(payload614, 0, it, 1, size)
            },
            "617B(头部3字节)" to ByteArray(size + 3).also {
                it[0] = sIdx
                it[1] = mono
                it[2] = 1
                System.arraycopy(payload614, 0, it, 3, size)
            },
        )

        // 参数形态（按可能性排序）：
        //   0 = (slot, 0)            —— libgphoto2 注释「2 params」最常见写法
        //   1 = (slot, pcdType)      —— 第二参声明数据种类
        //   2 = (slot)               —— 单参退化
        //   3 = (slot, 614)          —— 第二参是载荷长度
        //   4 = (slot, 1)            —— 第二参是「启用自定义曲线」标志
        val paramSets = listOf(
            "两参 (slot,0)" to intArrayOf(slotPtp, 0),
            "两参 (slot,1)" to intArrayOf(slotPtp, 1),
            "单参 (slot)" to intArrayOf(slotPtp),
            "两参 (slot,$size)" to intArrayOf(slotPtp, size),
            "两参 (slot,2)" to intArrayOf(slotPtp, 2),
        )

        // 前置动作（按可能性排序：先不动 → 切 PC 模式 → 切 PC + 结束取景 → 先删槽位再写）
        val preludes = listOf(
            "无前置" to suspend { },
            "切PC控制模式" to suspend {
                runCatching { changeCameraMode(true) }
                runCatching { deviceReadyBlocking() }
                kotlinx.coroutines.delay(200)
            },
            "切PC+结束取景+DeviceReady" to suspend {
                runCatching { changeCameraMode(true) }
                runCatching { endLiveView() }
                kotlinx.coroutines.delay(150)
                runCatching { deviceReadyBlocking() }
                kotlinx.coroutines.delay(150)
            },
            // 注意：这里**不再**包含「先删槽位(0x90CE)」。
            // v1.4.0 里把它当成无害前置动作，结果真机上把用户 C-1 的「Konica Ce」预设删没了
            // （删完 0x90CC 只回 36 字节全零）。破坏性操作不能混在自动探测里。
            "先选中槽位(0xD200)+切PC" to suspend {
                runCatching { changeCameraMode(true) }
                runCatching { setActivePictureControl(slotPtp) }
                kotlinx.coroutines.delay(250)
                runCatching { deviceReadyBlocking() }
                kotlinx.coroutines.delay(150)
            },
        )

        for ((pi, preludePair) in preludes.withIndex()) {
            val (preludeLabel, prelude) = preludePair
            for ((mi, paramPair) in paramSets.withIndex()) {
                val (pLabel, params) = paramPair
                for ((di, payloadPair) in payloadSets.withIndex()) {
                    val (dLabel, payload) = payloadPair
                    runCatching { prelude() }
                    val r = runCatching { setPicCtrlRaw(params, payload) }
                        .getOrElse {
                            PtpResult(-1, IntArray(0), null, "", "", "")
                        }
                    val label = "$preludeLabel / $pLabel / $dLabel"
                    var readBack: String? = null
                    if (r.ok) {
                        // 只有真能读回同名才算成功——有些固件对越界写入回 OK 但实际丢弃
                        val back = runCatching {
                            getPicCtrlData(slotPtp, timeoutMs = 5000).getOrNull()
                        }.getOrNull()
                        readBack = back?.let {
                            com.zbox.ai.core.PcPayload614.parse(it)?.name
                        }
                    }
                    val probe = WriteProbe(
                        label = label,
                        params = params.joinToString(",") { "0x%04X".format(it) },
                        payloadSize = payload.size,
                        responseCode = r.responseCode,
                        ok = r.ok,
                        readBackName = readBack,
                    )
                    attempts.add(probe)
                    HexLog.info(
                        "写入探测",
                        "$label → RC=0x%04X %s%s".format(
                            r.responseCode,
                            Ptp.responseName(r.responseCode),
                            if (readBack != null) " 读回=$readBack" else "",
                        ),
                    )
                    onProgress(probe)
                    if (r.ok && readBack != null) {
                        // 命中即固化：后续写入直接用这套机制，不再试错
                        writeMode = WriteMode(
                            paramMode = mi,
                            itemPrefix = di,
                            prelude = pi,
                            verifiedName = readBack,
                        )
                        HexLog.info("写入机制已固化", writeMode!!.describe())
                        return@withContext probe to attempts
                    }
                }
            }
        }
        null to attempts
    }

    /**
     * 扫描一片 item 号，列出所有能读到的优化校准，用来找「用户明明存了却显示不出来」的槽位。
     * 每次读一次往返，扫 0x00C0~0x00DC 共 29 次，大约 2~4 秒。
     */
    suspend fun scanPicCtrlItems(
        range: IntRange = 0x00C0..0x00DC,
        onProgress: ((Int, String?) -> Unit)? = null,
    ): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<Int, String>>()
        val saved = singleParamMode
        // 先按当前形态；若一次都没成功，换另一形态再来一遍
        for (mode in listOf(saved, !saved)) {
            singleParamMode = mode
            out.clear()
            for (item in range) {
                val r = runCatching { getPicCtrlData(item, timeoutMs = 4000) }.getOrNull()
                val bytes: ByteArray? = r?.getOrNull()
                val name: String? = bytes?.let {
                    com.zbox.ai.core.PcPayload614.parse(it)?.name
                }
                onProgress?.invoke(item, name)
                if (bytes != null && name != null) out.add(item to name)
            }
            if (out.isNotEmpty()) break
        }
        singleParamMode = saved
        HexLog.info("槽位扫描", "在 0x%04X..0x%04X 找到 ${out.size} 项".format(range.first, range.last))
        out
    }

    /**
     * 向机身提问：到底哪些优化校准项是存在的、当前选中项是哪一项、
     * 以及尼康真正的「曲线读写对」（0x90C5/0x90C6）和「应用模式」（0x9435）能不能用。
     *
     * 为什么先问再写：真机上盲目穷举 0x90CD 的参数形态只会得到一堆
     * AccessDenied(0x200F) 和 InvalidParameter(0x201D)，错误码不区分「参数错」和
     * 「没权限」，等于什么信息都没拿到。而 0x90CF / 0xD200 / 0x90C5 这些是**只读**
     * 操作，问一遍不会改动机身状态，答案却能直接告诉我们该往哪个方向修。
     *
     * 全部只读，不会写坏任何槽位。
     */
    suspend fun auditPictureControl(): Result<String> = withContext(Dispatchers.IO) {
        if (!isOpen) return@withContext Result.failure(IllegalStateException("未连接"))
        val sb = StringBuilder()

        // 1) 当前激活的优化校准项（0xD200）与可切换项（0xD201）
        sb.append("── 机身当前优化校准项 ──\n")
        for ((label, code) in listOf(
            "ActivePicCtrlItem 0xD200" to Ptp.DPC_NIKON_ACTIVE_PICCTRL_ITEM,
            "ChangePicCtrlItem 0xD201" to Ptp.DPC_NIKON_CHANGE_PICCTRL_ITEM,
        )) {
            val r = runCatching { getDeviceProp(code, 4000) }.getOrNull()
            val d = r?.getOrNull()
            if (d != null && d.isNotEmpty()) {
                val v = when (d.size) {
                    1 -> (d[0].toInt() and 0xFF).toString()
                    else -> (CameraStorage.u16(d, 0)).toString() +
                        "（原始 ${d.toHex(8)}）"
                }
                sb.append("  $label = $v\n")
            } else {
                val err = r?.exceptionOrNull()?.message ?: "无响应"
                sb.append("  $label = 读不到（$err）\n")
            }
        }

        // 2) 逐个 item 问能力（0x90CF）。这一层能区分「不支持的 item」和「存在但空」。
        sb.append("\n── 0x90CF 能力查询 0x00C0..0x00DC ──\n")
        var supported = 0
        var denied = 0
        for (item in 0x00C0..0x00DC) {
            val r = runCatching { getPicCtrlCapability(item) }.getOrNull()
            val d = r?.getOrNull()
            if (d != null) {
                supported++
                sb.append("  0x%04X → 支持，能力数据 %dB %s\n".format(item, d.size, d.toHex(24)))
            } else {
                val code = (r?.exceptionOrNull() as? PtpException)?.responseCode ?: -1
                if (code == Ptp.RC_ACCESS_DENIED) denied++
                // 只打印 0xCB..0xD3 这一段（自定义槽位）的失败，其它 item 太多噪音
                if (item in 0x00CB..0x00D3) {
                    sb.append("  0x%04X → %s\n".format(item, Ptp.responseName(code)))
                }
            }
        }
        sb.append("  小结：支持 $supported 项，AccessDenied $denied 项\n")

        // 3) 尼康真正的曲线读写对 0x90C5 CurveDownload（1 参数，data-in）
        sb.append("\n── 0x90C5 CurveDownload 试探 ──\n")
        for (param in intArrayOf(1, 0)) {
            val r = runCatching {
                transact(Ptp.OP_NIKON_CURVE_DOWNLOAD, intArrayOf(param), null, expectData = true, timeoutMs = 5000)
            }.getOrNull()
            if (r != null) {
                sb.append(
                    "  param=$param → 0x%04X %s，数据 %dB %s\n".format(
                        r.responseCode, Ptp.responseName(r.responseCode),
                        r.data?.size ?: 0, r.data?.toHex(24) ?: "",
                    )
                )
            } else {
                sb.append("  param=$param → 调用异常\n")
            }
        }

        // 4) 应用模式 0x9435（1 参数）。部分机身只在 PC/应用模式下放开写入。
        sb.append("\n── 0x9435 ChangeApplicationMode 探测 ──\n")
        val appMode = runCatching {
            transact(Ptp.OP_NIKON_CHANGE_APPLICATION_MODE, intArrayOf(1), null, expectData = false, timeoutMs = 5000)
        }.getOrNull()
        if (appMode != null) {
            sb.append("  切 PC 应用模式(1) → 0x%04X %s\n".format(appMode.responseCode, Ptp.responseName(appMode.responseCode)))
        } else {
            sb.append("  切 PC 应用模式(1) → 调用异常\n")
        }
        runCatching { deviceReadyBlocking() }
        kotlinx.coroutines.delay(200)

        val text = sb.toString()
        HexLog.info("优化校准审计", text.replace("\n", " ⏎ "))
        Result.success(text)
    }

    /**
     * 无损回写测试：把刚刚从槽位 A 读出来的 614 字节原封不动写回同一个槽位 A。
     *
     * 为什么这一步最关键：内容完全没变，所以不管成功失败都不可能损坏任何东西，
     * 但它能把问题**一分为二**——
     *   · 如果原样回写成功 → 0x90CD 通道是通的，之前失败是「载荷内容」或「目标槽位」的问题；
     *   · 如果原样回写也被 AccessDenied → 说明 0x90CD 本身被固件锁死，必须换
     *     0x90C5/0x90C6 曲线通道或走别的注册流程，再试参数形态都是浪费。
     *
     * @param slotPtp 已确认读得通的槽位（例如用户的 C-1 = 0x00C9）
     */
    suspend fun probeWriteBackSame(
        slotPtp: Int,
        onProgress: (WriteProbe) -> Unit = {},
    ): Pair<WriteProbe?, List<WriteProbe>> = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<WriteProbe>()
        val original = getPicCtrlData(slotPtp, timeoutMs = 6000).getOrNull()
            ?: return@withContext null to attempts
        val name = com.zbox.ai.core.PcPayload614.parse(original)?.name
        HexLog.info("无损回写", "读回 0x%04X（${original.size}B，名称=$name），原样写回".format(slotPtp))

        val paramSets = listOf(
            "两参 (slot,0)" to intArrayOf(slotPtp, 0),
            "两参 (slot,1)" to intArrayOf(slotPtp, 1),
            "单参 (slot)" to intArrayOf(slotPtp),
        )
        for ((pi, entry) in paramSets.withIndex()) {
            val params: IntArray = entry.second
            val r = runCatching { setPicCtrlRaw(params, original) }
                .getOrElse { PtpResult(-1, IntArray(0), null, "", "", "") }
            var readBack: String? = null
            if (r.ok) {
                readBack = runCatching {
                    getPicCtrlData(slotPtp, timeoutMs = 6000).getOrNull()?.let {
                        com.zbox.ai.core.PcPayload614.readName(it)
                    }
                }.getOrNull()
            }
            val probe = WriteProbe(
                label = "无损回写 / ${params.joinToString(",") { "0x%04X".format(it) }}",
                params = params.joinToString(",") { "0x%04X".format(it) },
                payloadSize = original.size,
                responseCode = r.responseCode,
                ok = r.ok,
                readBackName = readBack,
            )
            attempts.add(probe)
            onProgress(probe)
            HexLog.info(
                "无损回写",
                "${
                    params.joinToString(",") { "0x%04X".format(it) }
                } → RC=0x%04X %s%s".format(
                    r.responseCode, Ptp.responseName(r.responseCode),
                    if (readBack != null) " 读回=$readBack" else "",
                ),
            )
            if (r.ok) {
                // 无损回写成功 = 通道通，把这个参数形态记下来给后续真实写入用
                writeMode = WriteMode(
                    paramMode = pi,
                    itemPrefix = 0,
                    prelude = 0,
                    verifiedName = readBack ?: (name ?: ""),
                )
                return@withContext probe to attempts
            }
        }
        null to attempts
    }

    companion object {
        const val TIMEOUT_MS = 8000
        val MAX_BULK_CHUNK: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 64 * 1024 else 16 * 1024
        const val PTP_EVENT_HEADER = 12
        const val CONNECT_ATTEMPTS = 3
        /** 每个目录只探测最后这么多个 handle（最新的在尾部）。 */
        const val FOLDER_FANOUT = 60

        const val PTP_USB_REQUEST_RESET = 0x66
        const val USB_BM_OUT_CLASS_INTERFACE = 0x21
        const val USB_BM_OUT_STANDARD_ENDPOINT = 0x02
        const val USB_REQUEST_CLEAR_FEATURE = 0x01
        const val USB_FEATURE_ENDPOINT_HALT = 0x00
    }
}

class PtpException(val responseCode: Int, message: String) : Exception(message)

// ---------------- Hex 工具 ----------------

fun ByteArray.toHex(maxBytes: Int = Int.MAX_VALUE): String {
    val n = minOf(size, maxBytes)
    val sb = StringBuilder(n * 3)
    for (i in 0 until n) {
        sb.append("%02X".format(this[i].toInt() and 0xFF))
        if (i != n - 1) sb.append(' ')
    }
    if (size > maxBytes) sb.append(" ...(+${size - maxBytes}B)")
    return sb.toString()
}
