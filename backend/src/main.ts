// Single entry point for both deploy targets — the Hono app (app.ts) holds all logic; this only
// picks the server binding by runtime. Bunny Edge Scripting injects a global `Bunny` and is served
// via its SDK (→ `Bunny.v1.serve`); everywhere else (Deno Deploy, local `deno run`) the native
// `Deno.serve` is used. (The SDK's own serve() does the same switch, but its deno branch binds
// 127.0.0.1:8080 — which Deno Deploy can't route to — so we call Deno.serve directly there.)
//
// Config is read once here; readConfig THROWS on missing env, so a misconfigured deployment fails
// to boot (fail-closed at deploy, not per-request). Tests drive createApp() with injected deps.
// Run locally with: `deno run --allow-net --allow-env src/main.ts`.

import * as BunnySDK from "@bunny.net/edgescript-sdk";
import { createApp } from "./app.ts";
import { readConfig } from "./config.ts";

const app = createApp({ config: readConfig(Deno.env.toObject()), fetch });

if ("Bunny" in globalThis) {
  BunnySDK.net.http.serve(app.fetch);
} else {
  Deno.serve(app.fetch);
}
