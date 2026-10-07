// Android key attestation (capability `privacy-security`, `src/android-attest.ts`) against chains real
// devices produced (`fixtures/android-attestation-samples.ts`, from Google's own verifier's test data), and
// the two routes that take it. The emulator's own recorded proof, through the routes, is
// `android-emulator-proof.test.ts`.

import "reflect-metadata"; // before x509, which needs it at load (see src/android-attest.ts)
import { X509Certificate } from "@peculiar/x509";
import { assert, assertEquals, assertRejects } from "@std/assert";
import { decodeBase64, encodeBase64 } from "@std/encoding";
import {
  forgetRevocationList,
  RevocationUnavailable,
  verifyAndroidAttestation,
  verifyAndroidSignature,
} from "../src/android-attest.ts";
import {
  bytesToB64,
  mintChallenge,
  type RefusalDetail,
  refusalDetail,
  type RefusalReason,
  refusalReason,
} from "../src/attest.ts";
import type { Config } from "../src/config.ts";
import { putAttestation, readAttestation } from "../src/db.ts";
import {
  type AndroidSample,
  FACTORY_TEE_LOCKED,
  FACTORY_TEE_UNLOCKED,
  RKP_STRONGBOX,
  RKP_TEE,
  RKP_TEE_CA1_ROOT,
  SOFTWARE,
} from "./fixtures/android-attestation-samples.ts";
import { CONFIG, createApp, D, NOW, store, V2 } from "./support/harness.ts";

/** The roots every deployed backend pins — read from the committed component, so this tests what ships. */
const GOOGLE_ROOTS: string[] = JSON.parse(
  await Deno.readTextFile(new URL("../../deployments/components/android.json", import.meta.url)),
).androidAttestationRoots;

/** The AOSP software attestation root: the last certificate of the software chain. */
const SOFTWARE_ROOT = pem(SOFTWARE.chain[SOFTWARE.chain.length - 1]);

function pem(b64: string): string {
  return `-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----`;
}

/** A deployed backend's policy, accepting [sample]'s app. */
function hardware(sample: AndroidSample, roots: string[] = GOOGLE_ROOTS): Config {
  return {
    ...CONFIG,
    androidPackageName: sample.packageName,
    androidSigningCertDigests: [sample.signingDigest],
    androidAttestationRoots: roots,
    androidAttestationTrust: "hardware",
  };
}

/** The local rig's policy: software attestation, any digest, any boot state. */
function anyTrust(sample: AndroidSample, roots: string[]): Config {
  return {
    ...hardware(sample, roots),
    androidSigningCertDigests: [],
    androidAttestationTrust: "any",
  };
}

/** A status list: the serials named, with the status given, and a `max-age`. */
function statusList(entries: Record<string, string> = {}, maxAge = 3600) {
  let fetches = 0;
  const fetch = () => {
    fetches++;
    const body = Object.fromEntries(Object.entries(entries).map(([s, status]) => [s, { status }]));
    return Promise.resolve(
      new Response(JSON.stringify({ entries: body }), {
        headers: { "cache-control": `public, max-age=${maxAge}` },
      }),
    );
  };
  return { fetch, fetches: () => fetches };
}

function verify(
  config: Config,
  sample: AndroidSample,
  opts: { chain?: string[]; at?: Date; fetch?: ReturnType<typeof statusList>["fetch"] } = {},
) {
  forgetRevocationList();
  return verifyAndroidAttestation(config, {
    chain: (opts.chain ?? sample.chain).map(decodeBase64),
    expectedChallenge: decodeBase64(sample.challenge),
    at: opts.at ?? new Date(sample.at),
    fetch: opts.fetch ?? statusList().fetch,
  });
}

/** The checks a refusal names `certificate` for: the chain's root, shape, signatures, validity, and revocation. */
const CERTIFICATE_CHECKS = [
  "expired",
  "pinned attestation root",
  "self-signed root",
  "certificates",
  "issuer",
  "revoked",
];

/** Refused for `why` (the logged check), told to the app as `reason`, with `detail` — by default, per check kind. */
const refused = async (
  p: Promise<unknown>,
  why: string,
  reason: RefusalReason = "device-unverifiable",
  detail: RefusalDetail | undefined = CERTIFICATE_CHECKS.some((c) => why.includes(c))
    ? "certificate"
    : undefined,
) => {
  const e = await assertRejects(() => p, Error, why);
  assertEquals(refusalReason(e), reason, `the reason told for "${why}"`);
  assertEquals(refusalDetail(e), detail, `the detail named for "${why}"`);
};

// ── What a genuine device proves ────────────────────────────────────────────────────────────────────

Deno.test("android: a factory-provisioned TEE key on a locked, verified phone is accepted", async () => {
  const verified = await verify(hardware(FACTORY_TEE_LOCKED), FACTORY_TEE_LOCKED);
  assertEquals(verified.securityLevel, "tee");
  assertEquals(verified.publicKey.length, 65);
  assertEquals(verified.publicKey[0], 0x04);
});

Deno.test("android: a factory chain's EXPIRED intermediates are no refusal — its keys cannot be rotated", async () => {
  // Its intermediates and root certificate expired on 2026-05-24. Google: "still trustworthy unless they
  // appear in the certificate revocation list".
  const verified = await verify(hardware(FACTORY_TEE_LOCKED), FACTORY_TEE_LOCKED, {
    at: new Date("2026-09-29T00:00:00Z"),
  });
  assertEquals(verified.securityLevel, "tee");
});

Deno.test("android: a remotely provisioned TEE key is accepted, its root matched by KEY across re-issues", async () => {
  // This chain ends at the 2019 certificate of Google's RSA root; the deployment pins the 2022 one.
  const verified = await verify(hardware(RKP_TEE), RKP_TEE);
  assertEquals(verified.securityLevel, "tee");
});

Deno.test("android: a StrongBox key reports StrongBox", async () => {
  assertEquals((await verify(hardware(RKP_STRONGBOX), RKP_STRONGBOX)).securityLevel, "strongbox");
});

Deno.test("android: a chain under Google's 2026 root (Key Attestation CA1) is accepted", async () => {
  assertEquals((await verify(hardware(RKP_TEE_CA1_ROOT), RKP_TEE_CA1_ROOT)).securityLevel, "tee");
});

// ── What it must not ────────────────────────────────────────────────────────────────────────────────

Deno.test("android: an RKP chain's expiry IS enforced — its short life is its threat model", async () => {
  await refused(
    verify(hardware(RKP_TEE), RKP_TEE, { at: new Date("2026-09-29T00:00:00Z") }),
    "expired",
  );
});

Deno.test("android: an UNLOCKED bootloader is refused where hardware is required", async () => {
  await refused(
    verify(hardware(FACTORY_TEE_UNLOCKED), FACTORY_TEE_UNLOCKED),
    "locked bootloader",
    "device-modified",
  );
});

Deno.test("android: a SOFTWARE attestation is refused where hardware is required — even under a pinned root", async () => {
  await refused(
    verify(hardware(SOFTWARE, [...GOOGLE_ROOTS, SOFTWARE_ROOT]), SOFTWARE),
    "software attestation",
    "device-modified",
  );
});

Deno.test("android: a chain ending at no pinned root is refused", async () => {
  await refused(verify(hardware(SOFTWARE), SOFTWARE), "pinned attestation root");
  await refused(verify(hardware(RKP_TEE, [SOFTWARE_ROOT]), RKP_TEE), "pinned attestation root");
});

Deno.test("android: another challenge is refused", async () => {
  const other = {
    ...RKP_TEE,
    challenge: encodeBase64(new TextEncoder().encode("not the challenge")),
  };
  await refused(verify(hardware(RKP_TEE), other), "challenge");
});

Deno.test("android: another app, or another signing certificate, is refused", async () => {
  await refused(
    verify({ ...hardware(RKP_TEE), androidPackageName: "app.snapsync" }, RKP_TEE),
    "not this app",
    "app-not-genuine",
  );
  const digest = Array(32).fill("AB").join(":");
  await refused(
    verify({ ...hardware(RKP_TEE), androidSigningCertDigests: [digest] }, RKP_TEE),
    "signed with",
    "app-not-genuine",
  );
  // No digest at all: no Android device.
  await refused(
    verify({ ...hardware(RKP_TEE), androidSigningCertDigests: [] }, RKP_TEE),
    "signed with",
    "app-not-genuine",
  );
});

Deno.test("android: a chain any longer or shorter than its provisioning shape is refused", async () => {
  // A certificate appended below the key — the shape of the chain-extension attack — or one removed.
  const extended = [RKP_TEE.chain[0], ...RKP_TEE.chain];
  await refused(verify(hardware(RKP_TEE), RKP_TEE, { chain: extended }), "certificates");
  await refused(
    verify(hardware(RKP_TEE), RKP_TEE, { chain: RKP_TEE.chain.slice(1) }),
    "certificates",
  );
});

Deno.test("android: a chain whose certificates are not signed by each other is refused", async () => {
  // The key of one device over the intermediates of another.
  const spliced = [RKP_STRONGBOX.chain[0], ...RKP_TEE.chain.slice(1)];
  await refused(verify(hardware(RKP_TEE), RKP_TEE, { chain: spliced }), "issuer");
});

// ── Revocation ──────────────────────────────────────────────────────────────────────────────────────

Deno.test("android: a REVOKED or SUSPENDED certificate anywhere in the chain is refused", async () => {
  const intermediate = new X509Certificate(decodeBase64(RKP_TEE.chain[2])).serialNumber
    .toLowerCase()
    .replace(/^0+/, "");
  for (const status of ["REVOKED", "SUSPENDED"]) {
    await refused(
      verify(hardware(RKP_TEE), RKP_TEE, { fetch: statusList({ [intermediate]: status }).fetch }),
      "revoked",
    );
  }
});

Deno.test("android: a status list that cannot be fetched is 'could not look', never a refusal", async () => {
  const down = () => Promise.resolve(new Response("", { status: 503 }));
  await assertRejects(
    () => verify(hardware(RKP_TEE), RKP_TEE, { fetch: down }),
    RevocationUnavailable,
  );
});

Deno.test("android: the status list is fetched once per max-age, not per attestation", async () => {
  const list = statusList({}, 600);
  forgetRevocationList();
  const at = new Date(RKP_TEE.at);
  const opts = {
    chain: RKP_TEE.chain.map(decodeBase64),
    expectedChallenge: decodeBase64(RKP_TEE.challenge),
    fetch: list.fetch,
  };
  await verifyAndroidAttestation(hardware(RKP_TEE), { ...opts, at });
  await verifyAndroidAttestation(hardware(RKP_TEE), {
    ...opts,
    at: new Date(at.getTime() + 599_000),
  });
  assertEquals(list.fetches(), 1);
  await verifyAndroidAttestation(hardware(RKP_TEE), {
    ...opts,
    at: new Date(at.getTime() + 601_000),
  });
  assertEquals(list.fetches(), 2);
});

// ── The local rig's policy ──────────────────────────────────────────────────────────────────────────

Deno.test("android: under trust 'any' the software attestation and the unlocked phone are accepted", async () => {
  assertEquals(
    (await verify(anyTrust(SOFTWARE, [SOFTWARE_ROOT]), SOFTWARE)).securityLevel,
    "software",
  );
  assertEquals(
    (await verify(anyTrust(FACTORY_TEE_UNLOCKED, GOOGLE_ROOTS), FACTORY_TEE_UNLOCKED))
      .securityLevel,
    "tee",
  );
});

Deno.test("android: trust 'any' pins no root — the emulator's is minted per AVD — but still needs the rest", async () => {
  // A root no deployment names is accepted here, as the emulator's "Google Test LLC" root must be.
  assertEquals(
    (await verify(anyTrust(SOFTWARE, GOOGLE_ROOTS), SOFTWARE)).securityLevel,
    "software",
  );
  // The chain itself is still verified: another device's key over this chain is refused.
  const spliced = [RKP_STRONGBOX.chain[0], ...RKP_TEE.chain.slice(1)];
  await refused(verify(anyTrust(RKP_TEE, []), RKP_TEE, { chain: spliced }), "issuer");
  const other = { ...SOFTWARE, challenge: encodeBase64(new TextEncoder().encode("x")) };
  await refused(verify(anyTrust(SOFTWARE, [SOFTWARE_ROOT]), other), "challenge");
  await refused(
    verify(
      { ...anyTrust(SOFTWARE, [SOFTWARE_ROOT]), androidPackageName: "app.snapsync" },
      SOFTWARE,
    ),
    "not this app",
    "app-not-genuine",
  );
});

// ── Renewal ─────────────────────────────────────────────────────────────────────────────────────────

/** A P-256 key standing for an attested Keystore key, and a DER signature by it, as the Keystore makes one. */
async function keystoreKey() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ]);
  const publicKey = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const sign = async (challenge: string) =>
    derOf(
      new Uint8Array(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          pair.privateKey,
          new TextEncoder().encode(challenge),
        ),
      ),
    );
  return { publicKey, sign };
}

/** raw `r||s` → DER `SEQUENCE { INTEGER r, INTEGER s }`, as `SHA256withECDSA` emits it. */
function derOf(raw: Uint8Array): Uint8Array {
  const int = (v: Uint8Array) => {
    let i = 0;
    while (i < v.length - 1 && v[i] === 0) i++;
    const body = v[i] & 0x80 ? [0, ...v.slice(i)] : [...v.slice(i)];
    return [0x02, body.length, ...body];
  };
  const seq = [...int(raw.slice(0, 32)), ...int(raw.slice(32))];
  return new Uint8Array([0x30, seq.length, ...seq]);
}

Deno.test("android renew: a signature by the attested key over the challenge verifies, any other does not", async () => {
  const key = await keystoreKey();
  await verifyAndroidSignature({
    signature: await key.sign("c1"),
    challenge: "c1",
    publicKey: key.publicKey,
  });
  await refused(
    verifyAndroidSignature({
      signature: await key.sign("c1"),
      challenge: "c2",
      publicKey: key.publicKey,
    }),
    "does not verify",
  );
  const other = await keystoreKey();
  await refused(
    verifyAndroidSignature({
      signature: await other.sign("c1"),
      challenge: "c1",
      publicKey: key.publicKey,
    }),
    "does not verify",
  );
});

// ── The routes ──────────────────────────────────────────────────────────────────────────────────────

Deno.test("route: an Android row renews by its SIGNATURE, and the row says what proved it", async () => {
  const db = await store();
  const key = await keystoreKey();
  await putAttestation(
    db,
    D,
    { publicKey: bytesToB64(key.publicKey), platform: "android", environment: "tee" },
    "t0",
    "e0",
  );
  const app = createApp({
    config: CONFIG,
    db,
    fetch: () => Promise.reject(new Error("no network here")),
  });
  const challenge = await mintChallenge(CONFIG, NOW);
  const res = await app.request("/api/v2/attest/renew", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge,
      assertion: bytesToB64(await key.sign(challenge)),
    }),
  });
  assertEquals(res.status, 201);
  assert((await res.json()).token);
  assertEquals((await readAttestation(db, D))?.platform, "android");
});

Deno.test("route: an Android row refuses anything but its own key's signature", async () => {
  const db = await store();
  const key = await keystoreKey();
  await putAttestation(
    db,
    D,
    { publicKey: bytesToB64(key.publicKey), platform: "android", environment: "tee" },
    "t0",
    "e0",
  );
  const app = createApp({
    config: CONFIG,
    db,
    fetch: () => Promise.reject(new Error("no network here")),
  });
  const challenge = await mintChallenge(CONFIG, NOW);
  const other = await keystoreKey();
  const res = await app.request("/api/v2/attest/renew", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge,
      assertion: bytesToB64(await other.sign(challenge)),
    }),
  });
  assertEquals(res.status, 401);
});

Deno.test("route: an Android attestation whose status list cannot be fetched is 502, recording nothing", async () => {
  forgetRevocationList();
  const db = await store();
  // A deployment accepting this chain's app; the route judges its validity at the harness's NOW, which
  // the factory chain survives (its expiry is not enforced).
  const config = hardware(FACTORY_TEE_LOCKED);
  const app = createApp({
    config,
    db,
    fetch: () => Promise.resolve(new Response("", { status: 503 })),
  });
  const res = await app.request("/api/v2/attest/token", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge: await mintChallenge(config, NOW),
      proof: { format: "android-key", chain: FACTORY_TEE_LOCKED.chain },
    }),
  });
  assertEquals(res.status, 502);
  assertEquals(await readAttestation(db, D), null);
});

Deno.test("route: a genuine Android chain over ANOTHER challenge is 401, recording nothing", async () => {
  forgetRevocationList();
  const db = await store();
  const config = hardware(FACTORY_TEE_LOCKED);
  const list = statusList();
  const app = createApp({
    config,
    db,
    fetch: () => Promise.reject(new Error("no storage here")),
    revocationFetch: list.fetch,
  });
  const res = await app.request("/api/v2/attest/token", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge: await mintChallenge(config, NOW),
      proof: { format: "android-key", chain: FACTORY_TEE_LOCKED.chain },
    }),
  });
  assertEquals(res.status, 401);
  assertEquals(await res.text(), "attestation rejected: device-unverifiable");
  assertEquals(await readAttestation(db, D), null);
});

Deno.test("route: a chain under no pinned root is 401 naming a certificate problem, recording nothing", async () => {
  // The OnePlus case (Bugsink SNAPSYNC-42..44): the default reason, and the one diagnostic code.
  forgetRevocationList();
  const db = await store();
  const config = hardware(FACTORY_TEE_LOCKED, [SOFTWARE_ROOT]);
  const app = createApp({
    config,
    db,
    fetch: () => Promise.reject(new Error("no storage here")),
    revocationFetch: statusList().fetch,
  });
  const res = await app.request("/api/v2/attest/token", {
    method: "POST",
    headers: V2,
    body: JSON.stringify({
      deviceId: D,
      challenge: await mintChallenge(config, NOW),
      proof: { format: "android-key", chain: FACTORY_TEE_LOCKED.chain },
    }),
  });
  assertEquals(res.status, 401);
  assertEquals(await res.text(), "attestation rejected: device-unverifiable (certificate)");
  assertEquals(await readAttestation(db, D), null);
});
