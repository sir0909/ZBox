import SwiftUI
import UIKit

/// Explicit live-view screen; it does not start camera operations on navigation.
struct LiveViewScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmCapture = false
    @State private var landscapeRequested = false
    @State private var orientationMessage = ""

    /// Request a scene rotation only when the foreground scene is unambiguous.
    /// iPad multitasking or system policy may reject the request; never claim a lock.
    private func requestOrientation(_ mask: UIInterfaceOrientationMask, reportErrors: Bool) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
        guard scenes.count == 1, let scene = scenes.first else {
            if reportErrors { orientationMessage = "无法确定当前窗口；请手动旋转设备" }
            return
        }
        scene.windows.first(where: { $0.isKeyWindow })?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in
            if reportErrors {
                DispatchQueue.main.async { orientationMessage = "系统未接受方向请求；请手动旋转设备：\(error.localizedDescription)" }
            }
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 12) {
                    HStack {
                        Button { dismiss() } label: {
                            Label("返回", systemImage: "chevron.left")
                        }
                        Spacer()
                        Text("实时取景").font(.headline)
                        Spacer()
                        Button(landscapeRequested ? "退出横屏" : "横屏全屏") {
                            landscapeRequested.toggle()
                            orientationMessage = landscapeRequested ? "已请求横屏；能否旋转由系统决定" : "已请求竖屏"
                            requestOrientation(landscapeRequested ? .landscape : .portrait, reportErrors: true)
                        }.font(.caption)
                        Text("\(model.liveViewFrames) 帧")
                            .font(.caption.monospacedDigit())
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal)
                    .padding(.top, 8)

                    ZStack {
                        Color(white: 0.08)
                        if let data = model.liveViewJPEG, let image = UIImage(data: data) {
                            Image(uiImage: image).resizable().scaledToFit()
                                .accessibilityLabel("相机实时取景")
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

                    if !orientationMessage.isEmpty {
                        Text(orientationMessage).font(.caption2).foregroundStyle(.white.opacity(0.7))
                            .padding(.horizontal)
                    }
                    if model.captureBusy || model.captureMessage != "拍摄到机身存储卡" {
                        Text(model.captureMessage).font(.caption).foregroundStyle(.white.opacity(0.8))
                            .padding(.horizontal)
                    }
                    HStack(spacing: 18) {
                        Text(model.liveViewMessage)
                            .font(.caption).foregroundStyle(.white.opacity(0.75))
                            .lineLimit(2)
                        Spacer()
                        Button(model.captureBusy ? "拍摄中…" : "实验性 · 拍一张") { confirmCapture = true }
                            .disabled(!model.canCapture)
                        if model.liveViewPhase == .idle {
                            Button("开始取景") { model.startLiveView() }
                                .disabled(!model.canStartLiveView)
                        } else {
                            Button("停止取景") { Task { await model.stopLiveView() } }
                                .disabled(model.liveViewPhase == .stopping)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.horizontal)
                    .padding(.bottom, landscape ? 4 : 16)
                }
            }
        }
        .statusBarHidden(landscapeRequested)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .confirmationDialog("拍摄一张照片到相机存储卡？", isPresented: $confirmCapture, titleVisibility: .visible) {
            Button("确认拍摄") { Task { await model.captureOneToCard() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("该功能尚未在 iPhone 与 Z 30 真机验证，将先停止取景。相机接受快门命令不代表已核实落卡；如连接中断，请先检查机身，避免重复拍摄。")
        }
        .onDisappear {
            if landscapeRequested { requestOrientation(.portrait, reportErrors: false) }
            model.cancelPendingCapture()
            Task { await model.stopLiveView() }
        }
    }
}
