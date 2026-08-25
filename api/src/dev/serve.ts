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

import { createApp } from "../app.ts";
import { mintToken } from "../attest.ts";
import { DEV_TOKEN_DEVICE_ID, devConfig } from "./config.ts";
import { fsFetch } from "./fs-storage.ts";
import { startTunnel, type Tunnel } from "./tunnel.ts";

const HOST_FILE = ".localdev/host";

type Options = { port: number; store: string; tunnel: boolean };

function parseOptions(args: string[]): Options {
  const options: Options = { port: 8080, store: ".localstore", tunnel: false };
  for (const arg of args) {
    if (arg === "--tunnel") options.tunnel = true;
    else if (arg.startsWith("--port=")) options.port = Number(arg.slice("--port=".length));
    else if (arg.startsWith("--store=")) options.store = arg.slice("--store=".length);
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!Number.isInteger(options.port) || options.port <= 0) {
    throw new Error(`invalid --port: ${options.port}`);
  }
  return options;
}

const options = parseOptions(Deno.args);

// The tunnel starts FIRST when requested: its hostname becomes `s3Host`, so it has to be known before the
// Config exists. cloudflared announces the hostname without waiting for the origin to answer.
let tunnel: Tunnel | null = null;
if (options.tunnel) tunnel = await startTunnel(options.port);
const origin = tunnel ? tunnel.origin : `http://127.0.0.1:${options.port}`;
const publicHost = new URL(origin).host;
// Both halves of the origin travel into the Config. The scheme matters because a presigned download URL
// is fetched by the DEVICE: minting `https://` for a plain-HTTP loopback server hands every simulator a
// URL that fails on TLS, which reads as "downloads are inert on this host" rather than as a wrong scheme.
const publicScheme = new URL(origin).protocol.replace(":", "");

const config = devConfig(publicHost, publicScheme);
const storage = fsFetch(config, options.store);
const app = createApp({ config, fetch: storage });

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
    const headers = new Headers(request.headers);
    headers.set("authorization", `Bearer ${devToken}`);
    request = new Request(request, { headers });
  }
  return await app.fetch(request);
}

await Deno.mkdir(".localdev", { recursive: true });
await Deno.writeTextFile(HOST_FILE, origin);

console.log(`
  origin      ${origin}${tunnel ? "  (cloudflared quick tunnel)" : ""}
  store       ${options.store}/        (reset: rm -rf ${options.store})
  host file   ${HOST_FILE}

  device build — paste onto the ssh-mac xcodebuild archive line:
    BACKGROUND_UPLOAD_URL_BASE=${origin}/api/v1

  presigned downloads are minted at ${origin}/<zone>/... — this origin's own scheme, so a device
  or a simulator can follow one directly, and so can curl.

  curl works with no authorization header; a request carrying a bad token still 401s.
`);

Deno.serve({ port: options.port, hostname: "127.0.0.1" }, handler);

// A quick tunnel outlives the process it was spawned from unless it is explicitly killed.
globalThis.addEventListener("unload", () => tunnel?.close());
