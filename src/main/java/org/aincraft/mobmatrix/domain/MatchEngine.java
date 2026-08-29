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
      MatchRules rules, long seed, Instant startTime, List<CardCopy> oneInventory, List<CardCopy> twoInventory) {
    public StartMatch {
      Objects.requireNonNull(matchId, "match id is required");
      Objects.requireNonNull(oneDeck, "one deck is required");
      Objects.requireNonNull(twoDeck, "two deck is required");
      Objects.requireNonNull(catalog, "catalog is required");
      Objects.requireNonNull(rules, "rules are required");
      Objects.requireNonNull(startTime, "start time is required");
      oneInventory = List.copyOf(Objects.requireNonNull(oneInventory, "one inventory is required"));
      twoInventory = List.copyOf(Objects.requireNonNull(twoInventory, "two inventory is required"));
    }
    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        MatchRules rules, long seed, Instant startTime) {
      this(matchId, oneDeck, twoDeck, catalog, rules, seed, startTime, oneDeck.copies(), twoDeck.copies());
    }
    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        RuleSet rules, long seed, Instant startTime) {
      this(matchId, oneDeck, twoDeck, catalog, Objects.requireNonNull(rules).asMatchRules(), seed, startTime);
    }
    public MatchId id() { return matchId; }
    public RuleSet ruleSet() { return rules.asRuleSet(); }
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
    RandomTranscript transcript = new RandomTranscript(start.seed());
    RuleSet activeRules = RuleResolver.resolve(start.ruleSet(), transcript);
    Map<PlayerSeat, List<HandCard>> hands = new EnumMap<>(PlayerSeat.class);
    Deck oneDeck = activeRules.has(Rule.RANDOM)
        ? randomDeck(start.oneInventory(), start.oneDeck(), transcript, "random-one") : start.oneDeck();
    Deck twoDeck = activeRules.has(Rule.RANDOM)
        ? randomDeck(start.twoInventory(), start.twoDeck(), transcript, "random-two") : start.twoDeck();
    Map<CardCopyId, PlayerSeat> swapOwners = new java.util.HashMap<>();
    if (activeRules.has(Rule.SWAP)) {
      int oneIndex = transcript.nextInt("swap-one", 5);
      int twoIndex = transcript.nextInt("swap-two", 5);
      List<CardCopy> one = new ArrayList<>(oneDeck.copies());
      List<CardCopy> two = new ArrayList<>(twoDeck.copies());
      CardCopy exchanged = one.set(oneIndex, two.get(twoIndex));
      two.set(twoIndex, exchanged);
      swapOwners.put(one.get(oneIndex).copyId(), PlayerSeat.TWO);
      swapOwners.put(two.get(twoIndex).copyId(), PlayerSeat.ONE);
      oneDeck = new Deck(one); twoDeck = new Deck(two);
    }
    hands.put(PlayerSeat.ONE, hand(oneDeck, start.catalog()));
    hands.put(PlayerSeat.TWO, hand(twoDeck, start.catalog()));
    Map<PlayerSeat, Set<CardCopyId>> revealed = new EnumMap<>(PlayerSeat.class);
    revealed.put(PlayerSeat.ONE, selectRevealed(hands.get(PlayerSeat.ONE), PlayerSeat.ONE, activeRules, transcript));
    revealed.put(PlayerSeat.TWO, selectRevealed(hands.get(PlayerSeat.TWO), PlayerSeat.TWO, activeRules, transcript));
    PlayerSeat first = transcript.nextInt("first-seat", 2) == 0 ? PlayerSeat.ONE : PlayerSeat.TWO;
    return accepted(MatchState.initial(start.matchId(), start.catalog(), activeRules.asMatchRules(), hands, first,
        start.startTime(), start.seed(), transcript, revealed, swapOwners), List.of("MATCH_STARTED"));
  }
  private static Deck randomDeck(List<CardCopy> inventory, Deck fallback, RandomTranscript transcript, String label) {
    if (inventory.size() < 5) return fallback;
    List<CardCopy> available = new ArrayList<>(inventory);
    List<CardCopy> selected = new ArrayList<>();
    for (int i = 0; i < 5; i++) selected.add(available.remove(transcript.nextInt(label + "-" + i, available.size())));
    return new Deck(selected);
  }

  private static Set<CardCopyId> selectRevealed(List<HandCard> hand, PlayerSeat seat,
      RuleSet rules, RandomTranscript transcript) {
    if (!rules.has(Rule.THREE_OPEN)) return Set.of();
    List<HandCard> remaining = new ArrayList<>(hand);
    Set<CardCopyId> selected = new HashSet<>();
    for (int i = 0; i < 3; i++) {
      int index = transcript.nextInt("three-open-" + seat + "-" + i, remaining.size());
      selected.add(remaining.remove(index).copyId());
    }
    return Set.copyOf(selected);
  }

  public static MatchTransition apply(MatchState state, MatchCommand command) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(command, "command is required");
    if (state.hasCommand(command.commandId())) return rejected(state, MatchErrorCode.DUPLICATE_COMMAND_ID);
    if (state.isFinished()) return rejected(state, MatchErrorCode.MATCH_FINISHED);
    if (command.expectedRevision() != state.revision()) return rejected(state, MatchErrorCode.STALE_REVISION);
    if (command.actor() != state.currentSeat()) return rejected(state, MatchErrorCode.WRONG_ACTOR);
    if (command.commandTime().isBefore(state.turnStartedAt())) return rejected(state, MatchErrorCode.INVALID_COMMAND);
    if (command instanceof MatchCommand.PlaceCard place) return applyPlacement(state, place);
    return applyTimeout(state, (MatchCommand.Timeout) command);
  }

  private static MatchTransition applyPlacement(MatchState state, MatchCommand.PlaceCard command) {
    if (state.isTurnExpired(command.commandTime())) return rejected(state, MatchErrorCode.CLOCK_EXPIRED);
    if (!state.board().isEmpty(command.square())) return rejected(state, MatchErrorCode.OCCUPIED_SQUARE);
    if (state.ruleSet().has(Rule.ORDER) && !state.hand(command.actor()).isEmpty()
        && !state.hand(command.actor()).getFirst().copyId().equals(command.copyId())) {
      return rejected(state, MatchErrorCode.INVALID_COMMAND);
    }
    if (state.ruleSet().has(Rule.CHAOS) && !chaosChoice(state, command.actor()).equals(command.copyId())) {
      return rejected(state, MatchErrorCode.INVALID_COMMAND);
    }
    HandCard played = null;
    for (HandCard card : state.hand(command.actor())) {
      if (card.copyId().equals(command.copyId())) { played = card; break; }
    }
    if (played == null) {
      boolean wasPlayed = state.board().occupiedSquares().stream()
          .map(state.board()::copyAt).anyMatch(copy -> copy.copyId().equals(command.copyId()));
      return rejected(state, wasPlayed ? MatchErrorCode.ALREADY_PLAYED_COPY : MatchErrorCode.UNKNOWN_COPY);
    }
    return place(state, command.actor(), played, command.square(), command.commandId(), command.commandTime());
  }

  private static MatchTransition applyTimeout(MatchState state, MatchCommand.Timeout command) {
    if (!state.isTurnExpired(command.commandTime())) return rejected(state, MatchErrorCode.TIMEOUT_NOT_DUE);
    List<HandCard> hand = state.hand(command.actor());
    List<Square> empty = state.board().emptySquares();
    if (hand.isEmpty() || empty.isEmpty()) return rejected(state, MatchErrorCode.INVALID_COMMAND);
    HandCard card = state.ruleSet().has(Rule.CHAOS) ? find(state, command.actor(), chaosChoice(state, command.actor())) : hand.getFirst();
    return place(state, command.actor(), card, empty.getFirst(), command.commandId(), command.commandTime());
  }

  private static CardCopyId chaosChoice(MatchState state, PlayerSeat seat) {
    String label = "chaos-" + state.revision() + "-" + seat;
    for (RandomTranscript.Decision decision : state.randomDecisions()) {
      if (decision.label().equals(label)) return state.hand(seat).get(decision.value() % state.hand(seat).size()).copyId();
    }
    int index = state.transcript().nextInt(label, state.hand(seat).size());
    return state.hand(seat).get(index).copyId();
  }

  private static HandCard find(MatchState state, PlayerSeat seat, CardCopyId copyId) {
    return state.hand(seat).stream().filter(card -> card.copyId().equals(copyId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown chaos card"));
  }
  private static MatchTransition place(MatchState state, PlayerSeat actor, HandCard played,
      Square square, UUID commandId, Instant commandTime) {
    Map<PlayerSeat, Long> banks = new EnumMap<>(PlayerSeat.class);
    banks.putAll(state.bankMillis());
    long elapsed = elapsedMillis(state.turnStartedAt(), commandTime);
    long charge = Math.max(0L, elapsed - MatchState.GRACE_MILLIS);
    banks.put(actor, Math.max(0L, state.remainingBankMillis(actor) - charge));
    PlacementResolver.Resolution resolution = PlacementResolver.resolve(state.board(), state.catalog(),
        state.ruleSet(), played.copy(), actor, square);
    Board board = resolution.board();
    List<String> events = new ArrayList<>();
    events.add("CARD_PLACED");
    events.addAll(resolution.events());

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
      if (result.outcome() == MatchResult.Outcome.DRAW && state.ruleSet().has(Rule.SUDDEN_DEATH)
          && state.consecutiveDraws() < 4) {
        Map<PlayerSeat, List<HandCard>> fresh = freshHands(state.catalog(), board, hands);
        MatchState completed = state.next(board, hands, actor, state.revision() + 1, result, banks,
            commandTime, commandIds);
        PlayerSeat first = completed.transcript().nextInt(
            "sudden-death-first-" + (completed.consecutiveDraws() + 1), 2) == 0
                ? PlayerSeat.ONE : PlayerSeat.TWO;
        events.add("SUDDEN_DEATH_RESTART");
        return accepted(completed.restartAfterDraw(fresh, first, commandTime), events);
      }
      events.add("MATCH_FINISHED");
    }
    PlayerSeat nextSeat = result == null ? actor.opponent() : actor;
    MatchState next = state.next(board, hands, nextSeat, state.revision() + 1, result, banks,
        commandTime, commandIds);
    return accepted(next, events);
  }

  private static Map<PlayerSeat, List<HandCard>> freshHands(CardCatalog catalog, Board board,
      Map<PlayerSeat, List<HandCard>> remaining) {
    Map<PlayerSeat, List<HandCard>> result = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) result.put(seat, new ArrayList<>(remaining.get(seat)));
    for (Board.Cell cell : board.cells()) if (cell != null) {
      CardDefinition definition = catalog.require(cell.cardId());
      result.get(cell.owner()).add(new HandCard(cell.copy(), definition));
    }
    for (PlayerSeat seat : PlayerSeat.values()) result.put(seat, List.copyOf(result.get(seat)));
    return result;
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
