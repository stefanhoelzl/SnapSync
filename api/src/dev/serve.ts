// DEV-ONLY ENTRY POINT for the local backend rig. `deno task dev:local` / `deno task dev:tunnel`.
//
// This is a SECOND top-level alongside `src/main.ts`, not a mode of it. `main.ts` is the Edge Scripting
// entry: it reads the four secrets from the environment and serves `createApp` bound to the global
// `fetch` (i.e. the real bunny zone). This one composes the SAME `createApp` with a filesystem `fetch`
// and a `Config` built from the same source constants. Because `deno bundle src/main.ts` roots the
// deployed bundle at `main.ts`, and `main.ts` reaches nothing under `src/dev/`, none of this can ship —
// structurally, with no flag and no build-time exclusion to get wrong.
//
// It wraps the app with exactly two behaviors, both of which exist so the rig is usable without changing
// a line of `app.ts`:
//
//  1. PRESIGNED DOWNLOADS. `s3Host` and `s3Scheme` are the rig's own origin, so `presignDownloadUrl`
//     mints a real SigV4 URL of the identical production shape pointed home — and pointed at a scheme
//     this server actually speaks, so a device can follow it. Requests under `/<zone>/` are served off
//     disk with the signature IGNORED — bunny's exact acceptance semantics are not reproducible, so
//     validating locally would pin our guess rather than their behavior.
//
//  2. FALLBACK BEARER. The attestation gate stays fully ON. A request that arrives with NO
//     `authorization` header gets a fixed dev token attached — the same trick `test/app.test.ts` uses to
//     avoid threading a header through ~100 call sites — so a bare `curl` works. A request carrying its
//     OWN token is untouched, including an expired or foreign one: it 401s exactly as deployed, and
//     `DeviceAttestation.rejected()` on the device then drops it and re-attests, so crossing backends
//     heals the credential with no operator action. `/attest/*` is ungated either way, so the device's
//     REAL attestation flow runs for real against the rig.
//
//     THE FALLBACK ALSO FILLS AN ABSENT ENROLMENT, and must. A `devices` row is created only by
//     `POST /attest/token` (capability `device-attestation`: a row exists iff the device has attested),
//     and `PUT /api/v1/devices/<id>` — the push registration — now UPDATEs that row and answers 401 when
//     there is none. On a SIMULATOR that is unrecoverable rather than a first-launch round-trip: App
//     Attest does not exist there (`DCAppAttestService.isSupported` is false), so the app never attests,
//     `DeviceAttestation.refresh` returns early without trying, and the registration would 401 forever.
//     Supplying a credential without the enrolment it implies is half a credential, so the fallback
//     supplies both. A caller with its own token is untouched here too — a real device attests for real.

import { createApp } from "../app.ts";
import { mintToken } from "../attest.ts";
import { putAttestation } from "../db.ts";
import { DEV_ATTEST_TTL_MS, enrolmentTarget } from "./fallback.ts";
import { DEV_TOKEN_DEVICE_ID, devConfig } from "./config.ts";
import { sqliteDb } from "./db-sqlite.ts";
import { replay } from "./replay.ts";
import { fsFetch } from "./fs-storage.ts";
import { startTunnel, type Tunnel } from "./tunnel.ts";

const HOST_FILE = ".localdev/host";

type Options = { port: number; store: string; tunnel: boolean; ephemeral: boolean };

function parseOptions(args: string[]): Options {
  const options: Options = { port: 8080, store: ".localstore", tunnel: false, ephemeral: false };
  for (const arg of args) {
    if (arg === "--tunnel") options.tunnel = true;
    else if (arg === "--ephemeral") options.ephemeral = true;
    else if (arg.startsWith("--port=")) options.port = Number(arg.slice("--port=".length));
    else if (arg.startsWith("--store=")) options.store = arg.slice("--store=".length);
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (options.ephemeral) {
    // EPHEMERAL MODE is the rig as a TEST launches it (the backend port contracts' live binding, capability
    // `port-contracts`): the port defaults to `0` so parallel test JVMs never collide, and the tunnel is
    // refused because a test must never be reachable from outside loopback.
    //
    // Its launcher grants exactly `--allow-net=127.0.0.1 --allow-read=<api/>,<store> --allow-write=<store>`
    // and nothing else (measured sufficient, 2026-09-23, deno 2.9). The net grant is the zone guarantee:
    // under it a request to any non-loopback host fails `NotCapable` rather than reaching bunny — the same
    // guarantee `deno task test` gets by withholding `--allow-net` entirely. No `--allow-run`: only the
    // tunnel spawns a process.
    if (options.tunnel) throw new Error("--ephemeral cannot be combined with --tunnel");
    if (!args.some((a) => a.startsWith("--port="))) options.port = 0;
  }
  if (
    !Number.isInteger(options.port) || options.port < 0 ||
    (options.port === 0 && !options.ephemeral)
  ) {
    throw new Error(`invalid --port: ${options.port}`);
  }
  return options;
}

const options = parseOptions(Deno.args);

// The tunnel starts FIRST when requested: its hostname becomes `s3Host`, so it has to be known before the
// Config exists. cloudflared announces the hostname without waiting for the origin to answer.
let tunnel: Tunnel | null = null;
if (options.tunnel) tunnel = await startTunnel(options.port);

// The listener binds BEFORE the app is composed, because an ephemeral port is known only once bound and the
// Config needs the origin. Until `app` exists every request is answered 503 — in ephemeral mode none can
// arrive before the readiness line, which is printed only after composition.
let serveRequest: (request: Request) => Promise<Response> = () =>
  Promise.resolve(new Response("starting", { status: 503 }));
const server = Deno.serve(
  { port: options.port, hostname: "127.0.0.1", onListen: options.ephemeral ? () => {} : undefined },
  (request) => serveRequest(request),
);
const origin = tunnel ? tunnel.origin : `http://127.0.0.1:${server.addr.port}`;
const publicHost = new URL(origin).host;
// Both halves of the origin travel into the Config. The scheme matters because a presigned download URL
// is fetched by the DEVICE: minting `https://` for a plain-HTTP loopback server hands every simulator a
// URL that fails on TLS, which reads as "downloads are inert on this host" rather than as a wrong scheme.
const publicScheme = new URL(origin).protocol.replace(":", "");

const config = devConfig(publicHost, publicScheme);
const storage = fsFetch(config, options.store);
// The rig's relational store (capability `database`): a real SQLite file beside the object store, so a
// local run exercises the same statements the deployed store runs — cascades, the conditional capacity
// insert, the atomic publish — with no credential and no network. It lives INSIDE the store directory so
// `rm -rf` clears both halves at once: clearing one and not the other is the state where the rig looks
// broken for no visible reason.
await Deno.mkdir(options.store, { recursive: true });
const db = sqliteDb(`${options.store}/api.db`);
await replay(db);

const app = createApp({ config, db, fetch: storage });

// One long-lived token for unauthenticated callers. `verifyToken` does not bind a token to the route's
// device id, so this single token authorizes a curl against any device's partition.
const devToken = await mintToken(config, DEV_TOKEN_DEVICE_ID, Date.now());

const presignPrefix = `/${config.zone}/`;

async function handler(request: Request): Promise<Response> {
  const path = new URL(request.url).pathname;

  if (path.startsWith(presignPrefix)) {
    // Re-enter the shim with the storage URL this key would have had, so downloads reuse the exact same
    // disk logic (and Content-Type) as every other read. The signature is not checked.
    const key = path.slice(presignPrefix.length);
    return await storage(`https://${config.host}/${config.zone}/${key}`, {
      method: request.method,
    });
  }

  if (!request.headers.get("authorization")) {
    // The push registration is the one route that needs an enrolment as well as a token. Enrol exactly
    // the device the path names — not a fixed dev id — because the route reads the row by that id.
    const enrol = enrolmentTarget(request.method, path);
    if (enrol) {
      await putAttestation(
        db,
        enrol,
        { publicKey: "dev-rig-not-a-real-attestation", environment: "development" },
        new Date().toISOString(),
        new Date(Date.now() + DEV_ATTEST_TTL_MS).toISOString(),
      );
    }
    const headers = new Headers(request.headers);
    headers.set("authorization", `Bearer ${devToken}`);
    request = new Request(request, { headers });
  }
  return await app.fetch(request);
}

serveRequest = handler;

if (options.ephemeral) {
  // NO host file: `.localdev/host` is how a developer's running rig publishes its origin, and a test run
  // overwriting it would silently repoint their next device build at a server that is about to exit.
  // One greppable line is the readiness signal, written synchronously so it is never lost in a pipe
  // buffer (the launcher reads stdout line by line and waits for exactly this).
  Deno.stdout.writeSync(new TextEncoder().encode(`LIVE-EDGE READY ${origin}\n`));
  // The launcher holds our stdin open for as long as it lives. EOF means it is gone — killed without its
  // shutdown hook running — and a server nobody will ever stop must not outlive it.
  for await (const _ of Deno.stdin.readable) { /* drain until the launcher closes it */ }
  await server.shutdown();
  db.close();
  Deno.exit(0);
}

await Deno.mkdir(".localdev", { recursive: true });
await Deno.writeTextFile(HOST_FILE, origin);

console.log(`
  origin      ${origin}${tunnel ? "  (cloudflared quick tunnel)" : ""}
  store       ${options.store}/        (reset: rm -rf ${options.store})
  host file   ${HOST_FILE}

  device build — point the build at this origin by SELECTING it, not by overriding a build setting:
    1. set  "domain": "${origin.replace(/^https?:\/\//, "")}"  in deployments/local.json
    2. re-run scripts/resolve-deployment.py, then archive

    There is deliberately no xcodebuild-line override. The base rides in the GENERATED
    Deployment.plist, bundled as a resource, and a build setting cannot substitute into a resource
    file — a BACKGROUND_UPLOAD_URL_BASE= on the archive line is accepted and silently does nothing.

  presigned downloads are minted at ${origin}/<zone>/... — this origin's own scheme, so a device
  or a simulator can follow one directly, and so can curl.

  curl works with no authorization header; a request carrying a bad token still 401s.
  a push registration from an un-attested caller (curl, or a SIMULATOR — App Attest does not exist
  there) is enrolled by the rig rather than refused.
`);

// A quick tunnel outlives the process it was spawned from unless it is explicitly killed.
globalThis.addEventListener("unload", () => tunnel?.close());
