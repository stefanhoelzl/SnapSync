// Edge Scripting entry point. Thin wiring only — the Hono app (app.ts) holds the logic. Config is
// read once here; readConfig THROWS on missing env, so a misconfigured deployment fails to boot
// (fail-closed at deploy, not per-request). Tests drive createApp() with injected deps.
// Run locally with: `deno run --allow-net --allow-env src/main.ts`.

import * as BunnySDK from "@bunny.net/edgescript-sdk";
import { createApp } from "./app.ts";
import { readConfig } from "./config.ts";

const app = createApp({ config: readConfig(Deno.env.toObject()), fetch });

BunnySDK.net.http.serve(app.fetch);
