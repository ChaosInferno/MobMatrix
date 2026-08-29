# Task 6 Report — Durable Physical Card Custody and Recovery

## Status

Implemented and committed durable match-bound custody operation identity, duplicate-safe restoration gating, PREPARED recovery cleanup, exact snapshot custody, movement guards, strict CAS, and prior Task 6 recovery requirements.

Implementation commits: `83cdb3b` (`fix: bind custody operations to matches`), plus prior Task 6 commits `a504b4c`, `6d1af52`, and `b307ad7`.

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

The selected codec test class contains 4 tests and all 4 were skipped by its explicit Paper-registry assumption (`Paper API item registry requires a running Paper server`). They were not executed proof. The selected storage tests (4), application tests (2), AdvancedRules (17), RandomTranscript, MatchEngineStandard, MatchClock, and HeuristicBot tests executed with zero failures or errors. SQLite emitted the environment's Java restricted-native-access warning; it did not fail the suite. No formatter, linter, project-wide build, or project-wide test suite was run.

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

Implementation commit: `83cdb3b` (`fix: bind custody operations to matches`)

The regression-focused red command was run after adding match-bound retry, terminal-operation retry, first-seat rejection cleanup, PREPARED recovery, duplicate-restoration gating, and SQLite match-binding tests:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.inventory.CardInventoryServiceTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact red result for the new duplicate-restoration regression:

```text
> Task :compileTestJava FAILED
CardInventoryServiceTest.java:11: error: cannot find symbol
  symbol:   method restorationMustRemainPending(boolean,boolean)
3 errors
FAILURE: Build failed with an exception.
```

Exact green result for that focused regression command:

```text
> Task :test
BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

The required focused adapter/domain/bot command was rerun without the extra CardInventoryServiceTest selector:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Exact result:

```text
> Task :test
BUILD SUCCESSFUL in 1s
4 actionable tasks: 1 executed, 3 up-to-date
```

The extra regression command selected 60 tests: 56 passed and 4 Paper-registry codec tests were assumption-skipped. The required command selected 59 tests: 55 passed and the same 4 codec tests were assumption-skipped. Current source counts are 6 storage tests, 7 application tests, and 1 CardInventoryService test. The CardInventoryService regression exercises the production duplicate/counterfeit ambiguity gate without constructing Paper items; the four codec tests remain skipped because this environment has no running Paper registry.

Durability corrections:

- Every match start now calls a match-bound custody preparation API. SQLite schema version 3 persists `reservations.match_id`; the SQLite and in-memory journals compare match identity together with owner and ordered copy IDs across retries/restarts. Legacy/unaware custody adapters reject match-bound preparation instead of silently reusing an operation.
- The process-local `MatchApplicationService.starts` cache was removed. Terminal `RETURNED` and `ABORTED` operations cannot be prepared again, so cleanup/settlement cannot be bypassed by stale cache entries.
- Restore inspects duplicate/counterfeit scan state before acknowledging any copy. Ambiguous scans queue exact snapshots as durable recovery and stay `RETURN_PENDING`; modified same-UUID items still require byte-for-byte replacement or remain pending.
- PREPARED startup recovery transitions to `RETURN_PENDING` and queues exact snapshots before attempting return. First-seat prepare rejection does not clean unknown operations or report phantom missing/recovery UUIDs; only accepted/known operations are cleaned on later failures.
- `SWAP_OFFHAND` and existing inventory movement protections remain primary-thread-bound. Paper's no-server `Bukkit.isPrimaryThread()` null failure is treated as unavailable only for test/runtime-less API use.

No formatter, linter, project-wide build, or project-wide test suite was run. SQLite emitted its Java restricted-native-access warning during the focused runs.
