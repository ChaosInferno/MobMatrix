# MobMatrix Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable Paper plugin for direct two-player and bot Triple Triad matches with the current 475-card catalog, physical `PAPER` cards, MapGui UI, and all board-level rules in the approved design.

**Architecture:** One deployable plugin keeps pure Java domain and application packages independent from Paper, MapGui, inventory, and SQLite adapters. The first public slice is direct play only; deferred Battlehall, NPC roster, matchmaking, tournaments, regional rotation, rewards, and economy are not exposed.

**Tech Stack:** JDK toolchain 25; Paper API `io.papermc.paper:paper-api:26.2.build.+`; Paper API version `26.2`; Gradle wrapper 9.7.1; `xyz.jpenilla.run-paper` 3.1.0; MapGui API `io.github.flog99:mapgui-api:1.1.0`; Spotless 8.10.0 with google-java-format 1.36.1; Checkstyle 13.11.0; PMD 7.26.0; SpotBugs Gradle plugin 6.5.10 with SpotBugs engine 4.9.7; JUnit Jupiter; SQLite JDBC.

## Global Constraints

- Keep all `domain` and `application` classes free of Bukkit, Paper, Adventure, MapGui, JDBC, and filesystem imports.
- Use server-authoritative match state; MapGui is a projection/input adapter and never calculates captures.
- Use vanilla `Material.PAPER` physical cards with PDC schema version, catalog card ID, copy UUID, and integrity marker; never parse lore or titles for identity.
- Include exactly the current text-only catalog: public No. 1–460 and Ex. 1–15, 475 records; do not bundle FFXIV artwork, icons, URLs, or descriptive prose.
- Use the explicit approved rule policy in `docs/superpowers/specs/2026-08-29-mobmatrix-design.md`, including placement-time Ascension/Descension timing, Same/Plus/Combo ordering, and five-draw Sudden Death limit.
- Physical deck copies are selected by exact copy UUID, reserved before play, returned after settlement, and never consumed; bot/Draft cards are temporary house cards.
- Perform Bukkit/Paper object access and inventory mutation on the primary thread; perform SQLite/catalog/serialization I/O asynchronously and return immutable values to the primary thread.
- Declare MapGUI as a required server dependency with `load: BEFORE`, `required: true`, and `join-classpath: true`; compile against `mapgui-api:1.1.0` with `compileOnly` and do not shade it.
- Every implementation task follows TDD: write a behavior test, run it failing for the intended reason, implement the smallest behavior, run it passing, then refactor without changing behavior.
- Skip formatters, linters, and project-wide suites inside implementation subagents; run the canonical quality gate once at the end.

---

### Task 1: Establish Gradle and plugin quality foundation

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `.gitignore`
- Create: `config/checkstyle/checkstyle.xml`
- Create: `config/pmd/pmd.xml`
- Create: `config/spotbugs/exclude.xml`
- Create: `src/test/java/org/aincraft/mobmatrix/ProjectFoundationTest.java`

**Interfaces:**
- Produces one Java 25 Gradle project with `clean check`, `test`, `runServer`, Spotless, Checkstyle, PMD, and SpotBugs tasks.
- The package root is `org.aincraft.mobmatrix`.
- No plugin production class is added in this task; later tasks supply the entry point and descriptor.

- [ ] **Step 1: Write the failing foundation test**

Create `ProjectFoundationTest` with one deterministic assertion that the project test runtime uses Java 25 or newer:

```java
package org.aincraft.mobmatrix;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProjectFoundationTest {
  @Test
  void testRuntimeUsesRequiredJavaVersion() {
    assertTrue(Runtime.version().feature() >= 25);
  }
}
```

- [ ] **Step 2: Run the focused test and verify the project is not configured yet**

Run: `./gradlew test --tests org.aincraft.mobmatrix.ProjectFoundationTest`

Expected: the command fails because the Gradle project files do not yet exist.

- [ ] **Step 3: Add the build configuration**

Use a single `java` project with repositories Maven Central and Paper's Maven repository. Pin the version catalog values exactly as listed in the plan header. Configure:

```kotlin
group = "org.aincraft"
version = providers.gradleProperty("version").getOrElse("2026.08.29-SNAPSHOT")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.mapgui.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    implementation(libs.sqlite.jdbc)
}
```

Apply `xyz.jpenilla.run-paper` 3.1.0, configure `runServer` for Paper API `26.2`, and set Java compilation encoding to UTF-8 with `options.release = 25`. Apply the Shadow plugin `com.gradleup.shadow` 9.6.1 so the Xerial SQLite JDBC runtime is included in the deployable jar; keep `mapgui-api` compile-only and exclude it from `shadowJar`. Apply Spotless 8.10.0 with google-java-format 1.36.1, Checkstyle 13.11.0, PMD 7.26.0, and SpotBugs 6.5.10 with engine 4.9.7. Configure `check` to depend on all quality tasks and use JUnit Platform. Keep `mapgui-api` compile-only and do not shade it.

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `./gradlew test --tests org.aincraft.mobmatrix.ProjectFoundationTest`

Expected: `1 test completed, 1 passed`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradle.properties gradlew gradlew.bat .gitignore config src/test/java/org/aincraft/mobmatrix/ProjectFoundationTest.java
git commit -m "build: establish Paper plugin foundation"
```

---

### Task 2: Add catalog snapshot, card values, copies, and deck legality

**Files:**
- Create: `src/main/resources/catalog/cards.csv`
- Create: `src/main/resources/catalog/source.txt`
- Create: `tools/refresh-cards.js`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardId.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardCopyId.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardType.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/Side.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardDefinition.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardCatalog.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/CardCopy.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/Deck.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/DeckValidation.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/CardCatalogTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/CatalogFixtures.java`

**Interfaces:**
- `CardDefinition` exposes `CardId id()`, `String publicNumber()`, `String name()`, `int stars()`, `CardType type()`, `int value(Side side)`, and `int salePrice()`.
- `CardCatalog` exposes `CardDefinition require(CardId id)`, `Collection<CardDefinition> all()`, and `String snapshotId()`.
- `CardCopy` exposes `CardCopyId copyId()`, `CardId cardId()`, and `CopyOwner owner()`; it has no Paper dependency.
- `DeckValidation.validate(List<CardCopy>, CardCatalog)` returns a value object containing either valid five-copy deck data or specific errors; duplicate card definitions are allowed when distinct physical copy IDs are supplied.

- [ ] **Step 1: Write failing catalog/deck tests**

Test these behaviors before production classes exist:

```java
@Test
void currentSnapshotContainsAllPublicNumbers() {
    CardCatalog catalog = CatalogFixtures.currentSnapshot();
    assertEquals(475, catalog.all().size());
    assertTrue(catalog.all().stream().anyMatch(card -> card.publicNumber().equals("No. 460")));
    assertTrue(catalog.all().stream().anyMatch(card -> card.publicNumber().equals("Ex. 15")));
}

@Test
void deckAllowsDuplicateCopiesButEnforcesCurrentRarityLimits() {
    List<CardCopy> copies = List.of(copy("a", 1), copy("b", 1), copy("c", 2), copy("d", 3), copy("e", 4));
    assertTrue(DeckValidation.validate(copies, fixtureCatalog()).isValid());
    List<CardCopy> illegal = List.of(copy("a", 5), copy("b", 4), copy("c", 4), copy("d", 1), copy("e", 2));
    assertTrue(DeckValidation.validate(illegal, fixtureCatalog()).errors().contains(DeckError.TOO_MANY_FOUR_OR_FIVE_STAR));
}
```

Add boundary tests for zero cards, six cards, two five-stars, two four-stars plus a five-star, and a valid five-card deck of only one-to-three-star cards.

- [ ] **Step 2: Run the focused tests and verify expected missing-type failures**

Run: `./gradlew test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest`

Expected: compilation fails because the domain classes and fixture do not exist.

- [ ] **Step 3: Generate and validate the text-only catalog**

Implement `tools/refresh-cards.js` as an offline-reviewable importer for `https://ffxivcollect.com/api/triad/cards`. It must:

- reject non-2xx responses and malformed records;
- retain `id`, `number`, `name`, `stars`, `sell_price`, `stats.numeric`, and type name only;
- reject missing or duplicate internal IDs;
- require exactly public No. 1–460 and Ex. 1–15;
- reject non-integral side values outside 1–10 or stars outside 1–5;
- write deterministic UTF-8 CSV sorted by internal ID;
- write source URL, retrieval timestamp, upstream patches, and a SHA-256 digest to `source.txt`;
- never fetch or store image/icon/description fields.

Populate the initial CSV from the researched current endpoint and include the attribution/acceptable-use links in `source.txt`.

- [ ] **Step 4: Implement immutable card and deck domain values**

Use Java records where they preserve validation clarity. `CardDefinition` validates nonblank explicit public number/name, stars 1–5, side values 1–10, and non-null type. `CardCatalog` rejects duplicate IDs, duplicate public numbers, missing IDs, and exposes an unmodifiable collection. `DeckValidation` enforces exactly five distinct `CardCopyId`s, requires every definition to exist, allows duplicate catalog IDs when copy IDs differ, permits any number of 1–3-star cards, permits at most two cards of stars 4–5, permits at most one five-star, and permits at most one four-star when a five-star exists.

- [ ] **Step 5: Run the focused tests and verify they pass**

Run: `./gradlew test --tests org.aincraft.mobmatrix.domain.CardCatalogTest --tests org.aincraft.mobmatrix.domain.DeckValidationTest`

Expected: all catalog and deck tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/catalog tools/refresh-cards.js src/main/java/org/aincraft/mobmatrix/domain src/test/java/org/aincraft/mobmatrix/domain
git commit -m "feat: add current card catalog and deck validation"
```

---

### Task 3: Implement standard match state, commands, clocks, and scoring

**Files:**
- Create: `src/main/java/org/aincraft/mobmatrix/domain/PlayerSeat.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/Square.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/Board.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/HandCard.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchId.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchRules.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchState.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchCommand.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchResult.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchEngine.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/MatchProjection.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/MatchEngineStandardTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/MatchClockTest.java`

**Interfaces:**
- `MatchEngine.start(StartMatch)` returns `MatchTransition` with active state and first seat.
- `MatchEngine.apply(MatchState, MatchCommand)` returns `MatchTransition` containing new state, domain events, or structured rejection.
- `MatchCommand.PlaceCard` carries actor seat, copy ID, square, expected revision, and command ID.
- `MatchCommand.Timeout` carries seat, current deadline, expected revision, and command ID.
- `MatchProjection.forViewer(MatchState, PlayerSeat)` masks opponent hand cards unless rules expose them.

- [ ] **Step 1: Write failing standard-rule tests**

Cover random first seat, one card per turn, row-major board squares, occupied-square rejection, wrong-seat rejection, strict capture, equal-value no-capture, final ten-card score, and draw. Include a test that a played card leaves the hand and nine placements finish the board.

Example:

```java
@Test
void higherFacingSideCapturesOnlyOpposingAdjacentCard() {
    MatchState state = Fixtures.stateWithAdjacentCards(/* ... */);
    MatchTransition transition = engine.apply(state, Fixtures.place(PlayerSeat.ONE, "one-card", Square.CENTER));
    assertEquals(PlayerSeat.ONE, transition.state().board().ownerOf(Square.NORTH));
    assertEquals(PlayerSeat.TWO, transition.state().board().ownerOf(Square.SOUTH));
}
```

- [ ] **Step 2: Run focused tests and verify they fail for missing engine behavior**

Run: `./gradlew test --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest`

Expected: compilation or assertion failures because the engine behavior is absent.

- [ ] **Step 3: Implement immutable board/state and standard transition**

Implement validated row-major `Square` coordinates, immutable board occupancy/ownership, remaining hand cards, turn transitions, revision increments, expected-revision/idempotency rejection, and structured error codes. A normal placement evaluates only opposing adjacent cards and uses strict `>` comparison. Score each seat as board-controlled cards plus unplayed hand cards. Keep printed values in definitions and calculate no advanced modifier in this task.

- [ ] **Step 4: Implement clocks and timeout command**

Store per-seat remaining bank milliseconds, current grace deadline, and absolute turn deadline. A turn begins with five seconds of grace; grace never replenishes the bank. A timeout transition selects the deterministic first legal card/square from the match RNG transcript when the bank is exhausted or the grace deadline expires. A live bank deadline subtracts elapsed time once, never below zero.

- [ ] **Step 5: Implement viewer projections**

Return board state, local hand details, masked opponent hand slots, legal card IDs, legal squares, active rules, current seat, and clock values. All-open and three-open flags are represented in `MatchRules` but their random selection is implemented in Task 4.

- [ ] **Step 6: Run focused tests and verify they pass**

Run the same focused command. Expected: all standard engine and clock tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/aincraft/mobmatrix/domain src/test/java/org/aincraft/mobmatrix/domain
git commit -m "feat: add standard Triple Triad match engine"
```

---

### Task 4: Add every board-level advanced rule and deterministic RNG

**Files:**
- Create: `src/main/java/org/aincraft/mobmatrix/domain/Rule.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/RuleSet.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/RandomTranscript.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/PlacementResolver.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/RuleResolver.java`
- Create: `src/main/java/org/aincraft/mobmatrix/domain/DraftOfferings.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/AdvancedRulesTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/domain/RandomTranscriptTest.java`

**Interfaces:**
- `RuleSet` validates incompatible pairs and contains the approved rules only.
- `RandomTranscript` exposes deterministic `nextInt(String decision, int bound)` and an immutable list of recorded decisions.
- `PlacementResolver.resolve(MatchState, Placement)` returns captures, event order, effective values, and new ownership.

- [ ] **Step 1: Write failing advanced-rule vectors**

Add one observable test per rule and boundary tests for:

- All Open and Three Open masking/selection;
- Same with friendly matching cards and one opposing match;
- Plus with equal sums and friendly matching cards;
- Combo propagation without recursive Same/Plus;
- Reverse and Fallen Ace in both directions;
- Ascension/Descension existing-type timing and A/1 caps;
- Order and Chaos playable-card selection;
- Swap ownership restoration;
- Random physical-copy selection;
- Draft three five-card offerings and one selected card per offering;
- Roulette incompatible-pair rejection/resolution;
- Sudden Death restart ownership and fifth additional draw;
- replaying the same seed/transcript yields the same transition.

- [ ] **Step 2: Run focused advanced tests and verify intended failures**

Run: `./gradlew test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest`

Expected: failures for each unimplemented rule vector.

- [ ] **Step 3: Implement rule validation and random transcript**

Use an enum for every approved rule. Reject duplicate rules and incompatible pairs All Open/Three Open, Order/Chaos, Ascension/Descension. Roulette samples only compatible rules and records every choice. Never use `java.util.Random` directly from a resolver; all choices consume the transcript.

- [ ] **Step 4: Implement placement resolution**

Resolve each placement from an immutable adjacent snapshot. Apply existing same-type counts for placement-time Ascension/Descension, cap/floor effective values, evaluate Same and Plus simultaneously, union opposing special targets, apply ordinary or Reverse/Fallen-Ace captures, and BFS only from special captures for Combo. Capture events retain direction and source (`STANDARD`, `SAME`, `PLUS`, `COMBO`). Same/Plus equality and sum checks ignore Reverse.

- [ ] **Step 5: Implement setup rules and Sudden Death**

Resolve open-card selections, Order/Chaos next-card choice, Random deck replacement from available physical copies, Swap temporary ownership, Draft house offerings, and Sudden Death continuation in domain setup/settlement services. Store all choices in the state transcript.

- [ ] **Step 6: Run focused advanced tests and verify they pass**

Run the focused command again. Expected: all advanced vectors pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/aincraft/mobmatrix/domain src/test/java/org/aincraft/mobmatrix/domain
git commit -m "feat: implement Triple Triad advanced rules"
```

---

### Task 5: Implement the bot move policy

**Files:**
- Create: `src/main/java/org/aincraft/mobmatrix/bot/BotMovePolicy.java`
- Create: `src/main/java/org/aincraft/mobmatrix/bot/HeuristicBot.java`
- Create: `src/test/java/org/aincraft/mobmatrix/bot/HeuristicBotTest.java`

**Interfaces:**
- `BotMovePolicy.choose(MatchState, PlayerSeat, RandomTranscript)` returns an ordinary `MatchCommand.PlaceCard` or a typed no-legal-move result.
- The bot receives only the same projection that a player seat is allowed to see.

- [ ] **Step 1: Write failing bot tests**

Test that the bot returns a legal card/square, never chooses an occupied square or unavailable card, prefers an immediate capture over a non-capture when otherwise equal, and completes a full match under standard, Reverse, Same, Plus, and Ascension profiles.

- [ ] **Step 2: Run focused bot tests and verify they fail**

Run: `./gradlew test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest`

Expected: failures because no bot policy exists.

- [ ] **Step 3: Implement deterministic heuristic**

Enumerate legal pairs, simulate each with `MatchEngine`, score final control delta, immediate captures, corner occupancy, edge occupancy, and mobility, and choose the highest score. Use the match transcript only to break equal scores. The bot submits an ordinary domain command and cannot mutate state directly.

- [ ] **Step 4: Run focused bot tests and verify they pass**

Run the focused bot command. Expected: all bot tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/aincraft/mobmatrix/bot src/test/java/org/aincraft/mobmatrix/bot
git commit -m "feat: add deterministic Triple Triad bot"
```

---

### Task 6: Add Paper card items, SQLite custody, and recovery

**Files:**
- Create: `src/main/java/org/aincraft/mobmatrix/inventory/CardItemCodec.java`
- Create: `src/main/java/org/aincraft/mobmatrix/inventory/CardInventoryService.java`
- Create: `src/main/java/org/aincraft/mobmatrix/storage/Database.java`
- Create: `src/main/java/org/aincraft/mobmatrix/storage/SchemaMigrator.java`
- Create: `src/main/java/org/aincraft/mobmatrix/storage/SqliteCardRepository.java`
- Create: `src/main/java/org/aincraft/mobmatrix/storage/SqliteMatchRepository.java`
- Create: `src/main/java/org/aincraft/mobmatrix/application/CardCustodyPort.java`
- Create: `src/main/java/org/aincraft/mobmatrix/application/MatchRepository.java`
- Create: `src/main/java/org/aincraft/mobmatrix/application/MatchApplicationService.java`
- Create: `src/test/java/org/aincraft/mobmatrix/inventory/CardItemCodecTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/storage/SqliteCustodyTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/application/MatchApplicationServiceTest.java`

**Interfaces:**
- `CardItemCodec.create(CardDefinition, CardCopyId, CopyOwner)` creates a `PAPER`; `read(ItemStack)` returns a validated immutable `CardItemData` or a typed invalid-item result.
- `CardCustodyPort.prepare(owner, copy IDs, operation ID)`, `escrow(operation ID)`, `returnCopies(operation ID)`, and `recover(operation ID)` are idempotent.
- `MatchRepository.save(expectedRevision, MatchState)` atomically persists active state and reservation status.

- [ ] **Step 1: Write failing item/custody tests**

Test PDC round-trip, rejection of non-PAPER and missing/invalid PDC, unique copy UUID preservation, readable metadata generation, prepared-to-escrow transitions, duplicate reservation rejection, exact-copy return, and recovery after an interrupted operation.

- [ ] **Step 2: Run focused adapter tests and verify failures**

Run: `./gradlew test --tests org.aincraft.mobmatrix.inventory.CardItemCodecTest --tests org.aincraft.mobmatrix.storage.SqliteCustodyTest --tests org.aincraft.mobmatrix.application.MatchApplicationServiceTest`

Expected: failures because adapters and repositories do not exist.

- [ ] **Step 3: Implement PDC codec and synchronous inventory service**

Use plugin `NamespacedKey`s and a schema integer, catalog ID integer, canonical copy UUID string, and integrity marker string. Validate `Material.PAPER` before reading. Make copy UUID differences prevent stacking. Snapshot exact serialized item data before removal. Inventory click, drag, number-key, swap, drop, death, and quit paths are rejected/reconciled while a copy is reserved.

- [ ] **Step 4: Implement SQLite schema and repositories**

Create migrations for catalog snapshots, card copies, reservations, serialized escrow items, active match state, revisions, and recovery rows. Use transactions for database state transitions. Do not access Bukkit objects from JDBC tasks. Store enough match state and transcript data to reconstruct the domain aggregate after restart.

- [ ] **Step 5: Implement application reservation protocol**

Persist `PREPARED`; synchronously revalidate/remove exact inventory copies; persist `ESCROWED`; only then create active match. On failure, restore snapshots and mark operation aborted. Settlement persists `RETURN_PENDING`, restores exact items if possible, or records recovery for a later primary-thread return. Every operation ID is idempotent.

- [ ] **Step 6: Run focused adapter tests and verify they pass**

Run the focused command. Expected: all item, SQLite, and application custody tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/aincraft/mobmatrix/inventory src/main/java/org/aincraft/mobmatrix/storage src/main/java/org/aincraft/mobmatrix/application src/test/java/org/aincraft/mobmatrix/inventory src/test/java/org/aincraft/mobmatrix/storage src/test/java/org/aincraft/mobmatrix/application

git commit -m "feat: persist physical card custody"
```

---

### Task 7: Wire Paper commands, direct challenges, bot matches, and MapGui

**Files:**
- Create: `src/main/java/org/aincraft/mobmatrix/MobMatrixPlugin.java`
- Create: `src/main/java/org/aincraft/mobmatrix/paper/MobMatrixCommand.java`
- Create: `src/main/java/org/aincraft/mobmatrix/paper/InventoryProtectionListener.java`
- Create: `src/main/java/org/aincraft/mobmatrix/paper/PlayerLifecycleListener.java`
- Create: `src/main/java/org/aincraft/mobmatrix/mapgui/MatchScreen.java`
- Create: `src/main/java/org/aincraft/mobmatrix/mapgui/DeckScreen.java`
- Create: `src/main/resources/paper-plugin.yml`
- Create: `src/main/resources/config.yml`
- Create: `src/test/java/org/aincraft/mobmatrix/paper/CommandValidationTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/mapgui/MatchProjectionTest.java`

**Interfaces:**
- Commands: `/mobmatrix give <player> <public-number> [amount]`, `/mobmatrix deck`, `/mobmatrix challenge <player>`, `/mobmatrix bot`, `/mobmatrix accept`, `/mobmatrix decline`, `/mobmatrix cancel`.
- `MatchScreen` receives an application `MatchView` and submits only `PlaceCard`, `Timeout`, and `CloseView` intents.
- `paper-plugin.yml` main class is `org.aincraft.mobmatrix.MobMatrixPlugin`, API version `26.2`, and required MapGUI dependency is declared exactly as approved.

- [ ] **Step 1: Write failing command/projection tests**

Test public-number lookup for No. and Ex. labels, non-player command rejection, missing-target rejection, no-five-card deck rejection, challenge acceptance state, hidden opponent cards, and legal-cell/card projection.

- [ ] **Step 2: Run focused command/projection tests and verify failures**

Run: `./gradlew test --tests org.aincraft.mobmatrix.paper.CommandValidationTest --tests org.aincraft.mobmatrix.mapgui.MatchProjectionTest`

Expected: failures because the Paper/application wiring is absent.

- [ ] **Step 3: Implement plugin bootstrap and descriptor**

Initialize the catalog, SQLite schema, application service, custody service, bot policy, commands, lifecycle listeners, and MapGui registrations in `onEnable`. Run recovery for prepared/escrowed/return-pending rows before accepting commands. Close screens and stop scheduler tasks in `onDisable` without discarding active state. Do not enable a fallback UI when MapGUI is missing.

- [ ] **Step 4: Implement commands and direct challenge flow**

`give` creates distinct Paper copies on the primary thread. `deck` opens a MapGui deck screen that selects exact copy UUIDs. `challenge` creates a pending challenge; `accept` reserves both player decks and opens private projections; `bot` reserves the player's deck and creates a house bot seat. Apply commands asynchronously through the application service and schedule UI changes back on the primary thread.

- [ ] **Step 5: Implement MapGui screens**

Use a focused popup `Screen`. Render a 3×3 board, local cards, masked or revealed opponent slots, current turn, rule badges, timer values, event messages, and result. Card selection enables only legal empty cells. Use `Button` or one `Draw` grid with explicit click coordinate mapping. Call `invalidate()`/state updates only when a committed projection changes. Screen close sends no settlement command.

- [ ] **Step 6: Implement lifecycle and inventory protections**

On quit, persist/reload active state and apply the approved timeout/disconnect policy. On death, drop, drag, number-key, swap, and ordinary inventory click, prevent movement of reserved copy UUIDs. On return, restore exact items or durable recovery rows. Never call blocking JDBC from an event handler.

- [ ] **Step 7: Run focused tests and compile the plugin**

Run: `./gradlew test --tests org.aincraft.mobmatrix.paper.CommandValidationTest --tests org.aincraft.mobmatrix.mapgui.MatchProjectionTest`

Expected: all focused tests pass. Do not run the full quality gate in the task subagent.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/aincraft/mobmatrix src/main/resources/paper-plugin.yml src/main/resources/config.yml src/test/java/org/aincraft/mobmatrix/paper src/test/java/org/aincraft/mobmatrix/mapgui
git commit -m "feat: wire direct Paper and MapGui matches"
```

---

### Task 8: Verify the playable slice and finish repository hygiene

**Files:**
- Create: `src/test/java/org/aincraft/mobmatrix/integration/BotMatchIntegrationTest.java`
- Create: `src/test/java/org/aincraft/mobmatrix/integration/RecoveryIntegrationTest.java`
- Modify: `src/main/resources/catalog/source.txt` only if the generated digest or retrieval metadata changed during final import.
- Modify: `docs/superpowers/specs/2026-08-29-mobmatrix-design.md` only if executable verification proves an explicit policy wording mismatch.

**Interfaces:**
- Integration tests exercise the real domain/application/storage stack without mocking capture rules.
- The Paper smoke scenario uses the real `runServer` task and MapGui dependency.

- [ ] **Step 1: Write failing integration tests**

Test a full bot match reaches a result and returns the exact five reserved player copy UUIDs. Test a prepared reservation survives repository reload and is reconciled once. Test the 475-row catalog loads from the packaged resource.

- [ ] **Step 2: Run focused integration tests and verify the expected red state**

Run: `./gradlew test --tests org.aincraft.mobmatrix.integration.BotMatchIntegrationTest --tests org.aincraft.mobmatrix.integration.RecoveryIntegrationTest`

Expected: failures until the complete application wiring is present.

- [ ] **Step 3: Implement only the missing integration glue**

Use real SQLite repositories and domain transitions. Do not weaken assertions, replace real rules with mocks, or add deferred mode commands to make tests pass.

- [ ] **Step 4: Run focused integration tests and verify green**

Run the focused integration command. Expected: all integration tests pass.

- [ ] **Step 5: Run the canonical quality gate**

Run: `./gradlew clean check`

Expected: successful exit with zero test failures, Checkstyle violations, PMD violations, SpotBugs findings, and Spotless differences.

- [ ] **Step 6: Run the actual Paper smoke path**

Run: `./gradlew runServer` using the configured Paper 26.2 server. In the server console, issue cards to a test player, start `/mobmatrix bot`, place cards through the MapGui popup, allow the match to settle, and inspect the player's inventory for the same five copy UUIDs. Stop the server cleanly after observing the result and verify recovery rows are empty for the completed match.

- [ ] **Step 7: Commit final hygiene changes**

```bash
git add .
git commit -m "test: verify playable core match"
```

- [ ] **Step 8: Record the staged boundary**

Leave deferred Battlehall/NPC/tournament commands absent. The final report must identify the exact smoke path and quality-gate output; it must not claim the deferred FFXIV modes are implemented.
