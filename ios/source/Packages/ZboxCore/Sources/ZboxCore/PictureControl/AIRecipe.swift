import Foundation

/// Untrusted model output. A bounded monotone curve and optional preview-only color values.
public struct AIRecipe: Decodable, Sendable {
    public struct PreviewSuggestion: Decodable, Equatable, Sendable {
        public let brightness: Float
        public let contrast: Float
        public let saturation: Float
        public let hue: Float
        var valid: Bool {
            brightness.isFinite && contrast.isFinite && saturation.isFinite && hue.isFinite &&
            (-2...2).contains(brightness) && (-3...3).contains(contrast) &&
            (-3...3).contains(saturation) && (-3...3).contains(hue)
        }
    }
    public let name: String
    public let points: [CurveLUT.Point]
    public let analysis: String
    public let previewSuggestion: PreviewSuggestion?
    private enum CodingKeys: String, CodingKey {
        case name = "preset_name", points = "tone_curve_points", analysis = "style_analysis"
        case previewSuggestion = "preview_adjustments"
    }
    public enum Error: Swift.Error { case invalid }
    public static func parse(_ data: Data) throws -> AIRecipe {
        guard !data.isEmpty, data.count <= 16_384 else { throw Error.invalid }
        let result = try JSONDecoder().decode(AIRecipe.self, from: data)
        let bytes = Array(result.name.utf8)
        guard (1...19).contains(bytes.count), bytes.allSatisfy({ (0x20...0x7e).contains($0) }),
              !result.name.trimmingCharacters(in: .whitespaces).isEmpty,
              result.analysis.utf8.count <= 512, result.previewSuggestion?.valid ?? true,
              (2...8).contains(result.points.count),
              result.points.first?.x == 0, result.points.last?.x == 255,
              zip(result.points, result.points.dropFirst()).allSatisfy({ $0.0.x < $0.1.x && $0.0.y <= $0.1.y })
        else { throw Error.invalid }
        _ = try CurveLUT.make(result.points)
        return result
    }
}
