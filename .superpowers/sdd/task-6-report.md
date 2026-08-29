# Task 6 Report — Durable Physical Card Custody and Recovery

## Status

Implemented and committed durable match-bound custody operation identity, duplicate-safe restoration gating, PREPARED recovery cleanup, exact snapshot custody, movement guards, strict CAS, upgrade migration coverage, and prior Task 6 recovery requirements.

Implementation commits: `83cdb3b` (`fix: bind custody operations to matches`) and `31b099a` (`fix: exercise durable duplicate recovery`), plus prior Task 6 commits `a504b4c`, `6d1af52`, and `b307ad7`.

## TDD and focused verification

Focused red command (run before the corrective production implementation):

```text
cmd /c gradlew.bat test --tests org.aincraft.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact red result (the first harmless package-qualified filter was accepted):

```text
> Task :compileTestJava FAILED
...SqliteCustodyTest.java:72: error: cannot find symbol
  symbol:   method markRestored(UUID,CardCopyId)
...SqliteCustodyTest.java:111: error: cannot find symbol
  symbol:   method findState(MatchId,CardCatalog)
...SqliteCustodyTest.java:157: error: cannot find symbol
  symbol:   method rehydrate(...)
10 errors
FAILURE: Build failed with an exception.
```

Corrective focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

Required focused green command including domain and bot regression coverage:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 1s
4 actionable tasks: 1 executed, 3 up-to-date
```

That earlier corrective run selected 4 storage tests, 2 application tests, AdvancedRules (17), RandomTranscript, MatchEngineStandard, MatchClock, and HeuristicBot; all selected non-codec tests passed. The selected codec test class contains 4 tests and all 4 were skipped by its explicit Paper-registry assumption (`Paper API item registry requires a running Paper server`). They were not executed proof. SQLite emitted the environment's Java restricted-native-access warning; it did not fail the suite. Final third-review counts and exact commands are recorded below. No formatter, linter, project-wide build, or project-wide test suite was run.

## Corrected coverage

- Durable custody journal: pure `CardCustodyJournal` port and SQLite implementation; operation owner, exact copy UUID order, exact serialized snapshots, status, restored UUIDs, and open-operation hydration persist across reconstruction. PREPARED, ESCROWED, RETURN_PENDING, RETURNED, and ABORTED transitions are idempotent.
- Inventory custody: production constructor takes a durable journal; startup hydrates open operations; exact restoration records each UUID durably and creates validated recovery rows when the owner is offline or inventory has no room. Click/drag/number-key/swap/drop/death/quit adapter methods reject or retain reserved items on the Paper primary thread.
- Complete MatchState snapshots: versioned codec persists catalog snapshot ID, rules, revision, current seat, all board copy/card/owner/effective values, ordered hands and copy owners, stored banks, all timestamps, terminal result/scores, match seed, command IDs, transcript decisions/seed/cursor/replay mode, revealed cards, original physical-copy owners, Sudden Death count, Draft offerings/selections/provenance/house IDs, Chaos choices, and settlement-derived metadata. Rehydration resolves definitions from the caller's matching catalog snapshot and rejects mismatches rather than synthesizing partial cards.
- Startup recovery: `MatchApplicationService.recoverStartup` enumerates all open custody operation IDs and all active repository snapshots, rehydrates active states through a catalog resolver, and applies one deterministic idempotent timeout command for overdue turns.
- Stable codec secret: ephemeral random constructor defaults were removed. One-argument production constructors require a configured stable server secret; explicit secret constructors remain available for tests, including two codec instances reading a card minted by the first.
- Exact start protocol: standard start validates five distinct requested UUIDs against each physical deck, owner, and inventory card identity before either reservation. Seat-two prepare, domain rejection, persistence/CAS rejection, and database failure share durable cleanup and preserve pending recovery state; stale save failures map to `CardCustodyPort.Failure.STALE_REVISION`.
- SQLite constraints/CAS: composite `(operation_id, copy_uuid)` recovery foreign key, same-transaction reservation-owner validation, restored-copy ledger, and strict new-row revision 0 / successor revision `expectedRevision + 1` checks are present.

## Thread-boundary notes

`domain` and `application` remain free of Bukkit, Paper, Adventure, MapGui, JDBC, and filesystem imports. `inventory` owns Paper/Bukkit event and inventory access and requires the primary thread for movement or custody mutation. `storage` owns JDBC and accepts immutable values/serialized bytes only. Snapshot rehydration resolves definitions through a domain `CardCatalog`; live Bukkit objects never cross into SQLite.

## Concerns and environmental limitations

1. Paper `ItemStack` construction requires a running Paper registry implementation; this environment has no Paper server/MockBukkit registry, so all four codec tests were explicitly skipped rather than reported as executed.
2. The SQLite JDBC dependency emits Java's restricted native-access warning under this JVM. The required focused tests still completed successfully.
3. Startup rehydration requires the host lifecycle to provide a catalog resolver keyed by the persisted catalog snapshot ID; this is intentional so stale or incompatible definitions cannot be silently synthesized.

## Task 6 second-review corrections

Implementation commit: `b307ad7` (`fix: close custody recovery windows`)

The required focused red command was run after adding the recovery and RANDOM regression tests:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result:

```text
> Task :test FAILED
SqliteCustodyTest > markingRestoredClearsRecoveryInSameTransaction() FAILED
53 tests completed, 1 failed, 4 skipped
BUILD FAILED in 1s
```

Exact green result after implementation:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

Corrections cover byte-for-byte same-UUID restoration and replacement, one-transaction restored-ledger/recovery-row completion, PREPARED-to-RETURN_PENDING recovery after removal, explicit SWAP_OFFHAND protection, structured first-seat/start cleanup, pure-transition RANDOM selected UUID reservation, and omission of generated house-owned Draft copies from player custody. Recovery owner/FK validation and strict custody transitions remain transactional and idempotent.

The final focused run still skipped the four Paper-registry codec tests because no running Paper registry is available; they are not executed proof. No formatter, linter, project-wide build, or project-wide suite was run.
## Task 6 third-review corrections

Implementation commits: `83cdb3b` (`fix: bind custody operations to matches`) and `31b099a` (`fix: exercise durable duplicate recovery`)

The regression-focused red command was run after adding match-bound retry, terminal-operation retry, first-seat rejection cleanup, PREPARED recovery, duplicate-restoration recovery, and V2-to-V3 migration tests:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result for the duplicate-restoration regression before the focused restore seam was implemented:

```text
> Task :compileTestJava FAILED
CardInventoryServiceTest.java:36: error: cannot find symbol
  symbol:   method restoreAmbiguous(UUID,CopyOwner,List<CardCopyId>,List<SerializedItemSnapshot>,Set<Object>,SqliteCardRepository)
1 error
FAILURE: Build failed with an exception.
```

Exact green result for the strengthened regression command:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

The required focused adapter/domain/bot command was rerun after the strengthened tests:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 1s
4 actionable tasks: 1 executed, 3 up-to-date
```

The extra regression command selected 61 tests: 57 passed and 4 Paper-registry codec tests were assumption-skipped. The required command selected 60 tests: 56 passed and the same 4 codec tests were assumption-skipped. Current source counts are 7 storage tests, 7 application tests, and 1 CardInventoryService test. The duplicate regression now drives the durable recovery seam and verifies `RETURN_PENDING`, no restored UUIDs, and byte-for-byte recovery snapshots. The PREPARED regression uses the SQLite journal and verifies an exact pending recovery row. The V2-to-V3 migration regression removes the V3 column/marker from a live fixture, reruns migration, preserves old rows, and accepts a new match-bound row. No formatter, linter, project-wide build, or project-wide test suite was run.

Durability corrections:

- Every match start now calls a match-bound custody preparation API. SQLite schema version 3 persists `reservations.match_id`; the SQLite and in-memory journals compare match identity together with owner and ordered copy IDs across retries/restarts. Legacy/unaware custody adapters reject match-bound preparation instead of silently reusing an operation.
- The process-local `MatchApplicationService.starts` cache was removed. Terminal `RETURNED` and `ABORTED` operations cannot be prepared again, so cleanup/settlement cannot be bypassed by stale cache entries.
- Restore inspects duplicate/counterfeit scan state before acknowledging any copy. Ambiguous scans queue exact snapshots as durable recovery and stay `RETURN_PENDING`; modified same-UUID items still require byte-for-byte replacement or remain pending.
- PREPARED startup recovery transitions to `RETURN_PENDING` and queues exact snapshots before attempting return. First-seat prepare rejection does not clean unknown operations or report phantom missing/recovery UUIDs; only accepted/known operations are cleaned on later failures.
- `SWAP_OFFHAND` and existing inventory movement protections remain primary-thread-bound. Paper's no-server `Bukkit.isPrimaryThread()` null failure is treated as unavailable only for test/runtime-less API use.

No formatter, linter, project-wide build, or project-wide test suite was run. SQLite emitted its Java restricted-native-access warning during the focused runs.


Implementation commits for this correction: `c2458e1` (`fix: preserve active custody on startup`) and
`61e14c3` (`fix: preserve active custody on startup`, final DRAFT owner/copy validation repair).
The preceding report/source combined commit is `aff8144`; this report-only correction follows it.

### Corrective coverage

- Startup loads active match snapshot IDs before custody reconciliation. An open `ESCROWED`
  operation is preserved when its durable match binding belongs to an active match. Orphaned
  `PREPARED`, `ESCROWED`, and `RETURN_PENDING` rows are still reconciled, including when there
  are no active matches. Each recovery exception is converted into a structured
  `RecoveryResult` failure and the sweep continues; active-state rehydration isolates missing
  catalogs and unsupported snapshots per match.
- A persisted active start is compared against the complete freshly evaluated initial state and
  durable operation bindings. An identical retry returns the loaded state without another
  `save(0, ...)` and without returning or aborting custody. Different rules, owners, copy sets,
  match IDs, operation IDs, or non-escrowed custody state are rejected. The comparison survives
  service reconstruction through `MatchRepository.findState` and custody metadata rather than a
  process-local start cache.
- Application `prepare` catches custody removal/journal-transition exceptions and invokes the
  same durable cleanup path used by start-level failures. This prevents a removed card from being
  left with only `PREPARED` status.
- `CardInventoryService` now exposes primary-thread handlers for offhand swaps,
  automated `InventoryMoveItemEvent` transfers (checking source, destination, and moved stacks),
  and entity pickup transfers. Existing click, drag, number-key, drop, death, and quit guards
  remain in place.
- Player-seat starts reject `CopyOwner.house()` before DRAFT/RANDOM early exits, including plans
  whose generated house cards produce empty player custody lists. Existing byte-for-byte
  restoration and duplicate-UUID pending/recovery invariants are unchanged.

### TDD and focused verification

The required focused command was run red after adding the startup-preservation, idempotent retry,
transition-exception cleanup, and house-owner regression tests:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

The initial red compile failed because the new tests referenced the not-yet-added durable
`operationMatchId`, `operationStatus`, and `MatchRepository.findState` seams; after correcting the
test-only import, the focused compile reported those missing production methods. This was the
expected pre-implementation failure.

The same command passed after implementation:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The selected Paper codec tests remain assumption-skipped in this environment because Paper's item
registry requires a running server; the other selected adapter, storage, application, domain, and
bot tests executed successfully. SQLite emitted only the JVM restricted-native-access warning.

The final focused rerun after adding the automated `InventoryPickupItemEvent` guard also passed:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

Latest implementation guard commit: `1d16dd3` (`fix: preserve active custody on startup`).

The retry path now validates the computed reservation plan before checking a persisted match, so a
different RANDOM copy selection cannot be accepted as an identical retry. This final implementation
adjustment is `26c5c53` (`fix: preserve active custody on startup`); the preceding movement guard
adjustment is `1d16dd3` with the same required subject.
The preceding report commit is `6f8894a`; this update is report-only.
No formatter, linter, project-wide build, or project-wide test suite was run.
## Task 6 durable identity and custody correction (implementation `341bc4f`, report `920a4da`)

Implementation commit: `341bc4f` (`fix: enforce durable start identity`).

### Corrective coverage

- Startup escrow reconciliation keeps an `ESCROWED` operation bound to an active durable match,
  while orphaned `PREPARED`, `ESCROWED`, and `RETURN_PENDING` operations are recovered independently.
- Start retries now require a versioned durable `match_start_bindings` row containing the request
  identity, seat owners, ordered custody copy UUIDs, and both operation UUIDs. SQLite and
  reconstructed application services compare this binding before returning the existing revision-0
  state; swapped operation IDs and changed DRAFT empty-custody plans are rejected.
- Custody operation binding metadata is read from the durable journal rather than a process-local
  operation cache, and explicit inventory restoration is package-private and verifies the owner's
  live inventory identity.
- Cross-seat physical UUID overlap is rejected before reservation and by `MatchEngine.start`.

### TDD and focused verification

The focused red command was:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result after adding the overlap regression and before its implementation:

```text
> Task :test FAILED
MatchApplicationServiceTest > overlappingSeatCopiesAreRejectedBeforeReservation() FAILED
68 tests completed, 1 failed, 4 skipped
FAILURE: Build failed with an exception.
```

The same focused command passed after implementation:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 2 up-to-date
```

The final focused result selected 69 tests: 65 executed successfully and 4
`CardItemCodecTest` tests were assumption-skipped because Paper's item registry requires a running
Paper server. SQLite emitted only the JVM restricted-native-access warning. No formatter, linter,
project-wide build, or project-wide test suite was run.
# Task 6 final custody/start fixes

## Status

Implemented SWAP reservation preservation, atomic revision-0 match/start-binding persistence,
strict durable custody binding checks on retries, and the prior Task 6 custody/recovery behavior.

Implementation commit: `f11ad04` (`fix: make start custody atomic`).

## TDD and focused verification

Focused adapter/domain/bot command (red, before implementation):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result:

```text
> Task :compileTestJava FAILED
MatchApplicationServiceTest.java:554: error: method does not override or implement a method from a supertype
    @Override
    ^
1 error
FAILURE: Build failed with an exception.
```

Focused adapter/domain/bot command (green):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

The final focused selection completed 72 tests: 68 passed and 4 Paper-registry codec tests were
assumption-skipped because no running Paper server was available. SQLite emitted only the JVM
restricted-native-access warning. No formatter, linter, project-wide build, or project-wide test
suite was run.

## Corrective coverage

- SWAP starts reserve the five requested pre-SWAP physical UUIDs per seat. Settlement state maps
  all ten cards back to their original owner seats; the regression asserts both five-copy
  reservations and original-owner settlement.
- `MatchRepository.saveInitial` is the start-only persistence operation. SQLite inserts revision-0
  match state and its durable start binding in one transaction; application start uses this
  operation and performs durable custody cleanup on a transaction/binding failure.
- Active retries require a present durable `OperationBinding` for every nonempty seat reservation,
  with exact match ID, owner, ordered copy UUID list, and `ESCROWED` status. Default/absent metadata
  is rejected; only intentionally empty Draft custody plans bypass the binding requirement.

## Thread-boundary notes

No new live Bukkit/Paper objects cross into application or storage. SQLite receives immutable match
state and binding values only; custody cleanup remains through the custody port.

## Concerns

- Paper codec tests remain assumption-skipped without a running Paper item registry.
- SQLite emits Java's restricted-native-access warning under this JVM.
# Task 6 final atomic/snapshot fixes

## Status

Implemented and committed the atomic initial-match persistence contract and duplicate physical-copy
UUID rejection during snapshot rehydration.

Implementation commit: `85da59b` (`fix: require atomic match snapshots`).

## TDD and focused verification

Focused adapter/domain/bot command (red, after adding atomic-default, SQLite rollback, and
duplicate physical UUID snapshot regressions):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result:

```text
> Task :test FAILED

MatchApplicationServiceTest > defaultInitialSaveRejectsWithoutWritingEitherRow() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:200

SqliteCustodyTest > snapshotRejectsDuplicateHandAndBoardCopyUuid() FAILED
    org.opentest4j.AssertionFailedError at SqliteCustodyTest.java:272

75 tests completed, 2 failed, 4 skipped
FAILURE: Build failed with an exception.
4 actionable tasks: 3 executed, 1 up-to-date
```

Focused adapter/domain/bot command (green):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

The selected Paper codec tests remain assumption-skipped because this environment has no running
Paper item registry. SQLite emits the JVM restricted-native-access warning; it does not fail the
focused suite. No formatter, linter, project-wide build, or project-wide test suite was run.

## Corrective coverage

- `MatchRepository.saveInitial` now has a safe default that validates arguments and returns
  `INVALID_STATE` without invoking either legacy persistence method. Test doubles used by
  application tests implement the atomic operation explicitly.
- `SqliteMatchRepository.saveInitial` already performs the revision-0 match insert and durable
  start-binding insert in one transaction; the new trigger-injected failure regression verifies the
  match row is rolled back when binding persistence fails.
- `MatchStateSnapshotCodec.decode` maintains one authoritative `CardCopyId` set while reading both
  hands and occupied board cells, rejecting a duplicate before creating executable `HandCard` or
  board state. Draft offering/selection copies remain outside this active set and continue their
  separate catalog/structure validation.

## Concerns

- Paper codec tests remain assumption-skipped without a running Paper item registry.
- SQLite emits Java's restricted-native-access warning under this JVM.
# Task 6 final persistence/owner/abort fixes

## Status

Implemented and committed as `ccbd1f3` (`fix: require persisted match state`), with cleanup
hardening in `aba1cae` (`fix: preserve cleanup recovery on abort failure`).
The production two-argument `MatchApplicationService` constructor now requires a repository, while
the isolated one-argument constructor uses the explicit `InMemoryMatchRepository` with initial-save,
snapshot lookup, and optimistic CAS semantics. Accepted starts and accepted commands always persist
before success is reported. RANDOM starts validate selected UUIDs, physical-copy owners, and exact
inventory card identities before custody. The `CardCustodyPort.abort` default is explicitly
unsupported, and cleanup preserves pending recovery when an adapter cannot durably abort.

## TDD and focused verification

Focused adapter/domain/bot command (red, before implementation):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red output:

```text
> Task :test FAILED

MatchApplicationServiceTest > cleanupPreservesRecoveryWhenAdapterUsesUnsupportedDefaultAbort() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:537

MatchApplicationServiceTest > oneArgumentServicePersistsApplyAndEnforcesCas() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:68

MatchApplicationServiceTest > randomStartRejectsWrongOwnerBeforeReservation() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:161

78 tests completed, 3 failed, 4 skipped

FAILURE: Build failed with an exception.
> There were failing tests. See the report at: file:///C:/Users/blaze/IdeaProjects/Aincraft-MobMatrix/build/reports/tests/test/index.html

BUILD FAILED in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

Focused adapter/domain/bot command (green, after implementation):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green output:

```text
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

The final focused selection completed 78 tests: 74 passed and 4 Paper-registry codec tests were
assumption-skipped because no running Paper server was available. SQLite emitted only the JVM
restricted-native-access warning. No formatter, linter, project-wide build, or project-wide test
suite was run.

## Corrective coverage

- One-argument service starts and applies use explicit in-memory persistence; repeated application
  against the same revision is rejected as `STALE_REVISION`.
- RANDOM selection validates exactly five distinct UUIDs for each seat, each selected physical
  `CardCopy.owner()`, each inventory copy owner, and matching inventory card IDs before `prepare`.
  Pure domain RANDOM start rejects inconsistent seat inventory owners before generating a match.
- Unsupported default abort returns a failure result rather than claiming `ABORTED`. Cleanup checks
  both acceptance and status and retains expected UUIDs as pending recovery when abort is unsupported.
- Existing SQLite custody, match-bound retry, atomic initial save, duplicate snapshot, SWAP, exact
  restore, movement, startup recovery, and schema behavior remain covered by the focused command.

## Thread-boundary notes and concerns

The in-memory repository contains immutable domain states only; durable production wiring remains
the explicit repository constructor. No Bukkit/Paper objects cross application or storage. Paper
codec tests remain assumption-skipped without a running Paper registry, and SQLite emits the JVM
restricted-native-access warning.
# Final focused verification rerun

The exact focused adapter/domain/bot command was rerun against the committed implementation
(`ccbd1f3` persistence/owner changes and `aba1cae` abort-cleanup hardening; report baseline
`3aa88a0`):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test UP-TO-DATE

BUILD SUCCESSFUL in 586ms
4 actionable tasks: 4 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

No formatter, linter, project-wide build, or project-wide test suite was run.

## Task 6 canonical apply and strict Draft snapshot fixes

### Status

Implemented and committed canonical persisted apply and strict Draft snapshot metadata
validation.

Implementation commit: `a3953b7` (`fix: validate canonical match state`).

### TDD and focused verification

The focused adapter/domain/bot command was run after adding the divergent-state and Draft
metadata regressions, before the production implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result:

```text
> Task :test FAILED

MatchApplicationServiceTest > applyUsesPersistedCanonicalStateInsteadOfCallerSnapshot() FAILED
SqliteCustodyTest > draftSnapshotRejectsMalformedProvenanceReference() FAILED
SqliteCustodyTest > draftSnapshotRejectsSelectionReferenceOutsideOfferings() FAILED
SqliteCustodyTest > draftSnapshotRejectsOfferingIdsDuplicatedAcrossSeats() FAILED
SqliteCustodyTest > draftSnapshotRejectsOfferingCopyDuplicatedByAuthoritativeHand() FAILED
SqliteCustodyTest > draftSnapshotRejectsPlayerOwnedOfferingCopy() FAILED

85 tests completed, 6 failed, 4 skipped
FAILURE: Build failed with an exception.
```

The same focused command passed after implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green result:

```text
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused selection completed 85 tests: 81 passed and 4 Paper-registry codec tests were
assumption-skipped because no running Paper server is available. SQLite emitted only the JVM
restricted-native-access warning. No formatter, linter, project-wide build, or project-wide
test suite was run.

### Corrective coverage

- `MatchApplicationService.apply` first loads `matches.findState(state.matchId(), state.catalog())`,
  rejects absent or failed rehydration, applies only to that canonical state, and CAS-saves with
  the loaded revision. Revision mismatches retain structured stale handling.
- The application regression passes a divergent same-revision caller snapshot and verifies that
  its command is rejected without a save or persisted-state overwrite. Existing accepted-command
  persistence and CAS coverage remains green.
- Snapshot decode requires every offering copy to be HOUSE-owned and catalog-valid, rejects
  duplicate offering seats/IDs/copy UUIDs and conflicting active hand/board identities, and keeps
  legitimate DRAFT hand/deck references as references rather than new physical copies.
- Draft selections, five-card decks, and provenance must resolve to exact authoritative offering
  copies, match each seat's choice indexes, preserve HOUSE ownership, and use valid offering IDs.
  Non-DRAFT snapshots cannot carry Draft metadata, while DRAFT snapshots require both seats'
  offering and selection records.
- Malformed provenance, outside references, player-owned/tampered offering copies, duplicate
  offering IDs, and active-copy conflicts are covered by focused regressions. Legitimate encoded
  DRAFT snapshots continue to rehydrate with player custody unaffected.

### Thread-boundary notes and concerns

No Bukkit/Paper objects cross into application or storage. Snapshot validation remains pure
binary/domain rehydration. The Paper registry limitation and SQLite JVM warning remain as
described in the preceding report sections.

## Task 6 final ownership/CHAOS/catalog identity fixes

### Status

Implemented and committed snapshot ownership metadata completeness, CHAOS choice integrity,
and persisted catalog-definition identity validation.

Implementation commit: `f9b6710` (`fix: enforce snapshot ownership metadata`).

### TDD and focused verification

The focused adapter/domain/bot command was run red after adding incomplete/extra/wrong-seat
original-owner, invalid CHAOS, and same-ID altered-catalog regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test FAILED

SqliteCustodyTest > snapshotRejectsIncompleteOriginalOwnerMetadata() FAILED
SqliteCustodyTest > snapshotRejectsChaosChoiceOutsideThatSeatsHand() FAILED
SqliteCustodyTest > snapshotRejectsChaosChoiceInNonChaosState() FAILED
SqliteCustodyTest > snapshotRejectsOriginalOwnerMappedToWrongSeat() FAILED
SqliteCustodyTest > findStateRejectsSameIdAlteredPersistedCatalogDefinition() FAILED
SqliteCustodyTest > snapshotRejectsExtraOriginalOwnerMetadata() FAILED

91 tests completed, 6 failed, 4 skipped
FAILURE: Build failed with an exception.
```

The same focused command passed after implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green output:

```text
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused selection completed 91 tests: 87 passed and 4 Paper-registry codec tests were
assumption-skipped because no running Paper server was available. SQLite emitted the JVM
restricted-native-access warning. No formatter, linter, project-wide build, or project-wide
test suite was run.

### Corrective coverage

- Snapshot decode now requires `originalOwners` to exactly cover every non-house physical copy
  in active hands and occupied board cells. Duplicate entries, missing/extra IDs, null seats,
  and inconsistent original seats for one player owner are rejected before rehydration. Generated
  HOUSE-owned Draft offering copies remain excluded from the required key set.
- CHAOS metadata now rejects all entries in non-CHAOS snapshots, rejects duplicate seat keys,
  and requires every CHAOS choice to be a copy currently in that seat's authoritative hand.
  Invalid choices therefore cannot reach timeout or recovery handling.
- SQLite `findState` and catalog-resolver rehydration now require a persisted complete
  definition set and compare every CardDefinition field against the supplied catalog.
  Revision-0 `save` and atomic `saveInitial` persist the complete set in the same transaction;
  existing snapshot IDs are never overwritten with altered definitions.

### Thread-boundary notes and concerns

The changes remain inside pure snapshot/storage rehydration. No Bukkit/Paper objects cross into
application or storage. Paper codec tests remain assumption-skipped without a running Paper
registry, and SQLite emits the JVM restricted-native-access warning.

## Task 6 final catalog/settlement rehydration fixes

### Status

Implemented fail-closed SQLite catalog persistence/rehydration and corrected original-owner
decoding for captured cards and plain legacy snapshots.

Implementation commit: `3a2b2e2` (`fix: fail closed catalog rehydration`).

### TDD and focused verification

Focused command used for both red and green runs:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result after adding the regressions and before implementation:

```text
> Task :test FAILED

SqliteCustodyTest > findStateRejectsAbsentPersistedCatalogDefinitions() FAILED
SqliteCustodyTest > plainInitialSnapshotWithEmptyOriginalOwnersRehydrates() FAILED
SqliteCustodyTest > catalogSnapshotCannotBeOverwrittenWithAlteredDefinitions() FAILED
SqliteCustodyTest > nonSwapSuddenDeathCapturedCardRehydratesAfterRestart() FAILED
SqliteCustodyTest > directRevisionZeroSavePersistsCompleteCatalogDefinitions() FAILED

96 tests completed, 5 failed, 4 skipped

FAILURE: Build failed with an exception.
```

Exact green result after implementation:

```text
> Task :test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused selection passed with the four Paper-registry codec tests assumption-skipped because
no Paper server was available. SQLite emitted only the JVM restricted-native-access warning.
No formatter, linter, project-wide build, or project-wide test suite was run.

### Corrective coverage

- Revision-0 direct saves and atomic `saveInitial` persist complete catalog definitions in the
  same transaction as match state (and start binding for `saveInitial`).
- Catalog snapshot rows are immutable by snapshot ID: incomplete, altered, or absent persisted
  definitions reject both save and rehydration; matching existing definitions remain idempotent.
- Non-SWAP sudden-death snapshots may restore captured cards in the opposing seat's hand while
  retaining the original settlement owner.
- RANDOM and SWAP snapshots require exact original-owner key coverage for all active non-house
  copies; missing, extra, duplicate, and inconsistent metadata remains rejected.
- Empty original-owner metadata is accepted only for explicit non-RANDOM/non-SWAP legacy/plain
  snapshots, where ownership is inferred from each card's current hand/board seat.

## Task 6 final terminal recovery/catalog metadata fixes

### Status

Implemented terminal recovery status preservation and immutable catalog snapshot metadata
validation.

Implementation commit: `f4a9c0b` (`fix: preserve terminal custody status`).

### TDD and focused verification

Focused command used for the red and green runs:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result after adding both regressions and before the production implementation:

```text
> Task :test FAILED

CardInventoryServiceTest > recoverPreservesAbortedTerminalStatus() FAILED
    org.opentest4j.AssertionFailedError at CardInventoryServiceTest.java:69

SqliteCustodyTest > catalogSnapshotMetadataCannotBeOverwritten() FAILED
    org.opentest4j.AssertionFailedError at SqliteCustodyTest.java:443

98 tests completed, 2 failed, 4 skipped

FAILURE: Build failed with an exception.
```

Exact green result after implementation:

```text
> Task :test

BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused selection passed; the four Paper-registry codec tests were assumption-skipped
because no running Paper server was available. SQLite emitted only the JVM
restricted-native-access warning. No formatter, linter, project-wide build, or
project-wide test suite was run.

### Corrective coverage

- `RecoveryResult.complete(operationId, status, copyIds)` now accepts and preserves only
  terminal `RETURNED` or `ABORTED` statuses. `CardInventoryService.recover` uses the
  durable operation status for terminal rows and the return result status after successful
  restoration, so ABORTED remains ABORTED while complete.
- Catalog snapshot persistence now reads and compares persisted `source`, `digest`, and
  `metadata_json` before accepting an existing snapshot ID, in addition to comparing every
  card-definition field. Altered metadata is rejected before any row update and the
  original metadata remains unchanged.
- The existing altered-definition rehydration regression now exercises a complete direct
  repository save path, while the new metadata regression verifies source, digest, and JSON
  changes are rejected atomically and matching metadata remains intact.

### Thread-boundary notes and concerns

Changes remain within the pure custody result API, Paper inventory recovery adapter, and
JDBC catalog persistence boundary. No live Bukkit/Paper objects cross into storage or
application. Existing Paper registry and SQLite native-access environment limitations remain
as documented above.

## Task 6 final catalog metadata/count hardening

### Status

Implemented catalog metadata reuse for match snapshot saves and bounded, fail-closed
collection decoding.

Implementation commit: `08eb8ce` (`fix: harden snapshot persistence`).

### Corrective coverage

- `SqliteMatchRepository` now checks an existing catalog snapshot's complete persisted
  definition set without comparing against synthetic match metadata. Existing imported
  `source`, `digest`, and `metadata_json` remain untouched; altered definitions still
  reject atomically. New snapshot IDs still create deterministic metadata and all
  definitions in the match transaction, for both direct revision-0 saves and atomic
  initial saves.
- `MatchStateSnapshotCodec` uses a bounded nonnegative `readCount` for every encoded
  dynamic collection: rules, transcript decisions, hands, revealed IDs, original
  owners, Draft seats/offerings/cards, selections/choices/selected/deck/provenance,
  house copies, Chaos entries, UUID sets, and nested collections.
- Duplicate set/rule entries are rejected rather than normalized. Existing duplicate
  map validations remain fail-closed, while repeated valid Draft references continue
  to resolve to authoritative offering copies.
- Focused regressions cover pre-imported catalog metadata followed by match save,
  negative original-owner count, and negative nested hand count.

### TDD and focused verification

Focused command used for the red and green runs:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result before the production implementation:

```text
> Task :test FAILED

SqliteCustodyTest > matchSaveReusesMetadataFromPreImportedCatalogSnapshot() FAILED
    java.lang.IllegalArgumentException at SqliteCustodyTest.java:466

101 tests completed, 1 failed, 4 skipped

FAILURE: Build failed with an exception.
```

Exact green result after implementation:

```text
> Task :test

BUILD SUCCESSFUL in 4s
4 actionable tasks: 3 executed, 1 up-to-date
```

SQLite emitted the JVM restricted-native-access warning; it did not fail the focused
selection. No formatter, linter, project-wide build, or project-wide test suite was
run.

## Task 6 final persistence-integrity fixes

### Status

Implemented and committed as `5750a93` and follow-up hardening `24f82b4` (both
`fix: close persistence integrity gaps`).
Initial-save rejection and exception paths now re-read durable match state, start binding, and
exact ESCROWED custody bindings before cleanup. A matching concurrent winner is returned as the
active state; unavailable or ambiguous durable reads report `DATABASE_ERROR` and leave custody
for startup recovery. Apply persistence failures retain their `STALE_REVISION`,
`DATABASE_ERROR`, or `INVALID_STATE` classification. Snapshot decoding rejects active HOUSE
copies outside authoritative Draft offerings, enforces current-seat-only CHAOS choices (with
the empty-current-hand exception), and SQLite rehydration rejects embedded/row match-ID
mismatches.

### TDD and focused verification

The focused adapter/domain/bot command was run red after adding the initial regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact initial red output:

```text
> Task :test FAILED

MatchApplicationServiceTest > applyPreservesDatabaseAndInvalidStateSaveFailures() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:111

MatchApplicationServiceTest > rejectedInitialSaveKeepsConcurrentMatchingWinnerCustody() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:130

104 tests completed, 2 failed, 4 skipped
```

After the thrown-save race regression was added, the focused command was red before its
production catch-path correction:

```text
> Task :test FAILED

MatchApplicationServiceTest > thrownInitialSaveKeepsConcurrentMatchingWinnerCustody() FAILED
    org.opentest4j.AssertionFailedError at MatchApplicationServiceTest.java:149

110 tests completed, 1 failed, 4 skipped
```

Exact final green output:

```text
> Task :test

BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The final fail-closed winner-binding hardening rerun used the same focused command and produced:

```text
> Task :test

BUILD SUCCESSFUL in 3s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused selection's four Paper-registry codec tests remain assumption-skipped because no
running Paper server is available. SQLite emitted only the JVM restricted-native-access warning.
No formatter, linter, project-wide build, or project-wide test suite was run.

### Corrective coverage

- Initial-save rejection and thrown-save race regressions verify matching durable winners are
  returned without `returnCopies`; ambiguous reads preserve custody and report database failure.
- Apply regressions verify database and invalid-state save failures are not collapsed into stale.
- Snapshot regressions cover non-Draft HOUSE hand copies, opponent and missing current-seat CHAOS
  choices, valid empty-current-hand CHAOS snapshots, and embedded/row match-ID mismatch through
  both `findState` and direct `rehydrate`.
- Legitimate Draft offering references remain accepted, while non-authoritative active HOUSE
  copies are rejected before executable state exposure.

## Task 6 final custody/state integrity fixes

### Status

Implemented and committed as `a9a658d` (`fix: harden custody state transitions`).

### Corrective coverage

- `MatchApplicationService.apply` now catches runtime failures from successor `matches.save`
  and returns `DATABASE_ERROR`, while preserving explicit stale/database/invalid-state mappings.
- Inventory operation caches refresh durable journal status and restored-copy state before
  `recover`/`abort`/return results. PREPARED escrow now compares each live serialized item
  byte-for-byte with its prepare snapshot before removing it, returning `INVENTORY_CHANGED`
  without mutation on drift.
- SQLite `findState` and both `rehydrate` overloads reject decoded match revisions that differ
  from the row revision; the in-memory rehydration seam applies the same identity check.
- Active retry custody-binding reads distinguish runtime durable-read failures (`DATABASE_ERROR`)
  from successfully absent or nonmatching metadata (`MISMATCHED_OPERATION`).
- Startup catches `openOperationIds()` failures as a structured `StartupRecoveryResult`
  `custodyFailure` without inventing an operation ID, leaves custody untouched, and continues
  independent active-match rehydration.
- Focused regressions cover thrown apply saves, unavailable retry binding reads, startup
  enumeration failure, durable RETURN_PENDING visibility after ambiguous restore, and row/state
  revision mismatch. Existing bounded collection, duplicate-entry, Draft reference, HOUSE/CHAOS,
  original-owner, and decoded identity validations remain fail-closed.

### TDD and focused verification

Focused command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result after temporarily removing the production save-exception catch:

```text
> Task :test FAILED

MatchApplicationServiceTest > applyCatchesThrownSaveAsDatabaseError() FAILED
    java.lang.IllegalStateException at MatchApplicationServiceTest.java:126

115 tests completed, 1 failed, 4 skipped
```

Exact green result after restoring the implementation:

```text
> Task :test

BUILD SUCCESSFUL in 653ms
4 actionable tasks: 2 from cache, 2 up-to-date
```

The four Paper codec tests remain assumption-skipped because this environment has no running
Paper item registry. SQLite emitted only the JVM restricted-native-access warning. No formatter,
linter, project-wide build, or project-wide test suite was run.

## Task 6 final custody robustness fixes

### Status

Implemented and committed as `540684b` and `4d56d35` (`fix: harden custody recovery boundaries`).

### Corrective coverage

- Snapshot decoding now uses one shared `DecodeBudget` across every top-level and nested
  collection. Per-collection `MAX_COLLECTION_COUNT` remains enforced, while aggregate decoded
  entries and objects are bounded before collection allocation. Encoded snapshot bytes are also
  bounded up front; repeated Draft references remain valid because they are checked as references,
  not inserted into the active-copy uniqueness set.
- Startup now reports `matchFailure = DATABASE_ERROR` when `activeMatches()` is unavailable.
  Bound ESCROWED custody remains untouched during that outage, while custody enumeration and
  independent active-state processing retain their existing fail-safe behavior.
- Public `settle(UUID)` and `recover(UUID)` convert custody adapter runtime failures to typed
  `DATABASE_ERROR` results. When durable operation metadata is available, expected and unresolved
  UUIDs are preserved and nonterminal failures report `RETURN_PENDING`; raw adapter/SQLite
  exceptions do not cross the application boundary.
- PREPARED-to-ESCROWED still validates every current serialized item against its exact prepare
  snapshot before removing any slot; same-UUID metadata drift returns `INVENTORY_CHANGED` without
  mutation. SQLite row/state match ID and revision checks remain enforced in both findState and
  rehydrate paths.

### TDD and focused verification

Focused red command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result before the production changes:

```text
> Task :compileTestJava FAILED
MatchApplicationServiceTest.java:151: error: cannot find symbol
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.matchFailure());
                                                               ^
  symbol:   method matchFailure()
  location: variable result of type StartupRecoveryResult
MatchApplicationServiceTest.java:159: error: cannot find symbol
    MatchApplicationService.ReturnResult result = new MatchApplicationService(
                           ^
  symbol:   class ReturnResult
MatchApplicationServiceTest.java:173: error: cannot find symbol
    MatchApplicationService.RecoveryResult result = new MatchApplicationService(
                           ^
  symbol:   class RecoveryResult
3 errors
FAILURE: Build failed with an exception.
```

Focused green command (rerun without task cache):

```text
cmd /c gradlew.bat test --rerun-tasks --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 4 executed
```

The focused selection passed; Paper codec tests remain assumption-skipped because this environment
has no running Paper item registry. SQLite emitted only the JVM restricted-native-access warning.
No formatter, linter, project-wide build, or project-wide test suite was run.

### Final implementation adjustment

The failure conversion path now always reports nonterminal `RETURN_PENDING` when the adapter
throws, including when durable status metadata itself is terminal; this prevents an uncertain
database outcome from being mistaken for completed custody.

Final focused rerun after this adjustment:

```text
cmd /c gradlew.bat test --rerun-tasks --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 4 executed
```

## Task 6 terminal custody gap closure

### Status

Implemented and committed as `88c46e5` (`fix: close terminal custody gaps`).

### Corrective coverage

- `CardInventoryService.abort` now routes `PREPARED` operations through durable return before
  allowing `ABORTED`; snapshot restoration validates codec identity, exact copy UUID, and owner
  before insertion/replacement or `markRestored`.
- Startup recovery retains durable binding copy IDs on operation-status, match-binding, or recover
  failures. Ambiguous initial-save cleanup reports both reservation plans as pending recovery.
- `MatchApplicationService.apply` distinguishes thrown canonical lookup failures
  (`DATABASE_ERROR`) from an absent canonical state (`INVALID_STATE`).
- SQLite start bindings are immutable: exact retries are idempotent and mismatches reject without
  overwriting the original row.
- Non-replay transcript restoration requires a cursor equal to the decision count, while replay
  cursors remain bounded.
- `CardItemCodec` uses one authoritative catalog range (`1..475`) for both creation and reads.

### TDD and focused verification

Focused command used for red and green runs:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result after the regression tests were added:

```text
> Task :test FAILED
MatchApplicationServiceTest > startupRecoveryFailurePreservesDurableCopyIds() FAILED
MatchApplicationServiceTest > applyClassifiesCanonicalLoadFailureAsDatabaseError() FAILED
MatchApplicationServiceTest > rejectedInitialSaveLeavesCustodyWhenDurableWinnerReadIsAmbiguous() FAILED
RandomTranscriptTest > nonReplayRestoreRequiresConsumedDecisionCursor() FAILED
CardInventoryServiceTest > preparedAbortMustAttemptRestoreBeforeTerminalAbort() FAILED
CardItemCodecTest > createRejectsCatalogIdsOutsideAuthoritativeRange() FAILED
SqliteCustodyTest > startBindingIsIdempotentButRejectsIdentityOverwrite() FAILED
125 tests completed, 7 failed, 4 skipped
FAILURE: Build failed with an exception.
```

Exact final green result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The four Paper-registry codec tests remain assumption-skipped because no running Paper server is
available. SQLite emitted the JVM restricted-native-access warning only. Inventory custody
continues to enforce the Paper primary-thread boundary; no formatter, linter, project-wide build,
or project-wide test suite was run.
# Task 6 final authenticated custody fixes

## Status

Implemented and committed as `542aceb` (`fix: authenticate durable match state`) with authenticated
SQLite match snapshots, durable ABORTED restoration invariants, and safe empty-owner inference
while preserving prior custody/state behavior.

### Corrective coverage

- Schema migration 4 adds nullable `matches.state_digest`; legacy rows remain fail-closed rather
  than being silently trusted.
- Every SQLite `save` and `saveInitial` stores a versioned SHA-256 digest over complete state bytes.
  `findState` and both `rehydrate` paths verify the digest before decoding; missing or mismatched
  envelopes are rejected.
- In-memory and SQLite custody journals reject `ABORTED` unless every expected copy is present in
  the restored-copy ledger. `CardInventoryService.abort` already routes PREPARED, ESCROWED, and
  RETURN_PENDING through return/recovery before requesting terminal abort.
- Empty original-owner metadata is inferred only for a no-RANDOM/no-SWAP snapshot with an empty
  board. Placed or captured snapshots require exact owner metadata; plain initial fixtures remain
  compatible.

### TDD and focused verification

Focused red command after adding the three regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact red result before production changes:

```text
> Task :test FAILED
SqliteCustodyTest > custodyJournalsRejectAbortedUntilEveryCopyIsRestored() FAILED
SqliteCustodyTest > matchSnapshotRejectsMissingAndMismatchedIntegrity() FAILED
SqliteCustodyTest > snapshotRejectsEmptyOwnerInferenceAfterPlacement() FAILED
46 tests completed, 3 failed
FAILURE: Build failed with an exception.
```

Final focused command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact green result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused run emitted only SQLite's JVM restricted-native-access warning. No formatter, linter,
project-wide build, or project-wide test suite was run.


## Task 6 final authenticated transition hardening

### Status

Implemented and committed as `6d8000f` (`fix: authenticate match transitions`).
Match snapshots now use HMAC-SHA-256 over the complete serialized state bytes with an explicitly
configured stable server secret. Missing, short, rotated, wrong, and legacy unauthenticated keys
fail closed. Empty original-owner inference is restricted to true revision-zero initial state,
including no result, draws, gameplay transcript, command IDs, revealed metadata, Draft metadata,
or CHAOS metadata. SQLite migration markers must be exactly contiguous from 1 through the current
version and the required schema is validated before startup succeeds. V3/V4 DDL is idempotent,
and V5 records the idempotent start-binding table migration. ABORTED journal transitions validate
restoration and exact escrow snapshots before same-status idempotence; malformed legacy ABORTED
rows are rejected during hydration.

### TDD and focused verification

Focused red command after adding keyed-MAC, true-initial-owner, migration-gap/partial-DDL, and
ABORTED hydration regressions, before the production implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact red result:

```text
> Task :compileTestJava FAILED
SqliteCustodyTest.java:211: error: no suitable constructor found for
    SqliteMatchRepository(Database,String)
SqliteCustodyTest.java:216: error: no suitable constructor found for
    SqliteMatchRepository(Database,String)
SqliteCustodyTest.java:241: error: no suitable constructor found for
    SqliteMatchRepository(Database,String)
3 errors
FAILURE: Build failed with an exception.
```

Focused storage green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

Required focused adapter/domain/bot green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

The focused runs emitted SQLite's JVM restricted-native-access warning only. No formatter, linter,
project-wide build, or project-wide test suite was run.

### Corrective coverage

- `SqliteMatchRepository` has explicit secret constructors plus fail-closed process configuration.
  Saved envelopes are `hmac-sha256-v1:` values, and `findState`/both `rehydrate` paths reject
  missing, wrong-key, tampered, and old SHA-256 envelopes before decoding.
- Plain direct initial snapshots remain compatible, while post-placement and post-Sudden-Death
  empty-owner snapshots reject inference. Properly mapped non-SWAP Sudden-Death captures retain
  their original settlement owner.
- `SchemaMigrator` rejects gaps, out-of-range markers, and incomplete current schemas. Partial
  V3/V4 upgrades repair missing columns, V5 recreates its table when absent, and each migration
  marker is written only in the same transaction as its DDL.
- In-memory and SQLite journals reject unresolved ABORTED operations even when the target status is
  already ABORTED. Hydration requires every expected copy's restored ledger entry and exact escrow
  snapshot, preventing legacy rows from reporting false completion.

### Thread-boundary notes and concerns

Changes remain within persistence, snapshot rehydration, and pure custody journal boundaries.
No live Bukkit/Paper objects cross into application or storage. Paper registry codec tests remain
environment-skipped where no running Paper server is available, and SQLite emits the documented
restricted-native-access warning.
# Task 6 status, counterfeit, and seat-owner validation corrections

## Status

Implemented and committed as `2779316` (`fix: validate persisted status and seat owners`).
SQLite match-row authentication now covers the canonical envelope `(match_id, revision, status,
state_bytes, transcript_json)`, and verification runs before `find`, `activeMatches`, and either
rehydration path exposes persisted data. Decoded state status must agree with the persisted status,
and active-match enumeration validates every row before returning only unfinished matches.
Inventory scans now classify every invalid identity-bearing ItemStack as counterfeit regardless of
material. Match starts reject the same player UUID in both seats before pure start evaluation or
custody.

## TDD and focused verification

Focused red command, run after adding the regressions and before the production changes:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact red result:

```text
> Task :compileTestJava FAILED
CardInventoryServiceTest.java:123: error: cannot find symbol
    method isCounterfeit(ReadResult,boolean)
3 errors
FAILURE: Build failed with an exception.
```

The first post-implementation focused run exposed three expected test-fixture adjustments:
the pre-existing RANDOM wrong-owner regression used the same UUID in both seats, and row identity/
revision tests called `find` after mutating authenticated envelope fields. Those tests were updated
to use distinct wrong-owner data and to assert fail-closed `find` behavior.

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

Required focused adapter/domain/bot regression command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

The focused runs emitted only SQLite's JVM restricted-native-access warning. The four Paper codec
tests remain assumption-skipped because this environment has no running Paper registry. No
formatter, linter, project-wide build, or project-wide test suite was run.

## Corrective coverage

- Every SQLite match write computes the keyed MAC from all canonical row envelope fields, including
  revision, mutable status, state bytes, and transcript JSON; stale or modified rows fail before
  snapshot exposure.
- `findState` and both `rehydrate` paths decode only after integrity verification and reject a
  status that differs from `state.isFinished() ? "FINISHED" : "ACTIVE"`.
- `activeMatches` no longer trusts `WHERE status = 'ACTIVE'`; it authenticates and decodes all
  rows, rejects row/state status mismatches, and excludes decoded terminal matches.
- Invalid identity-bearing inventory items are counterfeit even when their material is not PAPER;
  ordinary non-card items with no card namespace identity remain ignored.
- Start owner validation returns structured `INVALID_OWNER` before `MatchEngine.start` or either
  custody preparation when both seats carry the same player UUID.

## Thread-boundary notes and concerns

Changes remain inside the existing application, inventory, and SQLite storage boundaries. The
counterfeit classifier is package-private and pure so its material-independent behavior is
focused-tested without requiring a live Paper registry. Existing Paper primary-thread custody
requirements, exact snapshots, and durable recovery behavior are unchanged.

## Task 6 final CAS/inventory boundary fixes

### Status

Implemented and committed as `2f43011` (`fix: authenticate CAS predecessor`).
`SqliteMatchRepository.save` now loads the complete predecessor row inside its write
transaction and authenticates the keyed MAC before revision comparison or update. Successor writes
also revalidate immutable catalog definitions. `CardItemCodec.hasIdentity` now detects configured
namespace keys regardless of the stored PDC primitive type. `CardInventoryService.prepare` now
rejects every list except exactly five unique copy IDs with `INVALID_COPY_SET`; the application
start path retains its explicit empty Draft-house bypass.

### TDD and focused verification

Focused red command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact red result:

```text
> Task :test FAILED

CardInventoryServiceTest > directPrepareRejectsAnythingOtherThanFiveUniqueCopies() FAILED
    org.opentest4j.AssertionFailedError at CardInventoryServiceTest.java:31

SqliteCustodyTest > successorCasRejectsTamperedAuthenticatedPredecessor() FAILED
    java.lang.IllegalArgumentException at SqliteCustodyTest.java:255

62 tests completed, 2 failed, 5 skipped
FAILURE: Build failed with an exception.
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

The green run selected 64 tests; the five Paper-registry codec tests were assumption-skipped
because this environment has no running Paper registry, and the remaining focused inventory,
storage, and application tests passed. SQLite emitted only its JVM restricted-native-access
warning. No formatter, linter, project-wide build, or project-wide suite was run.

### Corrective coverage

- Authenticated CAS regression tampers the stored predecessor MAC, verifies successor save is
  rejected as `INVALID_STATE`, and verifies revision and tampered MAC remain unchanged. A separate
  successor regression rejects altered definitions under an existing immutable catalog snapshot.
- Identity detection regression stores a configured catalog key using `PersistentDataType.BYTE`;
  `hasIdentity` recognizes it despite the unexpected primitive type. Ordinary items without any
  configured namespace key remain unclassified.
- Direct inventory custody regression rejects four-copy and duplicate-five-copy preparation lists
  before player lookup or journaling. The application-level `prepareForStart` empty-list branch
  remains the only Draft-house bypass.

### Implementation commit

`fix: authenticate CAS predecessor`

# Task 6 final custody completion and movement fixes

## Status

Implemented and committed as `2e907c6` (`fix: enforce custody completion`).

### Corrective coverage

- Cleanup now preserves every planned operation copy UUID as both pending and missing when
  `returnCopies` (including an uncertain enumeration/return path) throws after prepare or escrow
  may have accepted. Cleanup returns empty lists only when a successful durable
  `MISMATCHED_OPERATION` result has an empty expected set. The existing concurrent-winner
  no-cleanup path remains unchanged.
- `RETURNED` transitions in the in-memory and SQLite journals require the durable restored-copy
  ledger to contain every reserved UUID before checking same-status idempotence. Shared journal
  entry validation and SQLite hydration also reject malformed terminal rows.
- Inventory restoration now re-scans every reserved UUID, including UUIDs already recorded in the
  restored ledger, and requires exact serialized bytes and original owner. Missing or changed
  cards are reinserted/replaced from the validated escrow snapshot or remain `RETURN_PENDING` with
  durable recovery. Ambiguous duplicate/counterfeit scans keep all snapshots pending until a clean
  scan can verify them.
- `CardInventoryService.handlePlayerInteractEntity` is a primary-thread adapter handler that
  cancels interactions targeting `ItemFrame` (including item-frame subtypes) when either hand
  contains a reserved card. Ordinary entity interactions are not cancelled. No Paper listener
  existed in this module, so the adapter method remains exposed for Task 7 wiring.

## TDD and focused verification

Focused red command after adding the cleanup, terminal-transition, restoration identity, and
ItemFrame guard regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact red result before production changes:

```text
> Task :compileTestJava FAILED
CardInventoryServiceTest.java:161: error: cannot find symbol
    method exactItemMatchesSnapshot(ReadResult,CardCopyId,CopyOwner,byte[],byte[])
CardInventoryServiceTest.java:173: error: cannot find symbol
    method shouldCancelItemFrameInteraction(boolean,boolean,boolean)
8 errors
FAILURE: Build failed with an exception.
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

Required focused adapter/domain/bot regression command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

The focused runs emitted only the existing SQLite JVM restricted-native-access warning. Paper
codec tests that require a running Paper item registry remain assumption-skipped in this
environment. No formatter, linter, project-wide build, or project-wide test suite was run.

# Task 6 final startup/journal custody integrity fixes

## Status

Implemented and committed as `785e7a6` (`fix: harden startup custody recovery`).

### Corrective coverage

- `recoverStartup` now records a `MatchFailure(matchId, failure)` for every empty or throwing
  match rehydration. Active match IDs are collected before rehydration, so an ESCROWED operation
  bound to a match whose snapshot is malformed remains protected while other matches continue.
- Overdue timeout application failures are recorded with the match ID and typed custody failure;
  the unadvanced state is not returned in the successful recovered-match list. Accepted timeout
  successors remain the only exposed state.
- SQLite reservation hydration requires exact one-to-one escrow snapshots for every
  PREPARED, ESCROWED, RETURN_PENDING, RETURNED, and ABORTED row. Malformed rows throw the typed
  `CustodyIntegrityException`, retaining the operation UUID and expected copy UUIDs.
- SQLite and in-memory ABORTED transitions validate exact escrow snapshot coverage before status
  idempotence, including RETURNED-to-ABORTED and same-status requests. Shared journal entry
  validation also rejects duplicate or incomplete ABORTED snapshot coverage.

## TDD and focused verification

Focused red command, before the startup/journal implementation:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact red result:

```text
> Task :compileTestJava FAILED
MatchApplicationServiceTest.java:222: error: cannot find symbol
  symbol:   method assertThrows(...)
SqliteCustodyTest.java:113: error: cannot find symbol
  symbol:   class CustodyIntegrityException
3 errors
FAILURE: Build failed with an exception.
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 3 executed, 1 up-to-date
```

The focused run emitted only the existing SQLite JDBC restricted-native-access warning. No
formatter, linter, project-wide build, or project-wide test suite was run.

## Task 6 identity-protection correction

### Status

Implemented and committed as `63e1007` (`fix: preserve recovery identities`).

### Corrective coverage

- Active-bound custody is now fail-closed during startup. `PREPARED`, `ESCROWED`, and
  `RETURN_PENDING` operations stay untouched while their match binding is active; only a proven
  terminal status may be reconciled. Missing status and durable binding metadata failures produce
  `DATABASE_ERROR` recovery results with the operation's exact expected copy UUIDs. A durable
  operation binding is also used when the legacy match-ID lookup is unavailable, and active match
  IDs are collected before per-match rehydration.
- `CardCustodyJournal.Entry` and `InMemoryCardCustodyJournal.transition` require exact one-to-one
  escrow snapshot coverage for `RETURNED` before checking complete-restored idempotence. Existing
  exact-snapshot and complete-restored `ABORTED` invariants remain enforced.
- SQLite reservation hydration decodes authoritative copy UUIDs before owner/status/snapshot
  fields and wraps invalid owner, status, UUID, null, missing, duplicate, and unresolved
  reservation data in `CustodyIntegrityException`. The exception implements a package-neutral
  custody integrity contract carrying the operation UUID and all copy UUIDs parsed so far.
- SQLite open-operation enumeration now returns raw nonterminal/unknown-status identities without
  hydrating rows first; inventory hydration leaves malformed entries out of its cache so startup
  recovery can report their structured identity instead of treating them as absent.

### TDD and focused verification

Focused red command after adding active-status, unknown-status, structured-identity, exact-RETURNED,
and malformed-reservation regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest
```

Exact red result:

```text
> Task :test FAILED
MatchApplicationServiceTest > startupReportsUnknownActiveBoundStatusWithDurableCopyIds() FAILED
MatchApplicationServiceTest > inMemoryReturnedRequiresExactEscrowSnapshotsBeforeIdempotence() FAILED
MatchApplicationServiceTest > startupPreservesEveryActiveBoundNonterminalStatus() FAILED
MatchApplicationServiceTest > returnedEntryRequiresExactEscrowSnapshots() FAILED
MatchApplicationServiceTest > startupPropagatesStructuredReservationIdentityFailure() FAILED
99 tests completed, 5 failed
FAILURE: Build failed with an exception.
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

The final focused run selected application, SQLite custody, and inventory custody tests and
completed successfully. SQLite emitted the existing JVM restricted-native-access warning. No
formatter, linter, project-wide build, or project-wide test suite was run.

## Task 6 final malformed custody/movement fixes

### Status

Implemented and committed as `ac1bdb9` (`fix: fail closed malformed custody`).

### Corrective coverage

- `CardInventoryService.hydrate` now retains parsed operation/copy identities from structured
  custody integrity failures in a fail-closed reserved set. Movement guards continue protecting
  those copies until the journal proves `RETURNED` or `ABORTED`; typed recovery/cleanup failures
  remain unchanged.
- SQLite owner decoding now accepts `HOUSE` only with a null UUID and `PLAYER` only with a valid
  UUID. Reservation, recovery, card-copy preparation, and match-start binding readers reject
  malformed owner fields; reservation/binding failures retain operation and copy identity.
- Player death handling checks both mutable drops and the authoritative dying-player inventory,
  retaining inventory whenever either contains a reserved copy while ordinary deaths remain
  unchanged.
- `InMemoryCardCustodyJournal.prepare` now validates exact one-to-one copy/snapshot escrow parity
  before creating `PREPARED` state. Direct malformed-entry coverage uses explicit constructor
  validation or prepare rejection.

### TDD and focused verification

Focused red command for the new regressions:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.malformedHouseOwnerUuidIsRejectedWithReservationIdentity --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.inMemoryPrepareRejectsIncompleteOrMismatchedSnapshots --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest.malformedOpenCustodyRemainsReservedUntilTerminalProof
```

Exact red result:

```text
> Task :test FAILED
SqliteCustodyTest > malformedHouseOwnerUuidIsRejectedWithReservationIdentity() FAILED
SqliteCustodyTest > inMemoryPrepareRejectsIncompleteOrMismatchedSnapshots() FAILED
CardInventoryServiceTest > malformedOpenCustodyRemainsReservedUntilTerminalProof() FAILED
3 tests completed, 3 failed
FAILURE: Build failed with an exception.
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 2s
4 actionable tasks: 2 executed, 2 up-to-date
```

SQLite emitted the existing JVM restricted-native-access warning. No formatter, linter,
project-wide build, or project-wide test suite was run.

## Task 6 malformed reservation reuse hardening

### Status

Implemented the final malformed-custody corrections from `task-6-fix27-brief.md`.

### Corrective coverage

- SQLite startup enumeration now includes terminal reservation rows, allowing hydration and startup recovery to validate malformed terminal rows instead of filtering them out.
- Schema migration version 6 explicitly drops and recreates the active-copy trigger. A copy is reusable only for `RETURNED` or `ABORTED` rows with exact escrow snapshot coverage and complete restored-ledger coverage; unknown status and incomplete terminal proof remain blocked.
- In-memory custody rejects overlap with every active reservation and permits reuse only after a proven terminal transition.
- Card item reservation checks extract the copy UUID independently of full item validation and retain fail-closed protection for malformed identity-bearing items while reserved custody exists.

### TDD and focused verification

Focused red command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.malformedTerminalRowsAreEnumeratedForStartupValidation --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.unknownOrUnprovenTerminalCopyCannotBeReused --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.migrationRecreatesCopyGuardWithTerminalProof --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.inMemoryRejectsActiveCopyOverlapButAllowsProvenTerminalReuse --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest.malformedReservedIdentityCannotMove
```

Exact red result:

```text
SqliteCustodyTest > inMemoryRejectsActiveCopyOverlapButAllowsProvenTerminalReuse() FAILED
SqliteCustodyTest > migrationRecreatesCopyGuardWithTerminalProof() FAILED
SqliteCustodyTest > malformedTerminalRowsAreEnumeratedForStartupValidation() FAILED
SqliteCustodyTest > unknownOrUnprovenTerminalCopyCannotBeReused() FAILED
5 tests completed, 4 failed, 1 skipped
```

Focused green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.malformedTerminalRowsAreEnumeratedForStartupValidation --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.unknownOrUnprovenTerminalCopyCannotBeReused --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.migrationRecreatesCopyGuardWithTerminalProof --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest.inMemoryRejectsActiveCopyOverlapButAllowsProvenTerminalReuse --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest.malformedReservedIdentityCannotMove
```

BUILD SUCCESSFUL in 1s
```

The affected storage and inventory suites were also run together and passed:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest
> Task :test
BUILD SUCCESSFUL in 2s
```

The Paper item regression remains assumption-skipped because this environment has no running Paper
item registry. No formatter, linter, or project-wide test suite was run.

Required focused verification set (including application, domain, and bot suites):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
> Task :test
BUILD SUCCESSFUL in 5s
```

Implementation commit: `69625e4` (`fix: reject malformed reservation reuse`).
Prior report commit: `c482db5` (`docs: record malformed reservation reuse verification`).
