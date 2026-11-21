# Level 0 Qualifier Hierarchy Synthesis

You are designing a sound, minimal checker-framework type hierarchy that captures
one latent bug pattern. This is Level 0: **pure marker qualifier hierarchy only**.

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

Write `placement_guide.md` FIRST, then the qualifier sources. The harness validates
the guide even when the Java sources are already present.

1. **Qualifier sources** under `./custom_quals_src/<package path>/*.java`.
   Each file declares exactly one qualifier annotation:
   - `@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})`
   - `@Retention(RetentionPolicy.RUNTIME)`
   - A `@SubtypeOf(...)` edge that builds a strict lattice with one root
     (marked `@DefaultQualifierInHierarchy`) and at least one child qualifier.
   - EXACTLY ONE bottom qualifier. The Checker Framework rejects a hierarchy with
     multiple bottoms ("Multiple bottoms found"). If you create two or more sibling
     children, add one dedicated bottom marker (for example `AccountIdBottom`)
     annotated `@SubtypeOf({ChildOne.class, ChildTwo.class})`, and include it in
     the discovered qualifier set. A lattice with a single child needs no extra
     bottom, because that child is the unique bottom.
   - ZERO classes, interfaces, enums, records, methods, fields, or helpers.
     Annotation declarations with no members only.

2. **A declarative `placement_guide.md`** in the current working directory with
   exactly these four sections (exact headings required):

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

## Hard constraints

- Do not read `.git`, do not run `git log`, do not look for test files or patches.
- Every qualifier source must compile with `javac -proc:none` against `checker-qual.jar`.
- Keep the package stable; the harness discovers all `@Target(TYPE_USE)`
  qualifiers automatically for `-Aquals`.
