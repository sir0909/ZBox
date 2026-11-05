import Foundation
import XCTest
import ZboxCore
@testable import Zbox

@MainActor
final class RecipeWorkbenchTests: XCTestCase {
    private func temporaryStore() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true).appendingPathComponent("recipes.json")
    }
    func testLocalSaveReloadAndDeleteDoesNotNeedCamera() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        workbench.name = "LOCAL"
        workbench.setPoint(2, y: 150)
        workbench.save()
        XCTAssertEqual(workbench.entries.count, 1)
        let reopened = RecipeWorkbench(storeURL: url)
        XCTAssertTrue(reopened.storeHealthy); XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(try PictureControl614.parse(reopened.entries[0].payload).name, "LOCAL")
        reopened.remove(reopened.entries[0]); XCTAssertTrue(reopened.entries.isEmpty)
        XCTAssertTrue(RecipeWorkbench(storeURL: url).entries.isEmpty)
    }
    func testCorruptStoreIsNotOverwritten() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let original = Data("bad json".utf8); try original.write(to: url)
        let workbench = RecipeWorkbench(storeURL: url)
        XCTAssertFalse(workbench.storeHealthy)
        workbench.save()
        XCTAssertEqual(try Data(contentsOf: url), original)
    }
    func testInvalidImportLeavesDraftUnchangedAndRenamePreservesCurve() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        var original = RecipePayload.linearTemplate(); original[0x67] = 7
        workbench.importBytes(original)
        workbench.importBytes(Data(repeating: 0, count: 84))
        workbench.name = "RENAMED"
        let renamed = try workbench.snapshot()
        XCTAssertEqual(Data(renamed[0x2f..<614]), Data(original[0x2f..<614]))
    }
}
