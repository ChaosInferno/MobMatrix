package org.aincraft.mobmatrix.domain;

import java.util.Objects;

/** A non-exceptional command rejection. */
public record MatchRejection(MatchErrorCode code, String message) {
  public MatchRejection {
    Objects.requireNonNull(code, "code is required");
    message = message == null ? code.name() : message;
  }
}
