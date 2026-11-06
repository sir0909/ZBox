import SwiftUI
import ZboxCore

/// The bundled metadata is available offline; photographs are fetched only after explicit consent.
struct PoseCatalogScreen: View {
    @ObservedObject private var library = PoseCatalogStore.shared
    @State private var filters: [String: String] = [:]
    @State private var favoritesOnly = false
    @State private var filterExpanded = false
    @State private var selectedName: String?
    @State private var downloadConfirmation = false
    private var visible: [PoseCatalog.Item] {
        guard let catalog = library.catalog else { return [] }
        let result = catalog.filtered(filters)
        return favoritesOnly ? result.filter { library.favorites.contains($0.name) } : result
    }
    private var current: PoseCatalog.Item? { visible.first { $0.name == selectedName } ?? visible.first }
    private var position: Int { current.flatMap { item in visible.firstIndex { $0.name == item.name } } ?? 0 }

    var body: some View {
        List {
            downloadSection
            filterSection
            referenceSection
            Section {
                Text("Poze ©2025 · MIT；固定版本 827ce3e。代码许可不等于人物肖像或照片商用授权，请自行确认使用权。图库仅经同意从 GitHub 下载，不调用 AI、不上传个人图片，也不改变相机。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("姿势图库")
        .onDisappear { library.cancel() }
        .confirmationDialog("下载固定版本姿势图库？", isPresented: $downloadConfirmation, titleVisibility: .visible) {
            Button("同意下载并校验") { library.downloadAfterConsent() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将从 GitHub 下载约 112 MiB（可能产生流量费用），请预留至少 340 MiB 空间。逐张按随应用提供的 Git blob 校验；过程可取消，已校验图片保留。关闭此页面会取消，不会自动重试或上传你的照片。")
        }
    }
    private var downloadSection: some View {
        Section("离线图库 · 4155 张") {
            Text(library.message).font(.footnote).foregroundStyle(.secondary)
            if library.busy {
                ProgressView()
                Button("取消下载与校验", role: .cancel) { library.cancel() }
            } else {
                Button(library.installed ? "重新下载并核验图库" : "下载姿势图库") { downloadConfirmation = true }
                    .disabled(library.catalog == nil)
            }
            Text("显示前会再次核验单张文件；缺失或损坏的图片不会显示，可手动确认后重新下载修复。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }
    private var filterSection: some View {
        Section {
            Toggle("只看收藏", isOn: $favoritesOnly).onChange(of: favoritesOnly) { _, _ in selectedName = nil }
            DisclosureGroup("姿势筛选 · 12 类", isExpanded: $filterExpanded) {
                if let catalog = library.catalog {
                    ForEach(Array(PoseCatalog.fields.enumerated()), id: \.element) { index, field in
                        Picker(PoseCatalog.fieldLabels[index], selection: Binding(
                            get: { filters[field] ?? "" }, set: { value in
                                if value.isEmpty { filters.removeValue(forKey: field) } else { filters[field] = value }
                                selectedName = nil
                            })) {
                                Text("全部").tag("")
                                ForEach((catalog.definitions[field] ?? [:]).keys.sorted(), id: \.self) { code in
                                    Text(catalog.definitions[field]?[code] ?? code).tag(code)
                                }
                            }
                    }
                    Button("清空筛选") { filters = [:]; selectedName = nil }.disabled(filters.isEmpty)
                }
            }
            Text("符合条件：\(visible.count) 张").font(.footnote).foregroundStyle(.secondary)
        }
    }
    private var referenceSection: some View {
        Section("姿势参考") {
            if let item = current {
                if let image = library.image(item) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 360)
                        .background(Color.black, in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("姿势图库参考图")
                } else {
                    Label(library.installed ? "图片缺失或校验失败" : "图片尚未下载", systemImage: "photo")
                        .foregroundStyle(.secondary)
                }
                Text("\(position + 1) / \(visible.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                if let catalog = library.catalog {
                    let chars = Array(item.code).map(String.init)
                    let labels = zip(PoseCatalog.fields, chars).compactMap { catalog.definitions[$0.0]?[$0.1] }
                    Text(labels.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
                }
                ViewThatFits(in: .horizontal) {
                    HStack { controls(item) }
                    VStack(alignment: .leading) { controls(item) }
                }.buttonStyle(.bordered)
            } else { Text("当前筛选没有参考图。").foregroundStyle(.secondary) }
        }
    }
    @ViewBuilder private func controls(_ item: PoseCatalog.Item) -> some View {
        Button("上一张") { move(-1) }.disabled(visible.count < 2)
        Button("随机") {
            if visible.count > 1 { selectedName = visible.filter { $0.name != item.name }.randomElement()?.name }
        }.disabled(visible.count < 2)
        Button("下一张") { move(1) }.disabled(visible.count < 2)
        Button(library.favorites.contains(item.name) ? "取消收藏" : "收藏") { library.toggleFavorite(item) }
            .disabled(!library.favoritesHealthy)
    }
    private func move(_ offset: Int) {
        guard !visible.isEmpty else { return }
        selectedName = visible[(position + offset + visible.count) % visible.count].name
    }
}
