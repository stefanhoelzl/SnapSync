// Event lifecycle — pure functions over event rows (capability `event-limits`). Extracted from app.ts so
// the Edge Script AND the out-of-edge nightly sweep (capability `scheduled-cleanup`) decide an event's
// fate by the SAME rules. Depends only on the row shape in db.ts — never on Hono, never on storage.
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
 * When an event's data is deleted (capability `event-limits`), in epoch ms — DERIVED on every read,
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
 * Is an event STALE — should the nightly sweep delete it (capability `scheduled-cleanup`)? Two
 * independent reasons, either of which suffices:
 *
 *   DEADLINE    now is past the derived delete-by — the GUARANTEE, nothing can prevent it
 *   EMPTY       every enrolled device has departed — OPPORTUNISTIC, see below
 *
 * (The third reason, INCOMPLETE, is gone: it described a marker missing fields that are now `NOT NULL`
 * columns. A row that cannot be classified cannot exist.)
 *
 * An event with NO memberships at all is NOT empty — it has been minted but never joined, which is the
 * normal state of every fresh event (`POST /events` always produces a zero-device event, because the
 * creator confirms through the same join gate a scanned QR uses). Deleting on an empty membership set
 * would reap a mint before the host confirms.
 *
 * Emptiness is OPPORTUNISTIC RECLAMATION, NOT A GUARANTEE. `LeaveEvent` clears the device's local config
 * and then dispatches the backend `DELETE` fire-and-forget, best-effort, never retried — so a leave that
 * never reaches the backend leaves an active membership behind and the event never empties. The deadline
 * is the only bound that always holds; nothing (spec, client behaviour, or user-facing copy) may be
 * written as if emptiness were assured.
 */
export function eventIsStale(
  event: EventRow,
  counts: MembershipCounts,
  nowMs: number,
): boolean {
  const deleteBy = deleteByMs(event);
  if (Number.isNaN(deleteBy)) return true; // corrupt anchors — fail toward reclamation
  if (nowMs > deleteBy) return true;
  return counts.total > 0 && counts.active === 0;
}
