package org.aincraft.mobmatrix.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
      MatchRules rules, long seed, Instant startTime, List<CardCopy> oneInventory,
      List<CardCopy> twoInventory, List<Integer> oneDraftChoices, List<Integer> twoDraftChoices) {
    public StartMatch {
      Objects.requireNonNull(matchId, "match id is required");
      Objects.requireNonNull(oneDeck, "one deck is required");
      Objects.requireNonNull(twoDeck, "two deck is required");
      Objects.requireNonNull(catalog, "catalog is required");
      Objects.requireNonNull(rules, "rules are required");
      Objects.requireNonNull(startTime, "start time is required");
      oneInventory = List.copyOf(Objects.requireNonNull(oneInventory, "one inventory is required"));
      twoInventory = List.copyOf(Objects.requireNonNull(twoInventory, "two inventory is required"));
      oneDraftChoices = immutableChoices(oneDraftChoices);
      twoDraftChoices = immutableChoices(twoDraftChoices);
    }

    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        MatchRules rules, long seed, Instant startTime) {
      this(matchId, oneDeck, twoDeck, catalog, rules, seed, startTime,
          oneDeck.copies(), twoDeck.copies(), List.of(0, 0, 0), List.of(0, 0, 0));
    }

    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        MatchRules rules, long seed, Instant startTime, List<CardCopy> oneInventory,
        List<CardCopy> twoInventory) {
      this(matchId, oneDeck, twoDeck, catalog, rules, seed, startTime,
          oneInventory, twoInventory, List.of(0, 0, 0), List.of(0, 0, 0));
    }

    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        RuleSet rules, long seed, Instant startTime) {
      this(matchId, oneDeck, twoDeck, catalog, Objects.requireNonNull(rules).asMatchRules(), seed, startTime);
    }

    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        RuleSet rules, long seed, Instant startTime, List<CardCopy> oneInventory,
        List<CardCopy> twoInventory, List<Integer> oneDraftChoices, List<Integer> twoDraftChoices) {
      this(matchId, oneDeck, twoDeck, catalog, Objects.requireNonNull(rules).asMatchRules(), seed,
          startTime, oneInventory, twoInventory, oneDraftChoices, twoDraftChoices);
    }

    private static List<Integer> immutableChoices(List<Integer> choices) {
      return choices == null ? List.of() : List.copyOf(choices);
    }

    public MatchId id() { return matchId; }
 
    public StartMatch(MatchId matchId, Deck oneDeck, Deck twoDeck, CardCatalog catalog,
        RuleSet rules, long seed, Instant startTime, List<CardCopy> oneInventory,
        List<CardCopy> twoInventory) {
      this(matchId, oneDeck, twoDeck, catalog, Objects.requireNonNull(rules).asMatchRules(), seed,
          startTime, oneInventory, twoInventory, List.of(0, 0, 0), List.of(0, 0, 0));
    }
    public RuleSet ruleSet() { return rules.asRuleSet(); }
  }

  public record MatchTransition(MatchState state, List<String> events, MatchRejection rejection) {
    public MatchTransition {
      events = List.copyOf(Objects.requireNonNull(events, "events are required"));
      if (rejection == null) Objects.requireNonNull(state, "accepted transition state is required");
      if (rejection != null && !events.isEmpty()) {
        throw new IllegalArgumentException("rejection cannot have events");
      }
    }

    public boolean accepted() { return rejection == null; }
    public boolean rejected() { return rejection != null; }
  }

  public static MatchTransition start(StartMatch start) {
    Objects.requireNonNull(start, "start is required");
    RandomTranscript transcript = new RandomTranscript(start.seed());
    RuleSet activeRules;
    try {
      activeRules = RuleResolver.resolve(start.ruleSet(), transcript);
    } catch (IllegalArgumentException exception) {
      return startRejected(MatchErrorCode.INVALID_COMMAND, exception.getMessage());
    }

    if (activeRules.has(Rule.RANDOM)) {
      MatchErrorCode oneError = validateRandomPool(start.oneInventory(), start.catalog());
      if (oneError != null) return startRejected(oneError, oneError.name());
      MatchErrorCode twoError = validateRandomPool(start.twoInventory(), start.catalog());
      if (twoError != null) return startRejected(twoError, twoError.name());
    }

    Map<PlayerSeat, DraftOfferings.SeatOffers> draftOffers = new EnumMap<>(PlayerSeat.class);
    Map<PlayerSeat, DraftOfferings.Selection> draftSelections = new EnumMap<>(PlayerSeat.class);
    Deck oneDeck = start.oneDeck();
    Deck twoDeck = start.twoDeck();
    try {
      if (activeRules.has(Rule.DRAFT)) {
        DraftOfferings.SeatOffers oneOffers = DraftOfferings.generate(PlayerSeat.ONE, start.catalog(), transcript);
        DraftOfferings.SeatOffers twoOffers = DraftOfferings.generate(PlayerSeat.TWO, start.catalog(), transcript);
        draftOffers.put(PlayerSeat.ONE, oneOffers);
        draftOffers.put(PlayerSeat.TWO, twoOffers);
        DraftOfferings.Selection oneSelection = DraftOfferings.select(oneOffers, start.oneDraftChoices());
        DraftOfferings.Selection twoSelection = DraftOfferings.select(twoOffers, start.twoDraftChoices());
        recordDraftChoices(transcript, PlayerSeat.ONE, oneSelection.offeringChoices());
        recordDraftChoices(transcript, PlayerSeat.TWO, twoSelection.offeringChoices());
        draftSelections.put(PlayerSeat.ONE, oneSelection);
        draftSelections.put(PlayerSeat.TWO, twoSelection);
        oneDeck = oneSelection.deck();
        twoDeck = twoSelection.deck();
      }
      if (activeRules.has(Rule.RANDOM)) {
        oneDeck = randomDeck(start.oneInventory(), start.catalog(), transcript, "random-one");
        twoDeck = randomDeck(start.twoInventory(), start.catalog(), transcript, "random-two");
      }
    } catch (IllegalArgumentException exception) {
      return startRejected(activeRules.has(Rule.DRAFT) ? MatchErrorCode.DRAFT_INVALID_SELECTION
          : MatchErrorCode.INVALID_COMMAND, exception.getMessage());
    }

    Map<CardCopyId, PlayerSeat> swapOwners = new HashMap<>();
    if (activeRules.has(Rule.SWAP)) {
      List<CardCopy> one = new ArrayList<>(oneDeck.copies());
      List<CardCopy> two = new ArrayList<>(twoDeck.copies());
      int oneIndex = transcript.nextInt("swap-one", one.size());
      int twoIndex = transcript.nextInt("swap-two", two.size());
      CardCopy oneCopy = one.get(oneIndex);
      CardCopy twoCopy = two.get(twoIndex);
      one.set(oneIndex, twoCopy);
      two.set(twoIndex, oneCopy);
      swapOwners.put(oneCopy.copyId(), PlayerSeat.ONE);
      swapOwners.put(twoCopy.copyId(), PlayerSeat.TWO);
      oneDeck = new Deck(one);
      twoDeck = new Deck(two);
    }

    Map<PlayerSeat, List<HandCard>> hands = new EnumMap<>(PlayerSeat.class);
    hands.put(PlayerSeat.ONE, hand(oneDeck, start.catalog()));
    hands.put(PlayerSeat.TWO, hand(twoDeck, start.catalog()));
    Map<PlayerSeat, Set<CardCopyId>> revealed = new EnumMap<>(PlayerSeat.class);
    revealed.put(PlayerSeat.ONE, selectRevealed(hands.get(PlayerSeat.ONE), PlayerSeat.ONE, activeRules,
        transcript, "three-open"));
    revealed.put(PlayerSeat.TWO, selectRevealed(hands.get(PlayerSeat.TWO), PlayerSeat.TWO, activeRules,
        transcript, "three-open"));

    PlayerSeat first = transcript.nextInt("first-seat", 2) == 0 ? PlayerSeat.ONE : PlayerSeat.TWO;
    Map<PlayerSeat, CardCopyId> chaos = new EnumMap<>(PlayerSeat.class);
    resolveChaos(transcript, activeRules, hands, first, chaos, "chaos");
    return accepted(MatchState.initial(start.matchId(), start.catalog(), activeRules.asMatchRules(), hands,
        first, start.startTime(), start.seed(), transcript, revealed, swapOwners,
        draftOffers, draftSelections, chaos), List.of("MATCH_STARTED"));
  }

  public static MatchTransition start(StartMatch start, List<Integer> oneDraftChoices,
      List<Integer> twoDraftChoices) {
    Objects.requireNonNull(start, "start is required");
    StartMatch configured = new StartMatch(start.matchId(), start.oneDeck(), start.twoDeck(), start.catalog(),
        start.rules(), start.seed(), start.startTime(), start.oneInventory(), start.twoInventory(),
        oneDraftChoices, twoDraftChoices);
    return start(configured);
  }

  private static MatchErrorCode validateRandomPool(List<CardCopy> inventory, CardCatalog catalog) {
    Set<CardCopyId> ids = new HashSet<>();
    int usable = 0;
    for (CardCopy copy : inventory) {
      if (copy == null || !ids.add(copy.copyId())) return MatchErrorCode.RANDOM_DUPLICATE_COPY;
      try {
        catalog.require(copy.cardId());
        usable++;
      } catch (IllegalArgumentException ignored) {
        // An unknown definition is not a usable physical copy.
      }
    }
    return usable < 5 ? MatchErrorCode.RANDOM_INSUFFICIENT_POOL : null;
  }

  private static Deck randomDeck(List<CardCopy> inventory, CardCatalog catalog,
      RandomTranscript transcript, String label) {
    List<CardCopy> available = new ArrayList<>();
    for (CardCopy copy : inventory) {
      try {
        catalog.require(copy.cardId());
        available.add(copy);
      } catch (IllegalArgumentException ignored) {
        // Setup validation permits unknown inventory entries but RANDOM cannot play them.
      }
    }
    List<CardCopy> selected = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      selected.add(available.remove(transcript.nextInt(label + "-" + index, available.size())));
    }
    return new Deck(selected);
  }
 
  private static void recordDraftChoices(RandomTranscript transcript, PlayerSeat seat,
      List<Integer> choices) {
    for (int index = 0; index < choices.size(); index++) {
      transcript.record("draft-choice-" + seat + "-" + index, 5, choices.get(index));
    }
  }

  private static Set<CardCopyId> selectRevealed(List<HandCard> hand, PlayerSeat seat,
      RuleSet rules, RandomTranscript transcript, String label) {
    if (!rules.has(Rule.THREE_OPEN)) return Set.of();
    List<HandCard> remaining = new ArrayList<>(hand);
    Set<CardCopyId> selected = new HashSet<>();
    for (int index = 0; index < 3; index++) {
      int selectedIndex = transcript.nextInt(label + "-" + seat + "-" + index, remaining.size());
      selected.add(remaining.remove(selectedIndex).copyId());
    }
    return Set.copyOf(selected);
  }

  private static void resolveChaos(RandomTranscript transcript, RuleSet rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat seat,
      Map<PlayerSeat, CardCopyId> choices, String label) {
    if (!rules.has(Rule.CHAOS)) return;
    List<HandCard> hand = hands.get(seat);
    if (hand.isEmpty()) return;
    int index = transcript.nextInt(label + "-" + seat + "-" + choices.size(), hand.size());
    choices.put(seat, hand.get(index).copyId());
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
    if (state.ruleSet().has(Rule.CHAOS)
        && !Objects.equals(state.chaosChoice(command.actor()), command.copyId())) {
      return rejected(state, MatchErrorCode.INVALID_COMMAND);
    }
    HandCard played = findHandCard(state.hand(command.actor()), command.copyId());
    if (played == null) {
      boolean wasPlayed = state.board().occupiedSquares().stream()
          .map(state.board()::copyAt).anyMatch(copy -> copy.copyId().equals(command.copyId()));
      return rejected(state, wasPlayed ? MatchErrorCode.ALREADY_PLAYED_COPY : MatchErrorCode.UNKNOWN_COPY);
    }
    return place(state, command.actor(), played, command.square(), command.commandId(), command.commandTime(),
        state.transcript());
  }

  private static MatchTransition applyTimeout(MatchState state, MatchCommand.Timeout command) {
    if (!state.isTurnExpired(command.commandTime())) return rejected(state, MatchErrorCode.TIMEOUT_NOT_DUE);
    List<HandCard> hand = state.hand(command.actor());
    List<Square> empty = state.board().emptySquares();
    if (hand.isEmpty() || empty.isEmpty()) return rejected(state, MatchErrorCode.INVALID_COMMAND);

    RandomTranscript transcript = state.transcript();
    int cardIndex = state.ruleSet().has(Rule.CHAOS)
        ? hand.indexOf(findHandCard(hand, state.chaosChoice(command.actor()))) : 0;
    cardIndex = transcript.record("timeout-card-" + state.revision() + "-" + command.actor(), hand.size(), cardIndex);
    int squareIndex = transcript.record("timeout-square-" + state.revision() + "-" + command.actor(),
        empty.size(), 0);
    return place(state, command.actor(), hand.get(cardIndex), empty.get(squareIndex), command.commandId(),
        command.commandTime(), transcript);
  }

  private static HandCard findHandCard(List<HandCard> hand, CardCopyId copyId) {
    if (copyId == null) return null;
    return hand.stream().filter(card -> card.copyId().equals(copyId)).findFirst().orElse(null);
  }

  private static MatchTransition place(MatchState state, PlayerSeat actor, HandCard played,
      Square square, UUID commandId, Instant commandTime, RandomTranscript transcript) {
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
        RandomTranscript nextTranscript = transcript;
        int drawNumber = state.consecutiveDraws() + 1;
        PlayerSeat first = nextTranscript.nextInt("sudden-death-first-" + drawNumber, 2) == 0
            ? PlayerSeat.ONE : PlayerSeat.TWO;
        Map<PlayerSeat, Set<CardCopyId>> freshRevealed = new EnumMap<>(PlayerSeat.class);
        for (PlayerSeat seat : PlayerSeat.values()) {
          freshRevealed.put(seat, selectRevealed(fresh.get(seat), seat, state.ruleSet(), nextTranscript,
              "sudden-death-three-open-" + drawNumber));
        }
        Map<PlayerSeat, CardCopyId> freshChaos = new EnumMap<>(PlayerSeat.class);
        resolveChaos(nextTranscript, state.ruleSet(), fresh, first, freshChaos,
            "sudden-death-chaos-" + drawNumber);
        events.add("SUDDEN_DEATH_RESTART");
        return accepted(state.restartAfterDraw(fresh, first, commandTime, commandIds, nextTranscript,
            freshRevealed, freshChaos), events);
      }
      events.add("MATCH_FINISHED");
    }

    PlayerSeat nextSeat = result == null ? actor.opponent() : actor;
    Map<PlayerSeat, Set<CardCopyId>> revealed = state.revealedCardsMap();
    Map<PlayerSeat, CardCopyId> chaos = new EnumMap<>(PlayerSeat.class);
    resolveChaos(transcript, state.ruleSet(), hands, nextSeat, chaos, "chaos-" + (state.revision() + 1));
    int draws = state.consecutiveDraws()
        + (result != null && result.outcome() == MatchResult.Outcome.DRAW ? 1 : 0);
    MatchState next = state.next(board, hands, nextSeat, state.revision() + 1, result, banks,
        commandTime, commandIds, transcript, revealed, chaos, draws);
    return accepted(next, events);
  }

  private static Map<PlayerSeat, List<HandCard>> freshHands(CardCatalog catalog, Board board,
      Map<PlayerSeat, List<HandCard>> remaining) {
    Map<PlayerSeat, List<HandCard>> result = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) result.put(seat, new ArrayList<>(remaining.get(seat)));
    for (Board.Cell cell : board.cells()) if (cell != null) {
      result.get(cell.owner()).add(new HandCard(cell.copy(), catalog.require(cell.cardId())));
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

  private static MatchTransition startRejected(MatchErrorCode code, String message) {
    return new MatchTransition(null, List.of(), new MatchRejection(code, message));
  }
}
