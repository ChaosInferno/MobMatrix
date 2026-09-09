package org.aincraft.mobmatrix.mapgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.ui.AwtFont;
import de.flog99.mapgui.ui.CustomPaint;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.HandCard;
import org.junit.jupiter.api.Test;

final class DeckScreenTest {
  @Test
  void pagesUniqueCardTypesWithoutOverlappingCards() {
    List<HandCard> cards = cardsWithDuplicateType();
    DeckScreen screen = new DeckScreen(cards, ignored -> {});

    assertEquals(13, screen.cards().size());
    assertEquals(2, screen.pageCount());
    assertEquals(1, screen.page());
    assertEquals(12, screen.visibleCards().size());

    screen.nextPage();
    assertEquals(2, screen.page());
    assertEquals(1, screen.visibleCards().size());
    screen.nextPage();
    assertEquals(2, screen.page());

    screen.previousPage();
    assertEquals(1, screen.page());
  }

  @Test
  void cardPageUsesMainboardTokensWithNumericValues() {
    DeckScreen screen =
        new DeckScreen(
            List.of(
                handCard(definition(1), 1L),
                handCard(definition(2), 2L),
                handCard(definition(3), 3L)),
            ignored -> {});
    Node grid = screen.previewTree().children().get(1);
    Node row = grid.children().getFirst();

    assertEquals("card-grid", grid.key());
    assertEquals(3, row.children().size());
    row.children().forEach(node -> assertInstanceOf(CustomPaint.class, node));

    screen.layout(AwtFont.named("Dialog", 8, Font.PLAIN, false), new Rect(0, 0, 128, 128));
    MapSurface surface = new MapSurface(128, 128);
    screen.paint(
        new Painter(surface, MapColors.INSTANCE, AwtFont.named("Dialog", 8, Font.PLAIN, false)));
    assertTrue(hasColor(surface, MapColors.of(new Color(255, 239, 151))));
  }

  private static boolean hasColor(MapSurface surface, byte color) {
    for (int y = 0; y < 128; y++) {
      for (int x = 0; x < 128; x++) {
        if (surface.get(x, y) == color) return true;
      }
    }
    return false;
  }

  private static List<HandCard> cardsWithDuplicateType() {
    List<HandCard> cards = new ArrayList<>();
    CardDefinition duplicate = definition(1);
    cards.add(handCard(duplicate, 1L));
    cards.add(handCard(duplicate, 2L));
    for (int id = 2; id <= 13; id++) cards.add(handCard(definition(id), id + 2L));
    return cards;
  }

  private static HandCard handCard(CardDefinition definition, long copyId) {
    return new HandCard(
        new CardCopy(new CardCopyId(new UUID(0L, copyId)), definition.id()), definition);
  }

  private static CardDefinition definition(int id) {
    return new CardDefinition(
        new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100);
  }
}
