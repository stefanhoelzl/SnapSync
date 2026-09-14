## Context

`SNAPSYNC-1` is the largest open issue in the project, and its message is the problem: *"The OS
watchdog terminated your app, possibly because it overused RAM."* The reporting SDK's heuristic
cannot separate a watchdog kill from a memory kill, and — measured on the board, 2026-08-29 — every
one of its 40 events carries `in_foreground: true`, because that heuristic excludes background
terminations by construction. Background kills (`SNAPSYNC-23`) are not under-represented there; they
are absent.

MetricKit is the OS's own attribution, and its foreground counter set separates exactly what the
heuristic conflates. A device probe measured the whole surface before any of this was designed; its
record is `PROBE-FINDINGS.md`, alongside this document. Everything below rests on that measurement
rather than on documentation, and where a claim is still inference it says so.

Constraints that shaped the design, all measured:

| fact | consequence |
|---|---|
| accumulation starts only at first touch, never retroactively | arm on the earliest unconditional path |
| delivery is **one-shot** — waits indefinitely, then never returns | arming without a live consumer is worse than not arming |
| one delivery of 12 payloads = **15.1 MB**, rolled the log, blocked 18 s | call stacks must never be read |
| zero-valued counters are **omitted** from the serialized form | absence and zero are indistinguishable |
| dev-signed builds **do** receive payloads (`isTestFlightApp: false`) | this works on every build |
| the whole `MX*` surface is deprecated at iOS 27; its successor is Swift-only | the port is a swap seam, not decoration |

## Goals / Non-Goals

**Goals:**

- Partition the existing `SNAPSYNC-1` population: say whether a termination was a watchdog kill, the
  app exceeding its own memory limit, or the system reclaiming memory under pressure.
- Close the background gap the reporting heuristic cannot see.
- Cost nothing on a healthy device, and nothing at all in app behaviour.
- Survive the vendor replacing the platform API without touching anything but one adapter.

**Non-Goals:**

- Saying **which code** was running. No call stacks, deliberately (see D2).
- Prompt attribution. Reports arrive on the OS's cadence — roughly daily, always long after the event.
- Extension-process coverage. Structurally unavailable (report cadence vs per-invocation lifetime).
- Acting on termination history. Diagnostics only; acting would rebuild the rejected shutdown marker.
- Replacing the existing crash channel. The SDK still owns everything it can catch.

## Decisions

### D1 — Read the platform's serialized representation, not named properties

Take the platform's own `dictionaryRepresentation()` and flatten it to dotted keys, rather than
reading each counter as a typed property.

*Alternative considered and initially chosen:* read named, non-null accessors, because the serialized
form omits zero-valued counters and so cannot distinguish "was zero" from "this OS lacks the counter".
**Reversed.** That distinction is inert here: the threshold fires on presence, and both absences mean
"do not fire". Reading the serialized form removes a whole class of forgot-to-read-it bugs and makes a
counter the vendor adds appear with no code change — which is what makes D10 (no vocabulary pin) safe
rather than a blind spot.

*Consequence:* the whole payload is flattened, not a shortlist. ~50–100 keys, a few KB per report —
three orders of magnitude below what call stacks would have cost, and everything useful (exit counts,
hang histogram, peak memory, foreground/background time, launch times) arrives without per-field code.

### D2 — Never read call-stack data

*Alternative considered:* write it verbatim to the device log, which is un-redacted, size-bounded and
already rides an operator dump. **Rejected by measurement, not judgement.** One delivery of twelve
queued diagnostic payloads wrote **15,156,294 bytes**, rolled the 10 MB log, destroyed the history
preceding it, and blocked the delivering call for **18 s**. It obliterates the channel it was meant to
feed.

A single crash diagnostic is 314 KB across 19 thread stacks, carrying **20 distinct binary UUIDs** —
UUID-shaped, so the capability's content-blind scrub would replace exactly the field that offline
symbolication resolves against.

Dropping call stacks dissolves three problems at once — the log destruction, the scrub collision, and
the 18 s block — which is what then makes D8's inline handling safe.

### D3 — One generic port, open key/value

`ProcessMetricSource` in `:domain:ports` yields reports as `Map<String, String>`. Not named for the
platform framework; no window in the type.

*Alternatives considered:* a fully typed closed vocabulary (cannot carry what a second provider
reports without changing `:domain`); a sealed hierarchy of metric kinds (more machinery, a bigger
first-time commitment).

*Accepted cost:* the threshold rule's lookups are stringly-typed, so a mistyped key silently disables
a threshold. Mitigated by keeping the key constants in `:domain:model`, shared by the adapter that
writes them and the rule that reads them, so there is exactly one spelling.

### D4 — The report is the unit; one event per report, fixed message

A crossing produces one event with a fixed message; the reasons ride in the attached report
context, not in the message and not as transport tags — the logging seam has no per-message tag
surface, and a varying message would split the single issue this design depends on.

*Alternatives considered:* one event per crossing reason (issue list reads as a list of failure modes,
but multiplies events); message naming the reasons (combinations produce 2ⁿ issues, and the same
watchdog kill lands in a different issue depending on what else happened that day).

*Unexpected virtue:* the measured 14-event relaunch storm would arrive as **one** report carrying a
count of 14, with the reason named — a better signal than fourteen undifferentiated events. The
aggregation accepted as a limitation also deduplicates storms.

*Accepted cost:* the issue list shows one undifferentiated entry; separating a watchdog kill from a
memory kill means opening an occurrence and reading its attached report.

### D5 — Thresholds start tight, loosen with data

Any non-normal exit counter present, or a reported hang above **1 s**.

The hang ceiling sits just above the 260–749 ms band this device produced routinely. The vendor
documents **no** minimum hang duration — an earlier claim of a ~250 ms floor was inferred from a
single histogram bucket and is retracted. The only documented number is that hangs beyond 9 s land in
a final catch-all bucket.

Counters whose meaning is unestablished are **included**, not excluded: inclusion is how their meaning
is learned. `cumulativeAbnormalExitCount` is the case in point — it read 6 in the one window measured,
all from development `SIGKILL`s, and whether ordinary user force-quit trips it is unknown. Excluding
it would preserve that unknown indefinitely.

Noise is cheap here because of D4: a chatty counter is one issue with a high occurrence count, and
that count is itself the measurement.

### D6 / D7 — Three channels, and the context survives crashes

Device log per report (always) · report as **global-scope context** · event on crossing.

The global scope is the load-bearing choice: the reporting adapter already sets its process tag there
because the native SDK *"persists into fatal events"*. So a crash captured in this process and
delivered on a later launch carries the attribution alongside it — which is precisely the correlation
the platform vendor documents between per-incident diagnostics and aggregate counts.

*This needs no persisted state*: the context is set at delivery, and the SDK snapshots the scope onto
events captured afterwards.

### D8 — Arm at process start, handle inline

Registration happens in the shell's own initialization, and handling completes before the delivering
call returns.

*Alternative considered:* seat the consumer on the application graph, as every other feature is.
**Rejected**: that graph is deliberately deferred so a cold background wake does not force it, and
reports land ~1 s after launch on every launch shape. Combined with one-shot delivery, an armed
subscriber with no live consumer **discards a report the OS was holding safely** — strictly worse than
not arming. Hence: arming and handling are inseparable.

*Alternative considered:* hop to the composition lane, the convention the photo-selection observer
states. **Rejected**: D2 removed the cost that motivated hopping, and a background wake killed
mid-hop loses a one-shot report permanently.

### D9 — No third composition

The adapter is constructed by the shell and takes the pure rule; the rule returns **emissions** rather
than a verdict, so the dispatch is a loop with no conditional.

*Alternative considered:* a third top-level entry point in `:domain:compose` beside the two existing
compositions. **Rejected** — it would sit against "there SHALL be no second wiring", and the law's
purpose (the harness cannot drift from production) does not apply to something nothing else composes.
Adapters in this module already call pure `:domain:model` functions, so this is the established
pattern rather than a new one. Returning emissions rather than a verdict is what removes the last
conditional and keeps the shell purely constructive.

### D10 / D11 — No vocabulary pin; yes to a rig trigger

No pin, because D1 makes an added counter appear on its own. A dev-only route feeding a synthetic
report through the same rule and channels exercises everything below the platform decode in seconds
rather than a day — cheap, because the port is open key/value and the synthetic report needs no
platform types.

### D12 — Diagnostics are read, and everything fires

*Re-examined after reading the board, and confirmed unchanged.* Filtering to only the kills the SDK
cannot catch was considered and **rejected on two grounds**: all attribution events share one message
(D4), so they are occurrences in a single issue rather than issues competing with the SDK's; and a
signal-based filter would rest on an inference — that a watchdog kill surfaces with a specific signal —
that has never been measured.

*Accepted cost:* event volume is bounded by payload count, not by the ~1–2 reports/day the metric
side produces. A 12-payload burst was measured. Narrowing later is exactly what D5's philosophy is for.

## Risks / Trade-offs

- **`abnormal` may fire on ordinary user force-quit** → then every device produces occurrences forever.
  Mitigation: the deliberate experiment in `tasks.md` settles it in one report cycle, and narrowing is
  one named constant.
- **A background watchdog kill may produce no per-incident diagnostic** → the *moment* of a background
  kill would then be unavailable, only the day. This is inference from the successor API's vocabulary,
  never measured. Mitigation: the counters still name the reason, which is the primary question.
- **Event volume is unbounded on a crashing device** → mitigated by single-issue grouping; the burst
  coincides with things already being wrong.
- **A mistyped key silently disables a threshold** → mitigated by shared constants in `:domain:model`
  and by the rule's tests naming each threshold.
- **The vendor's successor API is unreachable from this language** → the port is the swap seam; nothing
  breaks at iOS 27 since deprecated ≠ removed, but the trigger is named rather than discovered.
- **Handling inline runs on a platform-owned queue** → acceptable only because D2 reduced the work to
  reading a map and writing a few short lines; if that ever grows, the trade must be revisited.
- **Attribution arrives roughly a day late** → it answers "what killed us", never "what is happening
  now". It is not, and cannot become, a live signal.

## Migration Plan

Additive; nothing to migrate and no rollback beyond reverting. Two housekeeping items:

- The temporary probe (`MetricKitProbe`, the rig `crash` route) is **deleted** by this change — it
  exists only to have answered the questions this design rests on.
- `PROBE-FINDINGS.md` moves from the repo root into this change directory, where the repo's convention
  puts a decision's measurements.

The first report after shipping may arrive as a **burst** covering several past days, describing
windows from builds that predate this change; each report names the version it came from.

## Open Questions

- What does `cumulativeAbnormalExitCount` actually count? Settled by the force-quit experiment, not by
  reasoning.
- Does a background watchdog kill produce a per-incident diagnostic, and is `terminationReason`
  ever non-null? It was `null` for the one crash measured, and no watchdog diagnostic has been seen.
- Once distributions are known, which thresholds loosen, and to what? Deliberately deferred: the
  design's answer is that they are named constants and changing one is a reviewed edit.
