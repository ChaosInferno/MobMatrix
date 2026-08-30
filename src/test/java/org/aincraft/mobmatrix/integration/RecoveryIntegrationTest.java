package org.aincraft.mobmatrix.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.CardCustodyPort.CustodyResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationBinding;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.CardCustodyPort.PrepareResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.RecoveryResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.ReturnResult;
import org.aincraft.mobmatrix.application.MatchApplicationService;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.storage.Database;
import org.aincraft.mobmatrix.storage.SqliteMatchRepository;
import org.junit.jupiter.api.Test;

final class RecoveryIntegrationTest {
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  private static final String SECRET = "recovery-integration-secret";

  @Test
  void restartRehydratesMatchAndPreservesEveryBoundNonterminalCustodyStatus() throws Exception {
    for (OperationStatus status :
        List.of(
            OperationStatus.PREPARED, OperationStatus.ESCROWED, OperationStatus.RETURN_PENDING)) {
      var path = Files.createTempFile("mobmatrix-recovery-", ".sqlite");
      try {
        CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
        CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
        List<CardCopy> oneCopies = copies(oneOwner, 1);
        List<CardCopy> twoCopies = copies(twoOwner, 6);
        CardCatalog catalog = catalog(10);
        MatchId matchId = new MatchId("recovery-" + status.name().toLowerCase());
        MatchEngine.StartMatch start =
            new MatchEngine.StartMatch(
                matchId,
                new Deck(oneCopies),
                new Deck(twoCopies),
                catalog,
                new MatchRules(Set.of()),
                77L,
                START,
                oneCopies,
                twoCopies);
        UUID oneOperation = UUID.randomUUID();
        UUID twoOperation = UUID.randomUUID();
        RestartableCustody custody = new RestartableCustody();

        try (Database database = new Database(path)) {
          MatchApplicationService service =
              new MatchApplicationService(custody, new SqliteMatchRepository(database, SECRET));
          MatchApplicationService.MatchStartResult started =
              service.start(
                  new MatchApplicationService.StartMatchRequest(
                      start,
                      oneOwner,
                      oneCopies.stream().map(CardCopy::copyId).toList(),
                      oneOperation,
                      twoOwner,
                      twoCopies.stream().map(CardCopy::copyId).toList(),
                      twoOperation));
          assertTrue(started.accepted());
          custody.setStatus(oneOperation, status);
          custody.setStatus(twoOperation, status);
        }

        try (Database database = new Database(path)) {
          MatchApplicationService restarted =
              new MatchApplicationService(custody, new SqliteMatchRepository(database, SECRET));
          MatchApplicationService.StartupRecoveryResult recovered =
              restarted.recoverStartup(snapshot -> catalog, START.plusSeconds(1));

          assertEquals(1, recovered.matches().size());
          assertEquals(matchId, recovered.matches().getFirst().matchId());
          assertEquals(0L, recovered.matches().getFirst().revision());
          assertEquals(2, recovered.recoveries().size());
          assertTrue(
              recovered.recoveries().stream()
                  .allMatch(
                      result ->
                          result.status() == OperationStatus.RETURN_PENDING
                              && result.failure() == Failure.DATABASE_ERROR));
          assertTrue(
              custody.recovered.isEmpty(), "active bound custody must not be returned on restart");
          assertFalse(recovered.matches().getFirst().isFinished());
        }
      } finally {
        Files.deleteIfExists(path);
      }
    }
  }

  private static List<CardCopy> copies(CopyOwner owner, int firstId) {
    List<CardCopy> result = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      result.add(
          new CardCopy(
              new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index), owner));
    }
    return List.copyOf(result);
  }

  private static CardCatalog catalog(int count) {
    List<CardDefinition> result = new ArrayList<>();
    for (int id = 1; id <= count; id++) {
      result.add(
          new CardDefinition(
              new CardId(id), "No. " + id, "Card " + id, 1, CardType.NORMAL, 1, 1, 1, 1, 100));
    }
    return new CardCatalog(result, "recovery-integration-catalog");
  }

  private static final class RestartableCustody implements CardCustodyPort {
    private final Map<UUID, OperationBinding> operations = new HashMap<>();
    private final List<UUID> recovered = new ArrayList<>();

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return prepare(owner, copyIds, operationId, null);
    }

    @Override
    public PrepareResult prepare(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      operations.put(
          operationId, new OperationBinding(owner, copyIds, OperationStatus.PREPARED, matchId));
      List<SerializedItemSnapshot> snapshots =
          copyIds.stream()
              .map(copy -> new SerializedItemSnapshot(copy, new byte[] {1, 2}))
              .toList();
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
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      recovered.add(operationId);
      OperationBinding binding = operations.get(operationId);
      return binding == null
          ? new ReturnResult(
              operationId,
              OperationStatus.ABORTED,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              Failure.MISMATCHED_OPERATION)
          : ReturnResult.pending(
              operationId, binding.copyIds(), List.of(), binding.copyIds(), binding.copyIds());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      return RecoveryResult.none(operationId);
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.copyOf(operations.keySet());
    }

    @Override
    public Optional<OperationStatus> operationStatus(UUID operationId) {
      return Optional.ofNullable(operations.get(operationId)).map(OperationBinding::status);
    }

    @Override
    public Optional<MatchId> operationMatchId(UUID operationId) {
      return Optional.ofNullable(operations.get(operationId)).map(OperationBinding::matchId);
    }

    @Override
    public Optional<OperationBinding> operationBinding(UUID operationId) {
      return Optional.ofNullable(operations.get(operationId));
    }

    private void setStatus(UUID operationId, OperationStatus status) {
      OperationBinding binding = operations.get(operationId);
      operations.put(
          operationId,
          new OperationBinding(binding.owner(), binding.copyIds(), status, binding.matchId()));
    }
  }
}
