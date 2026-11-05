import SwiftUI
import ZboxCore

struct ExposureEditorScreen: View {
    @ObservedObject var model: CameraConnectionModel
    private let menu: Bool
    @State private var property: ExposureProperty
    init(model: CameraConnectionModel, menu: Bool = false) {
        self.model = model
        self.menu = menu
        self._property = State(initialValue: menu ? .picture : .iso)
    }
    @State private var desired: Data?
    @State private var confirmation = false

    private func label(_ bytes: Data, property: ExposureProperty) -> String {
        property.displayValue(bytes) ?? bytes.map { String(format: "%02X", $0) }.joined(separator: " ")
    }

    var body: some View {
        List {
            Section(menu ? "实验性 i 菜单" : "实验性曝光设置") {
                Text("仅提供相机描述符声明且可识别的值；更改会修改机身设置，尚未在 iPhone + Z 30 真机验证。i 菜单未知含义按原始代码显示，不猜测名称。")
                    .font(.footnote).foregroundStyle(.secondary)
                Picker("属性", selection: $property) {
                    ForEach(menu ? ExposureProperty.menuCases : ExposureProperty.exposureCases, id: \.rawValue) { item in
                        Text(item.title).tag(item)
                    }
                }
                .disabled(model.exposureBusy)
                .onChange(of: property) { _, _ in desired = nil }
                Button(model.exposureBusy ? "处理中…" : "读取相机支持的值") {
                    desired = nil
                    Task { await model.loadExposureDescriptor(property) }
                }
                .disabled(!model.canEditExposure)
                Text(model.exposureEditMessage).font(.footnote).foregroundStyle(.secondary)
            }
            if let d = model.exposureDescriptor, d.property == property {
                Section("相机允许的值") {
                    LabeledContent("当前值", value: label(d.current, property: property))
                    ForEach(d.choices, id: \.self) { bytes in
                        Button {
                            desired = bytes
                            confirmation = true
                        } label: {
                            HStack {
                                Text(label(bytes, property: property))
                                Spacer()
                                if bytes == d.current { Text("当前").foregroundStyle(.secondary) }
                            }
                        }
                        .disabled(!d.writable || model.exposureBusy || !model.canEditExposure)
                    }
                }
            }
        }
        .navigationTitle(menu ? "i 菜单" : "曝光设置")
        .confirmationDialog("确认修改相机设置？", isPresented: $confirmation, titleVisibility: .visible) {
            Button("确认写入") {
                if let d = model.exposureDescriptor, let desired, d.property == property {
                    Task { await model.writeExposure(d, desired: desired) }
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("会重新核实候选值和当前值，再写入并读回。连接中断时结果可能未知，请先检查机身，不要重复写入。")
        }
        .onDisappear { model.cancelPendingExposureWrite() }
    }
}
