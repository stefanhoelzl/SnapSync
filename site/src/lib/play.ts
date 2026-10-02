// What the site offers on Google Play, decided from the resolved deployment (capabilities web-site, event-site).
// Pure — no DOM — so every page and component reads the same answer and `scripts/play.test.ts` runs it under Deno.
//
// Three states, and the resolver refuses the fourth (both set):
//   * `playStoreUrl` set     → the listing is public: the badge alone;
//   * `playTestGroupUrl` set → a closed test anyone may join: the three tester steps, the badge being the third;
//   * neither                → no Google Play at all (a closed listing answers non-testers with "not found").
// The opt-in page and the listing are fixed functions of the package, so they are derived here, not configured.

export interface PlayDeployment {
  androidPackageName: string;
  playStoreUrl: string;
  playTestGroupUrl: string;
}

export type PlayOffer =
  | { kind: "none" }
  | { kind: "listing"; listingUrl: string }
  | { kind: "testing"; listingUrl: string; groupUrl: string; optInUrl: string };

/** The Play listing of [pkg] — exactly the form the resolver requires of `playStoreUrl`, so `&referrer=` appends. */
export function listingUrlFor(pkg: string): string {
  return `https://play.google.com/store/apps/details?id=${pkg}`;
}

/** Play's closed-test opt-in page for [pkg], where a member of the tester list becomes a tester. */
export function optInUrlFor(pkg: string): string {
  return `https://play.google.com/apps/testing/${pkg}`;
}

export function playOfferFor(d: PlayDeployment): PlayOffer {
  if (d.playStoreUrl) return { kind: "listing", listingUrl: d.playStoreUrl };
  if (d.playTestGroupUrl) {
    return {
      kind: "testing",
      listingUrl: listingUrlFor(d.androidPackageName),
      groupUrl: d.playTestGroupUrl,
      optInUrl: optInUrlFor(d.androidPackageName),
    };
  }
  return { kind: "none" };
}
