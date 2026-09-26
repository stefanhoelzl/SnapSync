package app.snapsync.ports

/**
 * **The app's foreground life** (capability `sync-status`): hear it become active and stop being active. On iOS the
 * application's `didBecomeActive` / `willResignActive` notifications — `willResignActive` includes the transient
 * inactive states (a permission prompt, the app switcher, Control Center), which the foreground flow treats as
 * leaving; `willEnterForeground` would lose the cold launch's first activation. On Android the process lifecycle's
 * `ON_START` / `ON_STOP`.
 *
 * One external system, deciding nothing: what an activation runs is the composition's handler. An event port,
 * registered once per adapter as the graph is composed; registering installs the platform's observers and runs no
 * handler.
 */
interface Lifecycle : Listenable<LifecycleHandlers>

/** What the app's foreground life tells the core. Built only by a composition. */
class LifecycleHandlers(
    /** The app became active. The handler assembles the status host before its foreground work. */
    val onForeground: () -> Unit,
    /** The app is leaving the active state, including a transient interruption. */
    val onBackground: () -> Unit,
)
