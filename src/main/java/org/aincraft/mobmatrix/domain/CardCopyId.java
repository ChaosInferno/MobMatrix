package org.aincraft.mobmatrix.domain;

import java.util.UUID;

/** Immutable physical-card-copy identifier. */
public record CardCopyId(UUID value) {
  public CardCopyId {
    if (value == null) {
      throw new IllegalArgumentException("copy id is required");
    }
  }
}
