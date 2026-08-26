/**
 * A cyclomatic-complexity ceiling for this backend's TypeScript (capability `complexity-budgets`).
 *
 * THE CEILING IS A NUMBER THAT MAY ONLY FALL. Lowering it is ordinary work — do it in the change that
 * makes it true. RAISING it requires a stated forcing proof in that change's description. There is no
 * check that enforces this: it is a ratchet carried by this paragraph and by review, exactly as the
 * Kotlin side's tier configs are, and deliberately not a proof.
 *
 * WHY THIS IS HAND-WRITTEN. Nothing published measures complexity for Deno. `deno lint --rules` ships
 * no complexity, depth, length or parameter-count rule, and none of the published JSR lint plugins
 * provides one: `@nashaddams/lint-plugin` (no-missing-await, no-missing-try-catch),
 * `@devhaven/deno-lint-plugin` (function-call-argument-newline, no-empty-function),
 * `@yolk-oss/deno-lint-plugin` (no-magic-numbers, no-underscore-dangle, require-yield), and
 * `@uki00a/deno-lint-plugin-extra-rules` (Deno built-in API and std rules). eslint's `complexity` rule
 * would have been the battle-tested alternative, and was rejected: it means npm, typescript-eslint, a
 * lockfile and a node_modules surface in a backend that deliberately runs Deno-only.
 *
 * WHAT COUNTS AS A DECISION, enumerated so this definition is comparable BY READING against detekt's
 * `CyclomaticComplexMethod` on the Kotlin side rather than assumed equal to it:
 *
 *   +1  every function, as its base (a straight-line function is 1)
 *   +1  `if`
 *   +1  the `?:` conditional expression
 *   +1  `for`, `for…of`, `for…in`, `while`, `do…while`
 *   +1  `catch`
 *   +1  each `case` that carries a test (`default` does not)
 *   +1  each `&&`, `||`, `??`
 *
 * Deliberately NOT counted, and each is a real difference from detekt: `else` (it is the fall-through
 * of an `if` already counted), optional chaining `?.` (detekt counts no equivalent), and detekt's
 * `nestingFunctions` list (`also`/`let`/`run`/…), which has no TypeScript counterpart.
 *
 * PARITY IS PARTIAL AND STATED. Cyclomatic complexity is the one measure both sides share, so that
 * "the complexity budget" names one thing across the repository. Method length, parameter count and
 * nesting depth are NOT enforced here — approximating them with more hand-written rules would produce
 * four definitions that silently drift from detekt's instead of one that is written down.
 */

/**
 * The ceiling. detekt's thresholds are the first value that FAILS; this one is the largest value that
 * PASSES, because that is how the rest of `deno lint` reads. Seeded at the tree's measured maximum
 * when the gate landed: `attest.ts` `verifyAssertion`, `app.ts`'s two largest handlers.
 */
const MAX_COMPLEXITY = 19;

/** Node types that add one decision each. */
const DECISION_NODES: ReadonlySet<string> = new Set([
  "IfStatement",
  "ConditionalExpression",
  "ForStatement",
  "ForOfStatement",
  "ForInStatement",
  "WhileStatement",
  "DoWhileStatement",
  "CatchClause",
]);

/** Node types that open a new complexity scope. */
const FUNCTION_NODES = [
  "FunctionDeclaration",
  "FunctionExpression",
  "ArrowFunctionExpression",
] as const;

/** The short-circuiting operators. `&` and `|` are arithmetic and branch nothing. */
const BRANCHING_OPERATORS: ReadonlySet<string> = new Set(["&&", "||", "??"]);

const plugin: Deno.lint.Plugin = {
  name: "snapsync",
  rules: {
    "cyclomatic-complexity": {
      create(context) {
        // A stack, not a counter: a callback declared inside a function is its OWN scope, so its
        // decisions must not be charged to the enclosing one. Without this, a handler containing
        // three small callbacks would report their combined complexity as its own.
        const scopes: { node: Deno.lint.Node; complexity: number }[] = [];

        const bump = () => {
          const current = scopes[scopes.length - 1];
          if (current) current.complexity += 1;
        };

        // Keyed by node type at runtime, so it is built as a plain record and handed back as a
        // visitor. Deno's plugin API types each visitor by its own node type; the two `as` narrowings
        // below are where a handler needs a field that only its own node kind carries.
        const visitors: Record<string, (node: Deno.lint.Node) => void> = {};

        for (const kind of FUNCTION_NODES) {
          visitors[kind] = (node: Deno.lint.Node) => scopes.push({ node, complexity: 1 });
          visitors[`${kind}:exit`] = (node: Deno.lint.Node) => {
            const scope = scopes.pop();
            if (!scope) return;
            if (scope.complexity > MAX_COMPLEXITY) {
              context.report({
                node,
                message:
                  `This function's cyclomatic complexity is ${scope.complexity}, above the ceiling ` +
                  `of ${MAX_COMPLEXITY} (capability \`complexity-budgets\`). Split it, or state a ` +
                  `forcing proof and raise the ceiling in api/src/lint/complexity.ts.`,
              });
            }
          };
        }

        for (const kind of DECISION_NODES) visitors[kind] = bump;

        visitors["SwitchCase"] = (node) => {
          // `default` carries no test and branches nothing — it is where control lands otherwise.
          if ((node as Deno.lint.SwitchCase).test) bump();
        };

        visitors["LogicalExpression"] = (node) => {
          if (BRANCHING_OPERATORS.has((node as Deno.lint.LogicalExpression).operator)) bump();
        };

        return visitors as Deno.lint.LintVisitor;
      },
    },
  },
};

export default plugin;
