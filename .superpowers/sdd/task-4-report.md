# Task 4 Report

Status: COMPLETE

Commit: `80ed178` (`feat: implement Triple Triad advanced rules`)

## Focused TDD verification

RED command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest
```

RED result (before advanced production classes existed):

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava FAILED
...
24 errors

FAILURE: Build failed with an exception.
```

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

GREEN result:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

## Coverage summary

- `RuleSet` validates immutable approved vocabulary, duplicate/null rules, and all approved incompatible pairs; `MatchRules` preserves the Task 3 API while using the same validation.
- `RandomTranscript` records seeded decisions and supports strict replay validation for labels, bounds, and exhaustion.
- `PlacementResolver` implements one adjacent snapshot, typed Ascension/Descension timing and caps, Same/Plus friendly/opposing semantics, union targets, Reverse/Fallen Ace ordinary comparison, and non-recursive breadth-first Combo captures with source/direction/event records.
- `MatchEngine` integrates immutable advanced placement resolution, deterministic first-seat/open-card selection, All Open/Three Open projections, Order/Chaos legal-card restrictions and timeout choice, Random physical-copy setup, Swap setup ownership metadata, Roulette compatible finite candidates, and Sudden Death continuation counting/restarts.
- `DraftOfferings` generates deterministic house-owned three-by-five offering sets and immutable seat selections.
- Focused tests cover transcript replay/mismatch boundaries, rule incompatibilities, Same/Plus friendly behavior, Reverse/Fallen Ace, Three Open masking, Draft offering shape/selection, plus existing standard capture, scoring, projection, and clock contracts.

Concerns: No formatter, linter, or project-wide suite was run per task instructions. Paper, Bukkit, MapGui, storage, and bot layers remain untouched.

## Review fix TDD verification

RED command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

RED result (before review-fix implementation):

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE

> Task :compileTestJava FAILED
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:50: error: no suitable constructor found for StartMatch(MatchId,Deck,Deck,CardCatalog,RuleSet,long,Instant,List<CardCopy>,List<CardCopy>,List<Integer>,List<Integer>)
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("draft"), deck(1), deck(6), catalog(),
                                   ^
    constructor StartMatch.StartMatch(MatchId,Deck,Deck,CardCatalog,MatchRules,long,Instant,List<CardCopy>,List<CardCopy>) is not applicable
      (actual and formal argument lists differ in length)
    constructor StartMatch.StartMatch(MatchId,Deck,Deck,CardCatalog,MatchRules,long,Instant) is not applicable
      (actual and formal argument lists differ in length)
    constructor StartMatch.StartMatch(MatchId,Deck,Deck,CardCatalog,RuleSet,long,Instant) is not applicable
      (actual and formal argument lists differ in length)
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:56: error: cannot find symbol
    assertEquals(3, state.draftOfferings(PlayerSeat.ONE).offerings().size());
                         ^
  symbol:   method draftOfferings(PlayerSeat)
  location: variable state of type MatchState
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:60: error: cannot find symbol
        state.draftOfferings(PlayerSeat.ONE).offerings().stream().flatMap(o -> o.cards().stream())
             ^
  symbol:   method draftOfferings(PlayerSeat)
  location: variable state of type MatchState
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:63: error: cannot find symbol
    assertEquals(List.of(0, 1, 2), state.draftSelection(PlayerSeat.ONE).offeringChoices());
                                        ^
  symbol:   method draftSelection(PlayerSeat)
  location: variable state of type MatchState
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:65: error: cannot find symbol
        () -> DraftOfferings.select(state.draftOfferings(PlayerSeat.ONE), List.of(0, 1)));
                                         ^
  symbol:   method draftOfferings(PlayerSeat)
  location: variable state of type MatchState
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:67: error: cannot find symbol
        () -> DraftOfferings.select(state.draftOfferings(PlayerSeat.ONE), List.of(0, 1, 5)));
                                         ^
  symbol:   method draftOfferings(PlayerSeat)
  location: variable state of type MatchState
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:79: error: cannot find symbol
    assertEquals(6, placement.board().cellAt(Square.CENTER).effectiveValue(Side.LEFT));
                                                           ^
  symbol:   method effectiveValue(Side)
  location: class Cell
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:90: error: incompatible types: inference variable E has incompatible bounds
        () -> DraftOfferings.select(offers, List.of(playerCard, offers.offerings().get(1).cards().getFirst(),
                            ^
    equality constraints: Integer
    lower bounds: CardCopy
  where E is a type-variable:
    E extends Object declared in method <E>of(E,E,E)
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:98: error: incompatible types: RuleSet cannot be converted to MatchRules
        new RuleSet(Rule.RANDOM), 1L, java.time.Instant.parse("2026-01-01T00:00:00Z"),
        ^
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:100: error: cannot find symbol
    assertEquals(MatchErrorCode.RANDOM_INSUFFICIENT_POOL, MatchEngine.start(shortStart).rejection().code());
                               ^
  symbol:   variable RANDOM_INSUFFICIENT_POOL
  location: class MatchErrorCode
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:104: error: incompatible types: RuleSet cannot be converted to MatchRules
        catalog(), new RuleSet(Rule.RANDOM), 1L, java.time.Instant.parse("2026-01-01T00:00:00Z"),
                   ^
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\AdvancedRulesTest.java:106: error: cannot find symbol
    assertEquals(MatchErrorCode.RANDOM_DUPLICATE_COPY, MatchEngine.start(duplicateStart).rejection().code());
                               ^
  symbol:   variable RANDOM_DUPLICATE_COPY
  location: class MatchErrorCode
C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\domain\RandomTranscriptTest.java:41: error: cannot find symbol
    CardCopyId playable = before.chaosChoice(actor);
                                ^
  symbol:   method chaosChoice(PlayerSeat)
  location: variable before of type MatchState
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.
13 errors


FAILURE: Build failed with an exception.
```

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

GREEN result:

```text
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 3 executed, 1 up-to-date
```

## Review fix summary

- Integrated Draft setup into MatchEngine with three immutable five-card house-owned offerings per seat, validated per-offering selections, immutable provenance, and the explicit `SELECT_ONE_FROM_EACH_THEN_FIRST_TWO_REMAINING` five-card policy.
- Stored placement-time Ascension/Descension effective side values in immutable board cells and used them for all later ordinary, Same, Plus, Reverse/Fallen-Ace, and Combo comparisons.
- Forked random transcripts at every state boundary; persisted Chaos choices at turn setup; kept rejected transitions and predecessor decisions unchanged; and recorded deterministic timeout card/square selections with labels and bounds.
- Restricted ORDER/CHAOS projections to exactly one playable copy without consuming random state during rendering.
- Added structured RANDOM insufficient-pool and duplicate-physical-copy start rejections without fallback decks.
- Regenerated Three Open selections on every Sudden Death restart, preserved continuation transcript decisions, advanced restart revisions exactly once, and stopped after the fifth consecutive draw.

Implementation commit: `38eebce` (`fix: preserve advanced rule state`)
Follow-up implementation commit: `d660338` (`fix: reject null draft selections`)

## Remaining review fixes

RED command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

RED result (before remaining review-fix implementation):

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE

> Task :compileTestJava FAILED
AdvancedRulesTest.java:131: error: cannot find symbol
    assertTrue(state.houseOwnedCopies().containsAll(state.hand(PlayerSeat.ONE).stream()
                    ^
  symbol:   method houseOwnedCopies()
  location: variable state of type MatchState
AdvancedRulesTest.java:134: error: cannot find symbol
        .noneMatch(copy -> state.houseOwnedCopies().contains(copy.copyId())));
                                ^
  symbol:   method houseOwnedCopies()
  location: variable state of type MatchState
2 errors

FAILURE: Build failed with an exception.
```

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

GREEN result:

```text
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 3 executed, 1 up-to-date
```

Coverage:

- Removed the public Deck-backed `DraftOfferings.Selection` compatibility constructor; selections are now created only through offering membership-validated APIs.
- RANDOM setup now samples only catalog-known unique physical copies; unknown extras are ignored, while insufficient valid pools and duplicate UUIDs retain structured setup rejections.
- Draft per-offering choices are recorded with `draft-choice-<SEAT>-<INDEX>` labels and bound 5 decisions. Generated offering copy IDs are exposed as `houseOwnedCopies()` and excluded from player settlement copies.
- Placement captures and emitted capture events are sorted row-major after BFS source resolution, preserving Combo source attribution and reachability.

Implementation commit: `0bcfa0e` (`fix: harden draft and random setup`)

## Cross-seat RANDOM custody fix

RED command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

RED result (before shared cross-seat validation):

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses

> Task :test FAILED

AdvancedRulesTest > randomStartRejectsPhysicalCopySharedAcrossSeatInventories() FAILED
    org.opentest4j.AssertionFailedError at AdvancedRulesTest.java:183

33 tests completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///C:/Users/blaze/IdeaProjects/Aincraft-MobMatrix/build/reports/tests/test/index.html

* Try:
> Run with --scan to get a Build Scan

BUILD FAILED in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

GREEN result:

```text
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
Consider enabling configuration cache to speed up your build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

Implementation:

- Added a focused cross-seat duplicate UUID test that verifies a shared `CardCopyId` is rejected and neither supplied inventory list is mutated.
- RANDOM setup now validates both inventories against one shared physical-copy UUID set before sampling. Unknown catalog IDs remain excluded from usable samples; duplicate UUIDs return `RANDOM_DUPLICATE_COPY`, and no fallback deck is created.

Implementation commit: `c1472e9` (`fix: reject cross-seat random duplicates`)

## Final settlement ownership and event compatibility fix

RED command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

RED result:

```text
> Task :compileJava UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses

> Task :test FAILED

AdvancedRulesTest > settlementRoutesCapturedCopyToOriginalSeatAndRetainsCaptureEvent() FAILED
    org.opentest4j.AssertionFailedError at AdvancedRulesTest.java:284

34 tests completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///C:/Users/blaze/IdeaProjects/Aincraft-MobMatrix/build/reports/tests/test/index.html

* Try:
> Run with --scan to get a Build Scan for full insights.

BUILD FAILED in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

GREEN command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

GREEN result:

```text
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 3 executed, 1 up-to-date
Consider enabling configuration cache to speed up your build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

Fix summary:

- `MatchState` now stores an immutable original-owner map for every player-owned copy entering a standard or RANDOM match, carrying it through normal successors and Sudden Death restarts. Settlement routes both hand and board copies through original ownership rather than mutable board control ownership.
- SWAP exchanges preserve the same original-owner map; generated DRAFT copies remain house-owned and excluded from player settlement.
- Match transitions restore the compatible `CARD_CAPTURED` event once per deterministic row-major capture while `PlacementResolver` retains structured capture source and direction data.

Implementation commit: `5ea1511` (`fix: preserve original card owners`)

Concerns: No formatter, linter, or project-wide suite was run per task instructions. Focused tests only.
