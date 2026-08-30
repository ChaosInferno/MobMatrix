package org.aincraft.mobmatrix.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure implementation of the approved placement precedence and capture rules. */
public final class PlacementResolver {
  public enum CaptureSource {
    STANDARD,
    SAME,
    PLUS,
    COMBO
  }

  public enum Direction {
    NORMAL,
    REVERSE
  }

  public record Capture(
      Square square, PlayerSeat from, PlayerSeat to, CaptureSource source, Direction direction) {}

  public record Resolution(
      Board board,
      List<Capture> captures,
      List<String> events,
      Map<Side, Integer> effectiveValues) {
    public Resolution {
      Objects.requireNonNull(board);
      captures = List.copyOf(captures);
      events = List.copyOf(events);
      effectiveValues = Map.copyOf(effectiveValues);
    }

    public List<Square> capturedSquares() {
      return captures.stream().map(Capture::square).toList();
    }
  }

  private PlacementResolver() {}

  public static Resolution resolve(
      Board board,
      CardCatalog catalog,
      RuleSet rules,
      CardCopy placed,
      PlayerSeat owner,
      Square square) {
    Objects.requireNonNull(board);
    Objects.requireNonNull(catalog);
    Objects.requireNonNull(rules);
    Objects.requireNonNull(placed);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(square);
    if (!board.isEmpty(square)) throw new IllegalArgumentException("square is occupied");

    CardDefinition placedDefinition = catalog.require(placed.cardId());
    int sameType = 0;
    for (Board.Cell cell : board.cells()) {
      if (cell != null && catalog.require(cell.cardId()).type() == placedDefinition.type())
        sameType++;
    }
    Map<Side, Integer> values = new java.util.EnumMap<>(Side.class);
    for (Side side : Side.values()) {
      int value = placedDefinition.value(side);
      if (rules.has(Rule.ASCENSION)) value = Math.min(10, value + sameType);
      if (rules.has(Rule.DESCENSION)) value = Math.max(1, value - sameType);
      values.put(side, value);
    }

    Board placedBoard = board.place(square, placed, owner, values);
    List<Board.Adjacent> snapshot =
        placedBoard.adjacent(square).stream()
            .filter(adjacent -> adjacent.cell() != null)
            .sorted(Comparator.comparingInt(adjacent -> adjacent.square().index()))
            .toList();

    Set<Square> sameTargets = new HashSet<>();
    if (rules.has(Rule.SAME)) {
      List<Board.Adjacent> matching =
          snapshot.stream()
              .filter(
                  adjacent ->
                      values
                          .get(adjacent.facingSide())
                          .equals(effective(adjacent.cell(), adjacent.adjacentSide(), catalog)))
              .toList();
      if (matching.size() >= 2) matching.forEach(adjacent -> sameTargets.add(adjacent.square()));
    }

    Set<Square> plusTargets = new HashSet<>();
    if (rules.has(Rule.PLUS)) {
      Map<Integer, List<Board.Adjacent>> bySum = new HashMap<>();
      for (Board.Adjacent adjacent : snapshot) {
        int sum =
            values.get(adjacent.facingSide())
                + effective(adjacent.cell(), adjacent.adjacentSide(), catalog);
        bySum.computeIfAbsent(sum, ignored -> new ArrayList<>()).add(adjacent);
      }
      bySum.values().stream()
          .filter(group -> group.size() >= 2)
          .flatMap(List::stream)
          .forEach(adjacent -> plusTargets.add(adjacent.square()));
    }

    Board resultBoard = placedBoard;
    Set<Square> special = new HashSet<>();
    for (Square target : sameTargets) {
      if (placedBoard.ownerOf(target) != owner) special.add(target);
    }
    for (Square target : plusTargets) {
      if (placedBoard.ownerOf(target) != owner) special.add(target);
    }

    List<Capture> captures = new ArrayList<>();
    List<String> events = new ArrayList<>();
    for (Board.Adjacent adjacent : snapshot) {
      if (adjacent.cell().owner() == owner) continue;
      CaptureSource source =
          sameTargets.contains(adjacent.square())
              ? CaptureSource.SAME
              : plusTargets.contains(adjacent.square()) ? CaptureSource.PLUS : null;
      int adjacentValue = effective(adjacent.cell(), adjacent.adjacentSide(), catalog);
      if (source == null && !ordinary(values.get(adjacent.facingSide()), adjacentValue, rules))
        continue;
      if (source == null) source = CaptureSource.STANDARD;
      PlayerSeat oldOwner = adjacent.cell().owner();
      resultBoard = resultBoard.withOwner(adjacent.square(), owner);
      captures.add(
          new Capture(
              adjacent.square(),
              owner,
              oldOwner,
              source,
              rules.has(Rule.REVERSE) ? Direction.REVERSE : Direction.NORMAL));
      events.add(source.name());
    }

    if (rules.has(Rule.COMBO) && !special.isEmpty()) {
      ArrayDeque<Square> queue =
          new ArrayDeque<>(
              special.stream().sorted(Comparator.comparingInt(Square::index)).toList());
      Set<Square> seen = new HashSet<>(special);
      while (!queue.isEmpty()) {
        Square sourceSquare = queue.remove();
        Board.Cell sourceCell = resultBoard.cellAt(sourceSquare);
        if (sourceCell == null) continue;
        for (Board.Adjacent adjacent :
            resultBoard.adjacent(sourceSquare).stream()
                .filter(item -> item.cell() != null)
                .sorted(Comparator.comparingInt(item -> item.square().index()))
                .toList()) {
          if (adjacent.cell().owner() == owner || seen.contains(adjacent.square())) continue;
          int sourceValue = effective(sourceCell, adjacent.facingSide(), catalog);
          int adjacentValue = effective(adjacent.cell(), adjacent.adjacentSide(), catalog);
          if (!ordinary(sourceValue, adjacentValue, rules)) continue;
          PlayerSeat oldOwner = adjacent.cell().owner();
          resultBoard = resultBoard.withOwner(adjacent.square(), owner);
          seen.add(adjacent.square());
          queue.add(adjacent.square());
          captures.add(
              new Capture(
                  adjacent.square(),
                  owner,
                  oldOwner,
                  CaptureSource.COMBO,
                  rules.has(Rule.REVERSE) ? Direction.REVERSE : Direction.NORMAL));
          events.add(CaptureSource.COMBO.name());
        }
      }
    }
    captures.sort(Comparator.comparingInt(capture -> capture.square().index()));
    events.clear();
    captures.forEach(capture -> events.add(capture.source().name()));
    return new Resolution(resultBoard, captures, events, values);
  }

  public static Resolution resolve(
      Board board,
      CardCatalog catalog,
      MatchRules rules,
      CardCopy placed,
      PlayerSeat owner,
      Square square) {
    return resolve(board, catalog, rules.asRuleSet(), placed, owner, square);
  }

  private static int effective(Board.Cell cell, Side side, CardCatalog catalog) {
    Integer stored = cell.effectiveValue(side);
    return stored == null ? catalog.require(cell.cardId()).value(side) : stored;
  }

  private static boolean ordinary(int placed, int adjacent, RuleSet rules) {
    boolean reverse = rules.has(Rule.REVERSE);
    if (reverse && placed < adjacent || !reverse && placed > adjacent) return true;
    return rules.has(Rule.FALLEN_ACE)
        && (reverse ? placed == 10 && adjacent == 1 : placed == 1 && adjacent == 10);
  }
}
