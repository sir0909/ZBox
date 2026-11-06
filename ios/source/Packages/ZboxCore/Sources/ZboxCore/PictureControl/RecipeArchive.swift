import Foundation
import CryptoKit

/// ZBox's own local interchange format; it is not a Nikon NCP file.
/// SHA-256 detects accidental modification but does not authenticate the author or camera.
public enum RecipeArchive {
    public enum Error: Swift.Error, Equatable { case invalid, integrity }
    public struct Contents: Sendable {
        public let payload: Data
        public let points: [CurveLUT.Point]
        public let metadata: ShareRecipe?
        public let family: Int?
        /// Optional original 84-byte file, retained verbatim as provenance, never written to the camera.
        public let legacyOriginal: Data?
    }
    private struct Body: Codable {
        let format: String
        let version: Int
        let payload: Data
        let points: [CurveLUT.Point]
        let metadata: ShareRecipe?
        let family: Int?
        let legacyOriginal: Data?
    }
    private struct Envelope: Codable {
        let body: Body
        let sha256: String
    }
    private static let limit = 32_768
    private static func encoder() -> JSONEncoder {
        let result = JSONEncoder(); result.outputFormatting = [.sortedKeys]; return result
    }
    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func validate(_ body: Body) throws {
        guard body.format == "ZBOX_RECIPE", body.version == 1,
              body.family == nil || [0, 1, 2, 8].contains(body.family!) else { throw Error.invalid }
        let parsed = try PictureControl614.parse(body.payload)
        if let original = body.legacyOriginal {
            guard original.count == 84, (try RecipePayload.importFile(original)).approximate else { throw Error.invalid }
        }
        _ = try CurveLUT.make(body.points)
        if let metadata = body.metadata {
            guard metadata.name == parsed.name, !metadata.profile.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  metadata.profile.utf8.count <= 256, metadata.sharpening.isFinite, metadata.clarity.isFinite,
                  metadata.contrast.isFinite, metadata.brightness.isFinite, metadata.saturation.isFinite, metadata.hue.isFinite,
                  (-3...9).contains(metadata.sharpening), (-5...5).contains(metadata.clarity),
                  (-3...3).contains(metadata.contrast), (-2...2).contains(metadata.brightness),
                  (-3...3).contains(metadata.saturation), (-3...3).contains(metadata.hue) else { throw Error.invalid }
            _ = try CurveLUT.make(metadata.points)
        }
    }
    public static func encode(payload: Data, points: [CurveLUT.Point], metadata: ShareRecipe?, family: Int?, legacyOriginal: Data? = nil) throws -> Data {
        let body = Body(format: "ZBOX_RECIPE", version: 1, payload: payload, points: points, metadata: metadata, family: family,
                        legacyOriginal: legacyOriginal)
        try validate(body)
        let hash = digest(try encoder().encode(body))
        let data = try encoder().encode(Envelope(body: body, sha256: hash))
        guard data.count <= limit else { throw Error.invalid }
        return data
    }
    public static func decode(_ data: Data) throws -> Contents {
        guard !data.isEmpty, data.count <= limit else { throw Error.invalid }
        let envelope = try JSONDecoder().decode(Envelope.self, from: data)
        try validate(envelope.body)
        guard envelope.sha256 == digest(try encoder().encode(envelope.body)) else { throw Error.integrity }
        return Contents(payload: envelope.body.payload, points: envelope.body.points,
                        metadata: envelope.body.metadata, family: envelope.body.family, legacyOriginal: envelope.body.legacyOriginal)
    }
}
