# Level 0 Checker Strategy Synthesis

Choose the soundest checker strategy for capturing one latent bug pattern. This is
Level 0: **pure marker qualifier hierarchy only** when using a custom hierarchy.

## Bug context

- Benchmark: {{benchmark_id}}
- Fixed commit: {{commit_hash}} (you only see its parent {{parent_hash}} plus the diff below)
- Description: {{description}}
- Freedom level: {{freedom_level}} (must stay Level 0: no helpers, no methods)
- Allowed checkers: {{allowed_checkers}}

## Production diff (parent vs fixed commit, production paths only)

```diff
{{commit_diff}}
```

Read the diff only to understand the *kind* of confusion generically (for example,
two identifiers of the same carrier type that must never be conflated). Do NOT name
any concrete repository class, method, variable, file, or test in your output.

## What to produce

## Decide first: synthesis_decision.json

Write `synthesis_decision.json` in the workspace root FIRST, before
`placement_guide.md` or any qualifier sources. It must contain exactly these keys
and values:

```json
{
  "checker_category": "custom_qualifier_hierarchy" | "builtin_checker",
  "processor": "<fully qualified Checker Framework processor class>",
  "rationale": "<one or two sentences tying the choice to the bug semantics>"
}
```

Use the configured processors: custom is `{{custom_processor}}`; built-in is
`{{builtin_checker}}` ({{builtin_checker_name}}). The rationale must explain why
the selected strategy captures the confusion described by the bug.

`allowed_checkers` constrains your choice:
- `custom_only`: MUST choose `custom_qualifier_hierarchy` and processor
  `{{custom_processor}}`.
- `builtin_only`: MUST choose `builtin_checker` and processor
  `{{builtin_checker}}`.
- `all`: choose exactly one honestly. Prefer `custom_qualifier_hierarchy` when a
  marker lattice can express the confusion; choose `builtin_checker` only when
  the configured built-in checker's existing annotations/defaulting directly
  capture the bug. If the configured built-in is a poor fit, choose the custom
  hierarchy instead of forcing a mismatch. For `builtin_checker`, processor MUST
  be `{{builtin_checker}}`.

If your decision is `builtin_checker`, do NOT create any files under
`custom_quals_src/`.

## If you chose custom_qualifier_hierarchy

For this choice, write `placement_guide.md` FIRST, then the qualifier sources. The
harness validates the guide even when the Java sources are already present.

1. **Qualifier sources** under `./custom_quals_src/<package path>/*.java`.
   Each file declares exactly one qualifier annotation:
   - `@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})`
   - `@Retention(RetentionPolicy.RUNTIME)`
   - An explicit `@SubtypeOf(...)` annotation on EVERY qualifier, root included.
     The root (the one marked `@DefaultQualifierInHierarchy`) MUST be written as
     `@SubtypeOf({})` so it registers an empty supertype edge. Omitting
     `@SubtypeOf` on the root or relying on `@DefaultQualifierInHierarchy` alone
     makes the Checker Framework crash with "is not a key in the directSuperMap".
     The other annotations build a strict lattice with that single root and at
     least one child qualifier.
   - EXACTLY ONE bottom qualifier. The Checker Framework rejects a hierarchy with
     multiple bottoms ("Multiple bottoms found"). If you create two or more sibling
     children, add one dedicated bottom marker (for example `AccountIdBottom`)
     annotated `@SubtypeOf({ChildOne.class, ChildTwo.class})`, and include it in
     the discovered qualifier set. A lattice with a single child needs no extra
     bottom, because that child is the unique bottom.
   - ZERO classes, interfaces, enums, records, methods, fields, or helpers.
     Annotation declarations with no members only.

2. **A declarative `placement_guide.md`** in the current working directory with
   exactly these five sections (exact headings required):

## Qualifier Semantics

- <a brief (short paragraph) description of the lattice's high level meaning and purpose.>
- <one `- ` bullet per qualifier you declared: `@QualifierName` followed by what values it
  denotes (for example which identifier domain or trust level), and how it relates to the
  other qualifiers in the lattice (root, child, or bottom). At least one bullet.>

## Defaulting Invariants

- <at least one `- ` bullet stating the default qualifier and where it applies>

## Source Rules (Introducers)

- <at least one `- ` bullet describing, in generic terms, which values introduce the sensitive qualifier>

## Sink Rules (Consumers)

- <at least one `- ` bullet describing, in generic terms, which operations consume the sensitive qualifier>

## Sanitizers & Coercions

- <at least one `- ` bullet describing, in generic terms, what would legitimize a conversion>

Rules must be semantic and generic (identifier conflation, untrusted vs vetted
values). Never name concrete repository symbols, files, or the regression test.

## If you chose builtin_checker

- Do NOT create any files under `custom_quals_src/`.
- Write `placement_guide.md` after the decision and before any other artifacts.
  Describe the selected built-in checker's own annotations (for Nullness, for
  example, `@Nullable`, `@NonNull`, etc.), including defaulting, introducers,
  consumers, and sanitizers. Use the same five headings and bullet format above.
- In the Qualifier Semantics section, list only the built-in checker's actual
  annotations. The "one bullet per qualifier you declared" rule applies to
  custom hierarchies; do not invent qualifier names for a built-in guide.
- Keep the guide generic; do not name repository symbols, files, or the regression
  test.

## Hard constraints

- Do not read `.git`, do not run `git log`, do not look for test files or patches.
- Do not spawn subagents or delegate to another agent.
- Write only inside the current working directory. Never modify the pipeline
  repository or any other checkout outside it.
- Every custom qualifier source must compile with
  `javac --release 8 -proc:none -cp "$CHECKER_QUAL_JAR"`, so use only Java 8
  language features and APIs. The harness compiles the qualifier jar exactly
  this way.
- The hierarchy root must be written literally as `@SubtypeOf({})` (an explicitly
  registered empty supertype edge); never leave the root without `@SubtypeOf`.
- Put the qualifiers in a package of their own. It must not be a package that
  the project already declares (for example a package from the commit diff):
  the harness rejects such a lattice.
- Keep the package stable; the harness discovers all `@Target(TYPE_USE)`
  qualifiers automatically for `-Aquals`.
