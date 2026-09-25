package app.snapsync.ios.upload

import app.snapsync.ports.ExtensionEntries

/**
 * The inbound port [UploadExtensionRoot] delegates to: the core's, unchanged. This directory is compiled only
 * WITHOUT `-Psnapsync.rig=true`; with it, the build compiles `test/rig/src/ext-hook` in its place (capability
 * `docs/architecture.md`, "A build-time-only module is contained by compilation, not by a runtime check").
 */
internal fun extensionRootEntries(): ExtensionEntries = productionExtensionEntries()
