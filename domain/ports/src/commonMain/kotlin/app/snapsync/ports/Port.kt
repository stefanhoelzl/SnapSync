package app.snapsync.ports

/**
 * **A port** — a seam an adapter answers for the core (`docs/architecture.md`, "Ports are the I/O boundary named for
 * the need"). Every port interface in this zone extends it, and the composition bundles (`AppPorts`, `ExtensionPorts`,
 * `ProcessPorts`, `DevicePorts`) hold nothing else: a service, a constant, a lambda or a dispatcher handed to a
 * composition is something the composition could have built itself, or something a port should declare.
 *
 * A marker, deliberately empty. What it buys is a checkable boundary: `PortBundleTest` (`:test:architecture`) fails
 * when a bundle field is not a `Port`, or when a port interface here does not extend it. The few interfaces here that
 * are NOT ports — a handle the platform hands the core ([Completion], [BackgroundTimeHold]) or a value it answers
 * ([LibraryChangeToken]) — are listed there with their reason.
 */
interface Port
