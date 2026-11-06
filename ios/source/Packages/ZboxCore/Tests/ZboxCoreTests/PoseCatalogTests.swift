import Foundation
import CryptoKit
import XCTest
@testable import ZboxCore

final class PoseCatalogTests: XCTestCase {
    func testPinnedAndroidCatalogAndTwelveWayFilter() throws {
        let catalog = try PoseCatalog.load()
        XCTAssertEqual(catalog.commit, PoseCatalog.pinnedCommit)
        XCTAssertEqual(catalog.items.count, 4155)
        XCTAssertEqual(catalog.definitions.count, 12)
        let item = try XCTUnwrap(catalog.items.first)
        let code = Array(item.code).map(String.init)
        let filters = Dictionary(uniqueKeysWithValues: zip(PoseCatalog.fields, code))
        XCTAssertTrue(catalog.filtered(filters).contains(item))
        XCTAssertTrue(catalog.filtered(["pose": "not-a-code"]).isEmpty)
        XCTAssertTrue(catalog.filtered(["unknown": "a"]).isEmpty)
        XCTAssertEqual(catalog.filtered([:]).count, 4155)
    }
    func testGitBlobHashAndUnsafeNames() {
        let data = Data("abc".utf8)
        var h = Insecure.SHA1(); h.update(data: Data("blob 3\0".utf8)); h.update(data: data)
        let sha = h.finalize().map { String(format: "%02x", $0) }.joined()
        let valid = PoseCatalog.Item(name: "0001-aaaaaaaaaaaa.jpg", code: "aaaaaaaaaaaa", sha: sha, bytes: 3)
        XCTAssertTrue(PoseCatalog.matchesBlob(valid, data: data))
        XCTAssertFalse(PoseCatalog.matchesBlob(valid, data: Data("abd".utf8)))
        XCTAssertFalse(PoseCatalog.isSafeItem(.init(name: "../1-aaaaaaaaaaaa.jpg", code: "aaaaaaaaaaaa", sha: sha, bytes: 3)))
        XCTAssertFalse(PoseCatalog.isSafeItem(.init(name: "0001-aaaaaaaaaaaa.jpg", code: "aaaaaaaaaaaa", sha: sha, bytes: 0)))
    }
}
