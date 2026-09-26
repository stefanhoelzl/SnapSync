package app.snapsync.ios.upload

import app.snapsync.contract.extension.ContractRunningExtensionHost
import app.snapsync.extension.IosExtensionHost
import app.snapsync.ports.ExtensionHost

/**
 * A rig extension's entry port: the adapter, decorated so a port-contract run the app's rig requested through the App
 * Group takes the place of a cycle. Compiled into `:app:ios:extension` in place of its `src/entries` only under
 * `-Psnapsync.rig=true`.
 */
internal fun extensionHost(inner: IosExtensionHost): ExtensionHost = ContractRunningExtensionHost(inner)
