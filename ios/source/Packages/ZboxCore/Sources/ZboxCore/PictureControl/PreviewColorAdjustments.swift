import Foundation

/// A deliberately approximate display-only transform. It does not describe Nikon's
/// color engine or encode any parameters into a camera payload.
public struct PreviewColorAdjustments: Sendable {
    public let monochrome: Bool
    private let saturation: Float
    private let contrast: Float
    private let brightness: Float
    private let cosine: Float
    private let sine: Float
    public let isNeutral: Bool

    /// Inputs use the Android share-code ranges; reject invalid persisted metadata.
    public init?(saturation: Float, hue: Float, contrast: Float, brightness: Float, monochrome: Bool) {
        guard saturation.isFinite, hue.isFinite, contrast.isFinite, brightness.isFinite,
              (-3...3).contains(saturation), (-3...3).contains(hue),
              (-3...3).contains(contrast), (-2...2).contains(brightness) else { return nil }
        self.monochrome = monochrome
        self.saturation = 1 + saturation * 0.20
        self.contrast = 1 + contrast * 0.12
        self.brightness = brightness * 0.06
        let angle = hue * (.pi / 18) // local illustration only: 10 degrees per step
        self.cosine = cos(angle); self.sine = sin(angle)
        self.isNeutral = !monochrome && saturation == 0 && hue == 0 && contrast == 0 && brightness == 0
    }
    public static let neutral = PreviewColorAdjustments(saturation: 0, hue: 0, contrast: 0, brightness: 0, monochrome: false)!

    /// sRGB-like pixels; alpha and the image's original file are untouched.
    public func apply(red: UInt8, green: UInt8, blue: UInt8) -> (UInt8, UInt8, UInt8) {
        if isNeutral { return (red, green, blue) }
        let r = Float(red) / 255, g = Float(green) / 255, b = Float(blue) / 255
        let luma = 0.299 * r + 0.587 * g + 0.114 * b
        // Rotate chroma in YIQ; this is not a Nikon hue model.
        let i = 0.596 * r - 0.274 * g - 0.322 * b
        let q = 0.211 * r - 0.523 * g + 0.312 * b
        let rotatedI = (i * cosine - q * sine) * (monochrome ? 0 : saturation)
        let rotatedQ = (i * sine + q * cosine) * (monochrome ? 0 : saturation)
        let rr = luma + 0.956 * rotatedI + 0.621 * rotatedQ
        let gg = luma - 0.272 * rotatedI - 0.647 * rotatedQ
        let bb = luma - 1.106 * rotatedI + 1.703 * rotatedQ
        func component(_ value: Float) -> UInt8 {
            let adjusted = (value - 0.5) * contrast + 0.5 + brightness
            return UInt8((max(0, min(1, adjusted)) * 255).rounded())
        }
        return (component(rr), component(gg), component(bb))
    }
}
