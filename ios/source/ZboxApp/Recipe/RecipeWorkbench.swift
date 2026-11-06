import Foundation
import ZboxCore

struct LocalRecipe: Codable, Identifiable {
    let id: UUID
    let name: String
    let payload: Data
    let shareMetadata: ShareRecipe?
    let family: Int?
    let savedAt: Date
}

enum LocalPreviewParameter: String, CaseIterable, Identifiable {
    case brightness, contrast, saturation, hue
    var id: String { rawValue }
    var title: String {
        switch self {
        case .brightness: return "亮度"
        case .contrast: return "对比度"
        case .saturation: return "饱和度"
        case .hue: return "色相"
        }
    }
    var range: ClosedRange<Double> { self == .brightness ? -2...2 : -3...3 }
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
    @Published private(set) var metadata: ShareRecipe?
    @Published private(set) var presetFamily: Int?
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
        let updated = Float(max(0, min(255, y.rounded())))
        guard updated != points[index].y else { return }
        points[index].y = updated
        curveEdited = true
    }
    func applyLocalPreset(_ kind: Int) {
        let ys: [[Float]] = [[0,64,128,191,255], [12,72,132,188,245], [0,48,128,209,255]]
        guard ys.indices.contains(kind) else { return }
        points = zip([Float(0),64,128,191,255], ys[kind]).map { .init(x: $0.0, y: $0.1) }
        curveEdited = true
        message = "已载入本地示例曲线；尚未写入相机。"
    }
    func previewValue(_ parameter: LocalPreviewParameter) -> Double {
        guard let metadata else { return 0 }
        switch parameter {
        case .brightness: return Double(metadata.brightness)
        case .contrast: return Double(metadata.contrast)
        case .saturation: return Double(metadata.saturation)
        case .hue: return Double(metadata.hue)
        }
    }
    func setPreviewValue(_ parameter: LocalPreviewParameter, value: Double) {
        guard value.isFinite else { return }
        let value = Float(max(parameter.range.lowerBound, min(parameter.range.upperBound, (value * 2).rounded() / 2)))
        var recipe = metadata ?? ShareRecipe(name: name, points: points)
        switch parameter {
        case .brightness: recipe.brightness = value
        case .contrast: recipe.contrast = value
        case .saturation: recipe.saturation = value
        case .hue: recipe.hue = value
        }
        metadata = recipe
        message = "色彩参数只影响手机近似预览和快传码；不会写入 NCP 或相机。"
    }
    func resetPreviewColors() {
        guard var recipe = metadata else { return }
        recipe.brightness = 0; recipe.contrast = 0; recipe.saturation = 0; recipe.hue = 0
        metadata = recipe
        message = "已重置四项色彩调节；黑白类型、曲线和原始载荷不变。"
    }
    func previewAdjustments() -> PreviewColorAdjustments? {
        guard let metadata else { return .neutral }
        let mono = presetFamily == 1 || (presetFamily == nil && ["MONOCHROME", "MONO"].contains(metadata.profile.uppercased()))
        return PreviewColorAdjustments(saturation: metadata.saturation, hue: metadata.hue,
                                       contrast: metadata.contrast, brightness: metadata.brightness, monochrome: mono)
    }
    private func editedLUT15() throws -> [UInt16]? {
        guard curveEdited else { return nil }
        // Retain an imported 15-bit table when editing did not change its 8-bit visible curve.
        if try CurveLUT.make(points) == RecipePayload.lut8(template) { return nil }
        return try CurveLUT.make15(points)
    }
    func previewLUT() throws -> [UInt8] {
        guard let lut = try editedLUT15() else { return try RecipePayload.lut8(template) }
        return lut.map { UInt8((UInt32($0) * 255 + 16383) / 32767) }
    }
    func snapshot() throws -> Data {
        try RecipePayload.edit15(template: template, name: name.trimmingCharacters(in: .whitespaces), lut: editedLUT15())
    }
    func adopt(_ payload: Data, metadata: ShareRecipe? = nil, family: Int? = nil, notice: String) throws {
        let parsed = try PictureControl614.parse(payload)
        template = payload; name = parsed.name
        points = try CurveLUT.sample(RecipePayload.lut8(payload))
        curveEdited = false; self.metadata = metadata; presetFamily = family; message = notice
    }
    struct DraftIdentity: Equatable {
        let template: Data
        let name: String
        let points: [CurveLUT.Point]
        let curveEdited: Bool
        let metadata: ShareRecipe?
        let family: Int?
    }
    func draftIdentity() -> DraftIdentity {
        DraftIdentity(template: template, name: name, points: points, curveEdited: curveEdited,
                      metadata: metadata, family: presetFamily)
    }
    func useAI(_ result: AIRecipe) throws {
        let payload = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: result.name,
                                             lut: CurveLUT.make(result.points))
        var metadata: ShareRecipe?
        if let suggestion = result.previewSuggestion {
            metadata = ShareRecipe(name: result.name, contrast: suggestion.contrast, brightness: suggestion.brightness,
                                   saturation: suggestion.saturation, hue: suggestion.hue, points: result.points)
        }
        try adopt(payload, metadata: metadata,
                  notice: "已载入 AI 近似曲线；色彩建议仅供手机预览，不写入相机或 NCP。\(result.analysis)")
        points = result.points
    }
    func useBuiltIn(_ entry: BuiltInPreset) {
        do {
            try adopt(entry.makePayload(), metadata: entry.shareMetadata, family: entry.family,
                      notice: "已载入内置曲线；部分色彩参数仅在手机近似预览，不写入相机或 NCP。")
            points = entry.points
        } catch { message = "内置预设无效；未替换当前草稿。" }
    }
    func exportArchive() throws -> Data {
        let payload = try snapshot()
        var current = metadata
        current?.name = try PictureControl614.parse(payload).name
        current?.points = points
        return try RecipeArchive.encode(payload: payload, points: points, metadata: current, family: presetFamily)
    }
    func importBytes(_ data: Data) {
        do {
            if data.first(where: { ![0x09, 0x0a, 0x0d, 0x20].contains($0) }) == 0x7b {
                let archive = try RecipeArchive.decode(data)
                try adopt(archive.payload, metadata: archive.metadata, family: archive.family,
                          notice: "已导入 ZBox 本地配方；载荷和预览参数已恢复，不会写入相机。")
                points = archive.points
                return
            }
            let imported = try RecipePayload.importFile(data)
            var legacyMetadata: ShareRecipe?
            if let family = imported.legacyFamily {
                let profile: String
                switch family { case 1: profile = "MONOCHROME"; case 2: profile = "SEPIA"; case 8: profile = "DREAM"; default: profile = "STANDARD" }
                let parsed = try PictureControl614.parse(imported.payload)
                legacyMetadata = ShareRecipe(name: parsed.name, profile: profile,
                                             points: try CurveLUT.sample(RecipePayload.lut8(imported.payload)))
            }
            try adopt(imported.payload, metadata: legacyMetadata, family: imported.legacyFamily,
                      notice: "已导入：\(imported.description)。" + (imported.approximate ? "这是近似转换，类型不会写入 NCP 或相机。" : "原始 15 位 LUT 保留到编辑曲线为止。"))
        } catch { message = "导入失败：文件格式不受支持或内容无效；没有修改草稿。" }
    }
    func save() {
        do {
            let data = try snapshot()
            let entry = LocalRecipe(id: UUID(), name: try PictureControl614.parse(data).name,
                                    payload: data, shareMetadata: metadata, family: presetFamily, savedAt: Date())
            try persist([entry] + entries)
            try adopt(data, metadata: metadata, family: presetFamily, notice: "已保存到手机本地预设库；不会写入相机。")
        } catch { message = "保存失败：请使用 1–19 个 ASCII 字符的名称，并检查本地预设库状态。" }
    }
    func remove(_ entry: LocalRecipe) {
        do { try persist(entries.filter { $0.id != entry.id }); message = "已删除本地预设，不影响相机。" }
        catch { message = "删除失败；未修改本地预设库。" }
    }
    func use(_ entry: LocalRecipe) {
        do { try adopt(entry.payload, metadata: entry.shareMetadata, family: entry.family, notice: "已载入本地预设；不会修改相机。") }
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
            try adopt(data, metadata: imported, notice: "快传码已近似重建曲线；部分参数仅用于手机近似预览，未写入相机或 NCP。")
            points = imported.points; curveEdited = false
        } catch { message = "快传码无效或机身名称不是 ASCII；未替换当前草稿。" }
    }
}
