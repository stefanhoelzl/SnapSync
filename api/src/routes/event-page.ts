// The EVENT PAGE's server half (capabilities `event-site`, `privacy-security`): what `/join/<eventId>` fills
// into the one built page, so a link preview and a browser running no script both see the event.
//
// PURE: an event row, its member counts and the clock in; strings out. The route (`site.ts`) does the reads
// and the storage fetch, and this file decides every word — which is what lets the tests pin each state
// without a database.
//
// THE TEMPLATE CONTRACT. `site/src/pages/join.astro` builds ONE `join/index.html` carrying each token below
// exactly once (`%%DESCRIPTION%%` twice: the page's description and `og:description`), and the route
// replaces every occurrence. A token the page lacks is simply not filled; a
// token left in the page shows as itself, which the site's build check refuses.
//
// EVERY value is HTML-escaped here, the event's name above all: it is user-written text, and this is the
// first place it is rendered into HTML by the server rather than set as `textContent` by the browser.

import { canonicalFromMs } from "../validators.ts";
import type { EventRow } from "../db.ts";

/** The tokens the built page carries, and the route fills. */
export const TOKENS = [
  "TITLE",
  "OG_TITLE",
  "DESCRIPTION",
  "URL",
  "VIEW",
  "PILL",
  "HEADING",
  "FACTS",
] as const;
export type Token = typeof TOKENS[number];
export type Filling = Record<Token, string>;

/**
 * Which view the page opens on, the value of the page's `data-view` (the page's CSS shows exactly one):
 * `event` a live event, `invalid` the "invalid or expired link" view, `pending` neither yet — the constant
 * `/join` a fragment link reaches, whose island decides (and moves the browser to the event's own page).
 */
export type View = "event" | "invalid" | "pending";

/** An event's member counts: `active` still in it, `final` of those, settled (`memberCounts`). */
export type Members = { active: number; final: number };

/** At most this many member dots; a larger event shows the count alone. */
const MAX_DOTS = 24;

const GENERIC_TITLE = "SnapSync — event photos";
const GENERIC_DESCRIPTION =
  "Join with SnapSync, and every photo arrives in your gallery. Or download them all here.";
const INVALID_TITLE = "SnapSync — link expired";
const INVALID_DESCRIPTION =
  "This link could not be opened. If the event is still on, ask anyone in it for a new link.";

/** Escape text for HTML element content and quoted attribute values alike. */
export function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

/** Replace every token in [template] with its (already escaped) value. */
export function fill(template: string, filling: Filling): string {
  let out = template;
  for (const token of TOKENS) out = out.replaceAll(`%%${token}%%`, filling[token]);
  return out;
}

/**
 * The constant page a fragment link reaches at `/join`: no event is known to the server (the fragment never
 * arrives), so the filling is generic and the island takes over.
 */
export function pendingFilling(origin: string): Filling {
  return {
    TITLE: escapeHtml(GENERIC_TITLE),
    OG_TITLE: escapeHtml(GENERIC_TITLE),
    DESCRIPTION: escapeHtml(GENERIC_DESCRIPTION),
    URL: escapeHtml(`${origin}/join`),
    VIEW: "pending",
    PILL: "",
    HEADING: escapeHtml("Event photos"),
    FACTS: "",
  };
}

/** The page of a link whose event never existed, is malformed, or whose photos are gone. Names no event. */
export function invalidFilling(origin: string): Filling {
  return {
    TITLE: escapeHtml(INVALID_TITLE),
    OG_TITLE: escapeHtml(INVALID_TITLE),
    DESCRIPTION: escapeHtml(INVALID_DESCRIPTION),
    URL: escapeHtml(`${origin}/join`),
    VIEW: "invalid",
    PILL: "",
    HEADING: "",
    FACTS: "",
  };
}

/** Where an event stands, by the clock: before its start, inside its window, or past its end. */
export type Phase = "upcoming" | "running" | "ended";

export function phaseOf(event: Pick<EventRow, "startsAt" | "endsAt">, nowMs: number): Phase {
  const now = canonicalFromMs(nowMs); // canonical instants compare chronologically as strings
  if (now < event.startsAt) return "upcoming";
  if (now < event.endsAt) return "running";
  return "ended";
}

/** The zone the event's dates are read in: the host's, or UTC for an event that kept none. */
function zoneOf(event: Pick<EventRow, "zone">): string {
  return event.zone ?? "UTC";
}

/** "Sat 4 Oct" — or "Sat 4 Oct 2026" with [year] — in the event's zone. */
function day(instant: string, zone: string, year: boolean): string {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    weekday: "short",
    day: "numeric",
    month: "short",
    ...(year ? { year: "numeric" } : {}),
  }).format(new Date(instant)).replace(",", "");
}

/** The day key of an instant in a zone, to tell same-day and same-year ranges apart. */
function ymd(instant: string, zone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  })
    .format(new Date(instant));
}

/**
 * The event's dates as its host chose them, in the host's own calendar (capability `event-site`): "Sat 4 Oct
 * 2026" for a one-day event, "Sat 4 Oct – Sun 5 Oct 2026" within one year, both years otherwise.
 */
export function dateRange(event: Pick<EventRow, "startsAt" | "endsAt" | "zone">): string {
  const zone = zoneOf(event);
  const [from, until] = [ymd(event.startsAt, zone), ymd(event.endsAt, zone)];
  if (from === until) return day(event.startsAt, zone, true);
  const sameYear = from.slice(0, 4) === until.slice(0, 4);
  return `${day(event.startsAt, zone, !sameYear)} – ${day(event.endsAt, zone, true)}`;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** "4 of 6 members done sharing", or "All 6 members done sharing" once every one is. */
function settledLine(m: Members): string {
  if (m.active > 0 && m.final >= m.active) {
    return m.active === 1 ? "The member is done sharing" : `All ${m.active} members done sharing`;
  }
  return `${m.final} of ${plural(m.active, "member", "members")} done sharing`;
}

/** The page and preview of a live (not completed) event. */
export function eventFilling(
  origin: string,
  event: EventRow,
  members: Members,
  nowMs: number,
  deletesAt: string,
): Filling {
  const phase = phaseOf(event, nowMs);
  const zone = zoneOf(event);
  const dates = dateRange(event);
  const count = plural(members.active, "member", "members");

  let pill: string;
  let description: string;
  const facts = [`<p class="dates">${escapeHtml(dates)}</p>`];
  if (phase === "upcoming") {
    pill = `<span class="pill upcoming">Starts ${
      escapeHtml(day(event.startsAt, zone, false))
    }</span>`;
    description = `Starts ${day(event.startsAt, zone, false)} · ${count}`;
    facts.push(`<p class="members">${escapeHtml(count)}</p>`);
  } else if (phase === "running") {
    pill = `<span class="pill running">Happening now</span>`;
    description = `${dates} · ${count}`;
    facts.push(`<p class="members">${escapeHtml(count)} sharing</p>`);
  } else {
    const settled = settledLine(members);
    const allDone = members.active > 0 && members.final >= members.active;
    pill = allDone
      ? `<span class="pill ended">Ended</span>`
      : `<span class="pill ended">Ended — photos still arriving</span>`;
    description = `Ended ${day(event.endsAt, zone, false)} · ${settled}`;
    if (members.active > 0 && members.active <= MAX_DOTS) {
      const dots = Array.from(
        { length: members.active },
        (_, i) => `<span class="dot${i < members.final ? " done" : ""}"></span>`,
      ).join("");
      facts.push(`<div class="dots" aria-hidden="true">${dots}</div>`);
    }
    facts.push(`<p class="members">${escapeHtml(settled)}</p>`);
    facts.push(
      `<p class="until">Available until ${
        escapeHtml(day(deletesAt, zone, false))
      } at the latest</p>`,
    );
  }

  return {
    TITLE: escapeHtml(`${event.name} — SnapSync`),
    OG_TITLE: escapeHtml(event.name),
    DESCRIPTION: escapeHtml(description),
    URL: escapeHtml(`${origin}/join/${event.eventId}`),
    VIEW: "event",
    PILL: pill,
    HEADING: escapeHtml(event.name),
    FACTS: facts.join(""),
  };
}
