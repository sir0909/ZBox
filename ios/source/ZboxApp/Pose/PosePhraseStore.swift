import Foundation

/// The user's custom phrases and favorites stay local. A corrupt file is never overwritten.
@MainActor
final class PosePhraseStore: ObservableObject {
    enum Failure: Error { case invalid, duplicate, full, unhealthy, missing }
    private struct Shelf: Codable {
        var favorites: [String]
        var custom: [String]
    }
    @Published private(set) var favorites: [String] = []
    @Published private(set) var custom: [String] = []
    @Published private(set) var healthy = true
    @Published private(set) var message = "话术收藏和自定义内容仅保存在本机。"
    private let url: URL
    init(url: URL? = nil) {
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zbox", isDirectory: true).appendingPathComponent("pose-phrases.json")
        load()
    }
    private static func valid(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.count <= 180 && text.utf8.count <= 720 &&
        !text.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
    }
    private func load() {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            guard let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber,
                  size.intValue <= 256_000 else { throw Failure.unhealthy }
            let shelf = try JSONDecoder().decode(Shelf.self, from: Data(contentsOf: url))
            guard shelf.favorites.count <= 200, shelf.custom.count <= 100,
                  Set(shelf.favorites).count == shelf.favorites.count, Set(shelf.custom).count == shelf.custom.count,
                  (shelf.favorites + shelf.custom).allSatisfy(Self.valid) else { throw Failure.unhealthy }
            favorites = shelf.favorites; custom = shelf.custom
        } catch { healthy = false; message = "话术数据无法读取；为防止覆盖，已禁用保存和删除。" }
    }
    private func persist(favorites: [String], custom: [String]) throws {
        guard healthy else { throw Failure.unhealthy }
        guard favorites.count <= 200, custom.count <= 100 else { throw Failure.full }
        let data = try JSONEncoder().encode(Shelf(favorites: favorites, custom: custom))
        guard data.count <= 256_000 else { throw Failure.full }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
        self.favorites = favorites; self.custom = custom
    }
    func addCustom(_ input: String) throws {
        let line = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.valid(line) else { throw Failure.invalid }
        guard !custom.contains(line) else { throw Failure.duplicate }
        try persist(favorites: favorites, custom: [line] + custom)
        message = "已保存自定义话术。"
    }
    func toggleFavorite(_ line: String) throws {
        guard Self.valid(line) else { throw Failure.invalid }
        let updated = favorites.contains(line) ? favorites.filter { $0 != line } : [line] + favorites
        try persist(favorites: updated, custom: custom)
        message = updated.contains(line) ? "已收藏话术。" : "已取消话术收藏。"
    }
    func removeCustom(_ line: String) throws {
        guard custom.contains(line) else { throw Failure.missing }
        try persist(favorites: favorites.filter { $0 != line }, custom: custom.filter { $0 != line })
        message = "已删除自定义话术和对应收藏。"
    }
    func reportFailure() { message = healthy ? "操作未完成：请检查文字、重复内容或数量上限。" : "话术数据无法读取；为防止覆盖，已禁用保存和删除。" }
}
