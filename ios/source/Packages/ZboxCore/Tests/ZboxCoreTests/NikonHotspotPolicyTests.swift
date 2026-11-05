import XCTest
@testable import ZboxCore

final class NikonConnectionPolicyTests: XCTestCase {
    func testAccessPointSessionIdIsOne() {
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: false, random: 0xDEAD_BEEF), 1)
    }

    func testStaSessionIdStaysInRange() {
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: true, random: 0), 2)
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: true, random: 1), 3)
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: true, random: 0xFFFF_FFFF), 3)
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: true, random: 0x7fff_fffd), 0x7fff_ffff)
        XCTAssertEqual(NikonConnectionPolicy.sessionId(sta: true, random: 0x8000_0000), 2)
    }

    func testRemainingSettleNeverNegative() {
        XCTAssertEqual(NikonConnectionPolicy.remainingSettleMs(elapsedMs: 0), 8_500)
        XCTAssertEqual(NikonConnectionPolicy.remainingSettleMs(elapsedMs: 2_000), 6_500)
        XCTAssertEqual(NikonConnectionPolicy.remainingSettleMs(elapsedMs: 9_000), 0)
        XCTAssertEqual(NikonConnectionPolicy.remainingSettleMs(elapsedMs: -5), 8_500)
    }

    func testEmptyPairingPayloadMeansRegisteredWithoutPin() throws {
        XCTAssertEqual(try NikonConnectionPolicy.pairingCode(Data()), "")
    }

    func testPairingCodeFromDigitValuesAndAsciiAndPadding() throws {
        XCTAssertEqual(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0, 0, 1, 2, 3, 4])), "1234")
        XCTAssertEqual(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0, 0, 0x35, 0x36, 0x37, 0x38])), "5678")
        XCTAssertEqual(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0, 0, 0x31, 0x32, 0x33, 0x34, 0, 0])), "1234")
    }

    func testAmbiguousPairingPayloadsAreRejected() {
        XCTAssertThrowsError(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0])))
        XCTAssertThrowsError(try NikonConnectionPolicy.pairingCode(Data([9, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9])))
        XCTAssertThrowsError(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0, 0, 0x61, 0x62, 0x63, 0x64])))
        XCTAssertThrowsError(try NikonConnectionPolicy.pairingCode(Data([4, 0, 0, 0, 1, 2, 3, 4, 9])))
    }

    func testModelNormalization() {
        XCTAssertEqual(NikonConnectionPolicy.normalizedModel("Z 30_012345"), "Z 30")
        XCTAssertEqual(NikonConnectionPolicy.normalizedModel("Z 50Ⅱ"), "Z 50II")
        XCTAssertEqual(NikonConnectionPolicy.normalizedModel("Z 50_II"), "Z 50II")
        XCTAssertEqual(NikonConnectionPolicy.normalizedModel("Z 6III_012345"), "Z 6III")
        XCTAssertEqual(NikonConnectionPolicy.normalizedModel("Z502"), "Z502")
    }
}

final class HotspotPolicyTests: XCTestCase {
    private func endpoint(_ camera: String) -> HotspotCameraEndpoint {
        HotspotCameraEndpoint(cameraAddress: camera, phoneAddress: "172.20.10.1", interfaceName: "bridge100")
    }

    func testDecisionMatchesAndroidRules() {
        XCTAssertEqual(HotspotConnectionPolicy.decide(found: []).action, .none)
        XCTAssertEqual(HotspotConnectionPolicy.decide(found: [endpoint("172.20.10.2")]).action, .autoConnect)
        let two = HotspotConnectionPolicy.decide(found: [endpoint("172.20.10.2"), endpoint("172.20.10.3")])
        XCTAssertEqual(two.action, .choose)
        XCTAssertEqual(two.candidates.count, 2)
        let duplicate = HotspotConnectionPolicy.decide(found: [endpoint("172.20.10.2"), endpoint("172.20.10.2")])
        XCTAssertEqual(duplicate.action, .autoConnect)
    }

    func testAddressParsingAndFormatting() {
        XCTAssertEqual(HotspotAddressPlanner.parseIPv4("192.168.0.2"), 0xC0A8_0002)
        XCTAssertEqual(HotspotAddressPlanner.format(0xC0A8_0002), "192.168.0.2")
        XCTAssertNil(HotspotAddressPlanner.parseIPv4("256.1.1.1"))
        XCTAssertNil(HotspotAddressPlanner.parseIPv4("1.2.3"))
    }

    func testSubnetRejectsPublicCellularAndWideRanges() {
        let local = HotspotAddressPlanner.parseIPv4("172.20.10.1")!
        XCTAssertNotNil(HotspotAddressPlanner.subnet(interfaceName: "bridge100", address: local, prefix: 28))
        XCTAssertNil(HotspotAddressPlanner.subnet(interfaceName: "pdp_ip0", address: local, prefix: 28))
        XCTAssertNil(HotspotAddressPlanner.subnet(interfaceName: "en0", address: HotspotAddressPlanner.parseIPv4("8.8.8.8")!, prefix: 24))
        XCTAssertNil(HotspotAddressPlanner.subnet(interfaceName: "en0", address: local, prefix: 15))
    }

    func testCandidatesExcludeNetworkBroadcastAndSelf() {
        let local = HotspotAddressPlanner.parseIPv4("192.168.0.2")!
        let subnet = HotspotAddressPlanner.subnet(interfaceName: "en0", address: local, prefix: 24)!
        let candidates = HotspotAddressPlanner.candidates(for: subnet)
        XCTAssertEqual(candidates.count, 253)
        XCTAssertFalse(candidates.contains(local))
        XCTAssertFalse(candidates.contains(HotspotAddressPlanner.parseIPv4("192.168.0.0")!))
        XCTAssertFalse(candidates.contains(HotspotAddressPlanner.parseIPv4("192.168.0.255")!))
        XCTAssertTrue(candidates.contains(HotspotAddressPlanner.parseIPv4("192.168.0.1")!))
        XCTAssertTrue(candidates.contains(HotspotAddressPlanner.parseIPv4("192.168.0.254")!))
    }

    func testWideSubnetsAreSkippedAndBoundaryIsScanned() {
        let local = HotspotAddressPlanner.parseIPv4("10.0.0.5")!
        let slash21 = HotspotAddressPlanner.subnet(interfaceName: "en0", address: local, prefix: 21)!
        XCTAssertTrue(HotspotAddressPlanner.candidates(for: slash21).isEmpty)
        let slash22 = HotspotAddressPlanner.subnet(interfaceName: "en0", address: local, prefix: 22)!
        XCTAssertEqual(HotspotAddressPlanner.candidates(for: slash22).count, 1021)
    }
}
