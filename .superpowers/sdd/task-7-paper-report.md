# Task 7 Paper integration report

## Implemented

- `MobMatrixPlugin` validates and snapshots the packaged catalog (475 records: 460 `No.` and 15 `Ex.`), opens/migrates SQLite, wires durable catalog/custody/match repositories, runs startup recovery before command registration, and schedules deterministic bot turns.
- `/mobmatrix` exposes only `give`, `deck`, `challenge`, `bot`, `accept`, `decline`, and `cancel` with pure validation helpers for labels, positive bounded amounts, exact five-copy decks, and challenge target/state checks.
- Direct PvP reserves the exact selected physical UUIDs. Bot starts use a pure `BotStartMatchRequest`; bot copies are deterministic house-owned copies and are excluded from settlement.
- Inventory and lifecycle listeners delegate every existing custody guard and recover exact pending operations on join. Active state remains durable on disable.
- Descriptor declares Paper API `26.2` and the required MapGUI `BEFORE`/`true`/`true` dependency.

## Focused verification

- `cmd /c gradlew.bat compileJava --no-daemon` — passed before the screen imports were added; final compile waits for the concurrently-produced public `DeckScreen`/`MatchScreen` source.
- `cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.paper.CommandValidationTest --no-daemon` — passed for the focused command/descriptor tests before screen source was required by the plugin imports.
- `cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest --no-daemon` — passed after the bot-start/house-ownership application changes.

## Commit

`aac54fd feat: wire Paper commands and lifecycle`

## Pending integration checkpoint

The Paper plugin imports the agreed public screen constructors:

- `DeckScreen(List<HandCard>, SelectionSink)`
- `MatchScreen(MatchView, PlayerSeat, IntentSink)`

Run the focused command/projection test command again after those screen classes appear, then amend this report with final green output.
