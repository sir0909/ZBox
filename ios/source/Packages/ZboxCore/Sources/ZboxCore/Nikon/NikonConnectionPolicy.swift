import Foundation

/// Nikon PTP/IP 的连接时序与配对规则。与安卓 `NikonConnectionPolicy` 保持一致。
public enum NikonConnectionPolicy {
    /// 主机注册准备(0x952B)后,确认(0x935A)前的最短等待。
    public static let hostSettleMs: Int64 = 8_500
    /// 每条 TCP 连接建立后、发送 InitRequest 前的等待。
    public static let connectSettleMs: Int64 = 350

    /// PTP 会话号。STA(热点客户端)模式取 [2, 0x7fffffff] 的随机值;AP 模式固定为 1。
    public static func sessionId(sta: Bool, random: UInt32) -> UInt32 {
        guard sta else { return 1 }
        return 2 + (random & 0x7fff_ffff) % 0x7fff_fffe
    }

    /// 距离上次主机注册准备还需等待的毫秒数(不小于 0)。
    public static func remainingSettleMs(elapsedMs: Int64) -> Int64 {
        max(0, hostSettleMs - max(0, elapsedMs))
    }

    /// 解析 0x952B 的数据负载。
    /// - 空负载:主机注册已成功,无 PIN(返回空字符串)。
    /// - 否则:小端 u32 位数(4...8)+ 该数量的字节 + 尾部补零。字节为 0...9 的数值或 ASCII 数字。
    /// 其他格式视为未确认,抛出 `pairingCodeUnrecognized`,不得自动重试。
    public static func pairingCode(_ data: Data) throws -> String {
        if data.isEmpty { return "" }
        guard data.count >= 4 else { throw PtpIpSessionError.pairingCodeUnrecognized }
        let count = Int(data.readLE(UInt32.self, at: 0))
        guard (4...8).contains(count), data.count >= 4 + count else {
            throw PtpIpSessionError.pairingCodeUnrecognized
        }
        let trailing = data.slice(at: 4 + count, count: data.count - 4 - count)
        guard trailing.allSatisfy({ $0 == 0 }) else { throw PtpIpSessionError.pairingCodeUnrecognized }
        var digits = ""
        for index in 0..<count {
            let byte = data[data.startIndex + 4 + index]
            switch byte {
            case 0...9: digits.append(Character(UnicodeScalar(UInt8(48) + byte)))
            case 48...57: digits.append(Character(UnicodeScalar(byte)))
            default: throw PtpIpSessionError.pairingCodeUnrecognized
            }
        }
        return digits
    }

    /// 规范化相机型号:罗马数字统一为 I/II/III/IV,去掉尾部的序列号后缀(如 `_012345`)。
    public static func normalizedModel(_ raw: String) -> String {
        var model = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for (from, to) in [("Ⅰ", "I"), ("Ⅱ", "II"), ("Ⅲ", "III"), ("Ⅳ", "IV")] {
            model = model.replacingOccurrences(of: from, with: to)
        }
        model = model.replacingOccurrences(of: #"_\d{6,}$"#, with: "", options: .regularExpression)
        model = model.replacingOccurrences(of: #"_(?=[IVX]+$)"#, with: "", options: .regularExpression)
        return String(model.prefix(80))
    }
}
