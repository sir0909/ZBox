import SwiftUI
import ZboxCore
import ImageIO
import UIKit

/// Read-only Nikon STA metadata and explicit, bounded original download.
struct STAAlbumScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var selected: NikonSTAAlbumItem?
    @State private var confirming = false

    private func thumbnail(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let info = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = info[kCGImagePropertyPixelWidth] as? Int,
              let height = info[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 10_000, height <= 10_000,
              Int64(width) * Int64(height) <= 80_000_000 else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceThumbnailMaxPixelSize: 800,
                                         kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }

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
                Text("实验功能，尚未在 iPhone + Z 30 真机验证。仅支持相机加入网络的 Nikon STA 路径。名称是句柄占位名，不是真实文件名；预览只尝试读取文件头内的 JPEG。")
                    .font(.footnote).foregroundStyle(.secondary)
                if let url = model.albumDownloadURL {
                    ShareLink(item: url) { Label("分享 / 保存已下载文件", systemImage: "square.and.arrow.up") }
                    Text("文件暂存在应用临时目录，请及时分享保存；不自动写入照片图库。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let jpeg = model.albumPreviewJPEG {
                Section("预览：\(model.albumPreviewTitle)") {
                    if let image = thumbnail(jpeg) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 300)
                    } else {
                        Text("内嵌数据不可解码或尺寸超过限制").foregroundStyle(.secondary)
                    }
                }
            }
            Section("媒体（最多展示 500 项）") {
                ForEach(model.albumItems) { item in
                    HStack {
                        Image(systemName: item.kind == .raw ? "camera" : (item.kind == .jpeg ? "photo" : "video"))
                            .frame(width: 28)
                        VStack(alignment: .leading) {
                            Text(item.placeholderName)
                            Text(item.capturedAt ?? "拍摄时间未知").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("预览") { Task { await model.previewSTAItem(item) } }
                        Button("下载") { selected = item; confirming = true }
                    }
                    .buttonStyle(.borderless)
                    .disabled(model.albumBusy || model.liveViewBusy || model.captureBusy || model.exposureBusy || model.batteryBusy || model.slotsBusy || model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.phase != .connected)
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
