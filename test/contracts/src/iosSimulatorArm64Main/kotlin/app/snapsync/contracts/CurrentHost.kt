package app.snapsync.contracts

import platform.Foundation.NSBundle

/**
 * Two processes on one simulator are two hosts: the app bundle has a bundle identifier, and the Kotlin/Native
 * test executable `simctl` spawns has none (`docs/architecture.md`, "Hosts are a closed set of what changes
 * reachable states").
 */
actual val currentHost: Host =
    if (NSBundle.mainBundle.bundleIdentifier != null) Host.IOS_SIM_APP else Host.IOS_SIM_KEXE
