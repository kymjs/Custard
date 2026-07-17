// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CustardMac",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "CustardMac", targets: ["CustardMac"])
    ],
    targets: [
        .executableTarget(
            name: "CustardMac",
            path: "Sources/CustardMac",
            resources: [
                .process("Resources")
            ],
            linkerSettings: [
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo")
            ]
        ),
        .testTarget(
            name: "CustardMacTests",
            dependencies: ["CustardMac"],
            path: "Tests/CustardMacTests"
        )
    ]
)
