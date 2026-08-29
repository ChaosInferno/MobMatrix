package org.aincraft.mobmatrix.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Settled standard-match result. */
public record MatchResult(Map<PlayerSeat, Integer> scores, Outcome outcome) {
  public enum Outcome {
    ONE_WINS,
    TWO_WINS,
    DRAW
  }

  public MatchResult {
    Objects.requireNonNull(scores, "scores are required");
    if (!scores.keySet().equals(java.util.Set.of(PlayerSeat.ONE, PlayerSeat.TWO))) {
      throw new IllegalArgumentException("scores for both seats are required");
    }
    scores = Map.copyOf(new EnumMap<>(scores));
    Objects.requireNonNull(outcome, "outcome is required");
  }

  public int score(PlayerSeat seat) {
    return scores.get(Objects.requireNonNull(seat, "seat is required"));
  }

  public PlayerSeat winner() {
    return switch (outcome) {
      case ONE_WINS -> PlayerSeat.ONE;
      case TWO_WINS -> PlayerSeat.TWO;
      case DRAW -> null;
    };
  }

  public boolean isDraw() {
    return outcome == Outcome.DRAW;
  }
}
