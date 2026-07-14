import { assert, assertEquals } from "@std/assert";
import { createApnsSender } from "../src/apns.ts";
import type { Config } from "../src/config.ts";

// The sender only reads the `apns*` fields; the storage fields are filler for the Config type.
const BASE: Omit<Config, "apnsPrivateKey"> = {
  zone: "z",
  host: "h",
  accessKey: "k",
  s3Region: "de",
  s3Host: "de-s3.storage.bunnycdn.com",
  apnsKeyId: "ABC123KEYID",
  apnsTeamId: "E9Z8BADH58",
  apnsTopic: "app.snapsync",
  attestTokenKey: "test-attest-token-key",
  appAttestRootCa: "",
  attestTokenTtlSeconds: 30 * 24 * 60 * 60,
  attestAppId: "E9Z8BADH58.app.snapsync",
};

// A real P-256 key so `crypto.subtle.sign` actually produces a valid ES256 JWT in-test.
async function genConfig(): Promise<Config> {
  const kp = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  let bin = "";
  for (const b of pkcs8) bin += String.fromCharCode(b);
  const b64 = btoa(bin);
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    b64.match(/.{1,64}/g)!.join("\n")
  }\n-----END PRIVATE KEY-----\n`;
  return { ...BASE, apnsPrivateKey: pem };
}

// A stand-in event id threaded into every send; the sender does not validate it, it just embeds it.
const EVT = "11111111-1111-4111-8111-111111111111";

type Call = { url: string; init: RequestInit };

function recorder(
  responder: (url: string) => Response = () => new Response(null, { status: 200 }),
) {
  const calls: Call[] = [];
  const fetchImpl = (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return Promise.resolve(responder(url));
  };
  return { calls, fetchImpl };
}

Deno.test("sender → production token posts a silent push to api.push.apple.com with the right headers", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  const [outcome] = await createApnsSender(config, fetchImpl)
    .sendSilent([{ kind: "apns", token: "DEADBEEF", env: "production" }], EVT);

  assertEquals(outcome.status, "sent");
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, "https://api.push.apple.com/3/device/DEADBEEF");
  const h = new Headers(calls[0].init.headers);
  assertEquals(h.get("apns-topic"), "app.snapsync");
  assertEquals(h.get("apns-push-type"), "background");
  assertEquals(h.get("apns-priority"), "5");
  assert(h.get("authorization")?.startsWith("bearer "));
  assertEquals(
    calls[0].init.body,
    JSON.stringify({ aps: { "content-available": 1 }, eventId: EVT }),
  );
});

Deno.test("sender → the event id rides alongside the aps object as a top-level key", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  await createApnsSender(config, fetchImpl)
    .sendSilent([{ kind: "apns", token: "T", env: "production" }], EVT);
  const body = JSON.parse(calls[0].init.body as string);
  assertEquals(body.eventId, EVT); // top-level sibling of aps
  assertEquals(body.aps, { "content-available": 1 }); // aps unchanged
});

Deno.test("sender → sandbox token targets api.sandbox.push.apple.com", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  await createApnsSender(config, fetchImpl).sendSilent([{
    kind: "apns",
    token: "T",
    env: "sandbox",
  }], EVT);
  assertEquals(calls[0].url, "https://api.sandbox.push.apple.com/3/device/T");
});

Deno.test("sender → unknown env is skipped with no request", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  const [o] = await createApnsSender(config, fetchImpl)
    .sendSilent([{ kind: "apns", token: "T", env: "staging" }], EVT);
  assertEquals(o.status, "skipped");
  assertEquals(calls.length, 0);
});

Deno.test("sender → non-apns kind is skipped with no request", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  const [o] = await createApnsSender(config, fetchImpl)
    .sendSilent([{ kind: "fcm", token: "T", env: "production" }], EVT);
  assertEquals(o.status, "skipped");
  assertEquals(calls.length, 0);
});

Deno.test("sender → reuses the provider JWT within its lifetime, re-signs after it expires", async () => {
  const config = await genConfig();
  const { calls, fetchImpl } = recorder();
  let t = 1_000_000;
  const sender = createApnsSender(config, fetchImpl, () => t);
  const auth = () => new Headers(calls.at(-1)!.init.headers).get("authorization");

  await sender.sendSilent([{ kind: "apns", token: "A", env: "production" }], EVT);
  const first = auth();
  t += 1_000; // +1s → within TTL
  await sender.sendSilent([{ kind: "apns", token: "B", env: "production" }], EVT);
  assertEquals(auth(), first); // reused (cached), not re-signed
  t += 51 * 60 * 1_000; // +51min → past the 50min TTL
  await sender.sendSilent([{ kind: "apns", token: "C", env: "production" }], EVT);
  assert(auth() !== first, "JWT should be re-signed after its TTL");
});

Deno.test("sender → one rejected token does not stop the batch; each outcome is reported", async () => {
  const config = await genConfig();
  const { fetchImpl } = recorder((url) =>
    url.endsWith("/BAD")
      ? new Response('{"reason":"Unregistered"}', { status: 410 })
      : new Response(null, { status: 200 })
  );
  const outcomes = await createApnsSender(config, fetchImpl).sendSilent([
    { kind: "apns", token: "OK1", env: "production" },
    { kind: "apns", token: "BAD", env: "production" },
    { kind: "apns", token: "OK2", env: "sandbox" },
  ], EVT);
  assertEquals(outcomes.map((o) => o.status), ["sent", "failed", "sent"]);
  assertEquals(outcomes[1].code, 410);
});

Deno.test("sender → a network error is reported as failed, never thrown", async () => {
  const config = await genConfig();
  const sender = createApnsSender(config, () => Promise.reject(new Error("boom")));
  const [o] = await sender.sendSilent([{ kind: "apns", token: "X", env: "production" }], EVT);
  assertEquals(o.status, "failed");
});
