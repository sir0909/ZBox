import Foundation

/// Extract a complete embedded JPEG from a bounded file prefix (not a full TIFF parser).
/// It deliberately does not claim that arbitrary marker pairs are valid image content.
public enum EmbeddedJPEG {
    public static let maxPrefixBytes = 256 * 1024
    public static func largestComplete(in prefix: Data) -> Data? {
        guard prefix.count <= maxPrefixBytes, prefix.count >= 4 else { return nil }
        let bytes = [UInt8](prefix)
        var starts: [Int] = []
        var best: Range<Int>?
        var i = 0
        while i + 1 < bytes.count {
            if bytes[i] == 0xff && bytes[i + 1] == 0xd8 {
                if starts.count < 128 { starts.append(i) } else { return nil }
                i += 2; continue
            }
            if bytes[i] == 0xff && bytes[i + 1] == 0xd9, let start = starts.popLast() {
                let range = start..<(i + 2)
                if range.count > 4 && range.count > (best?.count ?? 0) { best = range }
                i += 2; continue
            }
            i += 1
        }
        guard let best else { return nil }
        return Data(bytes[best])
    }
}
