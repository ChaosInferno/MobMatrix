package org.aincraft.mobmatrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.MobMatrixPlugin.BotTurnResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.MatchApplicationService.ApplyResult;
import org.aincraft.mobmatrix.bot.HeuristicBot;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class BotTurnIntegrationTest {

  @Test
  void independentBotStartAttemptsUseFreshMatchIdentities() {
    assertNotEquals(MobMatrixPlugin.freshBotMatchId(), MobMatrixPlugin.freshBotMatchId());
  }

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void botTurnAdvancesImmediatelyAfterAcceptedPlayerTransition() {
    MatchState beforeBot = afterPlayerOpening();
    BotTurnResult result =
        MobMatrixPlugin.advanceBotTurn(
            beforeBot,
            new HeuristicBot(),
            START.plusSeconds(2),
            command -> apply(beforeBot, command));

    assertTrue(result.applied());
    assertEquals(beforeBot.revision() + 1, result.state().revision());
    assertEquals(PlayerSeat.ONE, result.state().currentSeat());
    assertInstanceOf(MatchCommand.PlaceCard.class, result.command());
  }

  @Test
  void expiredBotTurnUsesTimeoutCommandInsteadOfRetryingExpiredPlacement() {
    MatchState beforeBot = afterPlayerOpening();
    BotTurnResult result =
        MobMatrixPlugin.advanceBotTurn(
            beforeBot,
            new HeuristicBot(),
            beforeBot.turnDeadline().plusMillis(1),
            command -> apply(beforeBot, command));

    assertTrue(result.applied());
    assertInstanceOf(MatchCommand.Timeout.class, result.command());
    assertEquals(PlayerSeat.ONE, result.state().currentSeat());
  }

  @Test
  void noLegalBotMoveWaitsUntilDeadlineThenReportsRejectedTimeout() {
    MatchState state = started();
    for (int index = 0; index < 5; index++) {
      MatchCommand.PlaceCard command =
          new MatchCommand.PlaceCard(
              PlayerSeat.TWO,
              state.hand(PlayerSeat.TWO).getFirst().copyId(),
              Square.of(index),
              state.revision(),
              UUID.randomUUID(),
              START.plusSeconds(index + 1L));
      state =
          MatchEngine.apply(state.withCurrentSeat(PlayerSeat.TWO), command)
              .state()
              .withCurrentSeat(PlayerSeat.TWO);
    }
    MatchState noLegalState = state;
    BotTurnResult waiting =
        MobMatrixPlugin.advanceBotTurn(
            state,
            new HeuristicBot(),
            START.plusSeconds(10),
            command -> apply(noLegalState, command));
    assertFalse(waiting.applied());
    assertTrue(waiting.waiting());

    BotTurnResult expired =
        MobMatrixPlugin.advanceBotTurn(
            state,
            new HeuristicBot(),
            noLegalState.turnDeadline().plusMillis(1),
            command -> apply(noLegalState, command));
    assertFalse(expired.applied());
    assertFalse(expired.waiting());
    assertEquals(Failure.INVALID_STATE, expired.result().failure());
    assertInstanceOf(MatchCommand.Timeout.class, expired.command());
  }

  private static MatchState afterPlayerOpening() {
    MatchState state = started();
    MatchCommand.PlaceCard opening =
        new MatchCommand.PlaceCard(
            PlayerSeat.ONE,
            state.hand(PlayerSeat.ONE).getFirst().copyId(),
            Square.CENTER,
            state.revision(),
            UUID.randomUUID(),
            START.plusSeconds(1));
    return MatchEngine.apply(state, opening).state();
  }

  private static MatchState started() {
    return MatchEngine.start(
            new MatchEngine.StartMatch(
                new MatchId("bot-turn"),
                deck(1),
                deck(6),
                catalog(),
                org.aincraft.mobmatrix.domain.RuleSet.empty(),
                23L,
                START))
        .state();
  }

  private static ApplyResult apply(MatchState state, MatchCommand command) {
    MatchEngine.MatchTransition transition = MatchEngine.apply(state, command);
    return new ApplyResult(
        transition,
        transition.accepted() ? Failure.NONE : Failure.INVALID_STATE,
        transition.state().revision());
  }

  private static Deck deck(int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index)));
    }
    return new Deck(copies);
  }

  private static CardCatalog catalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= 10; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100));
    }
    return new CardCatalog(definitions, "bot-turn");
  }
}
