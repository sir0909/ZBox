import XCTest
@testable import ZboxCore

private actor TestPause {
    private var paused = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var observers: [CheckedContinuation<Void, Never>] = []
    func pause() async {
        paused = true
        for observer in observers { observer.resume() }
        observers.removeAll()
        await withCheckedContinuation { waiter = $0 }
    }
    func waitUntilPaused() async {
        if paused { return }
        await withCheckedContinuation { observers.append($0) }
    }
    func resume() { waiter?.resume(); waiter = nil }
}

/// Delays the first post-handshake command and the actual channel close independently.
private actor PausedCommandChannel: PtpIpByteChannel {
    let sendPause: TestPause
    let closePause: TestPause
    private let responder = FakeCamera.command()
    private var inbox: [Data] = []
    private(set) var sent: [Data] = []
    init(sendPause: TestPause, closePause: TestPause) {
        self.sendPause = sendPause; self.closePause = closePause
    }
    func send(_ data: Data) async throws {
        sent.append(data)
        inbox.append(contentsOf: responder(data))
        if data.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue,
           data.readLE(UInt16.self, at: 12) == 0x9001 {
            await sendPause.pause()
        }
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async { await closePause.pause() }
    func commands() -> [UInt16] {
        sent.filter { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue }
            .map { $0.readLE(UInt16.self, at: 12) }
    }
}

final class PtpIpConcurrencyTests: XCTestCase {
    func testTransactionMismatchInvalidatesBeforeNextCommand() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue,
               bytes.readLE(UInt16.self, at: 12) == 0x9001 {
                let tx = bytes.readLE(UInt32.self, at: 14)
                return [opResponse(PtpResponseCode.ok, tx &+ 1)]
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        do {
            _ = try await session.execute(opCode: 0x9001)
            XCTFail("transaction mismatch accepted")
        } catch PtpIpSessionError.transactionMismatch { }
        let info = await session.connectionInfo
        XCTAssertNil(info)
        do {
            _ = try await session.execute(opCode: 0x9002)
            XCTFail("command sent after corrupted transaction")
        } catch PtpIpSessionError.notConnected { }
        let sent = await command.sent
        let opCodes = sent.filter { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue }
            .map { $0.readLE(UInt16.self, at: 12) }
        XCTAssertFalse(opCodes.contains(0x9002))
    }

    func testQueuedOperationCannotSendAfterCloseBegins() async throws {
        let sendPause = TestPause(), closePause = TestPause()
        let command = PausedCommandChannel(sendPause: sendPause, closePause: closePause)
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        let first = Task { try await session.execute(opCode: 0x9001) }
        await sendPause.waitUntilPaused()
        let second = Task { try await session.execute(opCode: 0x9002) }
        // Allow the second request to reach the lock while the first command is blocked.
        try await Task.sleep(nanoseconds: 30_000_000)
        let closing = Task { await session.close() }
        await closePause.waitUntilPaused()
        let infoDuringClose = await session.connectionInfo
        XCTAssertNil(infoDuringClose)
        await sendPause.resume()
        _ = try? await first.value
        do {
            _ = try await second.value
            XCTFail("queued command should be rejected after close")
        } catch PtpIpSessionError.notConnected {}
        let sent = await command.commands()
        XCTAssertFalse(sent.contains(0x9002))
        await closePause.resume()
        await closing.value
    }
}
