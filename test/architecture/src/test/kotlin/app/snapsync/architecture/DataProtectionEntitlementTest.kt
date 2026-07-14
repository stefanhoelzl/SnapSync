package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The default data-protection class is never raised to `NSFileProtectionComplete`** (capability
 * `architecture-guards`).
 *
 * This is the file-side twin of the Keychain guard, and the same invariant — *state read by background
 * work must survive a locked device* — with the opposite polarity: the Keychain accessibility class must
 * **be** set, and this entitlement must **not** be.
 *
 * Everything the background tier reads from disk — the SQL ledger, the download store, the discovery
 * cursor, the event-album map — lives in the App-Group container and relies on iOS's default protection
 * class, `NSFileProtectionCompleteUntilFirstUserAuthentication`, which is readable while the device is
 * locked (after the first unlock since boot). Ticking Xcode's "Data Protection" capability sets
 * `com.apple.developer.default-data-protection` to `NSFileProtectionComplete` and makes **every** file in
 * both containers unreadable while locked — disabling background upload and download entirely, silently,
 * and only on locked devices. It would be done by someone who believed they were improving security.
 *
 * Ten lines of test, then, for a one-checkbox catastrophe.
 */
class DataProtectionEntitlementTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val entitlements = listOf(
        "iosApp/iosApp/iosApp.entitlements",
        "iosApp/BackgroundUploadExtension/BackgroundUploadExtension.entitlements",
    )

    @Test
    fun `neither target raises the default data-protection class`() {
        entitlements.forEach { relative ->
            val file = File(repoRoot, relative)
            assertTrue(file.isFile, "missing entitlements file: $relative")

            val text = file.readText()
            assertTrue(
                !text.contains("NSFileProtectionComplete<"),
                "$relative sets default-data-protection to NSFileProtectionComplete. That makes EVERY " +
                    "App-Group file — the ledger, the download store, the discovery cursor, the album map " +
                    "— unreadable while the device is locked, which silently kills background upload and " +
                    "download. The background tier depends on the iOS default, " +
                    "NSFileProtectionCompleteUntilFirstUserAuthentication.",
            )
        }
    }

    /** Fail loudly rather than vacuously: if the files moved, this guard is inspecting nothing. */
    @Test
    fun `the guard actually found the entitlements files`() {
        entitlements.forEach { relative ->
            val file = File(repoRoot, relative)
            assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
            assertTrue(
                file.readText().contains("com.apple.security.application-groups"),
                "$relative does not look like the entitlements file this guard expects",
            )
        }
    }
}
