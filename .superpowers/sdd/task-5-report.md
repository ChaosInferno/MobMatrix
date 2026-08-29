# Task 5 Report — Deterministic Bot Move Policy

## Status

Complete. The pure `BotMovePolicy` API and `HeuristicBot` implementation are committed in:

- Commit: `b3c5c31` (`feat: add deterministic Triple Triad bot`)

The implementation has no Bukkit, Paper, Adventure, MapGui, JDBC, filesystem, or scheduler imports. Candidate moves are ordinary `MatchCommand.PlaceCard` commands and every candidate is simulated through `MatchEngine.apply`.

## TDD output

### RED

Command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Observed output before implementation:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE

> Task :compileTestJava FAILED
`C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\bot\HeuristicBotTest.java:36: error: cannot find symbol`
`  private final BotMovePolicy bot = new HeuristicBot();`
`                ^`
`  symbol:   class BotMovePolicy`
`  location: class HeuristicBotTest`
`C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\bot\HeuristicBotTest.java:36: error: cannot find symbol`
`  private final BotMovePolicy bot = new HeuristicBot();`
`                                        ^`
`  symbol:   class HeuristicBot`
`  location: class HeuristicBotTest`
`C:\Users\blaze\IdeaProjects\Aincraft-MobMatrix\src\test\java\org\aincraft\mobmatrix\bot\HeuristicBotTest.java:42: error: cannot find symbol`
`  BotMovePolicy.Decision decision = bot.choose(state, seat, new RandomTranscript(17L), START.plusSeconds(1));`
`  symbol:   class BotMovePolicy`
`... (the same missing-type error was emitted for the remaining 12 references at lines 43, 55, 59, 80, 94, 95, 111, 112, 134, and 136)`
`14 errors`

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':compileTestJava' ...
BUILD FAILED
```

### GREEN

Focused bot command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest
```

Observed output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 2 executed, 2 up-to-date
```

Focused bot plus listed domain command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

Observed output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 1 executed, 3 up-to-date
```

No formatter, linter, or project-wide suite was run.

## Rule-profile coverage

`HeuristicBotTest.completesMatchesUnderStandardAndAdvancedProfiles` repeatedly chooses and applies commands until completion for:

- Empty/standard rules
- `REVERSE`
- `SAME`
- `PLUS`
- `ASCENSION`

Additional focused tests cover legal command acceptance, unavailable copies and occupied squares, immediate capture preference, deterministic same-seed selection, unchanged predecessor state/transcript, typed no-legal-move exhaustion, and selection with masked opponent projection data.

## Concerns

- `MatchEngine.MatchTransition` exposes only generic `CARD_CAPTURED` events, so the bot invokes the existing pure `PlacementResolver` after accepted simulation to score capture sources; it does not duplicate or bypass resolution.
- A replay transcript missing or mismatching the bot tie-break decision now fails strictly through `RandomTranscript`; no lexical fallback is used.

## Task 5 review fix

Implementation commit: `d69b1b2` (`fix: enforce bot transcript replay`).

### RED

Command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

Observed output before the replay fix:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE

> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses

> Task :test FAILED

HeuristicBotTest > rejectsReplayWithMissingBotTieBreakDecision() FAILED
    org.opentest4j.AssertionFailedError at HeuristicBotTest.java:108

42 tests completed, 1 failed

FAILURE: Build failed with an exception.

* What went wrong:
> Task :test FAILED
> There were failing tests. See the report at: file:///C:/Users/blaze/IdeaProjects/Aincraft-MobMatrix/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights into a Build Scan by Develocity.

BUILD FAILED in 1s
4 actionable tasks: 2 executed, 2 up-to-date

### GREEN

Command:

```text
cmd /c gradlew.bat test --tests org.aincraft.mobmatrix.bot.HeuristicBotTest --tests org.aincraft.mobmatrix.domain.AdvancedRulesTest --tests org.aincraft.mobmatrix.domain.RandomTranscriptTest --tests org.aincraft.mobmatrix.domain.MatchEngineStandardTest --tests org.aincraft.mobmatrix.domain.MatchClockTest
```

Observed output after the replay fix:

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
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

The focused regression test uses `RandomTranscript.replay(List.of())` and verifies that the bot propagates the strict `IllegalStateException` instead of returning a command. No formatter, linter, or project-wide suite was run.
