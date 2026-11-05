import SwiftUI
import ZboxCore

/// Local-only guides; never alter the camera feed or the image saved to the card.
struct ViewfinderGuides: View {
    let imageSize: CGSize
    @AppStorage("viewfinder.grid") private var grid = false
    @AppStorage("viewfinder.frame16x9") private var frame = false

    var body: some View {
        Canvas { context, size in
            guard let fit = ViewfinderGeometry.aspectFit(contentWidth: Double(imageSize.width), contentHeight: Double(imageSize.height),
                boundsWidth: Double(size.width), boundsHeight: Double(size.height)) else { return }
            let r = CGRect(x: fit.x, y: fit.y, width: fit.width, height: fit.height)
            if grid {
                var path = Path()
                for i in 1...2 {
                    let x = r.minX + r.width * CGFloat(i) / 3
                    let y = r.minY + r.height * CGFloat(i) / 3
                    path.move(to: CGPoint(x: x, y: r.minY)); path.addLine(to: CGPoint(x: x, y: r.maxY))
                    path.move(to: CGPoint(x: r.minX, y: y)); path.addLine(to: CGPoint(x: r.maxX, y: y))
                }
                context.stroke(path, with: .color(.white.opacity(0.7)), lineWidth: 1)
            }
            if frame, let guide = ViewfinderGeometry.aspectFit(contentWidth: 16, contentHeight: 9,
                                                                 boundsWidth: fit.width, boundsHeight: fit.height) {
                let guideRect = CGRect(x: fit.x + guide.x, y: fit.y + guide.y, width: guide.width, height: guide.height)
                context.stroke(Path(guideRect), with: .color(.yellow.opacity(0.9)), style: StrokeStyle(lineWidth: 1, dash: [5, 3]))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
