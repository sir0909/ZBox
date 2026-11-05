import XCTest
@testable import ZboxCore

final class PtpIpPacketTests: XCTestCase {
    func testOperationRequestWithoutParams() {
        let bytes = PtpIpCodec.operationRequest(dataPhase: 1, opCode: 0x1002, transactionId: 1)
        XCTAssertEqual([UInt8](bytes), [
            0x12, 0x00, 0x00, 0x00,   // length = 8 + 10
            0x06, 0x00, 0x00, 0x00,   // type = OperationRequest
            0x01, 0x00, 0x00, 0x00,   // data phase
            0x02, 0x10,               // OpenSession
            0x01, 0x00, 0x00, 0x00,   // transaction id
        ])
    }

    func testInitCommandRequestLayout() throws {
        let guid = Data(repeating: 0xAB, count: 16)
        let bytes = try PtpIpCodec.initCommandRequest(guid: guid, hostName: "Z")
        // 8 头 + 16 GUID + 2 ('Z') + 2 (NUL) + 4 版本 = 32
        XCTAssertEqual(bytes.count, 32)
        XCTAssertEqual(Array(bytes.prefix(8)), [0x20, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00])
        XCTAssertEqual(Array(bytes[24..<26]), [0x5A, 0x00])
        XCTAssertEqual(Array(bytes.suffix(4)), [0x00, 0x00, 0x01, 0x00])
    }

    func testInitCommandRequestRejectsWrongGuidLength() {
        XCTAssertThrowsError(try PtpIpCodec.initCommandRequest(guid: Data(count: 8), hostName: "Zbox")) { error in
            XCTAssertEqual(error as? PtpError, PtpError.invalidGuidLength(8))
        }
    }

    func testFrameReaderHandlesFragmentedStream() throws {
        let packet = PtpIpCodec.frame(.initFail, payload: Data([0x01, 0x00, 0x00, 0x00]))
        var reader = PtpIpFrameReader()
        reader.append(packet.prefix(5))
        let early = try reader.nextPacket()
        XCTAssertNil(early)
        reader.append(packet.suffix(from: 5))
        let complete = try XCTUnwrap(reader.nextPacket())
        XCTAssertEqual(complete.type, PtpIpPacketType.initFail.rawValue)
        XCTAssertEqual(PtpIpCodec.initFailReason(in: complete), PtpIpInitFailReason.rejected)
        XCTAssertEqual(reader.pendingBytes, 0)
    }

    func testFrameReaderSplitsConcatenatedPackets() throws {
        let first = PtpIpCodec.frame(.probeRequest)
        let second = PtpIpCodec.frame(.probeResponse)
        var reader = PtpIpFrameReader()
        reader.append(first + second)
        XCTAssertEqual(try reader.nextPacket()?.type, PtpIpPacketType.probeRequest.rawValue)
        XCTAssertEqual(try reader.nextPacket()?.type, PtpIpPacketType.probeResponse.rawValue)
        XCTAssertNil(try reader.nextPacket())
    }

    func testParseInitCommandAck() throws {
        var payload = Data()
        payload.appendLE(UInt32(0x2A))
        payload.append(Data(repeating: 0x11, count: 16))
        for unit in "Nikon Z 30".utf16 { payload.appendLE(unit) }
        payload.appendLE(UInt16(0))
        let packet = PtpIpPacket(type: PtpIpPacketType.initCommandAck.rawValue, payload: payload)
        let ack = try PtpIpCodec.parseInitCommandAck(packet)
        XCTAssertEqual(ack.connectionNumber, 0x2A)
        XCTAssertEqual(ack.cameraGuid, Data(repeating: 0x11, count: 16))
        XCTAssertEqual(ack.cameraName, "Nikon Z 30")
    }
}
