// The /join island's invite decode and the Play referrer link (src/lib/invite.ts). Deno:
// `deno test --allow-read scripts/invite.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { decodeInvite, eventIdFromPath, inviteFor, playHrefFor } from "../src/lib/invite.ts";

const PLAY = "https://play.google.com/store/apps/details?id=app.snapsync";
const EVENT = "11111111-1111-4111-8111-111111111111";
const b64url = (s: string) => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const D = b64url(JSON.stringify({ eventId: EVENT }));

Deno.test("a valid invite decodes to its event and its payload", () => {
  assertEquals(decodeInvite(`#v=3&d=${D}`), { eventId: EVENT, d: D });
});

// @Verifies(spec = "event-site", requirement = "An invalid or expired link says so", scenario = "A truncated link")
// The fragment form's part: a cut-off invite decodes to none, which the page shows as invalid.
// @Verifies(spec = "privacy-security", requirement = "The event's identity goes only to SnapSync's own service", scenario = "An invalid invite is never handed over")
// The page's part: an invalid link decodes to no invite, so there is none to hand over.
Deno.test("anything else is no invite", () => {
  for (const hash of [
    "",
    "#",
    "#garbage",
    `#v=2&d=${D}`,
    "#v=3&d=not-base64!",
    `#v=3&d=${b64url(JSON.stringify({ eventId: "nope" }))}`,
    `#v=3&d=${b64url("[]")}`,
  ]) {
    assertEquals(decodeInvite(hash), null, hash);
  }
});

// @Verifies(spec = "privacy-security", requirement = "The event's identity goes only to SnapSync's own service", scenario = "Following the event page's Google Play button")
// The page's part: Google Play's link carries the invite and nothing else. What Play does with it is the store's.
Deno.test("the Play link carries exactly v and d, encoded as one query value", () => {
  const invite = decodeInvite(`#v=3&d=${D}&extra=secret`)!;
  const href = playHrefFor(PLAY, invite);
  assertEquals(href, `${PLAY}&referrer=v%3D3%26d%3D${D}`);
  const url = new URL(href);
  assertEquals(url.searchParams.get("id"), "app.snapsync");
  assertEquals(url.searchParams.get("referrer"), `v=3&d=${D}`); // what Play hands the app, decoded once
});

// @Verifies(spec = "invite-link", requirement = "The invite handed to Google Play carries its key", scenario = "Following the button from an encrypted event's page")
// @Verifies(spec = "invite-link", requirement = "The invite handed to Google Play carries its key", scenario = "A page without the key hands over no key")
Deno.test("an encrypted event's Play link carries its key after v and d", () => {
  const K = "A".repeat(42) + "E";
  const href = playHrefFor(PLAY, inviteFor(EVENT), K);
  assertEquals(new URL(href).searchParams.get("referrer"), `v=3&d=${D}&k=${K}`);
  assertEquals(
    playHrefFor(PLAY, inviteFor(EVENT), null),
    playHrefFor(PLAY, inviteFor(EVENT)),
    "no key, no k",
  );
});

Deno.test("a path-form page names its event; any other path names none", () => {
  assertEquals(eventIdFromPath(`/join/${EVENT}`), EVENT);
  assertEquals(eventIdFromPath(`/join/${EVENT}/`), EVENT);
  for (const path of ["/join", "/join/", "/join/nope", `/join/${EVENT}/x`, `/x/join/${EVENT}`]) {
    assertEquals(eventIdFromPath(path), null, path);
  }
});

// @Verifies(spec = "event-site", requirement = "An invite link without the app opens the event's page", scenario = "Both forms open the same page")
// The page's part: both forms read as the one invite of the one event.
Deno.test(
  "a path-form page builds the same invite, and so the same Play referrer, as the fragment form",
  () => {
    assertEquals(inviteFor(EVENT), decodeInvite(`#v=3&d=${D}`));
    assertEquals(
      playHrefFor(PLAY, inviteFor(EVENT)),
      playHrefFor(PLAY, decodeInvite(`#v=3&d=${D}`)!),
    );
  },
);
