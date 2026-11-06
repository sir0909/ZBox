import XCTest
@testable import ZboxCore

final class AIRecipeTests: XCTestCase {
    private func json(_ points: String, name: String = "AI_CURVE", analysis: String = "柔和") -> Data {
        Data("{\"preset_name\":\"\(name)\",\"tone_curve_points\":\(points),\"style_analysis\":\"\(analysis)\"}".utf8)
    }
    func testAcceptsBoundedOrderedCurve() throws {
        let result = try AIRecipe.parse(json("[{\"x\":0,\"y\":5},{\"x\":128,\"y\":130},{\"x\":255,\"y\":240}]"))
        XCTAssertEqual(result.name, "AI_CURVE"); XCTAssertEqual(result.points.count, 3)
        XCTAssertEqual(try CurveLUT.make(result.points).count, 256)
    }
    func testRejectsUnsafeOrMalformedOutput() throws {
        let bad = [
            "[{\"x\":1,\"y\":0},{\"x\":255,\"y\":255}]",
            "[{\"x\":0,\"y\":0},{\"x\":128,\"y\":200},{\"x\":255,\"y\":100}]",
            "[{\"x\":0,\"y\":0},{\"x\":0,\"y\":0},{\"x\":255,\"y\":255}]",
            "[{\"x\":0,\"y\":-1},{\"x\":255,\"y\":255}]"
        ]
        for points in bad { XCTAssertThrowsError(try AIRecipe.parse(json(points))) }
        XCTAssertThrowsError(try AIRecipe.parse(json("[{\"x\":0,\"y\":0},{\"x\":255,\"y\":255}]", name: "中文")))
        XCTAssertThrowsError(try AIRecipe.parse(Data(repeating: 65, count: 16_385)))
    }
    func testOptionalPreviewSuggestionsAreBoundedAndBackwardCompatible() throws {
        let base = "\"preset_name\":\"AI_CURVE\",\"tone_curve_points\":[{\"x\":0,\"y\":0},{\"x\":255,\"y\":255}],\"style_analysis\":\"ok\""
        let legacy = try AIRecipe.parse(Data("{\(base)}".utf8))
        XCTAssertNil(legacy.previewSuggestion)
        let good = Data("{\(base),\"preview_adjustments\":{\"brightness\":1,\"contrast\":-2,\"saturation\":0.5,\"hue\":3}}".utf8)
        let result = try AIRecipe.parse(good)
        XCTAssertEqual(result.previewSuggestion?.saturation, 0.5)
        let bad = Data("{\(base),\"preview_adjustments\":{\"brightness\":3,\"contrast\":0,\"saturation\":0,\"hue\":0}}".utf8)
        XCTAssertThrowsError(try AIRecipe.parse(bad))
        let incomplete = Data("{\(base),\"preview_adjustments\":{\"brightness\":0}}".utf8)
        XCTAssertThrowsError(try AIRecipe.parse(incomplete))
    }

}
