package app.snapsync.ios.upload

import app.snapsync.contract.extension.contractRunningEntries
import app.snapsync.ports.ExtensionEntries

/**
 * The rig build's inbound port for [UploadExtensionRoot]: the core's, wrapped so a port-contract run requested
 * through the App Group takes the place of a cycle (capability `port-contracts`, "The device run is reached
 * through the rig and contained at compile time").
 *
 * Compiled INTO `:app:ios:extension` in place of `src/entries`, and ONLY under `-Psnapsync.rig=true`, so a
 * production extension contains no route to a contract run. It is shell source for the gates (capability
 * `architecture-guards`, "Source contributed into a shell's source set is shell source for the gates"), so it
 * holds no decision: the branch is `contractRunningEntries`', in `:adapter:ios:ext-safe`'s rig source set.
 */
internal fun extensionRootEntries(): ExtensionEntries = contractRunningEntries(productionExtensionEntries())
