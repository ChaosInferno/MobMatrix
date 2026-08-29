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
...
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
