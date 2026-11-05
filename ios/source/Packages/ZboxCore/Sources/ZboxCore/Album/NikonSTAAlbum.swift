import Foundation

/// Nikon STA 0x9434 is a read-only metadata list, not a trustworthy file-size source.
public struct NikonSTAAlbumItem: Identifiable, Equatable, Sendable {
    public enum Kind: String, Sendable { case raw = "NEF", jpeg = "JPG", videoMOV = "MOV", videoMP4 = "MP4" }
    public let storageID: UInt32
    public let handle: UInt32
    public let kind: Kind
    /// Unvalidated local camera timestamp; nil means the camera did not supply a valid date.
    public let capturedAt: String?
    public var id: String { String(format: "%08X-%08X", storageID, handle) }
    /// Synthetic filename; the PTP handle is not a camera file number.
    public var placeholderName: String { String(format: "ZBox-%08X.%@", handle, kind.rawValue) }

    public static func kind(for handle: UInt32) -> Kind? {
        switch handle >> 24 {
        case 0x09, 0x0A: return .raw
        case 0x29, 0x2A: return .jpeg
        case 0x61, 0x62: return .videoMP4
        case 0x59, 0x5A, 0x65, 0x75: return .videoMOV
        default: return nil
        }
    }
}

public enum NikonSTAAlbum {
    /// Protect against nonsensical record counts and allocations. Reject truncation, do not
    /// silently report a partial list as a complete album. Unknown record tails are ignored.
    public static let maxEntries = 100_000
    public static func parseStorageIDs(_ data: Data) throws -> [UInt32] {
        guard data.count >= 4 else { throw malformed("存储列表过短") }
        let count = Int(data.readLE(UInt32.self, at: 0))
        guard count <= 64, data.count >= 4 + count * 4 else { throw malformed("存储列表长度异常") }
        return (0..<count).map { data.readLE(UInt32.self, at: 4 + $0 * 4) }
    }

    public static func parseMetadata(_ data: Data, storageID: UInt32) throws -> [NikonSTAAlbumItem] {
        guard data.count >= 8 else { throw malformed("元数据过短") }
        let count = Int(data.readLE(UInt32.self, at: 4))
        guard count <= maxEntries, data.count >= 8 + count * 16 else { throw malformed("元数据数量或长度异常") }
        var items: [NikonSTAAlbumItem] = []
        for index in 0..<count {
            let p = 8 + index * 16
            let handle = data.readLE(UInt32.self, at: p)
            guard let kind = NikonSTAAlbumItem.kind(for: handle) else { continue }
            // bytes p+4...p+7 are *not* trusted as actual file size on Z30.
            let second = Int(data[data.startIndex + p + 9])
            let minute = Int(data[data.startIndex + p + 10])
            let hour = Int(data[data.startIndex + p + 11])
            let day = Int(data[data.startIndex + p + 12])
            let month = Int(data[data.startIndex + p + 13])
            let year = Int(data.readLE(UInt16.self, at: p + 14))
            var captured: String?
            if (1970...2200).contains(year), (1...12).contains(month), (1...31).contains(day),
               hour < 24, minute < 60, second < 60 {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(secondsFromGMT: 0)!
                var c = DateComponents(); c.year = year; c.month = month; c.day = day
                c.hour = hour; c.minute = minute; c.second = second
                if let date = calendar.date(from: c), calendar.component(.day, from: date) == day {
                    captured = String(format: "%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
                }
            }
            items.append(NikonSTAAlbumItem(storageID: storageID, handle: handle, kind: kind, capturedAt: captured))
        }
        return items
    }
    private static func malformed(_ detail: String) -> PtpIpSessionError {
        .malformed(stage: "STA 相册", detail: detail)
    }
}
