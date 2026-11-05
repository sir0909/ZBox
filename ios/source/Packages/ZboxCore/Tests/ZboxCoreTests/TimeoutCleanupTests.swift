import Foundation
import XCTest
@testable import ZboxCore

/// Simulates a socket receive that ignores task cancellation and only ends on close.
actor CloseUnblocksChannel: PtpIpByteChannel {
    private var continuation: CheckedContinuation<Data, Never>?
    private var isClosed = false

    func send(_ data: Data) async throws {}
    func receive() async throws -> Data {
        if isClosed { return Data() }
        return await withCheckedContinuation { continuation = $0 }
    }
    func closed() -> Bool { isClosed }
    func close() async {
        isClosed = true
        continuation?.resume(returning: Data())
        continuation = nil
    }
}

final class TimeoutCleanupTests: XCTestCase {
    func testTimeoutClosesChannelBeforeTaskGroupReturns() async throws {
        let channel = CloseUnblocksChannel()
        do {
            let _: Data = try await withPtpIpTimeout(stage: "test", seconds: 0.1, onTimeout: {
                await channel.close()
            }) {
                try await channel.receive()
            }
            XCTFail("expected timeout")
        } catch PtpIpSessionError.timeout(let stage) {
            XCTAssertEqual(stage, "test")
        }
    }

    func testCompletedOperationDoesNotCloseChannel() async throws {
        let channel = CloseUnblocksChannel()
        let result: Int = try await withPtpIpTimeout(stage: "test", seconds: 1, onTimeout: {
            await channel.close()
        }) { 42 }
        XCTAssertEqual(result, 42)
        let closed = await channel.closed()
        XCTAssertFalse(closed)
    }
}
