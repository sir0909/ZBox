import XCTest
@testable import ZboxCore

final class LiveViewFrameTests: XCTestCase {
    func testExtractsJpegBetweenNikonPrefixAndSuffix() throws {
        let payload = Data([0x01, 0x02, 0xff, 0xd8, 0x10, 0xff, 0xd9, 0x03])
        XCTAssertEqual(try LiveViewFrame.jpeg(from: payload), Data([0xff, 0xd8, 0x10, 0xff, 0xd9]))
    }

    func testRejectsMissingOrTruncatedMarkers() {
        for payload in [Data(), Data([0xff, 0xd8]), Data([0x01, 0xff, 0xd8, 0x02]), Data([0xff, 0xd9])] {
            XCTAssertThrowsError(try LiveViewFrame.jpeg(from: payload))
        }
    }

    func testRejectsOversizedJpeg() {
        var payload = Data([0xff, 0xd8])
        payload.append(Data(repeating: 0, count: LiveViewFrame.maxJPEGBytes))
        payload.append(contentsOf: [0xff, 0xd9])
        XCTAssertThrowsError(try LiveViewFrame.jpeg(from: payload))
    }

    func testLiveViewOperationOrderAndResponseHandling() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.nikonGetLiveViewImage {
                    return dataIn(tx, Data([0, 0xff, 0xd8, 1, 0xff, 0xd9])) + [opResponse(PtpResponseCode.ok, tx)]
                }
                if op == PtpOpCode.nikonStartLiveView || op == PtpOpCode.nikonEndLiveView {
                    return [opResponse(PtpResponseCode.ok, tx)]
                }
            }
            return fallback(bytes)
        }
        let event = ScriptedChannel(FakeCamera.event)
        let session = PtpIpSession(commandChannel: command, openEventChannel: { event },
                                   config: testConfig(), settle: { _ in }, randomSource: { 5 })
        _ = try await session.connect()
        try await session.startLiveView()
        let image = try await session.fetchLiveViewJPEG()
        XCTAssertEqual(image, Data([0xff, 0xd8, 1, 0xff, 0xd9]))
        try await session.stopLiveView()
        let sent = await command.sent
        let operations = sent.dropFirst().map { operationOf($0).opCode }
        XCTAssertEqual(operations.suffix(3), [0x9201, 0x9203, 0x9202])
    }

    func testStartRejectsUnsupportedResponse() async throws {
        let session = PtpIpSession(commandChannel: ScriptedChannel(FakeCamera.command()),
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        do { try await session.startLiveView(); XCTFail("should reject camera response") }
        catch PtpIpSessionError.operationFailed(let code, let op) {
            XCTAssertEqual(code, PtpResponseCode.operationNotSupported)
            XCTAssertEqual(op, PtpOpCode.nikonStartLiveView)
        }
    }
    func testMultipleFramesRemainOrderedBeforeStop() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.nikonGetLiveViewImage {
                    return dataIn(tx, Data([0xff, 0xd8, UInt8(tx & 0xff), 0xff, 0xd9])) + [opResponse(PtpResponseCode.ok, tx)]
                }
                if op == PtpOpCode.nikonStartLiveView || op == PtpOpCode.nikonEndLiveView {
                    return [opResponse(PtpResponseCode.ok, tx)]
                }
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        try await session.startLiveView()
        for _ in 0..<3 {
            let frame = try await session.fetchLiveViewJPEG()
            XCTAssertEqual(frame.count, 5)
        }
        try await session.stopLiveView()
        let sent = await command.sent
        let operations = sent.dropFirst().map { operationOf($0).opCode }
        XCTAssertEqual(Array(operations.suffix(5)), [0x9201, 0x9203, 0x9203, 0x9203, 0x9202])
    }

    func testStopFailureIsNotReportedAsSuccess() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.nikonEndLiveView {
                    return [opResponse(PtpResponseCode.operationNotSupported, tx)]
                }
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        do { try await session.stopLiveView(); XCTFail("stop should fail") }
        catch PtpIpSessionError.operationFailed(let code, let op) {
            XCTAssertEqual(code, PtpResponseCode.operationNotSupported)
            XCTAssertEqual(op, PtpOpCode.nikonEndLiveView)
        }
    }

}
