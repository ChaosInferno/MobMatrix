package org.aincraft.mobmatrix.domain;

import java.util.List;

/** A row-major board coordinate. */
public record Square(int index) {
  public static final Square NORTH_WEST = new Square(0);
  public static final Square NORTH = new Square(1);
  public static final Square NORTH_EAST = new Square(2);
  public static final Square WEST = new Square(3);
  public static final Square CENTER = new Square(4);
  public static final Square EAST = new Square(5);
  public static final Square SOUTH_WEST = new Square(6);
  public static final Square SOUTH = new Square(7);
  public static final Square SOUTH_EAST = new Square(8);

  public Square {
    if (index < 0 || index > 8) {
      throw new IllegalArgumentException("square index must be between 0 and 8");
    }
  }

  public static Square of(int index) {
    return new Square(index);
  }

  public int row() {
    return index / 3;
  }

  public int column() {
    return index % 3;
  }

  public List<Square> adjacentSquares() {
    return List.of(
            row() > 0 ? new Square(index - 3) : null,
            column() < 2 ? new Square(index + 1) : null,
            row() < 2 ? new Square(index + 3) : null,
            column() > 0 ? new Square(index - 1) : null)
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }
}
