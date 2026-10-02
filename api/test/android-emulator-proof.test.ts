// The emulator's recorded proof (`fixtures/android-emulator-proof.ts`) through the REAL routes: what the
// Android adapter produces is what the api verifies — accepted under the local rig's policy
// (`androidAttestationTrust: any`), refused under a deployed backend's (`hardware`).

import { assertEquals } from "@std/assert";
import { decodeBase64, encodeBase64 } from "@std/encoding";
import { forgetRevocationList } from "../src/android-attest.ts";
import { createApp } from "../src/app.ts";
import { mintChallenge } from "../src/attest.ts";
import type { Config } from "../src/config.ts";
import { readAttestation } from "../src/db.ts";
import {
  ATTESTATION,
  RECORDED_AT,
  RECORDED_PACKAGE,
  RENEWAL,
} from "./fixtures/android-emulator-proof.ts";
import { CONFIG, D, store, V2 } from "./support/harness.ts";

/** The local rig's policy, naming the recording's package. */
const LOCAL: Config = {
  ...CONFIG,
  androidPackageName: RECORDED_PACKAGE,
  androidAttestationTrust: "any",
};

/** Concatenated DER → each element, base64 — what `HttpBackend` sends as `proof.chain`. */
function chainOf(concatenated: string): string[] {
  const bytes = decodeBase64(concatenated);
  const out: string[] = [];
  for (let i = 0; i < bytes.length;) {
    let len = bytes[i + 1];
    let header = 2;
    if (len & 0x80) {
      const n = len & 0x7f;
      len = bytes.slice(i + 2, i + 2 + n).reduce((v, b) => v * 256 + b, 0);
      header = 2 + n;
    }
    out.push(encodeBase64(bytes.slice(i, i + header + len)));
    i += header + len;
  }
  return out;
}

const noStorage = () => Promise.reject(new Error("no storage here"));
const noRevocations = () => Promise.resolve(new Response(JSON.stringify({ entries: {} })));

async function attest(config: Config) {
  forgetRevocationList();
  const db = await store();
  const app = createApp({
    config,
    db,
    fetch: noStorage,
    revocationFetch: noRevocations,
    now: () => RECORDED_AT,
  });
  const res = await app.request("/api/v2/attest/token", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge: await mintChallenge(CONFIG, RECORDED_AT),
      proof: { format: "android-key", chain: chainOf(ATTESTATION) },
    }),
  });
  return { db, res };
}

Deno.test("emulator proof: the local rig accepts the adapter's attestation and records it as Android", async () => {
  const { db, res } = await attest(LOCAL);
  assertEquals(res.status, 201);
  const record = await readAttestation(db, D);
  assertEquals(record?.platform, "android");
  assertEquals(record?.environment, "software");
});

Deno.test("emulator proof: the adapter's renewal signature renews that attestation", async () => {
  const { db } = await attest(LOCAL);
  const app = createApp({
    config: LOCAL,
    db,
    fetch: noStorage,
    revocationFetch: noRevocations,
    now: () => RECORDED_AT + 1000,
  });
  const res = await app.request("/api/v2/attest/renew", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge: await mintChallenge(CONFIG, RECORDED_AT + 1000),
      assertion: RENEWAL,
    }),
  });
  assertEquals(res.status, 201);
});

Deno.test("emulator proof: a deployed backend's policy refuses it — software, unlocked, unpinned", async () => {
  const deployed: Config = { ...LOCAL, androidAttestationTrust: "hardware" };
  const { db, res } = await attest(deployed);
  assertEquals(res.status, 401);
  assertEquals(await readAttestation(db, D), null);
});
