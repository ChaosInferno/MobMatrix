package org.aincraft.mobmatrix.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.storage.Database;
import org.aincraft.mobmatrix.storage.SqliteCardRepository;
import org.junit.jupiter.api.Test;

final class MatchApplicationServiceTest {
  @Test
  void prepareEscrowAndSettlementAreIdempotent() {
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));

    MatchApplicationService.StartResult started = service.prepare(owner, copies, operation);
    assertTrue(started.escrowed());
    service.prepare(owner, copies, operation);
    service.settle(operation);
    service.settle(operation);
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
  }

  @Test
  void startRejectsCopySetThatDoesNotMatchPhysicalDeckBeforeEscrow() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, 1, 1, 1, 1, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match = new MatchEngine.StartMatch(new MatchId("copy-validation"), new Deck(one), new Deck(two),
        new CardCatalog(definitions, "validation-v1"), new MatchRules(), 5L,
        Instant.parse("2026-01-01T00:00:00Z"));
    List<CardCopyId> wrong = new ArrayList<>(one.stream().map(CardCopy::copyId).toList());
    wrong.set(0, new CardCopyId(UUID.randomUUID()));
    MatchApplicationService service = new MatchApplicationService(new RecordingCustody());
    var result = service.start(new MatchApplicationService.StartMatchRequest(match, oneOwner, wrong,
        UUID.randomUUID(), twoOwner, two.stream().map(CardCopy::copyId).toList(), UUID.randomUUID()));
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());
  }

  @Test
  void randomStartReservesTheExactCopiesInThePureStartState() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 10; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, 1, 1, 1, 1, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match = new MatchEngine.StartMatch(new MatchId("random-custody"), new Deck(one.subList(0, 5)),
        new Deck(two.subList(0, 5)), new CardCatalog(definitions, "random-v1"),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.RANDOM), 7L,
        Instant.parse("2026-01-01T00:00:00Z"), one, two);
    MatchEngine.MatchTransition pure = MatchEngine.start(match);
    List<CardCopyId> selectedOne = pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.ONE)
        .stream().map(card -> card.copyId()).toList();
    List<CardCopyId> selectedTwo = pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.TWO)
        .stream().map(card -> card.copyId()).toList();
    RecordingCustody custody = new RecordingCustody();
    var result = new MatchApplicationService(custody).start(new MatchApplicationService.StartMatchRequest(
        match, oneOwner, selectedOne, UUID.randomUUID(), twoOwner, selectedTwo, UUID.randomUUID()));
    assertTrue(result.accepted());
    assertEquals(List.of(selectedOne, selectedTwo), custody.preparedCopies);
  }
  @Test
  void reusingOperationForDifferentMatchIsRejectedByDurableMatchBinding() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);

    var first = new MatchApplicationService.StartMatchRequest(
        match("bound-one", oneOwner, oneCopies, twoOwner, twoCopies), oneOwner, oneCopies,
        oneOperation, twoOwner, twoCopies, twoOperation);
    assertTrue(service.start(first).accepted());

    var retry = new MatchApplicationService.StartMatchRequest(
        match("bound-two", oneOwner, oneCopies, twoOwner, twoCopies), oneOwner, oneCopies,
        oneOperation, twoOwner, twoCopies, twoOperation);
    var result = service.start(retry);
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());

    CopyOwner changedOwner = CopyOwner.player(UUID.randomUUID());
    var ownerRetry = new MatchApplicationService.StartMatchRequest(
        match("bound-one", changedOwner, oneCopies, twoOwner, twoCopies), changedOwner, oneCopies,
        oneOperation, twoOwner, twoCopies, twoOperation);
    var ownerResult = service.start(ownerRetry);
    assertFalse(ownerResult.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, ownerResult.failure());

  }
  @Test
  void operationCannotBePreparedAgainAfterSettlement() {
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = copies();

    assertTrue(service.prepare(owner, copies, operation).accepted());
    assertTrue(service.settle(operation).complete());

    MatchApplicationService.StartResult retry = service.prepare(owner, copies, operation);
    assertFalse(retry.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_STATE, retry.failure());
  }

  @Test
  void rejectedFirstPrepareDoesNotCreatePhantomCleanup() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    RecordingCustody custody = new RecordingCustody(true);
    var request = new MatchApplicationService.StartMatchRequest(
        match("reject-first", oneOwner, oneCopies, twoOwner, twoCopies), oneOwner, oneCopies,
        UUID.randomUUID(), twoOwner, twoCopies, UUID.randomUUID());

    var result = new MatchApplicationService(custody).start(request);
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.COPY_NOT_FOUND, result.failure());
    assertEquals(List.of("prepare"), custody.calls);
    assertTrue(result.pendingRecoveryCopyIds().isEmpty());
    assertTrue(result.missingCopyIds().isEmpty());
  }
  @Test
  void startupPreservesActiveEscrowAndContinuesAfterOneRecoveryFailure() {
    UUID activeOperation = UUID.randomUUID();
    UUID orphanOperation = UUID.randomUUID();
    UUID failingOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start = match("startup-active", owner, oneCopies, owner, twoCopies);
    MatchState active = MatchEngine.start(start).state();
    StartupCustody custody = new StartupCustody(activeOperation, start.matchId(), orphanOperation,
        failingOperation);
    MatchRepository repository = new MatchRepository() {
      @Override
      public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
        return java.util.Optional.empty();
      }
      public SaveResult save(long expectedRevision, MatchState state) {
        return SaveResult.success(state.revision());
      }

      @Override
      public List<MatchSnapshot> activeMatches() {
        return List.of(new MatchSnapshot(start.matchId(), active.revision(), "ACTIVE",
            new byte[] {1}, "{}"));
      }

      @Override
      public java.util.Optional<MatchState> rehydrate(MatchSnapshot snapshot,
          java.util.function.Function<String, CardCatalog> resolver) {
        return java.util.Optional.of(active);
      }
    };

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, repository).recoverStartup(id -> start.catalog(),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(List.of(orphanOperation, failingOperation), custody.recovered);
    assertEquals(2, result.recoveries().size());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR,
        result.recoveries().get(1).failure());
  }

  @Test
  void identicalStartRetryReturnsDurableActiveStateWithoutSavingAgain() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start = match("retry-active", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingMatchRepository repository = new RecordingMatchRepository();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody, repository);
    MatchApplicationService.StartMatchRequest request = new MatchApplicationService.StartMatchRequest(
        start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation);

    assertTrue(service.start(request).accepted());
    MatchApplicationService retryService = new MatchApplicationService(custody, repository);
    MatchApplicationService.MatchStartResult retry = retryService.start(request);

    assertTrue(retry.accepted());
    assertEquals(1, repository.saves);
    assertEquals(0L, retry.state().revision());
  }
  @Test
  void retryWithSwappedOperationIdsRejectsDurableOwnerAndCopyBinding() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start = match("swapped-operations", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    var request = new MatchApplicationService.StartMatchRequest(start, oneOwner, oneCopies,
        oneOperation, twoOwner, twoCopies, twoOperation);
    assertTrue(service.start(request).accepted());

    var swapped = new MatchApplicationService.StartMatchRequest(start, oneOwner, oneCopies,
        twoOperation, twoOwner, twoCopies, oneOperation);
    var result = service.start(swapped);

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());
  }

  @Test
  void draftRetryWithDifferentOperationIdsRejectsDurableEmptyCustodyBinding() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("draft-binding"),
        new Deck(oneCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), oneOwner)).toList()),
        new Deck(twoCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), twoOwner)).toList()),
        new CardCatalog(List.of(new CardDefinition(new CardId(1), "No. 1", "Card 1", 1,
            CardType.NORMAL, 1, 1, 1, 1, 0)), "draft-binding-catalog"),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.DRAFT), 17L,
        Instant.parse("2026-01-01T00:00:00Z"));
    RecordingMatchRepository repository = new RecordingMatchRepository();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody, repository);
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    var request = new MatchApplicationService.StartMatchRequest(start, oneOwner, List.of(),
        oneOperation, twoOwner, List.of(), twoOperation);
    assertTrue(service.start(request).accepted());

    MatchApplicationService retryService = new MatchApplicationService(new RecordingCustody(), repository);
    var conflicting = new MatchApplicationService.StartMatchRequest(start, oneOwner, List.of(),
        UUID.randomUUID(), twoOwner, List.of(), UUID.randomUUID());
    var result = retryService.start(conflicting);
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());
  }

  @Test
  void overlappingSeatCopiesAreRejectedBeforeReservation() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = new ArrayList<>(copies());
    twoCopies.set(0, oneCopies.get(0));
    MatchEngine.StartMatch start = match("overlap", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();

    var result = new MatchApplicationService(custody).start(
        new MatchApplicationService.StartMatchRequest(start, oneOwner, oneCopies,
            UUID.randomUUID(), twoOwner, twoCopies, UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_COPY_SET, result.failure());
    assertTrue(custody.calls.isEmpty());
    assertFalse(MatchEngine.start(start).accepted());
  }


  @Test
  void prepareEscrowTransitionFailureCleansUpDurableOperation() {
    ThrowingEscrowCustody custody = new ThrowingEscrowCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());

    MatchApplicationService.StartResult result = service.prepare(owner, copies(), operation);

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
  }

  @Test
  void draftStartRejectsHouseOwnerEvenWhenCustodySelectionIsEmpty() {
    CopyOwner house = CopyOwner.house();
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start = new MatchEngine.StartMatch(new MatchId("house-owner-draft"),
        new Deck(oneCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), house)).toList()),
        new Deck(twoCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), house)).toList()),
        new CardCatalog(List.of(new CardDefinition(new CardId(1), "No. 1", "Card 1", 1,
            CardType.NORMAL, 1, 1, 1, 1, 0)), "house-owner-draft-catalog"),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.DRAFT), 5L,
        Instant.parse("2026-01-01T00:00:00Z"));

    MatchApplicationService.MatchStartResult result = new MatchApplicationService(
        new RecordingCustody()).start(new MatchApplicationService.StartMatchRequest(start, house,
            List.of(), UUID.randomUUID(), house, List.of(), UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_OWNER, result.failure());
  }

  @Test
  void preparedOperationRecoveryQueuesExactCopiesInsteadOfRetryingEscrow() throws Exception {
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {7, 6, 5});
    var path = Files.createTempFile("mobmatrix-prepared-recovery", ".sqlite");
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      journal.prepare(operation, owner, List.of(copy), List.of(snapshot));

      var service = new org.aincraft.mobmatrix.inventory.CardInventoryService(
          new org.aincraft.mobmatrix.inventory.CardItemCodec("mobmatrix", "stable-server-secret"),
          id -> null, journal);
      CardCustodyPort.RecoveryResult result = service.recover(operation);

      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(List.of(copy), result.pendingCopyIds());
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING,
          journal.findReservation(operation).orElseThrow().status());
      assertEquals(1, journal.pendingRecovery().size());
      assertArrayEquals(snapshot.bytes(), journal.pendingRecovery().get(0).snapshot().bytes());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void swapStartReservesOriginalFiveCopiesAndSettlesToOriginalOwners() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch base = match("swap-custody", oneOwner, oneCopies, twoOwner, twoCopies);
    MatchEngine.StartMatch swap = new MatchEngine.StartMatch(base.matchId(), base.oneDeck(),
        base.twoDeck(), base.catalog(), new MatchRules(org.aincraft.mobmatrix.domain.Rule.SWAP),
        base.seed(), base.startTime());
    RecordingCustody custody = new RecordingCustody();

    MatchApplicationService.MatchStartResult result = new MatchApplicationService(custody).start(
        new MatchApplicationService.StartMatchRequest(swap, oneOwner, oneCopies,
            UUID.randomUUID(), twoOwner, twoCopies, UUID.randomUUID()));

    assertTrue(result.accepted());
    assertEquals(List.of(oneCopies, twoCopies), custody.preparedCopies);
    assertEquals(new java.util.HashSet<>(oneCopies),
        new java.util.HashSet<>(result.state().settlementCopies()
            .get(org.aincraft.mobmatrix.domain.PlayerSeat.ONE).stream().map(CardCopy::copyId).toList()));
    assertEquals(new java.util.HashSet<>(twoCopies),
        new java.util.HashSet<>(result.state().settlementCopies()
            .get(org.aincraft.mobmatrix.domain.PlayerSeat.TWO).stream().map(CardCopy::copyId).toList()));
  }

  @Test
  void initialPersistenceFailureLeavesNoMatchAndCleansCustody() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    FailingInitialRepository repository = new FailingInitialRepository();
    RecordingCustody custody = new RecordingCustody();

    MatchApplicationService.MatchStartResult result = new MatchApplicationService(custody, repository).start(
        new MatchApplicationService.StartMatchRequest(
            match("atomic-start-failure", oneOwner, oneCopies, twoOwner, twoCopies),
            oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertTrue(repository.find(new MatchId("atomic-start-failure")).isEmpty());
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
    assertEquals(CardCustodyPort.OperationStatus.RETURNED, custody.statuses.get(oneOperation));
    assertEquals(CardCustodyPort.OperationStatus.RETURNED, custody.statuses.get(twoOperation));
  }

  @Test
  void activeRetryRejectsCustodyAdapterWithAbsentDurableMetadata() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start = match("strict-retry-binding", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingMatchRepository repository = new RecordingMatchRepository();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchApplicationService.StartMatchRequest request = new MatchApplicationService.StartMatchRequest(
        start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation);

    assertTrue(new MatchApplicationService(new RecordingCustody(), repository).start(request).accepted());
    MatchApplicationService.MatchStartResult retry = new MatchApplicationService(
        new AbsentMetadataCustody(), repository).start(request);

    assertFalse(retry.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, retry.failure());
  }

  private static List<CardCopyId> copies() {
    return List.of(new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()));
  }

  private static MatchEngine.StartMatch match(String id, CopyOwner oneOwner,
      List<CardCopyId> oneCopies, CopyOwner twoOwner, List<CardCopyId> twoCopies) {
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, 1, 1, 1, 1, 0));
      one.add(new CardCopy(oneCopies.get(index - 1), new CardId(index), oneOwner));
      two.add(new CardCopy(twoCopies.get(index - 1), new CardId(index), twoOwner));
    }
    return new MatchEngine.StartMatch(new MatchId(id), new Deck(one), new Deck(two),
        new CardCatalog(definitions, id + "-catalog"), new MatchRules(), 5L,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static final class RecordingMatchRepository implements MatchRepository {
    private MatchState state;
    private MatchRepository.StartBinding binding;
    private int saves;

    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      saves++;
      if (this.state != null) return SaveResult.stale(this.state.revision());
      this.state = state;
      return SaveResult.success(state.revision());
    }

    @Override
    public void saveStartBinding(MatchRepository.StartBinding binding) {
      this.binding = binding;
    }

    @Override
    public java.util.Optional<MatchRepository.StartBinding> findStartBinding(MatchId matchId) {
      return java.util.Optional.ofNullable(binding);
    }
    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return state == null ? java.util.Optional.empty()
          : java.util.Optional.of(new MatchSnapshot(matchId, state.revision(), "ACTIVE",
              new byte[] {1}, "{}"));
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.ofNullable(state);
    }
  }

  private static final class StartupCustody implements CardCustodyPort {
    private final UUID active;
    private final MatchId matchId;
    private final UUID orphan;
    private final UUID failing;
    private final List<UUID> recovered = new ArrayList<>();

    private StartupCustody(UUID active, MatchId matchId, UUID orphan, UUID failing) {
      this.active = active;
      this.matchId = matchId;
      this.orphan = orphan;

      this.failing = failing;
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(active, orphan, failing);
    }

    @Override
    public java.util.Optional<MatchId> operationMatchId(UUID operationId) {
      return operationId.equals(active) ? java.util.Optional.of(matchId)
          : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.of(OperationStatus.ESCROWED);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.INVALID_STATE);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.INVALID_STATE);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      return ReturnResult.pending(operationId, List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      if (operationId.equals(failing)) throw new IllegalStateException("recovery failed");
      return RecoveryResult.complete(operationId, List.of());
    }
  }
  private static final class FailingInitialRepository implements MatchRepository {
    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      throw new AssertionError("start must use saveInitial");
    }

    @Override
    public SaveResult saveInitial(MatchState state, MatchRepository.StartBinding binding) {
      return new SaveResult(false, -1L, Failure.DATABASE_ERROR);
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }
  }

  private static final class AbsentMetadataCustody implements CardCustodyPort {
    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      throw new AssertionError("retry must reject before preparing custody");
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      throw new AssertionError("retry must reject before escrowing custody");
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new AssertionError("retry must not clean active custody");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      throw new AssertionError("retry must not recover active custody");
    }
  }

  private static final class ThrowingEscrowCustody implements CardCustodyPort {
    private final List<String> calls = new ArrayList<>();

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      calls.add("prepare");
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      calls.add("escrow");
      throw new IllegalStateException("journal transition failed");
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      calls.add("return");
      return ReturnResult.pending(operationId, List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }
  }

  private static final class RecordingCustody implements CardCustodyPort {
    private final boolean rejectPrepare;
    private final Map<UUID, Binding> matchBindings = new java.util.HashMap<>();
    private final Map<UUID, OperationStatus> statuses = new java.util.HashMap<>();
    @Override
    public java.util.Optional<CardCustodyPort.OperationBinding> operationBinding(UUID operationId) {
      Binding binding = matchBindings.get(operationId);
      if (binding == null) return java.util.Optional.empty();
      OperationStatus status = statuses.get(operationId);
      if (status == null) return java.util.Optional.empty();
      return java.util.Optional.of(new CardCustodyPort.OperationBinding(binding.owner(),
          binding.copyIds(), status, binding.matchId()));
    }
    private final List<List<CardCopyId>> preparedCopies = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private RecordingCustody() {
      this(false);
    }

    private RecordingCustody(boolean rejectPrepare) {
      this.rejectPrepare = rejectPrepare;
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return prepareInternal(owner, copyIds, operationId, null);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId,
        MatchId matchId) {
      return prepareInternal(owner, copyIds, operationId, matchId);
    }

    private PrepareResult prepareInternal(CopyOwner owner, List<CardCopyId> copyIds,
        UUID operationId, MatchId matchId) {
      if (!calls.contains("prepare")) calls.add("prepare");
      if (rejectPrepare) return PrepareResult.rejected(operationId, owner, copyIds,
          Failure.COPY_NOT_FOUND);
      Binding prior = matchBindings.putIfAbsent(operationId,
          new Binding(matchId, owner, List.copyOf(copyIds)));
      if (prior != null && (!java.util.Objects.equals(prior.matchId(), matchId)
          || !prior.owner().equals(owner) || !prior.copyIds().equals(copyIds))) {
        return PrepareResult.rejected(operationId, owner, copyIds, Failure.MISMATCHED_OPERATION);
      }
      if (statuses.get(operationId) == OperationStatus.RETURNED) {
        return PrepareResult.rejected(operationId, owner, copyIds, Failure.INVALID_STATE);
      }
      preparedCopies.add(List.copyOf(copyIds));
      statuses.putIfAbsent(operationId, OperationStatus.PREPARED);
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      if (!calls.contains("escrow")) calls.add("escrow");
      statuses.put(operationId, OperationStatus.ESCROWED);
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      if (!calls.contains("return")) calls.add("return");
      statuses.put(operationId, OperationStatus.RETURNED);
      return ReturnResult.returned(operationId, List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }
    private record Binding(MatchId matchId, CopyOwner owner, List<CardCopyId> copyIds) {}
  }
}

