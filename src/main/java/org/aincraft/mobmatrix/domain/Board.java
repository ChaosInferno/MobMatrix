package org.aincraft.mobmatrix.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable nine-cell board and standard adjacency operations. */
public final class Board {
  public record Cell(CardCopy copy, CardId cardId, PlayerSeat owner, Map<Side, Integer> effectiveValues) {
    public Cell {
      Objects.requireNonNull(copy, "copy is required");
      Objects.requireNonNull(cardId, "card id is required");
      Objects.requireNonNull(owner, "owner is required");
      if (!copy.cardId().equals(cardId)) throw new IllegalArgumentException("card id does not match copy");
      effectiveValues = Map.copyOf(Objects.requireNonNull(effectiveValues, "effective values are required"));
      for (Map.Entry<Side, Integer> entry : effectiveValues.entrySet()) {
        Objects.requireNonNull(entry.getKey(), "effective side is required");
        if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 10) {
          throw new IllegalArgumentException("effective value must be between 1 and 10");
        }
      }
    }

    public Cell(CardCopy copy, CardId cardId, PlayerSeat owner) {
      this(copy, cardId, owner, Map.of());
    }

    public Integer effectiveValue(Side side) {
      return effectiveValues.get(Objects.requireNonNull(side, "side is required"));
    }
  }

  public record Adjacent(Square square, Side facingSide, Side adjacentSide, Cell cell) {}

  private final List<Cell> cells;

  private Board(List<Cell> cells) {
    if (cells.size() != 9) throw new IllegalArgumentException("board must contain nine cells");
    this.cells = java.util.Collections.unmodifiableList(new ArrayList<>(cells));
  }

  public static Board empty() {
    return new Board(java.util.Collections.nCopies(9, null));
  }

  public List<Cell> cells() {
    return cells;
  }

  public Cell cellAt(Square square) {
    Objects.requireNonNull(square, "square is required");
    return cells.get(square.index());
  }

  public Cell get(Square square) {
    return cellAt(square);
  }

  public boolean isEmpty(Square square) {
    return cellAt(square) == null;
  }

  public List<Square> emptySquares() {
    List<Square> result = new ArrayList<>();
    for (int index = 0; index < 9; index++) if (cells.get(index) == null) result.add(Square.of(index));
    return List.copyOf(result);
  }

  public List<Square> occupiedSquares() {
    List<Square> result = new ArrayList<>();
    for (int index = 0; index < 9; index++) if (cells.get(index) != null) result.add(Square.of(index));
    return List.copyOf(result);
  }

  public PlayerSeat ownerOf(Square square) {
    Cell cell = cellAt(square);
    return cell == null ? null : cell.owner();
  }

  public CardCopy copyAt(Square square) {
    Cell cell = cellAt(square);
    return cell == null ? null : cell.copy();
  }

  public CardId cardIdAt(Square square) {
    Cell cell = cellAt(square);
    return cell == null ? null : cell.cardId();
  }

  public Board place(Square square, CardCopy copy, PlayerSeat owner) {
    return place(square, copy, owner, Map.of());
  }

  public Board place(Square square, CardCopy copy, PlayerSeat owner, Map<Side, Integer> effectiveValues) {
    Objects.requireNonNull(square, "square is required");
    Objects.requireNonNull(copy, "copy is required");
    Objects.requireNonNull(owner, "owner is required");
    if (!isEmpty(square)) throw new IllegalArgumentException("square is occupied");
    List<Cell> updated = new ArrayList<>(cells);
    updated.set(square.index(), new Cell(copy, copy.cardId(), owner, effectiveValues));
    return new Board(updated);
  }

  public Board withOwner(Square square, PlayerSeat owner) {
    Cell cell = cellAt(square);
    if (cell == null) throw new IllegalArgumentException("square is empty");
    List<Cell> updated = new ArrayList<>(cells);
    updated.set(square.index(), new Cell(cell.copy(), cell.cardId(), owner, cell.effectiveValues()));
    return new Board(updated);
  }

  public List<Square> adjacentSquares(Square source) {
    List<Square> result = new ArrayList<>();
    addCoordinate(result, source, -3);
    addCoordinate(result, source, 1);
    addCoordinate(result, source, 3);
    addCoordinate(result, source, -1);
    return List.copyOf(result);
  }

  private void addCoordinate(List<Square> result, Square source, int offset) {
    int target = source.index() + offset;
    if (target < 0 || target > 8) return;
    if (offset == 1 && source.column() == 2 || offset == -1 && source.column() == 0) return;
    result.add(Square.of(target));
  }

  public List<Adjacent> adjacent(Square source) {
    List<Adjacent> result = new ArrayList<>();
    addAdjacent(result, source, Side.TOP, -3, Side.BOTTOM);
    addAdjacent(result, source, Side.RIGHT, 1, Side.LEFT);
    addAdjacent(result, source, Side.BOTTOM, 3, Side.TOP);
    addAdjacent(result, source, Side.LEFT, -1, Side.RIGHT);
    return List.copyOf(result);
  }

  private void addAdjacent(List<Adjacent> result, Square source, Side facing, int offset, Side adjacentSide) {
    int target = source.index() + offset;
    if (target < 0 || target > 8) return;
    if (offset == 1 && source.column() == 2 || offset == -1 && source.column() == 0) return;
    Cell cell = cells.get(target);
    result.add(new Adjacent(Square.of(target), facing, adjacentSide, cell));
  }

  public boolean isOccupied(Square square) {
    return !isEmpty(square);
  }

  public PlayerSeat owner(Square square) {
    return ownerOf(square);
  }

  public long cardsOwnedBy(PlayerSeat seat) {
    return countOwnedBy(seat);
  }

  public long countOwnedBy(PlayerSeat seat) {
    return cells.stream().filter(cell -> cell != null && cell.owner() == seat).count();
  }

  public Map<PlayerSeat, Long> ownershipCounts() {
    Map<PlayerSeat, Long> counts = new EnumMap<>(PlayerSeat.class);
    for (PlayerSeat seat : PlayerSeat.values()) counts.put(seat, countOwnedBy(seat));
    return Map.copyOf(counts);
  }
}
