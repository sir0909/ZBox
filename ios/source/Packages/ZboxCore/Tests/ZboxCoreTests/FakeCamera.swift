import Foundation
@testable import ZboxCore

/// 脚本化的字节通道:每次 send 后由 responder 生成应答,放入收件箱。
actor ScriptedChannel: PtpIpByteChannel {
    typealias Responder = @Sendable (Data) -> [Data]

    private let responder: Responder
    private var inbox: [Data] = []
    private(set) var sent: [Data] = []

    init(_ responder: @escaping Responder) {
        self.responder = responder
    }

    func send(_ data: Data) async throws {
        sent.append(data)
        inbox.append(contentsOf: responder(data))
    }

    func receive() async throws -> Data {
        inbox.isEmpty ? Data() : inbox.removeFirst()
    }

    func close() async {}
}

actor SettleRecorder {
    private(set) var values: [Int64] = []
    func add(_ value: Int64) { values.append(value) }
}

actor TestClock {
    private var now: Int64
    init(now: Int64) { self.now = now }
    func set(_ value: Int64) { now = value }
    func current() -> Int64 { now }
}

actor NameLog {
    private(set) var names: [String] = []
    func add(_ name: String) { names.append(name) }
}

// MARK: - Byte helpers

func le<T: FixedWidthInteger>(_ value: T) -> Data {
    var data = Data()
    data.appendLE(value)
    return data
}

func frame(_ type: PtpIpPacketType, _ payload: Data = Data()) -> Data {
    PtpIpCodec.frame(type, payload: payload)
}

func opResponse(_ code: UInt16, _ transactionId: UInt32) -> Data {
    frame(.operationResponse, le(code) + le(transactionId))
}

func dataIn(_ transactionId: UInt32, _ bytes: Data) -> [Data] {
    [
        frame(.startData, le(transactionId) + le(UInt64(bytes.count))),
        frame(.endData, le(transactionId) + bytes),
    ]
}

func ptpString(_ text: String) -> Data {
    if text.isEmpty { return Data([0]) }
    let units = Array(text.utf16) + [0]
    var data = Data([UInt8(units.count)])
    for unit in units { data.appendLE(unit) }
    return data
}

/// 构造 DeviceInfo 数据集(与安卓解析的字段顺序一致)。
func deviceInfoDataset(
    manufacturer: String = "Nikon Corporation",
    model: String = "Z 30",
    operations: [UInt16] = [0x1001, 0x1002, 0x952B, 0x935A]
) -> Data {
    var data = le(UInt16(100)) + le(UInt32(0x0A)) + le(UInt16(100)) + ptpString("") + le(UInt16(0))
    data += le(UInt32(operations.count))
    for op in operations { data += le(op) }
    for _ in 0..<4 { data += le(UInt32(0)) }   // events, device props, capture formats, image formats
    data += ptpString(manufacturer) + ptpString(model) + ptpString("1.20") + ptpString("1234567")
    return data
}

/// 解析发送出去的操作请求:(OpCode, 参数列表)。
func operationOf(_ bytes: Data) -> (opCode: UInt16, params: [UInt32]) {
    let opCode = bytes.readLE(UInt16.self, at: 12)
    var params: [UInt32] = []
    var offset = 18
    while offset + 4 <= bytes.count {
        params.append(bytes.readLE(UInt32.self, at: offset))
        offset += 4
    }
    return (opCode, params)
}

// MARK: - Fake camera scripts

enum FakeCamera {
    static let guid = Data(repeating: 0xAB, count: 16)

    /// 命令通道:握手、OpenSession、GetDeviceInfo、0x952B、0x935A。
    static func command(
        cameraName: String = "Nikon Z 30",
        initFailReason: UInt32? = nil,
        openSessionCode: UInt16 = PtpResponseCode.ok,
        transactionOffset: UInt32 = 0,
        deviceInfo: Data = deviceInfoDataset(),
        pairingData: Data = Data([4, 0, 0, 0, 0x31, 0x32, 0x33, 0x34]),
        confirmCode: UInt16 = PtpResponseCode.ok
    ) -> ScriptedChannel.Responder {
        { bytes in
            let type = bytes.readLE(UInt32.self, at: 4)
            switch type {
            case PtpIpPacketType.initCommandRequest.rawValue:
                if let reason = initFailReason {
                    return [frame(.initFail, le(reason))]
                }
                let payload = le(UInt32(7)) + guid + utf16z(cameraName)
                return [frame(.initCommandAck, payload)]
            case PtpIpPacketType.operationRequest.rawValue:
                let opCode = bytes.readLE(UInt16.self, at: 12)
                let tx = bytes.readLE(UInt32.self, at: 14)
                switch opCode {
                case PtpOpCode.openSession:
                    return [opResponse(openSessionCode, tx &+ transactionOffset)]
                case PtpOpCode.getDeviceInfo:
                    return dataIn(tx, deviceInfo) + [opResponse(PtpResponseCode.ok, tx)]
                case PtpOpCode.nikonGetPairingCode:
                    return dataIn(tx, pairingData) + [opResponse(PtpResponseCode.ok, tx)]
                case PtpOpCode.nikonConfirmPairing:
                    return [opResponse(confirmCode, tx)]
                default:
                    return [opResponse(PtpResponseCode.operationNotSupported, tx)]
                }
            default:
                return []
            }
        }
    }

    /// 事件通道:只回应 InitEventRequest。
    static let event: ScriptedChannel.Responder = { bytes in
        bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.initEventRequest.rawValue
            ? [frame(.initEventAck)]
            : []
    }

    static func utf16z(_ text: String) -> Data {
        var data = Data()
        for unit in text.utf16 { data.appendLE(unit) }
        data.appendLE(UInt16(0))
        return data
    }
}

func testConfig(name: String = "Zbox", sta: Bool = false) -> PtpIpSessionConfig {
    PtpIpSessionConfig(initiatorGuid: FakeCamera.guid, initiatorName: name, staMode: sta)
}
