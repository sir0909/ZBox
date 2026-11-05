import SwiftUI
import UIKit

/// M2 monitor: no operation is started automatically on navigation or foregrounding.
struct MonitorHomeView: View {
    @StateObject private var model = CameraConnectionModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmCapture = false
    @State private var fullScreen = false
    private let yellow = Color(red: 1, green: 0.90, blue: 0)

    private var connected: Bool { model.phase == .connected }
    private var status: String {
        switch model.phase {
        case .idle: return "未连接"
        case .scanning: return "正在发现相机"
        case .connecting: return "正在连接"
        case .connected: return "已连接"
        case .disconnecting: return "正在断开"
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Text("Z").font(.title3.bold()).foregroundStyle(.black)
                .frame(width: 32, height: 32).background(yellow, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text("ZBOX").font(.headline)
                Text("NIKON · REMOTE MONITOR").font(.system(size: 9, weight: .medium, design: .monospaced)).foregroundStyle(.secondary)
            }
            Spacer()
            HStack(spacing: 5) {
                Circle().fill(connected ? Color.green : Color.gray).frame(width: 7, height: 7)
                Text(status).font(.caption)
            }
            if let level = model.batteryLevel { Text("\(level)%").font(.caption.monospacedDigit()) }
            NavigationLink { AppRootView(model: model) } label: { Image(systemName: "gearshape").font(.title3) }
                .accessibilityLabel("连接与设置")
        }
    }

    private var frameCard: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: 14).fill(Color.black)
            if let bytes = model.liveViewJPEG, let image = UIImage(data: bytes) {
                ViewfinderPicture(model: model, image: image).clipShape(RoundedRectangle(cornerRadius: 14))
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "viewfinder").font(.title2)
                    Text(connected ? model.liveViewMessage : "OFF · 未连接相机").font(.caption)
                }.foregroundStyle(.gray).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            VStack {
                HStack {
                    Text(model.liveViewPhase == .streaming ? "LIVE · \(model.liveViewFrames) 帧" : "LIVE · OFF")
                        .font(.caption2.bold()).padding(7).background(.black.opacity(0.65), in: Capsule())
                    Spacer()
                }
                Spacer()
                Button { fullScreen = true } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right").padding(9)
                        .background(.black.opacity(0.7), in: Circle())
                }
                .disabled(!connected)
                .accessibilityLabel("横屏全屏取景")
            }.padding(10)
        }
        .aspectRatio(3 / 2, contentMode: .fit)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.15)))
    }

    private var controls: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                if model.liveViewPhase == .idle {
                    Button("取景", systemImage: "viewfinder") { model.startLiveView() }.disabled(!model.canStartLiveView)
                } else {
                    Button("停取景", systemImage: "stop.fill") { Task { await model.stopLiveView() } }
                        .disabled(model.liveViewPhase == .stopping)
                }
                Button("AF", systemImage: "scope") { Task { await model.autofocusOnce() } }.disabled(!model.canAutofocus)
                Button("AI 构图", systemImage: "sparkles") {}.disabled(true)
            }.font(.caption).buttonStyle(.bordered).frame(maxWidth: .infinity)
            MovieControls(model: model)
            Text("\(model.focusMessage) · AF 为实验功能；AI 构图尚未实现")
                .font(.caption2).foregroundStyle(.secondary)
            HStack {
                NavigationLink { ExposureEditorScreen(model: model) } label: { Label("曝光", systemImage: "slider.horizontal.3") }
                    .disabled(!connected)
                Spacer()
                Button { confirmCapture = true } label: {
                    Circle().stroke(yellow, lineWidth: 3).frame(width: 60, height: 60)
                        .overlay(Circle().fill(yellow).frame(width: 44, height: 44))
                }
                .disabled(!model.canCapture)
                .accessibilityLabel("拍摄一张到相机存储卡")
                Spacer()
                NavigationLink { ExposureEditorScreen(model: model, menu: true) } label: { Label("菜单", systemImage: "square.grid.3x3") }
                    .disabled(!connected)
            }.buttonStyle(.plain)
            Text(model.captureMessage).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    topBar
                    Text("MONITOR / 竖屏监看").font(.caption.bold()).foregroundStyle(yellow)
                    if !connected {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("连接相机").font(.headline)
                                Text(model.statusMessage).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            NavigationLink("连接") { AppRootView(model: model) }.buttonStyle(.borderedProminent).tint(yellow).foregroundStyle(.black)
                        }.padding(12).background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
                    }
                    frameCard
                    if !model.exposureRows.isEmpty {
                        Text("上次读取的曝光信息（可能已过期）").font(.caption2).foregroundStyle(.secondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(model.exposureRows) { row in
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.title).font(.caption2).foregroundStyle(.secondary)
                                        Text(row.value).font(.caption.monospacedDigit())
                                    }.padding(8).background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
                                }
                            }
                        }
                    }
                    Text("轻点画面尝试触点对焦；辅助叠加仅分析预览，不修改原片")
                        .font(.caption2).foregroundStyle(.secondary)
                    controls
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("机身照片快览").font(.headline)
                            Text("前往机身相册，手动读取列表").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        NavigationLink("相册") { STAAlbumScreen(model: model) }
                            .disabled(!connected || !model.connectedSTA)
                    }.padding(12).background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
                    if !model.connectedSTA { Text("相册仅支持相机加入网络的 STA 模式").font(.caption2).foregroundStyle(.secondary) }
                }.padding()
            }
            .background(Color(red: 0.04, green: 0.045, blue: 0.05).ignoresSafeArea())
            .preferredColorScheme(.dark)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $fullScreen) { LiveViewScreen(model: model) }
            .confirmationDialog("拍摄一张照片到相机存储卡？", isPresented: $confirmCapture, titleVisibility: .visible) {
                Button("确认拍摄") { Task { await model.captureOneToCard() } }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将先停止取景。相机接受命令不等于已核实落卡；超时或断线请先检查机身，不要重复拍摄。")
            }
            .onDisappear {
                if !fullScreen {
                    model.cancelPendingCapture()
                    model.requestStopLiveView()
                }
            }
        }
        .task {
            model.loadIdentity()
            if scenePhase == .active { model.appBecameActive() } else { model.appBecameInactive() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.appBecameActive() } else { model.appBecameInactive() }
        }
    }
}
