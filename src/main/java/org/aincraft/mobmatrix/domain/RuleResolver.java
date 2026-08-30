package org.aincraft.mobmatrix.domain;

import java.util.List;
import java.util.Objects;

/** Resolves setup-level random rule profiles from an explicit finite candidate list. */
public final class RuleResolver {
  private static final List<RuleSet> CANDIDATES =
      List.of(
          new RuleSet(),
          new RuleSet(Rule.REVERSE),
          new RuleSet(Rule.SAME),
          new RuleSet(Rule.PLUS, Rule.COMBO),
          new RuleSet(Rule.FALLEN_ACE),
          new RuleSet(Rule.ASCENSION),
          new RuleSet(Rule.DESCENSION),
          new RuleSet(Rule.SWAP),
          new RuleSet(Rule.ORDER),
          new RuleSet(Rule.CHAOS));

  private RuleResolver() {}

  public static RuleSet resolve(RuleSet requested, RandomTranscript transcript) {
    Objects.requireNonNull(requested);
    Objects.requireNonNull(transcript);
    if (!requested.has(Rule.ROULETTE)) return requested;
    int selected = transcript.nextInt("roulette-rule-set", CANDIDATES.size());
    return CANDIDATES.get(selected);
  }

  public static RuleSet resolve(RandomTranscript transcript) {
    return CANDIDATES.get(transcript.nextInt("roulette-rule-set", CANDIDATES.size()));
  }

  public static List<RuleSet> candidates() {
    return CANDIDATES;
  }
}
