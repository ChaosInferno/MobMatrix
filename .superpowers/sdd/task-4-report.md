# Task 4 Report

Status: COMPLETE

Commit: `feat: implement Triple Triad advanced rules`

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
