import SwiftUI

struct LogConsoleView: View {
    @ObservedObject var log: DiagnosticLog

    var body: some View {
        List {
            ForEach(Array(log.lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .navigationTitle("日志")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                ShareLink(item: log.exportText) {
                    Label("导出", systemImage: "square.and.arrow.up")
                }
                Button("清空", role: .destructive) { log.clear() }
            }
        }
    }
}
