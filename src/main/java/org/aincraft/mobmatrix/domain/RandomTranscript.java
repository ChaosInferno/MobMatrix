package org.aincraft.mobmatrix.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Seeded random source that records every decision and can replay a transcript. */
public final class RandomTranscript {
  public record Decision(String decision, int bound, int value) {
    public Decision {
      if (decision == null || decision.isBlank()) throw new IllegalArgumentException("decision label is required");
      if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
      if (value < 0 || value >= bound) throw new IllegalArgumentException("value outside bound");
    }
    public String label() { return decision; }
    public int selected() { return value; }
  }

  private final SplittableRandom random;
  private final List<Decision> decisions;
  private final boolean replay;
  private int cursor;

  public RandomTranscript(long seed) {
    random = new SplittableRandom(seed);
    decisions = new ArrayList<>();
    replay = false;
  }

  private RandomTranscript(List<Decision> decisions) {
    random = null;
    this.decisions = new ArrayList<>(List.copyOf(Objects.requireNonNull(decisions, "decisions are required")));
    replay = true;
  }

  public static RandomTranscript replay(List<Decision> decisions) { return new RandomTranscript(decisions); }
  public static RandomTranscript from(List<Decision> decisions) { return replay(decisions); }

  public synchronized int nextInt(String decision, int bound) {
    if (decision == null || decision.isBlank()) throw new IllegalArgumentException("decision label is required");
    if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
    if (replay) {
      if (cursor >= decisions.size()) throw new IllegalStateException("random transcript exhausted");
      Decision expected = decisions.get(cursor++);
      if (!expected.decision().equals(decision) || expected.bound() != bound) {
        cursor--;
        throw new IllegalArgumentException("random transcript decision mismatch");
      }
      return expected.value();
    }
    int value = random.nextInt(bound);
    decisions.add(new Decision(decision, bound, value));
    return value;
  }

  public synchronized List<Decision> decisions() { return List.copyOf(decisions); }
  public synchronized List<Decision> recordedDecisions() { return decisions(); }
  public synchronized boolean exhausted() { return replay && cursor == decisions.size(); }
}
