import XCTest
@testable import Zbox

@MainActor
final class PosePhraseStoreTests: XCTestCase {
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true).appendingPathComponent("phrases.json")
    }
    func testCustomAndFavoritesPersistAndDeleteTogether() throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = PosePhraseStore(url: url)
        try store.addCustom("  请自然转身  ")
        try store.toggleFavorite("请自然转身")
        let builtin = try XCTUnwrap(PoseWords.groups.first?.lines.first)
        try store.toggleFavorite(builtin)
        let reopened = PosePhraseStore(url: url)
        XCTAssertTrue(reopened.healthy)
        XCTAssertEqual(reopened.custom, ["请自然转身"])
        XCTAssertEqual(Set(reopened.favorites), Set(["请自然转身", builtin]))
        try reopened.removeCustom("请自然转身")
        let after = PosePhraseStore(url: url)
        XCTAssertTrue(after.custom.isEmpty)
        XCTAssertEqual(after.favorites, [builtin])
    }
    func testInvalidDuplicatesAndCorruptFileFailClosed() throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = PosePhraseStore(url: url)
        XCTAssertThrowsError(try store.addCustom("   "))
        XCTAssertThrowsError(try store.addCustom("line\nline"))
        XCTAssertThrowsError(try store.addCustom(String(repeating: "x", count: 181)))
        try store.addCustom("好")
        XCTAssertThrowsError(try store.addCustom("好"))
        let bad = Data("broken".utf8); try bad.write(to: url)
        let broken = PosePhraseStore(url: url)
        XCTAssertFalse(broken.healthy)
        XCTAssertThrowsError(try broken.addCustom("another"))
        XCTAssertEqual(try Data(contentsOf: url), bad)
    }
    func testBuiltInFavoriteWorksWithoutCustomPhrases() throws {
        let url = temporaryURL(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let line = try XCTUnwrap(PoseWords.groups.last?.lines.first)
        let store = PosePhraseStore(url: url)
        try store.toggleFavorite(line)
        XCTAssertEqual(PosePhraseStore(url: url).favorites, [line])
        try store.toggleFavorite(line)
        XCTAssertTrue(PosePhraseStore(url: url).favorites.isEmpty)
    }
}
