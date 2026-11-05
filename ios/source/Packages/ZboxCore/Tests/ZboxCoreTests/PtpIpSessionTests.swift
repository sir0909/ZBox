import XCTest
@testable import ZboxCore

final class PtpIpSessionTests: XCTestCase {
    private func makeSession(
        command: ScriptedChannel,
        event: ScriptedChannel = ScriptedChannel(FakeCamera.event),
        sta: Bool = false,
        random: UInt32 = 5,
        settle: @escaping PtpIpSession.Settle = { _ in },
        clock: @escaping PtpIpSession.Clock = { 0 }
    ) -> PtpIpSession {
        PtpIpSession(
            commandChannel: command,
            openEventChannel: { event },
            config: testConfig(sta: sta),
            settle: settle,
            clock: clock,
            randomSource: { random }
        )
    }

    func testHandshakeOpensAccessPointSession() async throws {
        let command = ScriptedChannel(FakeCamera.command())
        let session = makeSession(command: command)

        let info = try await session.connect()

        XCTAssertEqual(info.connectionNumber, 7)
        XCTAssertEqual(info.cameraName, "Nikon Z 30")
        XCTAssertEqual(info.cameraGuid, FakeCamera.guid)
        XCTAssertEqual(info.ptpSessionId, 1)
        let sent = await command.sent
        XCTAssertEqual(sent.first?.readLE(UInt32.self, at: 4), PtpIpPacketType.initCommandRequest.rawValue)
        let open = operationOf(sent.last!)
        XCTAssertEqual(open.opCode, PtpOpCode.openSession)
        XCTAssertEqual(open.params, [1])
    }

    func testStaSessionUsesRandomIdAndSendsProbe() async throws {
        let command = ScriptedChannel(FakeCamera.command())
        let event = ScriptedChannel(FakeCamera.event)
        let session = makeSession(command: command, event: event, sta: true, random: 0x7fff_fffd)

        let info = try await session.connect()

        XCTAssertEqual(info.ptpSessionId, 0x7fff_ffff)
        let eventSent = await event.sent
        XCTAssertTrue(eventSent.contains { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.probeRequest.rawValue })
        let open = operationOf(await command.sent.last!)
        XCTAssertEqual(open.params, [0x7fff_ffff])
    }

    func testInitFailIsReportedWithReason() async throws {
        let session = makeSession(command: ScriptedChannel(FakeCamera.command(initFailReason: 1)))
        do {
            _ = try await session.connect()
            XCTFail("expected initFailed")
        } catch PtpIpSessionError.initFailed(let reason) {
            XCTAssertEqual(reason, 1)
        }
    }

    func testOpenSessionAlreadyOpenIsReportedNotTakenOver() async throws {
        let session = makeSession(command: ScriptedChannel(FakeCamera.command(openSessionCode: 0x201E)))
        do {
            _ = try await session.connect()
            XCTFail("expected operationFailed")
        } catch PtpIpSessionError.operationFailed(let code, let opCode) {
            XCTAssertEqual(code, 0x201E)
            XCTAssertEqual(opCode, PtpOpCode.openSession)
        }
    }

    func testTransactionMismatchIsRejected() async throws {
        let session = makeSession(command: ScriptedChannel(FakeCamera.command(transactionOffset: 5)))
        do {
            _ = try await session.connect()
            XCTFail("expected transactionMismatch")
        } catch PtpIpSessionError.transactionMismatch(let expected, let actual) {
            XCTAssertEqual(expected, 1)
            XCTAssertEqual(actual, 6)
        }
    }

    func testDeviceInfoIdentifiesNikonAndHostRegistration() async throws {
        let session = makeSession(command: ScriptedChannel(FakeCamera.command()))
        _ = try await session.connect()

        let info = try await session.fetchDeviceInfo()

        XCTAssertTrue(info.isNikon)
        XCTAssertEqual(info.model, "Z 30")
        XCTAssertEqual(info.serialNumber, "1234567")
        XCTAssertTrue(info.supportsNikonHostRegistration)
    }

    func testHostRegistrationWaitsForRemainingSettleThenConfirmsOnce() async throws {
        let command = ScriptedChannel(FakeCamera.command())
        let recorder = SettleRecorder()
        let clock = TestClock(now: 1_000)
        let session = makeSession(
            command: command,
            settle: { ms in await recorder.add(ms) },
            clock: { await clock.current() }
        )
        _ = try await session.connect()
        _ = try await session.fetchDeviceInfo()

        let pin = try await session.prepareHostRegistration()
        XCTAssertEqual(pin, "1234")

        await clock.set(3_000)  // 2 s elapsed → 6.5 s remaining
        try await session.confirmHostRegistration()

        let settles = await recorder.values
        XCTAssertEqual(settles.last, 6_500)
        let confirm = operationOf(await command.sent.last!)
        XCTAssertEqual(confirm.opCode, PtpOpCode.nikonConfirmPairing)
        XCTAssertEqual(confirm.params, [0x2001])

        do {
            try await session.confirmHostRegistration()
            XCTFail("second confirmation must not be sent")
        } catch PtpIpSessionError.pairingNotPrepared {
        }
    }

    func testHostRegistrationRefusedWhenCameraDoesNotAdvertiseIt() async throws {
        let info = deviceInfoDataset(operations: [0x1001, 0x1002])
        let session = makeSession(command: ScriptedChannel(FakeCamera.command(deviceInfo: info)))
        _ = try await session.connect()
        _ = try await session.fetchDeviceInfo()

        do {
            _ = try await session.prepareHostRegistration()
            XCTFail("expected hostRegistrationUnsupported")
        } catch PtpIpSessionError.hostRegistrationUnsupported {
        }
    }

    func testUnrecognizedPairingPayloadNeverConfirms() async throws {
        let command = ScriptedChannel(FakeCamera.command(pairingData: Data([9, 9, 9])))
        let session = makeSession(command: command)
        _ = try await session.connect()
        _ = try await session.fetchDeviceInfo()

        do {
            _ = try await session.prepareHostRegistration()
            XCTFail("expected pairingCodeUnrecognized")
        } catch PtpIpSessionError.pairingCodeUnrecognized {
        }
        let sentOps = await command.sent.map { operationOf($0).opCode }
        XCTAssertFalse(sentOps.contains(PtpOpCode.nikonConfirmPairing))
    }

    func testOperationsRequireConnection() async throws {
        let session = makeSession(command: ScriptedChannel(FakeCamera.command()))
        do {
            _ = try await session.execute(opCode: PtpOpCode.getDeviceInfo)
            XCTFail("expected notConnected")
        } catch PtpIpSessionError.notConnected {
        }
    }

    // MARK: - Connector name fallback

    func testConnectorRetriesOnceWithFallbackNameOnRejection() async throws {
        let names = NameLog()
        let connected = try await PtpIpConnector.connect(retryDelayNanoseconds: 0) { name in
            await names.add(name)
            let responder = name == "Zbox"
                ? FakeCamera.command(initFailReason: 1)
                : FakeCamera.command()
            return PtpIpSession(
                commandChannel: ScriptedChannel(responder),
                openEventChannel: { ScriptedChannel(FakeCamera.event) },
                config: testConfig(name: name),
                settle: { _ in },
                clock: { 0 },
                randomSource: { 5 }
            )
        }
        XCTAssertEqual(connected.usedName, "Z-Cloud AI")
        XCTAssertEqual(connected.info.cameraName, "Nikon Z 30")
        let attempted = await names.names
        XCTAssertEqual(attempted, ["Zbox", "Z-Cloud AI"])
    }

    func testConnectorDoesNotRetryBusyReason() async throws {
        let names = NameLog()
        do {
            _ = try await PtpIpConnector.connect(retryDelayNanoseconds: 0) { name in
                await names.add(name)
                return PtpIpSession(
                    commandChannel: ScriptedChannel(FakeCamera.command(initFailReason: 2)),
                    openEventChannel: { ScriptedChannel(FakeCamera.event) },
                    config: testConfig(name: name),
                    settle: { _ in },
                    clock: { 0 },
                    randomSource: { 5 }
                )
            }
            XCTFail("expected initFailed")
        } catch PtpIpSessionError.initFailed(let reason) {
            XCTAssertEqual(reason, 2)
        }
        let attempted = await names.names
        XCTAssertEqual(attempted, ["Zbox"])
    }
}

final class DeviceInfoParsingTests: XCTestCase {
    func testTruncatedDatasetThrows() {
        let full = deviceInfoDataset()
        XCTAssertThrowsError(try PtpDeviceInfo.parse(full.prefix(20)))
    }

    func testNonNikonManufacturerIsDetected() throws {
        let info = try PtpDeviceInfo.parse(deviceInfoDataset(manufacturer: "Canon Inc."))
        XCTAssertFalse(info.isNikon)
    }
}
