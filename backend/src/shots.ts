/**
 * The contract between the committed raw captures (`screenshots/`), the derive that turns them into inlined
 * images (`scripts/shots.ts`), and the page that shows them (`landing.html` via `app.ts`) — capability
 * `marketing-site`.
 *
 * This file is COMMITTED; `shots.generated.ts` is not. Splitting them is what makes the set type-checked
 * rather than hoped-for: the generated module declares itself `ShotDataUris`, so a capture the derive forgot
 * to emit, or a key it spells differently, is a **type error** at `deno check` — not a `{{SHOT_…}}`
 * placeholder discovered on the public page.
 *
 * [SHOTS] is the single place the naming convention lives. Adding a marketing screen is adding a row here
 * (plus a forge preset, plus the `{{…}}` in `landing.html`) — and forgetting either half fails to compile.
 */

/** A forge state (`SNAPSYNC_FORGE_STATE`), which is also the raw capture's filename stem. */
export type ShotState = "create" | "joining" | "in_sync";

/** The appearance the capture was taken in — `simctl ui <device> appearance <mode>`. */
export type ShotMode = "light" | "dark";

/** The token `landing.html` carries where an inlined image belongs. */
export type ShotPlaceholder =
  | "SHOT_CREATE_LIGHT"
  | "SHOT_CREATE_DARK"
  | "SHOT_JOINING_LIGHT"
  | "SHOT_JOINING_DARK"
  | "SHOT_IN_SYNC_LIGHT"
  | "SHOT_IN_SYNC_DARK";

/** One capture: the raw it derives from, and the placeholder it fills. */
export interface Shot {
  readonly state: ShotState;
  readonly mode: ShotMode;
  readonly placeholder: ShotPlaceholder;
}

/**
 * Every capture, in the order the page presents them. Spelled out rather than computed from
 * `state`×`mode`: `"create".toUpperCase()` is a `string` to the type system, so deriving the placeholder
 * would need a cast — and a cast is exactly the shortcut this file exists to avoid. Six rows is cheaper
 * than a lie.
 */
export const SHOTS: readonly Shot[] = [
  { state: "create", mode: "light", placeholder: "SHOT_CREATE_LIGHT" },
  { state: "create", mode: "dark", placeholder: "SHOT_CREATE_DARK" },
  { state: "joining", mode: "light", placeholder: "SHOT_JOINING_LIGHT" },
  { state: "joining", mode: "dark", placeholder: "SHOT_JOINING_DARK" },
  { state: "in_sync", mode: "light", placeholder: "SHOT_IN_SYNC_LIGHT" },
  { state: "in_sync", mode: "dark", placeholder: "SHOT_IN_SYNC_DARK" },
];

/**
 * The derived images, keyed by placeholder. `Record<ShotPlaceholder, …>` — not `Record<string, …>` — so the
 * generated module must supply **exactly** this key set: a missing capture fails to compile, and an unknown
 * key fails to compile. Indexing it therefore needs no cast and cannot be `undefined`.
 */
export type ShotDataUris = Readonly<Record<ShotPlaceholder, string>>;

/** The committed raw capture a [Shot] derives from, relative to the repo's `screenshots/`. */
export function rawFilename(shot: Shot): string {
  return `${shot.state}-${shot.mode}.png`;
}
