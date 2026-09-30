import { assert, assertEquals } from "@std/assert";
import { decodeJwt, decodeProtectedHeader } from "jose";
import { createFcmSender, fcmBody } from "../src/fcm.ts";

// A real RSA key, so jose really signs the RS256 assertion the token exchange carries.
async function serviceAccountKey(): Promise<string> {
  const kp = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
  let bin = "";
  for (const b of pkcs8) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    btoa(bin).match(/.{1,64}/g)!.join("\n")
  }\n-----END PRIVATE KEY-----\n`;
  return JSON.stringify({
    client_email: "sender@test-project.iam.gserviceaccount.com",
    private_key: pem,
  });
}

const PROJECT = "test-project";
const EVT = "11111111-1111-4111-8111-111111111111";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SEND_URL = `https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`;

type Call = { url: string; init: RequestInit };

/** Google's two endpoints, faked: the token exchange answers [tokenStatus], each send answers [sendStatus]. */
function google(tokenStatus = 200, sendStatus = 200) {
  const calls: Call[] = [];
  const fetchImpl = (url: string, init: RequestInit) => {
    calls.push({ url, init });
    if (url === TOKEN_URL) {
      return Promise.resolve(
        tokenStatus === 200
          ? Response.json({ access_token: "ACCESS", expires_in: 3599, token_type: "Bearer" })
          : new Response("denied", { status: tokenStatus }),
      );
    }
    return Promise.resolve(
      new Response('{"name":"projects/p/messages/1"}', { status: sendStatus }),
    );
  };
  return { calls, fetchImpl, sends: () => calls.filter((c) => c.url === SEND_URL) };
}

async function sender(
  fetchImpl: (url: string, init: RequestInit) => Promise<Response>,
  now = () => 0,
) {
  return createFcmSender(
    { fcmProjectId: PROJECT, fcmServiceAccountKey: await serviceAccountKey() },
    fetchImpl,
    now,
  );
}

Deno.test("fcm → a token of this project is sent a high-priority data message carrying only the event id", async () => {
  const g = google();
  const [o] = await (await sender(g.fetchImpl)).sendSilent([{
    kind: "fcm",
    token: "F1",
    env: PROJECT,
  }], EVT);

  assertEquals(o.status, "sent");
  const [send] = g.sends();
  assertEquals(new Headers(send.init.headers).get("authorization"), "Bearer ACCESS");
  assertEquals(JSON.parse(send.init.body as string), {
    message: {
      token: "F1",
      data: { eventId: EVT },
      android: { priority: "HIGH", collapse_key: EVT },
    },
  });
  assertEquals(send.init.body, fcmBody("F1", EVT));
});

Deno.test("fcm → the token exchange presents a signed service-account assertion for the messaging scope", async () => {
  const g = google();
  await (await sender(g.fetchImpl, () => 1_000_000)).sendSilent([{
    kind: "fcm",
    token: "F1",
    env: PROJECT,
  }], EVT);

  const exchange = g.calls.find((c) => c.url === TOKEN_URL)!;
  const form = new URLSearchParams(exchange.init.body as string);
  assertEquals(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
  const assertion = form.get("assertion")!;
  assertEquals(decodeProtectedHeader(assertion).alg, "RS256");
  const claims = decodeJwt(assertion);
  assertEquals(claims.iss, "sender@test-project.iam.gserviceaccount.com");
  assertEquals(claims.aud, TOKEN_URL);
  assertEquals(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
  assertEquals(claims.iat, 1000);
  assertEquals(claims.exp, 1000 + 3600);
});

Deno.test("fcm → the access token is reused within its lifetime and renewed after", async () => {
  const g = google();
  let t = 0;
  const s = await sender(g.fetchImpl, () => t);
  const tokens = [{ kind: "fcm", token: "F1", env: PROJECT }, {
    kind: "fcm",
    token: "F2",
    env: PROJECT,
  }];
  await s.sendSilent(tokens, EVT);
  t = 49 * 60 * 1000;
  await s.sendSilent(tokens, EVT);
  assertEquals(
    g.calls.filter((c) => c.url === TOKEN_URL).length,
    1,
    "one exchange serves every send for 50 min",
  );
  t = 51 * 60 * 1000;
  await s.sendSilent(tokens, EVT);
  assertEquals(g.calls.filter((c) => c.url === TOKEN_URL).length, 2, "renewed once it is old");
});

Deno.test("fcm → a token of another project, another kind, or with no key configured is skipped with no request", async () => {
  const g = google();
  const s = await sender(g.fetchImpl);
  const outcomes = await s.sendSilent([
    { kind: "fcm", token: "F1", env: "another-project" },
    { kind: "apns", token: "A1", env: "production" },
  ], EVT);
  assertEquals(outcomes.map((o) => o.status), ["skipped", "skipped"]);

  const keyless = createFcmSender({ fcmProjectId: PROJECT, fcmServiceAccountKey: "" }, g.fetchImpl);
  const [o] = await keyless.sendSilent([{ kind: "fcm", token: "F1", env: PROJECT }], EVT);
  assertEquals(o.status, "skipped");
  assertEquals(g.calls.length, 0, "nothing was requested");
});

Deno.test("fcm → a refused exchange or a refused send is a failed outcome, never a throw", async () => {
  const refusedExchange = google(401);
  const [a] = await (await sender(refusedExchange.fetchImpl))
    .sendSilent([{ kind: "fcm", token: "F1", env: PROJECT }], EVT);
  assertEquals(a.status, "failed");
  assert(a.reason?.startsWith("auth:"));

  const unregistered = google(200, 404);
  const [b] = await (await sender(unregistered.fetchImpl))
    .sendSilent([{ kind: "fcm", token: "F1", env: PROJECT }], EVT);
  assertEquals(b.status, "failed");
  assertEquals(b.code, 404);
});
