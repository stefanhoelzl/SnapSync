package app.snapsync.ios.upload

import app.snapsync.compose.DevicePorts
import app.snapsync.contract.extension.ContractRunningExtensionHost
import app.snapsync.extension.IosExtensionHost
import app.snapsync.logging.appMarketingVersion
import app.snapsync.mix.LaunchMix
import app.snapsync.mix.MixFacts
import app.snapsync.mix.MixProcess
import app.snapsync.mix.mixedExtensionHost
import app.snapsync.mix.randomDeviceId
import app.snapsync.ports.ExtensionHost

// A rig extension's adapter set, compiled into `:app:ios:extension` in place of its `src/entries` only under
// `-Psnapsync.rig=true`. Both read the launch-time mix (`docs/testing.md`, "The launch-time mock mix") — the one the
// app already read, when the control channel runs this root inside the app; the extension's own read, in its own
// process. Wiring only: every decision is `LaunchMix`'s, in `:adapter:generic:mock`.

/**
 * The adapter, decorated so a port-contract run the app's rig requested through the App Group takes the place of a
 * cycle — and answered without composing where the launch-time mix says nothing may compose here. The root asks for
 * its ports first, so the launch this reads is the one they were built from.
 */
internal fun extensionHost(inner: IosExtensionHost): ExtensionHost =
    mixedExtensionHost(ContractRunningExtensionHost(inner), LaunchMix.alreadyRead())

/** The extension root's ports: [real], with the launch-time mix's mocked systems swapped in. */
internal fun extensionPorts(real: DevicePorts): DevicePorts = launch(real).portsFor(real, MixProcess.EXTENSION)

/** The OS-driven upload mechanism exists wherever this process does, so its registration is one a mock can model. */
private fun launch(real: DevicePorts): LaunchMix =
    LaunchMix.load(real.files, MixProcess.EXTENSION, extensionFacts)

private val extensionFacts = MixFacts(osDrivenUpload = true, appVersion = appMarketingVersion(), freshDeviceId = ::randomDeviceId)
