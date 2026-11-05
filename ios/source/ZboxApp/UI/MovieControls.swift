import SwiftUI

struct MovieControls: View {
    @ObservedObject var model: CameraConnectionModel
    @State private var confirmStart = false
    @State private var confirmStop = false
    @State private var confirmChecked = false
    var compact = false
    var inline = false

    @ViewBuilder private var buttons: some View {
        if model.movieMayBeActive {
            Button("停止录像", systemImage: "stop.circle") { confirmStop = true }
                .disabled(!model.canStopMovie)
            Button("已检查机身") { confirmChecked = true }.disabled(model.movieBusy)
        } else {
            Button(model.movieBusy ? "准备录像…" : "录像", systemImage: "record.circle") { confirmStart = true }
                .disabled(!model.canStartMovie)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            if compact {
                VStack(alignment: .leading, spacing: 4) { buttons }
                    .font(.caption2).buttonStyle(.bordered).tint(model.movieMayBeActive ? .red : .yellow)
            } else {
                HStack { buttons }
                    .font(.caption).buttonStyle(.bordered).tint(model.movieMayBeActive ? .red : .yellow)
            }
            if !inline {
                Text(model.movieMessage).font(.caption2).lineLimit(compact ? 4 : nil).foregroundStyle(model.movieMayBeActive ? Color.orange : Color.gray)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .confirmationDialog("开始录像到机身存储卡？", isPresented: $confirmStart, titleVisibility: .visible) {
            Button("确认开始") { Task { await model.startMovie() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("实验功能：先在机身手动切换录像模式并启动取景。开始命令不重试；断线或后台不自动停止，必须检查机身。")
        }
        .confirmationDialog("向原机身发送一次停止录像命令？", isPresented: $confirmStop, titleVisibility: .visible) {
            Button("发送停止命令") { Task { await model.stopMovie() } }
            Button("取消", role: .cancel) {}
        } message: { Text("结果不确定时请在机身检查；应用不会自动重试。") }
        .confirmationDialog("是否已在原机身确认录像停止？", isPresented: $confirmChecked, titleVisibility: .visible) {
            Button("我已检查机身，清除提示", role: .destructive) { model.acknowledgeMovieStopped() }
            Button("取消", role: .cancel) {}
        } message: { Text("只清除本机安全提示，不会向相机发送停止命令；请先亲自确认机身已停止录像。") }
    }
}
