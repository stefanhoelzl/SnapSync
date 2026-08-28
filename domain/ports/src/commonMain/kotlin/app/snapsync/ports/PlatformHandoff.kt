package app.snapsync.ports

/**
 * The ways this app hands something to the PLATFORM and stops being involved.
 *
 * Two ports, one question. [share] offers text to a chooser the user picks a destination from
 * (capability `event-link`); [links] leaves for whichever app claims a URL (capability
 * `min-app-version`). What groups them is everything that governs how they are called and what may be
 * assumed of them: both are **fire-and-forget** (there is no outcome this app is entitled to know or
 * act on, and nothing in `UiState` depends on one), both run on the **main lane** because presenting or
 * leaving asserts the platform's UI thread, and both are inert off-device, where there is no platform
 * to hand anything to.
 *
 * They are bundled rather than listed because `AppPorts` is measured (`complexity-budgets`), and a
 * ceiling may only fall: adding [links] beside [share] would have raised one. Grouping the two that
 * genuinely answer one question lowers the count instead — the same move `StatusSources` and
 * `UploadPorts` make, and for the same reason. `PhotoAccessRequester` deliberately stays out: it is an
 * escalation about what this app may SEE, and its members are read back through permission read-models
 * rather than being forgotten.
 *
 * Both default to their inert instances, so a composition with no platform to reach — the desktop
 * harnesses, the world — writes nothing about either.
 */
class PlatformHandoff(
    val share: SharePresenter = SharePresenter.None,
    val links: LinkOpener = LinkOpener.None,
)
