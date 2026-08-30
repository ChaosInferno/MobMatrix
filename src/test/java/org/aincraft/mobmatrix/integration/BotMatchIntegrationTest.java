package org.aincraft.mobmatrix.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.CardCustodyPort.CustodyResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationBinding;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.CardCustodyPort.PrepareResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.RecoveryResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.ReturnResult;
import org.aincraft.mobmatrix.application.InMemoryMatchRepository;
import org.aincraft.mobmatrix.application.MatchApplicationService;
import org.aincraft.mobmatrix.application.MatchApplicationService.ApplyResult;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.bot.BotMovePolicy;
import org.aincraft.mobmatrix.bot.HeuristicBot;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class BotMatchIntegrationTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void botMatchUsesOnlyPlayerCustodyAndSettlesOriginalOwners() {
    CopyOwner player = CopyOwner.player(UUID.randomUUID());
    List<CardCopy> playerCopies = deckCopies(player, 1);
    List<CardCopy> houseCopies = deckCopies(CopyOwner.house(), 20);
    MatchId matchId = new MatchId("bot-integration");
    CardCatalog catalog = catalog(25);
    MatchEngine.StartMatch start =
        new MatchEngine.StartMatch(
            matchId,
            new Deck(playerCopies),
            new Deck(houseCopies),
            catalog,
            new MatchRules(java.util.Set.of()),
            41L,
            START,
            playerCopies,
            houseCopies);
    UUID playerOperation = UUID.randomUUID();
    UUID houseOperation = UUID.randomUUID();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service =
        new MatchApplicationService(custody, new InMemoryMatchRepository());

    MatchApplicationService.MatchStartResult started =
        service.startBot(
            new MatchApplicationService.BotStartMatchRequest(
                start,
                player,
                playerCopies.stream().map(CardCopy::copyId).toList(),
                playerOperation,
                houseOperation));

    assertTrue(started.accepted());
    assertEquals(List.of(playerOperation), custody.prepared);
    assertEquals(List.of(playerOperation), custody.escrowed);
    assertTrue(
        started
            .state()
            .houseOwnedCopies()
            .containsAll(houseCopies.stream().map(CardCopy::copyId).toList()));

    MatchState state = started.state();
    HeuristicBot bot = new HeuristicBot();
    int transitions = 0;
    while (!state.isFinished() && transitions++ < 12) {
      MatchCommand command;
      if (state.currentSeat() == PlayerSeat.ONE) {
        command =
            new MatchCommand.PlaceCard(
                PlayerSeat.ONE,
                state.hand(PlayerSeat.ONE).getFirst().copyId(),
                firstEmpty(state),
                state.revision(),
                UUID.randomUUID(),
                state.turnStartedAt());
      } else {
        BotMovePolicy.Decision decision =
            bot.choose(state, PlayerSeat.TWO, state.transcript(), state.turnStartedAt());
        command =
            decision instanceof BotMovePolicy.Command selected
                ? selected.command()
                : new MatchCommand.Timeout(
                    PlayerSeat.TWO,
                    state.revision(),
                    UUID.randomUUID(),
                    state.turnDeadline().plusMillis(1));
      }
      ApplyResult applied = service.apply(state, command);
      assertTrue(applied.accepted(), () -> "bot match transition failed: " + applied.failure());
      state = applied.transition().state();
    }

    assertTrue(state.isFinished(), "the playable bot match must settle on the board");
    assertTrue(service.settle(playerOperation).complete());
    assertEquals(List.of(playerOperation), custody.returned);
    assertFalse(custody.returned.contains(houseOperation));
    assertTrue(
        state.settlementCopies().values().stream()
            .flatMap(List::stream)
            .noneMatch(copy -> copy.owner().isHouse()));
  }

  private static Square firstEmpty(MatchState state) {
    for (int index = 0; index < state.board().cells().size(); index++) {
      if (state.board().cells().get(index) == null) return Square.of(index);
    }
    throw new AssertionError("board has no empty square");
  }

  private static List<CardCopy> deckCopies(CopyOwner owner, int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(
              new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index), owner));
    }
    return List.copyOf(copies);
  }

  private static CardCatalog catalog(int count) {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int id = 1; id <= count; id++) {
      definitions.add(
          new CardDefinition(
              new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100));
    }
    return new CardCatalog(definitions, "bot-integration-catalog");
  }

  private static final class RecordingCustody implements CardCustodyPort {
    private final Map<UUID, OperationBinding> operations = new HashMap<>();
    private final List<UUID> prepared = new ArrayList<>();
    private final List<UUID> escrowed = new ArrayList<>();
    private final List<UUID> returned = new ArrayList<>();

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return prepare(owner, copyIds, operationId, null);
    }

    @Override
    public PrepareResult prepare(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      operations.put(
          operationId, new OperationBinding(owner, copyIds, OperationStatus.PREPARED, matchId));
      prepared.add(operationId);
      List<SerializedItemSnapshot> snapshots =
          copyIds.stream().map(copy -> new SerializedItemSnapshot(copy, new byte[] {1})).toList();
      return PrepareResult.success(operationId, owner, copyIds, snapshots);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      OperationBinding binding = operations.get(operationId);
      if (binding == null)
        return CustodyResult.rejected(
            operationId, OperationStatus.PREPARED, Failure.MISMATCHED_OPERATION);
      operations.put(
          operationId,
          new OperationBinding(
              binding.owner(), binding.copyIds(), OperationStatus.ESCROWED, binding.matchId()));
      escrowed.add(operationId);
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      OperationBinding binding = operations.get(operationId);
      if (binding == null)
        return new ReturnResult(
            operationId,
            OperationStatus.ABORTED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Failure.MISMATCHED_OPERATION);
      operations.put(
          operationId,
          new OperationBinding(
              binding.owner(), binding.copyIds(), OperationStatus.RETURNED, binding.matchId()));
      returned.add(operationId);
      return ReturnResult.returned(operationId, binding.copyIds());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }

    @Override
    public Optional<OperationBinding> operationBinding(UUID operationId) {
      return Optional.ofNullable(operations.get(operationId));
    }
  }
}
