## 1. Share the pulse phase

- [x] 1.1 In `AppStatusLine.kt`'s `StatusBody` `Syncing` branch, hoist a single
      `rememberInfiniteTransition` + one `animateFloat` (`StaticAlpha → 1f`,
      `infiniteRepeatable(tween(700), RepeatMode.Reverse)`), built only when
      `upload == Arrow.PULSING || download == Arrow.PULSING` and `!LocalReduceMotion.current`;
      otherwise the alpha is `1f`.
- [x] 1.2 Give the private `ArrowIcon` a `pulseAlpha: Float` parameter and delete its own
      `rememberInfiniteTransition` / `animateFloat`. It applies `pulseAlpha` only when its own level is
      `Arrow.PULSING`, and renders at full opacity for `Arrow.STATIC` — so `ArrowIcon` holds no
      animation state at all.
- [x] 1.3 Confirm `AppStatusLine`'s **public** signature is untouched and no opacity/animation parameter
      escaped onto it (`design-system`'s semantic-only rule).
- [x] 1.4 Update the comments in `ArrowIcon` and the `Syncing` branch to say where the phase now lives
      and why one value is shared — the current comment explains the per-arrow fade that is going away.

## 2. Check what already exists

- [x] 2.1 `./gradlew build` — in particular `:ui:screens:jvmTest`'s reduced-motion pair, which must still
      pass in both directions: pixel-identical over 350 ms under the preference, and demonstrably moving
      without it.
- [x] 2.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 2.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 3. Verify on device (the only check of the new behaviour)

- [x] 3.1 Load the `local-backend` skill; run the real API against its filesystem store
      (`deno task dev:tunnel`) — never the shared `snap-sync-dev` zone.
- [x] 3.2 Create an event from the device (never join one you did not create) and plant device files for
      a **second** member in the local store, so the device has foreign photos to reconcile.
- [x] 3.3 Load `rig-channel`; seed the device's own library (`/device/gallery/seed`) so uploads run, and
      drive a cycle so uploads are pending **and** downloads are in flight at the same moment — confirm
      via `/device/state` that `photos_pending > 0` and `downloads_in_flight > 0` together.
- [x] 3.4 Load `ios-device`; screenshot several single instants of the status line.
- [x] 3.5 Measure, do not eyeball: compare the two arrow glyph regions' opacity within **each** frame.
      Lockstep means equal within noise in every frame. (Judging live is what this step exists to avoid —
      anti-phase and lockstep look alike at a glance, and a crossover instant looks identical either way.)

