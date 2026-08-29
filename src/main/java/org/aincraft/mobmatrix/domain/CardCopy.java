package org.aincraft.mobmatrix.domain;

import java.util.Objects;

/** A physical copy reference; separate copies may point to one definition. */
public record CardCopy(CardCopyId copyId, CardId cardId) {
  public CardCopy {
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(cardId, "card id is required");
  }
}
