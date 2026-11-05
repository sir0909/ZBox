import Foundation

/// Bounded analysis of an RGBA preview, not the sensor RAW or a focus-confirmation signal.
public enum PreviewAnalysis {
    public struct Result: Sendable {
        public let histogram: [Int]
        public let overlayRGBA: [UInt8]
        public let width: Int, height: Int
        public let highlightCount: Int
    }
    public static func analyze(rgba: [UInt8], width: Int, height: Int, zebra: Bool, peaking: Bool) -> Result? {
        guard width > 0, height > 0, width <= 320, height <= 320,
              rgba.count == width * height * 4 else { return nil }
        let count = width * height
        var luma = [Int](repeating: 0, count: count)
        var histogram = [Int](repeating: 0, count: 256)
        var overlay = [UInt8](repeating: 0, count: rgba.count)
        var highlights = 0
        for i in 0..<count {
            let n = i * 4
            let value = (77 * Int(rgba[n]) + 150 * Int(rgba[n + 1]) + 29 * Int(rgba[n + 2])) >> 8
            luma[i] = value; histogram[value] += 1
            if value >= 245 { highlights += 1 }
        }
        for y in 0..<height {
            for x in 0..<width {
                let i = y * width + x, n = i * 4
                if zebra && luma[i] >= 245 && (x + y) % 8 < 4 {
                    overlay[n] = 190; overlay[n + 1] = 190; overlay[n + 2] = 190; overlay[n + 3] = 190
                } else if peaking && x > 0 && y > 0 &&
                    (abs(luma[i] - luma[i - 1]) + abs(luma[i] - luma[i - width])) >= 70 {
                    overlay[n] = 220; overlay[n + 3] = 220
                }
            }
        }
        return Result(histogram: histogram, overlayRGBA: overlay, width: width, height: height, highlightCount: highlights)
    }
}
