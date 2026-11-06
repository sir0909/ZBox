import Foundation
import ImageIO
import UniformTypeIdentifiers

struct PoseReference: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    var favorite: Bool
}

/// Only user-selected, downsampled and metadata-stripped images. Never talks to a camera or a network.
/// A broken index is left untouched and all mutations are disabled until manually recovered.
@MainActor
final class PoseReferenceStore: ObservableObject {
    enum Failure: Error { case invalidImage, unhealthy, full, missing }
    @Published private(set) var items: [PoseReference] = []
    @Published private(set) var healthy = true
    @Published private(set) var message = "自己的参考图仅保存在本机，不会上传。"
    private let directory: URL
    private var indexURL: URL { directory.appendingPathComponent("index.json") }
    private let maximumItems = 200
    private let maximumBytes = 2_000_000

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zbox", isDirectory: true).appendingPathComponent("pose-references", isDirectory: true)
        load()
    }
    private func imageURL(_ id: UUID) -> URL { directory.appendingPathComponent(id.uuidString + ".jpg") }
    private func load() {
        guard FileManager.default.fileExists(atPath: indexURL.path) else { return }
        do {
            let size = try FileManager.default.attributesOfItem(atPath: indexURL.path)[.size] as? NSNumber
            guard let size, size.intValue <= 128_000 else { throw Failure.unhealthy }
            let decoded = try JSONDecoder().decode([PoseReference].self, from: Data(contentsOf: indexURL))
            guard decoded.count <= maximumItems, Set(decoded.map(\.id)).count == decoded.count else { throw Failure.unhealthy }
            items = decoded
        } catch {
            healthy = false
            message = "参考图索引无法读取；为防止覆盖原数据，已禁用添加、收藏和删除。"
        }
    }
    private func persist(_ updated: [PoseReference]) throws {
        guard healthy else { throw Failure.unhealthy }
        guard updated.count <= maximumItems else { throw Failure.full }
        let data = try JSONEncoder().encode(updated)
        guard data.count <= 128_000 else { throw Failure.full }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: indexURL, options: [.atomic, .completeFileProtectionUnlessOpen])
        items = updated
    }
    private func isValidJPEG(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= maximumBytes,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetType(source) as String? == UTType.jpeg.identifier,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 768, height <= 768,
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              image.width == width, image.height == height else { return false }
        return true
    }
    @discardableResult
    func add(_ jpeg: Data) throws -> PoseReference {
        guard healthy else { throw Failure.unhealthy }
        guard items.count < maximumItems else { throw Failure.full }
        guard isValidJPEG(jpeg) else { throw Failure.invalidImage }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let entry = PoseReference(id: UUID(), createdAt: Date(), favorite: false)
        let destination = imageURL(entry.id)
        guard !FileManager.default.fileExists(atPath: destination.path) else { throw Failure.unhealthy }
        try jpeg.write(to: destination, options: [.atomic, .completeFileProtectionUnlessOpen])
        do { try persist([entry] + items) }
        catch { try? FileManager.default.removeItem(at: destination); throw error }
        message = "已添加一张本地参考图；未上传。"
        return entry
    }
    func toggleFavorite(_ id: UUID) throws {
        guard let i = items.firstIndex(where: { $0.id == id }) else { throw Failure.missing }
        var copy = items; copy[i].favorite.toggle()
        try persist(copy)
        message = copy[i].favorite ? "已收藏本地参考图。" : "已取消收藏。"
    }
    /// Commit index first. A failed file deletion may leave an orphan; it never deletes another entry.
    func remove(_ id: UUID) throws {
        guard items.contains(where: { $0.id == id }) else { throw Failure.missing }
        try persist(items.filter { $0.id != id })
        do {
            let path = imageURL(id)
            if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
            message = "已删除应用内参考图，不影响系统相册。"
        } catch { message = "索引已删除，文件清理失败；原相册不受影响。" }
    }
    func imageData(_ id: UUID) -> Data? {
        guard items.contains(where: { $0.id == id }) else { return nil }
        let url = imageURL(id)
        guard let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber,
              size.intValue <= maximumBytes, let data = try? Data(contentsOf: url), isValidJPEG(data) else { return nil }
        return data
    }
    func reportImportFailure() { message = "参考图读取或保存失败；原图和现有参考图未修改。" }
}
