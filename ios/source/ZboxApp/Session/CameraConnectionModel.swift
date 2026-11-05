import Foundation
import Network
import ZboxCore

/// 连接流程的界面状态。所有 PTP 调用都经过 `ZboxCore.PtpIpSession`。
@MainActor
final class CameraConnectionModel: ObservableObject {
    enum Phase: Equatable {
        case idle, scanning, connecting, connected, disconnecting
    }

    struct DeviceRow: Identifiable {
        let id = UUID()
        let title: String
        let value: String
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var candidates: [HotspotCameraEndpoint] = []
    @Published private(set) var bonjourCandidates: [BonjourCameraCandidate] = []
    @Published var cameraJoinsNetwork = false
    @Published private(set) var deviceRows: [DeviceRow] = []
    @Published private(set) var statusMessage = "尚未连接"
    @Published private(set) var needsRecovery = false
    @Published private(set) var pairingPIN: String?
    @Published private(set) var pairingPrepared = false
    @Published private(set) var pairingBusy = false
    @Published private(set) var batteryLevel: UInt8?
    @Published private(set) var batteryBusy = false
    @Published private(set) var monitorBusy = false
    @Published private(set) var slotsBusy = false
    @Published private(set) var slots: [PictureControlSlot] = []
    @Published private(set) var exposureBusy = false
    @Published private(set) var exposureRows: [DeviceRow] = []
    @Published private(set) var exposureDescriptor: ExposureDescriptor?
    @Published private(set) var exposureEditMessage = "先读取相机支持的候选值"
    @Published private(set) var exposureWriteInProgress = false
    private var exposureWriteCancelled = false
    var canEditExposure: Bool {
        phase == .connected && !exposureBusy && !albumBusy && !captureBusy &&
        !batteryBusy && !slotsBusy && !monitorBusy && !pairingBusy && !pairingPrepared && !liveViewBusy
    }
    @Published private(set) var connectedSTA = false
    @Published private(set) var albumBusy = false
    @Published private(set) var albumItems: [NikonSTAAlbumItem] = []
    @Published private(set) var albumMessage = "手动读取相机相册（仅 STA）"
    @Published private(set) var albumProgress: Double?
    @Published private(set) var albumDownloadURL: URL?
    @Published private(set) var albumPreviewJPEG: Data?
    @Published private(set) var albumPreviewTitle = ""
    private var albumCancelRequested = false
    @Published private(set) var captureBusy = false
    @Published private(set) var captureMessage = "拍摄到机身存储卡"
    private var captureCancelRequested = false
    var canCapture: Bool {
        phase == .connected && !captureBusy && !albumBusy && !batteryBusy && !monitorBusy &&
        !slotsBusy && !exposureBusy && !pairingBusy && !pairingPrepared &&
        (liveViewPhase == .idle || liveViewPhase == .streaming)
    }
    @Published private(set) var clientName = PtpIpConnector.defaultPrimaryName
    enum LiveViewPhase: Equatable { case idle, starting, streaming, stopping }
    @Published private(set) var liveViewPhase: LiveViewPhase = .idle
    @Published private(set) var liveViewJPEG: Data?
    @Published private(set) var liveViewFrames = 0
    @Published private(set) var liveViewMessage = "请手动开启取景"
    var liveViewBusy: Bool { liveViewPhase != .idle }
    var canStartLiveView: Bool {
        phase == .connected && !liveViewBusy && !batteryBusy && !monitorBusy &&
        !slotsBusy && !exposureBusy && !albumBusy && !captureBusy && !pairingBusy && !pairingPrepared
    }
    @Published var manualHost = ""
    @Published private(set) var guidText = "加载中…"

    let log = DiagnosticLog()

    private var session: PtpIpSession?
    private var monitorTask: Task<Void, Never>?
    private var liveViewTask: Task<Void, Never>?
    private var liveViewStopRequested = false

    // MARK: - Identity

    func loadIdentity() {
        do {
            let guid = try ClientIdentityStore.loadOrCreateGuid()
            guidText = ClientIdentityStore.hex(guid)
            log.add("PTP/IP 客户端身份已加载（GUID 不写入日志）")
        } catch {
            guidText = "读取失败：\(error)"
            log.add("Keychain 读取 GUID 失败：\(error)")
        }
    }

    func saveGuid(_ text: String) {
        guard let guid = ClientIdentityStore.parseHex(text) else {
            statusMessage = "GUID 须为 32 位十六进制字符"
            return
        }
        do {
            try ClientIdentityStore.saveGuid(guid)
            guidText = ClientIdentityStore.hex(guid)
            statusMessage = "GUID 已保存；如相机仍拒绝，请在机身重新配对"
            log.add("GUID 已手动保存")
        } catch {
            statusMessage = "保存 GUID 失败：\(error)"
        }
    }

    func resetGuid() {
        do {
            try ClientIdentityStore.resetGuid()
            loadIdentity()
            statusMessage = "GUID 已重置；请在相机菜单「连接到计算机」中重新配对"
        } catch {
            statusMessage = "重置 GUID 失败：\(error)"
        }
    }

    // MARK: - Discovery and connection

    func scan() async {
        guard phase == .idle else { return }
        phase = .scanning
        candidates = []
        log.add("开始热点相机扫描（bridge/en 私有子网，TCP 15740）")
        let found = await HotspotCameraScanner.scan()
        let decision = HotspotConnectionPolicy.decide(found: found)
        switch decision.action {
        case .none:
            statusMessage = "未发现相机：请确认手机热点已开启、相机已连接到该热点，或手动输入相机 IP"
            log.add("扫描结束：0 台")
            phase = .idle
        case .autoConnect:
            log.add("扫描结束：1 台，自动连接")
            phase = .idle
            await connect(decision.candidates[0])
        case .choose:
            candidates = decision.candidates
            statusMessage = "发现 \(decision.candidates.count) 台候选相机，请选择"
            log.add("扫描结束：\(decision.candidates.count) 台，等待用户选择")
            phase = .idle
        }
    }

    func scanBonjour() async {
        guard phase == .idle else { return }
        phase = .scanning
        bonjourCandidates = []
        log.add("开始 Bonjour 服务发现")
        let result = await BonjourCameraScanner.scan()
        bonjourCandidates = result.candidates
        phase = .idle
        if result.candidates.isEmpty {
            statusMessage = result.failures.isEmpty
                ? "未发现 Bonjour 服务；相机可能不广播，可尝试热点扫描或手动 IP"
                : "Bonjour 发现失败；请检查本地网络权限或尝试手动 IP"
        } else {
            statusMessage = "发现 \(result.candidates.count) 个 Bonjour 候选服务，请选择；发现不代表相机已认证"
        }
        log.add("Bonjour 发现结束：\(result.candidates.count) 个候选，\(result.failures.count) 项浏览错误")
    }

    func connectBonjour(_ candidate: BonjourCameraCandidate) async {
        guard phase == .idle else { return }
        await openSession(endpoint: candidate.endpoint, localAddress: nil,
                          staMode: cameraJoinsNetwork, displayName: "Bonjour 服务")
    }

    func connect(_ endpoint: HotspotCameraEndpoint) async {
        guard phase == .idle else { return }
        await openSession(host: endpoint.cameraAddress, localAddress: endpoint.phoneAddress)
    }

    func connectManual() async {
        guard phase == .idle else { return }
        let host = manualHost.trimmingCharacters(in: .whitespaces)
        guard HotspotAddressPlanner.parseIPv4(host) != nil else {
            statusMessage = "请输入有效的 IPv4 地址，例如 172.20.10.2"
            return
        }
        await openSession(host: host, localAddress: nil, staMode: cameraJoinsNetwork)
    }

    func disconnect(reason: String = "已断开") async {
        // Block new work, let an in-flight live-view transaction finish, then stop it.
        // iOS may suspend the app before cleanup finishes; camera state is not guaranteed.
        guard phase != .disconnecting else { return }
        let wasCapturing = captureBusy
        let wasWritingExposure = exposureWriteInProgress
        exposureWriteCancelled = true
        phase = .disconnecting
        captureCancelRequested = true
        albumCancelRequested = true
        let oldSession = session
        await stopLiveView()
        session = nil
        monitorTask?.cancel()
        monitorTask = nil
        phase = .idle
        monitorBusy = false
        slotsBusy = false
        exposureBusy = false
        exposureRows = []
        exposureDescriptor = nil
        exposureWriteInProgress = false
        connectedSTA = false
        albumBusy = false
        albumItems = []
        albumPreviewJPEG = nil
        albumPreviewTitle = ""
        albumProgress = nil
        captureBusy = false
        liveViewStopRequested = true
        liveViewPhase = .idle
        liveViewJPEG = nil
        slots = []
        deviceRows = []
        batteryLevel = nil
        batteryBusy = false
        pairingPIN = nil
        pairingPrepared = false
        pairingBusy = false
        needsRecovery = false
        statusMessage = wasCapturing ? "拍摄结果可能未知，请先检查相机存储卡，勿重复拍摄" :
            (wasWritingExposure ? "曝光设置结果可能未知，请先在机身检查，勿重复写入" : reason)
        log.add("会话已关闭")
        await oldSession?.close()
    }

    private func openSession(host: String, localAddress: String?, staMode: Bool? = nil) async {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: PtpIpCodec.defaultPort)!
        )
        await openSession(endpoint: endpoint, localAddress: localAddress,
                          staMode: staMode ?? (localAddress != nil), displayName: host)
    }

    private func openSession(endpoint: NWEndpoint, localAddress: String?, staMode: Bool, displayName: String) async {
        guard phase == .idle else { return }
        phase = .connecting
        let oldSession = session
        session = nil
        await oldSession?.close()
        batteryLevel = nil
        batteryBusy = false
        needsRecovery = false
        pairingPIN = nil
        pairingPrepared = false
        pairingBusy = false
        log.add("连接 \(displayName)（\(staMode ? "相机加入网络" : "手机连相机热点")）")
        do {
            let guid = try ClientIdentityStore.loadOrCreateGuid()
            let connected = try await PtpIpConnector.connect { name in
                let command = NetworkPtpIpChannel(endpoint: endpoint, localAddress: localAddress)
                try await command.open(timeoutSeconds: 6)
                return PtpIpSession(
                    commandChannel: command,
                    openEventChannel: {
                        let event = NetworkPtpIpChannel(endpoint: endpoint, localAddress: localAddress)
                        try await event.open(timeoutSeconds: 6)
                        return event
                    },
                    config: PtpIpSessionConfig(initiatorGuid: guid, initiatorName: name, staMode: staMode)
                )
            }
            clientName = connected.usedName
            if connected.usedName != PtpIpConnector.defaultPrimaryName {
                log.add("相机接受兼容客户端名称（名称已生效，未记录具体值）")
            }
            session = connected.session
            log.add("握手完成：连接号=\(connected.info.connectionNumber)，会话已打开")

            let device = try await connected.session.fetchDeviceInfo()
            guard device.isNikon else {
                throw PtpIpSessionError.malformed(stage: "DeviceInfo", detail: "目标不是 Nikon 设备，已停止")
            }
            deviceRows = [
                DeviceRow(title: "厂商", value: device.manufacturer),
                DeviceRow(title: "型号", value: NikonConnectionPolicy.normalizedModel(device.model)),
                DeviceRow(title: "固件", value: device.deviceVersion),
                DeviceRow(title: "序列号", value: maskSerial(device.serialNumber)),
                DeviceRow(title: "主机注册", value: device.supportsNikonHostRegistration ? "相机支持" : "相机未广告"),
            ]
            log.add("设备信息已读取：Nikon \(NikonConnectionPolicy.normalizedModel(device.model))，操作数 \(device.operations.count)")
            phase = .connected
            connectedSTA = staMode
            statusMessage = "已连接 \(NikonConnectionPolicy.normalizedModel(device.model))"
            startMonitor(for: connected.session)
        } catch PtpIpSessionError.initFailed(let reason) where reason == PtpIpInitFailReason.rejected.rawValue {
            needsRecovery = true
            phase = .idle
            statusMessage = "相机拒绝了当前客户端身份（InitFail reason=1）"
            log.add("InitFail reason=1：相机拒绝客户端身份，已尝试兼容名称")
        } catch {
            phase = .idle
            statusMessage = "连接失败：\(describe(error))"
            log.add("连接失败：\(describe(error))")
            await session?.close()
            session = nil
        }
    }

    // MARK: - Explicit live view

    func startLiveView() {
        guard let active = session, canStartLiveView else { return }
        liveViewStopRequested = false
        liveViewPhase = .starting
        liveViewJPEG = nil
        liveViewFrames = 0
        liveViewMessage = "正在开启取景…"
        liveViewTask = Task { [weak self] in
            guard let self else { return }
            await self.runLiveView(active)
        }
    }

    func stopLiveView() async {
        guard let task = liveViewTask else { return }
        liveViewStopRequested = true
        liveViewPhase = .stopping
        liveViewMessage = "正在停止取景…"
        // Do not cancel an in-flight PTP transaction: finish it before issuing 0x9202.
        await task.value
    }

    private func runLiveView(_ active: PtpIpSession) async {
        if liveViewStopRequested {
            liveViewPhase = .idle
            liveViewTask = nil
            liveViewStopRequested = false
            liveViewMessage = "取景未开启"
            return
        }
        var started = false
        var fatal = false
        do {
            try await active.startLiveView()
            started = true
            guard session === active else { return }
            if !liveViewStopRequested {
                liveViewPhase = .streaming
                liveViewMessage = "取景中"
            }
            log.add("实时取景已开启")
            var misses = 0
            while !liveViewStopRequested && session === active {
                do {
                    let bytes = try await active.fetchLiveViewJPEG()
                    guard session === active else { return }
                    if liveViewStopRequested { break }
                    liveViewJPEG = bytes
                    liveViewFrames += 1
                    misses = 0
                } catch PtpIpSessionError.timeout(let stage) {
                    liveViewMessage = "取景超时（\(stage)），连接已关闭"
                    fatal = true
                    break
                } catch PtpIpSessionError.operationFailed(let code, let op) {
                    misses += 1
                    if misses >= 5 {
                        liveViewMessage = String(format: "取景命令 0x%04X 返回 0x%04X，已停止", op, code)
                        break
                    }
                } catch PtpIpSessionError.malformed {
                    misses += 1
                    if misses >= 5 { liveViewMessage = "连续收到无效取景帧，已停止"; break }
                } catch {
                    liveViewMessage = "取景连接异常：\(describe(error))"
                    fatal = true
                    break
                }
                // Limit request rate; no overlapping frame requests or unbounded queue.
                try? await Task.sleep(nanoseconds: misses > 0 ? 400_000_000 : 100_000_000)
            }
        } catch PtpIpSessionError.timeout(let stage) {
            liveViewMessage = "开启取景超时（\(stage)），连接已关闭"
            fatal = true
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            liveViewMessage = String(format: "开启取景失败：0x%04X 返回 0x%04X", op, code)
        } catch {
            liveViewMessage = "开启取景失败：\(describe(error))"
            fatal = true
        }
        guard session === active else { return }
        if started && !fatal {
            liveViewPhase = .stopping
            do {
                try await active.stopLiveView()
                if liveViewStopRequested { liveViewMessage = "取景已停止" }
                log.add("实时取景已停止")
            } catch {
                liveViewMessage = "停止取景失败，连接已关闭：\(describe(error))"
                fatal = true
            }
        }
        guard session === active else { return }
        let finalMessage = liveViewMessage
        liveViewJPEG = nil
        liveViewPhase = .idle
        liveViewTask = nil
        liveViewStopRequested = false
        if fatal {
            // Avoid recursively awaiting this task via disconnect().
            session = nil
            monitorTask?.cancel()
            monitorTask = nil
            if phase != .disconnecting { phase = .idle }
            monitorBusy = false
            batteryBusy = false
            slotsBusy = false
            exposureBusy = false
            exposureRows = []
            exposureDescriptor = nil
            exposureWriteInProgress = false
            connectedSTA = false
            albumBusy = false
            albumItems = []
            albumProgress = nil
            captureBusy = false
            pairingBusy = false
            pairingPrepared = false
            pairingPIN = nil
            batteryLevel = nil
            slots = []
            deviceRows = []
            statusMessage = finalMessage
            log.add(finalMessage)
            await active.close()
        }
    }

    // MARK: - Idle connection monitoring

    private func startMonitor(for active: PtpIpSession) {
        monitorTask?.cancel()
        monitorTask = Task { [weak self, weak active] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                guard let self, let active else { return }
                await self.probeIfIdle(active)
            }
        }
    }

    private func probeIfIdle(_ active: PtpIpSession) async {
        guard session === active, phase == .connected, !batteryBusy, !pairingBusy,
              !pairingPrepared, !slotsBusy, !monitorBusy, !exposureBusy, !albumBusy, !captureBusy, !liveViewBusy else { return }
        monitorBusy = true
        do {
            try await active.probeConnection()
            guard session === active else { return }
            monitorBusy = false
        } catch {
            guard session === active, !Task.isCancelled else { return }
            monitorBusy = false
            log.add("连接监测失败，旧会话已关闭：\(describe(error))")
            await disconnect(reason: "相机连接已中断，请重新连接")
        }
    }

    // MARK: - Read-only picture controls

    func refreshSlots() async {
        guard let active = session, phase == .connected, !slotsBusy, !batteryBusy,
              !monitorBusy, !pairingBusy, !pairingPrepared, !exposureBusy, !albumBusy, !captureBusy, !liveViewBusy else { return }
        slotsBusy = true
        slots = []
        defer { if session === active { slotsBusy = false } }
        for index in 1...9 {
            do {
                let slot = try await active.fetchPictureControl(index: index)
                guard session === active, phase == .connected else { return }
                slots.append(slot)
            } catch {
                guard session === active, phase == .connected else { return }
                log.add("读取槽位失败，旧会话已关闭：\(describe(error))")
                await disconnect(reason: "读取 C-\(index) 时连接中断，请重新连接")
                return
            }
        }
        log.add("C-1…C-9 只读扫描结束，未修改相机数据")
    }

    // MARK: - Single explicit shutter (camera card)

    func cancelPendingCapture() {
        // Do not cancel an on-wire shutter command; its outcome may be unknown.
        if captureBusy { captureCancelRequested = true }
    }

    func captureOneToCard() async {
        guard let active = session, canCapture else { return }
        captureBusy = true
        captureCancelRequested = false
        captureMessage = "正在停止取景并检查机身…"
        defer { if session === active { captureBusy = false } }
        await stopLiveView()
        guard session === active, phase == .connected, !captureCancelRequested else {
            if session === active { captureMessage = "拍摄已取消，未发送快门命令" }
            return
        }
        do {
            var ready = false
            for _ in 0..<20 {
                guard session === active, phase == .connected, !captureCancelRequested else {
                    if session === active { captureMessage = "拍摄已取消，未发送快门命令" }
                    return
                }
                if try await active.nikonDeviceReady() { ready = true; break }
                try await Task.sleep(nanoseconds: 200_000_000)
            }
            guard ready else { captureMessage = "机身尚未就绪，未发送快门命令"; return }
            guard session === active, phase == .connected, !captureCancelRequested else {
                if session === active { captureMessage = "拍摄已取消，未发送快门命令" }
                return
            }
            captureMessage = "快门命令已发送，等待相机响应…"
            try await active.captureToCardOnce()
            guard session === active, phase == .connected else { return }
            captureMessage = "相机已接受快门命令；尚未验证照片已写入存储卡，请在机身检查。"
            log.add("单次快门命令已接受；未核实落卡")
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active {
                captureMessage = String(format: "相机拒绝命令 0x%04X：0x%04X；请检查机身", op, code)
            }
        } catch {
            guard session === active, phase == .connected else { return }
            captureMessage = "拍摄结果未知；请先检查相机，勿立即重试。\(describe(error))"
            log.add(captureMessage)
            await disconnect(reason: captureMessage)
        }
    }

    // MARK: - Explicit Nikon STA album (read-only)

    func cancelAlbumWork() {
        if albumBusy { albumCancelRequested = true; albumMessage = "正在取消；等待当前命令完成…" }
    }

    func loadSTAAlbum() async {
        guard let active = session, phase == .connected, connectedSTA, !albumBusy,
              !liveViewBusy, !batteryBusy, !slotsBusy, !monitorBusy, !exposureBusy,
              !pairingBusy, !pairingPrepared, !captureBusy else { return }
        albumBusy = true
        albumCancelRequested = false
        albumItems = []
        albumPreviewJPEG = nil
        albumPreviewTitle = ""
        albumMessage = "正在读取存储和元数据…"
        defer { if session === active { albumBusy = false } }
        do {
            let ids = try await active.fetchStorageIDsForAlbum()
            var items: [NikonSTAAlbumItem] = []
            var total = 0
            for id in ids {
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                let batch = try await active.fetchNikonSTAMetadata(storageID: id)
                total += batch.count
                items += batch
                // Keep at most 500 between volumes rather than retaining all card entries.
                items.sort { ($0.capturedAt ?? "") > ($1.capturedAt ?? "") }
                if items.count > 500 { items.removeSubrange(500...) }
            }
            guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
            albumItems = items
            albumMessage = total > 500 ? "共 \(total) 项，仅展示前 500 项" : "读取到 \(total) 项（文件名为占位名）"
            log.add("STA 相册元数据读取完成，未修改相机文件")
        } catch is CancellationError {
            if session === active { albumMessage = "已取消相册读取" }
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active { albumMessage = String(format: "相机拒绝读取：0x%04X 返回 0x%04X", op, code) }
        } catch {
            guard session === active, phase == .connected else { return }
            albumMessage = "相册读取异常，连接已关闭：\(describe(error))"
            await disconnect(reason: albumMessage)
        }
    }

    /// Read at most the first 256 KiB; embedded previews outside the prefix are not fetched.
    func previewSTAItem(_ item: NikonSTAAlbumItem) async {
        guard let active = session, phase == .connected, connectedSTA, !albumBusy,
              !liveViewBusy, !batteryBusy, !slotsBusy, !monitorBusy, !exposureBusy,
              !pairingBusy, !pairingPrepared, !captureBusy, albumItems.contains(item) else { return }
        albumBusy = true
        albumCancelRequested = false
        albumPreviewJPEG = nil
        albumPreviewTitle = item.placeholderName
        albumMessage = "正在读取受限文件头预览…"
        defer { if session === active { albumBusy = false } }
        do {
            let size = try await active.fetchNikonSTAObjectSize(handle: item.handle)
            guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
            let limit = Int(min(size, UInt64(EmbeddedJPEG.maxPrefixBytes)))
            guard limit > 0 else { albumMessage = "文件大小未知，无法预览"; return }
            var prefix = Data()
            while prefix.count < limit {
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                let chunk = try await active.fetchNikonSTAPartial(handle: item.handle, offset: UInt64(prefix.count), length: limit - prefix.count)
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                prefix.append(chunk)
            }
            guard let jpeg = EmbeddedJPEG.largestComplete(in: prefix) else {
                albumMessage = "前 256 KiB 未发现完整内嵌 JPEG；可选择下载原文件"; return
            }
            albumPreviewJPEG = jpeg
            albumMessage = "已读取内嵌 JPEG 标记；若图片不可解码，请下载原文件查看"
        } catch is CancellationError {
            if session === active { albumMessage = "预览读取已取消" }
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active { albumMessage = String(format: "相机拒绝预览：0x%04X 返回 0x%04X", op, code) }
        } catch {
            guard session === active, phase == .connected else { return }
            albumMessage = "预览读取异常，连接已关闭：\(describe(error))"
            await disconnect(reason: albumMessage)
        }
    }

    private func mayContinueAlbum(_ active: PtpIpSession) -> Bool {
        session === active && phase == .connected && albumBusy && !albumCancelRequested
    }

    private func reportAlbumProgress(_ active: PtpIpSession, offset: UInt64, total: UInt64) {
        guard mayContinueAlbum(active), total > 0 else { return }
        albumProgress = Double(offset) / Double(total)
        albumMessage = "下载中：\(offset) / \(total) 字节"
    }

    func downloadSTAItem(_ item: NikonSTAAlbumItem) async {
        guard let active = session, phase == .connected, connectedSTA, !albumBusy,
              !liveViewBusy, !batteryBusy, !slotsBusy, !monitorBusy, !exposureBusy,
              !pairingBusy, !pairingPrepared, !captureBusy, albumItems.contains(item) else { return }
        albumBusy = true
        albumCancelRequested = false
        albumProgress = 0
        albumDownloadURL = nil
        albumMessage = "正在读取文件大小…"
        defer { if session === active { albumBusy = false; albumProgress = nil } }
        do {
            let size = try await active.fetchNikonSTAObjectSize(handle: item.handle)
            guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
            let completed = try await STAFileDownload.download(size: size, fileName: item.placeholderName,
                shouldContinue: { [weak self, weak active] in
                    guard let self, let active else { return false }
                    return await self.mayContinueAlbum(active)
                }, fetch: { offset, length in
                    try await active.fetchNikonSTAPartial(handle: item.handle, offset: offset, length: length)
                }, progress: { [weak self, weak active] offset, size in
                    guard let self, let active else { return }
                    await self.reportAlbumProgress(active, offset: offset, total: size)
                })
            guard session === active, phase == .connected, !albumCancelRequested else {
                try? FileManager.default.removeItem(at: completed.deletingLastPathComponent())
                return
            }
            albumDownloadURL = completed
            albumMessage = "下载完成；请使用分享保存。名称为占位名，临时文件可能被系统清理。"
            log.add("STA 相册原文件下载完成，长度已核对；未验证文件内容")
        } catch is CancellationError {
            if session === active { albumMessage = "下载已取消；不完整文件已删除" }
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active { albumMessage = String(format: "相机拒绝下载：0x%04X 返回 0x%04X", op, code) }
        } catch STAFileDownloadError.invalidSize {
            if session === active { albumMessage = "文件大小为零或超过 32 GiB 安全上限" }
        } catch STAFileDownloadError.insufficientSpace {
            if session === active { albumMessage = "设备可用空间不足" }
        } catch {
            guard session === active, phase == .connected else { return }
            albumMessage = "下载异常，不完整文件已删除：\(describe(error))"
            // A size-limit or transport error can leave a partial PTP transaction on the wire.
            await disconnect(reason: albumMessage)
        }
    }

    // MARK: - Explicit exposure editing with typed descriptor and readback

    func cancelPendingExposureWrite() {
        // A write already on the wire cannot safely be cancelled or retried.
        if exposureWriteInProgress { exposureWriteCancelled = true }
    }

    func loadExposureDescriptor(_ property: ExposureProperty) async {
        guard let active = session, canEditExposure else { return }
        exposureBusy = true
        exposureDescriptor = nil
        exposureEditMessage = "正在读取机身描述符…"
        defer { if session === active { exposureBusy = false } }
        do {
            let descriptor = try await active.fetchExposureDescriptor(property)
            guard session === active, phase == .connected else { return }
            exposureDescriptor = descriptor
            exposureEditMessage = descriptor.writable ? "请选择机身声明的候选值" : "机身报告此属性只读，无法修改"
        } catch PtpIpSessionError.operationFailed(let code, _) {
            if session === active { exposureEditMessage = String(format: "机身不提供描述符：0x%04X", code) }
        } catch PtpIpSessionError.malformed {
            if session === active { exposureEditMessage = "描述符类型或范围未识别，已禁止写入" }
        } catch {
            guard session === active, phase == .connected else { return }
            exposureEditMessage = "读取描述符失败：\(describe(error))"
            await disconnect(reason: exposureEditMessage)
        }
    }

    private func mayCommitExposure(_ active: PtpIpSession) -> Bool {
        session === active && phase == .connected && exposureWriteInProgress && !exposureWriteCancelled
    }

    func writeExposure(_ descriptor: ExposureDescriptor, desired: Data) async {
        guard let active = session, canEditExposure, exposureDescriptor == descriptor,
              descriptor.writable, descriptor.choices.contains(desired) else { return }
        exposureBusy = true
        exposureWriteInProgress = true
        exposureWriteCancelled = false
        exposureEditMessage = "正在重新核实机身状态并读回…"
        defer {
            if session === active { exposureBusy = false; exposureWriteInProgress = false }
        }
        do {
            let result = try await active.writeExposureOnce(property: descriptor.property,
                expectedType: descriptor.type, expectedCurrent: descriptor.current, desired: desired, shouldContinue: { [weak self, weak active] in
                    guard let self, let active else { return false }
                    return await self.mayCommitExposure(active)
                })
            guard session === active, phase == .connected else { return }
            exposureDescriptor = nil
            exposureEditMessage = result == .verified ? "写入后读回一致（未经真机验收）" : "机身当前已是所选值，无需写入"
            log.add("曝光写入已完成读回校验（值不写入日志）")
        } catch ExposureWriteError.cancelledBeforeWrite {
            if session === active { exposureEditMessage = "已取消，未发送写入命令" }
        } catch ExposureWriteError.staleCurrentValue {
            if session === active { exposureDescriptor = nil; exposureEditMessage = "机身当前值已变化，未写入；请重新读取" }
        } catch ExposureWriteError.notWritableOrValueNotAllowed {
            if session === active { exposureDescriptor = nil; exposureEditMessage = "机身不再允许所选值，未写入；请重新读取" }
        } catch ExposureWriteError.readbackMismatch {
            if session === active { exposureDescriptor = nil; exposureEditMessage = "机身读回不一致；设置结果未确认，请在机身检查" }
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active { exposureDescriptor = nil; exposureEditMessage = String(format: "机身拒绝命令 0x%04X：0x%04X；请检查设置", op, code) }
        } catch {
            guard session === active, phase == .connected else { return }
            exposureDescriptor = nil
            exposureEditMessage = "设置结果可能未知；请在机身检查，勿重复写入：\(describe(error))"
            log.add(exposureEditMessage)
            await disconnect(reason: exposureEditMessage)
        }
    }

    // MARK: - Read-only exposure values (never write camera settings)

    func refreshExposure() async {
        guard let active = session, phase == .connected, !exposureBusy, !batteryBusy,
              !slotsBusy, !monitorBusy, !pairingBusy, !pairingPrepared, !albumBusy, !captureBusy, !liveViewBusy else { return }
        exposureBusy = true
        exposureRows = []
        defer { if session === active { exposureBusy = false } }
        for property in ExposureProperty.exposureCases {
            do {
                let value = try await active.fetchExposure(property)
                guard session === active, phase == .connected else { return }
                exposureRows.append(DeviceRow(title: property.title, value: value ?? "不可读 / 格式未知"))
            } catch {
                guard session === active, phase == .connected else { return }
                log.add("曝光信息读取失败：\(describe(error))")
                await disconnect(reason: "曝光信息读取中断，连接已关闭；请重新连接")
                return
            }
        }
        log.add("只读曝光信息读取完成；未修改相机设置")
    }

    // MARK: - Read-only battery

    func refreshBattery() async {
        guard let active = session, phase == .connected, !batteryBusy, !pairingPrepared, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !captureBusy, !liveViewBusy else { return }
        batteryBusy = true
        defer { if session === active { batteryBusy = false } }
        do {
            let value = try await active.fetchBatteryLevel()
            guard session === active, phase == .connected else { return }
            batteryLevel = value
            if let value {
                log.add("电池电量读取成功：\(value)%")
            } else {
                log.add("相机未返回有效的电池电量")
            }
        } catch PtpIpSessionError.timeout(let stage) {
            guard session === active, phase == .connected else { return }
            await handleTimeout(stage)
        } catch {
            guard session === active, phase == .connected else { return }
            batteryLevel = nil
            log.add("电池电量读取失败：\(describe(error))")
        }
    }

    // MARK: - Nikon host registration

    func prepareRegistration() async {
        guard let session, phase == .connected, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !captureBusy, !liveViewBusy else { return }
        pairingBusy = true
        defer { if self.session === session { pairingBusy = false } }
        do {
            let pin = try await session.prepareHostRegistration()
            guard self.session === session else { return }
            pairingPrepared = true
            pairingPIN = pin.isEmpty ? nil : pin
            statusMessage = pin.isEmpty
                ? "已准备注册：请在相机上完成「连接到计算机 → 配对」，再点确认"
                : "请在相机输入 PIN，完成后点确认"
            log.add("主机注册已准备（PIN 不写入日志）")
        } catch PtpIpSessionError.timeout(let stage) {
            guard self.session === session else { return }
            await handleTimeout(stage)
        } catch {
            guard self.session === session else { return }
            statusMessage = "准备注册失败：\(describe(error))"
            log.add("0x952B 失败：\(describe(error))")
        }
    }

    func confirmRegistration() async {
        guard let session, phase == .connected, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !captureBusy, !liveViewBusy else { return }
        pairingBusy = true
        defer { if self.session === session { pairingBusy = false } }
        do {
            try await session.confirmHostRegistration()
            guard self.session === session else { return }
            pairingPrepared = false
            pairingPIN = nil
            statusMessage = "主机注册已确认"
            log.add("0x935A 注册确认成功")
        } catch PtpIpSessionError.timeout(let stage) {
            guard self.session === session else { return }
            await handleTimeout(stage)
        } catch {
            guard self.session === session else { return }
            pairingPrepared = false
            statusMessage = "确认注册失败：\(describe(error))；请重新准备"
            log.add("0x935A 失败：\(describe(error))")
        }
    }

    // MARK: - Helpers

    private func handleTimeout(_ stage: String) async {
        log.add("\(stage)超时，已关闭失效会话")
        await disconnect(reason: "\(stage)超时，连接已关闭；请重新连接")
    }

    private func maskSerial(_ serial: String) -> String {
        guard serial.count > 4 else { return "••••" }
        return String(repeating: "•", count: serial.count - 4) + serial.suffix(4)
    }

    private func describe(_ error: Error) -> String {
        switch error {
        case PtpIpSessionError.timeout(let stage):
            return "\(stage)超时"
        case PtpIpSessionError.initFailed(let reason):
            return "相机拒绝初始化（reason=\(reason.map(String.init) ?? "missing")）"
        case PtpIpSessionError.operationFailed(let code, let opCode):
            return String(format: "操作 0x%04X 返回 0x%04X", opCode, code)
        case PtpIpSessionError.hostRegistrationUnsupported:
            return "相机未广告主机注册操作"
        case PtpIpSessionError.pairingNotPrepared:
            return "请先准备注册"
        case PtpIpSessionError.pairingCodeUnrecognized:
            return "配对数据格式无法识别，未确认注册"
        default:
            return String(describing: error)
        }
    }
}
