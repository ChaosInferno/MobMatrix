package org.aincraft.mobmatrix.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An immutable catalog tied to one generated snapshot. */
public final class CardCatalog {
  private final Map<CardId, CardDefinition> byId;
  private final List<CardDefinition> all;
  private final String snapshotId;

  public CardCatalog(Collection<CardDefinition> definitions, String snapshotId) {
    Objects.requireNonNull(definitions, "definitions are required");
    if (snapshotId == null || snapshotId.isBlank()) {
      throw new IllegalArgumentException("snapshot id is required");
    }
    Map<CardId, CardDefinition> cards = new HashMap<>();
    Set<String> publicNumbers = new HashSet<>();
    for (CardDefinition definition : definitions) {
      if (definition == null) {
        throw new IllegalArgumentException("catalog cannot contain null definitions");
      }
      if (cards.putIfAbsent(definition.id(), definition) != null) {
        throw new IllegalArgumentException("duplicate card id: " + definition.id());
      }
      if (!publicNumbers.add(definition.publicNumber())) {
        throw new IllegalArgumentException("duplicate public number: " + definition.publicNumber());
      }
    }
    List<CardDefinition> sorted = new ArrayList<>(cards.values());
    sorted.sort(java.util.Comparator.comparingInt(card -> card.id().value()));
    for (int index = 1; index < sorted.size(); index++) {
      int previousId = sorted.get(index - 1).id().value();
      int currentId = sorted.get(index).id().value();
      if (currentId != previousId + 1) {
        throw new IllegalArgumentException("missing card id: " + (previousId + 1));
      }
    }
    this.byId = Map.copyOf(cards);
    this.all = Collections.unmodifiableList(sorted);
    this.snapshotId = snapshotId;
  }

  public CardCatalog(Collection<CardDefinition> definitions) {
    this(definitions, "unspecified");
  }

  public CardDefinition require(CardId id) {
    Objects.requireNonNull(id, "id is required");
    CardDefinition definition = byId.get(id);
    if (definition == null) {
      throw new IllegalArgumentException("unknown card id: " + id.value());
    }
    return definition;
  }

  public Collection<CardDefinition> all() {
    return all;
  }

  public String snapshotId() {
    return snapshotId;
  }
}
