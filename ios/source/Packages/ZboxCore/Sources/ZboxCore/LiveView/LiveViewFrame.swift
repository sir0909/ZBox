import Foundation

/// A complete JPEG found within a Nikon live-view payload. This does not decode pixels.
public enum LiveViewFrame {
    public static let maxJPEGBytes = 8 * 1024 * 1024

    /// Nikon payloads can contain a device-specific prefix and trailing data. Return the
    /// first SOI through the last EOI, as in the Android driver; reject incomplete data.
    public static func jpeg(from payload: Data) throws -> Data {
        let bytes = [UInt8](payload)
        guard bytes.count >= 4 else {
            throw PtpIpSessionError.malformed(stage: "LiveView", detail: "JPEG 数据过短")
        }
        var start: Int?
        for i in 0..<(bytes.count - 1) where bytes[i] == 0xff && bytes[i + 1] == 0xd8 {
            start = i
            break
        }
        guard let start else {
            throw PtpIpSessionError.malformed(stage: "LiveView", detail: "缺少 JPEG 起始标记")
        }
        var end: Int?
        for i in stride(from: bytes.count - 2, through: start + 2, by: -1) {
            if bytes[i] == 0xff && bytes[i + 1] == 0xd9 { end = i + 2; break }
        }
        guard let end, end - start <= maxJPEGBytes else {
            throw PtpIpSessionError.malformed(stage: "LiveView", detail: "JPEG 不完整或超过大小上限")
        }
        return Data(bytes[start..<end])
    }
}
