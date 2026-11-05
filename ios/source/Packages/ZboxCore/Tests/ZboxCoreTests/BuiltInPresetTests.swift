import XCTest
@testable import ZboxCore

final class BuiltInPresetTests: XCTestCase {
    func testAllAndroidPresetsLoadAndTheirCurvesBuild() throws {
        let presets = try BuiltInPresetCatalog.load()
        XCTAssertEqual(presets.count, 200)
        XCTAssertEqual(Set(presets.map(\.id)).count, 200)
        XCTAssertEqual(Set(presets.map(\.category)).count, 10)
        XCTAssertEqual(presets.filter { $0.source == "derived" }.count, 34)
        for preset in presets {
            let payload = try preset.makePayload()
            XCTAssertEqual(try PictureControl614.parse(payload).name, preset.cameraName)
            XCTAssertEqual(try RecipePayload.lut8(payload), try CurveLUT.make(preset.points))
        }
        let sample = try XCTUnwrap(presets.first { $0.id == "pt01" })
        XCTAssertEqual(sample.cameraName, "SKIN_PORTRAIT")
        XCTAssertEqual(sample.points.count, 6)
        XCTAssertEqual(sample.sharpening, 3)
    }
}
