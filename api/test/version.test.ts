// The version prefix split and the marketing-version comparison (capabilities `backend-deployment`,
// `min-app-version`). Both are pure, so they are tested here directly; the GATE that uses them is tested
// over HTTP in `v2.test.ts`.

import { assertEquals } from "@std/assert";
import { compareVersions, splitVersion } from "../src/version.ts";
import { MIN_APP_VERSION } from "../src/config.ts";

Deno.test("splitVersion → strips a version prefix and reports which version it was", () => {
  assertEquals(splitVersion("/api/v1/events"), { version: 1, path: "/events" });
  assertEquals(splitVersion("/api/v2/attest/token"), { version: 2, path: "/attest/token" });
  // A bare mount normalizes to "/", which is what the auth gate's closed list is written in terms of.
  assertEquals(splitVersion("/api/v1"), { version: 1, path: "/" });
  assertEquals(splitVersion("/api/v2"), { version: 2, path: "/" });
});

Deno.test("splitVersion → an unversioned path is left completely alone", () => {
  // The web/link paths and the health route are served at the ROOT and belong to no version.
  for (const p of ["/", "/join", "/.well-known/apple-app-site-association", "/health"]) {
    assertEquals(splitVersion(p), { version: null, path: p });
  }
});

Deno.test("splitVersion → a prefix that only looks like one is not stripped", () => {
  // `/api/version` must not be read as version "ersion" — the boundary is a digit run followed by `/`
  // or end-of-path.
  assertEquals(splitVersion("/api/version"), { version: null, path: "/api/version" });
  assertEquals(splitVersion("/api/v1x/events"), { version: null, path: "/api/v1x/events" });
});

Deno.test("splitVersion → an unserved version number normalizes but resolves to no version", () => {
  // It still strips, so the auth gate and the router agree about the path; `version: null` then means the
  // version gate leaves it alone and routing answers 404 — the honest answer for a mount that does not
  // exist, rather than two middlewares disagreeing about what they are looking at.
  assertEquals(splitVersion("/api/v9/events"), { version: null, path: "/events" });
});

Deno.test("compareVersions → orders numerically, part by part", () => {
  assertEquals(compareVersions("0.2", "0.1") > 0, true);
  assertEquals(compareVersions("0.1", "0.2") < 0, true);
  assertEquals(compareVersions("1.0", "1.0"), 0);
});

Deno.test("compareVersions → a two-digit minor beats a one-digit one (string order disagrees)", () => {
  // THE case this function exists for. Lexicographically "0.10" < "0.9", so a string comparison would
  // refuse a NEWER build and admit an older one — silently, and only from the tenth release onward.
  assertEquals("0.10" < "0.9", true); // what a string compare would have concluded
  assertEquals(compareVersions("0.10", "0.9") > 0, true); // what is actually true
  assertEquals(compareVersions("0.12", "0.9") > 0, true);
  assertEquals(compareVersions("1.0", "0.99") > 0, true);
});

Deno.test("compareVersions → a missing part counts as zero, so 1.0 and 1 are the same version", () => {
  assertEquals(compareVersions("1", "1.0"), 0);
  assertEquals(compareVersions("1.0.1", "1.0") > 0, true);
});

Deno.test("compareVersions → an unparseable version sorts as OLDEST", () => {
  // So a garbage declaration is refused exactly like a too-old one. The gate collapses the two answers
  // deliberately: both mean the caller cannot be trusted to speak v2, and the remedy is identical.
  for (const bad of ["", "  ", "abc", "1.2.x", "v1.2", "1..2", "-1"]) {
    assertEquals(
      compareVersions(bad, "0.1") < 0,
      true,
      `expected ${JSON.stringify(bad)} to be oldest`,
    );
  }
});

Deno.test("MIN_APP_VERSION → pinned, so raising it is visible in a diff", () => {
  // Raising this disables every older install at once. The pin is not ceremony: it is what makes the
  // change appear in review rather than inside a configuration object nobody diffs.
  assertEquals(MIN_APP_VERSION, "0.1");
});
