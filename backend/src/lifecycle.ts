// Event lifecycle + device membership — pure functions over stored markers and manifest listings
// (capabilities `event-limits`, `device-manifest`). Extracted from app.ts so the Edge Script AND the
// out-of-edge nightly sweep (capability `scheduled-cleanup`) classify events and resolve membership by
// the SAME rules. Depends only on the on-wire shapes in storage.ts — never on Hono.

import type { BunnyEntry, EventMarker, StoredEventMarker } from "./storage.ts";
import { decodeObjectName } from "./storage.ts";

export type MemberState = "active" | "departed";

/**
 * Parse one `events/<eventId>/devices/` child object name into its device id and whether it is the
 * departed (`.left.json`) or active (`.json`) manifest. `.left.json` is checked first because it also
 * ends with `.json`. Returns `null` for anything else (a stray object or a directory entry).
 */
export function parseManifestObjectName(
  objectName: string,
): { deviceId: string; isLeft: boolean } | null {
  const decoded = decodeObjectName(objectName);
  if (decoded.endsWith(".left.json")) {
    return { deviceId: decoded.slice(0, -".left.json".length), isLeft: true };
  }
  if (decoded.endsWith(".json")) {
    return { deviceId: decoded.slice(0, -".json".length), isLeft: false };
  }
  return null;
}

/**
 * Resolve each device's membership from a single `events/<eventId>/devices/` listing, applying
 * **last-write-wins** when both a `<id>.json` (active) and a `<id>.left.json` (departed) sibling are
 * present: the newer object's state wins; an exact tie resolves to `active` (the leak-safe side). A
 * device is counted once. Shared membership source for the union (all devices), notify (active only),
 * and the sweep.
 */
export function resolveMembership(
  entries: BunnyEntry[] | null,
): { deviceId: string; state: MemberState }[] {
  const byDevice = new Map<string, { active?: number; left?: number }>();
  for (const e of entries ?? []) {
    if (e.IsDirectory) continue;
    const parsed = parseManifestObjectName(e.ObjectName);
    if (!parsed) continue;
    const parsedTime = Date.parse(e.LastChanged);
    const time = Number.isNaN(parsedTime) ? 0 : parsedTime;
    const slot = byDevice.get(parsed.deviceId) ?? {};
    if (parsed.isLeft) slot.left = Math.max(slot.left ?? -Infinity, time);
    else slot.active = Math.max(slot.active ?? -Infinity, time);
    byDevice.set(parsed.deviceId, slot);
  }
  const out: { deviceId: string; state: MemberState }[] = [];
  for (const [deviceId, slot] of byDevice) {
    const hasActive = slot.active !== undefined;
    const hasLeft = slot.left !== undefined;
    // Both present → LWW, active wins the tie; else whichever exists.
    const state: MemberState = hasActive && (!hasLeft || slot.active! >= slot.left!)
      ? "active"
      : "departed";
    out.push({ deviceId, state });
  }
  return out;
}

/**
 * An event's lifecycle (capability `event-limits`) — a PURE function of its stored marker and the wall
 * clock; no stored state machine, no marker rewrite. TWO gate states, since deletion moved to the
 * scheduled sweep (capability `scheduled-cleanup`): deletion *is* expiry, so the gate never sees an
 * "expired" state — an event past `endsAt` stays in grace (serving members, joins closed) until the
 * sweep deletes it.
 *
 *   live    while now <= endsAt   joins allowed (under capacity), full sync
 *   grace   while now >  endsAt   joins closed (410), members keep full sync — until the sweep deletes it
 *
 * A marker missing `startsAt`, `endsAt`, or `capacity`, or with an unparseable `endsAt` (written before
 * `event-limits`, or corrupt), is `gone`: it cannot be classified or served, so the gate treats it as
 * absent (404) and the sweep deletes it. There is no grace-period argument any more — the configured
 * grace governs only *when the sweep deletes* (`now > endsAt + grace`), not how the gate classifies.
 * On `live`/`grace` the narrowed, complete marker is returned so every consumer downstream handles only
 * total types.
 */
export function classifyEvent(
  stored: StoredEventMarker,
  nowMs: number,
): { phase: "live" | "grace"; marker: EventMarker } | { phase: "gone" } {
  const { startsAt, endsAt, capacity } = stored;
  if (!startsAt || !endsAt || typeof capacity !== "number") return { phase: "gone" };
  const endsAtMs = Date.parse(endsAt);
  if (Number.isNaN(endsAtMs)) return { phase: "gone" };
  return {
    phase: nowMs > endsAtMs ? "grace" : "live",
    marker: { ...stored, startsAt, endsAt, capacity },
  };
}

/**
 * Is a stored marker STALE — past `endsAt + grace`, or legacy/corrupt (missing or unparseable limit
 * fields)? The nightly sweep's event-phase predicate (capability `scheduled-cleanup`): a stale event's
 * marker + manifests are deleted. Kept beside `classifyEvent` so the two read the same marker fields.
 */
export function eventIsStale(
  stored: StoredEventMarker,
  nowMs: number,
  graceSeconds: number,
): boolean {
  const { endsAt } = stored;
  if (!endsAt) return true; // legacy marker — no lifetime to be within
  const endsAtMs = Date.parse(endsAt);
  if (Number.isNaN(endsAtMs)) return true; // corrupt — fail toward reclamation
  return nowMs > endsAtMs + graceSeconds * 1000;
}
