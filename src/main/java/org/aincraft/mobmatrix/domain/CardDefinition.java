package org.aincraft.mobmatrix.domain;

import java.util.Objects;

/** The factual, text-only definition of one catalog card. */
public record CardDefinition(
    CardId id,
    String publicNumber,
    String name,
    int stars,
    CardType type,
    int top,
    int right,
    int bottom,
    int left,
    int salePrice) {
  public CardDefinition {
    Objects.requireNonNull(id, "id is required");
    requireNonBlank(publicNumber, "public number");
    requireNonBlank(name, "name");
    Objects.requireNonNull(type, "type is required");
    if (stars < 1 || stars > 5) {
      throw new IllegalArgumentException("stars must be between 1 and 5");
    }
    requireSide(top, "top");
    requireSide(right, "right");
    requireSide(bottom, "bottom");
    requireSide(left, "left");
    if (salePrice < 0) {
      throw new IllegalArgumentException("sale price must not be negative");
    }
  }

  public int value(Side side) {
    Objects.requireNonNull(side, "side is required");
    return switch (side) {
      case TOP -> top;
      case RIGHT -> right;
      case BOTTOM -> bottom;
      case LEFT -> left;
    };
  }

  private static void requireSide(int value, String side) {
    if (value < 1 || value > 10) {
      throw new IllegalArgumentException(side + " value must be between 1 and 10");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
