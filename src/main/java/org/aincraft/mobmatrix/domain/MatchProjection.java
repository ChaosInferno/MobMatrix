package org.aincraft.mobmatrix.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Basic player-specific view; opponent hand definitions are intentionally masked. */
public record MatchProjection(
    List<Board.Cell> board,
    List<HandCard> viewerHand,
    List<HandSlot> opponentHandSlots,
    PlayerSeat currentSeat,
    List<CardCopyId> legalCopyIds,
    List<Square> legalSquares,
    long revision,
    MatchResult result,
    long viewerBankMillis,
    long viewerGraceMillis,
    long viewerTurnMillis) {
  public record HandSlot(CardCopyId copyId, CardId cardId, boolean revealed) {
    public static HandSlot masked() { return new HandSlot(null, null, false); }
  }

  public MatchProjection {
    board = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(Objects.requireNonNull(board)));
    viewerHand = List.copyOf(Objects.requireNonNull(viewerHand));
    opponentHandSlots = List.copyOf(Objects.requireNonNull(opponentHandSlots));
    currentSeat = Objects.requireNonNull(currentSeat);
    legalCopyIds = List.copyOf(Objects.requireNonNull(legalCopyIds));
    legalSquares = List.copyOf(Objects.requireNonNull(legalSquares));
    if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
  }

  public static MatchProjection forViewer(MatchState state, PlayerSeat viewer) {
    return forViewer(state, viewer, state.turnStartedAt());
  }

  public static MatchProjection forViewer(MatchState state, PlayerSeat viewer, Instant now) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(viewer, "viewer is required");
    Objects.requireNonNull(now, "time is required");
    boolean canAct = state.currentSeat() == viewer && !state.isFinished() && !state.isTurnExpired(now);
    List<CardCopyId> legalCopies;
    if (!canAct) {
      legalCopies = List.of();
    } else if (state.ruleSet().has(Rule.ORDER)) {
      legalCopies = state.hand(viewer).isEmpty() ? List.of() : List.of(state.hand(viewer).getFirst().copyId());
    } else if (state.ruleSet().has(Rule.CHAOS)) {
      CardCopyId chaos = state.chaosChoice(viewer);
      legalCopies = chaos == null ? List.of() : List.of(chaos);
    } else {
      legalCopies = state.hand(viewer).stream().map(HandCard::copyId).toList();
    }
    List<Square> legalSquares = canAct
        ? state.board().emptySquares() : List.of();
    List<HandSlot> opponentSlots = state.hand(viewer.opponent()).stream().map(card -> {
      boolean reveal = state.ruleSet().has(Rule.ALL_OPEN)
          || state.revealedCards(viewer.opponent()).contains(card.copyId());
      return reveal ? new HandSlot(card.copyId(), card.cardId(), true) : HandSlot.masked();
    }).toList();
    return new MatchProjection(state.board().cells(), state.hand(viewer), opponentSlots,
        state.currentSeat(), legalCopies, legalSquares, state.revision(), state.result(),
        state.remainingBankMillis(viewer, now), state.remainingGraceMillis(viewer, now),
        state.remainingTurnMillis(viewer, now));
  }

  public List<Board.Cell> boardCells() { return board; }
  public List<HandCard> hand() { return viewerHand; }
  public List<HandSlot> opponentHand() { return opponentHandSlots; }
}
