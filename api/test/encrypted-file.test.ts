// The encrypted file format's TypeScript half against the shared reference vectors
// (`test/vectors/encrypted-file.json`, produced independently of this code and decrypted by Google Tink).
// What the edge writes for an iOS extension upload must be the bytes the app writes itself, and what the
// event page decrypts must be what either wrote — so every case pins exact bytes, not a round trip.

import { assert, assertEquals, assertRejects } from "@std/assert";
import { decodeHex, encodeHex } from "@std/encoding/hex";
import {
  decodeHead,
  decryptFile,
  EncryptedFileError,
  encryptingStream,
  fileAssociatedData,
  type FileHead,
  fileKeyOf,
  FORMAT_VERSION,
  HEADER_LENGTH,
  keyIdOf,
  PREFIX_LENGTH,
  SEGMENT_LENGTH,
} from "../src/encrypted-file.ts";

type StreamVector = {
  segmentSize: number;
  ikm: string;
  associatedData: string;
  salt: string;
  noncePrefix: string;
  plaintextLength: number;
  ciphertext?: string;
  ciphertextLength?: number;
  ciphertextHead?: string;
  ciphertextSha256?: string;
};

const VECTORS = JSON.parse(
  await Deno.readTextFile(new URL("../../test/vectors/encrypted-file.json", import.meta.url)),
);

const pattern = (n: number, f: (i: number) => number) =>
  Uint8Array.from({ length: n }, (_, i) => f(i) & 0xff);

async function encrypt(fileKey: Uint8Array, head: FileHead, plain: Uint8Array, piece = 1000) {
  const source = new ReadableStream<Uint8Array>({
    start(c) {
      for (let at = 0; at < plain.length; at += piece) c.enqueue(plain.slice(at, at + piece));
      c.close();
    },
  });
  return new Uint8Array(
    await new Response(source.pipeThrough(encryptingStream(fileKey, head))).arrayBuffer(),
  );
}

async function sha256(bytes: Uint8Array) {
  return encodeHex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes as BufferSource)));
}

function assertPinned(actual: Uint8Array, v: {
  ciphertext?: string;
  ciphertextLength?: number;
  ciphertextHead?: string;
  ciphertextSha256?: string;
}, sha: string) {
  if (v.ciphertext) {
    assertEquals(encodeHex(actual), v.ciphertext);
  } else {
    assertEquals(actual.length, v.ciphertextLength);
    assertEquals(encodeHex(actual.subarray(0, 64)), v.ciphertextHead);
    assertEquals(sha, v.ciphertextSha256);
  }
}

Deno.test("the key id is the vectors'", async () => {
  for (const v of VECTORS.keyId) {
    assertEquals(encodeHex(await keyIdOf(decodeHex(v.eventKey))), v.keyId);
  }
});

Deno.test("every Tink stream vector of the app's segment size encrypts to its exact bytes", async () => {
  const ours = (VECTORS.stream as StreamVector[]).filter((v) => v.segmentSize === SEGMENT_LENGTH);
  assert(ours.length > 0);
  for (const v of ours) {
    const ikm = decodeHex(v.ikm);
    const head = {
      version: FORMAT_VERSION,
      keyId: await keyIdOf(ikm),
      salt: decodeHex(v.salt),
      noncePrefix: decodeHex(v.noncePrefix),
    };
    const key = await fileKeyOf(ikm, head.salt, decodeHex(v.associatedData));
    const plain = pattern(v.plaintextLength, (i) => i * 7 + 3);
    const stream = (await encrypt(key, head, plain)).subarray(PREFIX_LENGTH);
    assertPinned(stream, v, await sha256(stream));
  }
});

Deno.test("the reference file encrypts to its exact bytes and decrypts back", async () => {
  const v = VECTORS.file[0];
  const eventKey = decodeHex(v.eventKey);
  const ad = fileAssociatedData(v.eventId, v.deviceId, v.assetId, v.role);
  const head = {
    version: FORMAT_VERSION,
    keyId: await keyIdOf(eventKey),
    salt: decodeHex(v.salt),
    noncePrefix: decodeHex(v.noncePrefix),
  };
  const plain = pattern(v.plaintextLength, (i) => i * 13 + 1);
  for (const piece of [1, 4096, 65536, 300_000]) {
    if (piece === 1 && plain.length > 20_000) continue; // byte-at-a-time is covered by the stream vectors
    const file = await encrypt(await fileKeyOf(eventKey, head.salt, ad), head, plain, piece);
    assertPinned(file, {
      ciphertextLength: v.fileLength,
      ciphertextHead: v.fileHead,
      ciphertextSha256: v.fileSha256,
    }, await sha256(file));
    assertEquals(await decryptFile(file, eventKey, ad), plain);
  }
});

Deno.test("small stream vectors decrypt, fed byte by byte on the way in", async () => {
  const small = (VECTORS.stream as StreamVector[]).filter((v) =>
    v.segmentSize === SEGMENT_LENGTH && v.ciphertext
  );
  for (const v of small) {
    const ikm = decodeHex(v.ikm);
    const head = {
      version: FORMAT_VERSION,
      keyId: await keyIdOf(ikm),
      salt: decodeHex(v.salt),
      noncePrefix: decodeHex(v.noncePrefix),
    };
    const key = await fileKeyOf(ikm, head.salt, decodeHex(v.associatedData));
    const plain = pattern(v.plaintextLength, (i) => i * 7 + 3);
    const file = await encrypt(key, head, plain, 1);
    assertEquals(encodeHex(file.subarray(PREFIX_LENGTH)), v.ciphertext);
    assertEquals(await decryptFile(file, ikm, decodeHex(v.associatedData)), plain);
  }
});

Deno.test("a damaged, truncated, extended, moved or foreign file never decrypts", async () => {
  const v = VECTORS.file[0];
  const eventKey = decodeHex(v.eventKey);
  const ad = fileAssociatedData(v.eventId, v.deviceId, v.assetId, v.role);
  const head = {
    version: FORMAT_VERSION,
    keyId: await keyIdOf(eventKey),
    salt: decodeHex(v.salt),
    noncePrefix: decodeHex(v.noncePrefix),
  };
  const plain = pattern(v.plaintextLength, (i) => i * 13 + 1);
  const file = await encrypt(await fileKeyOf(eventKey, head.salt, ad), head, plain);

  const flipped = file.slice();
  flipped[file.length - 100] ^= 1;
  await assertRejects(() => decryptFile(flipped, eventKey, ad), EncryptedFileError);
  // Cut exactly at a segment boundary: every remaining segment still authenticates, but the new last one
  // was sealed as not-last.
  const boundary = PREFIX_LENGTH + HEADER_LENGTH + (SEGMENT_LENGTH - HEADER_LENGTH);
  await assertRejects(
    () => decryptFile(file.subarray(0, boundary), eventKey, ad),
    EncryptedFileError,
  );
  await assertRejects(
    () => decryptFile(new Uint8Array([...file, 0]), eventKey, ad),
    EncryptedFileError,
  );
  const elsewhere = fileAssociatedData(v.eventId, v.deviceId, v.assetId, "live");
  await assertRejects(() => decryptFile(file, eventKey, elsewhere), EncryptedFileError);
  await assertRejects(() => decryptFile(file, new Uint8Array(32), ad), EncryptedFileError);
  const future = file.slice();
  future[0] = 2;
  await assertRejects(() => decryptFile(future, eventKey, ad), EncryptedFileError);
});

Deno.test("the head round-trips and refuses a foreign layout", async () => {
  const head = {
    version: FORMAT_VERSION,
    keyId: await keyIdOf(new Uint8Array(32)),
    salt: new Uint8Array(32).fill(7),
    noncePrefix: new Uint8Array(7).fill(9),
  };
  const file = await encrypt(new Uint8Array(32), head, new Uint8Array(0));
  assertEquals(decodeHead(file), head);
  const odd = file.slice();
  odd[PREFIX_LENGTH] = 41;
  let refused = false;
  try {
    decodeHead(odd);
  } catch (e) {
    refused = e instanceof EncryptedFileError;
  }
  assert(refused);
});
