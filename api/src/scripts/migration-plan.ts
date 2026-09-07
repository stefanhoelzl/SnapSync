// DOES THIS DEPLOY TOUCH THE SCHEMA? (capabilities `database`, `backend-deployment`)
//
// `api-deploy.yml` branches a MAINTENANCE WINDOW on this answer, so it is the most consequential thing
// in the pipeline that is not itself a deploy step. It asks `bunny db migrations list` — which never
// creates the tracking table, so asking is read-only against a store that has never migrated — and
// re-emits the answer as the exit-code contract the workflow already speaks.
//
// THREE OUTCOMES, NOT TWO, and that is the whole point of the exit codes. A crash also exits non-zero,
// so `0`/`1` would make "nothing pending" and "the check blew up" the same answer. A crash read as
// "nothing pending" would publish the new bundle onto an un-migrated store — the exact failure the
// window exists to prevent. So: 0 = none, 10 = pending, anything else = failed, and the workflow treats
// every unrecognised code as fatal.
//
// IT REPORTS DRIFT BUT DOES NOT GATE ON IT. `modified`/`missing`/`out_of_order` history blocks
// `apply` — the deploy never passes `--allow-drift` — and that refusal happens inside the window, which
// the restore path lifts. Duplicating the refusal here was considered and declined: two implementations
// of "is this history sound?" can disagree, and the one that matters is the one that applies. So drift is
// named in the plan line, loudly, and left to fail where it actually fails.
//
// Out of the bundle: `main.ts` never reaches it.

import { MIGRATIONS_DIR } from "../dev/replay.ts";

/** Exit code for "at least one migration is unapplied". Read by `api-deploy.yml`. */
export const PENDING_EXIT_CODE = 10;

/** One entry of `bunny db migrations list --output json`. */
export type MigrationState = {
  name: string;
  state: "applied" | "pending" | "modified" | "missing" | "out_of_order";
  applied_at: string | null;
};

/** The shape the CLI prints. Only `migrations` is load-bearing here. */
export type ListOutput = { migrations: MigrationState[] };

export type Plan = {
  pending: string[];
  applied: string[];
  /** History the CLI will refuse to apply over. Reported, never gated on — see the header. */
  issues: MigrationState[];
};

/** Read the CLI's JSON into the decision. Pure, so the contract below is testable with no network. */
export function planFrom(output: ListOutput): Plan {
  const migrations = output.migrations ?? [];
  return {
    pending: migrations.filter((m) => m.state === "pending").map((m) => m.name),
    applied: migrations.filter((m) => m.state === "applied").map((m) => m.name),
    issues: migrations.filter((m) =>
      m.state === "modified" || m.state === "missing" || m.state === "out_of_order"
    ),
  };
}

/** The single greppable line a workflow log is read for: the exit code decides, this says why. */
export function planLine(plan: Plan): string {
  const drift = plan.issues.length === 0
    ? ""
    : ` ⚠ ${plan.issues.length} with unsound history (${
      plan.issues.map((i) => `${i.name}:${i.state}`).join(", ")
    }) — apply will refuse`;
  return plan.pending.length === 0
    ? `MIGRATE PLAN: none (${plan.applied.length} applied)${drift}`
    : `MIGRATE PLAN: ${plan.pending.length} pending (${plan.pending.join(", ")})${drift}`;
}

if (import.meta.main) {
  const url = Deno.env.get("BUNNY_DATABASE_URL");
  const token = Deno.env.get("BUNNY_DATABASE_AUTH_TOKEN");
  if (!url || !token) {
    console.error("missing configuration: BUNNY_DATABASE_URL, BUNNY_DATABASE_AUTH_TOKEN");
    Deno.exit(1);
  }

  // `--dir` is passed EXPLICITLY rather than relying on the CLI's cwd-relative default, so this and the
  // in-repository replayer cannot end up reading different sets of files.
  const listing = new Deno.Command("bunny", {
    args: [
      "db",
      "migrations",
      "list",
      "--dir",
      MIGRATIONS_DIR,
      "--url",
      url,
      "--token",
      token,
      "--output",
      "json",
    ],
    stdout: "piped",
    stderr: "piped",
  });

  const { code, stdout, stderr } = await listing.output();
  if (code !== 0) {
    console.error(new TextDecoder().decode(stderr).trim());
    console.error(`bunny db migrations list exited ${code}`);
    Deno.exit(1);
  }

  const plan = planFrom(JSON.parse(new TextDecoder().decode(stdout)) as ListOutput);
  console.log(planLine(plan));
  Deno.exit(plan.pending.length === 0 ? 0 : PENDING_EXIT_CODE);
}
