package org.aincraft.mobmatrix.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A selected five-copy deck. */
public record Deck(List<CardCopy> copies) {
  public Deck {
    Objects.requireNonNull(copies, "copies are required");
    if (copies.size() != 5) {
      throw new IllegalArgumentException("a deck must contain exactly five copies");
    }
    Set<CardCopyId> ids = new HashSet<>();
    for (CardCopy copy : copies) {
      if (copy == null) {
        throw new IllegalArgumentException("deck cannot contain null copies");
      }
      if (!ids.add(copy.copyId())) {
        throw new IllegalArgumentException("deck contains duplicate copy id: " + copy.copyId());
      }
    }
    copies = List.copyOf(copies);
  }
}
