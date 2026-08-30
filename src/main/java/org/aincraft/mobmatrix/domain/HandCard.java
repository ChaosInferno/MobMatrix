package org.aincraft.mobmatrix.domain;

import java.util.Objects;

/** A physical card copy held by a player, with its catalog definition. */
public record HandCard(CardCopy copy, CardDefinition definition) {
  public HandCard {
    Objects.requireNonNull(copy, "copy is required");
    Objects.requireNonNull(definition, "definition is required");
    if (!copy.cardId().equals(definition.id()))
      throw new IllegalArgumentException("definition does not match copy");
  }

  public CardCopyId copyId() {
    return copy.copyId();
  }

  public CardId cardId() {
    return copy.cardId();
  }
}
