// Entry point for the one deploy target — bunny Edge Scripting. The Hono app (app.ts) holds all logic;
// this only reads config and serves. The SDK's serve() is runtime-aware: under Edge Scripting it binds
// the injected global `Bunny` (→ `Bunny.v1.serve`), and everywhere else (a local `deno run`) it binds
// 127.0.0.1:8080 — which is exactly what local dev wants. (It used to be branched: Deno Deploy could not
// route to 127.0.0.1, so that leg called `Deno.serve` directly. Deno Deploy is retired — see
// changes/archive/…-migrate-runtime-to-bunny — and the branch retired with it.)
//
// Config is read once here. Only two SECRETS come from the environment (the storage AccessKey and the
// APNs .p8 PEM); every non-secret value is a source constant, so a deploy cannot ship code whose config
// is missing. readConfig THROWS on a missing secret, so a misconfigured deployment fails to boot
// (fail-closed at deploy, not per-request). Tests drive createApp() with injected deps.
// Run locally with: `deno run --allow-net --allow-env src/main.ts`.

import * as BunnySDK from "@bunny.net/edgescript-sdk";
import { createApp } from "./app.ts";
import { readConfig } from "./config.ts";

const app = createApp({ config: readConfig(Deno.env.toObject()), fetch });

BunnySDK.net.http.serve(app.fetch);
