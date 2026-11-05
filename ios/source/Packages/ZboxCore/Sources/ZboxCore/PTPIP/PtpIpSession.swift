import Foundation

public struct PtpIpSessionConfig: Sendable {
    public var initiatorGuid: Data
    public var initiatorName: String
    /// true 表示相机作为 STA 加入手机热点或局域网；false 表示手机连接相机热点。
    public var staMode: Bool
    public var commandTimeoutSeconds: Double
    public var openSessionTimeoutSeconds: Double
    public var pairingTimeoutSeconds: Double

    public init(
        initiatorGuid: Data,
        initiatorName: String,
        staMode: Bool,
        commandTimeoutSeconds: Double = 15,
        openSessionTimeoutSeconds: Double = 12,
        pairingTimeoutSeconds: Double = 15
    ) {
        self.initiatorGuid = initiatorGuid
        self.initiatorName = initiatorName
        self.staMode = staMode
        self.commandTimeoutSeconds = commandTimeoutSeconds
        self.openSessionTimeoutSeconds = openSessionTimeoutSeconds
        self.pairingTimeoutSeconds = pairingTimeoutSeconds
    }
}

public struct PtpIpConnectionInfo: Equatable, Sendable {
    public let connectionNumber: UInt32
    public let cameraName: String
    public let cameraGuid: Data
    public let ptpSessionId: UInt32
}

/// 一次 PTP/IP 会话:命令通道握手 → 事件通道握手 → OpenSession → GetDeviceInfo →(可选)Nikon 主机注册。
///
/// 时序与安卓 `PtpIpTransport.connect()` / `NikonPtpDriver` 一致:
/// - 每条 TCP 连接后等待 350 ms 再发送初始化;
/// - 事件通道在命令通道握手成功后才打开(由 `openEventChannel` 提供);
/// - STA 模式下事件通道先发送一次 ProbeRequest;
/// - 主机注册:0x952B 准备 → 等待到 8.5 s → 0x935A 确认(参数 0x2001)。每次准备只允许确认一次。
public actor PtpIpSession {
    public typealias Settle = @Sendable (Int64) async throws -> Void
    public typealias Clock = @Sendable () async -> Int64
    public typealias RandomSource = @Sendable () -> UInt32
    public typealias EventChannelOpener = @Sendable () async throws -> PtpIpByteChannel

    private let config: PtpIpSessionConfig
    private let command: PtpIpPacketStream
    private let openEventChannel: EventChannelOpener
    private var event: PtpIpPacketStream?
    private let settle: Settle
    private let clock: Clock
    private let randomSource: RandomSource
    private let lock = PtpIpAsyncLock()

    private var transactionId: UInt32 = 0
    private var sessionGeneration: UInt64 = 0
    private var pairingPreparedAtMs: Int64?

    public private(set) var connectionInfo: PtpIpConnectionInfo?
    public private(set) var deviceInfo: PtpDeviceInfo?
    public private(set) var ptpSessionId: UInt32 = 1

    public init(
        commandChannel: PtpIpByteChannel,
        openEventChannel: @escaping EventChannelOpener,
        config: PtpIpSessionConfig,
        settle: @escaping Settle = { ms in
            if ms > 0 { try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) }
        },
        clock: @escaping Clock = {
            Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        },
        randomSource: @escaping RandomSource = { UInt32.random(in: 0...UInt32.max) }
    ) {
        self.command = PtpIpPacketStream(channel: commandChannel)
        self.openEventChannel = openEventChannel
        self.config = config
        self.settle = settle
        self.clock = clock
        self.randomSource = randomSource
    }

    // MARK: - Handshake

    /// 完成握手并打开 PTP 会话。失败时自动关闭两条通道,不做任何自动重试。
    public func connect() async throws -> PtpIpConnectionInfo {
        sessionGeneration &+= 1
        do {
            let info = try await performHandshake()
            connectionInfo = info
            return info
        } catch {
            await closeChannels()
            throw error
        }
    }

    private func performHandshake() async throws -> PtpIpConnectionInfo {
        try await settle(NikonConnectionPolicy.connectSettleMs)
        let commandStream = command
        try await commandStream.send(
            try PtpIpCodec.initCommandRequest(guid: config.initiatorGuid, hostName: config.initiatorName)
        )
        let ackPacket = try await readPacket(commandStream, stage: "InitCommand", timeout: config.commandTimeoutSeconds)
        if ackPacket.type == PtpIpPacketType.initFail.rawValue {
            let reason = ackPacket.payload.count >= 4 ? ackPacket.payload.readLE(UInt32.self, at: 0) : nil
            throw PtpIpSessionError.initFailed(reason: reason)
        }
        let ack = try PtpIpCodec.parseInitCommandAck(ackPacket)

        // 事件通道在命令通道确认后才打开,避免热点场景下第二条连接走错网络接口。
        try await settle(NikonConnectionPolicy.connectSettleMs)
        let eventStream = PtpIpPacketStream(channel: try await openEventChannel())
        event = eventStream
        var connectionPayload = Data()
        connectionPayload.appendLE(ack.connectionNumber)
        try await eventStream.send(PtpIpCodec.frame(.initEventRequest, payload: connectionPayload))
        let eventAck = try await readPacket(eventStream, stage: "InitEvent", timeout: config.commandTimeoutSeconds)
        guard eventAck.type == PtpIpPacketType.initEventAck.rawValue else {
            throw PtpIpSessionError.unexpectedPacket(stage: "握手", type: eventAck.type)
        }
        if config.staMode {
            try await eventStream.send(PtpIpCodec.frame(.probeRequest))
        }

        let sessionId = NikonConnectionPolicy.sessionId(sta: config.staMode, random: randomSource())
        ptpSessionId = sessionId
        let opened = try await runOperation(
            opCode: PtpOpCode.openSession,
            params: [sessionId],
            expectData: false,
            timeout: config.openSessionTimeoutSeconds
        )
        guard opened.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: opened.response.code, opCode: PtpOpCode.openSession)
        }
        return PtpIpConnectionInfo(
            connectionNumber: ack.connectionNumber,
            cameraName: ack.cameraName,
            cameraGuid: ack.cameraGuid,
            ptpSessionId: sessionId
        )
    }

    // MARK: - Operations

    /// 执行一条 PTP 操作。调用方必须检查 `response.isOK`。
    public func execute(
        opCode: UInt16,
        params: [UInt32] = [],
        expectData: Bool = false,
        timeoutSeconds: Double? = nil,
        maxDataBytes: Int = 32 * 1024 * 1024
    ) async throws -> PtpOperationResult {
        guard connectionInfo != nil else { throw PtpIpSessionError.notConnected }
        guard (0...(32 * 1024 * 1024)).contains(maxDataBytes) else {
            throw PtpIpSessionError.malformed(stage: "命令", detail: "数据长度上限非法")
        }
        let timeout = timeoutSeconds ?? config.commandTimeoutSeconds
        let generation = sessionGeneration
        return try await lock.withLock { [self] in
            try await self.executeWhileLocked(opCode: opCode, params: params, expectData: expectData,
                                              timeout: timeout, maxDataBytes: maxDataBytes, generation: generation)
        }
    }

    /// Validate after acquiring the transaction lock; a queued command must not run on
    /// a session closed/replaced while it was waiting. Invalidate fatal errors before
    /// releasing the lock so the next waiter cannot send onto a poisoned stream.
    private func executeWhileLocked(opCode: UInt16, params: [UInt32], expectData: Bool, timeout: Double,
                                    maxDataBytes: Int, generation: UInt64) async throws -> PtpOperationResult {
        guard connectionInfo != nil, sessionGeneration == generation else { throw PtpIpSessionError.notConnected }
        do {
            return try await runOperation(opCode: opCode, params: params, expectData: expectData,
                                          timeout: timeout, maxDataBytes: maxDataBytes)
        } catch PtpIpSessionError.timeout(let stage) {
            if sessionGeneration == generation { await close() }
            throw PtpIpSessionError.timeout(stage: stage)
        } catch PtpIpSessionError.dataLimitExceeded(let limit) {
            if sessionGeneration == generation { await close() }
            throw PtpIpSessionError.dataLimitExceeded(limit: limit)
        } catch {
            // An incomplete or malformed wire transaction cannot be safely reused.
            // Camera rejection is a normal operation response, not an error here.
            if sessionGeneration == generation { await close() }
            throw error
        }
    }

    public func fetchDeviceInfo() async throws -> PtpDeviceInfo {
        let result = try await execute(opCode: PtpOpCode.getDeviceInfo, expectData: true)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.getDeviceInfo)
        }
        let info = try PtpDeviceInfo.parse(result.data)
        deviceInfo = info
        return info
    }

    /// Read the standard PTP battery level (0x5001) as a percentage.
    /// Unknown, missing and out-of-range values are represented as nil.
    public func fetchBatteryLevel() async throws -> UInt8? {
        let result = try await execute(
            opCode: PtpOpCode.getDevicePropValue,
            params: [0x5001],
            expectData: true,
            timeoutSeconds: 4
        )
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(
                code: result.response.code, opCode: PtpOpCode.getDevicePropValue
            )
        }
        guard result.data.count == 1, let value = result.data.first, value <= 100 else { return nil }
        return value
    }

    /// Send one explicitly requested AF drive while the caller's live-view gate remains valid.
    /// No auto-retry; an OK response means the command was accepted, not that focus was achieved.
    public func autofocusOnce(shouldContinue: @escaping @Sendable () async -> Bool) async throws {
        guard connectionInfo != nil else { throw PtpIpSessionError.notConnected }
        let generation = sessionGeneration
        let result = try await lock.withLock { [self] in
            try await self.autofocusWhileLocked(generation: generation, shouldContinue: shouldContinue)
        }
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonAutofocus)
        }
    }

    private func autofocusWhileLocked(generation: UInt64,
        shouldContinue: @escaping @Sendable () async -> Bool) async throws -> PtpOperationResult {
        guard connectionInfo != nil, sessionGeneration == generation else { throw PtpIpSessionError.notConnected }
        guard deviceInfo?.operations.contains(PtpOpCode.nikonAutofocus) == true else { throw AutofocusError.notAdvertised }
        guard await shouldContinue() else { throw AutofocusError.cancelledBeforeCommand }
        guard connectionInfo != nil, sessionGeneration == generation else { throw PtpIpSessionError.notConnected }
        return try await executeWhileLocked(opCode: PtpOpCode.nikonAutofocus, params: [], expectData: false,
                                            timeout: 8, maxDataBytes: 0, generation: generation)
    }

    /// One read-only DeviceReady attempt. Only DeviceBusy is retryable.
    public func nikonDeviceReady() async throws -> Bool {
        let result = try await execute(opCode: PtpOpCode.nikonDeviceReady, timeoutSeconds: 6, maxDataBytes: 0)
        if result.response.isOK { return true }
        if result.response.code == PtpResponseCode.deviceBusy { return false }
        throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonDeviceReady)
    }

    /// A single explicit camera-card shutter request. OK means accepted, not that a
    /// file was verified on the card. Never automatically retry on timeout/disconnect.
    public func captureToCardOnce() async throws {
        let result = try await execute(opCode: PtpOpCode.nikonCaptureToMedia,
            params: [UInt32.max, 0], timeoutSeconds: 60, maxDataBytes: 0)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonCaptureToMedia)
        }
    }

    /// Nikon STA album operations. They are read-only and must be initiated explicitly.
    public func fetchStorageIDsForAlbum() async throws -> [UInt32] {
        let result = try await execute(opCode: PtpOpCode.getStorageIDs, expectData: true,
                                       timeoutSeconds: 15, maxDataBytes: 4 + 64 * 4)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.getStorageIDs)
        }
        return try NikonSTAAlbum.parseStorageIDs(result.data)
    }

    public func fetchNikonSTAMetadata(storageID: UInt32) async throws -> [NikonSTAAlbumItem] {
        let result = try await execute(opCode: PtpOpCode.nikonObjectsMetadata,
                                       params: [storageID, 0, 0], expectData: true,
                                       timeoutSeconds: 90, maxDataBytes: 2 * 1024 * 1024)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonObjectsMetadata)
        }
        return try NikonSTAAlbum.parseMetadata(result.data, storageID: storageID)
    }

    public func fetchNikonSTAObjectSize(handle: UInt32) async throws -> UInt64 {
        let result = try await execute(opCode: PtpOpCode.nikonObjectSize, params: [handle],
                                       expectData: true, timeoutSeconds: 15, maxDataBytes: 8)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonObjectSize)
        }
        guard result.data.count == 8 else {
            throw PtpIpSessionError.malformed(stage: "STA 相册", detail: "文件大小格式异常")
        }
        return result.data.readLE(UInt64.self, at: 0)
    }

    /// Read at most one bounded chunk; reject over-returns and empty data before EOF.
    public func fetchNikonSTAPartial(handle: UInt32, offset: UInt64, length: Int) async throws -> Data {
        guard (1...(1024 * 1024)).contains(length) else {
            throw PtpIpSessionError.malformed(stage: "STA 相册", detail: "请求块大小非法")
        }
        let result = try await execute(opCode: PtpOpCode.nikonPartialObjectEx,
            params: [handle, UInt32(truncatingIfNeeded: offset), UInt32(truncatingIfNeeded: offset >> 32), UInt32(length), 0],
            expectData: true, timeoutSeconds: 30, maxDataBytes: length)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonPartialObjectEx)
        }
        guard !result.data.isEmpty else {
            throw PtpIpSessionError.malformed(stage: "STA 相册", detail: "分段返回空数据")
        }
        return result.data
    }

    /// Descriptor fetch is read-only. Unsupported forms are deliberately rejected.
    public func fetchExposureDescriptor(_ property: ExposureProperty) async throws -> ExposureDescriptor {
        let result = try await execute(opCode: PtpOpCode.getDevicePropDesc, params: [UInt32(property.rawValue)],
                                       expectData: true, timeoutSeconds: 6, maxDataBytes: 4096)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.getDevicePropDesc)
        }
        return try ExposureDescriptor.parse(result.data, property: property)
    }

    /// One explicit, serialized write, with a fresh descriptor and before/after readback.
    /// No retries. Transport failure after write begins means the camera state is unknown.
    public func writeExposureOnce(property: ExposureProperty, expectedType: UInt16, expectedCurrent: Data, desired: Data,
                                  shouldContinue: @escaping @Sendable () async -> Bool) async throws -> ExposureWriteResult {
        guard connectionInfo != nil else { throw PtpIpSessionError.notConnected }
        let generation = sessionGeneration
        return try await lock.withLock { [self] in
            try await self.performExposureWriteWhileLocked(property: property, expectedType: expectedType,
                expectedCurrent: expectedCurrent, desired: desired, generation: generation, shouldContinue: shouldContinue)
        }
    }

    private func performExposureWriteWhileLocked(property: ExposureProperty, expectedType: UInt16,
        expectedCurrent: Data, desired: Data, generation: UInt64,
        shouldContinue: @escaping @Sendable () async -> Bool) async throws -> ExposureWriteResult {
        do {
            return try await performExposureWrite(property: property, expectedType: expectedType,
                expectedCurrent: expectedCurrent, desired: desired, generation: generation, shouldContinue: shouldContinue)
        } catch PtpIpSessionError.timeout(let stage) {
            if sessionGeneration == generation { await close() }
            throw PtpIpSessionError.timeout(stage: stage)
        } catch PtpIpSessionError.dataLimitExceeded(let limit) {
            if sessionGeneration == generation { await close() }
            throw PtpIpSessionError.dataLimitExceeded(limit: limit)
        }
    }

    private func performExposureWrite(property: ExposureProperty, expectedType: UInt16, expectedCurrent: Data, desired: Data,
                                      generation: UInt64, shouldContinue: @Sendable () async -> Bool) async throws -> ExposureWriteResult {
        func ensureCurrent() throws {
            guard connectionInfo != nil, sessionGeneration == generation else { throw PtpIpSessionError.notConnected }
        }
        try ensureCurrent()
        guard await shouldContinue() else { throw ExposureWriteError.cancelledBeforeWrite }
        try ensureCurrent()
        let code = UInt32(property.rawValue)
        let desc = try await runOperation(opCode: PtpOpCode.getDevicePropDesc, params: [code], expectData: true,
                                          timeout: 6, maxDataBytes: 4096)
        try ensureCurrent()
        guard desc.response.isOK else { throw PtpIpSessionError.operationFailed(code: desc.response.code, opCode: PtpOpCode.getDevicePropDesc) }
        let parsed = try ExposureDescriptor.parse(desc.data, property: property)
        guard parsed.type == expectedType, parsed.writable, parsed.choices.contains(desired) else { throw ExposureWriteError.notWritableOrValueNotAllowed }
        guard await shouldContinue() else { throw ExposureWriteError.cancelledBeforeWrite }
        try ensureCurrent()
        let before = try await runOperation(opCode: PtpOpCode.getDevicePropValue, params: [code], expectData: true,
                                            timeout: 6, maxDataBytes: expectedType == 0xffff ? 257 : 4)
        try ensureCurrent()
        guard before.response.isOK else { throw PtpIpSessionError.operationFailed(code: before.response.code, opCode: PtpOpCode.getDevicePropValue) }
        guard before.data == expectedCurrent else { throw ExposureWriteError.staleCurrentValue }
        if before.data == desired { return .alreadyCurrent }
        guard await shouldContinue() else { throw ExposureWriteError.cancelledBeforeWrite }
        try ensureCurrent()
        let written = try await runOperation(opCode: PtpOpCode.setDevicePropValue, params: [code], expectData: false,
                                             timeout: 6, maxDataBytes: 0, dataOut: desired)
        try ensureCurrent()
        guard written.response.isOK else { throw PtpIpSessionError.operationFailed(code: written.response.code, opCode: PtpOpCode.setDevicePropValue) }
        let after = try await runOperation(opCode: PtpOpCode.getDevicePropValue, params: [code], expectData: true,
                                           timeout: 6, maxDataBytes: expectedType == 0xffff ? 257 : 4)
        try ensureCurrent()
        guard after.response.isOK, after.data == desired else { throw ExposureWriteError.readbackMismatch }
        return .verified
    }

    /// Read one known exposure value; non-OK responses remain unavailable, not defaults.
    public func fetchExposure(_ property: ExposureProperty) async throws -> String? {
        let result = try await execute(opCode: PtpOpCode.getDevicePropValue,
                                       params: [UInt32(property.rawValue)],
                                       expectData: true, timeoutSeconds: 6)
        guard result.response.isOK else { return nil }
        return property.displayValue(result.data)
    }

    /// Explicitly enter live view. The caller must later request stop or close the session.
    /// Camera state is changed; never invoke as an automatic capability probe.
    public func startLiveView() async throws {
        let result = try await execute(opCode: PtpOpCode.nikonStartLiveView, timeoutSeconds: 10)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonStartLiveView)
        }
    }

    /// Stop live view and report the camera response; failure is not treated as success.
    public func stopLiveView() async throws {
        let result = try await execute(opCode: PtpOpCode.nikonEndLiveView, timeoutSeconds: 5)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonEndLiveView)
        }
    }

    /// Fetch one frame. The result is a JPEG byte sequence, not a confirmed decoded image.
    public func fetchLiveViewJPEG() async throws -> Data {
        let result = try await execute(opCode: PtpOpCode.nikonGetLiveViewImage, expectData: true, timeoutSeconds: 12)
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonGetLiveViewImage)
        }
        return try LiveViewFrame.jpeg(from: result.data)
    }

    /// Idle PTP/IP health check. Any valid PTP response demonstrates reachability,
    /// including an operation-not-supported response (matching the Android implementation).
    public func probeConnection() async throws {
        _ = try await execute(opCode: PtpOpCode.getStorageIDs, expectData: true, timeoutSeconds: 4)
    }

    /// Reads exactly one Nikon custom picture-control slot. This is read-only.
    public func fetchPictureControl(index: Int) async throws -> PictureControlSlot {
        guard (1...9).contains(index) else {
            throw PtpIpSessionError.malformed(stage: "PictureControl", detail: "slot must be C-1 through C-9")
        }
        let result = try await execute(
            opCode: PtpOpCode.nikonGetPictureControl,
            params: [UInt32(0x00C8 + index), 0],
            expectData: true, timeoutSeconds: 6
        )
        return PictureControlSlot.decode(index: index, responseCode: result.response.code, data: result.data)
    }

    private func runOperation(
        opCode: UInt16,
        params: [UInt32],
        expectData: Bool,
        timeout: Double,
        maxDataBytes: Int = 32 * 1024 * 1024,
        dataOut: Data? = nil
    ) async throws -> PtpOperationResult {
        transactionId &+= 1
        let tx = transactionId
        let stream = command
        let request = PtpIpCodec.operationRequest(
            dataPhase: dataOut == nil ? PtpIpOperationParser.dataPhaseNoneOrIn : 2,
            opCode: opCode, transactionId: tx, params: params
        )
        try await withPtpIpTimeout(stage: "发送命令", seconds: timeout, onTimeout: { [self] in await self.close() }) {
            try await stream.send(request)
        }
        if let dataOut {
            var start = Data(); start.appendLE(tx); start.appendLE(UInt64(dataOut.count))
            var end = Data(); end.appendLE(tx); end.append(dataOut)
            for packet in [PtpIpCodec.frame(.startData, payload: start), PtpIpCodec.frame(.endData, payload: end)] {
                try await withPtpIpTimeout(stage: "写入数据", seconds: timeout, onTimeout: { [self] in await self.close() }) {
                    try await stream.send(packet)
                }
            }
        }
        var data = Data()
        while true {
            let packet = try await readPacket(stream, stage: "命令", timeout: timeout)
            switch packet.type {
            case PtpIpPacketType.operationResponse.rawValue:
                let response = try PtpIpOperationParser.response(packet)
                guard response.transactionId == tx else {
                    throw PtpIpSessionError.transactionMismatch(expected: tx, actual: response.transactionId)
                }
                return PtpOperationResult(response: response, data: expectData ? data : Data())
            case PtpIpPacketType.startData.rawValue, PtpIpPacketType.data.rawValue, PtpIpPacketType.endData.rawValue:
                let chunk = try PtpIpOperationParser.dataChunk(packet)
                if packet.type == PtpIpPacketType.startData.rawValue,
                   packet.payload.readLE(UInt64.self, at: 4) > UInt64(max(0, maxDataBytes)) {
                    throw PtpIpSessionError.dataLimitExceeded(limit: maxDataBytes)
                }
                guard chunk.bytes.count <= max(0, maxDataBytes - data.count) else {
                    throw PtpIpSessionError.dataLimitExceeded(limit: maxDataBytes)
                }
                guard chunk.transactionId == tx else {
                    throw PtpIpSessionError.transactionMismatch(expected: tx, actual: chunk.transactionId)
                }
                data.append(chunk.bytes)
            case PtpIpPacketType.probeRequest.rawValue:
                try await stream.send(PtpIpCodec.frame(.probeResponse))
            case PtpIpPacketType.event.rawValue, PtpIpPacketType.probeResponse.rawValue:
                continue
            default:
                throw PtpIpSessionError.unexpectedPacket(stage: "命令", type: packet.type)
            }
        }
    }

    // MARK: - Nikon host registration

    /// 0x952B 准备主机注册。返回相机显示的 PIN(空字符串表示无 PIN,等待机身确认)。
    public func prepareHostRegistration() async throws -> String {
        guard deviceInfo?.supportsNikonHostRegistration == true else {
            throw PtpIpSessionError.hostRegistrationUnsupported
        }
        pairingPreparedAtMs = nil
        let result = try await execute(
            opCode: PtpOpCode.nikonGetPairingCode,
            expectData: true,
            timeoutSeconds: config.pairingTimeoutSeconds
        )
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonGetPairingCode)
        }
        let code = try NikonConnectionPolicy.pairingCode(result.data)
        pairingPreparedAtMs = await clock()
        return code
    }

    /// 0x935A 确认注册。必须先等待满 8.5 s(从准备时刻起算);每次准备只允许确认一次。
    public func confirmHostRegistration() async throws {
        guard let preparedAt = pairingPreparedAtMs else {
            throw PtpIpSessionError.pairingNotPrepared
        }
        pairingPreparedAtMs = nil
        let elapsed = await clock() - preparedAt
        try await settle(NikonConnectionPolicy.remainingSettleMs(elapsedMs: elapsed))
        let result = try await execute(
            opCode: PtpOpCode.nikonConfirmPairing,
            params: [UInt32(PtpResponseCode.ok)],
            timeoutSeconds: config.pairingTimeoutSeconds
        )
        guard result.response.isOK else {
            throw PtpIpSessionError.operationFailed(code: result.response.code, opCode: PtpOpCode.nikonConfirmPairing)
        }
    }

    // MARK: - Lifecycle

    public func close() async {
        // Invalidate synchronously before awaiting channel closure, so reentrant calls
        // cannot see the old connection as usable while closeChannels suspends.
        sessionGeneration &+= 1
        connectionInfo = nil
        deviceInfo = nil
        pairingPreparedAtMs = nil
        await closeChannels()
    }

    private func closeChannels() async {
        if let event {
            await event.close()
        }
        event = nil
        await command.close()
    }

    private func readPacket(_ stream: PtpIpPacketStream, stage: String, timeout: Double) async throws -> PtpIpPacket {
        try await withPtpIpTimeout(stage: stage, seconds: timeout, onTimeout: {
            await stream.close()
        }) {
            try await stream.nextPacket()
        }
    }
}
