import SwiftUI
import UniformTypeIdentifiers
import PhotosUI
import UIKit
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
    @State private var exportFilename = "ZBOX.ncp"
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var referenceJPEG: Data?
    @State private var processedPNG: Data?
    @State private var showOriginal = false
    @State private var referenceGeneration = UUID()
    @State private var previewTask: Task<Void, Never>?
    @State private var previewGeneration = UUID()
    @State private var previewPending = false
    @State private var previewFailed = false
    @State private var aiSettingsExpanded = false
    @State private var localColorExpanded = false
    @AppStorage("ai.baseURL") private var aiBaseURL = "https://open.bigmodel.cn/api/paas/v4"
    @AppStorage("ai.model") private var aiModel = "glm-4v-flash"
    @State private var keyInput = ""
    @State private var aiHint = ""
    @State private var aiMessage = "需要视觉模型、服务商账号和 API Key。"
    @State private var aiBusy = false
    @State private var confirmAI = false
    @State private var aiTask: Task<Void, Never>?


    private var curve: [UInt8] { (try? workbench.previewLUT()) ?? (0...255).map(UInt8.init) }
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label("本地草稿", systemImage: "slider.horizontal.3")
                        .font(.headline)
                    Text("在手机上编辑曲线，预览为近似效果，不会写入相机。")
                        .font(.subheadline).foregroundStyle(.secondary)
                }.padding(.vertical, 4)
                TextField("机身名称（1–19 个 ASCII 字符）", text: $workbench.name)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .accessibilityLabel("机身名称，1 到 19 个 ASCII 字符")
                Text(workbench.message).font(.footnote).foregroundStyle(.secondary)
            }
            referenceSection
            Section("色调曲线 · 256 LUT") {
                curveChart
                ForEach(workbench.points.indices, id: \.self) { index in
                    HStack(spacing: 8) {
                        Text("X \(Int(workbench.points[index].x))")
                            .font(.caption.monospacedDigit()).frame(minWidth: 48, alignment: .leading)
                            .accessibilityHidden(true)
                        Slider(value: Binding(get: { Double(workbench.points[index].y) },
                                              set: { workbench.setPoint(index, y: $0) }), in: 0...255, step: 1)
                            .accessibilityLabel("输入 \(Int(workbench.points[index].x)) 的曲线控制点")
                            .accessibilityValue("输出 \(Int(workbench.points[index].y))，范围 0 到 255")
                        Text("\(Int(workbench.points[index].y))")
                            .font(.caption.monospacedDigit()).frame(minWidth: 28, alignment: .trailing)
                            .accessibilityHidden(true)
                    }.frame(minHeight: 44)
                }
                HStack {
                    Button("线性") { workbench.applyLocalPreset(0) }
                    Button("柔和") { workbench.applyLocalPreset(1) }
                    Button("反差") { workbench.applyLocalPreset(2) }
                }.buttonStyle(.bordered)
                Text("曲线图显示当前本地预览 LUT。导入的 LUT 可能无法由少量控制点精确表达；仅改名称保留原始 15 位 LUT；调整控制点会近似重建 15 位曲线，仍可能丢失原曲线细节。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            localColorSection
            Section("AI 参考图逆向 · 实验功能") {
                DisclosureGroup("服务配置", isExpanded: $aiSettingsExpanded) {
                    TextField("HTTPS Base URL（不含 /chat/completions）", text: $aiBaseURL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        .accessibilityLabel("服务商 HTTPS 基础地址")
                    TextField("视觉模型名称", text: $aiModel)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("API Key（保存到本机钥匙串）", text: $keyInput)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    ViewThatFits(in: .horizontal) {
                        HStack { aiKeyButtons }
                        VStack(alignment: .leading) { aiKeyButtons }
                    }.buttonStyle(.bordered).disabled(aiBusy)
                }
                TextField("参考图风格要求（可选）", text: $aiHint, axis: .vertical).lineLimit(1...3)
                Button("生成近似曲线") { confirmAI = true }
                    .disabled(referenceJPEG == nil || aiBusy)
                if aiBusy { ProgressView("等待服务商响应…") }
                Text(aiMessage).font(.footnote).foregroundStyle(.secondary)
                Text("生成近似全局曲线和可选的手机色彩预览建议，不生成真实色彩匹配或机身参数；服务商可能计费。Key 仅保存在此设备钥匙串中。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("本地文件") {
                Button("导入 ZBox 配方 / 614B / NCP / 旧 84B") { importing = true }
                Button("导出 NCP（614B 包装）") {
                    do { exportDocument = RecipeDocument(data: try RecipePayload.exportNCP(workbench.snapshot())); exportFilename = "\(workbench.name).ncp"; exporting = true }
                    catch { workbench.message = "导出失败：名称须为 1–19 个 ASCII 字符，且曲线须有效。" }
                }
                Button("导出 ZBox 配方（保留本地预览参数）") {
                    do { exportDocument = RecipeDocument(data: try workbench.exportArchive()); exportFilename = "\(workbench.name).zboxrecipe"; exporting = true }
                    catch { workbench.message = "导出失败：请检查名称、曲线和本地参数。" }
                }
                Button("保存到本地预设库") { workbench.save() }.disabled(!workbench.storeHealthy)
                Text("ZBox 配方是本应用格式，保留载荷、控制点和本地预览参数；校验值不代表来源可信。NCP 不含预览参数；旧 84B 是近似转换，导出不代表相机兼容。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("安卓快传码") {
                TextField("粘贴 ZCAI1 快传码", text: $workbench.shareText, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().lineLimit(2...5)
                HStack {
                    Button("生成") { workbench.makeShareCode() }
                    Button("导入") { workbench.importShareCode(); updatePreview() }
                    if !workbench.shareText.isEmpty { ShareLink("分享", item: workbench.shareText) }
                }.buttonStyle(.bordered)
                Text("快传码只携带曲线点与部分参数，不保证 614B 或 15 位曲线无损；部分参数仅用于手机近似预览，不写入 NCP 或相机。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("尚未接入") {
                Label("写入相机槽位", systemImage: "camera").foregroundStyle(.secondary)
                Text("相机写入将在安全验证完成后开放。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("配方工作台")
        .onChange(of: selectedPhoto) { _, item in
            referenceGeneration = UUID()
            let generation = referenceGeneration
            previewTask?.cancel(); previewGeneration = UUID(); previewPending = false; previewFailed = false
            referenceJPEG = nil; processedPNG = nil; showOriginal = false
            guard let item else { return }
            Task {
                do {
                    guard let thumbnail = try await item.loadTransferable(type: ReferenceThumbnail.self),
                          generation == referenceGeneration else { return }
                    referenceJPEG = thumbnail.jpeg
                    updatePreview()
                } catch {
                    if generation == referenceGeneration { workbench.message = "参考图读取失败或尺寸过大；未上传、未修改原图。" }
                }
            }
        }
        .onChange(of: workbench.points) { _, _ in updatePreview() }
        .onChange(of: workbench.metadata) { _, _ in updatePreview() }
        .onChange(of: workbench.presetFamily) { _, _ in updatePreview() }
        .onAppear { if referenceJPEG != nil { updatePreview() } }
        .onDisappear {
            previewTask?.cancel(); previewGeneration = UUID(); previewPending = false
            aiTask?.cancel(); referenceGeneration = UUID()
        }
        .confirmationDialog("向服务商上传参考图？", isPresented: $confirmAI, titleVisibility: .visible) {
            Button("同意上传并生成") { startAI() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将向 \(aiBaseURL) 发送去除元数据的参考图缩略图、文字要求和 API Key。图片将由该服务商处理，可能计费；请先确认服务商的隐私政策。不会自动写入相机。")
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
                var data = Data()
                while data.count <= 32_768 {
                    let chunk = try handle.read(upToCount: min(4096, 32_769 - data.count)) ?? Data()
                    if chunk.isEmpty { break }
                    data.append(chunk)
                }
                guard data.count <= 32_768 else { workbench.message = "文件超过支持的长度；未导入。"; return }
                workbench.importBytes(data)
                updatePreview()
            } catch { workbench.message = "文件读取失败；未修改草稿。" }
        }
        .fileExporter(isPresented: $exporting, document: exportDocument, contentType: .data,
                      defaultFilename: exportFilename) { result in
            switch result {
            case .success: workbench.message = "已导出本地文件；不代表机身兼容性。"
            case .failure: workbench.message = "导出未完成；原草稿未改变。"
            }
        }
    }
    private var localColorSection: some View {
        Section {
            DisclosureGroup("手机预览色彩调节", isExpanded: $localColorExpanded) {
                ForEach(LocalPreviewParameter.allCases) { parameter in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(parameter.title)
                            Spacer()
                            Text(workbench.previewValue(parameter), format: .number.precision(.fractionLength(1)))
                                .monospacedDigit().foregroundStyle(.secondary)
                                .accessibilityHidden(true)
                        }
                        Slider(value: Binding(get: { workbench.previewValue(parameter) },
                                              set: { workbench.setPreviewValue(parameter, value: $0) }),
                               in: parameter.range, step: 0.5)
                            .accessibilityLabel("手机预览\(parameter.title)")
                            .accessibilityValue(String(format: "%.1f", workbench.previewValue(parameter)))
                            .frame(minHeight: 44)
                    }
                }
                Button("重置四项调节") { workbench.resetPreviewColors() }
                    .disabled(workbench.metadata == nil)
                Text("这些设置只影响手机近似预览，并随本地预设和快传码保存；不会写入导出的 NCP 或相机。黑白类型仍由预设决定。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var aiKeyButtons: some View {
        Group {
            Button("保存 Key") { saveAIKey() }
            Button("删除此服务 Key", role: .destructive) { deleteAIKey() }
        }
    }

    private var referenceSection: some View {
        Section("参考图 · 手机本地预览") {
            VStack(alignment: .leading, spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12).fill(Color.black)
                    if let data = (showOriginal ? referenceJPEG : processedPNG ?? referenceJPEG),
                       let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .accessibilityLabel(showOriginal || processedPNG == nil ? "参考图原图" : "曲线近似效果")
                    } else {
                        VStack(spacing: 8) {
                            Image(systemName: "photo.on.rectangle").font(.title2).accessibilityHidden(true)
                            Text("选择一张参考图").font(.subheadline)
                        }.foregroundStyle(.white.opacity(0.85))
                    }
                }
                .aspectRatio(4 / 3, contentMode: .fit)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
                HStack(spacing: 8) {
                    if previewPending && !showOriginal { ProgressView().controlSize(.small) }
                    Text(previewStatus).font(.footnote).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }.accessibilityElement(children: .combine)
                ViewThatFits(in: .horizontal) {
                    HStack { referenceButtons }
                    VStack(alignment: .leading) { referenceButtons }
                }.buttonStyle(.bordered).controlSize(.large)
                Text("手机近似模拟曲线、黑白、亮度、对比、饱和度和色相；基础风格、锐化与清晰度未模拟。参数不会写入 NCP 或相机，效果不代表尼康或 RAW 实际成像。原图不上传或修改，系统可能从 iCloud 下载。")
                    .font(.footnote).foregroundStyle(.secondary)
            }.padding(.vertical, 4)
        }
    }

    private var previewStatus: String {
        if referenceJPEG == nil { return "选择后可比较原图与曲线效果" }
        if showOriginal { return "正在显示原图" }
        if previewPending { return "正在生成效果，暂时显示原图" }
        if previewFailed { return "参数无效或效果生成失败，正在显示原图" }
        return processedPNG == nil ? "正在显示原图" : "正在显示曲线近似效果"
    }

    private var referenceButtons: some View {
        Group {
            PhotosPicker(selection: $selectedPhoto, matching: .images) {
                Label(referenceJPEG == nil ? "选择参考图" : "更换参考图", systemImage: "photo.on.rectangle")
            }
            Button(showOriginal ? "显示效果" : "显示原图") { showOriginal.toggle() }
                .disabled(referenceJPEG == nil || (!showOriginal && processedPNG == nil))
        }
    }

    private var curveChart: some View {
        Canvas { context, size in
            var grid = Path()
            for i in 0...4 {
                let x = size.width * CGFloat(i) / 4, y = size.height * CGFloat(i) / 4
                grid.move(to: CGPoint(x: x, y: 0)); grid.addLine(to: CGPoint(x: x, y: size.height))
                grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(grid, with: .color(.white.opacity(0.20)), lineWidth: 0.5)
            var path = Path()
            let values = curve
            for i in values.indices {
                let point = CGPoint(x: size.width * CGFloat(i) / 255, y: size.height * (1 - CGFloat(values[i]) / 255))
                if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            context.stroke(path, with: .color(Color(red: 1, green: 0.90, blue: 0)), lineWidth: 2)
        }
        .padding(12).frame(height: 204)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel("当前本地预览曲线图，控制点可在下方调整")
    }

    private func saveAIKey() {
        do {
            let base = try AIKeyStore.validatedBase(aiBaseURL)
            try AIKeyStore.save(keyInput, base: base); keyInput = ""
            aiMessage = "Key 已保存到此设备钥匙串，仅用于当前 Base URL。"
        } catch { aiMessage = "保存失败：请检查 HTTPS 地址及 Key。" }
    }
    private func deleteAIKey() {
        do { try AIKeyStore.delete(base: AIKeyStore.validatedBase(aiBaseURL)); keyInput = ""; aiMessage = "当前地址的 Key 已删除。" }
        catch { aiMessage = "删除失败，请检查地址或钥匙串状态。" }
    }
    private func startAI() {
        guard !aiBusy, let jpeg = referenceJPEG else { return }
        do {
            let base = try AIKeyStore.validatedBase(aiBaseURL)
            let key = try AIKeyStore.read(base: base)
            let model = aiModel, hint = aiHint, generation = referenceGeneration
            let draft = workbench.draftIdentity()
            aiBusy = true; aiMessage = "已获得本次上传确认，正在生成…"
            aiTask = Task {
                defer { aiBusy = false }
                do {
                    let result = try await AIGrading.analyze(base: base, model: model, key: key, jpeg: jpeg, hint: hint)
                    guard !Task.isCancelled else { return }
                    guard generation == referenceGeneration, draft == workbench.draftIdentity() else {
                        aiMessage = "参考图或草稿已更改，本次结果未应用；服务商可能已收到图片。"
                        return
                    }
                    try workbench.useAI(result)
                    updatePreview()
                    aiMessage = "已载入 AI 近似结果；色彩建议仅用于手机预览，不会写入 NCP 或相机。"
                } catch is CancellationError { aiMessage = "请求已取消，服务商可能已收到数据。" }
                catch { if !Task.isCancelled { aiMessage = "生成失败或输出未通过校验；草稿未改变。请检查配置、网络和视觉模型。" } }
            }
        } catch { aiMessage = "请展开「服务配置」，填写有效 HTTPS Base URL 并保存该地址的 Key。" }
    }
    private func updatePreview() {
        previewTask?.cancel()
        previewGeneration = UUID()
        let generation = previewGeneration
        processedPNG = nil
        guard let jpeg = referenceJPEG else { previewPending = false; previewFailed = false; return }
        guard let lut = try? workbench.previewLUT(), let adjustments = workbench.previewAdjustments() else {
            previewPending = false; previewFailed = true; return
        }
        previewPending = true; previewFailed = false
        previewTask = Task {
            do { try await Task.sleep(nanoseconds: 250_000_000) } catch { return }
            let output = await Task.detached(priority: .utility) { ReferencePreview.apply(jpeg: jpeg, lut: lut, adjustments: adjustments) }.value
            guard !Task.isCancelled, generation == previewGeneration else { return }
            processedPNG = output
            previewPending = false; previewFailed = output == nil
        }
    }
}

struct LocalPresetLibraryScreen: View {
    @ObservedObject var workbench: RecipeWorkbench
    @State private var deleting: LocalRecipe?
    @State private var builtIns: [BuiltInPreset] = []
    @State private var catalogError = false
    @State private var search = ""
    @State private var category = "全部"
    private var categories: [String] { ["全部"] + Array(Set(builtIns.map(\.categoryLabel))).sorted() }
    private var filteredBuiltIns: [BuiltInPreset] {
        builtIns.filter { item in
            (category == "全部" || item.categoryLabel == category) &&
            (search.isEmpty || (item.displayName + " " + item.cameraName + " " + item.note + " " + item.categoryLabel)
                .localizedCaseInsensitiveContains(search))
        }
    }
    var body: some View {
        List {
            Section {
                Text("PRESETS / 本地预设库").font(.caption.bold()).foregroundStyle(.yellow)
                Text(workbench.message).font(.footnote).foregroundStyle(.secondary)
                if workbench.entries.isEmpty { Text("尚无已保存的本地预设").foregroundStyle(.secondary) }
            }
            Section("内置预设 · 200 套") {
                Text("移植自安卓预设。曲线可用于本地载荷；黑白和部分色彩参数仅用于手机近似预览，基础风格、锐化与清晰度未模拟；不代表机身效果。")
                    .font(.footnote).foregroundStyle(.secondary)
                if catalogError { Text("内置数据读取失败").foregroundStyle(.red) }
                Picker("分类", selection: $category) {
                    ForEach(categories, id: \.self) { Text($0).tag($0) }
                }
                TextField("搜索名称或描述", text: $search)
                Text("显示 \(filteredBuiltIns.count) 套").font(.caption).foregroundStyle(.secondary)
                ForEach(filteredBuiltIns) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(entry.displayName).font(.headline)
                            Spacer()
                            Button("载入预设") { workbench.useBuiltIn(entry) }
                        }
                        Text("\(entry.categoryLabel) · \(entry.cameraName) · \(entry.source == "derived" ? "衍生" : "NCP 数据")")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(entry.note).font(.caption).foregroundStyle(.secondary)
                    }
                }
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
        .task {
            do { builtIns = try BuiltInPresetCatalog.load() }
            catch { catalogError = true }
        }
        .confirmationDialog("删除本地预设？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("删除本地副本", role: .destructive) { if let deleting { workbench.remove(deleting) }; deleting = nil }
            Button("取消", role: .cancel) { deleting = nil }
        } message: { Text("仅删除应用内本地预设，不会修改相机。") }
    }
}
