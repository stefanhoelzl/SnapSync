// What the site offers on Google Play (src/lib/play.ts). Deno:
// `deno test --allow-read scripts/play.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { playOfferFor } from "../src/lib/play.ts";
import { playHrefFor } from "../src/lib/invite.ts";

const PKG = "app.snapsync";
const LISTING = "https://play.google.com/store/apps/details?id=app.snapsync";
const GROUP = "https://groups.google.com/g/snapsync-beta";

// @Verifies(spec = "event-site", requirement = "The page always offers the download and the app", scenario = "No Google Play offer before publication")
// @Verifies(spec = "web-site", requirement = "A public landing page describes SnapSync", scenario = "No Google Play offer otherwise")
Deno.test("neither key: no Google Play", () => {
  assertEquals(playOfferFor({ androidPackageName: PKG, playStoreUrl: "", playTestGroupUrl: "" }), {
    kind: "none",
  });
});

// @Verifies(spec = "event-site", requirement = "The page always offers the download and the app", scenario = "Both stores are offered once SnapSync is on Google Play")
// The page's part: the offer it renders. Both pages render this one offer.
// @Verifies(spec = "web-site", requirement = "A public landing page describes SnapSync", scenario = "Both stores are offered once SnapSync is on Google Play")
// @Verifies(spec = "privacy-security", requirement = "The event's identity goes only to SnapSync's own service", scenario = "The landing page carries no invite")
// The landing page's badge is the listing itself, with no referrer.
Deno.test("a public listing: the badge alone", () => {
  assertEquals(
    playOfferFor({ androidPackageName: PKG, playStoreUrl: LISTING, playTestGroupUrl: "" }),
    {
      kind: "listing",
      listingUrl: LISTING,
    },
  );
});

// @Verifies(spec = "event-site", requirement = "The page always offers the download and the app", scenario = "The tester steps are offered during the closed test")
// @Verifies(spec = "web-site", requirement = "A public landing page describes SnapSync", scenario = "The tester steps are offered during the closed test")
Deno.test(
  "a closed test: the steps, with the opt-in page and the listing derived from the package",
  () => {
    assertEquals(
      playOfferFor({ androidPackageName: PKG, playStoreUrl: "", playTestGroupUrl: GROUP }),
      {
        kind: "testing",
        listingUrl: LISTING,
        groupUrl: GROUP,
        optInUrl: "https://play.google.com/apps/testing/app.snapsync",
      },
    );
  },
);

Deno.test("the derived listing carries the invite exactly as the public one will", () => {
  const offer = playOfferFor({
    androidPackageName: PKG,
    playStoreUrl: "",
    playTestGroupUrl: GROUP,
  });
  if (offer.kind !== "testing") throw new Error(offer.kind);
  const url = new URL(playHrefFor(offer.listingUrl, { eventId: "x", d: "abc" }));
  assertEquals(url.searchParams.get("id"), PKG);
  assertEquals(url.searchParams.get("referrer"), "v=3&d=abc");
});
