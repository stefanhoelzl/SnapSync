#!/usr/bin/env node
/**
 * Client-side merge queue for /ship command.
 *
 * Waits for PRs ahead in queue, rebases when it's our turn,
 * applies committed rulesets (.github/rulesets/*.json, if any),
 * waits for CI, and confirms merge completion.
 *
 * Usage: pnpx tsx .claude/commands/ship-wait.ts <repo> <pr-number> <default-branch>
 *
 * This script is invoked by /ship as a BACKGROUND shell (`run_in_background: true`), because
 * the Bash tool caps a foreground call at 600_000 ms and clamps larger requests silently — a
 * budget longer than that could never be observed. /ship reads the outcome back out of the
 * background shell's output file via the single `SHIP-WAIT RESULT:` line printed by finish().
 *
 * Exit codes (and the matching result line):
 *   0 - MERGED: PR successfully merged
 *   1 - FAILED: PR failed (CI failed, conflicts, closed, etc.)
 *   2 - TIMEOUT: the watcher stopped looking. NOT a merge failure — auto-merge stays armed
 *       server-side, so the PR still lands on its own once its checks pass.
 *
 * Environment:
 *   Requires `gh` CLI to be authenticated.
 */

import { spawn } from "node:child_process";
import { existsSync, readdirSync, readFileSync, writeSync } from "node:fs";
import { join } from "node:path";

const RULESETS_DIR = ".github/rulesets";
const POLL_INTERVAL_MS = 30_000;
// Measured over 80 merged PRs (createdAt→mergedAt, which is this script's own window):
// p50 12 min, p75 16, p90 26, p95 34, max 51. 20 minutes covers ~84% of ships. It is
// deliberately not set to cover the tail: exceeding it costs a TIMEOUT report, and a TIMEOUT
// is cheap because auto-merge is already enabled on GitHub — the PR merges without us. Budget
// for the common case and diagnose a wedge early, rather than stall on every wedge to spare
// the occasional slow-but-healthy ship.
const TIMEOUT_MS = 1_200_000; // 20 minutes, whole run
// Sub-budget on the CI watch, the one phase with hard numbers: 19 successful non-main ios.yml
// runs took 7–14 min. 15 min is ~1.1× the measured max, so a wedged required check is
// diagnosed here rather than absorbed by the global budget.
const CI_WATCH_TIMEOUT_MS = 900_000; // 15 minutes
const MERGE_WAIT_TIMEOUT_MS = 120_000; // 2 minutes
const MERGE_POLL_INTERVAL_MS = 5000;
const COMMAND_TIMEOUT_MS = 60_000;
const CHECKS_APPEAR_POLL_MS = 5000;
const CHECKS_APPEAR_MAX_ATTEMPTS = 12;
const EXPECTED_ARGS = 3;

type FailingConclusion =
	| "FAILURE"
	| "CANCELLED"
	| "TIMED_OUT"
	| "ACTION_REQUIRED";

const FAILING_CONCLUSIONS: ReadonlySet<string> = new Set<FailingConclusion>([
	"FAILURE",
	"CANCELLED",
	"TIMED_OUT",
	"ACTION_REQUIRED",
]);

interface StatusCheck {
	name?: string;
	conclusion?: string | null;
}

interface PullRequest {
	number: number;
	createdAt: string;
	autoMergeRequest: { enabledAt: string } | null;
	state: "OPEN" | "MERGED" | "CLOSED";
	headRefName: string;
	mergeStateStatus?: string;
	statusCheckRollup?: StatusCheck[];
}

interface PullRequestState {
	state: "OPEN" | "MERGED" | "CLOSED";
	mergeStateStatus: string;
}

type SkipReason =
	| { kind: "closed" }
	| { kind: "dirty" }
	| { kind: "check-failed"; checkName: string; conclusion: string };

function formatSkipReason(reason: SkipReason): string {
	if (reason.kind === "closed") {
		return "CLOSED";
	}
	if (reason.kind === "dirty") {
		return "DIRTY";
	}
	return `check '${reason.checkName}' ${reason.conclusion}`;
}

function classifyAhead(
	pr: PullRequest,
	requiredChecks: ReadonlySet<string>,
): SkipReason | null {
	if (pr.state === "CLOSED") {
		return { kind: "closed" };
	}
	if (pr.mergeStateStatus === "DIRTY") {
		return { kind: "dirty" };
	}
	if (pr.mergeStateStatus === "UNKNOWN") {
		return null;
	}
	for (const check of pr.statusCheckRollup ?? []) {
		// Only REQUIRED checks can disqualify a queued PR — auto-merge itself waits on required
		// checks only, so a red non-required check (the migration beacon is red by design) never
		// stops that PR from merging, and skipping it here would desync the queue from GitHub's
		// own behavior. Empty set = the required-checks fetch failed → strict fallback: every
		// check counts.
		if (requiredChecks.size > 0 && check.name && !requiredChecks.has(check.name)) {
			continue;
		}
		const conclusion = check.conclusion;
		if (conclusion && FAILING_CONCLUSIONS.has(conclusion)) {
			return {
				kind: "check-failed",
				checkName: check.name ?? "<unnamed>",
				conclusion,
			};
		}
	}
	return null;
}

function log(message: string): void {
	const timestamp = new Date().toISOString();
	console.log(`[${timestamp}] ${message}`);
}

type ShipResult = "MERGED" | "FAILED" | "TIMEOUT";

const EXIT_CODE: Record<ShipResult, number> = {
	MERGED: 0,
	FAILED: 1,
	TIMEOUT: 2,
};

/**
 * The one line /ship reads back out of the background shell's output file. EVERY exit routes
 * through here — including the signal handlers and the catch-all — so a missing line means the
 * process died without running its own code. /ship reports that as UNKNOWN rather than guessing,
 * because "the wait failed" and "we never learned the outcome" have different consequences.
 *
 * writeSync, not console.log: when stdout is a pipe Node writes it asynchronously, and
 * process.exit() would truncate the very line the whole mechanism depends on.
 */
function finish(result: ShipResult, reason: string): never {
	writeSync(1, `SHIP-WAIT RESULT: ${result} (${reason})\n`);
	process.exit(EXIT_CODE[result]);
}

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

function exec(
	command: string,
	timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<string> {
	return new Promise((resolve, reject) => {
		const proc = spawn(command, [], {
			stdio: ["pipe", "pipe", "pipe"],
			shell: true,
		});

		let stdout = "";
		let stderr = "";

		const timeout = setTimeout(() => {
			proc.kill();
			reject(new Error(`Command timed out after ${timeoutMs}ms: ${command}`));
		}, timeoutMs);

		proc.stdout.on("data", (data: Buffer) => {
			stdout += data.toString();
		});
		proc.stderr.on("data", (data: Buffer) => {
			stderr += data.toString();
		});

		proc.on("close", (code) => {
			clearTimeout(timeout);
			if (code === 0) {
				resolve(stdout.trim());
			} else {
				reject(new Error(`Command failed: ${command}\n${stderr || stdout}`));
			}
		});

		proc.on("error", (err) => {
			clearTimeout(timeout);
			reject(new Error(`Command error: ${command}\n${err.message}`));
		});
	});
}

async function execNoThrow(
	command: string,
	timeoutMs = COMMAND_TIMEOUT_MS,
): Promise<{ success: boolean; stdout: string; stderr: string }> {
	try {
		const stdout = await exec(command, timeoutMs);
		return { success: true, stdout, stderr: "" };
	} catch (error) {
		const err = error as Error;
		return { success: false, stdout: "", stderr: err.message };
	}
}

async function fetchRequiredChecks(repo: string): Promise<Set<string>> {
	// Branch protection is the source of truth for which checks gate a merge (the rulesets
	// endpoint — this repo manages them via .github/rulesets/main.json). Auto-merge itself waits
	// on required checks only, so any check outside this set may be red without stopping a merge —
	// the migration beacon (`verify`) is red BY DESIGN for the whole module-architecture migration.
	// Deriving the filter here instead of naming that check keeps this list-free: any future
	// informational check is tolerated automatically. Degrades in the strict direction: an empty
	// set (fetch failure) makes callers treat every check as required.
	const result = await execNoThrow(
		`gh api repos/${repo}/rules/branches/main --jq '[.[] | select(.type == "required_status_checks") | .parameters.required_status_checks[].context] | unique | .[]'`,
	);
	if (!result.success) {
		log(
			`Could not fetch required checks (${result.stderr.split("\n")[0]}); treating every check as required`,
		);
		return new Set();
	}
	return new Set(
		result.stdout
			.split("\n")
			.map((line) => line.trim())
			.filter((line) => line.length > 0),
	);
}

async function getOpenPrsWithAutoMerge(repo: string): Promise<PullRequest[]> {
	const json = await exec(
		`gh pr list --repo ${repo} --state open --json number,createdAt,autoMergeRequest,state,headRefName,mergeStateStatus,statusCheckRollup`,
	);
	const prs: PullRequest[] = JSON.parse(json);
	return prs.filter((pr) => pr.autoMergeRequest !== null);
}

async function getPrState(
	repo: string,
	prNumber: number,
): Promise<PullRequestState> {
	const json = await exec(
		`gh pr view --repo ${repo} ${prNumber} --json state,mergeStateStatus`,
	);
	return JSON.parse(json);
}

function enabledAt(pr: PullRequest): number {
	const ts = pr.autoMergeRequest?.enabledAt;
	return ts ? new Date(ts).getTime() : Number.POSITIVE_INFINITY;
}

function getPrsAhead(ours: PullRequest, all: PullRequest[]): PullRequest[] {
	const oursEnabled = enabledAt(ours);
	return all
		.filter((pr) => pr.number !== ours.number)
		.filter((pr) => enabledAt(pr) < oursEnabled)
		.sort((a, b) => enabledAt(a) - enabledAt(b));
}

type QueueOutcome =
	| { kind: "turn" }
	| { kind: "ours-merged" }
	| { kind: "ours-closed" }
	| { kind: "timeout" };

function partitionAhead(
	prsAhead: PullRequest[],
	skipped: Set<number>,
	requiredChecks: ReadonlySet<string>,
): { waiting: string[]; skippedNow: string[] } {
	const waiting: string[] = [];
	const skippedNow: string[] = [];

	for (const pr of prsAhead) {
		if (skipped.has(pr.number)) {
			skippedNow.push(`#${pr.number} (skipped)`);
			continue;
		}
		const reason = classifyAhead(pr, requiredChecks);
		if (reason) {
			skipped.add(pr.number);
			skippedNow.push(`#${pr.number} (${formatSkipReason(reason)})`);
			continue;
		}
		waiting.push(`#${pr.number}`);
	}

	return { waiting, skippedNow };
}

async function checkOursState(
	repo: string,
	prNumber: number,
): Promise<QueueOutcome | null> {
	const oursState = await getPrState(repo, prNumber);
	if (oursState.state === "MERGED") {
		log("Our PR merged while waiting for queue - exiting early");
		return { kind: "ours-merged" };
	}
	if (oursState.state === "CLOSED") {
		log("Our PR was closed while waiting for queue");
		return { kind: "ours-closed" };
	}
	return null;
}

async function waitForPrsAhead(
	repo: string,
	ours: PullRequest,
	startTime: number,
): Promise<QueueOutcome> {
	const skipped = new Set<number>();
	// Fetched once per queue wait: required contexts change only when someone edits the ruleset,
	// and a mid-wait change is picked up on the next /ship invocation.
	const requiredChecks = await fetchRequiredChecks(repo);

	while (true) {
		if (Date.now() - startTime > TIMEOUT_MS) {
			return { kind: "timeout" };
		}

		const oursOutcome = await checkOursState(repo, ours.number);
		if (oursOutcome) {
			return oursOutcome;
		}

		const allPrs = await getOpenPrsWithAutoMerge(repo);
		const prsAhead = getPrsAhead(ours, allPrs);
		const { waiting, skippedNow } = partitionAhead(prsAhead, skipped, requiredChecks);

		if (waiting.length === 0) {
			if (skippedNow.length > 0) {
				log(`Ahead: skipping ${skippedNow.join(", ")}`);
			}
			log("No PRs ahead in queue - it's our turn!");
			return { kind: "turn" };
		}

		const parts = [`waiting on ${waiting.join(", ")}`];
		if (skippedNow.length > 0) {
			parts.push(`skipping ${skippedNow.join(", ")}`);
		}
		log(`Ahead: ${parts.join("; ")}`);

		await sleep(POLL_INTERVAL_MS);
	}
}

async function rebaseAndPush(defaultBranch: string): Promise<boolean> {
	log(`Fetching latest ${defaultBranch}...`);
	const fetchResult = await execNoThrow(`git fetch origin ${defaultBranch}`);
	if (!fetchResult.success) {
		log(`Failed to fetch: ${fetchResult.stderr}`);
		return false;
	}

	log(`Rebasing onto origin/${defaultBranch}...`);
	const rebaseResult = await execNoThrow(`git rebase origin/${defaultBranch}`);
	if (!rebaseResult.success) {
		log(`Rebase failed (conflicts?): ${rebaseResult.stderr}`);
		await execNoThrow("git rebase --abort");
		return false;
	}

	log("Force-pushing...");
	const pushResult = await execNoThrow(
		"git push --force-with-lease origin HEAD",
	);
	if (!pushResult.success) {
		log(`Push failed: ${pushResult.stderr}`);
		return false;
	}

	return true;
}

interface RulesetRef {
	id: number;
	name: string;
}

/**
 * Applies every committed ruleset (.github/rulesets/*.json) to the repo:
 * looked up by name, updated if it exists, created otherwise.
 * No-op for repos without a rulesets directory.
 * Runs when we are first in queue, after the rebase and before CI,
 * using the operator's authenticated `gh` (needs repo admin).
 */
async function applyRulesets(repo: string): Promise<void> {
	if (!existsSync(RULESETS_DIR)) {
		return;
	}
	const files = readdirSync(RULESETS_DIR)
		.filter((f) => f.endsWith(".json"))
		.sort();
	if (files.length === 0) {
		return;
	}

	const existing: RulesetRef[] = JSON.parse(
		await exec(`gh api "repos/${repo}/rulesets" --paginate`),
	);

	for (const file of files) {
		const path = join(RULESETS_DIR, file);
		const { name } = JSON.parse(readFileSync(path, "utf8")) as {
			name: string;
		};
		const match = existing.find((ruleset) => ruleset.name === name);
		if (match) {
			log(`Updating ruleset '${name}' (id=${match.id})`);
			await exec(
				`gh api -X PUT "repos/${repo}/rulesets/${match.id}" --input "${path}"`,
			);
		} else {
			log(`Creating ruleset '${name}'`);
			await exec(`gh api -X POST "repos/${repo}/rulesets" --input "${path}"`);
		}
	}
}

async function waitForChecksToAppear(
	repo: string,
	prNumber: number,
): Promise<boolean> {
	log("Waiting for CI checks to be registered...");

	for (let i = 0; i < CHECKS_APPEAR_MAX_ATTEMPTS; i++) {
		const result = await execNoThrow(
			// --required: the migration beacon (`verify`) is red by design and non-required; the
			// watcher's verdict must come from required checks only (auto-merge's own criterion).
			`gh pr checks --repo ${repo} ${prNumber} --required --json name`,
		);
		if (result.success) {
			const checks: unknown[] = JSON.parse(result.stdout);
			if (checks.length > 0) {
				log(`Found ${checks.length} check(s)`);
				return true;
			}
		}
		log("No checks yet, polling...");
		await sleep(CHECKS_APPEAR_POLL_MS);
	}

	log("No checks appeared after polling");
	return false;
}

type CiOutcome = "passed" | "failed" | "timeout";

function watchChecks(
	repo: string,
	prNumber: number,
	budgetMs: number,
): Promise<CiOutcome> {
	log(`Watching CI checks (up to ${Math.round(budgetMs / 60_000)} min)...`);

	return new Promise((resolve) => {
		const proc = spawn(
			"gh",
			[
				"pr",
				"checks",
				"--repo",
				repo,
				String(prNumber),
				"--watch",
				"--fail-fast",
				// Required checks only — the non-required migration beacon (`verify`) is red by
				// design until the migration completes and must not fail the watch. Auto-merge
				// itself only waits on required checks, so this keeps the report honest.
				"--required",
			],
			{
				stdio: "inherit",
			},
		);

		// The watch had NO deadline before this: `gh pr checks --watch` blocks until the checks
		// settle, so a wedged required check hung the whole script indefinitely. Killing the child
		// on the budget is what makes the advertised bound real.
		let timedOut = false;
		const timer = setTimeout(() => {
			timedOut = true;
			proc.kill();
		}, budgetMs);

		proc.on("close", (code) => {
			clearTimeout(timer);
			if (timedOut) {
				log("CI watch budget exhausted - stopped watching");
				resolve("timeout");
			} else if (code === 0) {
				log("All CI checks passed!");
				resolve("passed");
			} else {
				log("CI checks failed");
				resolve("failed");
			}
		});

		proc.on("error", (err) => {
			clearTimeout(timer);
			log(`CI check error: ${err.message}`);
			resolve("failed");
		});
	});
}

async function waitForCi(
	repo: string,
	prNumber: number,
	startTime: number,
): Promise<CiOutcome> {
	const hasChecks = await waitForChecksToAppear(repo, prNumber);
	if (!hasChecks) {
		return "passed";
	}
	// Whichever bites first: the CI sub-budget, or what is left of the whole run's budget.
	const budgetMs = Math.min(
		CI_WATCH_TIMEOUT_MS,
		TIMEOUT_MS - (Date.now() - startTime),
	);
	if (budgetMs <= 0) {
		return "timeout";
	}
	return watchChecks(repo, prNumber, budgetMs);
}

async function waitForMerge(
	repo: string,
	prNumber: number,
	startTime: number,
): Promise<"merged" | "failed" | "timeout"> {
	log("Waiting for auto-merge to complete...");

	const mergeStart = Date.now();

	while (true) {
		if (Date.now() - startTime > TIMEOUT_MS) {
			return "timeout";
		}

		if (Date.now() - mergeStart > MERGE_WAIT_TIMEOUT_MS) {
			log("Auto-merge taking longer than expected");
			return "timeout";
		}

		const state = await getPrState(repo, prNumber);

		if (state.state === "MERGED") {
			log("PR merged successfully!");
			return "merged";
		}

		if (state.state === "CLOSED") {
			log("PR was closed without merging");
			return "failed";
		}

		if (state.mergeStateStatus === "DIRTY") {
			log("Merge conflict detected");
			return "failed";
		}

		log(`PR state: ${state.state}, merge status: ${state.mergeStateStatus}`);
		await sleep(MERGE_POLL_INTERVAL_MS);
	}
}

async function fetchDefaultBranch(defaultBranch: string): Promise<void> {
	log(`Fetching origin/${defaultBranch}...`);
	await exec(`git fetch origin ${defaultBranch}`);
	log(`Fetched origin/${defaultBranch}`);
}

function parseArgs(): {
	repo: string;
	prNumber: number;
	defaultBranch: string;
} {
	const args = process.argv.slice(2);

	// Through finish() like every other exit: a usage error must still leave the result line
	// /ship greps for, or a mis-invocation reads as "the process vanished" (UNKNOWN) instead of
	// the plain, fixable mistake it is.
	if (args.length !== EXPECTED_ARGS) {
		finish(
			"FAILED",
			"usage: ship-wait.ts <repo> <pr-number> <default-branch>",
		);
	}

	const prNumber = Number.parseInt(args[1], 10);
	if (Number.isNaN(prNumber)) {
		finish("FAILED", `invalid PR number: ${args[1]}`);
	}

	return { repo: args[0], prNumber, defaultBranch: args[2] };
}

async function waitForQueueTurn(
	repo: string,
	prNumber: number,
	startTime: number,
): Promise<QueueOutcome> {
	const allPrs = await getOpenPrsWithAutoMerge(repo);
	const ourPr = allPrs.find((pr) => pr.number === prNumber);

	if (ourPr) {
		return waitForPrsAhead(repo, ourPr, startTime);
	}

	log("Warning: Our PR doesn't have auto-merge enabled, proceeding anyway");
	const json = await exec(
		`gh pr view --repo ${repo} ${prNumber} --json number,createdAt,state,headRefName`,
	);
	const pr = JSON.parse(json) as PullRequest;
	pr.autoMergeRequest = { enabledAt: new Date().toISOString() };

	return waitForPrsAhead(repo, pr, startTime);
}

async function main(): Promise<void> {
	const { repo, prNumber, defaultBranch } = parseArgs();
	const startTime = Date.now();

	log(
		`Starting ship-wait for ${repo} PR #${prNumber} (default branch: ${defaultBranch})`,
	);

	const state = await getPrState(repo, prNumber);

	if (state.state === "MERGED") {
		log("PR is already merged!");
		await fetchDefaultBranch(defaultBranch);
		finish("MERGED", "PR was already merged");
	}

	if (state.state === "CLOSED") {
		finish("FAILED", "PR is closed");
	}

	const queueOutcome = await waitForQueueTurn(repo, prNumber, startTime);
	if (queueOutcome.kind === "ours-merged") {
		await fetchDefaultBranch(defaultBranch);
		finish("MERGED", "merged while waiting in queue");
	}
	if (queueOutcome.kind === "ours-closed") {
		finish("FAILED", "PR was closed while waiting in queue");
	}
	if (queueOutcome.kind === "timeout") {
		finish("TIMEOUT", "still waiting on PRs ahead in queue");
	}

	if (!(await rebaseAndPush(defaultBranch))) {
		finish("FAILED", `could not rebase onto ${defaultBranch} and push`);
	}

	await applyRulesets(repo);

	const ciOutcome = await waitForCi(repo, prNumber, startTime);
	if (ciOutcome === "failed") {
		finish("FAILED", "a required CI check failed");
	}
	if (ciOutcome === "timeout") {
		finish("TIMEOUT", "required CI checks still running");
	}

	const mergeResult = await waitForMerge(repo, prNumber, startTime);

	if (mergeResult === "merged") {
		await fetchDefaultBranch(defaultBranch);
		finish("MERGED", `merged into ${defaultBranch}`);
	} else if (mergeResult === "failed") {
		finish("FAILED", "PR closed or conflicted while awaiting merge");
	} else {
		finish("TIMEOUT", "CI passed but auto-merge has not completed yet");
	}
}

process.on("SIGINT", () => {
	finish("FAILED", "interrupted");
});

process.on("SIGTERM", () => {
	finish("FAILED", "terminated");
});

main().catch((err) => {
	finish("FAILED", `unexpected error: ${err.message}`);
});
