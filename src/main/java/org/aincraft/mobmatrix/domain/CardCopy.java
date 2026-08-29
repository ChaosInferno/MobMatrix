package org.aincraft.mobmatrix.domain;

import java.util.Objects;

/** A physical copy reference; separate copies may point to one definition. */
public record CardCopy(CardCopyId copyId, CardId cardId, CopyOwner owner) {
  public CardCopy {
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(cardId, "card id is required");
    Objects.requireNonNull(owner, "owner is required");
  }

  /** Compatibility constructor for generated house cards and pre-custody domain fixtures. */
  public CardCopy(CardCopyId copyId, CardId cardId) {
    this(copyId, cardId, CopyOwner.house());
  }
}
