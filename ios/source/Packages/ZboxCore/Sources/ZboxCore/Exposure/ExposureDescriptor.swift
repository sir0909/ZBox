import Foundation

/// A restricted, typed PTP DevicePropDesc. Unknown types/forms are never writable.
public struct ExposureDescriptor: Equatable, Sendable {
    public let property: ExposureProperty
    public let type: UInt16
    public let writable: Bool
    public let current: Data
    public let choices: [Data]

    public static func parse(_ data: Data, property: ExposureProperty) throws -> ExposureDescriptor {
        func fail() -> PtpIpSessionError { .malformed(stage: "曝光描述符", detail: "类型、范围或长度未识别，禁止写入") }
        guard data.count >= 5, data.readLE(UInt16.self, at: 0) == property.rawValue else { throw fail() }
        let type = data.readLE(UInt16.self, at: 2)
        let expectedType: UInt16 = property == .compensation ? 0x0003 : (property == .nikonShutter ? 0x0006 : 0x0004)
        guard data[data.startIndex + 4] <= 1 else { throw fail() }
        if property.isMenuScalar, type == 0xffff {
            var offset = 5
            func readString() throws -> Data {
                guard offset < data.count else { throw fail() }
                let count = Int(data[data.startIndex + offset])
                guard count <= 128, offset + 1 + count * 2 <= data.count else { throw fail() }
                let value = data.slice(at: offset, count: 1 + count * 2)
                offset += 1 + count * 2
                guard Self.ptpString(value) != nil else { throw fail() }
                return value
            }
            _ = try readString() // factory default
            let current = try readString()
            guard offset + 3 <= data.count, data[data.startIndex + offset] == 2 else { throw fail() }
            let count = Int(data.readLE(UInt16.self, at: offset + 1)); offset += 3
            guard (1...128).contains(count) else { throw fail() }
            var choices: [Data] = []
            for _ in 0..<count {
                let value = try readString()
                if !choices.contains(value) { choices.append(value) }
            }
            guard offset == data.count, !choices.isEmpty else { throw fail() }
            return ExposureDescriptor(property: property, type: type, writable: data[data.startIndex + 4] == 1,
                                      current: current, choices: choices)
        }
        if property.isMenuScalar {
            guard (1...6).contains(type) else { throw fail() }
        } else {
            guard type == expectedType else { throw fail() }
        }
        let width = type <= 2 ? 1 : (type <= 4 ? 2 : 4)
        let formAt = 5 + width * 2
        guard data.count > formAt else { throw fail() }
        let current = data.slice(at: 5 + width, count: width)
        let form = data[data.startIndex + formAt]
        var choices: [Data] = []
        if form == 2 {
            guard data.count >= formAt + 3 else { throw fail() }
            let count = Int(data.readLE(UInt16.self, at: formAt + 1))
            guard (1...256).contains(count), data.count >= formAt + 3 + count * width else { throw fail() }
            for i in 0..<count {
                let value = data.slice(at: formAt + 3 + i * width, count: width)
                if !choices.contains(value) { choices.append(value) }
            }
        } else if form == 1 && width == 2 {
            guard data.count >= formAt + 1 + width * 3 else { throw fail() }
            func number(_ pos: Int) -> Int64 {
                let raw = data.readLE(UInt16.self, at: pos)
                return type == 0x0003 ? Int64(Int16(bitPattern: raw)) : Int64(raw)
            }
            let low = number(formAt + 1), high = number(formAt + 3), step = number(formAt + 5)
            guard step > 0, high >= low, (high - low) / step < 256 else { throw fail() }
            var value = low
            while value <= high {
                var encoded = Data()
                encoded.appendLE(UInt16(truncatingIfNeeded: value))
                choices.append(encoded)
                value += step
            }
        } else {
            // A free-form descriptor or unknown format is not permission to guess.
            throw fail()
        }
        guard !choices.isEmpty else { throw fail() }
        return ExposureDescriptor(property: property, type: type, writable: data[data.startIndex + 4] == 1,
                                  current: current, choices: choices)
    }
    /// Strict PTP UTF-16LE string decoding; unknown or malformed text is not writable.
    public static func ptpString(_ data: Data) -> String? {
        guard let first = data.first else { return nil }
        let count = Int(first)
        guard count <= 128, data.count == 1 + count * 2 else { return nil }
        if count == 0 { return "" }
        var units: [UInt16] = []
        for index in 0..<count { units.append(data.readLE(UInt16.self, at: 1 + index * 2)) }
        guard units.last == 0, !units.dropLast().contains(0) else { return nil }
        units.removeLast()
        let string = String(decoding: units, as: UTF16.self)
        guard Array(string.utf16) == units else { return nil }
        return string
    }

}

public enum ExposureWriteResult: Equatable, Sendable {
    case alreadyCurrent
    case verified
}

public enum ExposureWriteError: Error, Equatable, Sendable {
    case notWritableOrValueNotAllowed
    case staleCurrentValue
    case cancelledBeforeWrite
    /// Camera accepted a write but the readback did not match; do not report success.
    case readbackMismatch
}
