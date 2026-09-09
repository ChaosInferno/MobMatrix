package org.aincraft.mobmatrix.mapgui;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Text;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import java.awt.Color;
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
import org.aincraft.mobmatrix.domain.MatchProjection;
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

  private static final int MAP_SIZE = 128;
  private static final int HAND_CARD_SIZE = 16;
  private static final int SIDE_WIDTH = HAND_CARD_SIZE;
  private static final int COLUMN_GAP = 2;
  private static final int BOARD_GAP = 1;
  private static final int CELL_SIZE =
      (MAP_SIZE - SIDE_WIDTH * 2 - COLUMN_GAP * 2 - 2 - BOARD_GAP * 2) / 3;
  private static final int BOARD_SIZE = CELL_SIZE * 3 + BOARD_GAP * 2 + 2;
  private static final Color BOARD_FRAME = new Color(35, 49, 67);
  private static final Color BOARD_EMPTY = new Color(22, 35, 51);
  private static final Color BOARD_ALT = new Color(28, 45, 65);
  private static final Color BOARD_PLAYER_ONE = new Color(30, 68, 80);
  private static final Color BOARD_PLAYER_TWO = new Color(76, 49, 72);
  private static final Color BOARD_LEGAL = new Color(42, 67, 72);
  private static final Color BOARD_BORDER = new Color(81, 111, 137);
  private static final Color GOLD = new Color(235, 199, 103);
  private static final Color PLAYER_ACCENT = new Color(118, 220, 242);
  private static final Color OPPONENT_ACCENT = new Color(224, 147, 197);
  private static final Color OPPONENT_BORDER = new Color(119, 83, 151);
  private static final Color SCREEN_BACKGROUND = new Color(8, 13, 22);

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
  public Color background() {
    return SCREEN_BACKGROUND;
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
    String status =
        view.hasResult()
            ? resultText(view.result(), seat)
            : (view.currentSeat() == seat ? "YOUR TURN" : "OPPONENT TURN");
    Node header =
        Row(Text(status).key("status"), Text("T " + seconds(view.turnMillis()) + "s").key("timer"))
            .justify(Justify.SPACE_BETWEEN)
            .fillWidth()
            .key("header");

    Node playerPanel =
        Column(Text("YOU").key("player-title"), handStack())
            .gap(1)
            .width(SIDE_WIDTH)
            .align(Align.CENTER)
            .key("player-panel");
    Node boardPanel =
        Column(Text("BOARD").key("board-title"), boardGrid())
            .gap(1)
            .width(BOARD_SIZE)
            .align(Align.CENTER)
            .key("board-panel");
    Node opponentPanel =
        Column(Text("RIVAL").key("opponent-title"), opponentStack())
            .gap(1)
            .width(SIDE_WIDTH)
            .align(Align.CENTER)
            .key("opponent-panel");
    Node playArea =
        Row(List.of(playerPanel, boardPanel, opponentPanel))
            .gap(COLUMN_GAP)
            .fillWidth()
            .align(Align.CENTER)
            .justify(Justify.CENTER)
            .key("play-area");

    Node controls =
        Row(
                List.of(
                    Button("Timeout").onClick(this::timeout),
                    Button("Close").onClick(this::closeView)))
            .gap(1)
            .justify(Justify.CENTER)
            .fillWidth()
            .key("controls");
    return Column(header, playArea, controls)
        .gap(1)
        .fillWidth()
        .fillHeight()
        .justify(Justify.CENTER);
  }

  private Node handStack() {
    List<Node> cards = new ArrayList<>();
    for (HandCard card : view.hand()) {
      boolean legal = view.legalCopyIds().contains(card.copyId());
      cards.add(
          Draw(context -> paintPlayerCard(context, card, legal, card.copyId().equals(selectedCopy)))
              .preferred(HAND_CARD_SIZE, HAND_CARD_SIZE)
              .key("hand-" + card.copyId().value())
              .onClick(() -> selectCard(card.copyId())));
    }
    return verticalStack(cards, "No cards");
  }

  private Node opponentStack() {
    List<Node> cards = new ArrayList<>();
    for (int index = 0; index < view.opponentHand().size(); index++) {
      MatchProjection.HandSlot slot = view.opponentHand().get(index);
      cards.add(
          Draw(context -> paintOpponentCard(context, slot))
              .preferred(HAND_CARD_SIZE, HAND_CARD_SIZE)
              .key("opponent-" + index));
    }
    return verticalStack(cards, "No rival cards");
  }

  private Node verticalStack(List<Node> cards, String emptyLabel) {
    if (cards.isEmpty()) return Column(Text(emptyLabel)).width(SIDE_WIDTH).align(Align.CENTER);
    return Column(cards).gap(1).width(SIDE_WIDTH).align(Align.CENTER);
  }

  private Node boardGrid() {
    List<Node> rows = new ArrayList<>(3);
    for (int row = 0; row < 3; row++) {
      List<Node> cells = new ArrayList<>(3);
      for (int column = 0; column < 3; column++) {
        Square square = Square.of(row * 3 + column);
        cells.add(
            Draw(context -> paintBoardCell(context, square))
                .preferred(CELL_SIZE, CELL_SIZE)
                .key("square-" + square.index())
                .onClick(() -> selectSquare(square)));
      }
      rows.add(
          Row(cells).gap(BOARD_GAP).justify(Justify.CENTER).width(CELL_SIZE * 3 + BOARD_GAP * 2));
    }
    return Column(rows)
        .gap(BOARD_GAP)
        .padding(1)
        .background(BOARD_FRAME)
        .border(1, BOARD_BORDER)
        .radius(3)
        .width(BOARD_SIZE)
        .height(BOARD_SIZE);
  }

  private void paintPlayerCard(
      PaintContext context, HandCard card, boolean legal, boolean selected) {
    CardTokenRenderer.paintPlayerCard(context, card, legal, selected);
  }

  private void paintOpponentCard(PaintContext context, MatchProjection.HandSlot slot) {
    Painter painter = context.painter();
    Rect bounds = context.bounds();
    painter.rect(bounds, CardTokenRenderer.CARD_SURFACE, 1, OPPONENT_BORDER, 2);
    if (slot.revealed() && slot.cardId() != null) {
      CardTokenRenderer.drawArt(painter, bounds, slot.cardId().value());
    } else {
      CardTokenRenderer.drawArt(painter, bounds, CardTokenRenderer.CARD_BACK);
      CardTokenRenderer.drawTextCentered(painter, bounds, "?", OPPONENT_ACCENT);
    }
  }

  private void paintBoardCell(PaintContext context, Square square) {
    Painter painter = context.painter();
    Rect bounds = context.bounds();
    Board.Cell cell = view.board().get(square.index());
    boolean legal = view.legalSquares().contains(square) && !view.hasResult();
    Color fill;
    Color border;
    if (cell != null) {
      fill = cell.owner() == PlayerSeat.ONE ? BOARD_PLAYER_ONE : BOARD_PLAYER_TWO;
      border = cell.owner() == PlayerSeat.ONE ? PLAYER_ACCENT : OPPONENT_ACCENT;
    } else {
      fill = legal ? BOARD_LEGAL : (square.index() % 2 == 0 ? BOARD_EMPTY : BOARD_ALT);
      border = legal ? GOLD : BOARD_BORDER;
    }
    painter.rect(bounds, fill, legal ? 2 : 1, border, 1);
    if (cell == null) return;
    CardTokenRenderer.drawArt(painter, bounds, cell.cardId().value());
    CardTokenRenderer.drawEffectiveValues(painter, bounds, cell.effectiveValues());
    CardTokenRenderer.drawTextCentered(
        painter,
        bounds,
        cell.owner() == seat ? "Y" : "R",
        cell.owner() == seat ? PLAYER_ACCENT : OPPONENT_ACCENT);
  }

  private static String seconds(long millis) {
    return Long.toString(Math.max(0L, millis / 1000L));
  }

  static String resultText(MatchResult result, PlayerSeat viewer) {
    String message =
        switch (result.outcome()) {
          case DRAW -> "DRAW";
          case ONE_WINS -> viewer == PlayerSeat.ONE ? "YOU WIN" : "YOU LOSE";
          case TWO_WINS -> viewer == PlayerSeat.TWO ? "YOU WIN" : "YOU LOSE";
        };
    return message + " " + result.score(PlayerSeat.ONE) + "-" + result.score(PlayerSeat.TWO);
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
