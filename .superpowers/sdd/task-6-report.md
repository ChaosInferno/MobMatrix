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

## Task 6 final startup/retry corrections

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
## Task 6 durable identity and custody correction

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
