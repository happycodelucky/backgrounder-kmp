// swift-tools-version:6.0
import PackageDescription

// BEGIN KMMBRIDGE VARIABLES BLOCK (do not edit)
let remoteKotlinUrl = "https://github.com/happycodelucky/backgrounder-kmp/releases/download/v0.12.1/Backgrounder.xcframework.zip"
let remoteKotlinChecksum = "ed1ee02fcfcffd43ff5a8b717c7e9744705cee9c2bdf3b0c625cd5e451232241"
let packageName = "Backgrounder"
// END KMMBRIDGE BLOCK

let package = Package(
    name: packageName,
    platforms: [
        .iOS(.v18),
.macOS(.v15)
    ],
    products: [
        .library(
            name: packageName,
            targets: [packageName]
        ),
    ],
    targets: [
        .binaryTarget(
            name: packageName,
            url: remoteKotlinUrl,
            checksum: remoteKotlinChecksum
        )
        ,
    ]
)