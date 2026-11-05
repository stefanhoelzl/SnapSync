// Without its invite there is no way in (capability `invite-link`, "The invite link is the key to an event"): the
// service offers no listing or search of events. Every event read names its event, so a request that names none — or
// asks for a list, or searches — finds nothing, whether it carries no credential or a valid device's.

import { assert, assertEquals, assertNotEquals } from "@std/assert";
import { as, CONFIG, createApp, E, recorder, storeWithEvent, V2 } from "./support/harness.ts";

/** A name no generic page would happen to contain, so finding it in an answer means the event was listed. */
const NAME = "Zanzibar Reunion";

const LISTING_SHAPED = [
  "/api/v2/events",
  "/api/v2/events/",
  "/api/v2/events?name=Anna",
  "/api/v2/events?search=Anna",
  "/api/v2/events/search?q=Anna",
  "/events",
  "/web/events",
  "/web/events/",
  "/web/events/photos",
  "/join/",
];

async function answers(path: string, headers: Record<string, string>) {
  const db = await storeWithEvent({ name: NAME });
  try {
    const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
    const res = await app.request(path, { headers });
    return { status: res.status, body: await res.text() };
  } finally {
    db.close();
  }
}

for (const path of LISTING_SHAPED) {
  Deno.test(`no listing → ${path} without a credential names no event`, async () => {
    const { status, body } = await answers(path, V2);
    assertNotEquals(status, 200, `${path} answered 200: ${body.slice(0, 200)}`);
    assert(!body.includes(E), `${path} leaked the event id`);
    assert(!body.includes(NAME), `${path} leaked the event's name`);
  });

  Deno.test(`no listing → ${path} with a valid device's credential names no event`, async () => {
    const { status, body } = await answers(path, {
      ...V2,
      ...(await as("33333333-0000-4000-8000-000000000004")),
    });
    assertNotEquals(status, 200, `${path} answered 200: ${body.slice(0, 200)}`);
    assert(!body.includes(E), `${path} leaked the event id`);
    assert(!body.includes(NAME), `${path} leaked the event's name`);
  });
}

// The control: the event's own read, which names it, does answer it — so the fixture's event is there to be found,
// and the requests above found nothing because nothing lists it.
Deno.test("no listing → the read that names the event finds it", async () => {
  const { status, body } = await answers(`/api/v2/events/${E}`, V2);
  assertEquals(status, 200, body.slice(0, 200));
  assert(body.includes(NAME), "the named read answers the event");
});
