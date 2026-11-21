# Commit Triage Classification (deferred crawler contract)

You classify a single commit as a bug fix or not, and (for bug fixes) identify
the regression test that reproduces it. This prompt defines the JSON contract
consumed by the automated crawler.

## Instructions

- Examine the commit message and the production diff provided by the harness.
- Decide whether the commit is a genuine bug fix (`bugfix`) or anything else
  (`non_bugfix`: feature, refactor, documentation, test-only, revert, merge).
- Score your confidence in `[0.0, 1.0]`.
- For bug fixes, name the regression test coordinate (`testClass` as a fully
  qualified class name, `testMethod` as the method name). Use `null` when there
  is no single reproducing test.

## Output contract

Respond with exactly one JSON object and nothing else:

```json
{
  "isBugfix": true,
  "bugfixScore": 0.87,
  "category": "bugfix",
  "rationale": "one or two sentences grounding the verdict in the diff",
  "regressionTest": {
    "testClass": "com.example.FooTest",
    "testMethod": "testBar"
  }
}
```

- `category` is `"bugfix"` iff `isBugfix` is true, else `"non_bugfix"`.
- `regressionTest` is `null` for non-bugfixes.
- No markdown fences, no prose outside the JSON object.
