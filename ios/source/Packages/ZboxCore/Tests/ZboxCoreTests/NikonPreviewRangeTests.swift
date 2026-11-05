import XCTest
@testable import ZboxCore

final class NikonPreviewRangeTests: XCTestCase {
    private func write16(_ b: inout [UInt8], _ p: Int, _ n: UInt16) {
        b[p] = UInt8(truncatingIfNeeded: n); b[p + 1] = UInt8(truncatingIfNeeded: n >> 8)
    }
    private func write32(_ b: inout [UInt8], _ p: Int, _ n: UInt32) {
        for i in 0..<4 { b[p + i] = UInt8(truncatingIfNeeded: n >> (8 * i)) }
    }
    private func entry(_ b: inout [UInt8], _ p: Int, _ tag: UInt16, _ type: UInt16, _ count: UInt32, _ value: UInt32) {
        write16(&b, p, tag); write16(&b, p + 2, type); write32(&b, p + 4, count); write32(&b, p + 8, value)
    }
    private func makeRaw() -> Data {
        var b = [UInt8](repeating: 0, count: 150)
        b[0] = 0x49; b[1] = 0x49; write16(&b, 2, 42); write32(&b, 4, 8)
        write16(&b, 8, 1); entry(&b, 10, 0x8769, 4, 1, 32)
        write16(&b, 32, 1); entry(&b, 34, 0x927c, 7, 80, 60)
        b.replaceSubrange(60..<66, with: [78, 105, 107, 111, 110, 0])
        b[70] = 0x49; b[71] = 0x49; write16(&b, 72, 42); write32(&b, 74, 8)
        write16(&b, 78, 1); entry(&b, 80, 0x0011, 4, 1, 26)
        write16(&b, 96, 2); entry(&b, 98, 0x0201, 4, 1, 150_000)
        entry(&b, 110, 0x0202, 4, 1, 100)
        return Data(b)
    }
    private func makeMPF() -> Data {
        var b = [UInt8](repeating: 0, count: 68)
        b[0] = 0xff; b[1] = 0xd8; b[2] = 0xff; b[3] = 0xe2; b[4] = 0; b[5] = 64
        b.replaceSubrange(6..<10, with: [77, 80, 70, 0])
        b[10] = 0x49; b[11] = 0x49; write16(&b, 12, 42); write32(&b, 14, 8)
        write16(&b, 18, 1); entry(&b, 20, 0xb002, 7, 32, 26)
        write32(&b, 56, 200); write32(&b, 60, 300_000)
        return Data(b)
    }
    func testMakerNotePreviewOffsetIsRelativeToEmbeddedTiff() {
        XCTAssertEqual(NikonPreviewRange.largest(in: makeRaw(), fileSize: 200_000), 150_070..<150_170)
    }
    func testMPFOffsetIsRelativeToMPFTiffAndFirstEntryIsIgnored() {
        XCTAssertEqual(NikonPreviewRange.largest(in: makeMPF(), fileSize: 400_000), 300_010..<300_210)
    }
    func testRejectsRangeOutsideFileOrMetadataOutsidePrefix() {
        XCTAssertNil(NikonPreviewRange.largest(in: makeRaw(), fileSize: 150_100))
        XCTAssertNil(NikonPreviewRange.largest(in: makeRaw().prefix(100), fileSize: 200_000))
        XCTAssertNil(NikonPreviewRange.largest(in: makeMPF().dropLast(), fileSize: 400_000))
    }
    func testRejectsMalformedMakerAndOversizedMPF() {
        var raw = [UInt8](makeRaw()); raw[60] = 0
        XCTAssertNil(NikonPreviewRange.largest(in: Data(raw), fileSize: 200_000))
        var mpf = [UInt8](makeMPF()); write32(&mpf, 56, 500_000)
        XCTAssertNil(NikonPreviewRange.largest(in: Data(mpf), fileSize: 900_000))
    }
}
