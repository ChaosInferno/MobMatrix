# Task 7 Paper integration report

## Implemented

- `MobMatrixPlugin` validates and snapshots the packaged catalog (475 records: 460 `No.` and 15 `Ex.`), opens/migrates SQLite, wires durable catalog/custody/match repositories, runs startup recovery before command registration, and schedules deterministic bot turns.
- `/mobmatrix` exposes only `give`, `deck`, `challenge`, `bot`, `accept`, `decline`, and `cancel` with pure validation helpers for labels, positive bounded amounts, exact five-copy decks, and challenge target/state checks.
- Direct PvP reserves the exact selected physical UUIDs. Bot starts use a pure `BotStartMatchRequest`; bot copies are deterministic house-owned copies and are excluded from settlement.
- Inventory and lifecycle listeners delegate every existing custody guard and recover exact pending operations on join. Active state remains durable on disable.
- MapGui deck and match screens render projections and submit UI intents without owning match state or rule calculations.
- Descriptor declares Paper API `26.2` and the required MapGUI `BEFORE`/`true`/`true` dependency. Gradle expands the descriptor version during resource processing.
- Runtime resources package only the 475-card CSV. Catalog provenance and acceptable-use attribution remain repository-only in `catalog/source.txt`.

## Verification

- `cmd /c gradlew.bat clean check shadowJar --no-daemon` — passed; compilation, tests, Spotless, Checkstyle, PMD, SpotBugs, and shadow packaging completed successfully.
- The packaged `build/libs/MobMatrix-2026.08.29-SNAPSHOT.jar` contains a concrete descriptor version, required MapGUI dependency metadata, the runtime CSV, and no FFXIV source URLs or artwork payload.
- Gradle-managed Paper startup smoke passed with MapGUI 2.0.0 and MobMatrix `2026.08.29-SNAPSHOT`; `help mobmatrix` listed the registration and `mobmatrix` returned the command usage. The server then shut down cleanly.
- Actual player-client MapGui gameplay and exact-copy settlement were not exercised in this environment.

## Commit

`ec1c0d4 fix: keep catalog provenance out of plugin payload`
