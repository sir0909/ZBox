import SwiftUI
import ZboxCore

struct AppRootView: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var guidDraft = ""
    @AppStorage("viewfinder.grid") private var gridGuide = false
    @AppStorage("viewfinder.frame16x9") private var frameGuide = false

    private func slotDescription(_ slot: PictureControlSlot) -> String {
        switch slot.state {
        case .loaded(let name, _): return name
        case .empty: return "空槽"
        case .unavailable(let code): return String(format: "不可读（0x%04X）", Int(code))
        case .unexpectedData(let bytes): return "数据格式未知（\(bytes) 字节）"
        }
    }

    var body: some View {
        List {
                Section("取景辅助（仅手机显示）") {
                    Toggle("九宫格", isOn: $gridGuide)
                    Toggle("16:9 画幅框", isOn: $frameGuide)
                    Text("辅助线只叠加在手机画面上，不修改相机设置或原照片。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("工程状态") {
                    LabeledContent("阶段", value: "M2 实时取景开发中")
                    LabeledContent("协议核心", value: "ZboxCore")
                }

                Section("连接相机") {
                    Button {
                        Task { await model.scan() }
                    } label: {
                        Label(model.phase == .scanning ? "扫描中…" : "扫描热点相机", systemImage: "dot.radiowaves.left.and.right")
                    }
                    .disabled(model.phase != .idle)

                    ForEach(model.candidates, id: \.cameraAddress) { endpoint in
                        Button("连接 \(endpoint.cameraAddress)（\(endpoint.interfaceName)）") {
                            Task { await model.connect(endpoint) }
                        }
                        .disabled(model.phase != .idle)
                    }

                    Button("Bonjour 发现相机") {
                        Task { await model.scanBonjour() }
                    }
                    .disabled(model.phase != .idle)
                    ForEach(model.bonjourCandidates) { candidate in
                        Button("连接 \(candidate.name)（\(candidate.type)）") {
                            Task { await model.connectBonjour(candidate) }
                        }
                        .disabled(model.phase != .idle)
                    }
                    Picker("手动 / Bonjour 连接模式", selection: $model.cameraJoinsNetwork) {
                        Text("手机连相机热点").tag(false)
                        Text("相机加入网络或手机热点").tag(true)
                    }
                    .disabled(model.phase != .idle)
                    TextField("相机 IP（手动，例如 172.20.10.2）", text: $model.manualHost)
                        .keyboardType(.decimalPad)
                        .textInputAutocapitalization(.never)
                    Button("手动连接") {
                        Task { await model.connectManual() }
                    }
                    .disabled(model.phase != .idle || model.manualHost.isEmpty)

                    Text(model.statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if model.needsRecovery {
                    Section("需要恢复配对") {
                        Text("相机拒绝了当前客户端身份。常见于重装应用或更换客户端名称后。请在相机菜单「连接到计算机 → 配对」重新配对一次；若仍失败，可在下方恢复或重置 GUID。")
                            .font(.footnote)
                    }
                }

                if model.phase == .connected {
                    Section("实时取景") {
                        NavigationLink {
                            LiveViewScreen(model: model)
                        } label: {
                            Label("打开取景页面", systemImage: "viewfinder")
                        }
                        Text("进入页面不会自动开启相机取景；可手动开始和停止。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }

                    Section("相机相册") {
                        if model.connectedSTA {
                            NavigationLink { STAAlbumScreen(model: model) } label: {
                                Label("打开 STA 相册", systemImage: "photo.on.rectangle")
                            }
                        } else {
                            Text("STA 相册仅在相机加入网络的连接模式下可用；其他模式尚未实现。")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }

                    Section("相机信息") {
                        ForEach(model.deviceRows) { row in
                            LabeledContent(row.title, value: row.value)
                        }
                        LabeledContent("电池电量", value: model.batteryLevel.map { "\($0)%" } ?? "未读取 / 不可用")
                        Button(model.batteryBusy ? "读取中…" : "读取电池电量") {
                            Task { await model.refreshBattery() }
                        }
                        .disabled(model.batteryBusy || model.pairingPrepared || model.pairingBusy || model.monitorBusy || model.slotsBusy || model.liveViewBusy || model.exposureBusy || model.albumBusy || model.captureBusy)
                    }

                    Section("曝光信息") {
                        NavigationLink { ExposureEditorScreen(model: model) } label: {
                            Label("实验性曝光设置", systemImage: "slider.horizontal.3")
                        }
                        NavigationLink { ExposureEditorScreen(model: model, menu: true) } label: {
                            Label("实验性 i 菜单", systemImage: "square.grid.3x3")
                        }
                        Button(model.exposureBusy ? "读取中…" : "读取曝光信息") {
                            Task { await model.refreshExposure() }
                        }
                        .disabled(model.exposureBusy || model.liveViewBusy || model.batteryBusy ||
                                  model.slotsBusy || model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.albumBusy || model.captureBusy)
                        ForEach(model.exposureRows) { row in
                            LabeledContent(row.title, value: row.value)
                        }
                        Text("取景时请先停止；仅显示机身返回值，白平衡和曝光程序暂以原始代码显示。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }

                    Section("自定义优化校准（只读）") {
                        Button(model.slotsBusy ? "读取中…" : "读取 C-1…C-9") {
                            Task { await model.refreshSlots() }
                        }
                        .disabled(model.slotsBusy || model.batteryBusy || model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.liveViewBusy || model.exposureBusy || model.albumBusy || model.captureBusy)
                        ForEach(model.slots) { slot in
                            LabeledContent("C-\(slot.index)", value: slotDescription(slot))
                        }
                        Text("未授权或拒绝访问可能是空槽，也可能是权限不足；不会修改相机设置。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }

                    Section("主机注册") {
                        if let pin = model.pairingPIN {
                            LabeledContent("相机 PIN", value: pin)
                        }
                        Button("准备注册（0x952B）") {
                            Task { await model.prepareRegistration() }
                        }
                        .disabled(model.pairingPrepared || model.batteryBusy || model.pairingBusy || model.monitorBusy || model.slotsBusy || model.liveViewBusy || model.exposureBusy || model.albumBusy || model.captureBusy)
                        Button("确认注册（0x935A，等待 8.5 秒）") {
                            Task { await model.confirmRegistration() }
                        }
                        .disabled(!model.pairingPrepared || model.pairingBusy || model.batteryBusy || model.monitorBusy || model.slotsBusy || model.liveViewBusy || model.exposureBusy || model.albumBusy || model.captureBusy)
                    }

                    Section {
                        Button("断开连接", role: .destructive) {
                            Task { await model.disconnect() }
                        }
                    }
                }

                Section("PTP/IP 客户端身份") {
                    LabeledContent("GUID") {
                        Text(model.guidText)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    TextField("粘贴 32 位 GUID 以替换", text: $guidDraft)
                        .font(.system(.footnote, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    Button("保存 GUID") { model.saveGuid(guidDraft); guidDraft = "" }
                        .disabled(guidDraft.isEmpty)
                    Button("重置 GUID", role: .destructive) { model.resetGuid() }
                }

                Section("诊断") {
                    NavigationLink("日志控制台（\(model.log.lines.count)）") {
                        LogConsoleView(log: model.log)
                    }
                }
        }
        .navigationTitle("连接与设置")
    }
}
