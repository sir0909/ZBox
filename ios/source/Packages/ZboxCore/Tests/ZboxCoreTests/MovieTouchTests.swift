import XCTest
@testable import ZboxCore

private func header(x: Int = 320, y: Int = 240, zoom: Bool = false) -> Data {
    var d = Data(repeating: 0, count: 32)
    func put(_ offset: Int, _ n: Int) { d[offset] = UInt8(n >> 8); d[offset + 1] = UInt8(n & 255) }
    put(8, 640); put(10, 480); put(12, zoom ? 320 : 640); put(14, 480)
    put(16, 640); put(18, 480); put(20, 320); put(22, 240)
    put(24, 40); put(26, 40); put(28, x); put(30, y)
    return d
}
private func afDescriptor(_ mode: UInt16 = 0x8010) -> Data {
    var d = le(UInt16(0xd05d)) + le(UInt16(4)) + Data([1])
    d += le(mode) + le(mode) + Data([2]) + le(UInt16(1)) + le(mode)
    return d
}
private actor MovieTouchCamera: PtpIpByteChannel {
    private let fallback = FakeCamera.command(deviceInfo: deviceInfoDataset(operations: [0x1001,0x1002,0x1014,0x1015,0x90c1,0x90c8,0x9203,0x9205,0x920a,0x920b]))
    private var inbox: [Data] = []
    private var ops: [UInt16] = []
    private let mode: UInt8, prohibit: UInt32, canVerify: Bool, dropResponse: UInt16?
    init(mode: UInt8 = 1, prohibit: UInt32 = 0, canVerify: Bool = true, dropResponse: UInt16? = nil) {
        self.mode = mode; self.prohibit = prohibit; self.canVerify = canVerify; self.dropResponse = dropResponse
    }
    func send(_ data: Data) async throws {
        if data.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
            let op = data.readLE(UInt16.self, at: 12), tx = data.readLE(UInt32.self, at: 14)
            ops.append(op)
            if op == 0x1015 {
                let property = data.readLE(UInt32.self, at: 18)
                let value = property == 0xd0a4 ? le(prohibit) : Data([mode])
                inbox += dataIn(tx, value) + [opResponse(PtpResponseCode.ok, tx)]; return
            }
            if op == 0x1014 { inbox += dataIn(tx, afDescriptor()) + [opResponse(PtpResponseCode.ok, tx)]; return }
            if op == 0x9203 {
                let moved = ops.contains(0x9205) && canVerify
                inbox += dataIn(tx, header(x: moved ? 319 : 0, y: moved ? 239 : 0) + Data([0xff,0xd8,0xff,0xd9])) + [opResponse(PtpResponseCode.ok, tx)]; return
            }
            if [0x90c1,0x90c8,0x9205,0x920a,0x920b].contains(op) {
                if op != dropResponse { inbox += [opResponse(PtpResponseCode.ok, tx)] }; return
            }
        }
        inbox += fallback(data)
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
    func operations() -> [UInt16] { ops }
}
private actor CommitTracker {
    var didCommit = false
    func commit() -> Bool { didCommit = true; return true }
}
final class MovieTouchTests: XCTestCase {
    private func session(_ c: MovieTouchCamera) async throws -> PtpIpSession {
        let s = PtpIpSession(commandChannel: c, openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await s.connect(); _ = try await s.fetchDeviceInfo(); return s
    }
    func testMovieRequiresKnownSafeModesAndMask() throws {
        XCTAssertNoThrow(try MoviePrerequisites.validate(application: Data([1]), selector: Data([1]), prohibit: le(UInt32(0))))
        XCTAssertThrowsError(try MoviePrerequisites.validate(application: Data(), selector: Data([1]), prohibit: le(UInt32(0))))
        XCTAssertThrowsError(try MoviePrerequisites.validate(application: Data([1]), selector: Data([0]), prohibit: le(UInt32(0))))
        XCTAssertThrowsError(try MoviePrerequisites.validate(application: Data([1]), selector: Data([1]), prohibit: Data()))
        XCTAssertThrowsError(try MoviePrerequisites.validate(application: Data([1]), selector: Data([1]), prohibit: le(UInt32(1))))
    }
    func testMovieStartAndStopSendOnceAndCommitFirst() async throws {
        let c = MovieTouchCamera(), s = try await session(c), tracker = CommitTracker()
        try await s.startMovieOnce(shouldContinue: { true }, willSend: { await tracker.commit() })
        let committed = await tracker.didCommit; XCTAssertTrue(committed)
        try await s.stopMovieOnce(shouldContinue: { true }, willSend: { true })
        let ops = await c.operations()
        XCTAssertEqual(ops.filter { $0 == 0x920a }.count, 1); XCTAssertEqual(ops.filter { $0 == 0x920b }.count, 1)
        XCTAssertFalse(ops.contains(0x1016)); XCTAssertFalse(ops.contains(0x9435))
    }
    func testUnsafeOrCancelledNeverStart() async throws {
        let c = MovieTouchCamera(mode: 0), s = try await session(c)
        do { try await s.startMovieOnce(shouldContinue: { true }, willSend: { true }); XCTFail("unsafe mode") }
        catch MovieSafetyError.cameraModeUnknownOrNotMovie {}
        let c2 = MovieTouchCamera(), s2 = try await session(c2)
        do { try await s2.startMovieOnce(shouldContinue: { false }, willSend: { true }); XCTFail("cancelled") }
        catch MovieSafetyError.cancelled {}
        let a = await c.operations(), b = await c2.operations()
        XCTAssertFalse(a.contains(0x920a)); XCTAssertFalse(b.contains(0x920a))
    }
    func testLostMovieStartResponseStaysCommittedAndNeverRetries() async throws {
        let c = MovieTouchCamera(dropResponse: 0x920a), s = try await session(c), tracker = CommitTracker()
        do { try await s.startMovieOnce(shouldContinue: { true }, willSend: { await tracker.commit() }); XCTFail("lost response") }
        catch { }
        let committed = await tracker.didCommit; XCTAssertTrue(committed)
        let info = await s.connectionInfo; XCTAssertNil(info)
        let ops = await c.operations(); XCTAssertEqual(ops.filter { $0 == 0x920a }.count, 1)
    }
    func testCancelledTouchNeverMovesOrDrives() async throws {
        let c = MovieTouchCamera(), s = try await session(c)
        do { try await s.touchFocusOnce(x: 0.5, y: 0.5, imageWidth: 640, imageHeight: 480, shouldContinue: { false }); XCTFail("cancelled") }
        catch TouchFocusError.cancelled {}
        let ops = await c.operations(); XCTAssertFalse(ops.contains(0x9205)); XCTAssertFalse(ops.contains(0x90c1))
    }
    func testTouchGeometryRejectsZoomAndBlackBars() throws {
        XCTAssertNotNil(TouchFocus.geometry(header: header(), imageWidth: 640, imageHeight: 480))
        XCTAssertNil(TouchFocus.geometry(header: header(zoom: true), imageWidth: 640, imageHeight: 480))
        XCTAssertNil(TouchFocus.geometry(header: header(), imageWidth: 1280, imageHeight: 720))
        XCTAssertNil(TouchFocus.normalizedPoint(x: 1, y: 45, boundsWidth: 160, boundsHeight: 90, imageWidth: 4, imageHeight: 3))
        let point = try XCTUnwrap(TouchFocus.normalizedPoint(x: 80, y: 45, boundsWidth: 160, boundsHeight: 90, imageWidth: 4, imageHeight: 3))
        XCTAssertEqual(point.0, 0.5, accuracy: 0.001); XCTAssertEqual(point.1, 0.5, accuracy: 0.001)
        XCTAssertTrue(TouchFocus.singleAreaMode(afDescriptor())); XCTAssertFalse(TouchFocus.singleAreaMode(afDescriptor(0x8011)))
    }
    func testTouchFocusVerifiesBeforeOneAF() async throws {
        let c = MovieTouchCamera(), s = try await session(c)
        try await s.touchFocusOnce(x: 0.5, y: 0.5, imageWidth: 640, imageHeight: 480, shouldContinue: { true })
        let ops = await c.operations()
        XCTAssertEqual(ops.filter { $0 == 0x9205 }.count, 1); XCTAssertEqual(ops.filter { $0 == 0x90c1 }.count, 1)
        XCTAssertGreaterThanOrEqual(ops.filter { $0 == 0x9203 }.count, 2)
    }
    func testUnverifiedPositionNeverTriggersAF() async throws {
        let c = MovieTouchCamera(canVerify: false), s = try await session(c)
        do { try await s.touchFocusOnce(x: 0.5, y: 0.5, imageWidth: 640, imageHeight: 480, shouldContinue: { true }); XCTFail("unverified") }
        catch TouchFocusError.positionNotVerified {}
        let ops = await c.operations(); XCTAssertEqual(ops.filter { $0 == 0x9205 }.count, 1); XCTAssertFalse(ops.contains(0x90c1))
    }
}
