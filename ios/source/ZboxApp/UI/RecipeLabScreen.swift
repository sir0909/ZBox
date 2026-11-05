import SwiftUI
import UniformTypeIdentifiers
import ZboxCore

struct RecipeDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct RecipeLabScreen: View {
    @ObservedObject var workbench: RecipeWorkbench
    @State private var importing = false
    @State private var exporting = false
    @State private var exportDocument: RecipeDocument?

    private var curve: [UInt8] { (try? CurveLUT.make(workbench.points)) ?? (0...255).map(UInt8.init) }
    var body: some View {
        List {
            Section {
                Text("RECIPE LAB / 本地配方工作台").font(.caption.bold()).foregroundStyle(.yellow)
                Text("M3 开发中：本页只编辑本地曲线，不调用 AI 服务，不写入机身。")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("机身名称（1–19 个 ASCII 字符）", text: $workbench.name)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                Text(workbench.message).font(.footnote).foregroundStyle(.secondary)
            }
            Section("色调曲线 · 256 LUT") {
                Canvas { context, size in
                    var grid = Path()
                    for i in 0...4 {
                        let x = size.width * CGFloat(i) / 4, y = size.height * CGFloat(i) / 4
                        grid.move(to: CGPoint(x: x, y: 0)); grid.addLine(to: CGPoint(x: x, y: size.height))
                        grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: size.width, y: y))
                    }
                    context.stroke(grid, with: .color(.gray.opacity(0.35)), lineWidth: 0.5)
                    var path = Path()
                    let values = curve
                    for i in values.indices {
                        let point = CGPoint(x: size.width * CGFloat(i) / 255, y: size.height * (1 - CGFloat(values[i]) / 255))
                        if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
                    }
                    context.stroke(path, with: .color(.yellow), lineWidth: 2)
                }.frame(height: 180).accessibilityLabel("本地预览曲线")
                ForEach(workbench.points.indices, id: \.self) { index in
                    HStack {
                        Text("X \(Int(workbench.points[index].x))").font(.caption.monospacedDigit()).frame(width: 55, alignment: .leading)
                        Slider(value: Binding(get: { Double(workbench.points[index].y) },
                                              set: { workbench.setPoint(index, y: $0) }), in: 0...255, step: 1)
                        Text("\(Int(workbench.points[index].y))").font(.caption.monospacedDigit()).frame(width: 30)
                    }
                }
                HStack {
                    Button("线性") { workbench.applyLocalPreset(0) }
                    Button("柔和") { workbench.applyLocalPreset(1) }
                    Button("反差") { workbench.applyLocalPreset(2) }
                }.buttonStyle(.bordered)
                Text("曲线图使用控制点近似，不一定逐点对应导入的 LUT。示例为本地原创；仅改名称会保留原始 15 位 LUT，修改曲线后按 8 位重建。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("本地文件") {
                Button("导入 614B / NCP / 旧 84B") { importing = true }
                Button("导出 NCP（614B 包装）") {
                    do { exportDocument = RecipeDocument(data: try RecipePayload.exportNCP(workbench.snapshot())); exporting = true }
                    catch { workbench.message = "导出失败：名称须为 1–19 个 ASCII 字符，且曲线须有效。" }
                }
                Button("保存到本地预设库") { workbench.save() }.disabled(!workbench.storeHealthy)
                Text("仅支持明确识别的布局；旧 84B 为近似转换。导出不代表相机兼容。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("安卓快传码") {
                TextField("粘贴 ZCAI1 快传码", text: $workbench.shareText, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().lineLimit(2...5)
                HStack {
                    Button("生成") { workbench.makeShareCode() }
                    Button("导入") { workbench.importShareCode() }
                    if !workbench.shareText.isEmpty { ShareLink("分享", item: workbench.shareText) }
                }.buttonStyle(.bordered)
                Text("快传码只携带少量曲线点与参数，不保证 614B 或 15 位曲线无损；参数暂不应用。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("尚未接入") {
                Label("AI 逆向生成", systemImage: "sparkles").foregroundStyle(.secondary)
                Label("写入相机槽位", systemImage: "camera").foregroundStyle(.secondary)
                Text("AI 服务与相机写入将在安全验证完成后开放。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("配方工作台")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
                let data = try handle.read(upToCount: 619) ?? Data()
                guard data.count <= 618 else { workbench.message = "文件超过支持的长度；未导入。"; return }
                workbench.importBytes(data)
            } catch { workbench.message = "文件读取失败；未修改草稿。" }
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .data,
                      defaultFilename: "\(workbench.name).ncp") { result in
            switch result {
            case .success: workbench.message = "已导出本地 NCP；尚未验证机身兼容性。"
            case .failure: workbench.message = "导出未完成；原草稿未改变。"
            }
        }
    }
}

struct LocalPresetLibraryScreen: View {
    @ObservedObject var workbench: RecipeWorkbench
    @State private var deleting: LocalRecipe?
    var body: some View {
        List {
            Section {
                Text("PRESETS / 本地预设库").font(.caption.bold()).foregroundStyle(.yellow)
                Text(workbench.message).font(.footnote).foregroundStyle(.secondary)
                if workbench.entries.isEmpty { Text("尚无已保存的本地预设").foregroundStyle(.secondary) }
            }
            Section("已保存") {
                ForEach(workbench.entries) { entry in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(entry.name)
                            Text(entry.savedAt, style: .date).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("载入") { workbench.use(entry) }
                        Button("删除", role: .destructive) { deleting = entry }
                    }.buttonStyle(.borderless)
                }
            }
        }
        .navigationTitle("预设库")
        .confirmationDialog("删除本地预设？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("删除本地副本", role: .destructive) { if let deleting { workbench.remove(deleting) }; deleting = nil }
            Button("取消", role: .cancel) { deleting = nil }
        } message: { Text("仅删除应用内本地预设，不会修改相机。") }
    }
}
