import Foundation

/// PTP/IP 包类型(第二个 u32 字段,与安卓 `PtpIp.PACKET_*` 一致)。
public enum PtpIpPacketType: UInt32, Sendable {
    case initCommandRequest = 1
    case initCommandAck = 2
    case initEventRequest = 3
    case initEventAck = 4
    case initFail = 5
    case operationRequest = 6
    case operationResponse = 7
    case event = 8
    case startData = 9
    case data = 10
    case cancel = 11
    case endData = 12
    case probeRequest = 13
    case probeResponse = 14
}

/// InitFail 原因码。
public enum PtpIpInitFailReason: UInt32, Sendable {
    case rejected = 1
    case busy = 2
    case unspecified = 3
}

public struct PtpIpPacket: Equatable, Sendable {
    public let type: UInt32
    public let payload: Data

    public init(type: UInt32, payload: Data) {
        self.type = type
        self.payload = payload
    }
}

public struct PtpIpInitAck: Equatable, Sendable {
    public let connectionNumber: UInt32
    public let cameraGuid: Data
    public let cameraName: String
}

/// PTP/IP 包的编解码。TCP 端口 15740 的命令通道与事件通道共用此格式。
public enum PtpIpCodec {
    public static let defaultPort: UInt16 = 15740
    /// 初始化请求中声明的协议版本(0x0001_0000)。
    public static let protocolVersion: UInt32 = 0x0001_0000
    /// PTP/IP 包头:长度 u32 + 类型 u32。
    public static let headerSize = 8
    public static let guidLength = 16

    public static func frame(_ type: PtpIpPacketType, payload: Data = Data()) -> Data {
        var out = Data(capacity: headerSize + payload.count)
        out.appendLE(UInt32(headerSize + payload.count))
        out.appendLE(type.rawValue)
        out.append(payload)
        return out
    }

    /// Init Command Request:16 字节主机 GUID + UTF-16LE 名称(以 NUL 结尾)+ 协议版本。
    public static func initCommandRequest(guid: Data, hostName: String) throws -> Data {
        guard guid.count == guidLength else { throw PtpError.invalidGuidLength(guid.count) }
        var payload = guid
        for unit in hostName.utf16 { payload.appendLE(unit) }
        payload.appendLE(UInt16(0))
        payload.appendLE(protocolVersion)
        return frame(.initCommandRequest, payload: payload)
    }

    /// Operation Request:DataPhase(u32) + OpCode(u16) + TransactionID(u32) + 参数(u32 × n)。
    public static func operationRequest(dataPhase: UInt32, opCode: UInt16, transactionId: UInt32, params: [UInt32] = []) -> Data {
        var payload = Data()
        payload.appendLE(dataPhase)
        payload.appendLE(opCode)
        payload.appendLE(transactionId)
        for value in params { payload.appendLE(value) }
        return frame(.operationRequest, payload: payload)
    }

    /// 解析 8 字节包头,返回包长度与类型。
    public static func parseHeader(_ bytes: Data) throws -> (length: UInt32, type: UInt32) {
        guard bytes.count >= headerSize else {
            throw PtpError.truncated(expected: headerSize, actual: bytes.count)
        }
        let length = bytes.readLE(UInt32.self, at: 0)
        guard length >= UInt32(headerSize), length <= PtpLimits.maxContainerLength else {
            throw PtpError.invalidLength(length)
        }
        return (length, bytes.readLE(UInt32.self, at: 4))
    }

    /// 若为 InitFail 包,返回原因码;未知原因码返回 nil。
    public static func initFailReason(in packet: PtpIpPacket) -> PtpIpInitFailReason? {
        guard packet.type == PtpIpPacketType.initFail.rawValue, packet.payload.count >= 4 else { return nil }
        return PtpIpInitFailReason(rawValue: packet.payload.readLE(UInt32.self, at: 0))
    }

    /// 解析 Init Command Ack:连接号 u32 + 相机 GUID(16) + 相机名称(UTF-16LE,NUL 结尾)。
    public static func parseInitCommandAck(_ packet: PtpIpPacket) throws -> PtpIpInitAck {
        guard packet.type == PtpIpPacketType.initCommandAck.rawValue else {
            throw PtpError.unexpectedPacket(packet.type)
        }
        let minimum = 4 + guidLength
        guard packet.payload.count >= minimum else {
            throw PtpError.truncated(expected: minimum, actual: packet.payload.count)
        }
        let connection = packet.payload.readLE(UInt32.self, at: 0)
        let guid = packet.payload.slice(at: 4, count: guidLength)
        var units: [UInt16] = []
        var offset = minimum
        while offset + 1 < packet.payload.count {
            let unit = packet.payload.readLE(UInt16.self, at: offset)
            if unit == 0 { break }
            units.append(unit)
            offset += 2
        }
        return PtpIpInitAck(
            connectionNumber: connection,
            cameraGuid: guid,
            cameraName: String(decoding: units, as: UTF16.self)
        )
    }
}

/// 把 TCP 字节流切分为完整的 PTP/IP 包(处理粘包与半包)。
public struct PtpIpFrameReader: Sendable {
    private var buffer = Data()

    public init() {}

    public var pendingBytes: Int { buffer.count }

    public mutating func append(_ data: Data) {
        buffer.append(data)
    }

    /// 取出一个完整包;数据不足时返回 nil。包头非法时抛出错误。
    public mutating func nextPacket() throws -> PtpIpPacket? {
        guard buffer.count >= PtpIpCodec.headerSize else { return nil }
        let header = try PtpIpCodec.parseHeader(buffer)
        let total = Int(header.length)
        guard buffer.count >= total else { return nil }
        let payload = buffer.slice(at: PtpIpCodec.headerSize, count: total - PtpIpCodec.headerSize)
        buffer = Data(buffer.dropFirst(total))
        return PtpIpPacket(type: header.type, payload: payload)
    }
}
