package org.aincraft.mobmatrix.bot;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlacementResolver;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.RandomTranscript;
import org.aincraft.mobmatrix.domain.Rule;
import org.aincraft.mobmatrix.domain.Square;

/** Deterministic, immutable-state heuristic policy for Triple Triad. */
public final class HeuristicBot implements BotMovePolicy {
  private static final Comparator<Candidate> SCORE_ORDER = Comparator
      .comparing(Candidate::score)
      .thenComparing(candidate -> candidate.command().copyId().value().toString())
      .thenComparingInt(candidate -> candidate.command().square().index());

  @Override
  public Decision choose(MatchState state, PlayerSeat seat, RandomTranscript transcript, Instant now) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(seat, "seat is required");
    Objects.requireNonNull(transcript, "transcript is required");
    Objects.requireNonNull(now, "time is required");

    MatchProjection projection = MatchProjection.forViewer(state, seat, now);
    if (projection.currentSeat() != seat || projection.legalCopyIds().isEmpty()
        || projection.legalSquares().isEmpty()) {
      return new NoLegalMove(seat, state.revision());
    }

    List<HandCard> ownCards = state.hand(seat);
    List<Candidate> candidates = new ArrayList<>();
    for (CardCopyId copyId : projection.legalCopyIds()) {
      HandCard card = ownCards.stream().filter(item -> item.copyId().equals(copyId)).findFirst().orElse(null);
      if (card == null) continue;
      for (Square square : projection.legalSquares()) {
        MatchCommand.PlaceCard command = new MatchCommand.PlaceCard(seat, copyId, square,
            state.revision(), commandId(state, seat, copyId, square, now), now);
        MatchEngine.MatchTransition transition = MatchEngine.apply(state, command);
        if (!transition.accepted()) continue;
        PlacementResolver.Resolution resolution = PlacementResolver.resolve(state.board(), state.catalog(),
            state.ruleSet(), card.copy(), seat, square);
        candidates.add(new Candidate(command, transition, score(state, seat, transition, resolution)));
      }
    }
    if (candidates.isEmpty()) return new NoLegalMove(seat, state.revision());

    Score bestScore = candidates.stream().map(Candidate::score).max(Score::compareTo).orElseThrow();
    List<Candidate> best = candidates.stream().filter(candidate -> candidate.score().equals(bestScore)).toList();
    RandomTranscript tieBreaker = transcript.fork();
    int selected = tieBreaker.nextInt("bot-tie-" + state.revision() + "-" + seat, best.size());
    return new Command(best.stream().sorted(SCORE_ORDER.reversed()).toList().get(selected).command());
  }

  private static Score score(MatchState before, PlayerSeat seat, MatchEngine.MatchTransition transition,
      PlacementResolver.Resolution resolution) {
    MatchState after = transition.state();
    long control = after.board().countOwnedBy(seat) - after.board().countOwnedBy(seat.opponent());
    int captures = resolution.captures().size();
    int sourceWeight = resolution.captures().stream().mapToInt(capture -> switch (capture.source()) {
      case STANDARD -> 1;
      case SAME -> 2;
      case PLUS -> 3;
      case COMBO -> 4;
    }).sum();
    int corners = List.of(Square.NORTH_WEST, Square.NORTH_EAST, Square.SOUTH_WEST, Square.SOUTH_EAST)
        .stream().mapToInt(square -> after.board().ownerOf(square) == seat ? 1 : 0).sum();
    int edges = List.of(Square.NORTH, Square.WEST, Square.EAST, Square.SOUTH)
        .stream().mapToInt(square -> after.board().ownerOf(square) == seat ? 1 : 0).sum();
    int mobility = after.board().emptySquares().size()
        * (after.hand(seat).isEmpty() ? 0 : legalCopyCount(after, seat));
    return new Score(control, captures, sourceWeight, corners, edges, mobility);
  }

  private static int legalCopyCount(MatchState state, PlayerSeat seat) {
    if (state.ruleSet().has(Rule.ORDER)) return state.hand(seat).isEmpty() ? 0 : 1;
    if (state.ruleSet().has(Rule.CHAOS)) return state.chaosChoice(seat) == null ? 0 : 1;
    return state.hand(seat).size();
  }

  private static UUID commandId(MatchState state, PlayerSeat seat, CardCopyId copyId, Square square, Instant now) {
    String identity = state.matchId() + "|" + state.revision() + "|" + seat + "|"
        + copyId + "|" + square.index() + "|" + now;
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
  }

  private record Candidate(MatchCommand.PlaceCard command, MatchEngine.MatchTransition transition, Score score) {}

  private record Score(long control, int captures, int sourceWeight, int corners, int edges, int mobility)
      implements Comparable<Score> {
    @Override
    public int compareTo(Score other) {
      int result = Long.compare(control, other.control);
      if (result != 0) return result;
      result = Integer.compare(captures, other.captures);
      if (result != 0) return result;
      result = Integer.compare(sourceWeight, other.sourceWeight);
      if (result != 0) return result;
      result = Integer.compare(corners, other.corners);
      if (result != 0) return result;
      result = Integer.compare(edges, other.edges);
      if (result != 0) return result;
      return Integer.compare(mobility, other.mobility);
    }
  }
}
