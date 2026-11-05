import Foundation

/// GetDeviceInfo 返回的 DeviceInfo 数据集(只解析 M1 需要的字段)。
public struct PtpDeviceInfo: Equatable, Sendable {
    public let standardVersion: UInt16
    public let manufacturer: String
    public let model: String
    public let deviceVersion: String
    public let serialNumber: String
    public let operations: Set<UInt16>

    public var isNikon: Bool {
        manufacturer.localizedCaseInsensitiveContains("Nikon")
    }

    /// 相机广告了 Nikon 主机注册操作(0x952B 与 0x935A 同时存在才算)。
    public var supportsNikonHostRegistration: Bool {
        operations.contains(PtpOpCode.nikonGetPairingCode)
            && operations.contains(PtpOpCode.nikonConfirmPairing)
    }

    public static func parse(_ bytes: Data) throws -> PtpDeviceInfo {
        var reader = PtpDatasetReader(bytes)
        let standardVersion = try reader.u16()
        _ = try reader.u32()          // VendorExtensionID
        _ = try reader.u16()          // VendorExtensionVersion
        _ = try reader.string()       // VendorExtensionDesc
        _ = try reader.u16()          // FunctionalMode
        let operations = Set(try reader.u16Array())
        _ = try reader.u16Array()     // EventsSupported
        _ = try reader.u16Array()     // DevicePropertiesSupported
        _ = try reader.u16Array()     // CaptureFormats
        _ = try reader.u16Array()     // ImageFormats
        let manufacturer = try reader.string()
        let model = try reader.string()
        let deviceVersion = try reader.string()
        let serial = try reader.string()
        return PtpDeviceInfo(
            standardVersion: standardVersion,
            manufacturer: manufacturer,
            model: model,
            deviceVersion: deviceVersion,
            serialNumber: serial,
            operations: operations
        )
    }
}

/// PTP 数据集的顺序读取器。PTP 字符串:字符数(u8,含 NUL)+ UTF-16LE 字符。
struct PtpDatasetReader {
    private let data: Data
    private var offset = 0

    init(_ data: Data) {
        self.data = data
    }

    mutating func u8() throws -> UInt8 {
        try need(1)
        let value = data[data.startIndex + offset]
        offset += 1
        return value
    }

    mutating func u16() throws -> UInt16 {
        try need(2)
        let value = data.readLE(UInt16.self, at: offset)
        offset += 2
        return value
    }

    mutating func u32() throws -> UInt32 {
        try need(4)
        let value = data.readLE(UInt32.self, at: offset)
        offset += 4
        return value
    }

    mutating func u16Array() throws -> [UInt16] {
        let count = Int(try u32())
        guard count <= 2048 else {
            throw PtpIpSessionError.malformed(stage: "DeviceInfo", detail: "数组过长 \(count)")
        }
        try need(count * 2)
        var values: [UInt16] = []
        values.reserveCapacity(count)
        for _ in 0..<count {
            values.append(try u16())
        }
        return values
    }

    mutating func string() throws -> String {
        let count = Int(try u8())
        if count == 0 { return "" }
        try need(count * 2)
        var units: [UInt16] = []
        units.reserveCapacity(count)
        for _ in 0..<count {
            units.append(try u16())
        }
        if units.last == 0 { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }

    private func need(_ count: Int) throws {
        guard offset + count <= data.count else {
            throw PtpIpSessionError.malformed(stage: "DeviceInfo", detail: "数据不足:需要 \(offset + count)，实际 \(data.count)")
        }
    }
}
