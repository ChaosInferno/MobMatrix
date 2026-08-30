package org.aincraft.mobmatrix.application;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchResult;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Rule;
import org.aincraft.mobmatrix.domain.RuleSet;
import org.aincraft.mobmatrix.domain.Square;

/**
 * Immutable application-facing match view.
 *
 * <p>The view is a stable adapter around one committed, player-specific {@link MatchProjection}.
 * It deliberately exposes no executable match state or mutation operation. MapGui and other
 * adapters should use {@link #from(MatchProjection)} (or the rules overload) after the application
 * service commits a transition.
 */
public final class MatchView {
  private final MatchProjection projection;
  private final Set<Rule> rules;

  /** Creates a view from a committed projection with no rule badges. */
  public MatchView(MatchProjection projection) {
    this(projection, Set.of());
  }

  /** Creates a view from a committed projection and immutable rule badges. */
  public MatchView(MatchProjection projection, RuleSet rules) {
    this(projection, Objects.requireNonNull(rules, "rules are required").rules());
  }

  /** Creates a view from a committed projection and immutable rule badges. */
  public MatchView(MatchProjection projection, MatchRules rules) {
    this(projection, Objects.requireNonNull(rules, "rules are required").rules());
  }

  /** Creates a view from a committed projection and immutable rule badges. */
  public MatchView(MatchProjection projection, Collection<Rule> rules) {
    this.projection = Objects.requireNonNull(projection, "projection is required");
    this.rules = Set.copyOf(Objects.requireNonNull(rules, "rules are required"));
  }

  /** Creates a view from a committed projection with no rule badges. */
  public static MatchView from(MatchProjection projection) {
    return new MatchView(projection);
  }

  /** Creates a view from a committed projection and its selected rule set. */
  public static MatchView from(MatchProjection projection, RuleSet rules) {
    return new MatchView(projection, rules);
  }

  /** Creates a view from a committed projection and its selected match rules. */
  public static MatchView from(MatchProjection projection, MatchRules rules) {
    return new MatchView(projection, rules);
  }

  /** Creates a view from a committed projection and its selected rule badges. */
  public static MatchView from(MatchProjection projection, Collection<Rule> rules) {
    return new MatchView(projection, rules);
  }

  /** Returns the exact immutable projection represented by this view. */
  public MatchProjection projection() {
    return projection;
  }

  /** Returns board cells in explicit row-major order, indexes 0 through 8. */
  public List<Board.Cell> board() {
    return projection.boardCells();
  }

  /** Compatibility alias for {@link #board()}. */
  public List<Board.Cell> boardCells() {
    return board();
  }

  /** Returns the viewer's immutable physical hand. */
  public List<HandCard> hand() {
    return projection.hand();
  }

  /** Compatibility alias for {@link #hand()}. */
  public List<HandCard> viewerHand() {
    return hand();
  }

  /** Returns opponent slots; masked slots contain no card or copy identity. */
  public List<MatchProjection.HandSlot> opponentHand() {
    return projection.opponentHand();
  }

  /** Compatibility alias for {@link #opponentHand()}. */
  public List<MatchProjection.HandSlot> opponentHandSlots() {
    return opponentHand();
  }

  public PlayerSeat currentSeat() {
    return projection.currentSeat();
  }

  public List<CardCopyId> legalCopyIds() {
    return projection.legalCopyIds();
  }

  public List<Square> legalSquares() {
    return projection.legalSquares();
  }

  public long revision() {
    return projection.revision();
  }

  public MatchResult result() {
    return projection.result();
  }

  public boolean hasResult() {
    return result() != null;
  }

  public long bankMillis() {
    return projection.viewerBankMillis();
  }

  public long graceMillis() {
    return projection.viewerGraceMillis();
  }

  public long turnMillis() {
    return projection.viewerTurnMillis();
  }

  public long viewerBankMillis() {
    return bankMillis();
  }

  public long viewerGraceMillis() {
    return graceMillis();
  }

  public long viewerTurnMillis() {
    return turnMillis();
  }

  /** Returns immutable rule badges supplied by the application adapter. */
  public Set<Rule> rules() {
    return rules;
  }

  /** Returns immutable rule badges as a domain rule set. */
  public RuleSet ruleSet() {
    return new RuleSet(rules);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof MatchView that)) return false;
    return projection.equals(that.projection) && rules.equals(that.rules);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projection, rules);
  }

  @Override
  public String toString() {
    return "MatchView[revision=" + revision() + ", result=" + result() + ", rules=" + rules + "]";
  }
}
