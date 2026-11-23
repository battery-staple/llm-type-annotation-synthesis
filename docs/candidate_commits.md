# Candidate benchmark commits (static analysis only)

Generated 2026-11-22 from git history of the `eval-repos/` submodules. **Nothing here has been built or run through triage.** Each candidate: a fix-like commit changing 1-2 production `.java` files (<=80 changed lines) and 1-2 test `.java` files, with no other files touched. JSqlParser's `jsqlparser-index-kind` is already registered and verified.

Tiers: **A** = bug is a value-domain/representation confusion a qualifier hierarchy could plausibly express; **B** = plausible but weaker; **C** = logic/nullness/state bug, low expected type-system fit (useful as negative controls).

Before registering any entry: (1) confirm the test patch compiles on C-1 (tests may use symbols new in C), (2) run triage (`--stop-after triage`), (3) check the build on JDK 21 (see notes).


## RoaringBitmap

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| A | `c7bd6849f58418b69b8f47ea5667a252b3758a01` | `2863e96d6715113dd32b9f2582bf962fdb57bbe6` | 2026-07-08 | fix: ReverseIntIteratorFlyweight short overflow (>32768 containers) (#836) | ReverseIntIteratorFlyweight.java, BufferReverseIntIteratorFlyweight.java | org/roaringbitmap/ReverseIntIteratorFlyweightManyContainersTest.java | reverseIterationYieldsEveryValueWithMoreThanShortMaxContainers | index stored as short overflows past 32768 containers: signed/unsigned (or container-index vs raw short) confusion |
| A | `2242bc0e92386fb5630d7edd22f350ac7be268b9` | `eaa61caff211f10becbcb487b4e92284c68f1da2` | 2026-10-19 | Fix nextAbsentValue and previousAbsentValue for keys from 32768 on (#881) | RoaringBitmap.java, ImmutableRoaringBitmap.java | org/roaringbitmap/TestRoaringBitmap.java, org/roaringbitmap/buffer/TestRoaringBitmap.java | absentValuesWithKeysFromTwoToThe31, previousAbsentValueOfZeroWithTheLargestValuePresent, absentValuesWithKeysFromTwoToThe31 | container key (unsigned 16-bit) compared as shifted signed int; key-vs-value domain confusion |
| A | `0b422495a18f1c003d50bd9f2aff705436d137b8` | `f66432403848d24bb14b527c99cf7b9f939e582c` | 2026-11-04 | Fix run container forEach at the ends of the unsigned value range (#902) | RunContainer.java, MappeableRunContainer.java | org/roaringbitmap/TestForEach.java, org/roaringbitmap/buffer/TestForEach.java | testRunAtIntegerMinValue, testRunAtIntegerMaxValue, assertForEachMatchesToArray | unsigned-range loop  wraps at the ends of the int range; value vs. offset/length |
| B | `51fa3d6d2ca2e7b250b11e73f5c7c502b2e68069` | `baea79f84f78a8b697b10af5e3e0de034d6c66e8` | 2026-10-19 | Fix limit() overflow when maxcardinality is near Integer.MAX_VALUE (#861) | RoaringBitmap.java, ImmutableRoaringBitmap.java | org/roaringbitmap/TestRoaringBitmap.java, org/roaringbitmap/buffer/TestRoaringBitmap.java | limitNearIntegerMaxValueDoesNotOverflow, limitNearIntegerMaxValueDoesNotOverflow | limit() overflow near Integer.MAX_VALUE; cardinality vs. int |
| B | `baea79f84f78a8b697b10af5e3e0de034d6c66e8` | `ffa685e6dbfb002c484f2bb5e0f3c5d6f5120088` | 2026-10-19 | Fix contains(long, long) for ranges ending in an absent key or at the end (#863) | RoaringBitmap.java, ImmutableRoaringBitmap.java | org/roaringbitmap/TestRoaringBitmap.java, org/roaringbitmap/buffer/TestImmutableRoaringBitmap.java | testContainsRange_LastKeyAbsent, testContainsRange_EndOfUniverse, testContainsRange_AgreesWithRangeCardinality | contains(long,long) range end / absent key; range-bound confusion |

## agrona

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| A | `2af8867dd59a0313ccd810f176e583c0a925eb5d` | `388e328c3f3bbe2b8dafa4c516d07aaf99b2b921` | 2025-12-18 | Fixes 2 bugs in the BufferUtil boundCheck. (#347) | BufferUtil.java | org/agrona/BufferUtilTest.java | shouldBoundsCheckByteArrayIndexWithoutLongOverflow, shouldBoundsCheckByteBufferIndexWithoutLongOverflow | bounds check: index + length overflow; index/length/capacity qualifiers (Index Checker-style) |
| A | `6ea35db9479a30d6b36d4544d2ca7e51ccb00f45` | `21abef9397e0eb981b319d1b60f373a7d9c10923` | 2026-11-20 | [Java] Fix DirectBufferDataInput readLine for unsigned bytes (#406) | DirectBufferDataInput.java | org/agrona/io/DirectBufferDataInputTest.java | shouldReadLinesIntoAppendableAlternativeCharset | signed byte read where unsigned required; signedness |
| B | `dba3c7ab09d2cfc2dbecde8bcfff601654a86276` | `e9124835f06286a83181b4b998b9c88b89590a38` | 2025-05-07 | [Java] Handle `nextOffset` overflow when inserting at the end of the buffer. | DistinctErrorLog.java | org/agrona/concurrent/errors/DistinctErrorLogTest.java | shouldWriteErrorAtEndOfTheBufferWithMaxIntCapacity | offset overflow when computing remaining capacity; offset vs. remaining |
| A | `8758c757db5df5475f78975050d7dcf2e6c4a441` | `ddbb495e1bb55b764798dac2b37acfce28aeeccd` | 2026-11-12 | [Java] Fix readUTF to support strings longer than 32767 bytes (#397) | DirectBufferDataInput.java | org/agrona/io/DirectBufferDataInputTest.java (abstract; run via DirectBufferDataInputAgronaTest) | shouldReadUtfLongerThan31Kilobytes | readUTF length limit (signed short vs unsigned length); promoted from B, see "Expected fit" |

## caffeine

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| A | `45fdb397dab51226fedbde9a916ac6bdeaa7df10` | `07156f73db8d30736afbe803078faf565c9f0c56` | 2026-03-29 | Fix ageOf to mask writeTime tag bit during refresh | BoundedLocalCache.java | com/github/benmanes/caffeine/cache/BoundedLocalCacheTest.java | ageOf_masksWriteTimeTagBit | writeTime has a tag bit that must be masked before subtracting: tagged vs raw nanos |
| A | `211dd547b743bcfc81cde2f9391441b6c22b806e` | `3c5dfc952f299009b32d3b83c2eb7d8b85892e1c` | 2026-02-21 | fix putIfAbsent calculating the read expiry from the offered new value | BoundedLocalCache.java | com/github/benmanes/caffeine/cache/BoundedLocalCacheTest.java | (inspect) | read-expiry computed from the offered new value instead of the existing value: oldValue/newValue confusion |
| B | `5993fc2ee7a1ce2b9a183f358ddeca4070f3e78e` | `fc44ba1dde66238effbc1bb784ae6e8a2520675a` | 2026-07-04 | Fix equals-receiver reversal in asMap read views | LocalAsyncCache.java, UnboundedLocalCache.java | com/github/benmanes/caffeine/cache/AsMapTest.java | containsValue_brokenCachedEquality, valuesRemove_brokenCachedEquality, entrySetContains_brokenCachedEquality | equals receiver reversed (cached value vs. caller-supplied); argument-order confusion |
| B | `07156f73db8d30736afbe803078faf565c9f0c56` | `b97bd0dd68f676f9fec589cc27fb90818878efe2` | 2026-03-29 | Fix SerializationProxy dropping zero-duration expiration on round-trip | SerializationProxy.java | com/github/benmanes/caffeine/cache/CacheTest.java | serialize_zeroDurationExpiration | zero-duration expiration dropped on serialization; duration units/sentinel |

## reactor-core

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| B | `c1d508641751d8439f49d6bcbbb555ca0abea195` | `a794eae1d1aacc684133e5124883e922467b8aab` | 2026-06-22 | Fix windowTimeout fair-backpressure index wraparound | FluxWindowTimeout.java | reactor/core/publisher/FluxWindowTimeoutTest.java | windowTimeoutFairBackpressure_unsentWindowDeliveredAfterIndexWrap | wrapped window index compared with > instead of !=; counter wraparound |
| B | `cd881ce4a27c401d7d3eb2d84c473334d509daa0` | `d358fb12380768da26928e752180a5868debb9db` | 2025-07-01 | Fix RetryBackoffSpec multiplier handling of fractional values (#4048) | RetryBackoffSpec.java | reactor/util/retry/RetryBackoffSpecTest.java | companionDelaysRetriesWithDefaultMultiplier, companionDelaysRetriesWithFractionalMultiplier | backoff multiplier truncated via (long) cast; Duration/nanos unit handling |
| C | `8573237b15693be90e2ff878cad3ed41a2f57958` | `98a2c9d0ab94a83e54d470266900e0b529b79f2f` | 2022-11-23 | Fix regression: Sinks.one()#tryEmitValue(null) (#3287) | SinkOneMulticast.java | reactor/core/publisher/SinkOneMulticastTest.java | nullTryEmitNextIsTurnedToTryEmitComplete | null passed to tryEmitValue treated as completion; nullness |

## jobrunr

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| B | `61c48de80b5d51dc4b4bd6b88e46760c850b7306` | `9a20480ece6120f7118e71fd47da8cdca080eace` | 2025-07-08 | Fix schedule durationBetweenSchedules validation that sometimes fails | Schedule.java | org/jobrunr/scheduling/ScheduleTest.java | durationBetweenSchedulesWithIntervals, durationBetweenSchedulesWithCronExpressions | schedule validation: next() must be computed from currentInstant; instant vs duration |
| C | `2033fa687c3726aedd4256e0cbedf4f9ab50970f` | `5907de3b58711616bc7f796e361088157f97e400` | 2026-10-26 | fix(core): reject combining scheduleAt and scheduleIn on JobBuilder | JobBuilder.java | org/jobrunr/scheduling/JobBuilderTest.java | testThatOnlyOneOfScheduleAtOrScheduleInIsAllowed | scheduleAt vs scheduleIn mutually exclusive; builder state |
| C | `498aaff72c345df4198bbd86e6648f0db76e0761` | `eb25c30c264aed1f5582c7e3a391d70fabd79b08` | 2026-06-24 | Fix Cron expression parsing during DST overlap (#1577) | CronExpression.java | org/jobrunr/jobs/RecurringJobTest.java, org/jobrunr/scheduling/cron/CronExpressionTest.java | testToScheduledJobsForDST, testCronDuringDst, now | cron parsing across DST overlap; local time vs instant (28 changed lines) |

## wiremock

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| B | `d774636cd490e0f81ae7ada01414d8ddf0b20eda` | `12d552a6f7f0ba8744e62c00f81980a17bf4d8d0` | 2026-10-27 | fix: replace a stub's old version in its scenario | Scenario.java | src/test/java/com/github/tomakehurst/wiremock/stubbing/ScenariosTest.java | replacesTheOldVersionOfAStubUpdatedWithinTheSameScenario | scenario update keeps the stub's old version: stale-vs-new mapping |
| C | `12d552a6f7f0ba8744e62c00f81980a17bf4d8d0` | `44957fe945a8a4eb2bd24e91738892be573833e6` | 2026-10-27 | fix: keep stub order when removing from a scenario | Scenario.java | src/test/java/com/github/tomakehurst/wiremock/stubbing/ScenariosTest.java | keepsTheOrderOfTheRemainingStubsWhenAStubIsRemoved | stub order lost when removing from a scenario |
| C | `c434a85f0bff99dfa0d68a5eb47c25b1f4d22ea6` | `074f265b58788d6abb28ac99b0168b0cb6557724` | 2026-11-17 | Fix NPE in Parameter one() method | Parameters.java | src/test/java/com/github/tomakehurst/wiremock/extension/ParametersTest.java, src/test/java/org/wiremock/webhooks/WebhookDefinitionTest.java | oneAcceptsANullValue, deserialisesExtraParametersWithNullValues | NPE in Parameters.one(): nullness |
| C | `e08dd98d90d48a13e549de39b9e626785f17d819` | `c03d6fcc99cd63e1cb22580fb955919dee48f3a1` | 2026-09-28 | Fix order-dependent multipart name and filename matching (#3514) | MultipartValuePatternBuilder.java | src/test/java/com/github/tomakehurst/wiremock/MultipartBodyMatchingAcceptanceTest.java | matchesMultipartNameAndFileNameIndependently | multipart name/filename matched order-dependently; name vs filename confusion |

## resilience4j

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| B | `cec580d01b3fc33dcb501043463f18ca881b9779` | `158b64c235ecec44791923e4155d7e74e1217c30` | 2026-09-23 | Fix ThreadPoolBulkheadConfig.from() mutating base config (#2507) | ThreadPoolBulkheadConfig.java | io/github/resilience4j/bulkhead/ThreadPoolBulkheadConfigTest.java | fromShouldNotMutateBaseConfig, fromShouldCopyAllFields | from() mutated the base config instead of a copy; shared mutable state |
| C | `21b45b2625d84e30fea79fd40f49c416cb899f68` | `4104cd2566ec7066b9b114ee5c8f4446a999dd35` | 2025-09-29 | [GH-2334] fix: reject TIME_BASED + LOCK_FREE when slidingWindowSize < 2 (#2358) | CircuitBreakerConfig.java | io/github/resilience4j/common/circuitbreaker/configuration/CircuitBreakerConfigurationPropertiesTest.java | shouldRejectLockFreeTimeBasedWithSize1 | config validation (TIME_BASED + LOCK_FREE size<2); precondition |
| C | `bdae34ae66a4f2d5d548b8e1c03d52358ce05f34` | `7333ba1a28cd53869f115a45ee669b76442df9bb` | 2026-08-31 | fix(ratelimiter): prevent SemaphoreBasedRateLimiter refresh task from terminating after a downw | SemaphoreBasedRateLimiter.java | io/github/resilience4j/ratelimiter/internal/SemaphoreBasedRateLimiterRefreshLimitRegressionTest.java | refreshSchedulerMustSurviveLimitDecreaseBelowAvailablePermits, refreshLimitClampsExcessPermitsWithoutThrowing | rate limiter refresh: permits clamp when limit decreases |

## picocli

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| B | `d567eff4d36bf08d456949187e02b24cbc5d3925` | `2158dcb92c89fe3d3e6ec28b22f6da5640ed3448` | 2022-02-28 | 1615: Fix bug in getCJKAdjustedLength() that double counts supplementary code points | CommandLine.java | src/test/java/picocli/CJKLengthTest.java | testCJKLengths, testLength | CJK length counted code units twice for supplementary code points: char vs code point; big single file |
| C | `b910763d03f5c7e268124109fec967ca98e2d59b` | `05ec0e83f77c945b9654d7824def8208e95211a5` | 2022-10-07 | Fix and unit test for issue 1834. | CommandLine.java | src/test/java/picocli/HelpTest.java | testIssue1834SynopsisForCharArrayOption | synopsis for char[] option |
| C | `bcb9cece79eb094276eadfa6e46f2f2a5f4bb576` | `94bc75d7a2c25fac7f7531a091427a102ff2bbd9` | 2022-10-07 | Fix and unit test for issue 1836. | CommandLine.java | src/test/java/picocli/MixinTest.java | testIssue1836CommandAliasOnMixin | command alias on mixin |

## JsonPath

| Tier | Commit (C) | Parent (C-1) | Date | Subject | Prod files | Test file | Test methods | Type-fit note |
|---|---|---|---|---|---|---|---|---|
| C | `5340aff05b9abd7e02de8db086b4a605310e7d70` | `66c54c36ab23a722bf41b600b21f70add14120ce` | 2022-05-10 | fix issue json-path#762 and add some test cases. (#821) | JsonSmartJsonProvider.java | com/jayway/jsonpath/Issue_762.java | testParseJsonValue | parse value in JsonSmartJsonProvider (2022, inactive repo) |
| C | `66c54c36ab23a722bf41b600b21f70add14120ce` | `678be51709da6542fe97ca44ab71c02435695133` | 2022-05-10 | [#789] Removed `unwrap()` method call from `getMapValue()` method of `JacksonJsonNodeJsonProvid | JacksonJsonNodeJsonProvider.java | com/jayway/jsonpath/JacksonJsonNodeJsonProviderMapperSupportTest.java | mapMethod_withJacksonJsonNodeJsonProvider_shouldUsingJsonNodeForMappingValues, readMethod_withJacksonJsonNodeJsonProvider_shouldReturnJsonNode, setMethod_withJacksonJsonNodeJsonProvider_shouldAcceptJsonNode | unwrap() wrongly applied in getMapValue (2022) |

## Per-repo notes (unverified; from build files / history, nothing was built)

- **RoaringBitmap**: multi-module (core `RoaringBitmap/`, `bsi`, `roaringbitmap` buffer variants); fixes often change the parallel `Mutable*` / `Immutable*` classes together, so one bug appears twice in the diff. Java 8 target; likely needs `source/target_compatibility: "8"` override and a JDK >= 17 toolchain check. Fast tests. **Recommended second target.**
- **agrona**: single Gradle project, Java 17, JUnit 5 + 4 mix, fast tests. Many fixes are overflow/bounds bugs on `DirectBuffer` code, the Index Checker / signedness family of properties.
- **caffeine**: heavy build (toolchains, Error Prone/NullAway plugins, TestNG + JUnit, many source sets). Tests under `caffeine/src/test` may be driven by TestNG test classes with data providers, so check that `--tests Class.method` selects them. Already compiled under NullAway, so nullness bugs are mostly pre-eliminated; the tagged-time and equals-order bugs are the interesting ones.
- **reactor-core**: very large module, Java 8 multi-release layout, slow compile/test. Concurrency bugs dominate; only the index-wrap and duration cases look tractable.
- **jobrunr**: Gradle multi-module; `core` tests are plain JUnit 5, but other modules use Testcontainers (Docker), so keep to `core`.
- **wiremock**: `buildSrc` + version catalog, tests start local HTTP servers (ports, slower, occasionally flaky). Good realistic code, weaker type-fit.
- **resilience4j**: multi-module Gradle with Groovy/Spock in some modules (not JUnit Java); candidates here are all in plain-Java modules. Fixes are small and well tested, weak type-fit.
- **picocli**: 20k-line `CommandLine.java` with Java 5/6 source level in the build. JDK 21 `javac` rejects `-source 5/6`, so a compatibility override is probably needed, and Checker Framework on a single 20k-line file will be slow. Treat as low priority.
- **JsonPath**: no fix commits since 2022 that meet the filter; stale repo. Low priority.

## Expected fit (recorded 2026-11-22, before any synthesis run)

The tier labels above were assigned from commit subjects alone. After reading each registered fix diff, every registered benchmark was given an `expected_fit` label in `benchmarks.yaml` (`good` / `builtin_only` / `poor`). The labels are a prediction to compare against pipeline outcomes later. The manifest loader ignores the field, and it is never shown to any agent.

How `SubtypingChecker` behaves decides most of these labels:
- It checks only pseudo-assignments: assignments, arguments, returns and overrides. It never checks comparisons or loop conditions.
- The result of arithmetic or bitwise operators takes the LUB of the operands' qualifiers, so `x & ~1L` cannot drop a qualifier.
- An unannotated cast keeps its operand's qualifier, so narrowing casts are invisible.
- Flow refinement applies to locals only, never to conditionals.

Two more constraints matter:
- Under the repair-stage declaration lock (`DiffAwareAstGuard` guard 4), changing a field's type is `SIGNATURE_TAMPERING`.
- The built-in checker path is currently hard-wired to `NullnessChecker` (`SynthesisStage.BUILTIN_PROCESSOR`) and is taken only when no qualifier sources are written.

| Benchmark | Expected fit | Rationale |
|---|---|---|
| `caffeine-putifabsent-read-expiry` | good | Confusion between two values of the same type: `value` is passed where `oldValue` belongs. With `@Present` as the default and the offered value `@Offered` (a supertype), `expireAfterRead(prior, key, value, ...)` is ill-typed. `oldValue = prior.getValue()` refines to `@Present`. That line is the only caller, and generated `Node` classes need no annotations. |
| `agrona-readline-unsigned` | good | `readByte()` returns `@SignExtended`, a supertype of the default. The local `nextByte` refines to it, and `appendable.append((char) nextByte)` fails because JDK parameters take the default. The natural fix, `readUnsignedByte()`, is the upstream fix. |
| `agrona-readutf-unsigned-length` | good | Same shape as readline. `readShort()` returns `@SignExtended`, and `size` flows into project-code sinks (`boundsCheck0`, `getStringWithoutLengthUtf8`). The natural fix, `readUnsignedShort()`, is the upstream fix. Changing a local's type is permitted. |
| `roaringbitmap-absent-values` | builtin_only | The bug is the comparison `fromValue < containerKey << 16`, which `SubtypingChecker` never checks. The Signedness Checker flags `<` on unsigned operands, and its natural fix (`Integer.compareUnsigned`) is behaviourally correct. |
| `roaringbitmap-runcontainer-foreach` | poor | Overflow in a loop condition. The Signedness Checker would flag `l - le <= base`, but the obvious `compareUnsigned` repair is wrong: with key 0, run start 5 and length 10, it skips the run. Only the upstream rewrite (iterate `0..le`) is correct. |
| `roaringbitmap-reverse-iter-short` | poor | Truncation by `(short)`, which a marker lattice cannot see. The Value Checker (`@IntRange`, Level 2) could flag it, but the only fix changes the field `short pos` to `int`, which the declaration lock rejects. |
| `caffeine-agetof-tagbit` | poor | Tagged versus untagged nanos is a natural qualifier, and `TimeUnit.convert` would be a default-qualified sink. But untagging via `& ~1L` keeps `@Tagged` under LUB propagation. A fix needs a helper (banned at Level 0), a custom refinement rule (executable checker code), or a qualified cast, which is effectively a suppression. 13 other `getWriteTime()` uses would add noise. |
| `agrona-bounds-check` | poor | Overflow in `index + length` plus a missing negative-length check, with no confusion between kinds of values. The Index Checker does not reason about overflow, and `@NonNegative length` shifts the obligation to callers rather than producing the upstream runtime check. |

`synthetic-id-swap`, `cmsx-assignment-extension` and `jsqlparser-index-kind` predate this review and are unlabelled.

Rejected promotion: caffeine `5993fc2e` (equals-receiver reversal). Flagging `cachedValue.equals(value)` needs a receiver qualifier on the JDK's `Object.equals`, which requires `.astub` overlays (not in MVP).
