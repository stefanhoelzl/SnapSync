package app.snapsync.ios.upload

import app.snapsync.extension.IosExtensionHost
import app.snapsync.ports.ExtensionHost

/**
 * A production extension's entry port: the adapter itself. Compiled only WITHOUT `-Psnapsync.rig=true`; a rig build
 * takes the control channel's instead, which decorates it to run a requested port contract in place of a cycle.
 */
internal fun extensionHost(inner: IosExtensionHost): ExtensionHost = inner
