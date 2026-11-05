import Foundation

/// Operation Response 解析结果。
public struct PtpOperationResponse: Equatable, Sendable {
    public let code: UInt16
    public let transactionId: UInt32
    public let params: [UInt32]

    public var isOK: Bool { code == PtpResponseCode.ok }
}

/// 一次 PTP 操作的完整结果(数据阶段已收齐)。
public struct PtpOperationResult: Equatable, Sendable {
    public let response: PtpOperationResponse
    public let data: Data
}

/// Operation Response / 数据阶段包的解析。字段偏移以包负载为准(不含 8 字节包头)。
public enum PtpIpOperationParser {
    /// 数据阶段标志:无数据与数据读入(IN)都用 1;2 表示数据写出(OUT)。
    public static let dataPhaseNoneOrIn: UInt32 = 1

    /// Operation Response:ResponseCode(u16) + TransactionID(u32) + 参数(u32 × n)。
    public static func response(_ packet: PtpIpPacket) throws -> PtpOperationResponse {
        guard packet.payload.count >= 6 else {
            throw PtpIpSessionError.malformed(stage: "命令", detail: "响应包长度 \(packet.payload.count)")
        }
        let code = packet.payload.readLE(UInt16.self, at: 0)
        let transactionId = packet.payload.readLE(UInt32.self, at: 2)
        var params: [UInt32] = []
        var offset = 6
        while offset + 4 <= packet.payload.count {
            params.append(packet.payload.readLE(UInt32.self, at: offset))
            offset += 4
        }
        return PtpOperationResponse(code: code, transactionId: transactionId, params: params)
    }

    /// StartData(txid + 总长 u64) / Data 与 EndData(txid + 字节)。
    /// 返回事务号与本包携带的字节(StartData 不携带字节)。
    public static func dataChunk(_ packet: PtpIpPacket) throws -> (transactionId: UInt32, bytes: Data) {
        let isStart = packet.type == PtpIpPacketType.startData.rawValue
        let minimum = isStart ? 12 : 4
        guard packet.payload.count >= minimum else {
            throw PtpIpSessionError.malformed(stage: "数据", detail: "数据包长度 \(packet.payload.count)")
        }
        let transactionId = packet.payload.readLE(UInt32.self, at: 0)
        if isStart {
            return (transactionId, Data())
        }
        return (transactionId, packet.payload.slice(at: 4, count: packet.payload.count - 4))
    }
}
