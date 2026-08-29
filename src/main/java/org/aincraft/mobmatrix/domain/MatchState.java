package org.aincraft.mobmatrix.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete immutable state needed to apply standard match commands. */
public final class MatchState {
  static final long INITIAL_BANK_MILLIS = 90_000L;
  static final long GRACE_MILLIS = 5_000L;

  private final MatchId matchId;
  private final CardCatalog catalog;
  private final MatchRules rules;
  private final RuleSet ruleSet;
  private final Board board;
  private final Map<PlayerSeat, List<HandCard>> hands;
  private final PlayerSeat currentSeat;
  private final long revision;
  private final MatchResult result;
  private final Map<PlayerSeat, Long> bankMillis;
  private final Instant turnStartedAt;
  private final Instant graceDeadline;
  private final Instant turnDeadline;
  private final Set<java.util.UUID> commandIds;
  private final long seed;
  private final Map<PlayerSeat, Set<CardCopyId>> revealedCards;
  private final Map<CardCopyId, PlayerSeat> temporarySwapOwners;
  private final int consecutiveDraws;
  private final RandomTranscript transcript;

  MatchState(MatchId matchId, CardCatalog catalog, MatchRules rules, Board board,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat currentSeat, long revision,
      MatchResult result, Map<PlayerSeat, Long> bankMillis, Instant turnStartedAt,
      Instant graceDeadline, Instant turnDeadline, Set<java.util.UUID> commandIds, long seed,
      RandomTranscript transcript, Map<PlayerSeat, Set<CardCopyId>> revealedCards,
      Map<CardCopyId, PlayerSeat> temporarySwapOwners, int consecutiveDraws) {
    this.matchId = Objects.requireNonNull(matchId);
    this.catalog = Objects.requireNonNull(catalog);
    this.rules = Objects.requireNonNull(rules);
    this.ruleSet = rules.asRuleSet();
    this.transcript = Objects.requireNonNull(transcript);
    this.board = Objects.requireNonNull(board);
    EnumMap<PlayerSeat, List<HandCard>> handCopy = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) handCopy.put(seat, List.copyOf(Objects.requireNonNull(hands.get(seat))));
    EnumMap<PlayerSeat, Set<CardCopyId>> revealedCopy = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) {
      revealedCopy.put(seat, Set.copyOf(Objects.requireNonNull(revealedCards.getOrDefault(seat, Set.of()))));
    }
    this.revealedCards = Map.copyOf(revealedCopy);
    this.temporarySwapOwners = Map.copyOf(Objects.requireNonNull(temporarySwapOwners));
    this.hands = Map.copyOf(handCopy);
    this.currentSeat = Objects.requireNonNull(currentSeat);
    if (consecutiveDraws < 0) throw new IllegalArgumentException("draw count must not be negative");
    this.consecutiveDraws = consecutiveDraws;
    if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    this.revision = revision;
    this.result = result;
    EnumMap<PlayerSeat, Long> bankCopy = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) {
      bankCopy.put(seat, result == null ? Objects.requireNonNull(bankMillis.get(seat)) : 0L);
    }
    this.bankMillis = Map.copyOf(bankCopy);
    this.turnStartedAt = Objects.requireNonNull(turnStartedAt);
    this.graceDeadline = Objects.requireNonNull(graceDeadline);
    this.turnDeadline = Objects.requireNonNull(turnDeadline);
    this.commandIds = Set.copyOf(new HashSet<>(commandIds));
    this.seed = seed;
  }

  static MatchState initial(MatchId id, CardCatalog catalog, MatchRules rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat firstSeat, Instant start, long seed) {
    return initial(id, catalog, rules, hands, firstSeat, start, seed, new RandomTranscript(seed));
  }

  static MatchState initial(MatchId id, CardCatalog catalog, MatchRules rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat firstSeat, Instant start, long seed,
      RandomTranscript transcript) {
    return initial(id, catalog, rules, hands, firstSeat, start, seed, transcript, Map.of());
  }

  static MatchState initial(MatchId id, CardCatalog catalog, MatchRules rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat firstSeat, Instant start, long seed,
      RandomTranscript transcript, Map<PlayerSeat, Set<CardCopyId>> revealedCards) {
    return initial(id, catalog, rules, hands, firstSeat, start, seed, transcript, revealedCards, Map.of());
  }

  static MatchState initial(MatchId id, CardCatalog catalog, MatchRules rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat firstSeat, Instant start, long seed,
      RandomTranscript transcript, Map<PlayerSeat, Set<CardCopyId>> revealedCards,
      Map<CardCopyId, PlayerSeat> temporarySwapOwners) {
    return new MatchState(id, catalog, rules, Board.empty(), hands, firstSeat, 0, null,
        Map.of(PlayerSeat.ONE, INITIAL_BANK_MILLIS, PlayerSeat.TWO, INITIAL_BANK_MILLIS),
        start, start.plusMillis(GRACE_MILLIS), start.plusMillis(GRACE_MILLIS + INITIAL_BANK_MILLIS),
        Set.of(), seed, transcript, revealedCards, temporarySwapOwners, 0);
  }
  public MatchId matchId() { return matchId; }
  public MatchId id() { return matchId; }
  public CardCatalog catalog() { return catalog; }
  public MatchRules rules() { return rules; }
  public RuleSet ruleSet() { return ruleSet; }
  public List<RandomTranscript.Decision> randomDecisions() { return transcript.decisions(); }
  RandomTranscript transcript() { return transcript; }
  public Board board() { return board; }
  public int consecutiveDraws() { return consecutiveDraws; }
  public Set<CardCopyId> revealedCards(PlayerSeat seat) { return revealedCards.get(Objects.requireNonNull(seat)); }
  public Map<CardCopyId, PlayerSeat> temporarySwapOwners() { return temporarySwapOwners; }
  public Map<PlayerSeat, List<CardCopy>> settlementCopies() {
    EnumMap<PlayerSeat, List<CardCopy>> result = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) result.put(seat, new java.util.ArrayList<>());
    for (PlayerSeat seat : PlayerSeat.values()) {
      for (HandCard card : hand(seat)) result.get(temporarySwapOwners.getOrDefault(card.copyId(), seat)).add(card.copy());
    }
    for (Board.Cell cell : board.cells()) if (cell != null) {
      result.get(temporarySwapOwners.getOrDefault(cell.copy().copyId(), cell.owner())).add(cell.copy());
    }
    EnumMap<PlayerSeat, List<CardCopy>> immutable = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) immutable.put(seat, List.copyOf(result.get(seat)));
    return Map.copyOf(immutable);
  }
  public List<HandCard> hand(PlayerSeat seat) { return hands.get(Objects.requireNonNull(seat)); }
  public List<HandCard> handCards(PlayerSeat seat) { return hand(seat); }
  public List<HandCard> availableCards(PlayerSeat seat) { return hand(seat); }
  public Map<PlayerSeat, List<HandCard>> hands() { return hands; }
  public PlayerSeat currentSeat() { return currentSeat; }
  public long revision() { return revision; }
  public MatchResult result() { return result; }
  public boolean isFinished() { return result != null; }
  public Map<PlayerSeat, Long> bankMillis() { return bankMillis; }
  public long remainingBankMillis(PlayerSeat seat, Instant now) {
    Objects.requireNonNull(seat, "seat is required");
    Objects.requireNonNull(now, "time is required");
    if (isFinished()) return 0L;
    long stored = bankMillis.get(seat);
    if (seat != currentSeat) return stored;
    long charge = Math.max(0L, elapsedMillis(turnStartedAt, now) - GRACE_MILLIS);
    return Math.max(0L, stored - charge);
  }
  public long remainingBankMillis(PlayerSeat seat) {
    Objects.requireNonNull(seat, "seat is required");
    return isFinished() ? 0L : bankMillis.get(seat);
  }
  public long remainingGraceMillis(PlayerSeat seat, Instant now) {
    Objects.requireNonNull(seat, "seat is required");
    Objects.requireNonNull(now, "time is required");
    if (isFinished() || seat != currentSeat || now.compareTo(graceDeadline) >= 0) return 0;
    return Duration.between(now, graceDeadline).toMillis();
  }
  public long remainingTurnMillis(PlayerSeat seat, Instant now) {
    Objects.requireNonNull(seat, "seat is required");
    Objects.requireNonNull(now, "time is required");
    if (isFinished() || seat != currentSeat || now.compareTo(turnDeadline) >= 0) return 0;
    return Duration.between(now, turnDeadline).toMillis();
  }
  public Instant turnStartedAt() { return turnStartedAt; }
  public Instant graceDeadline() { return graceDeadline; }
  public Instant turnDeadline() { return turnDeadline; }
  boolean isTurnExpired(Instant now) {
    Objects.requireNonNull(now, "time is required");
    return !isFinished() && !now.isBefore(turnDeadline);
  }
  private static long elapsedMillis(Instant start, Instant end) {
    return Math.max(0L, Duration.between(start, end).toMillis());
  }
  boolean hasCommand(java.util.UUID id) { return commandIds.contains(id); }
  long seed() { return seed; }
  Set<java.util.UUID> commandIds() { return commandIds; }
  public MatchState withCurrentSeat(PlayerSeat seat) {
    return new MatchState(matchId, catalog, rules, board, hands, seat, revision, result, bankMillis,
        turnStartedAt, graceDeadline, turnDeadline, commandIds, seed, transcript, revealedCards, temporarySwapOwners, consecutiveDraws);
  }

  MatchState next(Board nextBoard, Map<PlayerSeat, List<HandCard>> nextHands, PlayerSeat nextSeat,
      long nextRevision, MatchResult nextResult, Map<PlayerSeat, Long> nextBanks,
      Instant nextStarted, Set<java.util.UUID> nextCommands) {
    return new MatchState(matchId, catalog, rules, nextBoard, nextHands, nextSeat, nextRevision,
        nextResult, nextBanks, nextStarted, nextStarted.plusMillis(GRACE_MILLIS),
        nextStarted.plusMillis(GRACE_MILLIS + nextBanks.get(nextSeat)), nextCommands, seed, transcript, revealedCards, temporarySwapOwners, consecutiveDraws);
  }
  MatchState restartAfterDraw(Map<PlayerSeat, List<HandCard>> freshHands, PlayerSeat firstSeat, Instant start) {
    return new MatchState(matchId, catalog, rules, Board.empty(), freshHands, firstSeat, revision + 1, null,
        Map.of(PlayerSeat.ONE, INITIAL_BANK_MILLIS, PlayerSeat.TWO, INITIAL_BANK_MILLIS), start,
        start.plusMillis(GRACE_MILLIS), start.plusMillis(GRACE_MILLIS + INITIAL_BANK_MILLIS),
        commandIds, seed, transcript, revealedCards, temporarySwapOwners, consecutiveDraws + 1);
  }
}
