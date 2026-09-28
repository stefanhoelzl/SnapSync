// Event lifecycle — pure functions over event rows (capability `event-lifetime`). Extracted from app.ts so
// the Edge Script AND the out-of-edge nightly sweep (capability `event-lifetime`) decide an event's
// fate by the SAME rules. Depends only on the row shape in db.ts — never on Hono, never on storage.
//
// Three facts decide an event's fate: its DEADLINE (`deleteByMs`, the guarantee), its CLOCK (`clockMs`,
// 3 days after the later of its range end and its last landing), and its memberships. `sweepVerdict`
// combines them (decision record `changes/early-event-completion`).
//
// WHAT LEFT THIS MODULE WHEN THE RELATIONAL STORE ARRIVED, and why none of it is missed:
//
//   `resolveMembership` + `parseManifestObjectName` — membership was two sibling objects
//   (`<id>.json` / `<id>.left.json`) resolved by last-write-wins over directory timestamps, and THREE
//   consumers each re-implemented that rule. It is now one `state` column, so the resolution, its
//   exact-tie clause (which the spec itself called "not producible in practice"), and its
//   count-a-device-once rule are all unstateable rather than merely centralised.
//
//   `classifyEvent` + `LiveEventMarker` — a stored marker could be missing `startsAt`, `endsAt` or
//   `capacity`, so every consumer had to handle an INCOMPLETE event that could be neither served nor
//   classified. Those are `NOT NULL` columns now: an event row either exists and is complete, or it does
//   not exist. The whole `gone` phase disappears with the shape that produced it.

import type { EventRow } from "./db.ts";

/** The fields the lifetime derivation reads. A full {@link EventRow} satisfies it. */
export type LifecycleFields = Pick<EventRow, "createdAt" | "startsAt" | "lifetimeSeconds">;

/**
 * When an event's data is deleted (capability `event-lifetime`), in epoch ms — DERIVED on every read,
 * never stored. `NaN` when neither anchor date can be parsed.
 *
 * `anchor = max(createdAt, startsAt)`, plus the event's own stamped `lifetimeSeconds`.
 *
 * ⚠️ Both anchors are parsed to absolute instants rather than compared as strings. `startsAt` is in the
 * canonical cutoff form (`…T…:…:…Z`, second precision) but `createdAt` is a full ISO-8601 timestamp WITH
 * fractional seconds, so the lexicographic comparison every other date in this codebase uses would
 * silently pick the wrong anchor.
 *
 * Anchoring at the LATER of the two is what makes both directions survivable: a back-dated event (whose
 * `startsAt` is already weeks past) is not stamped dead on arrival, and a created-early event (whose
 * `startsAt` is weeks away) outlives the window it declares.
 *
 * Stamping the DURATION rather than the instant is what keeps the per-event value immutable against a
 * later configuration change while leaving this anchor policy in shared code, correctable without
 * rewriting a single stored row.
 */
export function deleteByMs(event: LifecycleFields): number {
  const createdAtMs = Date.parse(event.createdAt ?? "");
  const startsAtMs = Date.parse(event.startsAt ?? "");
  const anchor = Number.isNaN(createdAtMs)
    ? startsAtMs
    : Number.isNaN(startsAtMs)
    ? createdAtMs
    : Math.max(createdAtMs, startsAtMs);
  if (Number.isNaN(anchor)) return Number.NaN;
  return anchor + event.lifetimeSeconds * 1000;
}

/** An event's membership counts, as the sweep reads them in one query. */
export type MembershipCounts = { total: number; active: number };

/**
 * How long after the later of its range's end and its last landing an event that someone joined is
 * completed regardless of who is still in it (capability `event-lifetime`, "A finished event closes").
 */
export const COMPLETION_GRACE_MS = 3 * 24 * 60 * 60 * 1000;

/**
 * The CLOCK (decision record `changes/early-event-completion` D4): `max(endsAt, lastLandedAt) + 3 days`,
 * in epoch ms. The last landing woke every receiver, so a member that has not finished 3 days after it is
 * silent — the clock is what keeps such a member from holding the photos on the server to the deadline.
 * `NaN` when `endsAt` cannot be parsed; an unparseable landing time is ignored (the end still anchors).
 */
export function clockMs(event: Pick<EventRow, "endsAt" | "lastLandedAt">): number {
  const endsAtMs = Date.parse(event.endsAt ?? "");
  if (Number.isNaN(endsAtMs)) return Number.NaN;
  const landedMs = Date.parse(event.lastLandedAt ?? "");
  return Math.max(endsAtMs, Number.isNaN(landedMs) ? endsAtMs : landedMs) + COMPLETION_GRACE_MS;
}

/**
 * What the nightly sweep does with an event (capability `event-lifetime`):
 *
 *   DROP      now is past the derived delete-by — the GUARANTEE: the row goes, and with it anything left
 *   COMPLETE  the event is finished: its memberships, assets and so bytes go, the ROW stays until DROP so
 *             a device still joined is told "completed" rather than a "not found" it would disbelieve.
 *             Two reasons, both only for an event someone has joined:
 *               EMPTY  every enrolled device has departed — by its own leave, or on its own once the
 *                      event closed and it had everything. Dependable now: a leave that fails to reach
 *                      the server is retried by the device until it lands.
 *               CLOCK  now is past `clockMs` — the members still in it are left behind.
 *   KEEP      otherwise, and for an event already completed (it waits for DROP).
 *
 * An event with NO memberships at all is not empty — it was minted and never joined, the normal state of
 * every fresh event (`POST /events` produces a zero-device event, and the creator confirms through the
 * same join gate a scanned QR uses) — and the clock does not apply to it either: it lives to its deadline.
 */
export type SweepVerdict = "drop" | "complete" | "keep";

export function sweepVerdict(
  event: EventRow,
  counts: MembershipCounts,
  nowMs: number,
): SweepVerdict {
  const deleteBy = deleteByMs(event);
  if (Number.isNaN(deleteBy)) return "drop"; // corrupt anchors — fail toward reclamation
  if (nowMs > deleteBy) return "drop";
  if (event.completedAt || counts.total === 0) return "keep";
  if (counts.active === 0) return "complete";
  const clock = clockMs(event);
  return Number.isNaN(clock) || nowMs > clock ? "complete" : "keep";
}
