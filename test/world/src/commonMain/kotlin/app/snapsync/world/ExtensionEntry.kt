package app.snapsync.world

import app.snapsync.compose.snapSyncExtension
import app.snapsync.mock.ExtensionHostOperator

/**
 * The upload extension's entry port, composed as its root composes it (`snapSyncExtension`) over this world's
 * [World.cycle] and [World.uploadPorts]: the operator invokes it through the returned face's `process()`.
 * [rereadCredential] is the root's per-invocation re-read of the shared device token.
 */
fun World.composeExtension(rereadCredential: () -> Unit = {}): ExtensionHostOperator {
    snapSyncExtension(extensionHost.port(), ports = { uploadPorts }, cycle = { cycle }, rereadCredential = rereadCredential)
    return extensionHost.operator
}
