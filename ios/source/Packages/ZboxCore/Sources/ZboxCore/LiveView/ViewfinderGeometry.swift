import Foundation

/// Geometry only: guides apply to the aspect-fitted picture, not the surrounding black bars.
public enum ViewfinderGeometry {
    public struct Rect: Equatable, Sendable {
        public let x: Double, y: Double, width: Double, height: Double
    }
    public static func aspectFit(contentWidth: Double, contentHeight: Double,
                                 boundsWidth: Double, boundsHeight: Double) -> Rect? {
        let dimensions = [contentWidth, contentHeight, boundsWidth, boundsHeight]
        guard dimensions.allSatisfy({ $0.isFinite && $0 > 0 }) else { return nil }
        let scale = min(boundsWidth / contentWidth, boundsHeight / contentHeight)
        let width = contentWidth * scale, height = contentHeight * scale
        guard width.isFinite, height.isFinite, width > 0, height > 0 else { return nil }
        return Rect(x: (boundsWidth - width) / 2, y: (boundsHeight - height) / 2,
                    width: width, height: height)
    }
}
