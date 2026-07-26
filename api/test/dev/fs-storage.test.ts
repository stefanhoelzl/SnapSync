// Contract test for the dev filesystem storage shim (`src/dev/fs-storage.ts`).
//
// It drives the shim THROUGH `storage.ts` — the same `putObject`/`listDir`/`readObjectText`/`deleteObject`
// the app and the sweep call — rather than through raw HTTP. That is the whole point: what needs pinning
// is not the shim's surface but that the shim satisfies the expectations its only callers already encode
// (a `404` meaning "absent", the `BunnyEntry` field set, a byte-exact filename round trip).
//
// WHAT THIS PROVES, AND WHAT IT DOES NOT. It proves the shim agrees with the same assumptions about bunny
// that the mocks in `test/app.test.ts` encode, so a failure in the local device loop means YOUR change
// broke rather than the rig. It does NOT prove either matches bunny — nothing in this repo does, beyond the
// directory-listing behaviour measured in `listDir`'s doc. Recorded here rather than implied.

import { assert, assertEquals, assertRejects } from "@std/assert";
import { storageConfig } from "../../src/config.ts";
import {
  decodeObjectName,
  deleteObject,
  listDir,
  putObject,
  readObjectText,
} from "../../src/storage.ts";
import { fsFetch } from "../../src/dev/fs-storage.ts";

const CONFIG = storageConfig("dev-access-key");
const D = "11111111-0000-4000-8000-000000000002"; // a deviceId

/** Run `body` against a shim rooted in a fresh temp directory, always cleaned up. */
async function withStore(
  body: (fetchImpl: ReturnType<typeof fsFetch>, root: string) => Promise<void>,
) {
  const root = await Deno.makeTempDir({ prefix: "snapsync-fs-storage-" });
  try {
    await body(fsFetch(CONFIG, root), root);
  } finally {
    await Deno.remove(root, { recursive: true });
  }
}

Deno.test("round trip: PUT then LIST then GET then DELETE", async () => {
  await withStore(async (f) => {
    const key = `files/devices/${D}/IMG_0001.HEIC`;
    await putObject(f, CONFIG, key, "photo-bytes", "image/heic");

    const entries = await listDir(f, CONFIG, `files/devices/${D}/`);
    assertEquals(entries?.length, 1);
    assertEquals(entries![0].ObjectName, "IMG_0001.HEIC");
    assertEquals(entries![0].IsDirectory, false);
    assertEquals(entries![0].Length, "photo-bytes".length);
    // `LastChanged` is the last-write-wins tiebreak and the sweep's upload-time floor; both parse it
    // with `Date.parse`, so an unparseable value would silently degrade to epoch 0.
    assert(!Number.isNaN(Date.parse(entries![0].LastChanged)));

    assertEquals(await readObjectText(f, CONFIG, key), "photo-bytes");

    await deleteObject(f, CONFIG, key);
    assertEquals(await readObjectText(f, CONFIG, key), null);
  });
});

Deno.test("a missing object reads as absent, not as a failure", async () => {
  await withStore(async (f) => {
    assertEquals(await readObjectText(f, CONFIG, `files/devices/${D}/nope.jpg`), null);
  });
});

Deno.test("an absent directory lists as no entries", async () => {
  await withStore(async (f) => {
    // The shim answers an absent directory with a `404`, which `listDir` tolerates and maps to `[]`.
    // MEASURED 2026-07-26: real bunny instead answers `200 []` here (see `listDir`'s doc) — a divergence
    // no caller can observe, because the seam collapses both to the same empty array. Pinned so it stays
    // unobservable: a caller that started distinguishing them would be reading the shim, not bunny.
    assertEquals(await listDir(f, CONFIG, `files/devices/${D}/`), []);
  });
});

Deno.test("a directory emptied by deletes lists as an empty array", async () => {
  await withStore(async (f) => {
    const key = `files/devices/${D}/only.jpg`;
    await putObject(f, CONFIG, key, "x", "image/jpeg");
    await deleteObject(f, CONFIG, key);
    // The shim reaches this through a different path than the absent case above — a real, empty directory
    // rather than a `404` — and both surface as the same `[]`, which is what every caller relies on.
    // Pinned so a change to either branch has to be deliberate.
    assertEquals(await listDir(f, CONFIG, `files/devices/${D}/`), []);
  });
});

Deno.test("a percent-encoded filename round-trips byte-exact", async () => {
  await withStore(async (f) => {
    // The upload route stores `encodeURIComponent(filename)`, and `decodeObjectName` decodes it back —
    // so the stored object name IS the encoded form and the shim must not decode on the way in.
    const filename = "a b+c&d.HEIC";
    await putObject(
      f,
      CONFIG,
      `files/devices/${D}/${encodeURIComponent(filename)}`,
      "bytes",
      "image/heic",
    );
    const entries = await listDir(f, CONFIG, `files/devices/${D}/`);
    assertEquals(entries?.length, 1);
    assertEquals(decodeObjectName(entries![0].ObjectName), filename);
  });
});

Deno.test("deleting an absent object succeeds (cascades stay re-runnable)", async () => {
  await withStore(async (f) => {
    // `deleteObject` treats bunny's 404 as success; this asserts the shim produces that 404 rather than
    // some other status that would throw and abort a leave/sweep cascade midway.
    await deleteObject(f, CONFIG, `files/devices/${D}/never-existed.jpg`);
  });
});

Deno.test("a stored Content-Type is served back", async () => {
  await withStore(async (f) => {
    const key = `files/devices/${D}/clip.mov`;
    await putObject(f, CONFIG, key, "bytes", "video/quicktime");
    const res = await f(`https://${CONFIG.host}/${CONFIG.zone}/${key}`, { method: "GET" });
    assertEquals(res.headers.get("content-type"), "video/quicktime");
    await res.body?.cancel();
  });
});

Deno.test("a streamed body is stored whole", async () => {
  await withStore(async (f) => {
    // The byte-upload route passes the request's ReadableStream straight through, never buffering it —
    // so the shim has to accept one.
    const stream = new Blob(["chunk-one", "chunk-two"]).stream();
    const key = `files/devices/${D}/streamed.bin`;
    const res = await f(`https://${CONFIG.host}/${CONFIG.zone}/${key}`, {
      method: "PUT",
      body: stream,
    });
    assertEquals(res.status, 201);
    assertEquals(await readObjectText(f, CONFIG, key), "chunk-onechunk-two");
  });
});

Deno.test("manifest siblings carry distinct LastChanged, so membership resolves last-write-wins", async () => {
  await withStore(async (f) => {
    const dir = `events/7a3f9c21-0000-4000-8000-000000000001/devices/`;
    await putObject(f, CONFIG, `${dir}${D}.json`, "{}", "application/json");
    await new Promise((r) => setTimeout(r, 10));
    await putObject(f, CONFIG, `${dir}${D}.left.json`, "{}", "application/json");

    const entries = await listDir(f, CONFIG, dir);
    const active = entries!.find((e) => e.ObjectName === `${D}.json`)!;
    const left = entries!.find((e) => e.ObjectName === `${D}.left.json`)!;
    assert(
      Date.parse(left.LastChanged) >= Date.parse(active.LastChanged),
      "the later write must not report an earlier LastChanged",
    );
  });
});

Deno.test("an upstream call outside the zone is refused, not passed through", async () => {
  await withStore(async (f) => {
    // The guard that keeps a call this shim does not model from reaching the real bunny zone with a real
    // AccessKey. Key-agnostic on purpose.
    await assertRejects(
      () => f("https://storage.bunnycdn.com/some-other-zone/files/x", { method: "GET" }),
      Error,
      "outside",
    );
    await assertRejects(
      () => f("https://api.push.apple.com/3/device/abc", { method: "POST" }),
      Error,
      "outside",
    );
  });
});

Deno.test("a traversal attempt cannot escape the store root", async () => {
  await withStore(async (f) => {
    // Two distinct mechanisms, neither of which is `safeJoin`:
    //   * a RAW `..` is normalized out by the URL parse before the shim sees a key at all;
    //   * an ENCODED `%2e%2e` survives the parse but is a literal directory name, not a parent hop.
    // `safeJoin` is therefore defense in depth, unreachable through this path. What matters is the
    // outcome — nothing outside the store is ever read — so that is what is asserted.
    for (const suffix of ["../../etc/passwd", "%2e%2e/%2e%2e/etc/passwd"]) {
      const res = await f(`https://${CONFIG.host}/${CONFIG.zone}/${suffix}`, { method: "GET" });
      assertEquals(res.status, 404);
      await res.body?.cancel();
    }
  });
});
