package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RandomTranscriptTest {
  @Test
  void seededTranscriptRecordsAndReplaysDecisions() {
    RandomTranscript source = new RandomTranscript(17L);
    int first = source.nextInt("first-seat", 2);
    int second = source.nextInt("open-card", 5);
    assertEquals(List.of(new RandomTranscript.Decision("first-seat", 2, first),
        new RandomTranscript.Decision("open-card", 5, second)), source.decisions());
    RandomTranscript replay = RandomTranscript.replay(source.decisions());
    assertEquals(first, replay.nextInt("first-seat", 2));
    assertEquals(second, replay.nextInt("open-card", 5));
  }

  @Test
  void transcriptRejectsInvalidAndMismatchedReads() {
    RandomTranscript transcript = new RandomTranscript(1L);
    assertThrows(IllegalArgumentException.class, () -> transcript.nextInt("", 2));
    assertThrows(IllegalArgumentException.class, () -> transcript.nextInt("x", 0));
    RandomTranscript replay = RandomTranscript.replay(List.of(new RandomTranscript.Decision("x", 2, 1)));
    assertThrows(IllegalArgumentException.class, () -> replay.nextInt("y", 2));
    assertThrows(IllegalArgumentException.class, () -> replay.nextInt("x", 3));
    assertEquals(1, replay.nextInt("x", 2));
    assertThrows(IllegalStateException.class, () -> replay.nextInt("x", 2));
  }
 
  @Test
  void stateTransitionsForkTranscriptAndRejectedChaosDoesNotConsumeDecision() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("chaos"), MatchEngineStandardTest.deck(1),
        MatchEngineStandardTest.deck(6), MatchEngineStandardTest.fixtureCatalog(), new RuleSet(Rule.CHAOS), 21L,
        java.time.Instant.parse("2026-01-01T00:00:00Z"));
    MatchState before = MatchEngine.start(start).state();
    List<RandomTranscript.Decision> beforeDecisions = before.randomDecisions();
    PlayerSeat actor = before.currentSeat();
    CardCopyId playable = before.chaosChoice(actor);
    CardCopyId illegal = before.hand(actor).stream().filter(card -> !card.copyId().equals(playable))
        .findFirst().orElseThrow().copyId();
    MatchEngine.MatchTransition rejected = MatchEngine.apply(before,
        new MatchCommand.PlaceCard(actor, illegal, Square.CENTER, before.revision(), java.util.UUID.randomUUID(),
            before.turnStartedAt()));
    assertEquals(beforeDecisions, before.randomDecisions());
    MatchEngine.MatchTransition accepted = MatchEngine.apply(before,
        new MatchCommand.PlaceCard(actor, playable, Square.CENTER, before.revision(), java.util.UUID.randomUUID(),
            before.turnStartedAt()));
    assertEquals(beforeDecisions, before.randomDecisions());
    assertTrue(accepted.state().randomDecisions().size() >= beforeDecisions.size());
    assertEquals(before.randomDecisions(), rejected.state().randomDecisions());
  }

  @Test
  void timeoutSelectionsAreRecordedWithoutMutatingPredecessor() {
    MatchState before = MatchEngine.start(MatchEngineStandardTest.fixtureStart(31L)).state();
    List<RandomTranscript.Decision> beforeDecisions = before.randomDecisions();
    MatchEngine.MatchTransition timeout = MatchEngine.apply(before,
        new MatchCommand.Timeout(before.currentSeat(), before.revision(), java.util.UUID.randomUUID(),
            before.turnDeadline()));
    assertTrue(timeout.accepted());
    assertEquals(beforeDecisions, before.randomDecisions());
    assertTrue(timeout.state().randomDecisions().stream().anyMatch(d -> d.label().startsWith("timeout-card-")));
    assertTrue(timeout.state().randomDecisions().stream().anyMatch(d -> d.label().startsWith("timeout-square-")));
  }
 
  @Test
  void forkKeepsIndependentCursorAndRandomSequence() {
    RandomTranscript source = new RandomTranscript(41L);
    source.nextInt("first", 5);
    RandomTranscript fork = source.fork();
    source.record("timeout", 1, 0);
    fork.record("timeout", 1, 0);
    assertEquals(source.nextInt("after-timeout", 7), fork.nextInt("after-timeout", 7));
    assertEquals(3, source.decisions().size());
    assertEquals(3, fork.decisions().size());
  }
}
