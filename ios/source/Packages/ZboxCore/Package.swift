// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ZboxCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ZboxCore", targets: ["ZboxCore"]),
    ],
    targets: [
        .target(name: "ZboxCore"),
        .testTarget(name: "ZboxCoreTests", dependencies: ["ZboxCore"]),
    ]
)
