import XCTest
@testable import ZboxCore

final class PreviewAnalysisTests: XCTestCase {
    func testHistogramAndZebra() throws {
        let rgba: [UInt8] = [255,255,255,255, 0,0,0,255, 255,255,255,255, 0,0,0,255]
        let r = try XCTUnwrap(PreviewAnalysis.analyze(rgba: rgba, width: 2, height: 2, zebra: true, peaking: false))
        XCTAssertEqual(r.histogram[255], 2); XCTAssertEqual(r.histogram[0], 2); XCTAssertEqual(r.highlightCount, 2)
        XCTAssertGreaterThan(r.overlayRGBA[3], 0); XCTAssertEqual(r.overlayRGBA[7], 0)
    }
    func testPeakingEdgesAndBounds() throws {
        let rgba: [UInt8] = [0,0,0,255, 0,0,0,255, 0,0,0,255, 255,255,255,255]
        let r = try XCTUnwrap(PreviewAnalysis.analyze(rgba: rgba, width: 2, height: 2, zebra: false, peaking: true))
        XCTAssertGreaterThan(r.overlayRGBA[15], 0)
        XCTAssertNil(PreviewAnalysis.analyze(rgba: rgba, width: 321, height: 1, zebra: true, peaking: false))
        XCTAssertNil(PreviewAnalysis.analyze(rgba: [], width: 2, height: 2, zebra: true, peaking: false))
    }
}
