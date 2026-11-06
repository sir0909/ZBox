import SwiftUI
import PhotosUI
import UIKit

/// A local-only sheet, available even while live view is running.
struct PoseReferenceScreen: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store = PoseReferenceStore()
    @State private var photo: PhotosPickerItem?
    @State private var selectedID: UUID?
    @State private var favoritesOnly = false
    @State private var pendingDeletion: PoseReference?
    @State private var importing = false
    @State private var importTask: Task<Void, Never>?
    @State private var importGeneration = UUID()
    @State private var groupID = 0
    @State private var phraseIndex = 0

    private var visible: [PoseReference] { store.items.filter { !favoritesOnly || $0.favorite } }
    private var selected: PoseReference? { visible.first { $0.id == selectedID } ?? visible.first }
    private var position: Int { selected.flatMap { current in visible.firstIndex { $0.id == current.id } } ?? 0 }
    private var group: PoseWords.Group { PoseWords.groups[groupID] }
    private var phrase: String { group.lines[min(phraseIndex, group.lines.count - 1)] }

    var body: some View {
        NavigationStack {
            List {
                referenceSection
                phraseSection
                Section {
                    Text("参考图和话术只在手机本地使用，不调用 AI、不读取或更改相机，也不会修改系统相册中的原图。图库尚未移植；请添加自己的参考图。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("姿势参考")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .onChange(of: photo) { _, item in startImport(item) }
            .onDisappear {
                importGeneration = UUID(); importTask?.cancel(); importTask = nil; importing = false
            }
            .confirmationDialog("删除这张本地参考图？", isPresented: Binding(
                get: { pendingDeletion != nil }, set: { if !$0 { pendingDeletion = nil } }), titleVisibility: .visible) {
                    Button("删除应用内副本", role: .destructive) {
                        if let target = pendingDeletion {
                            do { try store.remove(target.id) } catch { store.reportImportFailure() }
                            if selectedID == target.id { selectedID = nil }
                        }
                        pendingDeletion = nil
                    }
                    Button("取消", role: .cancel) { pendingDeletion = nil }
                } message: { Text("只删除本应用中的缩略图副本，不影响系统相册原图。") }
        }
    }

    private var referenceSection: some View {
        Section("自己的参考图") {
            PhotosPicker(selection: $photo, matching: .images) {
                Label(importing ? "正在处理…" : "从照片添加参考图", systemImage: "photo.badge.plus")
            }.disabled(!store.healthy || importing || store.items.count >= 200)
            Text("最多 200 张；复制为最长边 768 像素的 JPEG，不保存位置等原图元数据。")
                .font(.footnote).foregroundStyle(.secondary)
            Toggle("只看收藏", isOn: $favoritesOnly)
            if let current = selected {
                referenceImage(current)
                Text("\(position + 1) / \(visible.count) · 本地参考图")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                ViewThatFits(in: .horizontal) {
                    HStack { referenceButtons(current) }
                    VStack(alignment: .leading) { referenceButtons(current) }
                }.buttonStyle(.bordered)
            } else {
                Text(favoritesOnly ? "没有收藏的参考图。" : "还没有参考图，可以从照片中添加。")
                    .foregroundStyle(.secondary)
            }
            Text(store.message).font(.footnote).foregroundStyle(.secondary)
        }
    }
    private func referenceImage(_ current: PoseReference) -> some View {
        Group {
            if let data = store.imageData(current.id), let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 360)
                    .background(Color.black, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("自己添加的姿势参考图")
            } else {
                Label("参考图文件不可用；未自动删除索引。", systemImage: "photo.badge.exclamationmark")
                    .foregroundStyle(.secondary)
            }
        }
    }
    @ViewBuilder private func referenceButtons(_ current: PoseReference) -> some View {
        Button("上一张") { select(offset: -1) }.disabled(visible.count < 2)
        Button("下一张") { select(offset: 1) }.disabled(visible.count < 2)
        Button(current.favorite ? "取消收藏" : "收藏") {
            do { try store.toggleFavorite(current.id) } catch { store.reportImportFailure() }
        }.disabled(!store.healthy)
        Button("删除", role: .destructive) { pendingDeletion = current }.disabled(!store.healthy)
    }
    private func select(offset: Int) {
        guard !visible.isEmpty else { return }
        selectedID = visible[(position + offset + visible.count) % visible.count].id
    }

    private var phraseSection: some View {
        Section("摄影引导话术 · 离线") {
            Picker("分类", selection: $groupID) {
                ForEach(PoseWords.groups) { group in Text(group.title).tag(group.id) }
            }.onChange(of: groupID) { _, _ in phraseIndex = 0 }
            Text(phrase).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8).accessibilityLabel("摄影话术：\(phrase)")
            HStack {
                Button("换一句") { phraseIndex = (phraseIndex + 1) % group.lines.count }
                Button("复制话术") { UIPasteboard.general.string = phrase }
            }.buttonStyle(.bordered)
            Text("先征求被摄者意愿；一次只给一个小任务，不强迫动作、微笑或接触。话术是离线建议，不是 AI 实时生成。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func startImport(_ item: PhotosPickerItem?) {
        guard let item, !importing else { return }
        let generation = UUID(); importGeneration = generation; importing = true
        importTask = Task {
            defer {
                if generation == importGeneration { importing = false; photo = nil; importTask = nil }
            }
            do {
                guard let thumbnail = try await item.loadTransferable(type: ReferenceThumbnail.self) else {
                    store.reportImportFailure(); return
                }
                try Task.checkCancellation()
                guard generation == importGeneration else { return }
                let entry = try store.add(thumbnail.jpeg)
                favoritesOnly = false; selectedID = entry.id
            } catch is CancellationError {
                // No image is saved after a cancelled picker transfer.
            } catch { if generation == importGeneration { store.reportImportFailure() } }
        }
    }
}
