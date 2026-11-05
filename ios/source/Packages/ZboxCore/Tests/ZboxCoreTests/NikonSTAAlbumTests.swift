import XCTest
@testable import ZboxCore

final class NikonSTAAlbumTests: XCTestCase {
    private func metadata(_ handle: UInt32, day: UInt8 = 2, month: UInt8 = 1) -> Data {
        le(UInt32(1)) + le(UInt32(1)) + le(handle) + le(UInt32(1)) +
        Data([0, 3, 4, 5, day, month]) + le(UInt16(2025))
    }
    func testMetadataUsesHandleKindAndNeverAssumesSize() throws {
        let item = try XCTUnwrap(NikonSTAAlbum.parseMetadata(metadata(0x29000042), storageID: 0x10001).first)
        XCTAssertEqual(item.placeholderName, "ZBox-29000042.JPG")
        XCTAssertEqual(item.capturedAt, "2025-01-02 05:04:03")
        XCTAssertEqual(item.storageID, 0x10001)
        XCTAssertEqual(NikonSTAAlbumItem.kind(for: 0x09000001), .raw)
        XCTAssertEqual(NikonSTAAlbumItem.kind(for: 0x61000001), .videoMP4)
        XCTAssertNil(NikonSTAAlbumItem.kind(for: 0x11000001))
        XCTAssertTrue(try NikonSTAAlbum.parseMetadata(metadata(0x11000001), storageID: 1).isEmpty)
        XCTAssertNil(try NikonSTAAlbum.parseMetadata(metadata(0x29000001, day: 31, month: 2), storageID: 1).first?.capturedAt)
    }
    func testTruncatedAndExcessiveListsAreRejected() throws {
        XCTAssertThrowsError(try NikonSTAAlbum.parseMetadata(Data(), storageID: 1))
        XCTAssertThrowsError(try NikonSTAAlbum.parseMetadata(le(UInt32(1)) + le(UInt32(100_001)), storageID: 1))
        XCTAssertThrowsError(try NikonSTAAlbum.parseMetadata(metadata(0x29000001).dropLast(), storageID: 1))
        XCTAssertThrowsError(try NikonSTAAlbum.parseStorageIDs(le(UInt32(65))))
        XCTAssertThrowsError(try NikonSTAAlbum.parseStorageIDs(le(UInt32(1))))
        XCTAssertEqual(try NikonSTAAlbum.parseStorageIDs(le(UInt32(1)) + le(UInt32(0x10001))), [0x10001])
    }
    func testSTAReadOnlyProtocolAnd64BitOffset() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                switch op {
                case PtpOpCode.getStorageIDs: return dataIn(tx, le(UInt32(1)) + le(UInt32(0x10001))) + [opResponse(PtpResponseCode.ok, tx)]
                case PtpOpCode.nikonObjectsMetadata:
                    let payload = le(UInt32(1)) + le(UInt32(1)) + le(UInt32(0x29000042)) + le(UInt32(0)) + Data([0, 3, 4, 5, 2, 1]) + le(UInt16(2025))
                    return dataIn(tx, payload) + [opResponse(PtpResponseCode.ok, tx)]
                case PtpOpCode.nikonObjectSize: return dataIn(tx, le(UInt64(123))) + [opResponse(PtpResponseCode.ok, tx)]
                case PtpOpCode.nikonPartialObjectEx: return dataIn(tx, Data([1, 2])) + [opResponse(PtpResponseCode.ok, tx)]
                default: break
                }
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        let ids = try await session.fetchStorageIDsForAlbum()
        let items = try await session.fetchNikonSTAMetadata(storageID: ids[0])
        let size = try await session.fetchNikonSTAObjectSize(handle: items[0].handle)
        let bytes = try await session.fetchNikonSTAPartial(handle: items[0].handle, offset: 0x1_0000_0002, length: 4)
        XCTAssertEqual(size, 123); XCTAssertEqual(bytes, Data([1, 2]))
        let sent = await command.sent
        let operations = sent.dropFirst().map { operationOf($0) }
        XCTAssertEqual(Array(operations.suffix(4).map { $0.opCode }), [0x1004, 0x9434, 0x9421, 0x9431])
        XCTAssertEqual(operations.last?.params, [0x29000042, 2, 1, 4, 0])
    }
    func testOversizedPartialInvalidatesConnection() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue,
               bytes.readLE(UInt16.self, at: 12) == PtpOpCode.nikonPartialObjectEx {
                let tx = bytes.readLE(UInt32.self, at: 14)
                return dataIn(tx, Data(repeating: 1, count: 5)) + [opResponse(PtpResponseCode.ok, tx)]
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        do { _ = try await session.fetchNikonSTAPartial(handle: 1, offset: 0, length: 4); XCTFail("over-return accepted") }
        catch PtpIpSessionError.dataLimitExceeded(let limit) { XCTAssertEqual(limit, 4) }
        let info = await session.connectionInfo
        XCTAssertNil(info)
    }
}
