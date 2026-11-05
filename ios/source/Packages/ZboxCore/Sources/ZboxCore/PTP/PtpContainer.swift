import Foundation

/// PTP 容器:12 字节小端头 + 载荷(对应安卓 `com.zbox.ai.ptp.PtpContainer`)。
public struct PtpContainer: Sendable, Equatable {
    public static let headerSize = 12

    public let length: UInt32
    public let typeRaw: UInt16
    public let code: UInt16
    public let transactionId: UInt32
    public let payload: Data

    public var type: PtpContainerType? { PtpContainerType(rawValue: typeRaw) }

    /// 构造命令容器,参数按 u32 小端写入载荷。
    public static func command(code: UInt16, transactionId: UInt32, params: [UInt32] = []) -> Data {
        var body = Data()
        for value in params { body.appendLE(value) }
        return encode(type: .command, code: code, transactionId: transactionId, body: body)
    }

    /// 构造数据容器。
    public static func data(code: UInt16, transactionId: UInt32, payload: Data) -> Data {
        encode(type: .data, code: code, transactionId: transactionId, body: payload)
    }

    public static func encode(type: PtpContainerType, code: UInt16, transactionId: UInt32, body: Data) -> Data {
        var out = Data(capacity: headerSize + body.count)
        out.appendLE(UInt32(headerSize + body.count))
        out.appendLE(type.rawValue)
        out.appendLE(code)
        out.appendLE(transactionId)
        out.append(body)
        return out
    }

    /// 解析 12 字节头。长度非法或数据不足时抛出 `PtpError`。返回的容器 payload 为空。
    public static func parseHeader(_ bytes: Data) throws -> PtpContainer {
        guard bytes.count >= headerSize else {
            throw PtpError.truncated(expected: headerSize, actual: bytes.count)
        }
        let length = bytes.readLE(UInt32.self, at: 0)
        guard length >= UInt32(headerSize), length <= PtpLimits.maxContainerLength else {
            throw PtpError.invalidLength(length)
        }
        return PtpContainer(
            length: length,
            typeRaw: bytes.readLE(UInt16.self, at: 4),
            code: bytes.readLE(UInt16.self, at: 6),
            transactionId: bytes.readLE(UInt32.self, at: 8),
            payload: Data()
        )
    }

    /// 将载荷按 u32 小端解析为参数数组,不足 4 字节的尾部被忽略。
    public func params() -> [UInt32] {
        (0..<(payload.count / 4)).map { payload.readLE(UInt32.self, at: $0 * 4) }
    }
}
