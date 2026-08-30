package org.aincraft.mobmatrix.mapgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.application.MatchView;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class MatchProjectionTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void viewMasksOpponentCardsAndDefensivelyCopiesProjectionValues() {
    MatchStateFixture fixture = start(11L, new MatchRules());
    MatchProjection projection = MatchProjection.forViewer(fixture.state(), PlayerSeat.ONE, START);

    MatchView view = MatchView.from(projection);

    assertEquals(5, view.hand().size());
    assertEquals(5, view.opponentHand().size());
    assertTrue(view.opponentHand().stream().noneMatch(MatchProjection.HandSlot::revealed));
    assertTrue(view.opponentHand().stream().allMatch(slot -> slot.cardId() == null));
    assertEquals(projection.boardCells(), view.board());
    assertThrows(UnsupportedOperationException.class, () -> view.hand().clear());
    assertThrows(UnsupportedOperationException.class, () -> view.board().clear());
  }

  @Test
  void viewExposesOnlyLegalCopiesAndEmptySquares() {
    MatchStateFixture fixture = start(12L, new MatchRules());
    MatchProjection projection = MatchProjection.forViewer(fixture.state(), PlayerSeat.ONE, START);
    MatchView view = MatchView.from(projection);

    assertEquals(
        fixture.state().hand(PlayerSeat.ONE).stream().map(card -> card.copyId()).toList(),
        view.legalCopyIds());
    assertEquals(
        List.of(0, 1, 2, 3, 4, 5, 6, 7, 8),
        view.legalSquares().stream().map(Square::index).toList());
    assertEquals(projection.revision(), view.revision());
    assertEquals(projection.viewerTurnMillis(), view.turnMillis());
    assertFalse(view.hasResult());
  }

  @Test
  void viewCanCarryImmutableRuleBadgesWithoutChangingProjection() {
    MatchStateFixture fixture =
        start(13L, new MatchRules(org.aincraft.mobmatrix.domain.Rule.ALL_OPEN));
    MatchProjection projection = MatchProjection.forViewer(fixture.state(), PlayerSeat.ONE, START);

    MatchView view = MatchView.from(projection, fixture.state().ruleSet());

    assertTrue(view.rules().contains(org.aincraft.mobmatrix.domain.Rule.ALL_OPEN));
    assertThrows(UnsupportedOperationException.class, () -> view.rules().clear());
    assertTrue(view.opponentHand().stream().allMatch(MatchProjection.HandSlot::revealed));
  }

  private static MatchStateFixture start(long seed, MatchRules rules) {
    CardCatalog catalog = fixtureCatalog();
    MatchEngine.StartMatch start =
        new MatchEngine.StartMatch(
            new MatchId("match-" + seed), deck(1), deck(6), catalog, rules, seed, START);
    MatchEngine.MatchTransition transition = MatchEngine.start(start);
    assertTrue(transition.accepted());
    return new MatchStateFixture(transition.state());
  }

  private static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index)));
    }
    return new Deck(copies);
  }

  private static CardCatalog fixtureCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100));
    }
    return new CardCatalog(definitions, "test");
  }

  private record MatchStateFixture(org.aincraft.mobmatrix.domain.MatchState state) {}
}
