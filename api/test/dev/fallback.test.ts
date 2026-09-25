// The rig's fallback enrolment matcher. Pinned because its failure is SILENT: a matcher that stops
// matching returns a simulator to a permanent 401 on push registration, with every test still green and
// nothing to notice until someone runs a simulator by hand.

import { assertEquals } from "@std/assert";
import { deviceNamedBy, enrolmentTarget } from "../../src/dev/fallback.ts";

const D = "11111111-0000-4000-8000-000000000001";

Deno.test("the push-registration write is enrolled, on every served version", () => {
  assertEquals(enrolmentTarget("PUT", `/api/v1/devices/${D}`), D);
  assertEquals(enrolmentTarget("put", `/api/v1/devices/${D}`), D);
  // Version-BLIND, and this is the case that matters. The matcher was pinned to v1 while the device
  // moved to v2, which strands every local build on a permanent 401 that looks exactly like a bad
  // token — the rig answers normally, so there is nothing to notice.
  assertEquals(enrolmentTarget("PUT", `/api/v2/devices/${D}`), D);
  assertEquals(enrolmentTarget("PUT", `/api/v3/devices/${D}`), D);
});

Deno.test("an unversioned or malformed prefix is not enrolled", () => {
  // Blind to WHICH version, not to whether there is one: the rig enrols on a device-API route, and
  // `/devices/<id>` with no prefix is not one.
  assertEquals(enrolmentTarget("PUT", `/devices/${D}`), null);
  assertEquals(enrolmentTarget("PUT", `/api/devices/${D}`), null);
  assertEquals(enrolmentTarget("PUT", `/api/vX/devices/${D}`), null);
});

Deno.test("routes that name a device but read no devices row are not enrolled", () => {
  // Enrolling these would be harmless, and would also stop this matcher stating which route needs it.
  assertEquals(enrolmentTarget("PUT", `/api/v2/files/devices/${D}/a.heic`), null);
  assertEquals(enrolmentTarget("GET", `/api/v2/files/devices/${D}`), null);
  assertEquals(
    enrolmentTarget("PUT", `/api/v2/events/7a3f9c21-0000-4000-8000-0000000000ee/devices/${D}`),
    null,
  );
});

Deno.test("other methods on the config route are not enrolled", () => {
  assertEquals(enrolmentTarget("GET", `/api/v1/devices/${D}`), null);
  assertEquals(enrolmentTarget("DELETE", `/api/v1/devices/${D}`), null);
});

Deno.test("a non-UUID device segment is not enrolled", () => {
  assertEquals(enrolmentTarget("PUT", "/api/v1/devices/nope"), null);
  assertEquals(enrolmentTarget("PUT", "/api/v1/devices/"), null);
});

// The fallback bearer's token subject. Pinned for the same silent-failure reason: the backend refuses a
// token on any other device's route (403), so a matcher that missed a device route would 403 every
// simulator and every live contract clause on it.
Deno.test("the fallback token is minted for the device every device route names", () => {
  const E = "7a3f9c21-0000-4000-8000-0000000000ee";
  for (const v of ["v1", "v2"]) {
    assertEquals(deviceNamedBy(`/api/${v}/devices/${D}`), D);
    assertEquals(deviceNamedBy(`/api/${v}/events/${E}/devices/${D}`), D);
    assertEquals(deviceNamedBy(`/api/${v}/events/${E}/devices/${D}/manifest`), D);
    assertEquals(deviceNamedBy(`/api/${v}/files/devices/${D}`), D);
    assertEquals(deviceNamedBy(`/api/${v}/files/devices/${D}/ASSET/primary`), D);
  }
  // The FIRST `devices` segment is the device, even when an asset happens to be called `devices`.
  assertEquals(deviceNamedBy(`/api/v2/files/devices/${D}/devices/primary`), D);
  // Decoded as the router decodes the parameter it binds against.
  assertEquals(deviceNamedBy(`/api/v2/devices/%31${D.slice(1)}`), D);
});

Deno.test("a path naming no device gets the fixed dev token", () => {
  assertEquals(deviceNamedBy("/api/v2/events"), null);
  assertEquals(deviceNamedBy("/api/v2/events/7a3f9c21-0000-4000-8000-0000000000ee/files"), null);
  assertEquals(deviceNamedBy(`/devices/${D}`), null);
});
