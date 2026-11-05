import Foundation

/// 应用内日志控制台。只记录步骤与错误码,不记录 GUID、序列号、PIN 或照片数据。
@MainActor
final class DiagnosticLog: ObservableObject {
    @Published private(set) var lines: [String] = []
    private let limit = 600
    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    func add(_ message: String) {
        lines.append("\(formatter.string(from: Date()))  \(message)")
        if lines.count > limit {
            lines.removeFirst(lines.count - limit)
        }
    }

    func clear() {
        lines.removeAll()
    }

    var exportText: String {
        lines.joined(separator: "\n") + "\n"
    }
}
