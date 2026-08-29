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
    List<CardCopyId> legalCopies = state.currentSeat() == viewer && !state.isFinished()
        ? state.hand(viewer).stream().map(HandCard::copyId).toList() : List.of();
    List<Square> legalSquares = state.currentSeat() == viewer && !state.isFinished()
        ? state.board().emptySquares() : List.of();
    return new MatchProjection(state.board().cells(), state.hand(viewer),
        state.hand(viewer.opponent()).stream().map(card -> HandSlot.masked()).toList(),
        state.currentSeat(), legalCopies, legalSquares, state.revision(), state.result(),
        state.remainingBankMillis(viewer, now), state.remainingGraceMillis(viewer, now),
        state.remainingTurnMillis(viewer, now));
  }

  public List<Board.Cell> boardCells() { return board; }
  public List<HandCard> hand() { return viewerHand; }
  public List<HandSlot> opponentHand() { return opponentHandSlots; }
}
