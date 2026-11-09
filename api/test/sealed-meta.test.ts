// The sealed metadata record's TypeScript half against the shared reference vectors
// (`test/vectors/sealed-meta.json`, produced independently of this code; the Kotlin half is held to the same file).

import { assert, assertEquals, assertNotEquals } from "@std/assert";
import { decodeHex, encodeHex } from "@std/encoding/hex";
import {
  envelopeShape,
  type FileNameBinding,
  fileNameLabel,
  META_MAX_TEXT_LENGTH,
  metaKeyBytes,
  openFileName,
  openRecord,
  sealFileName,
  sealRecord,
} from "../src/sealed-meta.ts";

type Vectors = {
  metaKey: { eventKey: string; keyId: string; metaKey: string }[];
  seal: { eventKey: string; keyId: string; nonce: string; plaintext: string; envelope: string }[];
  reject: { reason: string; eventKey: string; keyId: string; envelope: string }[];
  fileName: {
    eventKey: string;
    binding: FileNameBinding;
    fileNameKey: string;
    nonce: string;
    plaintext: string;
    envelope: string;
  }[];
  fileNameReject: {
    reason: string;
    eventKey: string;
    binding: FileNameBinding;
    envelope: string;
  }[];
};

const vectors: Vectors = JSON.parse(
  await Deno.readTextFile(new URL("../../test/vectors/sealed-meta.json", import.meta.url)),
);

Deno.test("the record's key is the vectors'", async () => {
  for (const v of vectors.metaKey) {
    assertEquals(encodeHex(await metaKeyBytes(decodeHex(v.eventKey))), v.metaKey);
  }
});

Deno.test("every seal vector is written to its exact envelope and opens to its record", async () => {
  assert(vectors.seal.length > 0);
  for (const v of vectors.seal) {
    const key = decodeHex(v.eventKey);
    // The vector's plaintext is the exact text sealed; JSON.stringify of its parse is that text again.
    assertEquals(JSON.stringify(JSON.parse(v.plaintext)), v.plaintext);
    assertEquals(await sealRecord(key, JSON.parse(v.plaintext), decodeHex(v.nonce)), v.envelope);
    assertEquals(await openRecord(key, v.envelope), JSON.parse(v.plaintext));
  }
});

Deno.test("every reject vector opens to nothing", async () => {
  assert(vectors.reject.length > 0);
  for (const v of vectors.reject) {
    assertEquals(await openRecord(decodeHex(v.eventKey), v.envelope), null, v.reason);
  }
});

Deno.test("the edge's shape check: version, length and alphabet, never the key", () => {
  assert(envelopeShape(vectors.seal[0].envelope).ok);
  assert(!envelopeShape(vectors.seal[0].envelope + "==").ok, "padded");
  assert(!envelopeShape("a".repeat(META_MAX_TEXT_LENGTH + 1)).ok, "over the cap");
  assert(!envelopeShape("").ok, "empty");
  assert(!envelopeShape("not base64!").ok, "alphabet");
  const unknownVersion = vectors.reject.find((r) => r.reason === "unknown version byte")!;
  assert(!envelopeShape(unknownVersion.envelope).ok, "unknown version");
});

Deno.test("every file-name vector derives its key, seals to its exact envelope and opens to its name", async () => {
  assert(vectors.fileName.length > 0);
  for (const v of vectors.fileName) {
    const key = decodeHex(v.eventKey);
    assertEquals(encodeHex(await metaKeyBytes(key, fileNameLabel(v.binding))), v.fileNameKey);
    const record = JSON.parse(v.plaintext);
    if (Object.keys(record).length === 1) {
      assertEquals(await sealFileName(key, v.binding, record.name, decodeHex(v.nonce)), v.envelope);
    }
    assertEquals(await openFileName(key, v.binding, v.envelope), record.name);
  }
});

Deno.test("every file-name reject vector opens to nothing", async () => {
  assert(vectors.fileNameReject.length > 0);
  for (const v of vectors.fileNameReject) {
    assertEquals(await openFileName(decodeHex(v.eventKey), v.binding, v.envelope), null, v.reason);
  }
});

Deno.test("a sealed file name passes the edge's shape check", async () => {
  const v = vectors.fileName[0];
  assert(envelopeShape(await sealFileName(decodeHex(v.eventKey), v.binding, "IMG_4471.HEIC")).ok);
});

Deno.test("a seal draws a fresh nonce every time", async () => {
  const key = new Uint8Array(32).fill(7);
  assertNotEquals(await sealRecord(key, { name: "x" }), await sealRecord(key, { name: "x" }));
});
