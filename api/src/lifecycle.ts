// Event lifecycle — pure functions over event rows (capability `event-lifetime`). Extracted from app.ts so
// the Edge Script AND the out-of-edge nightly sweep (capability `event-lifetime`) decide an event's
// fate by the SAME rules. Depends only on the row shape in db.ts — never on Hono, never on storage.
//
// Its fate is decided by its DEADLINE (`deleteByMs`, the guarantee: 30 days after its open-until date), by whether
// it is FINISHED (every member still in it has shared and received everything, computed by the store — `db.ts`),
// by its CLOCK (`clockMs`, 3 days after the latest of its open-until date, its last join and its last landing), and
// by its memberships. `sweepVerdict` combines them (decision records `changes/early-event-completion`,
// `changes/back-dated-events`). There is no close: deletion is the only end of an event's life.
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
export type LifecycleFields = Pick<EventRow, "createdAt" | "openUntil" | "lifetimeSeconds">;

/**
 * When an event's data is deleted at the latest (capability `event-lifetime`), in epoch ms — DERIVED on every
 * read, never stored. `NaN` when neither anchor date can be parsed.
 *
 * `anchor = max(createdAt, openUntil)`, plus the event's own stamped `lifetimeSeconds`. The open-until date is the
 * range end for an ordinary event and the date its host chose for one created after its end, so every event lives
 * the stamped lifetime past the moment it stops being open — a 30-day event is not cut off on its last day, and a
 * back-dated one is not born expired. `createdAt` stays an anchor only for a row whose open-until date is earlier
 * than its creation, which no create admits.
 *
 * ⚠️ Both anchors are parsed to absolute instants rather than compared as strings: a row written before 0013
 * carries a `createdAt` WITH fractional seconds, and a lexicographic comparison would pick the wrong anchor.
 *
 * Stamping the DURATION rather than the instant is what keeps the per-event value immutable against a
 * later configuration change while leaving this anchor policy in shared code, correctable without
 * rewriting a single stored row.
 */
export function deleteByMs(event: LifecycleFields): number {
  const createdAtMs = Date.parse(event.createdAt ?? "");
  const openUntilMs = Date.parse(event.openUntil ?? "");
  const anchor = Number.isNaN(createdAtMs)
    ? openUntilMs
    : Number.isNaN(openUntilMs)
    ? createdAtMs
    : Math.max(createdAtMs, openUntilMs);
  if (Number.isNaN(anchor)) return Number.NaN;
  return anchor + event.lifetimeSeconds * 1000;
}

/**
 * What the sweep reads about an event's members, in one query: how many ever joined (`total`), how many are still
 * in it (`active`), whether it is FINISHED (the store's predicate, at the sweep's own now), and when a device last
 * joined it (`lastJoinedAt`, `null` for none).
 */
export type MembershipCounts = {
  total: number;
  active: number;
  finished: boolean;
  lastJoinedAt: string | null;
};

/**
 * How long after the latest of its open-until date, its last join and its last landing an event that someone joined
 * is completed regardless of who is still in it (capability `event-lifetime`).
 */
export const COMPLETION_GRACE_MS = 3 * 24 * 60 * 60 * 1000;

/**
 * The CLOCK (decision records `changes/early-event-completion` D4, `changes/back-dated-events` D2):
 * `max(openUntil, lastJoinedAt, lastLandedAt) + 3 days`, in epoch ms. The last landing woke every receiver, and a
 * guest who joined late gets the same 3 days to receive, so a member that has not finished by then is silent — the
 * clock is what keeps such a member from holding the photos on the server to the deadline. `NaN` when `openUntil`
 * cannot be parsed; an unparseable join or landing time is ignored (the open-until date still anchors).
 */
export function clockMs(
  event: Pick<EventRow, "openUntil" | "lastLandedAt">,
  lastJoinedAt: string | null = null,
): number {
  const openUntilMs = Date.parse(event.openUntil ?? "");
  if (Number.isNaN(openUntilMs)) return Number.NaN;
  const later = [event.lastLandedAt, lastJoinedAt]
    .map((at) => Date.parse(at ?? ""))
    .filter((ms) => !Number.isNaN(ms));
  return Math.max(openUntilMs, ...later) + COMPLETION_GRACE_MS;
}

/**
 * What the nightly sweep does with an event (capability `event-lifetime`):
 *
 *   DROP      now is past the derived delete-by — the GUARANTEE: the row goes, and with it anything left
 *   COMPLETE  the event's photos go: its memberships, assets and so bytes; the ROW stays until DROP so a device
 *             still joined is told "completed" rather than a "not found" it would disbelieve. Three reasons, all
 *             only for an event someone has joined:
 *               FINISHED  its open-until date has passed and every member still in it has shared and received
 *                         everything — the fast path, within a day of the last member catching up.
 *               EMPTY     every enrolled device has departed. Dependable: a leave that fails to reach the
 *                         server is retried by the device until it lands.
 *               CLOCK     now is past `clockMs` — the members still in it are left behind.
 *   KEEP      otherwise, and for an event already completed (it waits for DROP).
 *
 * An event with NO memberships at all is not empty — it was minted and never joined, the normal state of
 * every fresh event (`POST /events` produces a zero-device event, and the creator confirms through the
 * same join gate a scanned QR uses) — and neither the fast path nor the clock applies to it: it lives to its
 * deadline.
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
  if (counts.finished || counts.active === 0) return "complete";
  const clock = clockMs(event, counts.lastJoinedAt);
  return Number.isNaN(clock) || nowMs > clock ? "complete" : "keep";
}

/**
 * Whether a create may keep its event open until [openUntil] (decision record `changes/back-dated-events`, D1):
 * not before its range ends, and at most [windowMaxSeconds] after the later of that end and [nowMs]. Both are
 * canonical instants; anything unparseable is refused.
 */
export function openUntilAdmitted(
  endsAt: string,
  openUntil: string,
  nowMs: number,
  windowMaxSeconds: number,
): boolean {
  const end = Date.parse(endsAt);
  const until = Date.parse(openUntil);
  if (Number.isNaN(end) || Number.isNaN(until) || until < end) return false;
  return until <= Math.max(end, nowMs) + windowMaxSeconds * 1000;
}
