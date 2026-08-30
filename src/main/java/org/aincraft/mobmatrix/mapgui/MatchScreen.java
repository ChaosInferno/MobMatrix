package org.aincraft.mobmatrix.mapgui;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Node;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.aincraft.mobmatrix.application.MatchView;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchResult;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;

/** MapGui projection and input adapter for one player's committed match view. */
public final class MatchScreen extends Screen {
  private MatchView view;
  private final PlayerSeat seat;
  private final IntentSink intents;
  private CardCopyId selectedCopy;
  private boolean closeIntentSent;

  public MatchScreen(MatchView view, PlayerSeat seat, IntentSink intents) {
    this.view = Objects.requireNonNull(view, "view is required");
    this.seat = Objects.requireNonNull(seat, "seat is required");
    this.intents = Objects.requireNonNull(intents, "intent sink is required");
  }

  @Override
  public Component title() {
    return Component.text("MobMatrix");
  }

  @Override
  public Click activateOn() {
    return Click.BOTH;
  }

  /** Replaces the projection after an application commit and marks the screen dirty. */
  public void update(MatchView next) {
    view = Objects.requireNonNull(next, "view is required");
    if (selectedCopy != null && !view.legalCopyIds().contains(selectedCopy)) selectedCopy = null;
    invalidate();
  }

  public MatchView view() {
    return view;
  }

  public PlayerSeat seat() {
    return seat;
  }

  /** Selects a legal physical card without consulting display text. */
  public void selectCard(CardCopyId copyId) {
    Objects.requireNonNull(copyId, "copy id is required");
    if (view.legalCopyIds().contains(copyId)) {
      selectedCopy = copyId;
      invalidate();
    }
  }

  /** Emits a placement intent only when both selected identities are legal in this projection. */
  public void selectSquare(Square square) {
    Objects.requireNonNull(square, "square is required");
    if (selectedCopy == null
        || !view.legalCopyIds().contains(selectedCopy)
        || !view.legalSquares().contains(square)
        || view.currentSeat() != seat
        || view.hasResult()) return;
    intents.accept(
        new PlaceCardIntent(
            seat, selectedCopy, square, view.revision(), UUID.randomUUID(), Instant.now()));
    selectedCopy = null;
  }

  public void timeout() {
    if (view.currentSeat() == seat && !view.hasResult()) {
      intents.accept(new TimeoutIntent(seat, view.revision(), UUID.randomUUID(), Instant.now()));
    }
  }

  public void closeView() {
    sendCloseIntent();
    close();
  }

  @Override
  protected void onClose() {
    sendCloseIntent();
  }

  private void sendCloseIntent() {
    if (!closeIntentSent) {
      closeIntentSent = true;
      intents.accept(new CloseViewIntent());
    }
  }

  @Override
  protected Node build() {
    List<Node> rows = new ArrayList<>(3);
    for (int row = 0; row < 3; row++) {
      List<Node> cells = new ArrayList<>(3);
      for (int column = 0; column < 3; column++) {
        Square square = Square.of(row * 3 + column);
        cells.add(boardButton(square));
      }
      rows.add(Row(cells).gap(2).fillWidth());
    }
    List<Node> handNodes = new ArrayList<>();
    for (HandCard card : view.hand()) {
      String marker = card.copyId().equals(selectedCopy) ? "[selected] " : "";
      handNodes.add(
          Button(marker + card.definition().publicNumber())
              .key("hand-" + card.copyId().value())
              .onClick(() -> selectCard(card.copyId())));
    }
    if (handNodes.isEmpty()) handNodes.add(Text("No legal cards"));

    List<Node> opponentNodes = new ArrayList<>();
    for (int index = 0; index < view.opponentHand().size(); index++) {
      var slot = view.opponentHand().get(index);
      String label =
          slot.revealed() && slot.cardId() != null
              ? "Opponent " + slot.cardId().value()
              : "Opponent hidden";
      opponentNodes.add(Text((index + 1) + ": " + label));
    }
    if (opponentNodes.isEmpty()) opponentNodes.add(Text("Opponent hand empty"));

    List<Node> controls = new ArrayList<>();
    controls.add(Button("Timeout").onClick(this::timeout));
    controls.add(Button("Close").onClick(this::closeView));
    String status =
        view.hasResult()
            ? resultText(view.result())
            : (view.currentSeat() == seat ? "Your turn" : "Opponent turn");
    return Column(
            Text(status),
            Text("Revision " + view.revision() + "  Turn " + view.turnMillis() + "ms"),
            Text("Bank " + view.bankMillis() + "ms  Grace " + view.graceMillis() + "ms"),
            Text("Rules " + view.rules()),
            Column(rows).gap(2),
            Text("Hand"),
            Row(handNodes).gap(2).fillWidth(),
            Text("Opponent"),
            Column(opponentNodes).gap(1),
            Row(controls).gap(2).fillWidth())
        .gap(3)
        .padding(4);
  }

  private Node boardButton(Square square) {
    Board.Cell cell = view.board().get(square.index());
    String label =
        cell == null
            ? "[" + square.index() + "] empty"
            : "[" + square.index() + "] " + cell.owner() + " " + cell.cardId().value();
    return Button(label).key("square-" + square.index()).onClick(() -> selectSquare(square));
  }

  private static String resultText(MatchResult result) {
    return "Result "
        + result.outcome()
        + " ("
        + result.score(PlayerSeat.ONE)
        + "-"
        + result.score(PlayerSeat.TWO)
        + ")";
  }

  /** Receives screen-only intents; implementations decide how to commit them. */
  @FunctionalInterface
  public interface IntentSink {
    void accept(Intent intent);
  }

  public sealed interface Intent permits PlaceCardIntent, TimeoutIntent, CloseViewIntent {}

  public record PlaceCardIntent(
      PlayerSeat actor,
      CardCopyId copyId,
      Square square,
      long expectedRevision,
      UUID commandId,
      Instant commandTime)
      implements Intent {
    public PlaceCardIntent {
      Objects.requireNonNull(actor, "actor is required");
      Objects.requireNonNull(copyId, "copy id is required");
      Objects.requireNonNull(square, "square is required");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(commandTime, "command time is required");
      if (expectedRevision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
  }

  public record TimeoutIntent(
      PlayerSeat actor, long expectedRevision, UUID commandId, Instant commandTime)
      implements Intent {
    public TimeoutIntent {
      Objects.requireNonNull(actor, "actor is required");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(commandTime, "command time is required");
      if (expectedRevision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
  }

  public record CloseViewIntent() implements Intent {}
}
