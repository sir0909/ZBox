import SwiftUI
import ZboxCore

/// Read-only Nikon STA metadata and explicit, bounded original download.
struct STAAlbumScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var selected: NikonSTAAlbumItem?
    @State private var confirming = false

    var body: some View {
        List {
            Section("Nikon STA 相册") {
                Button(model.albumBusy ? "相机处理中…" : "读取相册列表") {
                    Task { await model.loadSTAAlbum() }
                }
                .disabled(model.albumBusy || !model.connectedSTA || model.liveViewBusy ||
                          model.batteryBusy || model.slotsBusy || model.exposureBusy ||
                          model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.captureBusy)
                if model.albumBusy {
                    Button("取消当前读取/下载", role: .cancel) { model.cancelAlbumWork() }
                    if let progress = model.albumProgress { ProgressView(value: progress) }
                }
                Text(model.albumMessage).font(.footnote).foregroundStyle(.secondary)
                Text("仅支持相机加入网络的 Nikon STA 路径。名称是句柄占位名，不是真实文件名；没有缩略图。")
                    .font(.footnote).foregroundStyle(.secondary)
                if let url = model.albumDownloadURL {
                    ShareLink(item: url) { Label("分享 / 保存已下载文件", systemImage: "square.and.arrow.up") }
                    Text("文件暂存在应用临时目录，请及时分享保存；不自动写入照片图库。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            Section("媒体（最多展示 500 项）") {
                ForEach(model.albumItems) { item in
                    Button {
                        selected = item
                        confirming = true
                    } label: {
                        HStack {
                            Image(systemName: item.kind == .raw ? "camera" : (item.kind == .jpeg ? "photo" : "video"))
                                .frame(width: 28)
                            VStack(alignment: .leading) {
                                Text(item.placeholderName)
                                Text(item.capturedAt ?? "拍摄时间未知")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "arrow.down.circle")
                        }
                    }
                    .disabled(model.albumBusy || model.liveViewBusy || model.captureBusy || model.phase != .connected)
                }
            }
        }
        .navigationTitle("相机相册")
        .confirmationDialog("下载原文件到临时目录？", isPresented: $confirming, titleVisibility: .visible) {
            Button("开始下载") {
                if let selected { Task { await model.downloadSTAItem(selected) } }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将读取完整原文件；需足够空间和稳定连接。文件名为占位名，完成后请分享保存。")
        }
        .onDisappear { model.cancelAlbumWork() }
    }
}
