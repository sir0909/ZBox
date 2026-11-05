import Foundation

/// A restricted, typed PTP DevicePropDesc. Unknown types/forms are never writable.
public struct ExposureDescriptor: Equatable, Sendable {
    public let property: ExposureProperty
    public let writable: Bool
    public let current: Data
    public let choices: [Data]

    public static func parse(_ data: Data, property: ExposureProperty) throws -> ExposureDescriptor {
        func fail() -> PtpIpSessionError { .malformed(stage: "曝光描述符", detail: "类型、范围或长度未识别，禁止写入") }
        guard data.count >= 5, data.readLE(UInt16.self, at: 0) == property.rawValue else { throw fail() }
        let expectedType: UInt16 = property == .compensation ? 0x0003 : (property == .nikonShutter ? 0x0006 : 0x0004)
        guard data.readLE(UInt16.self, at: 2) == expectedType, data[data.startIndex + 4] <= 1 else { throw fail() }
        let width = expectedType == 0x0006 ? 4 : 2
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
                return expectedType == 0x0003 ? Int64(Int16(bitPattern: raw)) : Int64(raw)
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
        return ExposureDescriptor(property: property, writable: data[data.startIndex + 4] == 1,
                                  current: current, choices: choices)
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
