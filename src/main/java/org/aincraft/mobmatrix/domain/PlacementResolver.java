package org.aincraft.mobmatrix.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure implementation of the approved placement precedence and capture rules. */
public final class PlacementResolver {
  public enum CaptureSource { STANDARD, SAME, PLUS, COMBO }
  public enum Direction { NORMAL, REVERSE }
  public record Capture(Square square, PlayerSeat from, PlayerSeat to, CaptureSource source, Direction direction) {}
  public record Resolution(Board board, List<Capture> captures, List<String> events,
      java.util.Map<Side, Integer> effectiveValues) {
    public Resolution {
      Objects.requireNonNull(board);
      captures = List.copyOf(captures);
      events = List.copyOf(events);
      effectiveValues = java.util.Map.copyOf(effectiveValues);
    }
    public List<Square> capturedSquares() { return captures.stream().map(Capture::square).toList(); }
  }

  private PlacementResolver() {}

  public static Resolution resolve(Board board, CardCatalog catalog, RuleSet rules,
      CardCopy placed, PlayerSeat owner, Square square) {
    Objects.requireNonNull(board); Objects.requireNonNull(catalog); Objects.requireNonNull(rules);
    Objects.requireNonNull(placed); Objects.requireNonNull(owner); Objects.requireNonNull(square);
    if (!board.isEmpty(square)) throw new IllegalArgumentException("square is occupied");
    CardDefinition definition = catalog.require(placed.cardId());
    int sameType = 0;
    for (Board.Cell cell : board.cells()) {
      if (cell != null && catalog.require(cell.cardId()).type() == definition.type()) sameType++;
    }
    boolean asc = rules.has(Rule.ASCENSION);
    boolean desc = rules.has(Rule.DESCENSION);
    java.util.Map<Side, Integer> values = new java.util.EnumMap<>(Side.class);
    for (Side side : Side.values()) {
      int value = definition.value(side);
      if (asc) value = Math.min(10, value + sameType);
      if (desc) value = Math.max(1, value - sameType);
      values.put(side, value);
    }
    Board placedBoard = board.place(square, placed, owner);
    List<Board.Adjacent> snapshot = placedBoard.adjacent(square).stream()
        .filter(a -> a.cell() != null).sorted(Comparator.comparingInt(a -> a.square().index())).toList();
    Set<Square> sameTargets = new HashSet<>();
    Set<Square> plusTargets = new HashSet<>();
    if (rules.has(Rule.SAME)) {
      List<Board.Adjacent> matching = new ArrayList<>();
      for (Board.Adjacent a : snapshot) {
        if (values.get(a.facingSide()) == catalog.require(a.cell().cardId()).value(a.adjacentSide())) matching.add(a);
      }
      if (matching.size() >= 2) matching.forEach(a -> sameTargets.add(a.square()));
    }
    if (rules.has(Rule.PLUS)) {
      java.util.Map<Integer, List<Board.Adjacent>> bySum = new java.util.HashMap<>();
      for (Board.Adjacent a : snapshot) {
        int sum = values.get(a.facingSide()) + catalog.require(a.cell().cardId()).value(a.adjacentSide());
        bySum.computeIfAbsent(sum, ignored -> new ArrayList<>()).add(a);
      }
      bySum.values().stream().filter(group -> group.size() >= 2)
          .flatMap(List::stream).forEach(a -> plusTargets.add(a.square()));
    }
    Board resultBoard = placedBoard;
    Set<Square> special = new HashSet<>();
    for (Square target : sameTargets) if (placedBoard.ownerOf(target) != owner) special.add(target);
    for (Square target : plusTargets) if (placedBoard.ownerOf(target) != owner) special.add(target);
    List<Capture> captures = new ArrayList<>();
    List<String> events = new ArrayList<>();
    List<Board.Adjacent> ordered = snapshot;
    for (Board.Adjacent a : ordered) {
      if (a.cell().owner() == owner) continue;
      CaptureSource source = sameTargets.contains(a.square()) ? CaptureSource.SAME
          : plusTargets.contains(a.square()) ? CaptureSource.PLUS : null;
      if (source == null && !ordinary(values.get(a.facingSide()), catalog.require(a.cell().cardId()).value(a.adjacentSide()), rules)) continue;
      if (source == null) source = CaptureSource.STANDARD;
      resultBoard = resultBoard.withOwner(a.square(), owner);
      captures.add(new Capture(a.square(), owner, a.cell().owner(), source,
          rules.has(Rule.REVERSE) ? Direction.REVERSE : Direction.NORMAL));
      events.add(source.name());
    }
    if (rules.has(Rule.COMBO) && !special.isEmpty()) {
      ArrayDeque<Square> queue = new ArrayDeque<>(special.stream().sorted(Comparator.comparingInt(Square::index)).toList());
      Set<Square> seen = new HashSet<>(special);
      while (!queue.isEmpty()) {
        Square sourceSquare = queue.remove();
        Board.Cell sourceCell = resultBoard.cellAt(sourceSquare);
        if (sourceCell == null) continue;
        CardDefinition sourceDefinition = catalog.require(sourceCell.cardId());
        for (Board.Adjacent adjacent : resultBoard.adjacent(sourceSquare).stream()
            .filter(a -> a.cell() != null).sorted(Comparator.comparingInt(a -> a.square().index())).toList()) {
          if (adjacent.cell().owner() == owner || seen.contains(adjacent.square())) continue;
          if (!ordinary(sourceDefinition.value(adjacent.facingSide()), catalog.require(adjacent.cell().cardId()).value(adjacent.adjacentSide()), rules)) continue;
          PlayerSeat oldOwner = adjacent.cell().owner();
          resultBoard = resultBoard.withOwner(adjacent.square(), owner);
          seen.add(adjacent.square()); queue.add(adjacent.square());
          captures.add(new Capture(adjacent.square(), owner, oldOwner, CaptureSource.COMBO,
              rules.has(Rule.REVERSE) ? Direction.REVERSE : Direction.NORMAL));
          events.add(CaptureSource.COMBO.name());
        }
      }
    }
    return new Resolution(resultBoard, captures, events, values);
  }

  public static Resolution resolve(Board board, CardCatalog catalog, MatchRules rules,
      CardCopy placed, PlayerSeat owner, Square square) {
    return resolve(board, catalog, rules.asRuleSet(), placed, owner, square);
  }

  private static boolean ordinary(int placed, int adjacent, RuleSet rules) {
    boolean reverse = rules.has(Rule.REVERSE);
    if (reverse && placed < adjacent || !reverse && placed > adjacent) return true;
    return rules.has(Rule.FALLEN_ACE)
        && (reverse ? placed == 10 && adjacent == 1 : placed == 1 && adjacent == 10);
  }
}
