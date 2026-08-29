package org.aincraft.mobmatrix.domain;

/** Stable machine-readable command rejection reasons. */
public enum MatchErrorCode {
  OCCUPIED_SQUARE,
  WRONG_ACTOR,
  UNKNOWN_COPY,
  ALREADY_PLAYED_COPY,
  STALE_REVISION,
  DUPLICATE_COMMAND_ID,
  MATCH_FINISHED,
  TIMEOUT_NOT_DUE,
  CLOCK_EXPIRED,
  INVALID_COMMAND
}
