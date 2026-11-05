import SwiftUI
import UIKit

/// Explicit live-view screen; it does not start camera operations on navigation.
struct LiveViewScreen: View {
    @ObservedObject var model: CameraConnectionModel
    @Environment(\.dismiss) private var dismiss

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

                    HStack(spacing: 18) {
                        Text(model.liveViewMessage)
                            .font(.caption).foregroundStyle(.white.opacity(0.75))
                            .lineLimit(2)
                        Spacer()
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
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onDisappear { Task { await model.stopLiveView() } }
    }
}
