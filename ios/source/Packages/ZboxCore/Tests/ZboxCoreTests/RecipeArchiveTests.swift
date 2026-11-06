import Foundation
import XCTest
@testable import ZboxCore

final class RecipeArchiveTests: XCTestCase {
    private let points: [CurveLUT.Point] = [.init(x: 0, y: 10), .init(x: 110, y: 135), .init(x: 255, y: 250)]
    func testLosslessPayloadPointsAndMetadata() throws {
        var original = RecipePayload.linearTemplate(); original[0x67] = 7; original[0x50] = 0xab
        let name = try PictureControl614.parse(original).name
        let metadata = ShareRecipe(name: name, profile: "MONOCHROME", sharpening: 3, contrast: 1,
                                   brightness: -1, saturation: 2, hue: -0.5, points: points)
        let file = try RecipeArchive.encode(payload: original, points: points, metadata: metadata, family: 1)
        let decoded = try RecipeArchive.decode(file)
        XCTAssertEqual(decoded.payload, original); XCTAssertEqual(decoded.points, points)
        XCTAssertEqual(decoded.metadata, metadata); XCTAssertEqual(decoded.family, 1)
    }
    func testCorruptionUnknownVersionAndMalformedInputsFailClosed() throws {
        let payload = RecipePayload.linearTemplate()
        let file = try RecipeArchive.encode(payload: payload, points: points, metadata: nil, family: 0)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: file) as? [String: Any])
        var body = try XCTUnwrap(json["body"] as? [String: Any])
        body["family"] = 1; json["body"] = body
        let modified = try JSONSerialization.data(withJSONObject: json)
        XCTAssertThrowsError(try RecipeArchive.decode(modified)) { XCTAssertEqual($0 as? RecipeArchive.Error, .integrity) }
        body["version"] = 2; json["body"] = body
        XCTAssertThrowsError(try RecipeArchive.decode(JSONSerialization.data(withJSONObject: json)))
        XCTAssertThrowsError(try RecipeArchive.decode(Data(repeating: 65, count: 32_769)))
        XCTAssertThrowsError(try RecipeArchive.encode(payload: payload, points: points, metadata: nil, family: 100))
        let bad = ShareRecipe(name: "WRONG", points: points)
        XCTAssertThrowsError(try RecipeArchive.encode(payload: payload, points: points, metadata: bad, family: nil))
        XCTAssertThrowsError(try RecipeArchive.encode(payload: payload, points: points, metadata: nil, family: nil, legacyOriginal: Data(repeating: 0, count: 84)))
    }
}
