import SwiftUI
import ZboxCore
import ImageIO
import UIKit

/// Read-only Nikon STA metadata and explicit, bounded original download.
struct STAAlbumScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var selected: NikonSTAAlbumItem?
    @State private var confirming = false
    @State private var isVisible = false
    @Environment(\.scenePhase) private var scenePhase

    private func thumbnail(_ data: Data, maxPixel: Int = 800) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let info = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = info[kCGImagePropertyPixelWidth] as? Int,
              let height = info[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 10_000, height <= 10_000,
              Int64(width) * Int64(height) <= 80_000_000 else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                                         kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }

    var body: some View {
        List {
            Section("Nikon STA 相册") {
                Button(model.albumBusy ? "相机处理中…" : "读取相册列表") {
                    Task {
                        await model.loadSTAAlbum()
                        if isVisible, scenePhase == .active, model.mayAutoLoadThumbnails, !model.albumDisplayedItems.isEmpty {
                            await model.loadThumbnailPage(start: 0, automatic: true)
                        }
                    }
                }
                .disabled(model.albumBusy || !model.connectedSTA || model.liveViewBusy ||
                          model.batteryBusy || model.slotsBusy || model.exposureBusy ||
                          model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.captureBusy)
                if model.albumBusy {
                    Button("取消当前读取/下载", role: .cancel) { model.cancelAlbumWork() }
                    if let progress = model.albumProgress { ProgressView(value: progress) }
                }
                Text(model.albumMessage).font(.footnote).foregroundStyle(.secondary)
                Text("实验功能，尚未在 iPhone + Z 30 真机验证。仅支持相机加入网络的 Nikon STA 路径。名称是句柄占位名，不是真实文件名；预览读取受限文件头及部分 TIFF / Nikon MakerNote / MPF 小预览，不保证每张照片可预览。")
                    .font(.footnote).foregroundStyle(.secondary)
                if let url = model.albumDownloadURL {
                    ShareLink(item: url) { Label("分享 / 保存已下载文件", systemImage: "square.and.arrow.up") }
                    Text("文件暂存在应用临时目录，请及时分享保存；不自动写入照片图库。")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let sha256 = model.albumDownloadSHA256 {
                        Text("接收数据 SHA-256（可用于后续比对）").font(.caption)
                        Text(sha256).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        Text("由下载过程中的接收数据计算，未重新读取保存的文件；相机未提供校验和，不能证明与相机原文件一致。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
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
            Section("媒体（最多读取 500 项）") {
                if !model.albumItems.isEmpty {
                    Picker("类型", selection: Binding(
                        get: { model.albumFilter },
                        set: { filter in
                            model.setAlbumBrowse(filter: filter, order: model.albumSort)
                            Task {
                                if isVisible, scenePhase == .active, model.mayAutoLoadThumbnails {
                                    await model.loadThumbnailPage(start: 0, automatic: true)
                                }
                            }
                        })) {
                        ForEach(AlbumMediaFilter.allCases, id: \.self) { value in Text(value.title).tag(value) }
                    }.pickerStyle(.segmented).disabled(model.albumBusy)
                    Picker("排序", selection: Binding(
                        get: { model.albumSort },
                        set: { order in
                            model.setAlbumBrowse(filter: model.albumFilter, order: order)
                            Task {
                                if isVisible, scenePhase == .active, model.mayAutoLoadThumbnails {
                                    await model.loadThumbnailPage(start: 0, automatic: true)
                                }
                            }
                        })) {
                        ForEach(AlbumSortOrder.allCases, id: \.self) { value in Text(value.title).tag(value) }
                    }.disabled(model.albumBusy)
                    Text("仅筛选已读取的最多 500 项；未知拍摄时间排在最后。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if !model.albumItems.isEmpty && model.albumDisplayedItems.isEmpty {
                    Text("当前筛选没有项目").foregroundStyle(.secondary)
                }
                if !model.albumDisplayedItems.isEmpty {
                    HStack {
                        Button("上一页") {
                            Task { await model.loadThumbnailPage(start: model.albumThumbnailPage - ThumbnailBatch.pageSize) }
                        }.disabled(model.albumBusy || model.albumThumbnailPage == 0)
                        Spacer()
                        Text("\(model.albumThumbnailPage + 1)–\(min(model.albumThumbnailPage + ThumbnailBatch.pageSize, model.albumDisplayedItems.count)) / \(model.albumDisplayedItems.count)")
                            .font(.caption.monospacedDigit())
                        Spacer()
                        Button("下一页") {
                            Task { await model.loadThumbnailPage(start: model.albumThumbnailPage + ThumbnailBatch.pageSize) }
                        }.disabled(model.albumBusy || model.albumThumbnailPage + ThumbnailBatch.pageSize >= model.albumDisplayedItems.count)
                    }
                    Button("重新加载本页缩略图") {
                        Task { await model.loadThumbnailPage(start: model.albumThumbnailPage) }
                    }.disabled(model.albumBusy)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 125), spacing: 12)], spacing: 12) {
                        ForEach(model.currentThumbnailItems) { item in
                            VStack(alignment: .leading, spacing: 6) {
                                Button {
                                    if model.albumThumbnails[item.id] != nil { model.showCachedPreview(item) }
                                    else { Task { await model.previewSTAItem(item) } }
                                } label: {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.12))
                                        if let bytes = model.albumThumbnails[item.id], let image = thumbnail(bytes, maxPixel: 220) {
                                            Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 8))
                                        } else {
                                            Image(systemName: item.kind == .raw ? "camera" : (item.kind == .jpeg ? "photo" : "video"))
                                                .font(.title).foregroundStyle(.secondary)
                                        }
                                    }.frame(height: 100)
                                }
                                Text(item.placeholderName).font(.caption2).lineLimit(1)
                                Text(item.capturedAt ?? "时间未知").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                                Button("下载") { selected = item; confirming = true }.font(.caption)
                            }
                            .buttonStyle(.borderless)
                            .disabled(model.albumBusy || model.liveViewBusy || model.captureBusy || model.exposureBusy || model.batteryBusy || model.slotsBusy || model.monitorBusy || model.pairingBusy || model.pairingPrepared || model.phase != .connected)
                        }
                    }
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
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false; model.cancelAlbumWork() }
    }
}
