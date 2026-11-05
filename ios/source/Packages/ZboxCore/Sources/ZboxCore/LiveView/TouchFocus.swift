import Foundation

/// Conservative Nikon live-view coordinate decoding, based on the Android ZBox policy.
/// Reject cropped/zoomed/unknown frames, black bars and malformed metadata rather than guessing.
public enum TouchFocus {
    public struct Geometry: Equatable, Sendable {
        public let width: Int, height: Int, afX: Int, afY: Int, afWidth: Int, afHeight: Int
    }
    public static func geometry(header: Data, imageWidth: Int, imageHeight: Int) -> Geometry? {
        guard header.count >= 32, imageWidth > 0, imageHeight > 0 else { return nil }
        let bytes = [UInt8](header)
        func be(_ i: Int) -> Int { Int(bytes[i]) * 256 + Int(bytes[i + 1]) }
        guard be(8) == imageWidth, be(10) == imageHeight else { return nil }
        let w = be(16), h = be(18), fw = be(24), fh = be(26), x = be(28), y = be(30)
        guard (256...32768).contains(w), (256...32768).contains(h), be(12) == w, be(14) == h,
              abs(be(20) * 2 - w) <= 2, abs(be(22) * 2 - h) <= 2,
              abs(Double(w) / Double(h) - Double(imageWidth) / Double(imageHeight)) <= 0.08,
              fw > 0, fh > 0, fw <= w, fh <= h, x < w, y < h else { return nil }
        return Geometry(width: w, height: h, afX: x, afY: y, afWidth: fw, afHeight: fh)
    }
    public static func normalizedPoint(x: Double, y: Double, boundsWidth: Double, boundsHeight: Double,
                                       imageWidth: Double, imageHeight: Double) -> (Double, Double)? {
        guard x.isFinite, y.isFinite, let r = ViewfinderGeometry.aspectFit(contentWidth: imageWidth, contentHeight: imageHeight,
            boundsWidth: boundsWidth, boundsHeight: boundsHeight), x >= r.x, x < r.x + r.width,
              y >= r.y, y < r.y + r.height else { return nil }
        return ((x - r.x) / r.width, (y - r.y) / r.height)
    }
    public static func target(_ geometry: Geometry, x: Double, y: Double) -> (Int, Int)? {
        guard x.isFinite, y.isFinite, (0...1).contains(x), (0...1).contains(y) else { return nil }
        return (Int(x * Double(geometry.width - 1)), Int(y * Double(geometry.height - 1)))
    }
    public static func singleAreaMode(_ descriptor: Data) -> Bool {
        guard descriptor.count >= 12, descriptor.readLE(UInt16.self, at: 0) == 0xd05d,
              descriptor.readLE(UInt16.self, at: 2) == 4, descriptor[descriptor.startIndex + 9] == 2 else { return false }
        let count = Int(descriptor.readLE(UInt16.self, at: 10))
        guard (1...32).contains(count), descriptor.count >= 12 + count * 2,
              descriptor[descriptor.startIndex + 4] <= 1, descriptor.readLE(UInt16.self, at: 7) == 0x8010 else { return false }
        return (0..<count).contains { descriptor.readLE(UInt16.self, at: 12 + $0 * 2) == 0x8010 }
    }
}

public enum TouchFocusError: Error, Equatable, Sendable {
    case notAdvertised, invalidGeometry, invalidMode, cancelled, positionNotVerified
}
