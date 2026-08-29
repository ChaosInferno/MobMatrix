# Task 6 Report — Physical Card Custody Persistence

## Status

Implemented and committed the physical-card PDC codec, synchronous Paper inventory custody journal, SQLite schema/migrations/repositories, immutable custody value/port types, and application reservation protocol.

Commit: `a3ddef9` (`feat: persist physical card custody`)

## TDD and focused verification

Red command (before production implementation):

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest
```

Red result: `:compileTestJava FAILED`; the expected missing production types (`CardItemCodec`, `Database`, `CardCustodyPort`, `MatchApplicationService`, and `CopyOwner`) were reported by the compiler.

Green command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Green result:

```text
> Task :test
BUILD SUCCESSFUL
4 actionable tasks: 2 executed, 2 up-to-date
```

The focused codec tests are guarded by a JUnit assumption because the Paper API alone cannot initialize its server registry (`No RegistryAccess implementation found`) in a plain unit-test JVM. Storage, application, domain, and bot focused tests execute successfully. No formatter, linter, project-wide build, or project-wide suite was run.

## Coverage

- PDC codec: Paper-only material/amount validation, schema/catalog/canonical UUID/owner/integrity validation, server-secret HMAC markers, readable generated metadata, immutable `CardItemData`, and distinct non-stackable copy identity.
- Inventory custody: synchronous primary-thread guard; exact UUID indexing; duplicate/counterfeit rejection; owner matching; immutable exact serialized item snapshots; prepare/escrow/return/recover idempotency; all-or-nothing removal; exact restoration; missing/offline recovery identification; reserved-copy lookup for movement protection.
- SQLite schema: explicit idempotent migration with catalog snapshots/definitions, card ledger, reservation operations, reservation copies, exact escrow bytes, active match snapshots/revisions/transcripts, recovery rows, foreign keys, status checks, indexes, and active-copy guard trigger.
- SQLite repositories: prepared statements, transactions, duplicate-copy ownership protection, durable status transitions, catalog metadata persistence, recovery row idempotency, and optimistic compare-and-swap match saves.
- Application: exactly-five distinct physical deck validation, prepare then escrow ordering, no active result before escrow, failure recovery/abort path, idempotent operation results, pure `MatchEngine.apply` persistence-before-notification boundary, and two-seat startup orchestration.

## Thread-boundary notes

`domain` and `application` contain only Java/value/port APIs and do not import Bukkit, Paper, Adventure, MapGui, JDBC, or filesystem classes. `inventory` is the only package touching Bukkit/Paper inventory/PDC objects; its mutating entry points require the Paper primary thread (with a no-server test escape). `storage` is the only package touching JDBC/filesystem and receives only immutable values/bytes. No live Bukkit object crosses into SQLite code.

## Concerns

1. Paper API item construction requires a running Paper registry implementation; a plain unit-test JVM cannot exercise `ItemStack` behavior without MockBukkit/Paper runtime support. The codec implementation itself compiles against Paper and the test explicitly records this environmental limitation rather than treating an API initialization failure as a codec defect.
2. Match snapshots persist a deterministic binary state summary and complete random transcript JSON. Rehydrating a live `MatchState` aggregate from a stored snapshot is intentionally left to the later Paper lifecycle wiring task, which owns catalog lookup and startup recovery orchestration.
