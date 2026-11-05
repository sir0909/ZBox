import SwiftUI
import ZboxCore

struct AppRootView: View {
    @StateObject private var model = CameraConnectionModel()
    @State private var guidDraft = ""

    var body: some View {
        NavigationStack {
            List {
                Section("工程状态") {
                    LabeledContent("阶段", value: "M1 连接中")
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
                    }

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
                    Section("相机信息") {
                        ForEach(model.deviceRows) { row in
                            LabeledContent(row.title, value: row.value)
                        }
                        Text("电池电量与 C-1…C-9 设置槽尚未接入(M1 后续提交)。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    Section("主机注册") {
                        if let pin = model.pairingPIN {
                            LabeledContent("相机 PIN", value: pin)
                        }
                        Button("准备注册（0x952B）") {
                            Task { await model.prepareRegistration() }
                        }
                        .disabled(model.pairingPrepared)
                        Button("确认注册（0x935A，等待 8.5 秒）") {
                            Task { await model.confirmRegistration() }
                        }
                        .disabled(!model.pairingPrepared)
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
            .navigationTitle("Zbox")
        }
        .task { model.loadIdentity() }
    }
}
