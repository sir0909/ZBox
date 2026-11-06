import Foundation
import ImageIO
import UIKit
import ZboxCore

/// One process-wide store prevents overlapping installs when a sheet is reopened during cancellation.
@MainActor
final class PoseCatalogStore: ObservableObject {
    static let shared = PoseCatalogStore()
    @Published private(set) var catalog: PoseCatalog?
    @Published private(set) var favorites: Set<String> = []
    @Published private(set) var favoritesHealthy = true
    @Published private(set) var busy = false
    @Published private(set) var installed = false
    @Published private(set) var message = "仅目录随应用提供；图片需你确认后下载。"
    private var task: Task<Void, Never>?
    private let root: URL
    private var images: URL { root.appendingPathComponent("images", isDirectory: true) }
    private var favoritesURL: URL { root.appendingPathComponent("favorites.json") }

    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zbox", isDirectory: true).appendingPathComponent("pose-catalog-827ce3e", isDirectory: true)
        do {
            let catalog = try PoseCatalog.load(); self.catalog = catalog
            if FileManager.default.fileExists(atPath: favoritesURL.path) {
                let size = try FileManager.default.attributesOfItem(atPath: favoritesURL.path)[.size] as? NSNumber
                guard let size, size.intValue <= 200_000 else { throw StoreError.invalid }
                let values = try JSONDecoder().decode([String].self, from: Data(contentsOf: favoritesURL))
                let allowed = Set(catalog.items.map(\.name))
                guard values.count <= catalog.items.count, Set(values).count == values.count,
                      values.allSatisfy({ allowed.contains($0) }) else { throw StoreError.invalid }
                favorites = Set(values)
            }
            let marker = images.appendingPathComponent("complete")
            if let attributes = try? FileManager.default.attributesOfItem(atPath: marker.path),
               let size = attributes[.size] as? NSNumber, size.intValue == PoseCatalog.pinnedCommit.utf8.count,
               attributes[.type] as? FileAttributeType == .typeRegular,
               let data = try? Data(contentsOf: marker), String(data: data, encoding: .utf8) == PoseCatalog.pinnedCommit {
                installed = true
                message = "图库曾完成校验；每张显示前仍会再次校验。"
            }
        } catch {
            favoritesHealthy = false
            message = catalog == nil ? "图库目录读取失败。" : "图库收藏数据损坏；为防止覆盖，已禁用收藏。"
        }
    }
    private enum StoreError: Error { case invalid, diskSpace }
    func toggleFavorite(_ item: PoseCatalog.Item) {
        guard favoritesHealthy, let catalog, catalog.items.contains(item) else { return }
        var next = favorites
        if next.contains(item.name) { next.remove(item.name) } else { next.insert(item.name) }
        do {
            let data = try JSONEncoder().encode(next.sorted())
            guard data.count <= 200_000 else { throw StoreError.invalid }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try data.write(to: favoritesURL, options: [.atomic, .completeFileProtectionUnlessOpen])
            favorites = next
        } catch { message = "收藏保存失败，原收藏未改变。" }
    }
    /// Verify the Git blob on every display; a missing/modified file is never used silently.
    func image(_ item: PoseCatalog.Item) -> UIImage? {
        guard let data = PoseArchiveInstaller.safeRead(directory: images, item: item),
              PoseCatalog.matchesBlob(item, data: data),
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 768, kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }
    /// Call only from the user's explicit download confirmation.
    func downloadAfterConsent() {
        guard !busy, let catalog else { return }
        do {
            try FileManager.default.createDirectory(at: images, withIntermediateDirectories: true)
            let attrs = try FileManager.default.attributesOfFileSystem(forPath: images.path)
            guard let free = attrs[.systemFreeSize] as? NSNumber, free.int64Value >= 340 * 1024 * 1024 else { throw StoreError.diskSpace }
        } catch { message = "空间不足或无法使用本地目录，请至少预留 340 MiB 后再试。"; return }
        busy = true; message = "正在连接 GitHub，下载固定版本；可取消。"
        let directory = images
        let url = URL(string: "https://codeload.github.com/zslongf-hash/poze/zip/\(PoseCatalog.pinnedCommit)")!
        let progress: @Sendable (String) -> Void = { [weak self] text in
            Task { @MainActor [weak self] in if self?.busy == true { self?.message = text } }
        }
        let worker = Task.detached(priority: .utility) {
            let downloader = PoseArchiveDownload { received, expected in
                let mb = received / 1_048_576
                if expected > 0 { progress("下载中 \(mb) / \(expected / 1_048_576) MiB；可取消。") }
                else { progress("下载中 \(mb) MiB；可取消。") }
            }
            let archive = try await downloader.download(url)
            defer { try? FileManager.default.removeItem(at: archive) }
            try Task.checkCancellation()
            try PoseArchiveInstaller.install(archiveURL: archive, directory: directory, commit: catalog.commit, items: catalog.items) { done, total in
                progress("正在核验 \(done) / \(total) 张；可取消。")
            }
        }
        task = Task {
            do {
                try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
                installed = true; message = "图库已下载并逐张校验；可离线浏览。"
            } catch is CancellationError { message = "已取消；已核验图片保留，需再次确认才会下载。" }
            catch { message = "下载或校验未完成；已核验图片保留，请检查网络或空间后手动重试。" }
            busy = false; task = nil
        }
    }
    func cancel() { task?.cancel(); if busy { message = "正在取消…" } }
}
