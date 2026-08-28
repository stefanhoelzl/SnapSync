// The one relation between this backend and the iOS build that neither file can see.
//
// `MIN_APP_VERSION` (api/src/config.ts) is the oldest marketing version `/api/v2` serves. The version a
// build DECLARES is its `CFBundleShortVersionString`, and for a dev, local or sideload build that is
// `MARKETING_VERSION` in `iosApp/Configuration/Config.xcconfig` verbatim — those builds have no release
// tag to compute a version from. So a minimum raised above that floor refuses every developer's own
// build, on a screen telling them to install from the App Store, while TestFlight and release builds
// (whose version is computed off the last release tag) keep working perfectly. That asymmetry is the
// whole hazard: nothing goes red, and it is invisible until someone tries to work.
//
// It lives here, in the api suite, because `api.yml` runs on EVERY ref with no path filter and is a
// required check — so this fails in review whichever of the two files moved. `api-deploy.yml` would have
// been the wrong home twice over: it is path-filtered (an xcconfig change would never trigger it) and it
// runs after merge.

import { assert } from "@std/assert";
import { MIN_APP_VERSION } from "../src/config.ts";
import { compareVersions } from "../src/version.ts";

const XCCONFIG = new URL("../../iosApp/Configuration/Config.xcconfig", import.meta.url);

/** `MARKETING_VERSION = 0.4` → `0.4`. Throws rather than defaulting: an unreadable floor is not a pass. */
function marketingVersionFloor(source: string): string {
  const line = source.split("\n").find((l) => /^\s*MARKETING_VERSION\s*=/.test(l));
  if (!line) throw new Error("Config.xcconfig declares no MARKETING_VERSION");
  const value = line.split("=")[1]?.trim();
  if (!value) throw new Error(`MARKETING_VERSION carries no value: ${line}`);
  return value;
}

Deno.test("MIN_APP_VERSION never exceeds the marketing-version floor a dev build carries", async () => {
  const floor = marketingVersionFloor(await Deno.readTextFile(XCCONFIG));
  assert(
    compareVersions(MIN_APP_VERSION, floor) <= 0,
    `MIN_APP_VERSION ${MIN_APP_VERSION} is above the Config.xcconfig floor ${floor}: every dev, ` +
      `sideload and simulator build would be refused 426 by its own backend. Raise the floor in the ` +
      `same change, or lower the minimum.`,
  );
});

Deno.test("the floor is read, not assumed", () => {
  // The parse itself, so a reformatted xcconfig fails loudly here rather than silently passing the
  // assertion above with a value it never found.
  assert(marketingVersionFloor("MARKETING_VERSION = 1.2\n") === "1.2");
  assert(marketingVersionFloor("A = 1\nMARKETING_VERSION = 0.4\nB = 2\n") === "0.4");
  let threw = false;
  try {
    marketingVersionFloor("// MARKETING_VERSION is discussed here but never set\nOTHER = 1\n");
  } catch {
    threw = true;
  }
  assert(threw, "a missing MARKETING_VERSION must throw, never pass");
});
