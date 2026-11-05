import XCTest
@testable import ZboxCore

private func descriptor(writable: Bool = true, current: UInt16 = 400, values: [UInt16] = [400, 800]) -> Data {
    var d = le(ExposureProperty.iso.rawValue) + le(UInt16(4)) + Data([writable ? 1 : 0])
    d += le(UInt16(400)) + le(current) + Data([2]) + le(UInt16(values.count))
    for value in values { d += le(value) }
    return d
}

private actor ExposureCamera: PtpIpByteChannel {
    private let fallback = FakeCamera.command()
    private var inbox: [Data] = []
    private var writes = 0
    private var packets: [Data] = []
    private var current: UInt16
    private let writable: Bool
    private let mutate: Bool
    init(current: UInt16 = 400, writable: Bool = true, mutate: Bool = true) {
        self.current = current; self.writable = writable; self.mutate = mutate
    }
    func send(_ data: Data) async throws {
        packets.append(data)
        let type = data.readLE(UInt32.self, at: 4)
        if type == PtpIpPacketType.operationRequest.rawValue {
            let op = data.readLE(UInt16.self, at: 12), tx = data.readLE(UInt32.self, at: 14)
            switch op {
            case PtpOpCode.getDevicePropDesc:
                inbox += dataIn(tx, descriptor(writable: writable, current: current)) + [opResponse(PtpResponseCode.ok, tx)]; return
            case PtpOpCode.getDevicePropValue:
                inbox += dataIn(tx, le(current)) + [opResponse(PtpResponseCode.ok, tx)]; return
            case PtpOpCode.setDevicePropValue:
                writes += 1; return
            default: break
            }
        } else if type == PtpIpPacketType.endData.rawValue {
            let tx = data.readLE(UInt32.self, at: 8)
            if mutate { current = data.readLE(UInt16.self, at: 12) }
            inbox.append(opResponse(PtpResponseCode.ok, tx)); return
        } else if type == PtpIpPacketType.startData.rawValue {
            return
        }
        inbox += fallback(data)
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
    func snapshot() -> (Int, [Data]) { (writes, packets) }
}

private actor StringMenuCamera: PtpIpByteChannel {
    private let fallback = FakeCamera.command()
    private var inbox: [Data] = []
    private var current = ptpString("6000x4000")
    private var writes = 0
    func send(_ data: Data) async throws {
        let type = data.readLE(UInt32.self, at: 4)
        if type == PtpIpPacketType.operationRequest.rawValue {
            let op = data.readLE(UInt16.self, at: 12), tx = data.readLE(UInt32.self, at: 14)
            if op == PtpOpCode.getDevicePropDesc {
                let values = [ptpString("6000x4000"), ptpString("3000x2000")]
                var desc = le(ExposureProperty.imageSize.rawValue)
                desc += le(UInt16(0xffff)); desc += Data([1]); desc += values[0]
                desc += current; desc += Data([2]); desc += le(UInt16(2))
                desc += values[0]; desc += values[1]
                inbox += dataIn(tx, desc) + [opResponse(PtpResponseCode.ok, tx)]; return
            }
            if op == PtpOpCode.getDevicePropValue {
                inbox += dataIn(tx, current) + [opResponse(PtpResponseCode.ok, tx)]; return
            }
            if op == PtpOpCode.setDevicePropValue { writes += 1; return }
        } else if type == PtpIpPacketType.endData.rawValue {
            let tx = data.readLE(UInt32.self, at: 8)
            current = data.slice(at: 12, count: data.count - 12)
            inbox.append(opResponse(PtpResponseCode.ok, tx)); return
        } else if type == PtpIpPacketType.startData.rawValue { return }
        inbox += fallback(data)
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
    func writeCount() -> Int { writes }
}

final class ExposureWriteTests: XCTestCase {
    private func makeSession(_ channel: ExposureCamera) async throws -> PtpIpSession {
        let s = PtpIpSession(commandChannel: channel, openEventChannel: { ScriptedChannel(FakeCamera.event) },
                             config: testConfig(), settle: { _ in })
        _ = try await s.connect(); return s
    }
    func testDescriptorEnumAndRangeAndInvalidType() throws {
        let d = try ExposureDescriptor.parse(descriptor(), property: .iso)
        XCTAssertTrue(d.writable); XCTAssertEqual(d.current, le(UInt16(400)))
        XCTAssertEqual(d.choices, [le(UInt16(400)), le(UInt16(800))])
        var range = le(ExposureProperty.compensation.rawValue) + le(UInt16(3)) + Data([1])
        range += le(UInt16(0)) + le(UInt16(0)) + Data([1]) + le(UInt16(bitPattern: -1000)) + le(UInt16(1000)) + le(UInt16(500))
        let r = try ExposureDescriptor.parse(range, property: .compensation)
        XCTAssertEqual(r.choices.count, 5)
        XCTAssertEqual(r.choices.first, le(UInt16(bitPattern: -1000)))
        XCTAssertThrowsError(try ExposureDescriptor.parse(descriptor().dropLast(), property: .iso))
        XCTAssertThrowsError(try ExposureDescriptor.parse(descriptor(), property: .compensation))
    }
    func testMenuScalarDescriptorAndUnknownType() throws {
        let quality = le(ExposureProperty.quality.rawValue) + le(UInt16(2)) + Data([1, 0, 2, 2]) + le(UInt16(3)) + Data([0, 2, 4])
        let d = try ExposureDescriptor.parse(quality, property: .quality)
        XCTAssertEqual(d.type, 2)
        XCTAssertEqual(d.current, Data([2]))
        XCTAssertEqual(d.choices, [Data([0]), Data([2]), Data([4])])
        XCTAssertEqual(ExposureProperty.quality.displayValue(Data([4])), "0x04")
        let stringDesc = le(ExposureProperty.imageSize.rawValue) + le(UInt16(0xffff)) + Data([1, 0, 0, 0])
        XCTAssertThrowsError(try ExposureDescriptor.parse(stringDesc, property: .imageSize))
    }
    func testStringMenuEnumerationAndStrictValidation() throws {
        let first = ptpString("6000x4000"), second = ptpString("3000x2000")
        var desc = le(ExposureProperty.imageSize.rawValue)
        desc += le(UInt16(0xffff)); desc += Data([1]); desc += first; desc += first
        desc += Data([2]); desc += le(UInt16(2)); desc += first; desc += second
        let parsed = try ExposureDescriptor.parse(desc, property: .imageSize)
        XCTAssertEqual(parsed.type, 0xffff)
        XCTAssertEqual(parsed.choices, [first, second])
        XCTAssertEqual(ExposureDescriptor.ptpString(second), "3000x2000")
        XCTAssertEqual(ExposureDescriptor.ptpString(Data([0])), "")
        XCTAssertNil(ExposureDescriptor.ptpString(Data([1, 1, 0])))
        XCTAssertNil(ExposureDescriptor.ptpString(Data([2, 0x00, 0xd8, 0, 0])))
        XCTAssertThrowsError(try ExposureDescriptor.parse(desc.dropLast(), property: .imageSize))
    }
    func testStringMenuWriteIsVerifiedWithoutTruncation() async throws {
        let camera = StringMenuCamera()
        let s = PtpIpSession(commandChannel: camera, openEventChannel: { ScriptedChannel(FakeCamera.event) },
                             config: testConfig(), settle: { _ in })
        _ = try await s.connect()
        let result = try await s.writeExposureOnce(property: .imageSize, expectedType: 0xffff,
            expectedCurrent: ptpString("6000x4000"), desired: ptpString("3000x2000"), shouldContinue: { true })
        XCTAssertEqual(result, .verified)
        let writes = await camera.writeCount()
        XCTAssertEqual(writes, 1)
    }
    func testChangedDescriptorTypeIsNeverWritten() async throws {
        let camera = ExposureCamera(), s = try await makeSession(camera)
        do {
            _ = try await s.writeExposureOnce(property: .iso, expectedType: 3,
                expectedCurrent: le(UInt16(400)), desired: le(UInt16(800)), shouldContinue: { true })
            XCTFail("type change must block write")
        } catch ExposureWriteError.notWritableOrValueNotAllowed {}
        let (writes, _) = await camera.snapshot(); XCTAssertEqual(writes, 0)
    }
    func testSuccessfulWriteSendsCorrectFramesAndVerifiesReadback() async throws {
        let camera = ExposureCamera(), s = try await makeSession(camera)
        let result = try await s.writeExposureOnce(property: .iso, expectedType: 4, expectedCurrent: le(UInt16(400)), desired: le(UInt16(800)), shouldContinue: { true })
        XCTAssertEqual(result, .verified)
        let (writes, packets) = await camera.snapshot(); XCTAssertEqual(writes, 1)
        let req = try XCTUnwrap(packets.first { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue && $0.readLE(UInt16.self, at: 12) == PtpOpCode.setDevicePropValue })
        XCTAssertEqual(req.readLE(UInt32.self, at: 8), 2)
        let tx = req.readLE(UInt32.self, at: 14)
        let start = try XCTUnwrap(packets.first { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.startData.rawValue })
        let end = try XCTUnwrap(packets.first { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.endData.rawValue })
        XCTAssertEqual(start.readLE(UInt32.self, at: 8), tx); XCTAssertEqual(start.readLE(UInt64.self, at: 12), 2)
        XCTAssertEqual(end.readLE(UInt32.self, at: 8), tx); XCTAssertEqual(end.slice(at: 12, count: 2), le(UInt16(800)))
    }
    func testReadonlyStaleAndCancelledNeverWrite() async throws {
        for scenario in 0...2 {
            let camera = ExposureCamera(writable: scenario != 0), s = try await makeSession(camera)
            do {
                _ = try await s.writeExposureOnce(property: .iso, expectedType: 4,
                    expectedCurrent: le(UInt16(scenario == 1 ? 200 : 400)), desired: le(UInt16(800)),
                    shouldContinue: { scenario != 2 })
                XCTFail("write should have been blocked")
            } catch let error as ExposureWriteError {
                XCTAssertEqual(error, [.notWritableOrValueNotAllowed, .staleCurrentValue, .cancelledBeforeWrite][scenario])
            }
            let (writes, _) = await camera.snapshot(); XCTAssertEqual(writes, 0)
        }
    }
    func testMismatchCannotBeReportedAsVerified() async throws {
        let camera = ExposureCamera(mutate: false), s = try await makeSession(camera)
        do {
            _ = try await s.writeExposureOnce(property: .iso, expectedType: 4, expectedCurrent: le(UInt16(400)), desired: le(UInt16(800)), shouldContinue: { true })
            XCTFail("mismatched readback")
        } catch ExposureWriteError.readbackMismatch {}
        let (writes, _) = await camera.snapshot(); XCTAssertEqual(writes, 1)
    }
}
