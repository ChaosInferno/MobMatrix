package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MatchEngineStandardTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void startCreatesRowMajorBoardHandsAndDeterministicFirstSeat() {
    MatchEngine.MatchTransition transition = MatchEngine.start(fixtureStart(17L));
    MatchState state = transition.state();

    assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8),
        state.board().emptySquares().stream().map(Square::index).toList());
    assertEquals(5, state.hand(PlayerSeat.ONE).size());
    assertEquals(5, state.hand(PlayerSeat.TWO).size());
    assertEquals(0, state.revision());
    assertNotNull(state.currentSeat());
    assertEquals(MatchEngine.start(fixtureStart(17L)).state().currentSeat(), state.currentSeat());
  }

  @Test
  void placementRemovesCopyAdvancesTurnAndRevision() {
    MatchState state = MatchEngine.start(fixtureStart(1L)).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();

    MatchEngine.MatchTransition transition = MatchEngine.apply(
        state, new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), START.plusSeconds(1)));

    assertTrue(transition.accepted());
    assertEquals(1, transition.state().revision());
    assertEquals(4, transition.state().hand(actor).size());
    assertEquals(actor.opponent(), transition.state().currentSeat());
    assertTrue(state.board().isEmpty(Square.CENTER));
  }

  @Test
  void standardCaptureUsesStrictFacingComparisonAndNeverFlipsFriendlyCards() {
    MatchState state = MatchEngine.start(fixtureStart(2L)).state();
    PlayerSeat actor = state.currentSeat();
    PlayerSeat opponent = actor.opponent();
    CardCopyId first = state.hand(actor).get(0).copyId();
    CardCopyId second = state.hand(opponent).get(0).copyId();

    MatchEngine.MatchTransition firstMove = MatchEngine.apply(state,
        place(actor, first, Square.WEST, state.revision(), START.plusSeconds(1)));
    MatchState afterFirst = firstMove.state();
    MatchEngine.MatchTransition secondMove = MatchEngine.apply(afterFirst,
        place(opponent, second, Square.CENTER, afterFirst.revision(), START.plusSeconds(2)));
    MatchState afterSecond = secondMove.state();
    CardCopyId third = afterSecond.hand(actor).getFirst().copyId();
    MatchEngine.MatchTransition capture = MatchEngine.apply(afterSecond,
        place(actor, third, Square.NORTH, afterSecond.revision(), START.plusSeconds(3)));

    assertTrue(capture.accepted());
    assertEquals(actor, capture.state().board().ownerOf(Square.CENTER));

    MatchState friendly = capture.state().withCurrentSeat(actor);
    CardCopyId fourth = friendly.hand(actor).getFirst().copyId();
    MatchEngine.MatchTransition friendlyMove = MatchEngine.apply(friendly,
        place(actor, fourth, Square.SOUTH, friendly.revision(), START.plusSeconds(4)));
    assertTrue(friendlyMove.accepted());
    assertEquals(actor, friendlyMove.state().board().ownerOf(Square.CENTER));
  }

  @Test
  void rejectsInvalidPlacementAndDuplicateCommand() {
    MatchState state = MatchEngine.start(fixtureStart(3L)).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();
    UUID commandId = UUID.randomUUID();
    MatchCommand command = new MatchCommand.PlaceCard(actor, copy, Square.CENTER, state.revision(), commandId, START);

    MatchEngine.MatchTransition accepted = MatchEngine.apply(state, command);
    assertTrue(accepted.accepted());
    assertEquals(MatchErrorCode.DUPLICATE_COMMAND_ID,
        MatchEngine.apply(accepted.state(), command).rejection().code());
    assertEquals(MatchErrorCode.OCCUPIED_SQUARE,
        MatchEngine.apply(accepted.state(), place(accepted.state().currentSeat(), accepted.state().hand(accepted.state().currentSeat()).getFirst().copyId(), Square.CENTER, accepted.state().revision(), START)).rejection().code());
    assertEquals(MatchErrorCode.WRONG_ACTOR,
        MatchEngine.apply(accepted.state(), place(actor, accepted.state().hand(accepted.state().currentSeat()).getFirst().copyId(), Square.NORTH, accepted.state().revision(), START)).rejection().code());
    assertEquals(MatchErrorCode.STALE_REVISION,
        MatchEngine.apply(accepted.state(), place(accepted.state().currentSeat(), accepted.state().hand(accepted.state().currentSeat()).getFirst().copyId(), Square.NORTH, 0, START)).rejection().code());
  }

  @Test
  void fullBoardProducesCardCountScoreAndDraw() {
    MatchState state = MatchEngine.start(fixtureStart(4L)).state();
    for (int index = 0; index < 9; index++) {
      PlayerSeat actor = state.currentSeat();
      CardCopyId copy = state.hand(actor).getFirst().copyId();
      MatchEngine.MatchTransition transition = MatchEngine.apply(state,
          place(actor, copy, Square.of(index), state.revision(), START.plusSeconds(index + 1L)));
      assertTrue(transition.accepted());
      state = transition.state();
    }
    assertTrue(state.isFinished());
    assertEquals(MatchResult.Outcome.DRAW, state.result().outcome());
    assertEquals(5, state.result().score(PlayerSeat.ONE));
    assertEquals(5, state.result().score(PlayerSeat.TWO));
    assertFalse(state.board().emptySquares().iterator().hasNext());
    assertEquals(0L, state.remainingBankMillis(PlayerSeat.ONE));
    assertEquals(0L, state.remainingBankMillis(PlayerSeat.TWO));
    assertEquals(0L, state.remainingBankMillis(PlayerSeat.ONE, START.plusSeconds(100)));
    assertEquals(0L, state.remainingGraceMillis(PlayerSeat.ONE, START.plusSeconds(100)));
    assertEquals(0L, state.remainingTurnMillis(PlayerSeat.ONE, START.plusSeconds(100)));
  }

  @Test
  void projectionMasksOpponentCardsAndExposesOnlyLegalActions() {
    MatchState state = MatchEngine.start(fixtureStart(5L)).state();
    MatchProjection projection = MatchProjection.forViewer(state, state.currentSeat());
    assertEquals(5, projection.viewerHand().size());
    assertEquals(5, projection.opponentHandSlots().size());
    assertTrue(projection.opponentHandSlots().stream().allMatch(slot -> slot.cardId() == null));
    assertEquals(5, projection.legalCopyIds().size());
    assertEquals(9, projection.legalSquares().size());
  }

  private static MatchCommand.PlaceCard place(PlayerSeat seat, CardCopyId copy, Square square, long revision, Instant time) {
    return new MatchCommand.PlaceCard(seat, copy, square, revision, UUID.randomUUID(), time);
  }

  static MatchEngine.StartMatch fixtureStart(long seed) {
    CardCatalog catalog = fixtureCatalog();
    return new MatchEngine.StartMatch(new MatchId("match-" + seed), deck(1), deck(6), catalog,
        new MatchRules(), seed, START);
  }

  static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(new CardCopy(new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index)));
    }
    return new Deck(copies);
  }

  static CardCatalog fixtureCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(new CardDefinition(new CardId(id), "No. " + id, "Card " + id, 1,
          CardType.NORMAL, id == 1 ? 10 : 1, id == 2 ? 10 : 1, id == 2 ? 10 : 1, 1, 100));
    }
    return new CardCatalog(definitions, "test");
  }
}
