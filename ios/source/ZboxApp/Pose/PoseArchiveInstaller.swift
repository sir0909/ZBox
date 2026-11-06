import Foundation
import ZIPFoundation
import ZboxCore

/// Archive entries are NEVER extracted by their path. Only exact catalog names are read, validated and saved.
enum PoseArchiveInstaller {
    enum Failure: Error { case malformed, incomplete, unsafePath }
    static func install(archiveURL: URL, directory: URL, commit: String, items: [PoseCatalog.Item],
                        progress: @escaping @Sendable (Int, Int) -> Void) throws {
        guard commit == PoseCatalog.pinnedCommit, !items.isEmpty,
              Set(items.map(\.name)).count == items.count, items.allSatisfy(PoseCatalog.isSafeItem) else { throw Failure.malformed }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try checkDirectory(directory)
        let expected = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0) })
        var verified = Set<String>()
        for item in items {
            try Task.checkCancellation()
            if let data = safeRead(directory: directory, item: item), PoseCatalog.matchesBlob(item, data: data) { verified.insert(item.name) }
        }
        progress(verified.count, items.count)
        let archive = try Archive(url: archiveURL, accessMode: .read)
        let prefix = "poze-\(commit)/assets/images/pose_samples/"
        var visited = Set<String>(), entryCount = 0
        for entry in archive {
            try Task.checkCancellation()
            entryCount += 1
            guard entryCount <= 6000 else { throw Failure.malformed }
            guard entry.path.hasPrefix(prefix), let item = expected[String(entry.path.dropFirst(prefix.count))] else { continue }
            guard entry.type == .file, entry.uncompressedSize == UInt64(item.bytes), visited.insert(item.name).inserted else { throw Failure.malformed }
            if verified.contains(item.name) { continue }
            var bytes = Data(); bytes.reserveCapacity(item.bytes)
            let crc = try archive.extract(entry, bufferSize: 32_768) { chunk in
                try Task.checkCancellation()
                guard chunk.count <= item.bytes - bytes.count else { throw Failure.malformed }
                bytes.append(chunk)
            }
            guard crc == entry.checksum, PoseCatalog.matchesBlob(item, data: bytes) else { throw Failure.malformed }
            try checkDirectory(directory)
            let target = directory.appendingPathComponent(item.name)
            try rejectSymbolicLink(target)
            try bytes.write(to: target, options: [.atomic, .completeFileProtectionUnlessOpen])
            guard let reread = safeRead(directory: directory, item: item), PoseCatalog.matchesBlob(item, data: reread) else { throw Failure.malformed }
            verified.insert(item.name)
            if verified.count % 40 == 0 || verified.count == items.count { progress(verified.count, items.count) }
        }
        guard verified.count == items.count else { throw Failure.incomplete }
        // This marker is a record of the completed verification, not a guarantee files remain intact later.
        let marker = directory.appendingPathComponent("complete")
        try rejectSymbolicLink(marker)
        try Data(commit.utf8).write(to: marker, options: [.atomic, .completeFileProtectionUnlessOpen])
        progress(verified.count, items.count)
    }
    static func safeRead(directory: URL, item: PoseCatalog.Item) -> Data? {
        guard PoseCatalog.isSafeItem(item), (try? checkDirectory(directory)) != nil else { return nil }
        let url = directory.appendingPathComponent(item.name)
        guard (try? rejectSymbolicLink(url)) != nil,
              let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber,
              size.intValue == item.bytes else { return nil }
        return try? Data(contentsOf: url)
    }
    private static func checkDirectory(_ url: URL) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard attributes[.type] as? FileAttributeType == .typeDirectory else { throw Failure.unsafePath }
    }
    private static func rejectSymbolicLink(_ url: URL) throws {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular else { throw Failure.unsafePath }
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            // An absent target is safe to create. Other errors must fail closed.
        }
    }
}
