import Foundation

/// Known Z30/PTP properties; interpretations must still be checked on the device.
public enum ExposureProperty: UInt16, CaseIterable, Sendable {
    case iso = 0x500F
    case fNumber = 0x5007
    case program = 0x500E
    case compensation = 0x5010
    case whiteBalance = 0x5005
    case nikonShutter = 0xD100

    public var title: String {
        switch self {
        case .iso: return "ISO"
        case .fNumber: return "光圈"
        case .program: return "曝光程序"
        case .compensation: return "曝光补偿"
        case .whiteBalance: return "白平衡"
        case .nikonShutter: return "快门"
        }
    }

    /// Strict length checks avoid displaying invented values for unknown encodings.
    public func displayValue(_ data: Data) -> String? {
        if self == .nikonShutter {
            guard data.count == 4 else { return nil }
            let packed = data.readLE(UInt32.self, at: 0)
            let numerator = packed >> 16, denominator = packed & 0xffff
            guard numerator > 0, denominator > 0 else { return nil }
            return "\(numerator)/\(denominator) 秒"
        }
        guard data.count == 2 else { return nil }
        let value = data.readLE(UInt16.self, at: 0)
        switch self {
        case .iso: return value == 0xffff ? "特殊值 (0xFFFF)" : String(value)
        case .fNumber: return value == 0 ? nil : String(format: "f/%.2f", Double(value) / 100)
        case .compensation:
            return String(format: "%+.3f EV", Double(Int16(bitPattern: value)) / 1000)
        case .whiteBalance, .program: return String(format: "0x%04X", value)
        case .nikonShutter: return nil
        }
    }
}
