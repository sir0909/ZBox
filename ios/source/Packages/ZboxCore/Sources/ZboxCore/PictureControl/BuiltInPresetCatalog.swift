import Foundation

/// Data and original descriptive metadata ported from Android BuiltInPresets.kt.
/// Only tone curves are currently applied; other fields are preserved as metadata.
public struct BuiltInPreset: Decodable, Identifiable, Sendable {
    public let id: String
    public let displayName: String
    public let cameraName: String
    public let category: String
    public let categoryLabel: String
    public let note: String
    public let rank: Int
    public let profile: String
    public let family: Int
    public let sharpening: Float
    public let clarity: Float
    public let contrast: Float
    public let brightness: Float
    public let saturation: Float
    public let hue: Float
    public let points: [CurveLUT.Point]
    public let source: String

    public var shareMetadata: ShareRecipe {
        ShareRecipe(name: cameraName, profile: profile, sharpening: sharpening, clarity: clarity,
                    contrast: contrast, brightness: brightness, saturation: saturation, hue: hue, points: points)
    }
    public func makePayload() throws -> Data {
        try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: cameraName, lut: CurveLUT.make(points))
    }
}

public enum BuiltInPresetCatalog {
    public enum Error: Swift.Error { case missing, invalid }
    public static func load() throws -> [BuiltInPreset] {
        guard let url = Bundle.module.url(forResource: "BuiltInPresets", withExtension: "json") else { throw Error.missing }
        let data = try Data(contentsOf: url)
        guard data.count <= 500_000 else { throw Error.invalid }
        let entries = try JSONDecoder().decode([BuiltInPreset].self, from: data)
        guard entries.count == 200, Set(entries.map(\.id)).count == 200 else { throw Error.invalid }
        for entry in entries {
            guard !entry.id.isEmpty, !entry.displayName.isEmpty, !entry.category.isEmpty,
                  ["ncp", "derived"].contains(entry.source) else { throw Error.invalid }
            _ = try entry.makePayload()
        }
        return entries.sorted { $0.rank < $1.rank }
    }
}
