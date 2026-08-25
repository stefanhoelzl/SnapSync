## Why

A TestFlight member reported that the joined screen's **settings gear and rename pen "appeared only after a while"**
(Bugsink `SNAPSYNC-26`, build 0.4/607, iOS 18.7.9). They are right, it happens on **every** join, and the delay is the
whole provision: the reduction sets `Joined.pendingSwitch` for **any** pending join while joined — never comparing its
event id to the configured one — so the join's own in-flight commit is misread as a *switch to a different event*, and
the two affordances that suppression hides stay hidden until the commit returns. In the reported session that was
**3.26 s** (config written at 19:06:11.65, pending cleared at 19:06:14.91), and it scales with the network, because the
window is `Provision` steps 3–6: a status refresh, an upload cycle with a device-manifest `PUT`, the album create, and
a reconcile `GET` plus a push `PUT`.

Investigating it exposed two further defects on the same path, both worse than the one reported. A pending join can
**rest forever in a phase that offers no action** — `Loading` and `Committing` pin no button at all — so a throw
anywhere in the commit leaves a dead-end spinner recoverable only by force-quit. And a throwing Orbit intent
**disables the entire status container for the process lifetime**: measured against the shipped `orbit-core` 10.0.0,
`RealContainer` rethrows when no `exceptionHandler` is configured, which cancels the non-supervisor `intentJob` and
silently drops every subsequent intent — leave, share, settings save, rename, join, create, cancel — while the screen
keeps rendering its last state and looks alive.

## What Changes

- **Drop the pending-switch suppression of the settings and rename affordances.** Both render in every `Joined` state,
  like share and leave. The config-write race the suppression named is already prevented three times over: the
  `eventId` guards inside `ReconfigureEvent` and `RenameEvent` (a surface opened for a different membership is a
  no-op), and the screen's own `LaunchedEffect(joined)` reset that closes both surfaces the moment a config clears.
  The rule was also inconsistent — leave and share are exposed identically and were never suppressed — and during the
  switch phases that actually render a dialog the whole screen is modal anyway, so the suppression's real coverage was
  the few hundred milliseconds of the details fetch.
- **A pending join never rests in a non-terminal phase.** `Loading` and `Committing` are the only phases with no
  action; the two paths that write them repair themselves on a throw. `commit()` decides from the config: if it now
  names the event being joined, the join landed and the pending join is dropped; otherwise the throw preceded the
  config write and the phase becomes the retryable `CommitFailed`. `loadInto()` becomes `LoadFailed`, converging with
  what a returning-`Failed` client already produces.
- **The status container survives a throwing intent.** The container is given an Orbit `exceptionHandler`, routed to a
  new injected error seam that the composition binds to an `Error`-severity log — so the failure still reaches the
  device log and the crash reporter, but no longer takes every future user tap with it. This is also what makes the
  rethrow in the two repairs above safe.

Not changed, deliberately: the reduction keeps emitting a same-event `pendingSwitch` (no consumer misreads it once the
suppression is gone), and the diagnostic dump's `screen` label may therefore read `Switch:Committing` for a plain join
— which is useful signal about a commit in flight rather than a lie about the surface.

No breaking changes: the two new seams both default to inert, so every existing construction site compiles unchanged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sync-status-screen`: the settings and rename affordances lose their "suppressed while an event-switch is in
  progress" requirement and its scenario; the reduction/container requirement gains **container liveness** — an intent
  that throws never disables the container.
- `join-event`: gains a requirement that the join gate never rests in a phase that offers no action, so a failure
  during a details load or a commit always leaves a surface the member can act on.

## Impact

- `ui/screens` — `StatusScreen.kt`: the two suppression conditions and the now-dead local; two comment blocks.
  `StatusScreenTest`: two suppression tests invert.
- `ui/presentation` — `StatusContainerHost.kt`: the container's `buildSettings`, a new `onIntentError` constructor
  seam, and the two catches in `commit()` / `loadInto()`. `StatusContainerHostTest`: new coverage for both repairs and
  for container liveness.
- `app/ios` — `SnapSyncRoot.kt` binds `onIntentError` where it already builds the host. Wiring only.
- No `:domain` change. No adapter, backend, or persistence change. No dependency change.
- `openspec/specs/sync-status-screen/spec.md` and `openspec/specs/join-event/spec.md`.
