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
    func testLocalSavePreservesEditablePointsAndPayloadAcrossReopen() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        let shared = ShareRecipe(name: "EXACT", points: [.init(x: 0, y: 5), .init(x: 101, y: 150), .init(x: 255, y: 240)])
        workbench.shareText = try RecipeShareCode.encode(shared)
        workbench.importShareCode()
        let originalPoints = workbench.points
        let originalPayload = try workbench.snapshot()
        workbench.save()
        XCTAssertEqual(workbench.points, originalPoints, "saving must not resample the live draft")
        XCTAssertEqual(try workbench.snapshot(), originalPayload)
        let reopened = RecipeWorkbench(storeURL: url)
        reopened.use(try XCTUnwrap(reopened.entries.first))
        XCTAssertEqual(reopened.points, originalPoints)
        XCTAssertEqual(try reopened.snapshot(), originalPayload)
        reopened.setPoint(1, y: 160)
        XCTAssertEqual(try PictureControl614.parse(reopened.snapshot()).curve, try CurveLUT.make15(reopened.points))
    }

    func testMoveAndRevertRestoresOriginalIrregularFifteenBitLUT() throws {
        let workbench = RecipeWorkbench(storeURL: temporaryStore())
        var original = RecipePayload.linearTemplate()
        original[0x67] = 7
        original[0x66 + 33 * 2] = 0x2a
        original[0x67 + 33 * 2] = 0xbc // irregular value between visible sampled points
        workbench.importBytes(original)
        let savedPoints = workbench.points
        let y = Double(savedPoints[2].y)
        workbench.setPoint(2, y: y + 12)
        XCTAssertNotEqual(try workbench.snapshot(), original)
        workbench.setPoint(2, y: y)
        XCTAssertEqual(workbench.points, savedPoints)
        XCTAssertEqual(try workbench.snapshot(), original)
        XCTAssertEqual(try workbench.previewLUT(), try RecipePayload.lut8(original))
        // This also holds after the editing points and 614B have been archived.
        let archived = try workbench.exportArchive()
        let reopened = RecipeWorkbench(storeURL: temporaryStore())
        reopened.importBytes(archived)
        reopened.setPoint(2, y: y + 12); reopened.setPoint(2, y: y)
        XCTAssertEqual(try reopened.snapshot(), original)
    }

    func testInvalidStoredEditingPointsNeverOverwriteLibrary() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let json: [[String: Any]] = [["id": UUID().uuidString, "name": "OLD", "payload": RecipePayload.linearTemplate().base64EncodedString(),
                                     "editPoints": [["x": 0, "y": 0], ["x": 0, "y": 10]], "savedAt": 0]]
        let bytes = try JSONSerialization.data(withJSONObject: json); try bytes.write(to: url)
        let workbench = RecipeWorkbench(storeURL: url)
        XCTAssertFalse(workbench.storeHealthy)
        workbench.save()
        XCTAssertEqual(try Data(contentsOf: url), bytes)
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
    func testImportedLUTDrivesPreviewUntilCurveEdited() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        var values = (0...255).map(UInt8.init)
        values[33] = 90 // Not one of the sampled control points.
        let imported = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "IMPORTED", lut: values)
        workbench.importBytes(imported)
        XCTAssertEqual(try workbench.previewLUT(), values)
        XCTAssertNotEqual(try CurveLUT.make(workbench.points), values)
        workbench.setPoint(2, y: 150)
        XCTAssertEqual(try workbench.previewLUT(), try RecipePayload.lut8(workbench.snapshot()))
    }

    func testBuiltInMonochromeFamilyAndMetadataSurviveLocalSave() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        let preset = try XCTUnwrap(BuiltInPresetCatalog.load().first { $0.family == 1 })
        workbench.useBuiltIn(preset)
        XCTAssertEqual(workbench.presetFamily, 1)
        XCTAssertTrue(try XCTUnwrap(workbench.previewAdjustments()).monochrome)
        workbench.save()
        let reopened = RecipeWorkbench(storeURL: url)
        let entry = try XCTUnwrap(reopened.entries.first)
        reopened.use(entry)
        XCTAssertEqual(reopened.presetFamily, 1)
        XCTAssertEqual(reopened.metadata, preset.shareMetadata)
        XCTAssertTrue(try XCTUnwrap(reopened.previewAdjustments()).monochrome)
        XCTAssertEqual(try reopened.snapshot(), entry.payload)
    }
    func testLegacyLocalEntryWithoutFamilyStillLoads() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let oldJSON: [[String: Any]] = [["id": UUID().uuidString, "name": "OLD", "payload": RecipePayload.linearTemplate().base64EncodedString(), "savedAt": 0]]
        try JSONSerialization.data(withJSONObject: oldJSON).write(to: url)
        let workbench = RecipeWorkbench(storeURL: url)
        XCTAssertTrue(workbench.storeHealthy)
        let entry = try XCTUnwrap(workbench.entries.first)
        XCTAssertNil(entry.family)
        workbench.use(entry)
        XCTAssertTrue(try XCTUnwrap(workbench.previewAdjustments()).isNeutral)
    }

    func testLocalColorEditsAffectPreviewAndShareButNotPayload() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        let original = try workbench.snapshot()
        workbench.setPreviewValue(.saturation, value: 1.7)
        workbench.setPreviewValue(.brightness, value: -20)
        XCTAssertEqual(workbench.previewValue(.saturation), 1.5)
        XCTAssertEqual(workbench.previewValue(.brightness), -2)
        XCTAssertFalse(try XCTUnwrap(workbench.previewAdjustments()).isNeutral)
        XCTAssertEqual(try workbench.snapshot(), original)
        workbench.makeShareCode()
        let shared = try RecipeShareCode.decode(workbench.shareText)
        XCTAssertEqual(shared.saturation, 1.5); XCTAssertEqual(shared.brightness, -2)
        workbench.save()
        let loaded = RecipeWorkbench(storeURL: url)
        loaded.use(try XCTUnwrap(loaded.entries.first))
        XCTAssertEqual(loaded.previewValue(.saturation), 1.5)
        XCTAssertEqual(loaded.previewValue(.brightness), -2)
        loaded.resetPreviewColors()
        XCTAssertTrue(try XCTUnwrap(loaded.previewAdjustments()).isNeutral)
        XCTAssertEqual(try loaded.snapshot(), original)
    }

    func testLegacyNameBeginningWithBraceIsNotMistakenForArchive() throws {
        let workbench = RecipeWorkbench(storeURL: temporaryStore())
        var original = Data(repeating: 0, count: 84)
        original.replaceSubrange(0..<7, with: Data("{LEGACY".utf8))
        for i in 0..<18 { original[0x1a + i] = UInt8(i * 15) }
        workbench.importBytes(original)
        XCTAssertEqual(workbench.name, "{LEGACY")
        XCTAssertEqual(workbench.legacyOriginal, original)
        XCTAssertEqual(try PictureControl614.parse(workbench.snapshot()).name, "{LEGACY")
        let archived = try workbench.exportArchive()
        let reopened = RecipeWorkbench(storeURL: temporaryStore())
        reopened.importBytes(archived)
        XCTAssertEqual(reopened.legacyOriginal, original)
        XCTAssertEqual(reopened.name, "{LEGACY")
    }

    func testLegacyMonochromeTypeIsLocalMetadataNotPayload() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        var old = Data(repeating: 0, count: 84)
        old.replaceSubrange(0..<4, with: Data("MONO".utf8)); old[0x18] = 1
        for i in 0..<18 { old[0x1a + i] = UInt8(i * 15) }
        old[0x30] = 0xb7 // Opaque bytes outside curve A survive independently of conversion.
        workbench.importBytes(old)
        XCTAssertEqual(workbench.legacyOriginal, old)
        XCTAssertEqual(workbench.presetFamily, 1)
        XCTAssertEqual(workbench.metadata?.profile, "MONOCHROME")
        XCTAssertTrue(try XCTUnwrap(workbench.previewAdjustments()).monochrome)
        XCTAssertEqual(try workbench.snapshot()[0x2c], 5) // fallback template, not the old family byte
        workbench.makeShareCode()
        XCTAssertEqual(try RecipeShareCode.decode(workbench.shareText).profile, "MONOCHROME")
        workbench.setPoint(2, y: 165)
        let archived = try workbench.exportArchive()
        let archiveCopy = RecipeWorkbench(storeURL: temporaryStore())
        archiveCopy.importBytes(archived)
        XCTAssertEqual(archiveCopy.legacyOriginal, old)
        workbench.save()
        let reopened = RecipeWorkbench(storeURL: url)
        reopened.use(try XCTUnwrap(reopened.entries.first))
        XCTAssertEqual(reopened.presetFamily, 1)
        XCTAssertEqual(reopened.legacyOriginal, old)
    }

    func testAIColorSuggestionIsPreviewOnlyAndDraftIdentityTracksChanges() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        let identity = workbench.draftIdentity()
        workbench.setPreviewValue(.saturation, value: 1)
        XCTAssertNotEqual(identity, workbench.draftIdentity())
        let json = """
        {"preset_name":"AI_CURVE","tone_curve_points":[{"x":0,"y":0},{"x":255,"y":255}],"style_analysis":"ok","preview_adjustments":{"brightness":0.5,"contrast":-1,"saturation":2,"hue":1}}
        """
        let result = try AIRecipe.parse(Data(json.utf8))
        try workbench.useAI(result)
        XCTAssertEqual(workbench.previewValue(.saturation), 2)
        XCTAssertEqual(workbench.previewValue(.brightness), 0.5)
        XCTAssertEqual(try workbench.snapshot(), try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "AI_CURVE"))
        let unchanged = workbench.draftIdentity()
        var sameVisibleCurve = try workbench.snapshot()
        sameVisibleCurve[0x67] = 7
        workbench.importBytes(sameVisibleCurve)
        XCTAssertNotEqual(unchanged, workbench.draftIdentity())
    }

    func testEditedCurveUsesFifteenBitPrecisionAndNoOpPreservesImportedBytes() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let workbench = RecipeWorkbench(storeURL: url)
        var original = RecipePayload.linearTemplate(); original[0x67] = 7; original[0x50] = 0xab
        workbench.importBytes(original)
        workbench.setPoint(2, y: Double(workbench.points[2].y))
        XCTAssertEqual(try workbench.snapshot(), original)
        workbench.setPoint(2, y: 150)
        let edited = try workbench.snapshot()
        let lut15 = try CurveLUT.make15(workbench.points)
        XCTAssertEqual(try PictureControl614.parse(edited).curve, lut15)
        XCTAssertEqual(edited[0x50], 0xab)
        XCTAssertEqual(try workbench.previewLUT(), try RecipePayload.lut8(edited))
        let exported = try RecipePayload.exportNCP(edited)
        XCTAssertEqual(try RecipePayload.importFile(exported).payload, edited)
    }

    func testZBoxArchiveRestoresPayloadEditingPointsAndPreviewMetadata() throws {
        let url = temporaryStore(); defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let source = RecipeWorkbench(storeURL: url)
        let preset = try XCTUnwrap(BuiltInPresetCatalog.load().first { $0.family == 1 })
        source.useBuiltIn(preset)
        source.name = "EXPORTED"
        source.setPoint(2, y: 150)
        source.setPreviewValue(.hue, value: -1.5)
        let original = try source.snapshot(), expectedPoints = source.points
        let file = try source.exportArchive()
        let reopened = RecipeWorkbench(storeURL: temporaryStore())
        reopened.importBytes(file)
        XCTAssertEqual(try reopened.snapshot(), original)
        XCTAssertEqual(reopened.points, expectedPoints)
        XCTAssertEqual(reopened.presetFamily, 1)
        XCTAssertEqual(reopened.previewValue(.hue), -1.5)
        XCTAssertTrue(try XCTUnwrap(reopened.previewAdjustments()).monochrome)
        let before = reopened.draftIdentity()
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: file) as? [String: Any])
        json["sha256"] = String(repeating: "0", count: 64)
        reopened.importBytes(try JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(reopened.draftIdentity(), before)
    }

}
