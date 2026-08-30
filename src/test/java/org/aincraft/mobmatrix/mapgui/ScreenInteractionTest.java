package org.aincraft.mobmatrix.mapgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class ScreenInteractionTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void matchScreenEmitsOnlyLegalPlacementAndTimeoutIntents() {
    MatchEngine.MatchTransition transition = MatchEngine.start(startMatch());
    MatchView view =
        MatchView.from(
            MatchProjection.forViewer(transition.state(), PlayerSeat.ONE, START),
            transition.state().rules());
    List<MatchScreen.Intent> intents = new ArrayList<>();
    MatchScreen screen = new MatchScreen(view, PlayerSeat.ONE, intents::add);

    screen.selectSquare(Square.of(0));
    assertTrue(intents.isEmpty());
    screen.selectCard(view.legalCopyIds().getFirst());
    screen.selectSquare(view.legalSquares().getFirst());

    assertEquals(1, intents.size());
    MatchScreen.PlaceCardIntent placement =
        assertInstanceOf(MatchScreen.PlaceCardIntent.class, intents.getFirst());
    assertEquals(view.legalCopyIds().getFirst(), placement.copyId());
    assertEquals(view.legalSquares().getFirst(), placement.square());

    MatchScreen timeoutScreen = new MatchScreen(view, PlayerSeat.ONE, intents::add);
    timeoutScreen.timeout();
    assertInstanceOf(MatchScreen.TimeoutIntent.class, intents.getLast());
  }

  @Test
  void deckScreenReturnsExactlyTheSelectedPhysicalIdentities() {
    MatchEngine.MatchTransition transition = MatchEngine.start(startMatch());
    List<HandCard> hand = transition.state().hand(PlayerSeat.ONE);
    List<List<CardCopyId>> selections = new ArrayList<>();
    DeckScreen screen = new DeckScreen(hand, selections::add);

    for (HandCard card : hand.subList(0, 5)) screen.toggle(card.copyId());
    assertTrue(screen.confirm());
    assertEquals(
        List.of(
            hand.get(0).copyId(),
            hand.get(1).copyId(),
            hand.get(2).copyId(),
            hand.get(3).copyId(),
            hand.get(4).copyId()),
        selections.getFirst());
    assertEquals(5, screen.selectedCopyIds().size());
  }

  private static MatchEngine.StartMatch startMatch() {
    return new MatchEngine.StartMatch(
        new org.aincraft.mobmatrix.domain.MatchId("screen"),
        deck(1),
        deck(6),
        catalog(),
        new MatchRules(),
        17L,
        START);
  }

  private static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index)));
    }
    return new Deck(copies);
  }

  private static CardCatalog catalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100));
    }
    return new CardCatalog(definitions, "screen");
  }
}
