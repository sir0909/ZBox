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
    @Published private(set) var connectedSTA = false
    @Published private(set) var albumBusy = false
    @Published private(set) var albumItems: [NikonSTAAlbumItem] = []
    @Published private(set) var albumMessage = "手动读取相机相册（仅 STA）"
    @Published private(set) var albumProgress: Double?
    @Published private(set) var albumDownloadURL: URL?
    private var albumCancelRequested = false
    @Published private(set) var clientName = PtpIpConnector.defaultPrimaryName
    enum LiveViewPhase: Equatable { case idle, starting, streaming, stopping }
    @Published private(set) var liveViewPhase: LiveViewPhase = .idle
    @Published private(set) var liveViewJPEG: Data?
    @Published private(set) var liveViewFrames = 0
    @Published private(set) var liveViewMessage = "请手动开启取景"
    var liveViewBusy: Bool { liveViewPhase != .idle }
    var canStartLiveView: Bool {
        phase == .connected && !liveViewBusy && !batteryBusy && !monitorBusy &&
        !slotsBusy && !exposureBusy && !albumBusy && !pairingBusy && !pairingPrepared
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
        phase = .disconnecting
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
        connectedSTA = false
        albumBusy = false
        albumItems = []
        albumProgress = nil
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
        statusMessage = reason
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
            connectedSTA = false
            albumBusy = false
            albumItems = []
            albumProgress = nil
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
              !pairingPrepared, !slotsBusy, !monitorBusy, !exposureBusy, !albumBusy, !liveViewBusy else { return }
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
              !monitorBusy, !pairingBusy, !pairingPrepared, !exposureBusy, !albumBusy, !liveViewBusy else { return }
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

    // MARK: - Explicit Nikon STA album (read-only)

    func cancelAlbumWork() {
        if albumBusy { albumCancelRequested = true; albumMessage = "正在取消；等待当前命令完成…" }
    }

    func loadSTAAlbum() async {
        guard let active = session, phase == .connected, connectedSTA, !albumBusy,
              !liveViewBusy, !batteryBusy, !slotsBusy, !monitorBusy, !exposureBusy,
              !pairingBusy, !pairingPrepared else { return }
        albumBusy = true
        albumCancelRequested = false
        albumItems = []
        albumMessage = "正在读取存储和元数据…"
        defer { if session === active { albumBusy = false } }
        do {
            let ids = try await active.fetchStorageIDsForAlbum()
            var items: [NikonSTAAlbumItem] = []
            for id in ids {
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                items += try await active.fetchNikonSTAMetadata(storageID: id)
            }
            guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
            items.sort { ($0.capturedAt ?? "") > ($1.capturedAt ?? "") }
            albumItems = Array(items.prefix(500))
            albumMessage = items.count > 500 ? "共 \(items.count) 项，仅展示前 500 项" : "读取到 \(items.count) 项（文件名为占位名）"
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

    func downloadSTAItem(_ item: NikonSTAAlbumItem) async {
        guard let active = session, phase == .connected, connectedSTA, !albumBusy,
              !liveViewBusy, !batteryBusy, !slotsBusy, !monitorBusy, !exposureBusy,
              !pairingBusy, !pairingPrepared, albumItems.contains(item) else { return }
        albumBusy = true
        albumCancelRequested = false
        albumProgress = 0
        albumDownloadURL = nil
        albumMessage = "正在读取文件大小…"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ZBox-" + UUID().uuidString, isDirectory: true)
        let partial = dir.appendingPathComponent("partial")
        let completed = dir.appendingPathComponent(item.placeholderName)
        var finished = false
        defer {
            if !finished { try? FileManager.default.removeItem(at: dir) }
            if session === active { albumBusy = false; albumProgress = nil }
        }
        do {
            let size = try await active.fetchNikonSTAObjectSize(handle: item.handle)
            guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
            // Bound disk use. 0x9434 metadata size is deliberately not used here.
            guard size > 0, size <= 32 * 1024 * 1024 * 1024 else {
                albumMessage = "文件大小为零或超过 32 GiB 安全上限"; return
            }
            let values = try? FileManager.default.temporaryDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            if let free = values?.volumeAvailableCapacityForImportantUsage,
               free >= 0, UInt64(free) < size + 8 * 1024 * 1024 {
                albumMessage = "设备可用空间不足"; return
            }
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            guard FileManager.default.createFile(atPath: partial.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            let writer = try FileHandle(forWritingTo: partial)
            defer { try? writer.close() }
            var offset: UInt64 = 0
            while offset < size {
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                let wanted = Int(min(UInt64(512 * 1024), size - offset))
                let bytes = try await active.fetchNikonSTAPartial(handle: item.handle, offset: offset, length: wanted)
                guard session === active, phase == .connected, !albumCancelRequested else { throw CancellationError() }
                try writer.write(contentsOf: bytes)
                offset += UInt64(bytes.count)
                albumProgress = Double(offset) / Double(size)
                albumMessage = "下载中：\(offset) / \(size) 字节"
            }
            try writer.close()
            let actual = try FileManager.default.attributesOfItem(atPath: partial.path)[.size] as? NSNumber
            guard actual?.uint64Value == size else {
                throw PtpIpSessionError.malformed(stage: "STA 相册", detail: "保存文件长度不匹配")
            }
            try FileManager.default.moveItem(at: partial, to: completed)
            finished = true
            albumDownloadURL = completed
            albumMessage = "下载完成；请使用分享保存。名称为占位名，临时文件可能被系统清理。"
            log.add("STA 相册原文件下载完成，长度已核对；未验证文件内容")
        } catch is CancellationError {
            if session === active { albumMessage = "下载已取消；不完整文件已删除" }
        } catch PtpIpSessionError.operationFailed(let code, let op) {
            if session === active { albumMessage = String(format: "相机拒绝下载：0x%04X 返回 0x%04X", op, code) }
        } catch {
            guard session === active, phase == .connected else { return }
            albumMessage = "下载异常，不完整文件已删除：\(describe(error))"
            // A size-limit or transport error can leave a partial PTP transaction on the wire.
            await disconnect(reason: albumMessage)
        }
    }

    // MARK: - Read-only exposure values (never write camera settings)

    func refreshExposure() async {
        guard let active = session, phase == .connected, !exposureBusy, !batteryBusy,
              !slotsBusy, !monitorBusy, !pairingBusy, !pairingPrepared, !albumBusy, !liveViewBusy else { return }
        exposureBusy = true
        exposureRows = []
        defer { if session === active { exposureBusy = false } }
        for property in ExposureProperty.allCases {
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
        guard let active = session, phase == .connected, !batteryBusy, !pairingPrepared, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !liveViewBusy else { return }
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
        guard let session, phase == .connected, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !liveViewBusy else { return }
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
        guard let session, phase == .connected, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy, !exposureBusy, !albumBusy, !liveViewBusy else { return }
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
