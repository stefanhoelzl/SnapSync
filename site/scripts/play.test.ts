// What the site offers on Google Play (src/lib/play.ts). Deno:
// `deno test --allow-read scripts/play.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { playOfferFor } from "../src/lib/play.ts";
import { playHrefFor } from "../src/lib/invite.ts";

const PKG = "app.snapsync";
const LISTING = "https://play.google.com/store/apps/details?id=app.snapsync";
const GROUP = "https://groups.google.com/g/snapsync-beta";

Deno.test("neither key: no Google Play", () => {
  assertEquals(playOfferFor({ androidPackageName: PKG, playStoreUrl: "", playTestGroupUrl: "" }), {
    kind: "none",
  });
});

Deno.test("a public listing: the badge alone", () => {
  assertEquals(
    playOfferFor({ androidPackageName: PKG, playStoreUrl: LISTING, playTestGroupUrl: "" }),
    {
      kind: "listing",
      listingUrl: LISTING,
    },
  );
});

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
