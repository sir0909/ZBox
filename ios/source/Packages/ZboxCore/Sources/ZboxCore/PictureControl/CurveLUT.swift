import Foundation

/// Android-compatible shape-preserving cubic Hermite interpolation, evaluated at 0...255.
public enum CurveLUT {
    public struct Point: Codable, Equatable, Sendable {
        public var x: Float
        public var y: Float
        public init(x: Float, y: Float) { self.x = x; self.y = y }
    }
    public enum Error: Swift.Error, Equatable { case invalidPoints }

    public static func make(_ points: [Point]) throws -> [UInt8] {
        guard (2...20).contains(points.count), points.allSatisfy({ $0.x.isFinite && $0.y.isFinite && (0...255).contains($0.x) && (0...255).contains($0.y) }) else { throw Error.invalidPoints }
        let pts = points.sorted { $0.x < $1.x }
        guard zip(pts, pts.dropFirst()).allSatisfy({ $0.1.x - $0.0.x >= 0.5 }) else { throw Error.invalidPoints }
        let n = pts.count
        let xs = pts.map(\.x), ys = pts.map(\.y)
        let h = (0..<(n - 1)).map { xs[$0 + 1] - xs[$0] }
        let delta = (0..<(n - 1)).map { (ys[$0 + 1] - ys[$0]) / h[$0] }
        var m = [Float](repeating: 0, count: n)
        m[0] = delta[0]; m[n - 1] = delta[n - 2]
        if n > 2 {
            for i in 1..<(n - 1) { m[i] = delta[i - 1] * delta[i] <= 0 ? 0 : (delta[i - 1] + delta[i]) / 2 }
        }
        func limit(_ v: Float, _ near: Float, _ next: Float?) -> Float {
            if near == 0 { return 0 }
            let candidate: Float = next != nil && near * next! <= 0 ? 0 : v
            return max(min(0, 3 * near), min(max(0, 3 * near), candidate))
        }
        m[0] = limit(m[0], delta[0], delta.count > 1 ? delta[1] : nil)
        m[n - 1] = limit(m[n - 1], delta[n - 2], n > 2 ? delta[n - 3] : nil)
        for i in 0..<(n - 1) {
            let d = delta[i]
            if abs(d) < 1e-9 { m[i] = 0; m[i + 1] = 0; continue }
            let a = m[i] / d, b = m[i + 1] / d, sum = a * a + b * b
            if sum > 9 {
                let t = Float(3) / sqrt(sum)
                m[i] = t * a * d; m[i + 1] = t * b * d
            }
        }
        return (0...255).map { integer in
            let x = Float(integer)
            if x <= xs[0] { return UInt8(max(0, min(255, Int(ys[0].rounded(.toNearestOrEven))))) }
            if x >= xs[n - 1] { return UInt8(max(0, min(255, Int(ys[n - 1].rounded(.toNearestOrEven))))) }
            var lo = 0, hi = n - 1
            while hi - lo > 1 { let mid = (lo + hi) / 2; if xs[mid] <= x { lo = mid } else { hi = mid } }
            let t = (x - xs[lo]) / h[lo], t2 = t * t, t3 = t2 * t
            let p0 = (2 * t3 - 3 * t2 + 1) * ys[lo]
            let p1 = (t3 - 2 * t2 + t) * h[lo] * m[lo]
            let p2 = (-2 * t3 + 3 * t2) * ys[lo + 1]
            let p3 = (t3 - t2) * h[lo] * m[lo + 1]
            let value = p0 + p1 + p2 + p3
            return UInt8(max(0, min(255, Int(value.rounded(.toNearestOrEven)))))
        }
    }
    public static func sample(_ lut: [UInt8], count: Int = 5) throws -> [Point] {
        guard lut.count == 256, (2...20).contains(count) else { throw Error.invalidPoints }
        return (0..<count).map { i in
            let x = Int((Float(i) * 255 / Float(count - 1)).rounded(.toNearestOrEven))
            return Point(x: Float(x), y: Float(lut[x]))
        }
    }
}
