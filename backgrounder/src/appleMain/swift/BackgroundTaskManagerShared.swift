import Foundation

// Bundled into the framework by SKIE (src/appleMain/swift). Gives Swift the
// same `BackgroundTaskManager.shared` spelling Kotlin has, instead of the raw
// `companion.sharedInstance()` the Objective-C bridge exposes.
public extension BackgroundTaskManager {
    /// The process-wide instance. Created lazily on first access on iOS and
    /// macOS; call `companion.create(...)` first only if you need an event
    /// listener or, on iOS, a custom tick identifier.
    static var shared: BackgroundTaskManager {
        BackgroundTaskManager.companion.sharedInstance()
    }
}
