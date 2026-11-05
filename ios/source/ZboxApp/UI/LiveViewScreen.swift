import SwiftUI
import UIKit

/// Explicit live-view screen; it does not start camera operations on navigation.
struct LiveViewScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmCapture = false
    @State private var landscapeRequested = false
    @State private var orientationMessage = ""
    @State private var hostingWindow: UIWindow?
    @State private var attemptedAutoRotation = false

    private func toggleLandscape() {
        guard let window = hostingWindow else {
            orientationMessage = "无法定位当前窗口；请手动旋转设备"
            return
        }
        if landscapeRequested {
            OrientationPolicy.shared.release(in: window, requestPortrait: true)
            landscapeRequested = false
            orientationMessage = "已恢复默认方向；竖屏请求由系统决定"
        } else {
            landscapeRequested = true
            orientationMessage = "已请求横屏方向；系统或 iPad 多任务可能覆盖"
            OrientationPolicy.shared.requestLandscape(in: window) { error in
                landscapeRequested = false
                orientationMessage = error
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: { Label("返回", systemImage: "chevron.left") }
            Spacer(minLength: 0)
            Text("实时取景").font(.headline)
            Spacer(minLength: 0)
            Button(landscapeRequested ? "退出横屏" : "横屏全屏") {
                toggleLandscape()
            }.font(.caption)
            Text("\(model.liveViewFrames) 帧").font(.caption.monospacedDigit())
        }
        .foregroundStyle(.white)
        .padding(.horizontal)
        .padding(.top, 6)
    }

    private var viewfinder: some View {
        ZStack {
            Color(white: 0.08)
            if let data = model.liveViewJPEG, let image = UIImage(data: data) {
                ViewfinderPicture(model: model, image: image)
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "viewfinder").font(.largeTitle)
                    Text(model.liveViewMessage).font(.subheadline)
                }.foregroundStyle(.gray)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .accessibilityElement(children: .combine)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.liveViewMessage).font(.caption).foregroundStyle(.white.opacity(0.8)).lineLimit(3)
            if !orientationMessage.isEmpty {
                Text(orientationMessage).font(.caption2).foregroundStyle(.white.opacity(0.7))
            }
            if model.captureBusy || model.captureMessage != "拍摄到机身存储卡" {
                Text(model.captureMessage).font(.caption).foregroundStyle(.white.opacity(0.8))
            }
            HStack {
                Button(model.captureBusy ? "拍摄中…" : "实验性 · 拍一张") { confirmCapture = true }
                    .disabled(!model.canCapture)
                if model.liveViewPhase == .idle {
                    Button("开始取景") { model.startLiveView() }.disabled(!model.canStartLiveView)
                } else {
                    Button("停止取景") { Task { await model.stopLiveView() } }
                        .disabled(model.liveViewPhase == .stopping)
                }
            }.buttonStyle(.borderedProminent)
            Button(model.focusBusy ? "对焦中…" : "单次 AF") { Task { await model.autofocusOnce() } }
                .disabled(!model.canAutofocus).buttonStyle(.bordered)
            Text(model.focusMessage).font(.caption2).foregroundStyle(.white.opacity(0.7))
            MovieControls(model: model, compact: true)
            HStack {
                NavigationLink { ExposureEditorScreen(model: model) } label: {
                    Label("曝光", systemImage: "slider.horizontal.3")
                }
                NavigationLink { ExposureEditorScreen(model: model, menu: true) } label: {
                    Label("i 菜单", systemImage: "square.grid.3x3")
                }
            }.buttonStyle(.bordered)
            Text("进入设置会停止取景；设置后需手动重新开启。")
                .font(.caption2).foregroundStyle(.white.opacity(0.65))
        }
        .foregroundStyle(.white)
        .padding(10)
    }

    private var landscapeLeftRail: some View {
        VStack(spacing: 8) {
            Button { dismiss() } label: { Label("退出", systemImage: "chevron.left") }
            if model.liveViewPhase == .idle {
                Button("开始取景") { model.startLiveView() }.disabled(!model.canStartLiveView)
            } else {
                Button("停取景") { Task { await model.stopLiveView() } }.disabled(model.liveViewPhase == .stopping)
            }
            Button("单次 AF") { Task { await model.autofocusOnce() } }.disabled(!model.canAutofocus)
            Spacer(minLength: 0)
            Text("LIVE · \(model.liveViewFrames)").font(.caption2.monospacedDigit())
        }.font(.caption2).buttonStyle(.bordered).padding(6).frame(maxHeight: .infinity)
    }
    private var landscapeRightRail: some View {
        ScrollView {
            VStack(spacing: 8) {
                NavigationLink { ExposureEditorScreen(model: model) } label: { Text("曝光") }
                Button { confirmCapture = true } label: {
                    Circle().stroke(.yellow, lineWidth: 3).frame(width: 44, height: 44)
                        .overlay(Circle().fill(.yellow).frame(width: 30, height: 30))
                }.disabled(!model.canCapture).accessibilityLabel("拍摄到机身存储卡")
                NavigationLink { ExposureEditorScreen(model: model, menu: true) } label: { Text("i 菜单") }
                MovieControls(model: model, compact: true)
                Text(model.focusMessage).font(.caption2).foregroundStyle(.secondary)
            }.font(.caption2).buttonStyle(.bordered).padding(6)
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 4) {
                    header
                    if landscape {
                        HStack(spacing: 0) {
                            landscapeLeftRail.frame(width: min(geometry.size.width * 0.15, 130))
                            viewfinder
                            landscapeRightRail.frame(width: min(geometry.size.width * 0.18, 160))
                        }
                    } else {
                        viewfinder
                        ScrollView { controls }.frame(maxHeight: min(geometry.size.height * 0.42, 250))
                    }
                }
            }
        }
        .background(OrientationWindowReader(window: $hostingWindow).frame(width: 0, height: 0))
        .statusBarHidden(landscapeRequested)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if hostingWindow != nil && !attemptedAutoRotation {
                attemptedAutoRotation = true
                toggleLandscape()
            }
        }
        .onChange(of: hostingWindow) { _, window in
            if window != nil && !attemptedAutoRotation {
                attemptedAutoRotation = true
                toggleLandscape()
            }
        }
        .confirmationDialog("拍摄一张照片到相机存储卡？", isPresented: $confirmCapture, titleVisibility: .visible) {
            Button("确认拍摄") { Task { await model.captureOneToCard() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("该功能尚未在 iPhone 与 Z 30 真机验证，将先停止取景。相机接受快门命令不代表已核实落卡；如连接中断，请先检查机身，避免重复拍摄。")
        }
        .onDisappear {
            if let window = hostingWindow { OrientationPolicy.shared.release(in: window, requestPortrait: landscapeRequested) }
            landscapeRequested = false
            attemptedAutoRotation = false
            orientationMessage = ""
            model.cancelPendingCapture()
            model.requestStopLiveView()
        }
    }
}
