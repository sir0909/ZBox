import Foundation

/// ZCAI1 text format used by Android. It carries points and metadata, NOT an exact 614-byte payload.
public struct ShareRecipe: Codable, Equatable, Sendable {
    public var name: String
    public var profile: String
    public var sharpening: Float
    public var clarity: Float
    public var contrast: Float
    public var brightness: Float
    public var saturation: Float
    public var hue: Float
    public var points: [CurveLUT.Point]
    public init(name: String, profile: String = "STANDARD", sharpening: Float = 0, clarity: Float = 0,
                contrast: Float = 0, brightness: Float = 0, saturation: Float = 0, hue: Float = 0,
                points: [CurveLUT.Point]) {
        self.name = name; self.profile = profile; self.sharpening = sharpening; self.clarity = clarity
        self.contrast = contrast; self.brightness = brightness; self.saturation = saturation; self.hue = hue
        self.points = points
    }
}
public enum RecipeShareCode {
    public enum Error: Swift.Error, Equatable { case invalid }
    private static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "|", with: "/").replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private static func q(_ f: Float) -> Int { Int(floor(f * 100 + 0.5)) } // Java Math.round
    public static func encode(_ recipe: ShareRecipe) throws -> String {
        let values = [recipe.sharpening, recipe.clarity, recipe.contrast, recipe.brightness, recipe.saturation, recipe.hue]
        guard values.allSatisfy({ $0.isFinite && abs($0) <= 100 }), recipe.name.utf8.count <= 256,
              recipe.profile.utf8.count <= 256, !clean(recipe.name).isEmpty, !clean(recipe.profile).isEmpty else { throw Error.invalid }
        _ = try CurveLUT.make(recipe.points)
        let points = recipe.points.map { "\(Int(floor($0.x + 0.5))):\(Int(floor($0.y + 0.5)))" }.joined(separator: ",")
        let fields = ["ZCAI1", clean(recipe.name), clean(recipe.profile)] + values.map { String(q($0)) } + [points]
        let base64 = Data(fields.joined(separator: "|").utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        return "ZCAI1:" + base64
    }
    public static func decode(_ text: String) throws -> ShareRecipe {
        guard text.utf8.count <= 8192 else { throw Error.invalid }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let raw = trimmed.range(of: "ZCAI1:").map { String(trimmed[$0.upperBound...]) } ?? trimmed
        var token = String(raw.prefix { !$0.isWhitespace })
        guard !token.isEmpty else { throw Error.invalid }
        token = token.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        token += String(repeating: "=", count: (4 - token.count % 4) % 4)
        guard let data = Data(base64Encoded: token), data.count <= 4096,
              let body = String(data: data, encoding: .utf8) else { throw Error.invalid }
        let fields = body.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard fields.count == 10, fields[0] == "ZCAI1", fields[1].utf8.count <= 256,
              fields[2].utf8.count <= 256 else { throw Error.invalid }
        var numbers: [Float] = []
        for field in fields[3...8] {
            guard let n = Int32(field) else { throw Error.invalid }
            numbers.append(Float(n) / 100)
        }
        let limits: [(Float, Float)] = [(-3,9),(-5,5),(-3,3),(-2,2),(-3,3),(-3,3)]
        numbers = zip(numbers, limits).map { max($0.1.0, min($0.1.1, $0.0)) }
        let pairs = fields[9].split(separator: ",", omittingEmptySubsequences: false)
        guard (2...20).contains(pairs.count) else { throw Error.invalid }
        let points: [CurveLUT.Point] = try pairs.map { pair in
            let xy = pair.split(separator: ":", omittingEmptySubsequences: false)
            guard xy.count == 2, let x = Float(xy[0]), let y = Float(xy[1]), x.isFinite, y.isFinite,
                  (0...255).contains(x), (0...255).contains(y) else { throw Error.invalid }
            return .init(x: x, y: y)
        }
        _ = try CurveLUT.make(points)
        return ShareRecipe(name: fields[1].isEmpty ? "SHARED" : String(fields[1].prefix(19)),
            profile: fields[2].isEmpty ? "Standard" : fields[2], sharpening: numbers[0], clarity: numbers[1],
            contrast: numbers[2], brightness: numbers[3], saturation: numbers[4], hue: numbers[5], points: points)
    }
}
