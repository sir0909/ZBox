import XCTest
@testable import ZboxCore

final class EmbeddedJPEGTests: XCTestCase {
    func testPicksLargestCompleteEmbeddedImageAndIgnoresIncompleteOuter() {
        let input = Data([0xff, 0xd8, 0, 0xff, 0xd8, 1, 0xff, 0xd9, 3, 0xff, 0xd8, 1, 2, 3, 0xff, 0xd9])
        XCTAssertEqual(EmbeddedJPEG.largestComplete(in: input), Data([0xff, 0xd8, 1, 2, 3, 0xff, 0xd9]))
    }
    func testTruncatedAndTooLargePrefixAreRejected() {
        XCTAssertNil(EmbeddedJPEG.largestComplete(in: Data([0xff, 0xd8, 1])))
        XCTAssertNil(EmbeddedJPEG.largestComplete(in: Data(repeating: 0, count: EmbeddedJPEG.maxPrefixBytes + 1)))
        XCTAssertNil(EmbeddedJPEG.largestComplete(in: Data([0xff, 0xd8, 0xff, 0xd9])))
    }
    func testPathologicalNestingIsRejected() {
        var input = Data()
        for _ in 0..<129 { input.append(contentsOf: [0xff, 0xd8]) }
        XCTAssertNil(EmbeddedJPEG.largestComplete(in: input))
    }
}
