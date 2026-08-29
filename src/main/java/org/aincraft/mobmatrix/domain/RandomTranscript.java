package org.aincraft.mobmatrix.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Seeded random source that records every decision and can be forked safely. */
public final class RandomTranscript {
  public record Decision(String decision, int bound, int value) {
    public Decision {
      if (decision == null || decision.isBlank()) {
        throw new IllegalArgumentException("decision label is required");
      }
      if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
      if (value < 0 || value >= bound) throw new IllegalArgumentException("value outside bound");
    }
    public String label() { return decision; }
    public int selected() { return value; }
  }

  private final long seed;
  private final SplittableRandom random;
  private final List<Decision> decisions;
  private final boolean replay;
  private int cursor;

  public RandomTranscript(long seed) {
    this.seed = seed;
    this.random = new SplittableRandom(seed);
    this.decisions = new ArrayList<>();
    this.replay = false;
    this.cursor = 0;
  }

  private RandomTranscript(long seed, List<Decision> decisions, boolean replay, int cursor) {
    this.seed = seed;
    this.random = new SplittableRandom(seed);
    this.decisions = new ArrayList<>(List.copyOf(Objects.requireNonNull(decisions, "decisions are required")));
    this.replay = replay;
    this.cursor = cursor;
    if (!replay) {
      for (Decision decision : this.decisions) random.nextInt(decision.bound());
      this.cursor = this.decisions.size();
    }
  }

  private RandomTranscript(List<Decision> decisions) {
    this.seed = 0L;
    this.random = null;
    this.decisions = new ArrayList<>(List.copyOf(Objects.requireNonNull(decisions, "decisions are required")));
    this.replay = true;
    this.cursor = 0;
  }

  public static RandomTranscript replay(List<Decision> decisions) { return new RandomTranscript(decisions); }
  public static RandomTranscript from(List<Decision> decisions) { return replay(decisions); }

  /** Returns an independent random cursor/list fork for an accepted successor state. */
  public synchronized RandomTranscript fork() {
    return new RandomTranscript(seed, decisions, replay, cursor);
  }

  public synchronized int nextInt(String decision, int bound) {
    validate(decision, bound);
    if (replay) return consume(decision, bound).value();
    int value = random.nextInt(bound);
    decisions.add(new Decision(decision, bound, value));
    cursor = decisions.size();
    return value;
  }

  /** Records a deterministic choice with the same strict replay validation as nextInt. */
  public synchronized int record(String decision, int bound, int value) {
    validate(decision, bound);
    if (value < 0 || value >= bound) throw new IllegalArgumentException("value outside bound");
    if (replay) return consume(decision, bound).value();
    random.nextInt(bound);
    decisions.add(new Decision(decision, bound, value));
    cursor = decisions.size();
    return value;
  }

  private Decision consume(String decision, int bound) {
    if (cursor >= decisions.size()) throw new IllegalStateException("random transcript exhausted");
    Decision expected = decisions.get(cursor);
    if (!expected.decision().equals(decision) || expected.bound() != bound) {
      throw new IllegalArgumentException("random transcript decision mismatch");
    }
    cursor++;
    return expected;
  }

  private static void validate(String decision, int bound) {
    if (decision == null || decision.isBlank()) throw new IllegalArgumentException("decision label is required");
    if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
  }

  public synchronized List<Decision> decisions() { return List.copyOf(decisions); }
  public synchronized List<Decision> recordedDecisions() { return decisions(); }
  public synchronized boolean exhausted() { return replay && cursor == decisions.size(); }
}
