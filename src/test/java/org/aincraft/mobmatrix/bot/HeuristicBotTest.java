package org.aincraft.mobmatrix.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchProjection;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.RandomTranscript;
import org.aincraft.mobmatrix.domain.Rule;
import org.aincraft.mobmatrix.domain.RuleSet;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class HeuristicBotTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  private final BotMovePolicy bot = new HeuristicBot();

  @Test
  void returnsLegalCardAndSquareAcceptedByEngine() {
    MatchState state = start(3L, RuleSet.empty());
    PlayerSeat seat = state.currentSeat();
    BotMovePolicy.Decision decision =
        bot.choose(state, seat, new RandomTranscript(17L), START.plusSeconds(1));
    BotMovePolicy.Command command = assertInstanceOf(BotMovePolicy.Command.class, decision);
    assertTrue(
        state.hand(seat).stream()
            .map(HandCard::copyId)
            .toList()
            .contains(command.command().copyId()));
    assertTrue(state.board().isEmpty(command.command().square()));
    assertEquals(state.revision(), command.command().expectedRevision());
    assertEquals(seat, command.command().actor());
    assertTrue(MatchEngine.apply(state, command.command()).accepted());
  }

  @Test
  void neverReturnsUnavailableCopyOrOccupiedSquare() {
    MatchState state = start(5L, RuleSet.empty());
    PlayerSeat seat = state.currentSeat();
    MatchCommand.PlaceCard first =
        ((BotMovePolicy.Command)
                bot.choose(state, seat, new RandomTranscript(19L), START.plusSeconds(1)))
            .command();
    state = MatchEngine.apply(state, first).state();
    seat = state.currentSeat();
    MatchCommand.PlaceCard second =
        ((BotMovePolicy.Command)
                bot.choose(state, seat, new RandomTranscript(19L), START.plusSeconds(2)))
            .command();
    assertFalse(second.copyId().equals(first.copyId()));
    assertTrue(state.hand(seat).stream().map(HandCard::copyId).anyMatch(second.copyId()::equals));
    assertTrue(state.board().isEmpty(second.square()));
  }

  @Test
  void prefersImmediateCaptureWhenOtherwiseTied() {
    CardCatalog catalog = captureCatalog();
    MatchState state =
        MatchEngine.start(
                new MatchEngine.StartMatch(
                    new MatchId("capture"), deck(1), deck(6), catalog, RuleSet.empty(), 2L, START))
            .state();
    PlayerSeat first = state.currentSeat();
    MatchCommand.PlaceCard opening =
        new MatchCommand.PlaceCard(
            first,
            state.hand(first).getFirst().copyId(),
            Square.SOUTH_WEST,
            state.revision(),
            UUID.randomUUID(),
            START.plusSeconds(1));
    state = MatchEngine.apply(state, opening).state();
    PlayerSeat opponent = state.currentSeat();
    MatchCommand.PlaceCard target =
        new MatchCommand.PlaceCard(
            opponent,
            state.hand(opponent).getFirst().copyId(),
            Square.WEST,
            state.revision(),
            UUID.randomUUID(),
            START.plusSeconds(2));
    state = MatchEngine.apply(state, target).state().withCurrentSeat(first);

    MatchCommand.PlaceCard selected =
        ((BotMovePolicy.Command)
                bot.choose(state, first, new RandomTranscript(23L), START.plusSeconds(3)))
            .command();
    assertTrue(state.board().isEmpty(selected.square()));
    MatchEngine.MatchTransition result = MatchEngine.apply(state, selected);
    assertTrue(result.accepted());
    assertTrue(result.events().contains("CARD_CAPTURED"));
    assertEquals(first, result.state().board().ownerOf(Square.WEST));
  }

  @Test
  void sameSeedAndStateAreDeterministicAndDoNotMutatePredecessorTranscript() {
    MatchState state = start(11L, RuleSet.empty());
    RandomTranscript transcript = new RandomTranscript(91L);
    List<RandomTranscript.Decision> before = transcript.decisions();
    List<RandomTranscript.Decision> stateBefore = state.randomDecisions();
    BotMovePolicy.Decision left =
        bot.choose(state, state.currentSeat(), transcript, START.plusSeconds(1));
    BotMovePolicy.Decision right =
        bot.choose(state, state.currentSeat(), transcript, START.plusSeconds(1));
    assertEquals(left, right);
    assertEquals(before, transcript.decisions());
    assertEquals(stateBefore, state.randomDecisions());
  }

  @Test
  void rejectsReplayWithMissingBotTieBreakDecision() {
    MatchState state = start(37L, RuleSet.empty());
    RandomTranscript replay = RandomTranscript.replay(List.of());

    assertThrows(
        IllegalStateException.class,
        () -> bot.choose(state, state.currentSeat(), replay, START.plusSeconds(1)));
  }

  @Test
  void reportsTypedNoLegalMoveWhenOwnHandIsExhausted() {
    MatchState state = start(13L, RuleSet.empty());
    PlayerSeat seat = state.currentSeat();
    for (int index = 0; index < 5; index++) {
      MatchCommand.PlaceCard placement =
          new MatchCommand.PlaceCard(
              seat,
              state.hand(seat).getFirst().copyId(),
              Square.of(index),
              state.revision(),
              UUID.randomUUID(),
              START.plusSeconds(index + 1L));
      state = MatchEngine.apply(state, placement).state().withCurrentSeat(seat);
    }
    BotMovePolicy.NoLegalMove noMove =
        assertInstanceOf(
            BotMovePolicy.NoLegalMove.class,
            bot.choose(state, seat, new RandomTranscript(29L), START.plusSeconds(10)));
    assertEquals(seat, noMove.seat());
    assertEquals(state.revision(), noMove.revision());
  }

  @Test
  void selectsFromMaskedProjectionWithoutOpponentHandData() {
    MatchState state = start(17L, RuleSet.empty());
    PlayerSeat seat = state.currentSeat();
    MatchProjection projection = MatchProjection.forViewer(state, seat);
    assertTrue(
        projection.opponentHandSlots().stream()
            .allMatch(slot -> slot.cardId() == null && slot.copyId() == null));
    assertNotNull(bot.choose(state, seat, new RandomTranscript(31L), START.plusSeconds(1)));
  }

  @Test
  void completesMatchesUnderStandardAndAdvancedProfiles() {
    for (RuleSet rules :
        List.of(
            RuleSet.empty(),
            new RuleSet(Rule.REVERSE),
            new RuleSet(Rule.SAME),
            new RuleSet(Rule.PLUS),
            new RuleSet(Rule.ASCENSION))) {
      MatchState state = start(41L + rules.size(), rules);
      int moves = 0;
      while (!state.isFinished()) {
        PlayerSeat seat = state.currentSeat();
        BotMovePolicy.Decision decision =
            bot.choose(
                state,
                seat,
                new RandomTranscript(101L + moves),
                state.turnStartedAt().plusSeconds(1));
        MatchCommand.PlaceCard command =
            assertInstanceOf(BotMovePolicy.Command.class, decision).command();
        MatchEngine.MatchTransition transition = MatchEngine.apply(state, command);
        assertTrue(transition.accepted(), () -> rules + " rejected " + transition.rejection());
        state = transition.state();
        assertTrue(++moves <= 9, "bot did not complete board");
      }
      assertEquals(9, moves, rules.toString());
      assertNotNull(state.result());
    }
  }

  private static MatchState start(long seed, RuleSet rules) {
    return MatchEngine.start(
            new MatchEngine.StartMatch(
                new MatchId("bot-" + seed), deck(1), deck(6), fixtureCatalog(), rules, seed, START))
        .state();
  }

  private static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index)));
    }
    return new Deck(copies);
  }

  private static CardCatalog fixtureCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id),
              "No. " + id,
              "Card " + id,
              1,
              CardType.NORMAL,
              id == 1 ? 10 : 1,
              id == 2 ? 10 : 1,
              id == 2 ? 10 : 1,
              1,
              100));
    }
    return new CardCatalog(definitions, "bot-test");
  }

  private static CardCatalog captureCatalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      int value = id == 6 ? 1 : id == 1 ? 10 : 5;
      definitions.add(
          new CardDefinition(
              new CardId(id),
              "No. " + id,
              "Card " + id,
              1,
              CardType.NORMAL,
              value,
              value,
              value,
              value,
              100));
    }
    return new CardCatalog(definitions, "bot-capture-test");
  }
}
