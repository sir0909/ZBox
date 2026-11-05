import XCTest
@testable import ZboxCore

final class ViewfinderGeometryTests: XCTestCase {
    func testPillarboxAndLetterboxBounds() throws {
        let pillar = try XCTUnwrap(ViewfinderGeometry.aspectFit(contentWidth: 4, contentHeight: 3, boundsWidth: 160, boundsHeight: 90))
        XCTAssertEqual(pillar.x, 20, accuracy: 0.0001); XCTAssertEqual(pillar.y, 0, accuracy: 0.0001)
        XCTAssertEqual(pillar.width, 120, accuracy: 0.0001); XCTAssertEqual(pillar.height, 90, accuracy: 0.0001)
        let letter = try XCTUnwrap(ViewfinderGeometry.aspectFit(contentWidth: 16, contentHeight: 9, boundsWidth: 100, boundsHeight: 100))
        XCTAssertEqual(letter.x, 0, accuracy: 0.0001); XCTAssertEqual(letter.y, 21.875, accuracy: 0.0001)
        XCTAssertEqual(letter.width, 100, accuracy: 0.0001); XCTAssertEqual(letter.height, 56.25, accuracy: 0.0001)
    }
    func testRejectsInvalidOrNonFiniteGeometry() {
        XCTAssertNil(ViewfinderGeometry.aspectFit(contentWidth: 0, contentHeight: 1, boundsWidth: 1, boundsHeight: 1))
        XCTAssertNil(ViewfinderGeometry.aspectFit(contentWidth: .infinity, contentHeight: 1, boundsWidth: 1, boundsHeight: 1))
        XCTAssertNil(ViewfinderGeometry.aspectFit(contentWidth: 1, contentHeight: .nan, boundsWidth: 1, boundsHeight: 1))
        XCTAssertNil(ViewfinderGeometry.aspectFit(contentWidth: 1, contentHeight: 1, boundsWidth: -1, boundsHeight: 1))
    }
}
