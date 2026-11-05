import Foundation

/// Read-only view of the 614-byte format documented by the Android implementation.
/// No ability to write, delete, apply or synthesize camera settings is provided.
public struct PictureControl614: Equatable, Sendable {
    public static let size = 614
    public let name: String
    public let curve: [UInt16]
    public let controlPoints: [Point]

    public struct Point: Equatable, Sendable {
        public let x: UInt8
        public let y: UInt8
    }

    public var curveIsNondecreasing: Bool {
        zip(curve, curve.dropFirst()).allSatisfy { $0.0 <= $0.1 }
    }

    public static func parse(_ payload: Data) throws -> Self {
        let b = [UInt8](payload)
        guard b.count == size else {
            throw PtpIpSessionError.malformed(stage: "PictureControl614", detail: "长度不是 614 字节")
        }
        guard b[0] == 2 && b[1] == 0 else {
            throw PtpIpSessionError.malformed(stage: "PictureControl614", detail: "不支持的版本")
        }
        let bytes = b[2..<22].prefix { $0 != 0 }.map { (0x20...0x7e).contains($0) ? $0 : UInt8(0x5f) }
        let rawName = String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
        var curve = [UInt16]()
        curve.reserveCapacity(256)
        for i in 0..<256 {
            let offset = 0x66 + i * 2
            let value = UInt16(b[offset]) << 8 | UInt16(b[offset + 1])
            guard value <= 32767 else {
                throw PtpIpSessionError.malformed(stage: "PictureControl614", detail: "LUT 值超过 15 位")
            }
            curve.append(value)
        }
        let points = (0..<4).compactMap { index -> Point? in
            let offset = 0x2f + index * 2
            let x = b[offset], y = b[offset + 1]
            return x == 0 && y == 0 ? nil : Point(x: x, y: y)
        }
        return Self(name: rawName.isEmpty ? "CUSTOM" : rawName, curve: curve, controlPoints: points)
    }
}
