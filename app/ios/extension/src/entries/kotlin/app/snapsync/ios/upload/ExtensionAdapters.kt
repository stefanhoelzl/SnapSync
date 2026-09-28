package app.snapsync.ios.upload

import app.snapsync.compose.DevicePorts
import app.snapsync.extension.IosExtensionHost
import app.snapsync.ports.ExtensionHost

/**
 * A production extension's entry port: the adapter itself. Compiled only WITHOUT `-Psnapsync.rig=true`; a rig build
 * takes the control channel's instead, which decorates it to run a requested port contract in place of a cycle, and
 * answers without composing where the launch-time mix says it must (`docs/testing.md`, "The launch-time mock mix").
 */
internal fun extensionHost(inner: IosExtensionHost): ExtensionHost = inner

/** A production extension's ports: its real adapters, as they are. A rig build takes its launch-time mix's instead. */
internal fun extensionPorts(real: DevicePorts): DevicePorts = real
