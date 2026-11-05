import Foundation
import ZboxCore

struct LocalRecipe: Codable, Identifiable {
    let id: UUID
    let name: String
    let payload: Data
    let shareMetadata: ShareRecipe?
    let savedAt: Date
}

/// Local storage only; refuses to overwrite an unreadable store.
@MainActor
final class RecipeWorkbench: ObservableObject {
    @Published var name = "ZBOX"
    @Published var points: [CurveLUT.Point] = (0...4).map { i in
        let x: Float = [0, 64, 128, 191, 255][i]
        return .init(x: x, y: x)
    }
    @Published private(set) var curveEdited = false
    @Published private(set) var entries: [LocalRecipe] = []
    @Published var message = "本地编辑不会修改相机。"
    @Published var shareText = ""
    @Published private(set) var storeHealthy = true
    private var template = RecipePayload.linearTemplate()
    private var metadata: ShareRecipe?
    private let storeURL: URL

    init(storeURL: URL? = nil) {
        self.storeURL = storeURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zbox", isDirectory: true).appendingPathComponent("recipes.json")
        loadStore()
    }
    private func loadStore() {
        guard FileManager.default.fileExists(atPath: storeURL.path) else { return }
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: storeURL.path)
            guard let size = attributes[.size] as? NSNumber, size.intValue <= 1_000_000 else { throw StoreError.invalid }
            let decoded = try JSONDecoder().decode([LocalRecipe].self, from: Data(contentsOf: storeURL))
            guard decoded.count <= 200 else { throw StoreError.invalid }
            for entry in decoded { _ = try PictureControl614.parse(entry.payload) }
            entries = decoded
        } catch {
            storeHealthy = false
            message = "本地预设库读取失败；为防止覆盖原数据，已禁用保存和删除。"
        }
    }
    private enum StoreError: Error { case invalid, full }
    private func persist(_ updated: [LocalRecipe]) throws {
        guard storeHealthy, updated.count <= 200 else { throw StoreError.full }
        let data = try JSONEncoder().encode(updated)
        guard data.count <= 1_000_000 else { throw StoreError.full }
        try FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: storeURL, options: [.atomic, .completeFileProtectionUnlessOpen])
        entries = updated
    }
    func setPoint(_ index: Int, y: Double) {
        guard points.indices.contains(index), y.isFinite else { return }
        points[index].y = Float(max(0, min(255, y.rounded())))
        curveEdited = true
    }
    func applyLocalPreset(_ kind: Int) {
        let ys: [[Float]] = [[0,64,128,191,255], [12,72,132,188,245], [0,48,128,209,255]]
        guard ys.indices.contains(kind) else { return }
        points = zip([Float(0),64,128,191,255], ys[kind]).map { .init(x: $0.0, y: $0.1) }
        curveEdited = true
        message = "已载入本地示例曲线；尚未写入相机。"
    }
    func snapshot() throws -> Data {
        let lut = curveEdited ? try CurveLUT.make(points) : nil
        return try RecipePayload.edit(template: template, name: name.trimmingCharacters(in: .whitespaces), lut: lut)
    }
    func adopt(_ payload: Data, metadata: ShareRecipe? = nil, notice: String) throws {
        let parsed = try PictureControl614.parse(payload)
        template = payload; name = parsed.name
        points = try CurveLUT.sample(RecipePayload.lut8(payload))
        curveEdited = false; self.metadata = metadata; message = notice
    }
    func importBytes(_ data: Data) {
        do {
            let imported = try RecipePayload.importFile(data)
            try adopt(imported.payload, notice: "已导入：\(imported.description)。" + (imported.approximate ? "这是近似转换，不能视为原文件无损还原。" : "原始 15 位 LUT 保留到编辑曲线为止。"))
        } catch { message = "导入失败：文件格式不受支持或内容无效；没有修改草稿。" }
    }
    func save() {
        do {
            let data = try snapshot()
            let entry = LocalRecipe(id: UUID(), name: try PictureControl614.parse(data).name,
                                    payload: data, shareMetadata: metadata, savedAt: Date())
            try persist([entry] + entries)
            try adopt(data, metadata: metadata, notice: "已保存到手机本地预设库；不会写入相机。")
        } catch { message = "保存失败：请使用 1–19 个 ASCII 字符的名称，并检查本地预设库状态。" }
    }
    func remove(_ entry: LocalRecipe) {
        do { try persist(entries.filter { $0.id != entry.id }); message = "已删除本地预设，不影响相机。" }
        catch { message = "删除失败；未修改本地预设库。" }
    }
    func use(_ entry: LocalRecipe) {
        do { try adopt(entry.payload, metadata: entry.shareMetadata, notice: "已载入本地预设；不会修改相机。") }
        catch { message = "预设无效，未替换草稿。" }
    }
    func makeShareCode() {
        do {
            _ = try snapshot()
            var recipe = metadata ?? ShareRecipe(name: name, points: points)
            recipe.name = name; recipe.points = points
            shareText = try RecipeShareCode.encode(recipe)
            message = "快传码只含曲线点和元数据，不能无损保留 614 字节或 15 位 LUT。"
        } catch { message = "快传码生成失败，请检查名称和曲线。" }
    }
    func importShareCode() {
        do {
            let imported = try RecipeShareCode.decode(shareText)
            let lut = try CurveLUT.make(imported.points)
            let data = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: imported.name, lut: lut)
            try adopt(data, metadata: imported, notice: "快传码已近似重建曲线；非曲线参数保留在码内，尚未应用到画面或相机。")
            points = imported.points; curveEdited = false
        } catch { message = "快传码无效或机身名称不是 ASCII；未替换当前草稿。" }
    }
}
