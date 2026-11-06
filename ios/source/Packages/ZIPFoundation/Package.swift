// swift-tools-version:5.9
// Locally vendored ZIPFoundation 0.9.20, commit 22787ffb59de99e5dc1fbfe80b19c97a904ad48d.
// Package manifest simplified to omit upstream test targets; source has only trailing whitespace removed in two lines.
import PackageDescription
let package = Package(
    name: "ZIPFoundation",
    platforms: [.macOS(.v10_13), .iOS(.v12), .tvOS(.v12), .watchOS(.v4)],
    products: [.library(name: "ZIPFoundation", targets: ["ZIPFoundation"])],
    targets: [.target(name: "ZIPFoundation", resources: [.copy("Resources/PrivacyInfo.xcprivacy"), .copy("Resources/LICENSE.txt")])],
    swiftLanguageVersions: [.v4, .v4_2, .v5]
)
