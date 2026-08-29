package org.aincraft.mobmatrix.domain;

/** Immutable internal catalog identifier. */
public record CardId(int value) {
  public CardId {
    if (value <= 0) {
      throw new IllegalArgumentException("card id must be positive");
    }
  }
}
