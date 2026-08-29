package org.aincraft.mobmatrix.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validated immutable set of board rules. */
public final class RuleSet {
  private final Set<Rule> rules;

  public RuleSet(Rule... rules) {
    this(rules == null ? null : List.of(rules));
  }

  public RuleSet(Collection<Rule> rules) {
    Objects.requireNonNull(rules, "rules are required");
    EnumSet<Rule> copy = EnumSet.noneOf(Rule.class);
    for (Rule rule : rules) {
      Objects.requireNonNull(rule, "rule is required");
      if (!copy.add(rule)) throw new IllegalArgumentException("duplicate rule: " + rule);
    }
    if (copy.contains(Rule.ALL_OPEN) && copy.contains(Rule.THREE_OPEN)) incompatible("ALL_OPEN", "THREE_OPEN");
    if (copy.contains(Rule.ORDER) && copy.contains(Rule.CHAOS)) incompatible("ORDER", "CHAOS");
    if (copy.contains(Rule.ASCENSION) && copy.contains(Rule.DESCENSION)) incompatible("ASCENSION", "DESCENSION");
    this.rules = Set.copyOf(copy);
  }

  public static RuleSet of(Rule... rules) { return new RuleSet(rules); }
  public static RuleSet empty() { return new RuleSet(); }
  public Set<Rule> rules() { return rules; }
  public boolean has(Rule rule) { return rules.contains(Objects.requireNonNull(rule, "rule is required")); }
  public int size() { return rules.size(); }
  public boolean isEmpty() { return rules.isEmpty(); }
  public MatchRules asMatchRules() { return new MatchRules(rules); }
  private static void incompatible(String a, String b) { throw new IllegalArgumentException("incompatible rules: " + a + " and " + b); }
}
