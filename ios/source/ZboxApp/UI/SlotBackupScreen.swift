import SwiftUI
import CryptoKit
import UniformTypeIdentifiers
import ZboxCore

struct SlotBackupScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var selectedSlot = 1
    @State private var records: [SlotWriteBackup] = []
    @State private var warning = ""
    @State private var riskExists = false
    @State private var exportDocument: RecipeDocument?
    @State private var exportName = ""
    @State private var exporting = false
    @State private var showRiskReview = false

    var body: some View {
        List {
            Section("备份相机已有槽位") {
                Picker("槽位", selection: $selectedSlot) {
                    ForEach(1...9, id: \.self) { Text("C-\($0)").tag($0) }
                }
                Button(model.slotsBusy ? "读取中…" : "读取并备份到手机") {
                    Task { await model.backupSlot(index: selectedSlot); await reload() }
                }.disabled(!model.canBackupSlot)
                Text(model.slotBackupMessage).font(.footnote).foregroundStyle(.secondary)
                Text("仅接受机身返回的完整 614 字节已有记录；空槽、访问拒绝和其他格式不会被当作备份。不会写入或删除相机槽位。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("本地备份") {
                if riskExists {
                    Text("检测到未决写入风险记录；请保留备份并检查原机身。")
                        .foregroundStyle(.orange)
                    Button("只读核对未决风险") {
                        Task {
                            await model.inspectSlotRisk()
                            if model.slotRecoveryReview != nil { showRiskReview = true }
                            await reload()
                        }
                    }.disabled(!model.canBackupSlot)
                    Text(model.slotRecoveryMessage).font(.footnote).foregroundStyle(.secondary)
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.orange).font(.footnote) }
                if records.isEmpty { Text("暂无可验证的本地备份").foregroundStyle(.secondary) }
                ForEach(records, id: \.id) { record in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("C-\(record.slot) · \((try? PictureControl614.parse(record.original).name) ?? "未知")")
                        Text(record.createdAt, style: .date).font(.caption)
                        Text("机身标识尾号 \(record.cameraID.suffix(8)) · \(record.original.count) 字节")
                            .font(.caption).foregroundStyle(.secondary)
                        Text("SHA-256 \(digest(record.original))").font(.caption2.monospaced()).textSelection(.enabled)
                        Button("导出原始 614B 备份") {
                            exportDocument = RecipeDocument(data: record.original)
                            exportName = "C\(record.slot)-\(record.id.uuidString).bin"
                            exporting = true
                        }.buttonStyle(.bordered)
                    }
                }
                Text("校验只证明本机备份文件重读一致；机身 GUID 不是身份认证，导出的文件需妥善保管。备份不能自动恢复。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("槽位备份（只读）")
        .task { await reload() }
        .onDisappear { model.discardSlotRecovery() }
        .confirmationDialog("确认已检查原机身？", isPresented: $showRiskReview, titleVisibility: .visible) {
            Button("已检查，重新读取并解除风险标记", role: .destructive) {
                Task { await model.confirmSlotRiskReview(); await reload() }
            }
            Button("取消，保留风险", role: .cancel) { model.discardSlotRecovery() }
        } message: {
            if let review = model.slotRecoveryReview {
                let kind = review.match == .original ? "原始备份" : "目标记录"
                Text("机身标识尾号 \(review.cameraID.suffix(8)) 的 C-\(review.slot) 当前与\(kind)逐字节一致。请亲自检查原机身：一致不代表命令历史已知。确认后将再次读取；只有仍一致才解除手机上的风险标记，不会写入相机。")
            } else {
                Text("核对已失效；不会解除风险。")
            }
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .data, defaultFilename: exportName) { result in
            switch result {
            case .success: warning = "已导出原始备份；不会修改相机。"
            case .failure: warning = "导出未完成；本地备份仍保留。"
            }
        }
    }
    private func reload() async {
        do {
            let listing = try await model.slotBackupStore.listBackups()
            records = listing.records; riskExists = listing.riskExists
            var notes: [String] = []
            if listing.corruptCount > 0 { notes.append("有 \(listing.corruptCount) 个备份校验失败，已保留原文件且不允许导出。") }
            if listing.truncated { notes.append("仅检查最近 200 项；更多备份仍保留在本机。") }
            warning = notes.joined(separator: " ")
        } catch { records = []; riskExists = true; warning = "备份目录不可读取；未修改或删除任何文件。" }
    }
    private func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
