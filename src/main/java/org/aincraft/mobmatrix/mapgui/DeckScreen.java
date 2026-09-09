package org.aincraft.mobmatrix.mapgui;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.HandCard;

/** MapGui adapter for selecting exactly five existing physical card copies. */
public final class DeckScreen extends Screen {
  public static final int CARDS_PER_PAGE = 12;

  private static final int CARD_COLUMNS = 3;
  private static final int CARD_ROWS = 4;
  private static final int CARD_SIZE = 16;
  private static final int GRID_GAP = 1;

  private final List<HandCard> cards;
  private final SelectionSink selections;
  private final Set<CardCopyId> selected = new LinkedHashSet<>();
  private int page;

  public DeckScreen(List<HandCard> cards, SelectionSink selections) {
    Objects.requireNonNull(cards, "cards are required");
    this.selections = Objects.requireNonNull(selections, "selection sink is required");
    Set<CardCopyId> identities = new LinkedHashSet<>();
    Set<CardId> types = new LinkedHashSet<>();
    List<HandCard> uniqueCards = new ArrayList<>();
    for (HandCard card : cards) {
      Objects.requireNonNull(card, "card is required");
      if (!identities.add(card.copyId()))
        throw new IllegalArgumentException("duplicate physical card");
      if (types.add(card.cardId())) uniqueCards.add(card);
    }
    this.cards = List.copyOf(uniqueCards);
  }

  @Override
  public Component title() {
    return Component.text("Choose deck");
  }

  @Override
  public Click activateOn() {
    return Click.BOTH;
  }

  public List<HandCard> cards() {
    return cards;
  }

  public List<CardCopyId> selectedCopyIds() {
    return List.copyOf(selected);
  }

  public int page() {
    return page + 1;
  }

  public int pageCount() {
    return Math.max(1, (cards.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
  }

  public List<HandCard> visibleCards() {
    int start = Math.min(page * CARDS_PER_PAGE, cards.size());
    int end = Math.min(start + CARDS_PER_PAGE, cards.size());
    return List.copyOf(cards.subList(start, end));
  }

  public void nextPage() {
    if (page + 1 >= pageCount()) return;
    page++;
    invalidate();
  }

  public void previousPage() {
    if (page == 0) return;
    page--;
    invalidate();
  }

  /** Toggles one exact physical identity; display labels are never authoritative. */
  public void toggle(CardCopyId copyId) {
    Objects.requireNonNull(copyId, "copy id is required");
    if (cards.stream().noneMatch(card -> card.copyId().equals(copyId))) return;
    if (selected.contains(copyId)) {
      selected.remove(copyId);
    } else if (selected.size() < 5) {
      selected.add(copyId);
    }
    invalidate();
  }

  /** Reports the selection only when it contains exactly five distinct physical copies. */
  public boolean confirm() {
    if (selected.size() != 5) return false;
    selections.accept(List.copyOf(selected));
    return true;
  }

  @Override
  protected Node build() {
    Node confirm = Button("Confirm five").onClick(this::confirm);
    Node navigation =
        Row(
                Button("Prev").onClick(this::previousPage),
                Text("Page " + page() + "/" + pageCount()).key("page-indicator"),
                Button("Next").onClick(this::nextPage))
            .gap(2)
            .justify(Justify.CENTER)
            .fillWidth();
    return Column(
            Text("Select five physical cards (" + selected.size() + "/5)"),
            cardGrid(),
            navigation,
            Row(List.of(confirm)).justify(Justify.CENTER).fillWidth())
        .gap(2)
        .padding(4);
  }

  private Node cardGrid() {
    List<HandCard> visible = visibleCards();
    if (visible.isEmpty()) return Text("No valid physical cards").key("empty-cards");

    List<Node> rows = new ArrayList<>(CARD_ROWS);
    for (int row = 0; row < CARD_ROWS; row++) {
      List<Node> cardsInRow = new ArrayList<>(CARD_COLUMNS);
      for (int column = 0; column < CARD_COLUMNS; column++) {
        int index = row * CARD_COLUMNS + column;
        if (index >= visible.size()) break;
        HandCard card = visible.get(index);
        cardsInRow.add(
            Draw(
                    context ->
                        CardTokenRenderer.paintPlayerCard(
                            context, card, true, selected.contains(card.copyId())))
                .preferred(CARD_SIZE, CARD_SIZE)
                .key("card-" + card.copyId().value())
                .onClick(() -> toggle(card.copyId())));
      }
      if (!cardsInRow.isEmpty()) {
        rows.add(Row(cardsInRow).gap(GRID_GAP).justify(Justify.CENTER).fillWidth());
      }
    }
    return Column(rows).gap(GRID_GAP).fillWidth().key("card-grid");
  }

  @FunctionalInterface
  public interface SelectionSink {
    void accept(List<CardCopyId> copyIds);
  }
}
