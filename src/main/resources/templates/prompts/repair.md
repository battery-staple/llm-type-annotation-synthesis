# Blind Checker Repair

You are a BLIND fixer. You receive only the annotated codebase and the selected
checker's diagnostics. You know nothing about the original bug, the tests, or
the commit history.

## Diagnostics to resolve (selected processor: {{processor}})

```
{{initial_checker_errors}}
```

Qualifiers in play: {{qualifier_names}}
Qualifier jar: {{custom_quals_jar_path}}
Maximum checker invocations: {{max_checker_invocations}}

## The annotated baseline is frozen

The tree you start from is the application stage's accepted annotation set.
Every declaration in it is immutable. You may change **only statements and
expressions inside method bodies**.

- NEVER add, remove, move, or rewrite a type-use qualifier annotation, including
  on fields, parameters, return types, local-variable types, type arguments, or
  nested types.
- NEVER change signatures, declarations, class/interface/enum headers, `throws`
  clauses, type parameters, field declarations, field initializers, or
  static/instance initializers.
- NEVER add a type-annotated cast such as `(@Qualifier Type) expr`, and never
  use a cast, `castNonNull`, `NullnessUtil`, or any other coercion to silence a
  diagnostic. An annotated cast is a checker bypass, not a repair, and is
  rejected by the anti-cheat guard.
- NEVER add `@SuppressWarnings`, `@AssumeAssertion`, or any suppression.
- NEVER modify tests, build scripts, `package-info.java`, or `.astub` files.
- NEVER add, delete, or rename files.

If a diagnostic cannot be resolved without changing a frozen declaration or
adding a coercing cast, leave it unresolved and explain why; do not cheat.

## Working in this workspace

- Use `./run-checker.sh` to compile the annotated tree and run the checker. It
  supplies the required checker and qualifier classpath and enforces the
  invocation budget. Plain `./gradlew` does **not** see the synthesised
  qualifiers and will report spurious "cannot find symbol" errors.
- Do not run the test suite, and do not attempt to discover or read the
  original bug, tests, or patch.
- Do not read or search the pipeline checkout, the harness source, logs,
  snapshots, `/tmp` fixtures, or the application/synthesis workspaces. Your only
  inputs are the files in this workspace and the diagnostics above.

## Retry protocol

If the harness says your edits were rejected, read its violation list rather
than probing the harness. A `signature_guard` or "declaration digest" violation
means a declaration or type annotation changed: revert that change and repair
only body statements/expressions. Do not spend turns reverse-engineering the
guards.

Resolve every fixable diagnostic from the selected processor by changing method
body logic so the confused values no longer flow where they must not. When the
checker reports zero errors, stop.
