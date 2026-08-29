package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MatchClockTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void gracePeriodDoesNotConsumeBankAndDoesNotCarryToNextTurn() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(11L)).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();
    MatchEngine.MatchTransition transition = MatchEngine.apply(state,
        new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), START.plusSeconds(4)));

    assertTrue(transition.accepted());
    assertEquals(90_000L, transition.state().remainingBankMillis(actor));
    assertEquals(5_000L, transition.state().remainingGraceMillis(transition.state().currentSeat(), START.plusSeconds(4)));
  }

  @Test
  void elapsedTimeAfterGraceReducesOnlyActivePlayersBank() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(12L)).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();
    MatchEngine.MatchTransition transition = MatchEngine.apply(state,
        new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), START.plusSeconds(15)));

    assertTrue(transition.accepted());
    assertEquals(80_000L, transition.state().remainingBankMillis(actor));
    assertEquals(90_000L, transition.state().remainingBankMillis(actor.opponent()));
  }

  @Test
  void exhaustedClockTimeoutMakesFirstDeterministicLegalMove() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(13L)).state();
    PlayerSeat actor = state.currentSeat();
    MatchEngine.MatchTransition transition = MatchEngine.apply(state,
        new MatchCommand.Timeout(actor, state.revision(), UUID.randomUUID(), START.plusSeconds(95)));

    assertTrue(transition.accepted());
    assertEquals(1, transition.state().revision());
    assertTrue(transition.state().board().occupiedSquares().contains(Square.of(0)));
    assertEquals(actor.opponent(), transition.state().currentSeat());
    assertEquals(0L, transition.state().remainingBankMillis(actor));
  }
}
