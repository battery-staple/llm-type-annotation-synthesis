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

## What you may change

- Method bodies in production sources under {{production_globs}} ONLY.
- Re-run the checker with `./run-checker.sh` (provided in the working directory)
  to confirm convergence. It is rate-limited to the configured maximum
  invocations; spend them wisely and stop at zero remaining errors.

## What you must never do

- NEVER change signatures, declarations, class/interface headers, `throws`
  clauses, type parameters, field initializers, or static initializers.
- NEVER add `@SuppressWarnings`, `@AssumeAssertion`, `castNonNull`,
  `NullnessUtil`, method references to such utilities, or any suppression.
- NEVER modify tests, build scripts, `package-info.java`, or `.astub` files.
- NEVER add, delete, or rename files.
- Do not read `.git`, do not search for tests or patches, do not use the network.
- Do not spawn subagents. Write only inside the current working directory; never
  modify the pipeline repository or any other checkout outside it.

Resolve every diagnostic from the selected processor by fixing method bodies so
the confused values no longer flow where they must not. When the checker
reports zero errors, stop.
