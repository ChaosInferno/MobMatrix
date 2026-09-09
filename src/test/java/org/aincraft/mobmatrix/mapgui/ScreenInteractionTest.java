package org.aincraft.mobmatrix.mapgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.ui.AwtFont;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import java.awt.Color;
import java.awt.Font;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.aincraft.mobmatrix.application.MatchView;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchResult;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.MatchState;
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
  void matchScreenUsesSmallVerticalHandsAndMaximumBoard() {
    MatchEngine.MatchTransition transition = MatchEngine.start(startMatch());
    MatchView view =
        MatchView.from(
            MatchProjection.forViewer(transition.state(), PlayerSeat.ONE, START),
            transition.state().rules());
    MatchScreen screen = new MatchScreen(view, PlayerSeat.ONE, intent -> {});

    Node root = screen.previewTree();
    assertEquals(3, root.children().size());
    Node playArea = root.children().get(1);
    assertEquals(
        List.of("player-panel", "board-panel", "opponent-panel"),
        playArea.children().stream().map(Node::key).toList());
    Node playerStack = playArea.children().get(0).children().get(1);
    Node opponentStack = playArea.children().get(2).children().get(1);
    assertEquals(5, playerStack.children().size());
    assertEquals(5, opponentStack.children().size());

    screen.layout(AwtFont.named("Dialog", 8, Font.PLAIN, false), new Rect(0, 0, 128, 128));
    Node arrangedPlayArea = screen.root().children().get(1);
    Node arrangedPlayerStack = arrangedPlayArea.children().get(0).children().get(1);
    Node arrangedBoard = arrangedPlayArea.children().get(1).children().get(1);
    assertEquals(16, arrangedPlayerStack.children().getFirst().bounds().width());
    assertEquals(91, arrangedBoard.bounds().width());
    assertEquals(29, arrangedBoard.children().getFirst().children().getFirst().bounds().width());
    assertTrue(
        arrangedBoard.bounds().width()
            > arrangedPlayerStack.children().getFirst().bounds().width());
  }

  @Test
  void matchScreenPaintsItsMapSurfaceWithVisibleCardValues() {
    MatchEngine.MatchTransition transition = MatchEngine.start(startMatch(shapedMaskCatalog()));
    MatchView view =
        MatchView.from(
            MatchProjection.forViewer(transition.state(), PlayerSeat.ONE, START),
            transition.state().rules());
    MatchScreen screen = new MatchScreen(view, PlayerSeat.ONE, intent -> {});
    AwtFont font = AwtFont.named("Dialog", 8, Font.PLAIN, false);
    MapSurface surface = new MapSurface(128, 128);

    screen.layout(font, new Rect(0, 0, 128, 128));
    screen.paint(new Painter(surface, MapColors.INSTANCE, font));

    Node firstCard =
        screen.root().children().get(1).children().get(0).children().get(1).children().getFirst();
    Rect cardBounds = firstCard.bounds();
    byte valueColor = MapColors.of(new Color(255, 239, 151));
    byte valueMask = MapColors.of(Color.BLACK);
    assertEquals(
        valueMask, surface.get(cardBounds.x() + 5, cardBounds.y() + 1), "mask follows glyph");
    assertNotEquals(
        valueMask, surface.get(cardBounds.x() + 9, cardBounds.y() + 5), "mask follows glyph shape");

    assertTrue(surface.isDirty());
    assertTrue(
        hasColor(surface, valueColor, cardBounds.x(), cardBounds.y(), cardBounds.width(), 6));
    assertTrue(
        hasColor(
            surface, valueColor, cardBounds.x(), cardBounds.bottom() - 6, cardBounds.width(), 6));
    assertTrue(
        hasColor(
            surface,
            valueColor,
            cardBounds.x(),
            cardBounds.y() + 2,
            cardBounds.width() / 2 + 1,
            cardBounds.height() - 4));
    assertTrue(
        hasColor(
            surface,
            valueColor,
            cardBounds.right() - (cardBounds.width() / 2 + 1),
            cardBounds.y() + 2,
            cardBounds.width() / 2 + 1,
            cardBounds.height() - 4));
  }

  @Test
  void boardValueGlyphsUseShortUnscaledRows() {
    MatchEngine.MatchTransition started = MatchEngine.start(startMatch(boardCatalog()));
    MatchEngine.MatchTransition placed =
        MatchEngine.apply(
            started.state(),
            new MatchCommand.PlaceCard(
                PlayerSeat.ONE,
                started.state().hand(PlayerSeat.ONE).getFirst().copyId(),
                Square.CENTER,
                started.state().revision(),
                UUID.randomUUID(),
                START.plusSeconds(1)));
    assertTrue(placed.accepted());
    MatchView view =
        MatchView.from(
            MatchProjection.forViewer(placed.state(), PlayerSeat.ONE, START),
            placed.state().rules());
    MatchScreen screen = new MatchScreen(view, PlayerSeat.ONE, intent -> {});
    AwtFont font = AwtFont.named("Dialog", 8, Font.PLAIN, false);
    MapSurface surface = new MapSurface(128, 128);

    screen.layout(font, new Rect(0, 0, 128, 128));
    screen.paint(new Painter(surface, MapColors.INSTANCE, font));

    Node centerCell =
        screen
            .root()
            .children()
            .get(1)
            .children()
            .get(1)
            .children()
            .get(1)
            .children()
            .get(1)
            .children()
            .get(1);
    Rect bounds = centerCell.bounds();
    byte valueColor = MapColors.of(new Color(255, 239, 151));
    assertEquals(29, bounds.width());
    assertEquals(valueColor, surface.get(bounds.x() + 14, bounds.y() + 2), "board top glyph");
    assertNotEquals(
        valueColor,
        surface.get(bounds.x() + 16, bounds.y() + 2),
        "board glyph width stays three logical pixels");
    assertNotEquals(
        valueColor, surface.get(bounds.x() + 15, bounds.y() + 3), "board glyph stays one row high");
    assertEquals(valueColor, surface.get(bounds.x() + 25, bounds.y() + 12), "board right glyph");
    assertEquals(valueColor, surface.get(bounds.x() + 14, bounds.y() + 22), "board bottom glyph");
    assertEquals(valueColor, surface.get(bounds.x() + 2, bounds.y() + 12), "board left glyph");
  }

  @Test
  void handValueGlyphsSurviveAdjacentValuePlates() {
    MatchEngine.MatchTransition transition = MatchEngine.start(startMatch(readabilityCatalog()));
    MatchView view =
        MatchView.from(
            MatchProjection.forViewer(transition.state(), PlayerSeat.ONE, START),
            transition.state().rules());
    MatchScreen screen = new MatchScreen(view, PlayerSeat.ONE, intent -> {});
    AwtFont font = AwtFont.named("Dialog", 8, Font.PLAIN, false);
    MapSurface surface = new MapSurface(128, 128);

    screen.layout(font, new Rect(0, 0, 128, 128));
    screen.paint(new Painter(surface, MapColors.INSTANCE, font));

    Node firstCard =
        screen.root().children().get(1).children().get(0).children().get(1).children().getFirst();
    Rect bounds = firstCard.bounds();
    byte valueColor = MapColors.of(new Color(255, 239, 151));
    byte valueMask = MapColors.of(Color.BLACK);
    assertEquals(valueColor, surface.get(bounds.x() + 5, bounds.y()), "first corner digit");
    assertEquals(valueColor, surface.get(bounds.x() + 10, bounds.y()), "second corner digit");
    assertEquals(valueColor, surface.get(bounds.x() + 4, bounds.y() + 4), "first digit base");
    assertEquals(valueColor, surface.get(bounds.x() + 10, bounds.y() + 4), "second digit base");
    assertEquals(valueMask, surface.get(bounds.x() + 6, bounds.y() + 1), "first digit halo");
    assertEquals(valueMask, surface.get(bounds.x() + 8, bounds.y() + 1), "second digit halo");
    assertEquals(valueMask, surface.get(bounds.x() + 7, bounds.y() + 4), "first base halo");
    assertEquals(valueMask, surface.get(bounds.x() + 8, bounds.y() + 4), "second base halo");
    assertNotEquals(
        valueMask, surface.get(bounds.x() + 7, bounds.y() + 1), "corner digit halos stay disjoint");
    assertEquals(valueColor, surface.get(bounds.x() + 12, bounds.y() + 5), "right glyph");
    assertEquals(valueColor, surface.get(bounds.x() + 8, bounds.y() + 10), "bottom glyph");
    assertEquals(valueColor, surface.get(bounds.x() + 5, bounds.y() + 7), "left glyph");
  }

  @Test
  void matchScreenShowsViewerRelativeFinalResult() {
    MatchResult result =
        new MatchResult(Map.of(PlayerSeat.ONE, 7, PlayerSeat.TWO, 3), MatchResult.Outcome.ONE_WINS);

    assertEquals("YOU WIN 7-3", MatchScreen.resultText(result, PlayerSeat.ONE));
    assertEquals("YOU LOSE 7-3", MatchScreen.resultText(result, PlayerSeat.TWO));
  }

  @Test
  void matchScreenColorsBoardCellsByFixedPlayerOwner() {
    MatchState state = MatchEngine.start(startMatch()).state();
    Board board =
        Board.empty()
            .place(Square.of(0), state.hand(PlayerSeat.ONE).getFirst().copy(), PlayerSeat.ONE)
            .place(Square.of(1), state.hand(PlayerSeat.TWO).getFirst().copy(), PlayerSeat.TWO);
    MatchScreen screen = new MatchScreen(boardView(state, board), PlayerSeat.ONE, intent -> {});
    AwtFont font = AwtFont.named("Dialog", 8, Font.PLAIN, false);
    MapSurface surface = new MapSurface(128, 128);

    screen.layout(font, new Rect(0, 0, 128, 128));
    screen.paint(new Painter(surface, MapColors.INSTANCE, font));

    Rect playerOne = boardCell(screen, 0).bounds();
    Rect playerTwo = boardCell(screen, 1).bounds();
    assertEquals(
        MapColors.of(new Color(118, 220, 242)),
        surface.get(playerOne.x(), playerOne.y() + playerOne.height() / 2));
    assertEquals(
        MapColors.of(new Color(224, 147, 197)),
        surface.get(playerTwo.x(), playerTwo.y() + playerTwo.height() / 2));
  }

  private static MatchView boardView(MatchState state, Board board) {
    return MatchView.from(
        new MatchProjection(
            board.cells(),
            state.hand(PlayerSeat.ONE),
            state.hand(PlayerSeat.TWO).stream()
                .map(card -> MatchProjection.HandSlot.masked())
                .toList(),
            state.currentSeat(),
            List.of(),
            List.of(),
            state.revision(),
            null,
            0L,
            0L,
            0L));
  }

  private static Node boardCell(MatchScreen screen, int index) {
    Node boardGrid = screen.root().children().get(1).children().get(1).children().get(1);
    return boardGrid.children().get(index / 3).children().get(index % 3);
  }

  private static boolean hasColor(
      MapSurface surface, byte color, int x, int y, int width, int height) {
    for (int row = y; row < y + height; row++) {
      for (int column = x; column < x + width; column++) {
        if (surface.get(column, row) == color) return true;
      }
    }
    return false;
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
    return startMatch(catalog());
  }

  private static MatchEngine.StartMatch startMatch(CardCatalog cardCatalog) {
    return new MatchEngine.StartMatch(
        new org.aincraft.mobmatrix.domain.MatchId("screen"),
        deck(1),
        deck(6),
        cardCatalog,
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

  private static CardCatalog boardCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id),
              "No. " + id,
              "Card " + id,
              1,
              CardType.NORMAL,
              id == 1 ? 6 : 1,
              id == 1 ? 3 : 1,
              id == 1 ? 9 : 1,
              id == 1 ? 4 : 1,
              100));
    }
    return new CardCatalog(definitions, "screen");
  }

  private static CardCatalog shapedMaskCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id),
              "No. " + id,
              "Card " + id,
              1,
              CardType.NORMAL,
              id == 1 ? 7 : 1,
              1,
              1,
              1,
              100));
    }
    return new CardCatalog(definitions, "screen");
  }

  private static CardCatalog readabilityCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id),
              "No. " + id,
              "Card " + id,
              1,
              CardType.NORMAL,
              id == 1 ? 10 : 1,
              id == 1 ? 2 : 1,
              id == 1 ? 3 : 1,
              id == 1 ? 4 : 1,
              100));
    }
    return new CardCatalog(definitions, "screen");
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
