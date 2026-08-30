package org.aincraft.mobmatrix.mapgui;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Node;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.HandCard;

/** MapGui adapter for selecting exactly five existing physical card copies. */
public final class DeckScreen extends Screen {
  private final List<HandCard> cards;
  private final SelectionSink selections;
  private final Set<CardCopyId> selected = new LinkedHashSet<>();

  public DeckScreen(List<HandCard> cards, SelectionSink selections) {
    this.cards = List.copyOf(Objects.requireNonNull(cards, "cards are required"));
    this.selections = Objects.requireNonNull(selections, "selection sink is required");
    Set<CardCopyId> identities = new LinkedHashSet<>();
    for (HandCard card : this.cards) {
      Objects.requireNonNull(card, "card is required");
      if (!identities.add(card.copyId()))
        throw new IllegalArgumentException("duplicate physical card");
    }
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
    List<Node> cardNodes = new ArrayList<>();
    for (HandCard card : cards) {
      String prefix = selected.contains(card.copyId()) ? "[selected] " : "";
      cardNodes.add(
          Button(prefix + card.definition().publicNumber())
              .key("card-" + card.copyId().value())
              .onClick(() -> toggle(card.copyId())));
    }
    if (cardNodes.isEmpty()) cardNodes.add(Text("No valid physical cards"));
    Node confirm = Button("Confirm five").onClick(this::confirm);
    return Column(
            Text("Select five physical cards (" + selected.size() + "/5)"),
            Column(cardNodes).gap(2),
            Row(List.of(confirm)).fillWidth())
        .gap(3)
        .padding(4);
  }

  @FunctionalInterface
  public interface SelectionSink {
    void accept(List<CardCopyId> copyIds);
  }
}
