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
 
  @Test
  void placementAtTurnDeadlineIsRejectedButDueTimeoutAdvancesTurn() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(14L)).state();
    PlayerSeat actor = state.currentSeat();
    Instant deadline = state.turnDeadline();
    CardCopyId copy = state.hand(actor).getFirst().copyId();

    MatchEngine.MatchTransition expiredPlacement = MatchEngine.apply(state,
        new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), deadline));

    assertEquals(MatchErrorCode.CLOCK_EXPIRED, expiredPlacement.rejection().code());
    MatchEngine.MatchTransition timeout = MatchEngine.apply(state,
        new MatchCommand.Timeout(actor, state.revision(), UUID.randomUUID(), deadline));
    assertTrue(timeout.accepted());
    assertEquals(1, timeout.state().revision());
  }

  @Test
  void projectionHidesLegalActionsAtTurnDeadline() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(15L)).state();

    MatchProjection projection = MatchProjection.forViewer(state, state.currentSeat(), state.turnDeadline());

    assertTrue(projection.legalCopyIds().isEmpty());
    assertTrue(projection.legalSquares().isEmpty());
  }

  @Test
  void stalePlacementAndTimeoutTimestampsCannotRewindNextTurnClock() {
    MatchState state = MatchEngine.start(MatchEngineStandardTest.fixtureStart(16L)).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();

    MatchEngine.MatchTransition stalePlacement = MatchEngine.apply(state,
        new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), START.minusSeconds(1)));
    MatchEngine.MatchTransition staleTimeout = MatchEngine.apply(state,
        new MatchCommand.Timeout(actor, state.revision(), UUID.randomUUID(), START.minusSeconds(1)));

    assertEquals(MatchErrorCode.INVALID_COMMAND, stalePlacement.rejection().code());
    assertEquals(MatchErrorCode.INVALID_COMMAND, staleTimeout.rejection().code());

    MatchEngine.MatchTransition placement = MatchEngine.apply(state,
        new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), START.plusSeconds(1)));
    MatchState next = placement.state();
    Instant nextTurnStartedAt = next.turnStartedAt();
    MatchEngine.MatchTransition staleNextTurn = MatchEngine.apply(next,
        new MatchCommand.Timeout(next.currentSeat(), next.revision(), UUID.randomUUID(), START));

    assertEquals(START.plusSeconds(1), nextTurnStartedAt);
    assertEquals(MatchErrorCode.INVALID_COMMAND, staleNextTurn.rejection().code());
    assertEquals(nextTurnStartedAt, staleNextTurn.state().turnStartedAt());
  }
}
