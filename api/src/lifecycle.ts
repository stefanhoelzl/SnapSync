// Event lifecycle + device membership — pure functions over stored markers and manifest listings
// (capabilities `event-limits`, `device-manifest`). Extracted from app.ts so the Edge Script AND the
// out-of-edge nightly sweep (capability `scheduled-cleanup`) classify events and resolve membership by
// the SAME rules. Depends only on the on-wire shapes in storage.ts — never on Hono.

import type { BunnyEntry, EventMarker, StoredEventMarker } from "./storage.ts";
import { decodeObjectName } from "./storage.ts";

export type MemberState = "active" | "departed";

/** The narrowed marker `classifyEvent` hands a `live` event's consumers. */
export type LiveEventMarker =
  & Omit<EventMarker, "lifetimeSeconds">
  & { lifetimeSeconds?: number };

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
  entries: BunnyEntry[],
): { deviceId: string; state: MemberState }[] {
  const byDevice = new Map<string, { active?: number; left?: number }>();
  for (const e of entries) {
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
 * An event's lifecycle (capability `event-limits`) — a PURE function of its stored marker; no stored
 * state machine, no marker rewrite. The lifecycle is BINARY: an event exists, or it has been deleted by
 * the sweep. There is no served intermediate state.
 *
 * In particular `endsAt` is NOT read here. It bounds only which captures may be uploaded; it closes
 * nothing. **Joining is never closed by time** — an event is joinable for as long as it exists, bounded
 * only by capacity — because a guest who joins after the window closed still holds in-window captures
 * that belong in the event. That is the whole point of separating the window from the lifetime.
 *
 * A marker missing `startsAt`, `endsAt`, or `capacity` (written before `event-limits`, or corrupt) is
 * `gone`: it cannot be served, so the gate treats it as absent (404) and the sweep deletes it. On
 * `live` the narrowed marker is returned so every consumer downstream handles only total types —
 * except `lifetimeSeconds`, which stays optional because a legacy marker lacking it is still perfectly
 * serviceable (see {@link deleteByMs}).
 */
export function classifyEvent(
  stored: StoredEventMarker,
): { phase: "live"; marker: LiveEventMarker } | { phase: "gone" } {
  const { startsAt, endsAt, capacity } = stored;
  if (!startsAt || !endsAt || typeof capacity !== "number") return { phase: "gone" };
  return { phase: "live", marker: { ...stored, startsAt, endsAt, capacity } };
}

/**
 * When an event's data is deleted (capability `event-limits`), in epoch ms — DERIVED on every read,
 * never stored. `NaN` when neither anchor date can be parsed (a corrupt marker, which the sweep reaps).
 *
 * `anchor = max(createdAt, startsAt)`, plus the marker's own stamped `lifetimeSeconds` (or
 * [lifetimeFallbackSeconds] for a marker written before that field existed).
 *
 * ⚠️ Both anchors are parsed to absolute instants rather than compared as strings. `startsAt` is in the
 * canonical cutoff form (`…T…:…:…Z`, second precision) but `createdAt` is a full ISO-8601 timestamp WITH
 * fractional seconds, so the lexicographic comparison every other date in this codebase uses would
 * silently pick the wrong anchor.
 *
 * Anchoring at the LATER of the two is what makes both directions survivable: a back-dated event (whose
 * `startsAt` is already weeks past) is not stamped dead on arrival, and a created-early event (whose
 * `startsAt` is weeks away) outlives the window it declares.
 */
export function deleteByMs(
  stored: StoredEventMarker,
  lifetimeFallbackSeconds: number,
): number {
  const createdAtMs = Date.parse(stored.createdAt ?? "");
  const startsAtMs = Date.parse(stored.startsAt ?? "");
  const anchor = Number.isNaN(createdAtMs)
    ? startsAtMs
    : Number.isNaN(startsAtMs)
    ? createdAtMs
    : Math.max(createdAtMs, startsAtMs);
  if (Number.isNaN(anchor)) return Number.NaN;
  const lifetime = typeof stored.lifetimeSeconds === "number"
    ? stored.lifetimeSeconds
    : lifetimeFallbackSeconds;
  return anchor + lifetime * 1000;
}

/**
 * Is an event STALE — should the nightly sweep delete it (capability `scheduled-cleanup`)? Kept beside
 * {@link classifyEvent} so the gate and the sweep read the same marker fields. Three independent
 * reasons, any one of which suffices:
 *
 *   INCOMPLETE  the marker cannot be classified (or its anchor dates will not parse)
 *   DEADLINE    now is past the derived delete-by — the GUARANTEE, nothing can prevent it
 *   EMPTY       every enrolled device has departed — OPPORTUNISTIC, see below
 *
 * [entries] is the event's `devices/` listing. An event whose listing holds NO manifest object at all is
 * NOT empty — it has been minted but never joined, which is the normal state of every fresh event
 * (`POST /events` always produces a zero-device event, because the creator confirms through the same
 * join gate a scanned QR uses). Deleting on an empty listing would reap a mint before the host confirms.
 *
 * Emptiness is OPPORTUNISTIC RECLAMATION, NOT A GUARANTEE. `LeaveEvent` clears the device's local config
 * and then dispatches the backend `DELETE` fire-and-forget, best-effort, never retried — so a leave that
 * never reaches storage leaves an active manifest behind and the event never empties. The deadline is
 * the only bound that always holds; nothing (spec, client behaviour, or user-facing copy) may be written
 * as if emptiness were assured.
 */
export function eventIsStale(
  stored: StoredEventMarker,
  entries: BunnyEntry[],
  nowMs: number,
  lifetimeFallbackSeconds: number,
): boolean {
  if (classifyEvent(stored).phase === "gone") return true; // incomplete — fail toward reclamation
  const deleteBy = deleteByMs(stored, lifetimeFallbackSeconds);
  if (Number.isNaN(deleteBy)) return true; // corrupt anchors — likewise
  if (nowMs > deleteBy) return true;
  const members = resolveMembership(entries);
  // Ever joined (at least one manifest object) and nobody active left → empty.
  return members.length > 0 && !members.some((m) => m.state === "active");
}
