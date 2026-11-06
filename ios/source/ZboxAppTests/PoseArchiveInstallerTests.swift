import XCTest
import CryptoKit
import ZIPFoundation
import ZboxCore
@testable import Zbox

final class PoseArchiveInstallerTests: XCTestCase {
    private let commit = PoseCatalog.pinnedCommit
    private func item(_ number: Int, _ content: Data) -> PoseCatalog.Item {
        var hash = Insecure.SHA1(); hash.update(data: Data("blob \(content.count)\0".utf8)); hash.update(data: content)
        return .init(name: String(format: "%04d-aaaaaaaaaaaa.jpg", number), code: "aaaaaaaaaaaa",
                     sha: hash.finalize().map { String(format: "%02x", $0) }.joined(), bytes: content.count)
    }
    private func zip(_ entries: [(String, Data)], url: URL) throws {
        let archive = try Archive(url: url, accessMode: .create)
        for (path, data) in entries {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count)) { position, size in
                data.subdata(in: Int(position)..<(Int(position) + size))
            }
        }
    }
    private func path(_ item: PoseCatalog.Item) -> String { "poze-\(commit)/assets/images/pose_samples/" + item.name }
    func testPartialInstallAndResumeVerifiedFiles() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let directory = root.appendingPathComponent("library"), firstData = Data("first".utf8), secondData = Data("second".utf8)
        let first = item(1, firstData), second = item(2, secondData)
        let one = root.appendingPathComponent("one.zip"), two = root.appendingPathComponent("two.zip")
        try zip([(path(first), firstData), ("../../outside", Data("bad".utf8))], url: one)
        XCTAssertThrowsError(try PoseArchiveInstaller.install(archiveURL: one, directory: directory, commit: commit, items: [first, second]) { _, _ in })
        XCTAssertEqual(PoseArchiveInstaller.safeRead(directory: directory, item: first), firstData)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("outside").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("complete").path))
        try zip([(path(second), secondData)], url: two)
        try PoseArchiveInstaller.install(archiveURL: two, directory: directory, commit: commit, items: [first, second]) { _, _ in }
        XCTAssertEqual(PoseArchiveInstaller.safeRead(directory: directory, item: second), secondData)
        XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("complete"), encoding: .utf8), commit)
    }
    func testRejectsMismatchedHashAndDoesNotWriteImage() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let good = Data("good".utf8), bad = Data("evil".utf8), item = item(1, good)
        let archive = root.appendingPathComponent("bad.zip"), directory = root.appendingPathComponent("library")
        try zip([(path(item), bad)], url: archive)
        XCTAssertThrowsError(try PoseArchiveInstaller.install(archiveURL: archive, directory: directory, commit: commit, items: [item]) { _, _ in })
        XCTAssertNil(PoseArchiveInstaller.safeRead(directory: directory, item: item))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("complete").path))
    }
    func testRejectsSymlinkWithoutTouchingDestination() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let directory = root.appendingPathComponent("library")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = Data("same".utf8), item = item(1, data), victim = root.appendingPathComponent("victim")
        try data.write(to: victim)
        try FileManager.default.createSymbolicLink(at: directory.appendingPathComponent(item.name), withDestinationURL: victim)
        let archive = root.appendingPathComponent("image.zip"); try zip([(path(item), data)], url: archive)
        XCTAssertThrowsError(try PoseArchiveInstaller.install(archiveURL: archive, directory: directory, commit: commit, items: [item]) { _, _ in })
        XCTAssertEqual(try Data(contentsOf: victim), data)
    }
}
