import Foundation
import ZboxCore

/// 连接流程的界面状态。所有 PTP 调用都经过 `ZboxCore.PtpIpSession`。
@MainActor
final class CameraConnectionModel: ObservableObject {
    enum Phase: Equatable {
        case idle, scanning, connecting, connected
    }

    struct DeviceRow: Identifiable {
        let id = UUID()
        let title: String
        let value: String
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var candidates: [HotspotCameraEndpoint] = []
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
    @Published private(set) var clientName = PtpIpConnector.defaultPrimaryName
    @Published var manualHost = ""
    @Published private(set) var guidText = "加载中…"

    let log = DiagnosticLog()

    private var session: PtpIpSession?
    private var monitorTask: Task<Void, Never>?

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
        await openSession(host: host, localAddress: nil)
    }

    func disconnect(reason: String = "已断开") async {
        // Reset state before suspension; a new session must not be erased by a late close.
        let oldSession = session
        session = nil
        monitorTask?.cancel()
        monitorTask = nil
        phase = .idle
        monitorBusy = false
        slotsBusy = false
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

    private func openSession(host: String, localAddress: String?) async {
        await session?.close()
        session = nil
        phase = .connecting
        batteryLevel = nil
        batteryBusy = false
        needsRecovery = false
        pairingPIN = nil
        pairingPrepared = false
        pairingBusy = false
        let staMode = localAddress != nil
        log.add("连接 \(host):15740（\(staMode ? "热点 STA" : "直连")）")
        do {
            let guid = try ClientIdentityStore.loadOrCreateGuid()
            let connected = try await PtpIpConnector.connect { name in
                let command = NetworkPtpIpChannel(host: host, localAddress: localAddress)
                try await command.open(timeoutSeconds: 6)
                return PtpIpSession(
                    commandChannel: command,
                    openEventChannel: {
                        let event = NetworkPtpIpChannel(host: host, localAddress: localAddress)
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
              !pairingPrepared, !slotsBusy, !monitorBusy else { return }
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
              !monitorBusy, !pairingBusy, !pairingPrepared else { return }
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

    // MARK: - Read-only battery

    func refreshBattery() async {
        guard let active = session, phase == .connected, !batteryBusy, !pairingPrepared, !pairingBusy, !monitorBusy, !slotsBusy else { return }
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
        guard let session, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy else { return }
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
        guard let session, !batteryBusy, !pairingBusy, !monitorBusy, !slotsBusy else { return }
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
