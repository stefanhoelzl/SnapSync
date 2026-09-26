package app.snapsync.ports

import app.snapsync.model.EntryScope

/**
 * The ambient "what triggered this" seam (capability `privacy-security`): the set/clear boundary
 * that lets the device-log writers prefix every line with `[<entryPoint>]` so downstream
 * engine/HTTP/download lines trace back to the entry point that drove them.
 *
 * It is a **port** because the holder it fronts is a process-global mutable — which may not live in
 * `:domain` (law "State and authority": no global mutable state in the core, ever). The concrete
 * holder therefore lives beside the writers that read it synchronously (`:adapter:ios:ext-safe`'s
 * `LogContext` / `IosEntryContext`); world and tests inject a no-context one. This repays the step-5
 * violation-in-transit that parked the global in `model/`.
 *
 * "Outermost wins": the first [enter] within a synchronous execution span sets the context; nested
 * wrapped seams keep the outer label until it is restored. Because fire-and-forget `scope.launch`
 * bodies run after their launcher returns, instrumentation sets the context *inside* the launched
 * coroutine so it spans the actual async work.
 */
interface EntryContext : EntryScope {

    /**
     * The entry point a line logged right now, on this thread, belongs to — or `null` when none is claimed. Read by
     * the log writers the process services install, so the crash channel can tag an event with its trigger.
     */
    fun current(): String?
}
