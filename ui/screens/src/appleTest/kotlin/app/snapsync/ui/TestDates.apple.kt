package app.snapsync.ui

import app.snapsync.model.DateFormats

/**
 * Never run: the screen tests run on the JVM only, and this compiles only so the shared suite does. It sits in
 * `appleTest`, not `iosTest`, because an `iosTest` directory registers the module's `iosPlatformTest` (root
 * `build.gradle.kts`) — which would run this suite on the simulator.
 */
internal actual val testDates: (languageTag: String?) -> DateFormats =
    { error("the screen tests run on the JVM only (`docs/testing.md`, \"Where each test runs\")") }
