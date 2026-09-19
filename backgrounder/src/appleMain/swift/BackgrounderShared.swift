import Foundation

// Bundled into the framework by SKIE (src/appleMain/swift). Gives Swift a
// `shared` accessor instead of the raw `companion.sharedInstance()` the
// Objective-C bridge exposes.
//
// `Backgrounder_`: the framework module and the Kotlin class share the name
// `Backgrounder`, so SKIE renames the class for Swift (see the name-collision
// warning at link time and LESSONS T-009). Renaming the framework module is
// the real fix; until then this is the class's Swift-visible name.
public extension Backgrounder.Backgrounder_ {
    /// The process-wide instance. Created lazily on first access on iOS and
    /// macOS; call `companion.create(...)` first only if you need an event
    /// listener or, on iOS, a custom tick identifier.
    static var shared: Backgrounder.Backgrounder_ {
        Backgrounder.Backgrounder_.companion.sharedInstance()
    }
}
