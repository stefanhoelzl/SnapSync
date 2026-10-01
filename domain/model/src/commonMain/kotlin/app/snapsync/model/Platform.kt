package app.snapsync.model

/**
 * Which platform the running build is for — a constant of the build, answered by the `BuildInfo` port. There is no
 * JVM value: the JVM world plays a phone, and its mocks answer [IOS].
 *
 * [tag] is how the platform is named where it leaves the device (the crash channel's `platform` tag).
 */
enum class Platform(val tag: String) {
    IOS("ios"),
    ANDROID("android"),
}
