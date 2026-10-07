// What the byte routes store for an ENCRYPTED event (the encrypted file format, `docs/architecture.md`):
// an event created with a key id takes only files encrypted under that key — sealed on the device, or sealed
// HERE from the iOS extension's one-file key — and never plaintext, whichever route the bytes arrive on.
// A plain event behaves exactly as before. What lands in storage is read back and opened with the event key,
// so "201" is never mistaken for "stored the right bytes".

import { assert, assertEquals } from "@std/assert";
import { encodeBase64Url } from "@std/encoding/base64url";
import { encodeHex } from "@std/encoding/hex";
import { insertEvent } from "../src/db.ts";
import {
  decryptFile,
  encodeHead,
  encryptingStream,
  fileAssociatedData,
  fileKeyOf,
  FORMAT_VERSION,
  keyIdOf,
} from "../src/encrypted-file.ts";
import { FILE_HEAD_HEADER, FILE_KEY_HEADER } from "../src/routes/encrypted-upload.ts";
import {
  CONFIG,
  createApp,
  D,
  E,
  EVENT,
  joinEvent,
  recorder,
  rows,
  store,
  V2,
} from "./support/harness.ts";

const EVENT_KEY = Uint8Array.from({ length: 32 }, (_, i) => i * 5);
const KEY_ID = encodeHex(await keyIdOf(EVENT_KEY));
const AD = fileAssociatedData(E, D, "ASSET1", "primary");
const BYTE_PATH = `/api/v2/events/${E}/files/devices/${D}/ASSET1/primary?filename=IMG_0001.HEIC`;
const EVENTLESS_BYTE_PATH = `/api/v2/files/devices/${D}/ASSET1/primary?filename=IMG_0001.HEIC`;
const PHOTO = Uint8Array.from({ length: 150_000 }, (_, i) => (i * 11 + 2) & 0xff);

async function member(keyId: string | null) {
  const db = await store();
  await insertEvent(db, { ...EVENT, keyId });
  await joinEvent(db, E, D);
  return db;
}

function freshHead() {
  return {
    version: FORMAT_VERSION,
    keyId: new Uint8Array(0),
    salt: crypto.getRandomValues(new Uint8Array(32)),
    noncePrefix: crypto.getRandomValues(new Uint8Array(7)),
  };
}

/** What a device writes for {@link PHOTO}: a whole encrypted file. */
async function sealedOnDevice(eventKey = EVENT_KEY) {
  const head = { ...freshHead(), keyId: await keyIdOf(eventKey) };
  const key = await fileKeyOf(eventKey, head.salt, AD);
  return new Uint8Array(
    await new Response(new Blob([PHOTO]).stream().pipeThrough(encryptingStream(key, head)))
      .arrayBuffer(),
  );
}

/** The bytes the route handed to storage, read off the recorded PUT. */
async function stored(calls: ReturnType<typeof recorder>["calls"]) {
  const put = calls.find((c) => c.init.method === "PUT");
  assert(put, "something was stored");
  return new Uint8Array(await new Response(put.init.body).arrayBuffer());
}

const putting = (body: BodyInit, headers: Record<string, string> = {}) => ({
  method: "PUT",
  headers: { ...V2, "content-type": "image/heic", ...headers },
  body,
});

Deno.test("create → a key id makes an encrypted event, and its metadata names it; a plain one names none", async () => {
  const db = await store();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const post = (body: unknown) =>
    app.request("/api/v2/events", {
      method: "POST",
      headers: { ...V2, "content-type": "application/json" },
      body: JSON.stringify(body),
    });
  const encrypted = await post({ name: "Secret", startsAt: EVENT.startsAt, keyId: KEY_ID });
  assertEquals(encrypted.status, 201);
  const created = await encrypted.json();
  assertEquals(created.keyId, KEY_ID);
  const read = await (await app.request(`/api/v2/events/${created.eventId}`, { headers: V2 }))
    .json();
  assertEquals(read.keyId, KEY_ID);

  const plain = await (await post({ name: "Open", startsAt: EVENT.startsAt })).json();
  assert(!("keyId" in plain), "a plain event's wire shape is what every installed build parses");
  assertEquals(
    (await rows(db, `SELECT key_id FROM events WHERE id = ?`, [plain.eventId]))[0].key_id,
    null,
  );
  for (const bad of ["DE30249B854310C8", "de30249b854310c", "zz30249b854310c8", 12]) {
    assertEquals(
      (await post({ name: "X", startsAt: EVENT.startsAt, keyId: bad })).status,
      400,
      `${bad}`,
    );
  }
  db.close();
});

Deno.test("encrypted event → a file the device sealed under the event's key is stored as it came", async () => {
  const db = await member(KEY_ID);
  const { calls, fetchImpl } = recorder();
  const file = await sealedOnDevice();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    BYTE_PATH,
    putting(file),
  );
  assertEquals(res.status, 201);
  const landed = await stored(calls);
  assertEquals(landed, file);
  assertEquals(await decryptFile(landed, EVENT_KEY, AD), PHOTO);
  db.close();
});

Deno.test("encrypted event → plaintext, or a file of another key, is refused 422 and nothing is stored", async () => {
  const db = await member(KEY_ID);
  const { calls, fetchImpl } = recorder();
  const app = createApp({ config: CONFIG, db, fetch: fetchImpl });
  const other = await sealedOnDevice(new Uint8Array(32));
  for (const body of [PHOTO, other, new Uint8Array([FORMAT_VERSION, 1, 2]), new Uint8Array(0)]) {
    assertEquals((await app.request(BYTE_PATH, putting(body))).status, 422);
  }
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
  assertEquals((await rows(db, `SELECT 1 FROM resources`)).length, 0);
  db.close();
});

Deno.test("encrypted event → every route that files bytes under it refuses plaintext, the event-less one included", async () => {
  const db = await member(KEY_ID);
  const { calls, fetchImpl } = recorder();
  const app = createApp({ config: CONFIG, db, fetch: fetchImpl });
  assertEquals((await app.request(EVENTLESS_BYTE_PATH, putting(PHOTO))).status, 422);
  assertEquals((await app.request(BYTE_PATH, putting(PHOTO))).status, 422);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
  const file = await sealedOnDevice();
  assertEquals((await app.request(EVENTLESS_BYTE_PATH, putting(file))).status, 201);
  db.close();
});

Deno.test("encrypted event → the extension's one-file key seals the plaintext here, as the device would", async () => {
  const db = await member(KEY_ID);
  const { calls, fetchImpl } = recorder();
  const head = { ...freshHead(), keyId: await keyIdOf(EVENT_KEY) };
  const fileKey = await fileKeyOf(EVENT_KEY, head.salt, AD);
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(
    BYTE_PATH,
    putting(PHOTO, {
      [FILE_KEY_HEADER]: encodeBase64Url(fileKey),
      [FILE_HEAD_HEADER]: encodeBase64Url(encodeHead(head)),
    }),
  );
  assertEquals(res.status, 201);
  const file = await stored(calls);
  assertEquals(await decryptFile(file, EVENT_KEY, AD), PHOTO, "opens with the event key");
  const asDevice = new Uint8Array(
    await new Response(new Blob([PHOTO]).stream().pipeThrough(encryptingStream(fileKey, head)))
      .arrayBuffer(),
  );
  assertEquals(file, asDevice, "byte for byte what the device writes with the same head");
  db.close();
});

Deno.test("encrypted event → a one-file key for another event's key, or a malformed one, is refused", async () => {
  const db = await member(KEY_ID);
  const { calls, fetchImpl } = recorder();
  const app = createApp({ config: CONFIG, db, fetch: fetchImpl });
  const foreign = { ...freshHead(), keyId: await keyIdOf(new Uint8Array(32)) };
  const key = encodeBase64Url(new Uint8Array(32));
  const send = (headers: Record<string, string>) => app.request(BYTE_PATH, putting(PHOTO, headers));
  assertEquals(
    (await send({
      [FILE_KEY_HEADER]: key,
      [FILE_HEAD_HEADER]: encodeBase64Url(encodeHead(foreign)),
    }))
      .status,
    403,
  );
  const ours = encodeBase64Url(encodeHead({ ...freshHead(), keyId: await keyIdOf(EVENT_KEY) }));
  assertEquals((await send({ [FILE_KEY_HEADER]: key })).status, 400, "one header of two");
  assertEquals((await send({ [FILE_HEAD_HEADER]: ours })).status, 400, "one header of two");
  assertEquals((await send({ [FILE_KEY_HEADER]: "short", [FILE_HEAD_HEADER]: ours })).status, 400);
  assertEquals((await send({ [FILE_KEY_HEADER]: key, [FILE_HEAD_HEADER]: "x" })).status, 400);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 0);
  db.close();
});

Deno.test("plain event → bytes are stored as they came, and a file key is refused 400", async () => {
  const db = await member(null);
  const { calls, fetchImpl } = recorder();
  const app = createApp({ config: CONFIG, db, fetch: fetchImpl });
  assertEquals((await app.request(BYTE_PATH, putting(PHOTO))).status, 201);
  assertEquals(await stored(calls), PHOTO);
  const headers = {
    [FILE_KEY_HEADER]: encodeBase64Url(new Uint8Array(32)),
    [FILE_HEAD_HEADER]: encodeBase64Url(encodeHead({ ...freshHead(), keyId: new Uint8Array(8) })),
  };
  assertEquals((await app.request(BYTE_PATH, putting(PHOTO, headers))).status, 400);
  db.close();
});
