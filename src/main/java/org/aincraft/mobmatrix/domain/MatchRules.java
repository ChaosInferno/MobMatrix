package org.aincraft.mobmatrix.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Immutable rules selected for one match. */
public record MatchRules(Set<Rule> rules) {
  public MatchRules {
    Objects.requireNonNull(rules, "rules are required");
    rules = Set.copyOf(new RuleSet(rules).rules());
  }

  public MatchRules(Rule... rules) {
    this(Arrays.asList(Objects.requireNonNull(rules, "rules are required")));
  }

  public MatchRules(Collection<Rule> rules) {
    this(
        Objects.requireNonNull(rules, "rules are required").stream()
            .collect(java.util.stream.Collectors.toSet()));
    if (rules.size() != this.rules.size()) throw new IllegalArgumentException("duplicate rule");
  }

  public MatchRules() {
    this(Set.of());
  }

  public boolean has(Rule rule) {
    return rules.contains(Objects.requireNonNull(rule, "rule is required"));
  }

  public RuleSet asRuleSet() {
    return new RuleSet(rules);
  }
}
