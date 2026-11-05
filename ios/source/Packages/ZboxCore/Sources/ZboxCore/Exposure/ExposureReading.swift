import Foundation

/// Known Z30/PTP properties; interpretations must still be checked on the device.
public enum ExposureProperty: UInt16, CaseIterable, Sendable {
    case iso = 0x500F
    case fNumber = 0x5007
    case program = 0x500E
    case compensation = 0x5010
    case whiteBalance = 0x5005
    case nikonShutter = 0xD100
    // i menu scalar properties; actual data type must be confirmed from the descriptor.
    case picture = 0xD200
    case quality = 0x5004
    case flash = 0x500C
    case release = 0x5013
    case afArea = 0xD05D
    case imageSize = 0x5003
    case metering = 0x500B
    case activeDLighting = 0xD14E
    case stabilization = 0xD138
    case focus = 0xD061

    public static let exposureCases: [ExposureProperty] = [.iso, .fNumber, .program, .compensation, .whiteBalance, .nikonShutter]
    public static let menuCases: [ExposureProperty] = [.picture, .quality, .flash, .release, .afArea, .whiteBalance, .imageSize, .metering, .activeDLighting, .stabilization, .focus]
    public var isMenuScalar: Bool { !Self.exposureCases.contains(self) }

    public var title: String {
        switch self {
        case .iso: return "ISO"
        case .fNumber: return "光圈"
        case .program: return "曝光程序"
        case .compensation: return "曝光补偿"
        case .whiteBalance: return "白平衡"
        case .nikonShutter: return "快门"
        case .picture: return "优化校准项"
        case .quality: return "图像品质"
        case .flash: return "闪光模式"
        case .release: return "释放模式"
        case .afArea: return "AF 区域"
        case .imageSize: return "图像尺寸"
        case .metering: return "测光模式"
        case .activeDLighting: return "动态 D-Lighting"
        case .stabilization: return "减震"
        case .focus: return "对焦模式"
        }
    }

    /// Strict length checks avoid displaying invented values for unknown encodings.
    public func displayValue(_ data: Data) -> String? {
        if isMenuScalar {
            switch data.count {
            case 1: return String(format: "0x%02X", Int(data[data.startIndex]))
            case 2: return String(format: "0x%04X", data.readLE(UInt16.self, at: 0))
            case 4: return String(format: "0x%08X", data.readLE(UInt32.self, at: 0))
            default: return nil
            }
        }
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
        case .nikonShutter, .picture, .quality, .flash, .release, .afArea, .imageSize, .metering, .activeDLighting, .stabilization, .focus: return nil
        }
    }
}
