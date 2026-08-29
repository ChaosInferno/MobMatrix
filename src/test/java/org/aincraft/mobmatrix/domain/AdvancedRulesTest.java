package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AdvancedRulesTest {
  @Test
  void rulesRejectIncompatibleAndDuplicateValues() {
    assertThrows(IllegalArgumentException.class, () -> new RuleSet(Rule.ALL_OPEN, Rule.THREE_OPEN));
    assertThrows(IllegalArgumentException.class, () -> new RuleSet(Rule.ORDER, Rule.CHAOS));
    assertThrows(IllegalArgumentException.class, () -> new RuleSet(Rule.ASCENSION, Rule.DESCENSION));
    assertThrows(IllegalArgumentException.class, () -> new RuleSet(Rule.SAME, Rule.SAME));
    assertThrows(NullPointerException.class, () -> new RuleSet((Rule) null));
  }

  @Test
  void sameAndPlusUnionOpposingTargetsAndFriendlyCardsNeverFlip() {
    CardCatalog catalog = catalog();
    Board board = Board.empty()
        .place(Square.WEST, copy(5), PlayerSeat.ONE)
        .place(Square.EAST, copy(5), PlayerSeat.TWO)
        .place(Square.NORTH, copy(5), PlayerSeat.TWO)
        .place(Square.SOUTH, copy(4), PlayerSeat.ONE);
    PlacementResolver.Resolution result = PlacementResolver.resolve(board, catalog,
        new RuleSet(Rule.SAME, Rule.PLUS, Rule.COMBO), copy(5), PlayerSeat.ONE, Square.CENTER);
    assertEquals(PlayerSeat.ONE, result.board().ownerOf(Square.EAST));
    assertEquals(PlayerSeat.ONE, result.board().ownerOf(Square.NORTH));
    assertEquals(PlayerSeat.ONE, result.board().ownerOf(Square.SOUTH));
    assertTrue(result.captures().stream().anyMatch(c -> c.source() == PlacementResolver.CaptureSource.SAME));
  }
  @Test
  void threeOpenRevealsExactlyThreeAndDraftCreatesThreeFiveCardOffers() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("open"), deck(1), deck(6), catalog(),
        new RuleSet(Rule.THREE_OPEN), 2L, java.time.Instant.parse("2026-01-01T00:00:00Z"));
    MatchState state = MatchEngine.start(start).state();
    MatchProjection projection = MatchProjection.forViewer(state, state.currentSeat());
    assertEquals(3, projection.opponentHandSlots().stream().filter(MatchProjection.HandSlot::revealed).count());
    DraftOfferings.SeatOffers offers = DraftOfferings.generate(PlayerSeat.ONE, catalog(), new RandomTranscript(3L));
    assertEquals(3, offers.offerings().size());
    assertTrue(offers.offerings().stream().allMatch(offer -> offer.cards().size() == 5));
    assertEquals(5, DraftOfferings.select(offers, List.of(0, 1, 2)).deck().copies().size());
  }

  @Test
  void reverseAndFallenAceUseOnlyOrdinaryComparisons() {
    CardCatalog catalog = catalog();
    Board board = Board.empty().place(Square.WEST, copy(10), PlayerSeat.TWO);
    PlacementResolver.Resolution result = PlacementResolver.resolve(board, catalog,
        new RuleSet(Rule.REVERSE, Rule.FALLEN_ACE), copy(9), PlayerSeat.ONE, Square.CENTER);
    assertEquals(PlayerSeat.ONE, result.board().ownerOf(Square.WEST));
  }

  private static CardCopy copy(int id) {
    return new CardCopy(new CardCopyId(new UUID(0, id)), new CardId(id));
  }

  private static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) copies.add(copy(firstId + index));
    return new Deck(copies);
  }

  private static CardCatalog catalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(new CardDefinition(new CardId(id), "No. " + id, "Card " + id, 1,
          CardType.NORMAL, id == 1 ? 10 : id, id == 2 ? 10 : id, id == 3 ? 10 : id, id == 10 ? 10 : id, 100));
    }
    return new CardCatalog(definitions, "test");
  }
}
