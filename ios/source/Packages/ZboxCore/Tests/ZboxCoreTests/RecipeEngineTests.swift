import XCTest
@testable import ZboxCore

final class RecipeEngineTests: XCTestCase {
    func testLinearAndMonotoneSplineAndRejectBadPoints() throws {
        let identity = try CurveLUT.make([.init(x: 0,y: 0), .init(x: 255,y: 255)])
        XCTAssertEqual(identity, (0...255).map(UInt8.init))
        let curve = try CurveLUT.make([.init(x: 0,y: 0), .init(x: 50,y: 20), .init(x: 180,y: 220), .init(x: 255,y: 255)])
        XCTAssertTrue(zip(curve, curve.dropFirst()).allSatisfy { $0.0 <= $0.1 })
        XCTAssertEqual(curve[50], 20); XCTAssertEqual(curve[180], 220)
        XCTAssertThrowsError(try CurveLUT.make([.init(x: 1,y: 2), .init(x: 1,y: 3)]))
        XCTAssertThrowsError(try CurveLUT.make([.init(x: .nan,y: 2), .init(x: 255,y: 255)]))
    }
    func testRenamePreservesOriginal15BitAndUnknownBytes() throws {
        var template = RecipePayload.linearTemplate()
        template[0x67] = 7 // high precision not representable in eight-bit UI
        template[0x50] = 0xab
        let original = try RecipePayload.lut8(template)
        let renamed = try RecipePayload.edit(template: template, name: "NEW", lut: original)
        XCTAssertEqual(Data(renamed[0x2f..<614]), Data(template[0x2f..<614]))
        XCTAssertEqual(renamed[0x50], 0xab)
        XCTAssertEqual(try PictureControl614.parse(renamed).name, "NEW")
        XCTAssertThrowsError(try RecipePayload.edit(template: template, name: "中文"))
        XCTAssertThrowsError(try RecipePayload.edit(template: template, name: String(repeating: "a", count: 20)))
    }
    func testChangedCurveAndControlPointSelfConsistency() throws {
        let lut = (0...255).map { UInt8($0 / 2) }
        let payload = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "TEST", lut: lut)
        XCTAssertEqual(try RecipePayload.lut8(payload), lut)
        for i in 0..<4 { let x = i * 255 / 3; XCTAssertEqual(payload[0x2f + i * 2], UInt8(x)); XCTAssertEqual(payload[0x30 + i * 2], lut[x]) }
    }
    func testRawAndNCPRoundtripAndStrictRejection() throws {
        let raw = RecipePayload.linearTemplate()
        XCTAssertEqual(try RecipePayload.importFile(raw).payload, raw)
        XCTAssertEqual(try RecipePayload.importFile(RecipePayload.exportNCP(raw)).payload, raw)
        XCTAssertThrowsError(try RecipePayload.importFile(Data(repeating: 0, count: 618)))
        XCTAssertThrowsError(try RecipePayload.importFile(Data(repeating: 0, count: 84)))
    }
    func testLegacyImportIsExplicitlyApproximate() throws {
        var bytes = Data(repeating: 0, count: 84)
        bytes.replaceSubrange(0..<6, with: Data("LEGACY".utf8))
        for i in 0..<18 { bytes[0x1a + i] = UInt8(i * 15) }
        bytes[0x18] = 1
        let imported = try RecipePayload.importFile(bytes)
        XCTAssertTrue(imported.approximate); XCTAssertEqual(imported.legacyFamily, 1); XCTAssertEqual(imported.payload.count, 614)
        XCTAssertEqual(try PictureControl614.parse(imported.payload).name, "LEGACY")
    }
    func testAndroidShareCodeKnownVectorAndRoundtrip() throws {
        let recipe = ShareRecipe(name: "TEST", profile: "STANDARD", sharpening: 1.25, clarity: -0.5,
                                 points: [.init(x: 0,y: 0), .init(x: 255,y: 255)])
        let encoded = try RecipeShareCode.encode(recipe)
        XCTAssertEqual(encoded, "ZCAI1:WkNBSTF8VEVTVHxTVEFOREFSRHwxMjV8LTUwfDB8MHwwfDB8MDowLDI1NToyNTU=")
        let decoded = try RecipeShareCode.decode("说明 " + encoded + " 后续")
        XCTAssertEqual(decoded, recipe)
        XCTAssertThrowsError(try RecipeShareCode.decode("ZCAI1:bad"))
    }
}
