import { assert, assertEquals } from "@std/assert";
import plugin from "../src/lint/complexity.ts";

/**
 * The ceiling rule's own tests (capability `complexity-budgets`).
 *
 * The rule is hand-written because nothing published measures complexity for Deno, so its definition
 * of "a decision" is ours and has to be pinned — a definition stated only in a doc comment drifts from
 * the code beneath it silently. Each case below is one line of that comment made executable, in both
 * directions: the forms that COUNT and the forms that deliberately do not.
 *
 * `Deno.lint.runPlugin` runs the plugin against a source string without touching the filesystem, so
 * these are ordinary unit tests and need no permissions.
 */

/** Runs the rule over `source` and returns the complexity it reported, or `null` if it stayed silent. */
function reportedComplexity(source: string): number | null {
  const diagnostics = Deno.lint.runPlugin(plugin, "fixture.ts", source);
  if (diagnostics.length === 0) return null;
  const match = diagnostics[0].message.match(/complexity is (\d+)/);
  assert(match, `diagnostic did not carry a complexity: ${diagnostics[0].message}`);
  return Number(match[1]);
}

/**
 * Builds a function whose body repeats `decision` enough times to land exactly one over the ceiling,
 * then asserts the rule reports the expected total. Written this way rather than against a fixed
 * ceiling so these tests keep passing as the ceiling ratchets DOWN, which is the whole point of it.
 */
function complexityOf(bodyLine: string, repeats: number): number | null {
  const body = Array.from({ length: repeats }, (_, i) => bodyLine.replaceAll("$i", String(i)))
    .join("\n  ");
  return reportedComplexity(
    `export function f(a: number, b: number) {\n  ${body}\n  return a + b;\n}`,
  );
}

/** How many repeats of a single-decision form it takes to exceed the ceiling. */
const OVER = 200;

Deno.test("a straight-line function is never reported", () => {
  assertEquals(reportedComplexity("export function f(a: number) { return a + 1; }"), null);
});

Deno.test("`if` counts as one decision each", () => {
  assertEquals(complexityOf("if (a === $i) return b;", OVER), OVER + 1);
});

Deno.test("the conditional expression counts", () => {
  assertEquals(complexityOf("const x$i = a > $i ? 1 : 2;", OVER), OVER + 1);
});

Deno.test("every loop form counts", () => {
  const forms = [
    "for (let i$i = 0; i$i < a; i$i++) { b + i$i; }",
    "for (const x$i of []) { b + Number(x$i); }",
    "for (const k$i in {}) { b + Number(k$i); }",
    "while (a > $i) { break; }",
    "do { break; } while (a > $i);",
  ];
  for (const form of forms) {
    assertEquals(complexityOf(form, OVER), OVER + 1, `loop form not counted: ${form}`);
  }
});

Deno.test("`catch` counts, and `try` alone does not", () => {
  assertEquals(complexityOf("try { b + $i; } catch { /* ignored */ }", OVER), OVER + 1);
});

Deno.test("each short-circuiting operator counts", () => {
  for (const op of ["&&", "||", "??"]) {
    const line = op === "??" ? `const x$i = (a as number | null) ?? b;` : `const x$i = a ${op} b;`;
    assertEquals(complexityOf(line, OVER), OVER + 1, `operator not counted: ${op}`);
  }
});

Deno.test("a `case` with a test counts and `default` does not", () => {
  const cases = Array.from({ length: OVER }, (_, i) => `case ${i}: return b;`).join("\n    ");
  const withDefault = reportedComplexity(
    `export function f(a: number, b: number) {\n  switch (a) {\n    ${cases}\n    default: return 0;\n  }\n}`,
  );
  const withoutDefault = reportedComplexity(
    `export function f(a: number, b: number) {\n  switch (a) {\n    ${cases}\n  }\n  return 0;\n}`,
  );
  assertEquals(withDefault, OVER + 1);
  assertEquals(withoutDefault, OVER + 1, "`default` must not add a decision");
});

Deno.test("`else` adds nothing beyond its `if`", () => {
  const ifs = Array.from({ length: OVER }, (_, i) => `if (a === ${i}) { b; } else { b; }`).join(
    "\n  ",
  );
  assertEquals(
    reportedComplexity(`export function f(a: number, b: number) {\n  ${ifs}\n}`),
    OVER + 1,
  );
});

Deno.test("optional chaining adds nothing", () => {
  const ifs = Array.from({ length: OVER }, (_, i) => `if (a === ${i}) { b; }`).join("\n  ");
  const chains = Array.from({ length: 20 }, (_, i) => `const y${i} = o?.p?.q;`).join("\n  ");
  assertEquals(
    reportedComplexity(
      `export function f(a: number, b: number, o: { p?: { q?: number } }) {\n  ${chains}\n  ${ifs}\n}`,
    ),
    OVER + 1,
  );
});

Deno.test("a nested function is its own scope, not its parent's", () => {
  // The inner arrow carries every decision; the outer function is straight-line. If the rule used a
  // single counter instead of a stack, the outer one would be reported carrying the inner's total.
  const ifs = Array.from({ length: OVER }, (_, i) => `if (a === ${i}) { return 1; }`).join(
    "\n    ",
  );
  const source =
    `export function outer(a: number) {\n  const inner = () => {\n    ${ifs}\n    return 0;\n  };\n  return inner;\n}`;
  const diagnostics = Deno.lint.runPlugin(plugin, "fixture.ts", source);
  assertEquals(diagnostics.length, 1, "exactly one function should breach the ceiling");
  assert(
    diagnostics[0].message.includes(`complexity is ${OVER + 1}`),
    `the inner function should carry the whole count: ${diagnostics[0].message}`,
  );
});

Deno.test("the diagnostic says where to change the ceiling", () => {
  const ifs = Array.from({ length: OVER }, (_, i) => `if (a === ${i}) { return 1; }`).join("\n  ");
  const [diagnostic] = Deno.lint.runPlugin(
    plugin,
    "fixture.ts",
    `export function f(a: number) {\n  ${ifs}\n  return 0;\n}`,
  );
  assert(
    diagnostic.message.includes("api/src/lint/complexity.ts"),
    "a ceiling breach must name the file that holds the ceiling",
  );
  assert(
    diagnostic.message.includes("forcing proof"),
    "a ceiling breach must state that raising the number needs a forcing proof",
  );
});
