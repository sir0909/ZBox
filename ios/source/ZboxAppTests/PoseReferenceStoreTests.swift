import XCTest
import UIKit
@testable import Zbox

@MainActor
final class PoseReferenceStoreTests: XCTestCase {
    private func folder() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }
    private func jpeg(size: CGSize = CGSize(width: 24, height: 16)) throws -> Data {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.orange.setFill(); context.fill(CGRect(origin: .zero, size: size))
        }
        return try XCTUnwrap(image.jpegData(compressionQuality: 0.8))
    }
    func testAddFavoriteReopenAndRemoveOnlyOwnCopy() throws {
        let url = folder(); defer { try? FileManager.default.removeItem(at: url) }
        let store = PoseReferenceStore(directory: url), image = try jpeg()
        let entry = try store.add(image)
        XCTAssertEqual(store.imageData(entry.id), image)
        try store.toggleFavorite(entry.id)
        let reopened = PoseReferenceStore(directory: url)
        XCTAssertTrue(reopened.healthy)
        XCTAssertEqual(reopened.items.count, 1)
        XCTAssertTrue(try XCTUnwrap(reopened.items.first).favorite)
        XCTAssertEqual(reopened.imageData(entry.id), image)
        try reopened.remove(entry.id)
        XCTAssertTrue(reopened.items.isEmpty)
        XCTAssertNil(reopened.imageData(entry.id))
        XCTAssertTrue(PoseReferenceStore(directory: url).items.isEmpty)
    }
    func testCorruptIndexNeverOverwrittenOrDeleted() throws {
        let url = folder(); defer { try? FileManager.default.removeItem(at: url) }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        let index = url.appendingPathComponent("index.json")
        let bad = Data("not json".utf8); try bad.write(to: index)
        let store = PoseReferenceStore(directory: url)
        XCTAssertFalse(store.healthy)
        XCTAssertThrowsError(try store.add(jpeg()))
        XCTAssertEqual(try Data(contentsOf: index), bad)
    }
    func testRejectsInvalidOversizedAndMissingImages() throws {
        let url = folder(); defer { try? FileManager.default.removeItem(at: url) }
        let store = PoseReferenceStore(directory: url)
        XCTAssertThrowsError(try store.add(Data("not an image".utf8)))
        XCTAssertThrowsError(try store.add(jpeg(size: CGSize(width: 769, height: 3))))
        XCTAssertThrowsError(try store.add(Data(repeating: 0, count: 2_000_001)))
        XCTAssertTrue(store.items.isEmpty)
        let entry = try store.add(jpeg())
        let imageURL = url.appendingPathComponent(entry.id.uuidString + ".jpg")
        try FileManager.default.removeItem(at: imageURL)
        let reopened = PoseReferenceStore(directory: url)
        XCTAssertEqual(reopened.items.count, 1)
        XCTAssertNil(reopened.imageData(entry.id))
        XCTAssertTrue(reopened.healthy)
    }
    func testIndexWriteFailureRollsBackOnlyTheNewImage() throws {
        let url = folder(); defer { try? FileManager.default.removeItem(at: url) }
        let store = PoseReferenceStore(directory: url)
        try FileManager.default.createDirectory(at: url.appendingPathComponent("index.json"), withIntermediateDirectories: true)
        XCTAssertThrowsError(try store.add(jpeg()))
        XCTAssertTrue(store.items.isEmpty)
        let names = try FileManager.default.contentsOfDirectory(atPath: url.path)
        XCTAssertEqual(names, ["index.json"])
    }
    func testFullIndexBlocksAddingAndDuplicateIDsFailClosed() throws {
        let url = folder(); defer { try? FileManager.default.removeItem(at: url) }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        let index = url.appendingPathComponent("index.json")
        let entries = (0..<200).map { _ in PoseReference(id: UUID(), createdAt: Date(), favorite: false) }
        let full = try JSONEncoder().encode(entries); try full.write(to: index)
        let store = PoseReferenceStore(directory: url)
        XCTAssertTrue(store.healthy)
        XCTAssertThrowsError(try store.add(jpeg()))
        XCTAssertEqual(try Data(contentsOf: index), full)
        let duplicate = try JSONEncoder().encode([entries[0], entries[0]]); try duplicate.write(to: index)
        let broken = PoseReferenceStore(directory: url)
        XCTAssertFalse(broken.healthy)
        XCTAssertThrowsError(try broken.add(jpeg()))
        XCTAssertEqual(try Data(contentsOf: index), duplicate)
    }
    func testOfflinePhraseCatalogHasAllFourGroups() {
        XCTAssertEqual(PoseWords.groups.count, 4)
        XCTAssertEqual(PoseWords.groups.flatMap(\.lines).count, 60)
        XCTAssertTrue(PoseWords.groups.allSatisfy { !$0.lines.isEmpty && !$0.title.isEmpty })
    }
}
