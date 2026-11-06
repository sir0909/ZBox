import Foundation
import XCTest
import ZboxCore
@testable import Zbox

@MainActor
final class USBReadOnlyProbeRunnerTests: XCTestCase {
    private func drain() async {
        await withCheckedContinuation { c in DispatchQueue.main.async { c.resume() } }
    }
    private func dataset() -> Data {
        func le<T: FixedWidthInteger>(_ value: T) -> Data {
            var value = value.littleEndian
            return withUnsafeBytes(of: &value) { Data($0) }
        }
        func str(_ value: String) -> Data {
            let chars = Array(value.utf16) + [0]
            return Data([UInt8(chars.count)]) + chars.reduce(into: Data()) { $0 += le($1) }
        }
        var d = le(UInt16(100)) + le(UInt32(10)) + le(UInt16(100)) + Data([0]) + le(UInt16(0))
        d += le(UInt32(1)) + le(UInt16(0x1001))
        for _ in 0..<4 { d += le(UInt32(0)) }
        d += str("Nikon") + str("Z 30") + str("1.0") + str("123")
        return d
    }
    func testFakeDriverEndToEndDoesNotReadWithoutExplicitAction() async throws {
        let driver = FakeUSBProbeDriver(), runner = USBReadOnlyProbeRunner(driver: driver)
        runner.start(); runner.start()
        let id = try XCTUnwrap(runner.probe.token)
        XCTAssertEqual(driver.opens, [id]); XCTAssertTrue(driver.reads.isEmpty)
        driver.events[id]?(.catalogReady); driver.events[id]?(.opened)
        await drain()
        XCTAssertEqual(runner.probe.phase, .ready)
        XCTAssertTrue(driver.reads.isEmpty)
        runner.readDeviceInfo(); runner.readDeviceInfo()
        let read = try XCTUnwrap(driver.reads.first)
        XCTAssertEqual(driver.reads.count, 1)
        XCTAssertEqual(read.command, PtpContainer.command(code: 0x1001, transactionId: read.tx))
        let response = PtpContainer.encode(type: .response, code: 0x2001, transactionId: read.tx, body: Data())
        driver.completions[read.tx]?(.success(USBProbeDriverReply(response: response, data: dataset(), format: .bareDataset)))
        await drain()
        XCTAssertEqual(runner.probe.phase, .ready); XCTAssertEqual(runner.probe.deviceInfo?.model, "Z 30")
        runner.stop(); runner.stop()
        XCTAssertEqual(driver.closes, [id])
        driver.events[id]?(.closed(success: true)); await drain()
        XCTAssertEqual(runner.probe.phase, .idle)
    }
    func testLateOpenAfterStopClosesAndStaleEventsCannotPolluteNewSession() async throws {
        let driver = FakeUSBProbeDriver(), runner = USBReadOnlyProbeRunner(driver: driver)
        runner.start(); let old = try XCTUnwrap(runner.probe.token)
        runner.stop(); runner.start()
        XCTAssertEqual(driver.opens.count, 1); XCTAssertTrue(driver.closes.isEmpty)
        driver.events[old]?(.closed(success: true)); await drain()
        XCTAssertEqual(runner.probe.phase, .closing)
        driver.events[old]?(.opened); await drain()
        XCTAssertEqual(driver.closes, [old])
        driver.events[old]?(.closed(success: true)); await drain()
        runner.start(); let new = try XCTUnwrap(runner.probe.token)
        XCTAssertNotEqual(old, new)
        driver.events[old]?(.opened); driver.events[old]?(.catalogReady); driver.events[old]?(.removed)
        driver.events[new]?(.opened); driver.events[new]?(.catalogReady); await drain()
        XCTAssertEqual(runner.probe.token, new); XCTAssertEqual(runner.probe.phase, .ready)
        XCTAssertEqual(driver.closes, [old]); XCTAssertTrue(driver.reads.isEmpty)
        runner.stop(); driver.events[new]?(.closed(success: true)); await drain()
    }
    func testReadTimeoutClosesOnceAndRejectsLateResult() async throws {
        let driver = FakeUSBProbeDriver(), runner = USBReadOnlyProbeRunner(driver: driver, timeoutNanoseconds: 200_000_000)
        runner.start(); let id = try XCTUnwrap(runner.probe.token)
        driver.events[id]?(.opened); driver.events[id]?(.catalogReady); await drain()
        runner.readDeviceInfo(); let read = try XCTUnwrap(driver.reads.first)
        try await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertEqual(runner.probe.phase, .closing)
        XCTAssertEqual(runner.probe.lastFailure, .timeout); XCTAssertEqual(driver.closes, [id])
        let response = PtpContainer.encode(type: .response, code: 0x2001, transactionId: read.tx, body: Data())
        driver.completions[read.tx]?(.success(USBProbeDriverReply(response: response, data: dataset(), format: .bareDataset)))
        await drain()
        XCTAssertNil(runner.probe.deviceInfo); XCTAssertEqual(driver.reads.count, 1)
        driver.events[id]?(.closed(success: true)); await drain()
    }
}
@MainActor
private final class FakeUSBProbeDriver: USBReadOnlyProbeDriver {
    struct Read { let token: UUID; let tx: UInt32; let command: Data }
    var opens: [UUID] = []
    var reads: [Read] = []
    var closes: [UUID] = []
    var events: [UUID: (USBProbeDriverEvent) -> Void] = [:]
    var completions: [UInt32: (Result<USBProbeDriverReply, Error>) -> Void] = [:]
    func open(token: UUID, events callback: @escaping (USBProbeDriverEvent) -> Void) { opens.append(token); events[token] = callback }
    func readDeviceInfo(token: UUID, transactionID: UInt32, command: Data,
                        completion: @escaping (Result<USBProbeDriverReply, Error>) -> Void) {
        reads.append(Read(token: token, tx: transactionID, command: command)); completions[transactionID] = completion
    }
    func close(token: UUID) { closes.append(token) }
}
