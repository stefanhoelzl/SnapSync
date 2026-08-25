## Context

`AppStatusLine`'s private `ArrowIcon` owns its own animation:

```kotlin
val animate = pulsing && !LocalReduceMotion.current
val alpha = if (animate) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = StaticAlpha,           // 0.38f
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    a
} else 1f
```

Two calls, two independent transitions. A Compose `InfiniteTransition`'s play time starts when it is
first composed, and this one is composed *inside* `if (animate)` — so an arrow's phase is set by the
moment **that** arrow began animating, and is thrown away and re-set on every `Hidden → Pulsing`,
`Static → Pulsing`, or reduced-motion flip. Nothing anywhere relates one arrow's phase to the other's.

The offset is the normal path. Uploads begin as soon as a member joins; the download arm's total is
populated only by the reconcile, which lands later — so the download arrow essentially always enters
after the upload arrow is already mid-fade. `SNAPSYNC-21`'s dump is exactly that state: 88 photos
pending and 24 downloads in flight, simultaneously.

Measurement (real `StatusScreen`, controlled clock, glyph opacity between `0.0624` dim and `0.1060`
bright, full swing `0.0436`): entering together gives a delta of `±0.00003`; entering 366 ms apart gives
`+0.039 → −0.038 → +0.039`, about 90% of full opposition.

Two constraints bound the solution. `design-system`'s **semantic-only rule** forbids appearance
parameters on any `App*` signature, so the opacity may not become a public parameter. And the
reduced-motion requirement must survive: under the preference the arrow renders at full opacity with no
motion, pinned by a test that asserts pixel-identity across 350 ms.

## Goals / Non-Goals

**Goals:**

- Two `Pulsing` arrows render the same opacity at every instant, whenever each began pulsing.
- The guarantee is stated in `design-system` as an observable property, not as an implementation.
- `AppStatusLine`'s public signature and the reduced-motion behaviour are unchanged.

**Non-Goals:**

- Arrow **derivation** — which arrow is `Hidden` / `Static` / `Pulsing`. That is `sync-status-screen`'s
  contract and is not touched, nor mirrored into it.
- A pulse phase that survives *all* pulsing stopping and later resuming. When the last `Pulsing` arrow
  goes quiet the shared phase ends and a later resume starts fresh — both arrows together, which is the
  whole complaint.
- An app-wide pulse phase. `AppStatusLine` is the only pulsing surface in the codebase; a
  `CompositionLocal` spanning the app would buy nothing today.
- A regression test, a forge preset for in-flight downloads, and a clock route on the harness driver —
  each considered and deliberately declined (see *Risks*).

## Decisions

**D1 — Share one animated value, not one transition.** Hoist a single `rememberInfiniteTransition` and a
single `animateFloat` into `AppStatusLine`'s `Syncing` branch, above both arrows, and pass the resulting
alpha into `ArrowIcon` as a parameter. `ArrowIcon` then holds no animation state at all: it applies the
alpha when its own level is `Pulsing` and renders at full opacity otherwise.

*Alternative considered — hoist only the transition, leaving each arrow its own `animateFloat`.* This
also works, and measurably so: `InfiniteTransition` shares one play time across its animations, so an
animation added later snaps into the shared phase on the next frame. But it was measured to render **one
frame at `initialValue`** first — a visible dim flash on the arrow that just appeared — and it keeps two
animations computing an identical value. The single shared value measured exact from frame zero
(delta `0.00000` throughout) and is less machinery.

**D2 — Gate the shared phase on `any arrow pulsing && !LocalReduceMotion`.** Build the transition inside
the `Syncing` branch only when it is actually needed. Because *both* arrows read the one value, they can
never drift from each other while it exists. The alternative — running it unconditionally for the row's
lifetime so the phase is continuous across `pending ↔ ongoing` flips — spends frames animating a value
nobody reads and puts the reduced-motion pixel-identity test at risk for a property nobody complained
about.

**D3 — A later-appearing arrow snaps into phase, including at the dim end.** Accepted rather than
mitigated. Lockstep is the requirement; an arrow that faded in on its own schedule before joining would
spend that fade deliberately out of phase with its partner, which is the reported symptom in miniature.

**D4 — The requirement is worded observably.** *"WHEN both arrows are `Pulsing`, they SHALL animate in
lockstep — identical opacity at every instant — regardless of when each arrow began pulsing."* A reader
can falsify that with one screenshot. The mechanical alternative ("SHALL drive both arrows from a single
animation phase") names the fix inside the contract, which the repo's laws reserve for the design record.

**D5 — Verification is on device, by measurement rather than by eye.** Judged live, near-anti-phase and
near-lockstep both look like "two arrows fading", and a crossover instant looks identical either way. A
**single frame** settles it: in lockstep both glyphs have the same opacity; drifted, one is visibly
brighter. So: capture a few instants with the `ios-device` skill and compare the two glyph regions
numerically.

Getting both arms live on one device needs foreign photos in the event union, which a self-created
single-device event never has — `/device/gallery/seed` seeds only the device's own library, which drives
uploads. So run the real API against its filesystem store via the `local-backend` skill
(`deno task dev:tunnel`), plant device files for a second member of the event, and point the build at
it. The device then reconciles real downloads while its own seeded photos upload. This also keeps the
exercise off the shared `snap-sync-dev` zone entirely.

## Risks / Trade-offs

- **[Nothing in CI pins the lockstep]** → Accepted, deliberately. A regression test was considered and
  declined; the guarantee will live in the spec and in the on-device check at ship time. The exposure is
  real and worth naming: this class of bug reads *fine* in review — the current broken code looks
  perfectly reasonable, which is how it shipped — so a future refactor that re-splits the transition
  would not be caught by CI or by eye.
- **[The state cannot be reviewed in the forge harness]** → Accepted. No preset produces a pulsing
  download arrow (`PanelController.setDownload`'s `inFlight` parameter defaults to `0` and none of the
  three panel buttons passes it), and `:test:harness-driver`'s clock never advances, so no animation is
  visible through it at all. Both were considered and declined; the on-device path in D5 is the check.
- **[The shared phase restarts when all pulsing stops and resumes]** → Not mitigated (see *Non-Goals*).
  Both arrows restart together, so the reported symptom does not recur.
- **[A newly-appearing arrow can pop in at 0.38 opacity]** → Accepted per D3.
