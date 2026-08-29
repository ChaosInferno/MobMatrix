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

  MatchState(MatchId matchId, CardCatalog catalog, MatchRules rules, Board board,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat currentSeat, long revision,
      MatchResult result, Map<PlayerSeat, Long> bankMillis, Instant turnStartedAt,
      Instant graceDeadline, Instant turnDeadline, Set<java.util.UUID> commandIds, long seed) {
    this.matchId = Objects.requireNonNull(matchId);
    this.catalog = Objects.requireNonNull(catalog);
    this.rules = Objects.requireNonNull(rules);
    this.board = Objects.requireNonNull(board);
    EnumMap<PlayerSeat, List<HandCard>> handCopy = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) handCopy.put(seat, List.copyOf(Objects.requireNonNull(hands.get(seat))));
    this.hands = Map.copyOf(handCopy);
    this.currentSeat = Objects.requireNonNull(currentSeat);
    if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    this.revision = revision;
    this.result = result;
    this.bankMillis = Map.copyOf(new EnumMap<>(bankMillis));
    this.turnStartedAt = Objects.requireNonNull(turnStartedAt);
    this.graceDeadline = Objects.requireNonNull(graceDeadline);
    this.turnDeadline = Objects.requireNonNull(turnDeadline);
    this.commandIds = Set.copyOf(new HashSet<>(commandIds));
    this.seed = seed;
  }

  static MatchState initial(MatchId id, CardCatalog catalog, MatchRules rules,
      Map<PlayerSeat, List<HandCard>> hands, PlayerSeat firstSeat, Instant start, long seed) {
    return new MatchState(id, catalog, rules, Board.empty(), hands, firstSeat, 0, null,
        Map.of(PlayerSeat.ONE, INITIAL_BANK_MILLIS, PlayerSeat.TWO, INITIAL_BANK_MILLIS),
        start, start.plusMillis(GRACE_MILLIS), start.plusMillis(GRACE_MILLIS + INITIAL_BANK_MILLIS), Set.of(), seed);
  }

  public MatchId matchId() { return matchId; }
  public MatchId id() { return matchId; }
  public CardCatalog catalog() { return catalog; }
  public MatchRules rules() { return rules; }
  public Board board() { return board; }
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
    Objects.requireNonNull(now, "time is required");
    long stored = remainingBankMillis(seat);
    if (seat != currentSeat) return stored;
    long charge = Math.max(0L, elapsedMillis(turnStartedAt, now) - GRACE_MILLIS);
    return Math.max(0L, stored - charge);
  }
  public long remainingBankMillis(PlayerSeat seat) { return bankMillis.get(Objects.requireNonNull(seat)); }
  public long remainingGraceMillis(PlayerSeat seat, Instant now) {
    Objects.requireNonNull(seat);
    if (seat != currentSeat || now.compareTo(graceDeadline) >= 0) return 0;
    return Duration.between(now, graceDeadline).toMillis();
  }
  public long remainingTurnMillis(PlayerSeat seat, Instant now) {
    Objects.requireNonNull(seat);
    if (seat != currentSeat || now.compareTo(turnDeadline) >= 0) return 0;
    return Duration.between(now, turnDeadline).toMillis();
  }
  public Instant turnStartedAt() { return turnStartedAt; }
  public Instant graceDeadline() { return graceDeadline; }
  public Instant turnDeadline() { return turnDeadline; }
  private static long elapsedMillis(Instant start, Instant end) {
    return Math.max(0L, Duration.between(start, end).toMillis());
  }
  boolean hasCommand(java.util.UUID id) { return commandIds.contains(id); }
  long seed() { return seed; }
  Set<java.util.UUID> commandIds() { return commandIds; }

  public MatchState withCurrentSeat(PlayerSeat seat) {
    return new MatchState(matchId, catalog, rules, board, hands, seat, revision, result, bankMillis,
        turnStartedAt, graceDeadline, turnDeadline, commandIds, seed);
  }

  MatchState next(Board nextBoard, Map<PlayerSeat, List<HandCard>> nextHands, PlayerSeat nextSeat,
      long nextRevision, MatchResult nextResult, Map<PlayerSeat, Long> nextBanks,
      Instant nextStarted, Set<java.util.UUID> nextCommands) {
    return new MatchState(matchId, catalog, rules, nextBoard, nextHands, nextSeat, nextRevision,
        nextResult, nextBanks, nextStarted, nextStarted.plusMillis(GRACE_MILLIS),
        nextStarted.plusMillis(GRACE_MILLIS + nextBanks.get(nextSeat)), nextCommands, seed);
  }
}
