import Foundation

/// Local-only view of the already bounded STA metadata; never requests camera data.
public enum AlbumMediaFilter: String, CaseIterable, Sendable {
    case all, jpeg, raw, video

    public var title: String {
        switch self {
        case .all: return "全部"
        case .jpeg: return "JPEG"
        case .raw: return "RAW"
        case .video: return "视频"
        }
    }

    public func includes(_ kind: NikonSTAAlbumItem.Kind) -> Bool {
        switch self {
        case .all: return true
        case .jpeg: return kind == .jpeg
        case .raw: return kind == .raw
        case .video: return kind == .videoMOV || kind == .videoMP4
        }
    }
}

public enum AlbumSortOrder: String, CaseIterable, Sendable {
    case newest, oldest
    public var title: String { self == .newest ? "最新优先" : "最早优先" }
}

public enum AlbumBrowse {
    /// Unknown camera timestamps sort last in both directions. Ties preserve source order.
    public static func apply(_ items: [NikonSTAAlbumItem], filter: AlbumMediaFilter,
                             order: AlbumSortOrder) -> [NikonSTAAlbumItem] {
        items.enumerated().filter { filter.includes($0.element.kind) }.sorted { a, b in
            switch (a.element.capturedAt, b.element.capturedAt) {
            case (nil, nil): return a.offset < b.offset
            case (nil, _): return false
            case (_, nil): return true
            case let (left?, right?):
                if left == right { return a.offset < b.offset }
                return order == .newest ? left > right : left < right
            }
        }.map { $0.element }
    }
}
