package org.aincraft.mobmatrix.domain;

public enum CardType {
  NORMAL,
  PRIMAL,
  SCION,
  SOCIETY,
  GARLEAN;

  public static CardType fromApiName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("card type is required");
    }
    try {
      return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown card type: " + name, exception);
    }
  }
}
