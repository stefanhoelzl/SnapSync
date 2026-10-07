package app.snapsync.mock

/**
 * One system a launch-time adapters may hand its mock (`docs/testing.md`, "Launch-time adapters") — one per mock of
 * [MockDevice], named by [key] in the adapters file. Every port a system's mock has a face for is REAL or
 * MOCK together: a mocked photo library is both the gallery and the grant.
 *
 * Not systems of the choice: the build's development controls (a rig build's are always the control channel's own), the
 * upload extension's entry port (the channel already plays the operating system's invocations of it, through the real
 * adapter), and MetricKit's process metrics (the channel feeds synthetic reports through the real handler).
 */
enum class MockedSystem(val key: String, val what: String) {
    BACKEND("backend", "the backend's routes and byte store"),
    LIBRARY("library", "the photo library, its grant and its albums"),
    FILES("files", "the app's files, shared and private"),
    DATABASES("databases", "the SQLite databases"),
    PREFERENCES("preferences", "the small shared preferences (UserDefaults; SharedPreferences)"),
    KEYCHAIN("keychain", "the protected small-value store (the Keychain; the Keystore-sealed store)"),
    INTEGRITY("integrity", "the device-integrity service (App Attest; Keystore key attestation)"),
    CRASH_REPORTER("crash-reporter", "the crash reporter's channel"),
    PROCESS_INFO("process-info", "the protected-data availability"),
    NETWORK("network", "the device's network as the operating system reports it to the app"),
    DEVICE_CONDITIONS(
        "device-conditions",
        "the device's power saving, battery, thermal state and background allowance",
    ),
    CLOCK("clock", "the wall clock and zone"),
    WAKE("wake", "the operating system's scheduled wakes"),
    BACKGROUND_TIME("background-time", "the operating system's background-time holds"),
    EXTENSION_REGISTRY("extension-registry", "the upload extension's registration"),
    UPLOAD_QUEUE("upload-queue", "the operating system's upload-job queue"),
    UPLOAD_SESSION("upload-session", "the app uploader's background transfer session"),
    DOWNLOADS("downloads", "the background download session"),
    LIFECYCLE("lifecycle", "the app's foreground life"),
    LINKS("links", "the links the platform opens the app with"),
    PUSH("push", "the push service"),
    SCREEN("screen", "the screen"),
    SYSTEM_UI("system-ui", "the share sheet, the URL opener and the Settings page"),
    ;

    companion object {
        fun ofKey(key: String): MockedSystem? = entries.firstOrNull { it.key == key }
    }
}
