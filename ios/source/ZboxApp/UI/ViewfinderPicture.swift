import SwiftUI
import UIKit
import ZboxCore

/// Same aspect-fit geometry is used for image, overlays and touch mapping.
struct ViewfinderPicture: View {
    @ObservedObject var model: CameraConnectionModel
    let image: UIImage
    @AppStorage("viewfinder.zebra") private var zebra = false
    @AppStorage("viewfinder.peaking") private var peaking = false
    @AppStorage("viewfinder.histogram") private var histogram = false

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Image(uiImage: image).resizable().scaledToFit().accessibilityLabel("相机实时取景")
                if (zebra || peaking), let bytes = model.previewOverlay, let overlay = UIImage(data: bytes) {
                    Image(uiImage: overlay).resizable().interpolation(.none).scaledToFit().allowsHitTesting(false).accessibilityHidden(true)
                }
                ViewfinderGuides(imageSize: image.size)
                if histogram, !model.previewHistogram.isEmpty,
                   let r = ViewfinderGeometry.aspectFit(contentWidth: Double(image.size.width), contentHeight: Double(image.size.height),
                       boundsWidth: Double(proxy.size.width), boundsHeight: Double(proxy.size.height)) {
                    histogramView
                        .frame(width: min(140, CGFloat(r.width) * 0.4), height: 50)
                        .position(x: CGFloat(r.x) + min(140, CGFloat(r.width) * 0.4) / 2 + 5,
                                  y: CGFloat(r.y + r.height) - 30)
                        .allowsHitTesting(false)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .contentShape(Rectangle())
            .gesture(SpatialTapGesture().onEnded { event in
                guard model.canTouchFocus, image.imageOrientation == .up, let cg = image.cgImage,
                      let point = TouchFocus.normalizedPoint(x: Double(event.location.x), y: Double(event.location.y),
                          boundsWidth: Double(proxy.size.width), boundsHeight: Double(proxy.size.height),
                          imageWidth: Double(image.size.width), imageHeight: Double(image.size.height)) else { return }
                Task { await model.touchFocus(x: point.0, y: point.1, imageWidth: cg.width, imageHeight: cg.height) }
            })
            .accessibilityHint("相机支持时，轻点画面尝试移动对焦区域并自动对焦。")
        }
    }
    private var histogramView: some View {
        Canvas { context, size in
            let values = model.previewHistogram
            let top = max(1, values.max() ?? 1)
            var path = Path()
            for i in values.indices {
                let x = size.width * CGFloat(i) / CGFloat(values.count)
                let height = size.height * CGFloat(values[i]) / CGFloat(top)
                path.move(to: CGPoint(x: x, y: size.height)); path.addLine(to: CGPoint(x: x, y: size.height - height))
            }
            context.stroke(path, with: .color(.white.opacity(0.9)), lineWidth: max(0.5, size.width / CGFloat(values.count)))
        }
        .padding(4).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 4))
        .accessibilityLabel("预览亮度直方图，不代表 RAW 曝光")
    }
}
