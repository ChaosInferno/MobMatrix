package org.aincraft.mobmatrix.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure immutable standard Triple Triad match aggregate. */
public final class MatchEngine {
  public MatchEngine() {}

  public record StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
      MatchRules rules, long seed, Instant startTime) {
    public StartMatch {
      Objects.requireNonNull(matchId, "match id is required");
      Objects.requireNonNull(oneDeck, "one deck is required");
      Objects.requireNonNull(twoDeck, "two deck is required");
      Objects.requireNonNull(catalog, "catalog is required");
      Objects.requireNonNull(rules, "rules are required");
      Objects.requireNonNull(startTime, "start time is required");
    }
    public MatchId id() { return matchId; }

  }

  public record MatchTransition(MatchState state, List<String> events, MatchRejection rejection) {
    public MatchTransition {
      Objects.requireNonNull(state, "state is required");
      events = List.copyOf(Objects.requireNonNull(events, "events are required"));
      if (rejection != null && !events.isEmpty()) throw new IllegalArgumentException("rejection cannot have events");
    }

    public boolean accepted() { return rejection == null; }
    public boolean rejected() { return rejection != null; }
  }

  public static MatchTransition start(StartMatch start) {
    Objects.requireNonNull(start, "start is required");
    Map<PlayerSeat, List<HandCard>> hands = new EnumMap<>(PlayerSeat.class);
    hands.put(PlayerSeat.ONE, hand(start.oneDeck(), start.catalog()));
    hands.put(PlayerSeat.TWO, hand(start.twoDeck(), start.catalog()));
    PlayerSeat first = Math.floorMod(start.seed(), 2) == 0 ? PlayerSeat.ONE : PlayerSeat.TWO;
    return accepted(MatchState.initial(start.matchId(), start.catalog(), start.rules(), hands, first,
        start.startTime(), start.seed()), List.of("MATCH_STARTED"));
  }

  public static MatchTransition apply(MatchState state, MatchCommand command) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(command, "command is required");
    if (state.hasCommand(command.commandId())) return rejected(state, MatchErrorCode.DUPLICATE_COMMAND_ID);
    if (state.isFinished()) return rejected(state, MatchErrorCode.MATCH_FINISHED);
    if (command.expectedRevision() != state.revision()) return rejected(state, MatchErrorCode.STALE_REVISION);
    if (command.actor() != state.currentSeat()) return rejected(state, MatchErrorCode.WRONG_ACTOR);
    if (command instanceof MatchCommand.PlaceCard place) return applyPlacement(state, place);
    return applyTimeout(state, (MatchCommand.Timeout) command);
  }

  private static MatchTransition applyPlacement(MatchState state, MatchCommand.PlaceCard command) {
    if (!state.board().isEmpty(command.square())) return rejected(state, MatchErrorCode.OCCUPIED_SQUARE);
    HandCard played = null;
    for (HandCard card : state.hand(command.actor())) {
      if (card.copyId().equals(command.copyId())) {
        played = card;
        break;
      }
    }
    if (played == null) {
      boolean wasPlayed = state.board().occupiedSquares().stream()
          .map(state.board()::copyAt).anyMatch(copy -> copy.copyId().equals(command.copyId()));
      return rejected(state, wasPlayed ? MatchErrorCode.ALREADY_PLAYED_COPY : MatchErrorCode.UNKNOWN_COPY);
    }
    return place(state, command.actor(), played, command.square(), command.commandId(), command.commandTime());
  }

  private static MatchTransition applyTimeout(MatchState state, MatchCommand.Timeout command) {
    long elapsed = elapsedMillis(state.turnStartedAt(), command.commandTime());
    long dueAfter = MatchState.GRACE_MILLIS + state.remainingBankMillis(command.actor());
    if (elapsed < dueAfter) return rejected(state, MatchErrorCode.TIMEOUT_NOT_DUE);
    List<HandCard> hand = state.hand(command.actor());
    List<Square> empty = state.board().emptySquares();
    if (hand.isEmpty() || empty.isEmpty()) return rejected(state, MatchErrorCode.INVALID_COMMAND);
    return place(state, command.actor(), hand.getFirst(), empty.getFirst(), command.commandId(), command.commandTime());
  }

  private static MatchTransition place(MatchState state, PlayerSeat actor, HandCard played,
      Square square, UUID commandId, Instant commandTime) {
    Map<PlayerSeat, Long> banks = new EnumMap<>(PlayerSeat.class);
    banks.putAll(state.bankMillis());
    long elapsed = elapsedMillis(state.turnStartedAt(), commandTime);
    long charge = Math.max(0L, elapsed - MatchState.GRACE_MILLIS);
    banks.put(actor, Math.max(0L, state.remainingBankMillis(actor) - charge));

    Board board = state.board().place(square, played.copy(), actor);
    List<String> events = new ArrayList<>();
    events.add("CARD_PLACED");
    for (Board.Adjacent adjacent : board.adjacent(square)) {
      if (adjacent.cell() == null || adjacent.cell().owner() == actor) continue;
      CardDefinition placedDefinition = state.catalog().require(played.cardId());
      CardDefinition adjacentDefinition = state.catalog().require(adjacent.cell().cardId());
      if (placedDefinition.value(adjacent.facingSide()) > adjacentDefinition.value(adjacent.adjacentSide())) {
        board = board.withOwner(adjacent.square(), actor);
        events.add("CARD_CAPTURED");
      }
    }

    Map<PlayerSeat, List<HandCard>> hands = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) {
      List<HandCard> remaining = new ArrayList<>(state.hand(seat));
      if (seat == actor) remaining.remove(played);
      hands.put(seat, List.copyOf(remaining));
    }
    Set<UUID> commandIds = new HashSet<>(state.commandIds());
    commandIds.add(commandId);
    MatchResult result = null;
    if (board.emptySquares().isEmpty()) {
      result = score(board, hands);
      events.add("MATCH_FINISHED");
    }
    PlayerSeat nextSeat = result == null ? actor.opponent() : actor;
    MatchState next = state.next(board, hands, nextSeat, state.revision() + 1, result, banks,
        commandTime, commandIds);
    return accepted(next, events);
  }

  private static MatchResult score(Board board, Map<PlayerSeat, List<HandCard>> hands) {
    Map<PlayerSeat, Integer> scores = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) {
      scores.put(seat, Math.toIntExact(board.countOwnedBy(seat)) + hands.get(seat).size());
    }
    MatchResult.Outcome outcome = scores.get(PlayerSeat.ONE).equals(scores.get(PlayerSeat.TWO))
        ? MatchResult.Outcome.DRAW
        : scores.get(PlayerSeat.ONE) > scores.get(PlayerSeat.TWO)
            ? MatchResult.Outcome.ONE_WINS : MatchResult.Outcome.TWO_WINS;
    return new MatchResult(scores, outcome);
  }

  private static List<HandCard> hand(Deck deck, CardCatalog catalog) {
    List<HandCard> cards = new ArrayList<>();
    for (CardCopy copy : deck.copies()) cards.add(new HandCard(copy, catalog.require(copy.cardId())));
    return List.copyOf(cards);
  }

  private static long elapsedMillis(Instant start, Instant end) {
    return Math.max(0L, Duration.between(start, end).toMillis());
  }

  private static MatchTransition accepted(MatchState state, List<String> events) {
    return new MatchTransition(state, events, null);
  }

  private static MatchTransition rejected(MatchState state, MatchErrorCode code) {
    return new MatchTransition(state, List.of(), new MatchRejection(code, code.name()));
  }
}
