import XCTest
@testable import ZboxCore

final class AlbumBrowseTests: XCTestCase {
    private func item(_ handle: UInt32, _ kind: NikonSTAAlbumItem.Kind, _ date: String?) -> NikonSTAAlbumItem {
        NikonSTAAlbumItem(storageID: 1, handle: handle, kind: kind, capturedAt: date)
    }

    func testFiltersIncludeBothVideoFormatsAndPreserveItemIdentity() {
        let items = [item(1, .raw, nil), item(2, .jpeg, nil),
                     item(3, .videoMOV, nil), item(4, .videoMP4, nil)]
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .all, order: .newest).map(\.handle), [1, 2, 3, 4])
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .raw, order: .newest).map(\.handle), [1])
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .jpeg, order: .newest).map(\.handle), [2])
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .video, order: .newest).map(\.handle), [3, 4])
        XCTAssertEqual(AlbumBrowse.apply([items[0]], filter: .video, order: .newest), [])
    }

    func testBothSortDirectionsPlaceUnknownDatesLastAndKeepTiesStable() {
        let items = [item(1, .jpeg, nil), item(2, .raw, "2025-01-02 00:00:00"),
                     item(3, .jpeg, "2024-01-02 00:00:00"), item(4, .jpeg, "2025-01-02 00:00:00"),
                     item(5, .videoMOV, nil)]
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .all, order: .newest).map(\.handle), [2, 4, 3, 1, 5])
        XCTAssertEqual(AlbumBrowse.apply(items, filter: .all, order: .oldest).map(\.handle), [3, 2, 4, 1, 5])
        XCTAssertEqual(AlbumBrowse.apply([], filter: .all, order: .oldest), [])
    }
}
