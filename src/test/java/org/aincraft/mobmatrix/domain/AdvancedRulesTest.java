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
  void draftStartUsesOnlyHouseOwnedOfferingCopiesAndExposesSelections() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("draft"), deck(1), deck(6), catalog(),
        new RuleSet(Rule.DRAFT), 7L, java.time.Instant.parse("2026-01-01T00:00:00Z"),
        deck(1).copies(), deck(6).copies(), List.of(0, 1, 2), List.of(2, 3, 4));
    MatchEngine.MatchTransition transition = MatchEngine.start(start);
    assertTrue(transition.accepted());
    MatchState state = transition.state();
    assertEquals(3, state.draftOfferings(PlayerSeat.ONE).offerings().size());
    assertEquals(5, state.hand(PlayerSeat.ONE).size());
    assertEquals(5, state.hand(PlayerSeat.TWO).size());
    assertTrue(state.hand(PlayerSeat.ONE).stream().allMatch(card ->
        state.draftOfferings(PlayerSeat.ONE).offerings().stream().flatMap(o -> o.cards().stream())
            .anyMatch(copy -> copy.copyId().equals(card.copyId()))));
    assertTrue(state.hand(PlayerSeat.ONE).stream().noneMatch(card -> deck(1).copies().contains(card.copy())));
    assertEquals(List.of(0, 1, 2), state.draftSelection(PlayerSeat.ONE).offeringChoices());
    assertThrows(IllegalArgumentException.class,
        () -> DraftOfferings.select(state.draftOfferings(PlayerSeat.ONE), List.of(0, 1)));
    assertThrows(IllegalArgumentException.class,
        () -> DraftOfferings.select(state.draftOfferings(PlayerSeat.ONE), List.of(0, 1, 5)));
  }

  @Test
  void ascensionStoresPlacementEffectiveValuesForLaterComparisons() {
    CardCatalog catalog = catalog();
    CardCopy first = copy(4);
    CardCopy second = copy(5);
    Board board = Board.empty().place(Square.WEST, first, PlayerSeat.ONE);
    PlacementResolver.Resolution placement = PlacementResolver.resolve(board, catalog,
        new RuleSet(Rule.ASCENSION), second, PlayerSeat.TWO, Square.CENTER);
    assertEquals(6, placement.effectiveValues().get(Side.LEFT));
    assertEquals(6, placement.board().cellAt(Square.CENTER).effectiveValue(Side.LEFT));
    PlacementResolver.Resolution later = PlacementResolver.resolve(placement.board(), catalog,
        new RuleSet(Rule.ASCENSION), copy(6), PlayerSeat.TWO, Square.EAST);
    assertEquals(PlayerSeat.TWO, later.board().ownerOf(Square.CENTER));
  }

  @Test
  void draftSelectionCannotInjectCardsOutsideOfferings() {
    DraftOfferings.SeatOffers offers = DraftOfferings.generate(PlayerSeat.ONE, catalog(), new RandomTranscript(9L));
    CardCopy playerCard = deck(1).copies().getFirst();
    assertThrows(IllegalArgumentException.class,
        () -> DraftOfferings.select(offers, List.of(playerCard, offers.offerings().get(1).cards().getFirst(),
            offers.offerings().get(2).cards().getFirst())));
  }
  @Test
  void draftSelectionHasNoPublicDeckCompatibilityConstructor() {
    assertTrue(java.util.Arrays.stream(DraftOfferings.Selection.class.getConstructors())
        .noneMatch(constructor -> java.util.Arrays.equals(constructor.getParameterTypes(),
            new Class<?>[] {PlayerSeat.class, List.class, Deck.class})));
  }

  @Test
  void randomSamplingIgnoresUnknownCopiesButRejectsInsufficientValidCopies() {
    List<CardCopy> onePool = new ArrayList<>(deck(1).copies());
    onePool.add(new CardCopy(new CardCopyId(new UUID(0, 999)), new CardId(999)));
    MatchEngine.StartMatch unknownExtra = new MatchEngine.StartMatch(new MatchId("unknown-extra"),
        deck(1), deck(6), catalog(), new RuleSet(Rule.RANDOM), 31L,
        java.time.Instant.parse("2026-01-01T00:00:00Z"), onePool, deck(6).copies());
    MatchEngine.MatchTransition accepted = MatchEngine.start(unknownExtra);
    assertTrue(accepted.accepted());
    assertTrue(accepted.state().hand(PlayerSeat.ONE).stream()
        .allMatch(card -> card.cardId().value() <= 10));

    List<CardCopy> fourValidOneUnknown = new ArrayList<>(deck(1).copies().subList(0, 4));
    fourValidOneUnknown.add(new CardCopy(new CardCopyId(new UUID(0, 1000)), new CardId(999)));
    MatchEngine.StartMatch insufficientValid = new MatchEngine.StartMatch(new MatchId("insufficient-valid"),
        deck(1), deck(6), catalog(), new RuleSet(Rule.RANDOM), 32L,
        java.time.Instant.parse("2026-01-01T00:00:00Z"), fourValidOneUnknown, deck(6).copies());
    assertEquals(MatchErrorCode.RANDOM_INSUFFICIENT_POOL,
        MatchEngine.start(insufficientValid).rejection().code());
  }

  @Test
  void draftChoicesAreRecordedAndGeneratedCardsRemainHouseOwnedAtSettlement() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("draft-transcript"),
        deck(1), deck(6), catalog(), new RuleSet(Rule.DRAFT), 33L,
        java.time.Instant.parse("2026-01-01T00:00:00Z"), deck(1).copies(), deck(6).copies(),
        List.of(0, 1, 2), List.of(2, 3, 4));
    MatchState state = MatchEngine.start(start).state();
    assertEquals(List.of(0, 1, 2), state.randomDecisions().stream()
        .filter(decision -> decision.label().startsWith("draft-choice-ONE-"))
        .map(RandomTranscript.Decision::selected).toList());
    assertTrue(state.houseOwnedCopies().containsAll(state.hand(PlayerSeat.ONE).stream()
        .map(HandCard::copyId).toList()));
    assertTrue(state.settlementCopies().values().stream().flatMap(List::stream)
        .noneMatch(copy -> state.houseOwnedCopies().contains(copy.copyId())));
  }

  @Test
  void comboCaptureEventsAreRowMajorWhileSourcesRemainCombo() {
    Board board = Board.empty()
        .place(Square.NORTH_WEST, copy(4), PlayerSeat.TWO)
        .place(Square.NORTH_EAST, copy(4), PlayerSeat.TWO)
        .place(Square.WEST, copy(5), PlayerSeat.TWO)
        .place(Square.EAST, copy(5), PlayerSeat.TWO);
    PlacementResolver.Resolution result = PlacementResolver.resolve(board, catalog(),
        new RuleSet(Rule.SAME, Rule.COMBO), copy(5), PlayerSeat.ONE, Square.CENTER);
    assertEquals(List.of(0, 2, 3, 5), result.captures().stream()
        .map(capture -> capture.square().index()).toList());
    assertEquals(List.of(PlacementResolver.CaptureSource.COMBO, PlacementResolver.CaptureSource.COMBO,
        PlacementResolver.CaptureSource.SAME, PlacementResolver.CaptureSource.SAME),
        result.captures().stream().map(PlacementResolver.Capture::source).toList());
  }

  @Test
  void randomStartRejectsInsufficientAndDuplicatePhysicalPools() {
    List<CardCopy> shortPool = deck(1).copies().subList(0, 4);
    MatchEngine.StartMatch shortStart = new MatchEngine.StartMatch(new MatchId("short"), deck(1), deck(6), catalog(),
        new RuleSet(Rule.RANDOM), 1L, java.time.Instant.parse("2026-01-01T00:00:00Z"),
        shortPool, deck(6).copies());
    assertEquals(MatchErrorCode.RANDOM_INSUFFICIENT_POOL, MatchEngine.start(shortStart).rejection().code());
    List<CardCopy> duplicate = new ArrayList<>(deck(1).copies());
    duplicate.set(1, duplicate.getFirst());
    MatchEngine.StartMatch duplicateStart = new MatchEngine.StartMatch(new MatchId("duplicate"), deck(1), deck(6),
        catalog(), new RuleSet(Rule.RANDOM), 1L, java.time.Instant.parse("2026-01-01T00:00:00Z"),
        duplicate, deck(6).copies());
    assertEquals(MatchErrorCode.RANDOM_DUPLICATE_COPY, MatchEngine.start(duplicateStart).rejection().code());
  }

 
  @Test
  void orderAndChaosProjectionExposeExactlyThePlayableCopy() {
    MatchState ordered = MatchEngine.start(new MatchEngine.StartMatch(new MatchId("order"), deck(1), deck(6),
        catalog(), new RuleSet(Rule.ORDER), 19L, java.time.Instant.parse("2026-01-01T00:00:00Z"))).state();
    MatchProjection orderProjection = MatchProjection.forViewer(ordered, ordered.currentSeat());
    assertEquals(List.of(ordered.hand(ordered.currentSeat()).getFirst().copyId()), orderProjection.legalCopyIds());

    MatchState chaos = MatchEngine.start(new MatchEngine.StartMatch(new MatchId("chaos-projection"), deck(1),
        deck(6), catalog(), new RuleSet(Rule.CHAOS), 20L, java.time.Instant.parse("2026-01-01T00:00:00Z"))).state();
    List<RandomTranscript.Decision> before = chaos.randomDecisions();
    MatchProjection chaosProjection = MatchProjection.forViewer(chaos, chaos.currentSeat());
    assertEquals(before, chaos.randomDecisions());
    assertEquals(List.of(chaos.chaosChoice(chaos.currentSeat())), chaosProjection.legalCopyIds());
  }
 
  @Test
  void suddenDeathRestartAdvancesOnceAndRefreshesThreeOpenSelections() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("sudden"), deck(1), deck(6),
        uniformCatalog(), new RuleSet(Rule.SUDDEN_DEATH, Rule.THREE_OPEN), 23L,
        java.time.Instant.parse("2026-01-01T00:00:00Z"));
    MatchState before = MatchEngine.start(start).state();
    MatchEngine.MatchTransition restart = finishDraw(before);
    assertTrue(restart.accepted());
    assertEquals(9, restart.state().revision());
    assertEquals(1, restart.state().consecutiveDraws());
    assertTrue(restart.state().board().emptySquares().size() == 9);
    for (PlayerSeat seat : PlayerSeat.values()) assertEquals(3, restart.state().revealedCards(seat).size());
    assertEquals(6, restart.state().randomDecisions().stream()
        .filter(decision -> decision.label().startsWith("sudden-death-three-open-1")).count());
  }

  @Test
  void suddenDeathStopsAfterFifthConsecutiveDraw() {
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("sudden-fifth"), deck(1), deck(6),
        uniformCatalog(), new RuleSet(Rule.SUDDEN_DEATH), 24L, java.time.Instant.parse("2026-01-01T00:00:00Z"));
    MatchState state = MatchEngine.start(start).state();
    for (int draw = 0; draw < 5; draw++) {
      MatchEngine.MatchTransition transition = finishDraw(state);
      assertTrue(transition.accepted());
      state = transition.state();
    }
    assertTrue(state.isFinished());
    assertEquals(MatchResult.Outcome.DRAW, state.result().outcome());
    assertEquals(5, state.consecutiveDraws());
  }

  private static MatchEngine.MatchTransition finishDraw(MatchState state) {
    for (int index = 0; index < 9; index++) {
      PlayerSeat actor = state.currentSeat();
      MatchEngine.MatchTransition transition = MatchEngine.apply(state,
          new MatchCommand.PlaceCard(actor, state.hand(actor).getFirst().copyId(), Square.of(index),
              state.revision(), UUID.randomUUID(), state.turnStartedAt().plusSeconds(1)));
      assertTrue(transition.accepted());
      state = transition.state();
    }
    return new MatchEngine.MatchTransition(state, List.of(), null);
  }

  private static CardCatalog uniformCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(new CardDefinition(new CardId(id), "No. " + id, "Card " + id, 1,
          CardType.NORMAL, 5, 5, 5, 5, 100));
    }
    return new CardCatalog(definitions, "uniform");
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
