package org.aincraft.mobmatrix.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable rules selected for one match. */
public record MatchRules(Set<Rule> rules) {
  public MatchRules {
    Objects.requireNonNull(rules, "rules are required");
    EnumSet<Rule> copy = rules.isEmpty() ? EnumSet.noneOf(Rule.class) : EnumSet.copyOf(rules);
    rules = Set.copyOf(copy);
  }

  public MatchRules(Rule... rules) {
    this(rules == null ? Set.of() : Set.of(rules));
  }

  public MatchRules(Collection<Rule> rules) {
    this(Set.copyOf(Objects.requireNonNull(rules, "rules are required")));
  }

  public MatchRules() {
    this(Set.of());
  }

  public boolean has(Rule rule) {
    return rules.contains(Objects.requireNonNull(rule, "rule is required"));
  }
}
