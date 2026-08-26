// The rig's fallback enrolment matcher. Pinned because its failure is SILENT: a matcher that stops
// matching returns a simulator to a permanent 401 on push registration, with every test still green and
// nothing to notice until someone runs a simulator by hand.

import { assertEquals } from "@std/assert";
import { enrolmentTarget } from "../../src/dev/fallback.ts";

const D = "11111111-0000-4000-8000-000000000001";

Deno.test("the push-registration write is enrolled", () => {
  assertEquals(enrolmentTarget("PUT", `/api/v1/devices/${D}`), D);
  assertEquals(enrolmentTarget("put", `/api/v1/devices/${D}`), D);
});

Deno.test("routes that name a device but read no devices row are not enrolled", () => {
  // Enrolling these would be harmless, and would also stop this matcher stating which route needs it.
  assertEquals(enrolmentTarget("PUT", `/api/v1/files/devices/${D}/a.heic`), null);
  assertEquals(enrolmentTarget("GET", `/api/v1/files/devices/${D}`), null);
  assertEquals(
    enrolmentTarget("PUT", `/api/v1/events/7a3f9c21-0000-4000-8000-0000000000ee/devices/${D}`),
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
