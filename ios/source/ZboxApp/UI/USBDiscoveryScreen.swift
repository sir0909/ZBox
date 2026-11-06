import SwiftUI

struct USBDiscoveryScreen: View {
    @StateObject private var store = USBDiscoveryStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            Section("本地相机发现 · 实验") {
                Text("手动查找系统可见的本地相机候选，可能包含 USB 相机或读卡设备；不会自动识别尼康型号。")
                    .font(.footnote).foregroundStyle(.secondary)
                if store.scanning {
                    Button("停止发现") { store.stop() }
                    Text("正在发现；离开页面或应用不活跃时自动停止。")
                        .font(.footnote).foregroundStyle(.secondary)
                } else {
                    Button("开始发现本地设备") { store.start() }
                        .disabled(scenePhase != .active)
                    Text("未启动；返回前台后不会自动重新扫描。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Text("只显示设备名称，不打开相机会话、不读取照片或发送 PTP 命令。系统可能显示权限提示；候选不代表连接成功。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if store.scanning {
                Section("候选设备（最多 64 个）") {
                    if store.candidates.isEmpty {
                        Text("暂未发现；请检查连接、线缆与系统权限。")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(store.candidates) { candidate in
                        Label(candidate.name, systemImage: "camera")
                    }
                }
            }
            Section("尚未开放") {
                Text("USB 连接、控制、相册读取与写入均尚未实现；设备发现也未经过 iPhone + Z 30 真机验证。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("本地设备发现")
        .onDisappear { store.stop() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { store.stop() } }
    }
}
