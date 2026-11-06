import Foundation
import CoreGraphics
import CoreTransferable
import ImageIO
import UniformTypeIdentifiers
import ZboxCore

/// PhotosPicker hands us a temporary file; decode a bounded, metadata-stripped thumbnail.
/// Reference pixels stay local and are not persisted or uploaded by this component.
struct ReferenceThumbnail: Transferable, Sendable {
    let jpeg: Data
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            try readFile(received.file)
        }
    }
    /// Shared by the photo picker and tests: downsample with orientation and do not copy source metadata.
    static func readFile(_ url: URL) throws -> ReferenceThumbnail {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 20_000, height <= 20_000,
              Int64(width) * Int64(height) <= 100_000_000 else { throw PreviewError.invalidImage }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 768, kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let data = ReferencePreview.encode(image, type: UTType.jpeg.identifier as CFString), data.count <= 2_000_000 else {
            throw PreviewError.invalidImage
        }
        return ReferenceThumbnail(jpeg: data)
    }
    enum PreviewError: Error { case invalidImage }
}

enum ReferencePreview {
    static func encode(_ image: CGImage, type: CFString) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, type, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
    /// Per-channel sRGB-like display approximation, not a Nikon engine or RAW output.
    static func apply(jpeg: Data, lut: [UInt8], adjustments: PreviewColorAdjustments = .neutral) -> Data? {
        guard jpeg.count <= 2_000_000, lut.count == 256,
              let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              image.width > 0, image.height > 0, image.width <= 768, image.height <= 768 else { return nil }
        let w = image.width, h = image.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        return pixels.withUnsafeMutableBytes { buffer -> Data? in
            guard let ctx = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return nil }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let bytes = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return nil }
            for i in 0..<(w * h) {
                let offset = i * 4
                let r = lut[Int(bytes[offset])], g = lut[Int(bytes[offset + 1])], b = lut[Int(bytes[offset + 2])]
                let transformed = adjustments.apply(red: r, green: g, blue: b)
                bytes[offset] = transformed.0
                bytes[offset + 1] = transformed.1
                bytes[offset + 2] = transformed.2
            }
            guard let output = ctx.makeImage() else { return nil }
            return encode(output, type: UTType.png.identifier as CFString)
        }
    }
}
