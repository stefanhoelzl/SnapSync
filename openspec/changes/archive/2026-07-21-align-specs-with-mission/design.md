# Design — mission review decisions

## Context

The owner restated the mission on 2026-07-21 and asked for a review of all specs against it:

> The app allows joined users to easily share photos they take during an event. Events are rather
> short-lived (days/weeks — celebrations, holidays, trips). Photos are directly synced between
> device galleries: users do not need to care about how they get all the photos taken, they just
> look at their own gallery. No accounts required; simple event setup and enrollment.
>
> Possible futures: Android support · paid events (small events ≤3 persons / ≤3 days free, larger
> events paid at creation) · joining multiple events concurrently.

All 56 specs were reviewed against this statement. Well-aligned and not relitigated: the receive
side (`photo-download` renders no gallery of its own), the anti-backup guards (`event-creation-ui`,
`upload-lifecycle`), account-free enrollment (`permission-gate`, `join-event`), and the App Store
copy ("No account. No sign-up."). The tensions found, and the decisions on each, follow. Each
decision was made explicitly by the owner during the review interview.

## Goals / Non-Goals

**Goals**: make the specs say what the mission means where they were silent or contradictory;
name the three futures where new work would otherwise deepen assumptions against them; remove the
last backup-era leftover; give the mission a durable home.

**Non-Goals**: implementing any future (no payment mechanism, no multi-event client work, no
Android seam code); changing any default limit; adding user-facing UI or copy.

## Decisions

- **D1 — Event lifetime stays a 30-day ceiling.** "Days/weeks" is the *use*, 30 days is the
  *bound*: it covers a three-week trip plus the sync tail. When paid events arrive, duration
  becomes creator-chosen at creation; `event-limits` already stamps values at mint precisely so
  that change needs no schema or enforcement work. Rejected: shortening the default (cuts off slow
  syncers), creator-chosen duration now (a creation-flow decision against "simple setup" with no
  payment tier to justify it).

- **D2 — No capture-date end bound; expiry IS the end.** The cutoff stays a lower bound only.
  "The event is over" is owned server-side by `event-limits`: grace (one day past `endsAt`) keeps
  existing members' late uploads landing, then expiry deletes the event and uploads stop because
  their destination stops existing. A photo captured after the celebration but inside the window
  is admitted on the same logic as admit-on-doubt: visible and harmless, where a second exclusion
  mechanism would add a new silent way for a real event photo to fail. Rejected: capture end bound
  at `endsAt` (new "why didn't my last photo sync" edge), user-set end date (friction).

- **D3 — Single active membership is pinned as the current contract; multi-event is a named
  future.** The client models one event (switch = leave-then-join, one `Joined` state, "no
  membership, no arm"); the backend is already multi-event-ready (device-partitioned
  event-independent bytes, cross-event GC in the leave cascade, the event-independent ledger key).
  The Purpose notes exist so new work stops deepening the single-event assumption for free.
  Rejected: starting multi-event groundwork now (churn for a "may"), staying silent (the drift
  this review corrects).

- **D4 — Event expiry stays undisclosed to users.** Photos land durably in members' own
  libraries; the union is an implementation detail. Rejected: disclosure lines and expiry
  countdowns.

- **D5 — Paid events get an attach point, not a mechanism.** Creation stays free for any attested
  device; `event-creation`'s token requirement names the route as where a payment/authorization
  gate would attach, and `event-limits` names capacity/duration as the tier boundary. Today's
  defaults (10 devices / 30 days) are NOT pre-aligned to the future free tier (≤3 / ≤3 days) —
  that would punish current users for an unbuilt product.

- **D6 — Platform seams are named, not built.** App Attest and APNs are the iOS bindings of
  platform-neutral needs (prove-genuine-client, wake-a-member); Android would bind Play Integrity
  and FCM behind the same token mint and sender seam. One sentence each — no neutral-contract
  rewrite for a "may".

- **D7 — The whole-library manifest projection dies.** `photo-selection-policy` forbids any scope
  admitting the whole library; `device-manifest`'s "no cutoff → identity projection" clause is the
  last spec'd backup-era default. Verified dead in production before removal: `UploadCycle`'s
  `onDiscovery` is `cutoff: String` (non-null) end-to-end; only `projectDeviceManifest(startDate:
  String?)` and two test call sites still model null. The signature is tightened with the clause.

- **D8 — The lazy expiry reap stays; a nightly cleanup is separate work.** Already in progress in
  the `nightly-cleanup` workspace; this change does not touch reap semantics.

- **D9 — `startsAt` stays unbounded; the far-future life extension is accepted and written
  down.** A far-past start self-defuses (born expired, reaped on first touch) — and a
  within-window past start is a feature (create the event mid-trip). A far-future start extends
  the marker's life to `startsAt + duration`; acceptable while creation is attestation-gated and
  free, re-examined when duration becomes creator-chosen under paid events. Rejected: a rejection
  horizon (a creation failure mode for a hole nobody can exploit at scale today), clamping
  (silently mints a different event than requested).

- **D10 — The identity posture is confirmed as "no accounts".** A persistent device UUID with
  App Attest is not an account: nothing to create, remember, or log into; the specs already
  disclaim user identity. Crash reporting keeps the device id as its only correlation key.

- **D11 — The mission lives in `openspec/config.yaml` + `CLAUDE.md`.** The config context block
  is injected into every OpenSpec agent and is the one surface `openspec update` never rewrites;
  CLAUDE.md carries the compressed form. Rejected: specs-only (implicit mission is what drifted).
