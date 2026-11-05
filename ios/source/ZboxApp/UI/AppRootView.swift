import SwiftUI

struct AppRootView: View {
    @State private var guidText = "加载中…"

    var body: some View {
        NavigationStack {
            List {
                Section("工程状态") {
                    LabeledContent("阶段", value: "M1 骨架")
                    LabeledContent("协议核心", value: "ZboxCore")
                }
                Section("PTP/IP 客户端身份") {
                    LabeledContent("GUID") {
                        Text(guidText)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                Section("连接") {
                    Text("Wi-Fi 发现与 Init 握手尚未接入,将在 M1 后续提交中完成。")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Zbox")
        }
        .task { loadGuid() }
    }

    private func loadGuid() {
        do {
            let guid = try ClientIdentityStore.loadOrCreateGuid()
            guidText = ClientIdentityStore.hex(guid)
        } catch {
            guidText = "读取失败:\(error)"
        }
    }
}
