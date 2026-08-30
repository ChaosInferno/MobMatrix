package org.aincraft.mobmatrix.domain;

import java.util.Objects;
import java.util.UUID;

/** Nonblank identity of a match. */
public record MatchId(String value) {
  public MatchId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("match id is required");
  }

  public MatchId(UUID value) {
    this(Objects.requireNonNull(value, "match id is required").toString());
  }
}
