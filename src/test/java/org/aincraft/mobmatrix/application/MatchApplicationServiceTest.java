package org.aincraft.mobmatrix.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
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
    List<CardCopyId> copies =
        List.of(
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()));

    MatchApplicationService.StartResult started = service.prepare(owner, copies, operation);
    assertTrue(started.escrowed());
    service.prepare(owner, copies, operation);
    service.settle(operation);

    service.settle(operation);
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
  }

  @Test
  void cancellationReturnsBothReservedOperationsDeletesMatchAndIsIdempotent() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start = match("cancel", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();
    InMemoryMatchRepository repository = new InMemoryMatchRepository();
    MatchApplicationService service = new MatchApplicationService(custody, repository);
    assertTrue(
        service
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation))
            .accepted());

    MatchApplicationService.CancelResult first =
        service.cancel(start.matchId(), List.of(oneOperation, twoOperation));

    assertTrue(first.cancelled());
    assertTrue(first.complete());
    assertEquals(OperationStatus.RETURNED, custody.statuses.get(oneOperation));
    assertEquals(OperationStatus.RETURNED, custody.statuses.get(twoOperation));
    assertTrue(repository.activeMatches().isEmpty());

    MatchApplicationService.CancelResult repeated =
        service.cancel(start.matchId(), List.of(oneOperation, twoOperation));

    assertTrue(repeated.cancelled());
    assertTrue(repeated.complete());
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
  }

  @Test
  void cancellationRetainsMatchWhenCardReturnIsPending() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start =
        match("cancel-pending", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody(false, true);
    InMemoryMatchRepository repository = new InMemoryMatchRepository();
    MatchApplicationService service = new MatchApplicationService(custody, repository);
    assertTrue(
        service
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation))
            .accepted());

    MatchApplicationService.CancelResult result =
        service.cancel(start.matchId(), List.of(oneOperation, twoOperation));

    assertTrue(!result.cancelled());
    assertTrue(!result.complete());
    assertEquals(
        List.of(start.matchId()),
        repository.activeMatches().stream().map(MatchRepository.MatchSnapshot::matchId).toList());
  }

  @Test
  void oneArgumentServicePersistsApplyAndEnforcesCas() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start =
        match("one-arg-persistence", oneOwner, oneCopies, twoOwner, twoCopies);
    MatchApplicationService service = new MatchApplicationService(new RecordingCustody());
    assertTrue(
        service
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start,
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()))
            .accepted());
    MatchState state = MatchEngine.start(start).state();
    PlayerSeat actor = state.currentSeat();
    CardCopyId copy = state.hand(actor).getFirst().copyId();
    MatchCommand.PlaceCard command =
        new MatchCommand.PlaceCard(
            actor, copy, Square.CENTER, state.revision(), UUID.randomUUID(), state.turnStartedAt());
    assertTrue(service.apply(state, command).accepted());
    MatchApplicationService.ApplyResult stale = service.apply(state, command);
    assertFalse(stale.accepted());
    assertEquals(CardCustodyPort.Failure.STALE_REVISION, stale.failure());
  }

  @Test
  void applyUsesPersistedCanonicalStateInsteadOfCallerSnapshot() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch canonicalStart =
        match("canonical-apply", oneOwner, copies(), twoOwner, copies());
    MatchState canonical = MatchEngine.start(canonicalStart).state();
    MatchEngine.StartMatch divergentStart =
        match("canonical-apply", oneOwner, copies(), twoOwner, copies());
    MatchState divergent = MatchEngine.start(divergentStart).state();
    CanonicalMatchRepository repository = new CanonicalMatchRepository(canonical);
    MatchState divergentState = divergent;
    MatchCommand.PlaceCard command =
        new MatchCommand.PlaceCard(
            divergentState.currentSeat(),
            divergentState.hand(divergentState.currentSeat()).getFirst().copyId(),
            Square.CENTER,
            divergentState.revision(),
            UUID.randomUUID(),
            divergentState.turnStartedAt());

    MatchApplicationService.ApplyResult result =
        new MatchApplicationService(new RecordingCustody(), repository)
            .apply(divergentState, command);

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_STATE, result.failure());
    assertEquals(canonical, repository.state);
    assertEquals(0, repository.saves);
  }

  @Test
  void applyPreservesDatabaseAndInvalidStateSaveFailures() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    MatchState canonical =
        MatchEngine.start(match("apply-save-failure", oneOwner, copies(), twoOwner, copies()))
            .state();
    MatchCommand.PlaceCard command =
        new MatchCommand.PlaceCard(
            canonical.currentSeat(),
            canonical.hand(canonical.currentSeat()).getFirst().copyId(),
            Square.CENTER,
            canonical.revision(),
            UUID.randomUUID(),
            canonical.turnStartedAt());

    for (MatchRepository.Failure failure :
        List.of(MatchRepository.Failure.DATABASE_ERROR, MatchRepository.Failure.INVALID_STATE)) {
      MatchApplicationService.ApplyResult result =
          new MatchApplicationService(
                  new RecordingCustody(), new SaveFailureMatchRepository(canonical, failure))
              .apply(canonical, command);
      assertEquals(
          failure == MatchRepository.Failure.DATABASE_ERROR
              ? CardCustodyPort.Failure.DATABASE_ERROR
              : CardCustodyPort.Failure.INVALID_STATE,
          result.failure());
    }
  }

  @Test
  void applyCatchesThrownSaveAsDatabaseError() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    MatchState canonical =
        MatchEngine.start(match("apply-thrown-save", oneOwner, copies(), twoOwner, copies()))
            .state();
    MatchCommand.PlaceCard command =
        new MatchCommand.PlaceCard(
            canonical.currentSeat(),
            canonical.hand(canonical.currentSeat()).getFirst().copyId(),
            Square.CENTER,
            canonical.revision(),
            UUID.randomUUID(),
            canonical.turnStartedAt());

    MatchApplicationService.ApplyResult result =
        new MatchApplicationService(
                new RecordingCustody(), new ThrowingSaveMatchRepository(canonical))
            .apply(canonical, command);

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
  }

  @Test
  void applyClassifiesCanonicalLoadFailureAsDatabaseError() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    MatchState state =
        MatchEngine.start(match("canonical-load-failure", oneOwner, copies(), twoOwner, copies()))
            .state();
    MatchCommand.PlaceCard command =
        new MatchCommand.PlaceCard(
            state.currentSeat(),
            state.hand(state.currentSeat()).getFirst().copyId(),
            Square.CENTER,
            state.revision(),
            UUID.randomUUID(),
            state.turnStartedAt());
    MatchApplicationService.ApplyResult result =
        new MatchApplicationService(new RecordingCustody(), new CanonicalLoadFailingRepository())
            .apply(state, command);
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
  }

  @Test
  void startupReportsCustodyEnumerationFailureWithoutBlockingMatchLoad() {
    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(new EnumerationFailingCustody(), new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.custodyFailure());
    assertTrue(result.matches().isEmpty());
    assertTrue(result.recoveries().isEmpty());
  }

  @Test
  void startupReportsBlankRawIdentityAsPendingWithoutRecover() {
    String rawOperation = "";
    List<CardCopyId> expected = copies().subList(0, 3);
    MalformedStartupCustody custody = new MalformedStartupCustody(rawOperation, expected);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertTrue(recovery.operationId() == null);
    assertEquals(rawOperation, recovery.rawOperationId());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, recovery.status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void blankRawRecoveryIdentityIsRepresentable() {
    List<CardCopyId> expected = copies().subList(0, 2);
    CardCustodyPort.RecoveryResult recovery =
        CardCustodyPort.RecoveryResult.malformed("", expected);
    assertTrue(recovery.operationId() == null);
    assertEquals("", recovery.rawIdentity().orElseThrow());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
  }

  @Test
  void startupGenericMetadataFailureUsesEnumeratedCopyIds() {
    UUID operation = UUID.randomUUID();
    List<CardCopyId> expected = copies();
    GenericStartupFailureCustody custody =
        new GenericStartupFailureCustody(operation, expected, false);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(operation, recovery.operationId());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void startupGenericRecoverFailureUsesEnumeratedCopyIds() {
    UUID operation = UUID.randomUUID();
    List<CardCopyId> expected = copies();
    GenericStartupFailureCustody custody =
        new GenericStartupFailureCustody(operation, expected, true);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(operation, recovery.operationId());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertEquals(List.of(operation), custody.recovered);
  }

  @Test
  void startupRetainsBadMatchRowCustodyAndLoadsHealthyRows() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch healthyStart =
        match("healthy-active-row", owner, copies(), owner, copies());
    MatchState healthy = MatchEngine.start(healthyStart).state();
    MatchId badMatch = new MatchId("bad-active-row");
    UUID operation = UUID.randomUUID();
    BadRowMatchRepository repository =
        new BadRowMatchRepository(healthyStart.matchId(), healthy, badMatch);
    BoundEscrowCustody custody = new BoundEscrowCustody(operation, badMatch);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, repository)
            .recoverStartup(id -> healthyStart.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(List.of(healthy), result.matches());
    assertEquals(
        List.of(
            new MatchApplicationService.MatchFailure(
                badMatch, CardCustodyPort.Failure.DATABASE_ERROR)),
        result.matchFailures());
    assertEquals(1, result.recoveries().size());
    assertEquals(
        CardCustodyPort.OperationStatus.RETURN_PENDING, result.recoveries().getFirst().status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.recoveries().getFirst().failure());
  }

  @Test
  void startupEntryAcceptsBlankRawIdentity() {
    List<CardCopyId> expected = copies().subList(0, 2);
    CardCustodyPort.StartupEntry entry = CardCustodyPort.StartupEntry.malformed("", expected);
    assertEquals("", entry.rawOperationId());
    assertTrue(entry.operationId().isEmpty());
    assertEquals(expected, entry.expectedCopyIds());
  }

  @Test
  void startupReportsActiveMatchRepositoryFailureWithoutReleasingBoundEscrow() {
    UUID operation = UUID.randomUUID();
    MatchId matchId = new MatchId("active-match");
    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(
                new BoundEscrowCustody(operation, matchId), new ActiveMatchesFailingRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.matchFailure());
    assertEquals(1, result.recoveries().size());
    assertEquals(
        CardCustodyPort.OperationStatus.RETURN_PENDING, result.recoveries().getFirst().status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.recoveries().getFirst().failure());
  }

  @Test
  void startupReportsEachMatchRehydrationFailureAndPreservesBoundEscrow() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch failedStart =
        match("startup-rehydrate-failed", owner, copies(), owner, copies());
    MatchEngine.StartMatch healthyStart =
        match("startup-rehydrate-healthy", owner, copies(), owner, copies());
    MatchState healthy = MatchEngine.start(healthyStart).state();
    UUID failedOperation = UUID.randomUUID();
    RehydrationFailureCustody custody =
        new RehydrationFailureCustody(failedOperation, failedStart.matchId());
    MatchRepository repository =
        new StartupRehydrationRepository(
            failedStart.matchId(), healthyStart.matchId(), healthy, false);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, repository)
            .recoverStartup(id -> healthyStart.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(List.of(healthy), result.matches());
    assertEquals(
        List.of(
            new MatchApplicationService.MatchFailure(
                failedStart.matchId(), CardCustodyPort.Failure.INVALID_STATE)),
        result.matchFailures());
    assertEquals(1, result.recoveries().size());
    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(failedOperation, recovery.operationId());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, recovery.status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertTrue(recovery.expectedCopyIds().isEmpty());
    assertTrue(recovery.pendingCopyIds().isEmpty());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void startupReportsThrownMatchRehydrationFailureWithMatchId() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        match("startup-rehydrate-throws", owner, copies(), owner, copies());
    MatchState state = MatchEngine.start(start).state();
    MatchRepository repository =
        new StartupRehydrationRepository(start.matchId(), null, state, false, true);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(new EnumerationFailingCustody(), repository)
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertTrue(result.matches().isEmpty());
    assertEquals(
        List.of(
            new MatchApplicationService.MatchFailure(
                start.matchId(), CardCustodyPort.Failure.DATABASE_ERROR)),
        result.matchFailures());
  }

  @Test
  void startupReportsRejectedTimeoutAndDoesNotExposeUnadvancedState() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        match("startup-timeout-rejected", owner, copies(), owner, copies());
    MatchState overdue = MatchEngine.start(start).state();
    MatchRepository repository =
        new StartupRehydrationRepository(start.matchId(), null, overdue, true);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(new EnumerationFailingCustody(), repository)
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:05:00Z"));

    assertTrue(result.matches().isEmpty());
    assertEquals(
        List.of(
            new MatchApplicationService.MatchFailure(
                start.matchId(), CardCustodyPort.Failure.STALE_REVISION)),
        result.matchFailures());
  }

  @Test
  void inMemoryPrepareRejectsIncompleteEscrowSnapshots() {
    InMemoryCardCustodyJournal journal = new InMemoryCardCustodyJournal();
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = copies().subList(0, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            journal.prepare(
                operation,
                owner,
                copies,
                List.of(new SerializedItemSnapshot(copies.getFirst(), new byte[] {1}))));
  }

  @Test
  void returnedEntryRequiresExactEscrowSnapshots() {
    List<CardCopyId> copies = copies().subList(0, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CardCustodyJournal.Entry(
                UUID.randomUUID(),
                CopyOwner.player(UUID.randomUUID()),
                copies,
                List.of(new SerializedItemSnapshot(copies.getFirst(), new byte[] {1})),
                CardCustodyPort.OperationStatus.RETURNED,
                Set.copyOf(copies)));
  }

  @Test
  void inMemoryAbortPrepareRejectsIncompleteEscrowSnapshots() {
    InMemoryCardCustodyJournal journal = new InMemoryCardCustodyJournal();
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = copies().subList(0, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            journal.prepare(
                operation,
                owner,
                copies,
                List.of(new SerializedItemSnapshot(copies.getFirst(), new byte[] {1}))));
  }

  @Test
  void settleConvertsThrownCustodyFailureToTypedDatabaseError() {
    UUID operation = UUID.randomUUID();
    List<CardCopyId> copies = copies();
    CardCustodyPort.ReturnResult result =
        new MatchApplicationService(new ThrowingSettlementCustody(copies)).settle(operation);

    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
    assertEquals(copies, result.expectedCopyIds());
    assertEquals(copies, result.missingCopyIds());
    assertEquals(copies, result.recoveryCopyIds());
  }

  @Test
  void startupRecoveryFailurePreservesDurableCopyIds() {
    List<CardCopyId> copies = copies();
    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(
                new BindingThrowingRecoveryCustody(copies), new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));
    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(copies, recovery.expectedCopyIds());
    assertEquals(copies, recovery.pendingCopyIds());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, recovery.status());
  }

  @Test
  void startupPreservesEveryActiveBoundNonterminalStatus() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        match("startup-active-statuses", owner, copies(), owner, copies());
    MatchState state = MatchEngine.start(start).state();
    List<CardCopyId> expected = copies();
    for (CardCustodyPort.OperationStatus status :
        List.of(
            CardCustodyPort.OperationStatus.PREPARED,
            CardCustodyPort.OperationStatus.ESCROWED,
            CardCustodyPort.OperationStatus.RETURN_PENDING)) {
      ActiveBoundStatusCustody custody =
          new ActiveBoundStatusCustody(UUID.randomUUID(), start.matchId(), owner, expected, status);
      MatchApplicationService.StartupRecoveryResult result =
          new MatchApplicationService(
                  custody, new StartupRehydrationRepository(start.matchId(), null, state, false))
              .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));
      assertEquals(1, result.recoveries().size());
      assertEquals(expected, result.recoveries().getFirst().expectedCopyIds());
      assertEquals(expected, result.recoveries().getFirst().pendingCopyIds());
      assertEquals(
          CardCustodyPort.OperationStatus.RETURN_PENDING, result.recoveries().getFirst().status());
      assertEquals(
          CardCustodyPort.Failure.DATABASE_ERROR, result.recoveries().getFirst().failure());
      assertTrue(custody.recovered.isEmpty());
    }
  }

  @Test
  void startupUsesDurableBindingWhenLegacyMatchLookupIsAbsent() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        match("startup-durable-binding", owner, copies(), owner, copies());
    MatchState state = MatchEngine.start(start).state();
    List<CardCopyId> expected = copies();
    ActiveBoundStatusCustody custody =
        new ActiveBoundStatusCustody(
            UUID.randomUUID(),
            start.matchId(),
            owner,
            expected,
            CardCustodyPort.OperationStatus.ESCROWED,
            false);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(
                custody, new StartupRehydrationRepository(start.matchId(), null, state, false))
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(1, result.recoveries().size());
    assertEquals(expected, result.recoveries().getFirst().expectedCopyIds());
    assertEquals(expected, result.recoveries().getFirst().pendingCopyIds());
    assertEquals(
        CardCustodyPort.OperationStatus.RETURN_PENDING, result.recoveries().getFirst().status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.recoveries().getFirst().failure());
  }

  @Test
  void startupReportsUnknownActiveBoundStatusWithDurableCopyIds() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        match("startup-unknown-status", owner, copies(), owner, copies());
    MatchState state = MatchEngine.start(start).state();
    List<CardCopyId> expected = copies();
    UUID operation = UUID.randomUUID();
    ActiveBoundStatusCustody custody =
        new ActiveBoundStatusCustody(operation, start.matchId(), owner, expected, null);
    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(
                custody, new StartupRehydrationRepository(start.matchId(), null, state, false))
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(1, result.recoveries().size());
    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(operation, recovery.operationId());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, recovery.status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void startupPreservesActiveMatchWhenCustodyMetadataIsUnreadable() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> expected = copies();
    MatchEngine.StartMatch start =
        match("startup-missing-custody-metadata", owner, expected, owner, copies());
    MatchState state = MatchEngine.start(start).state();
    UUID operation = UUID.randomUUID();
    UnknownMetadataCustody custody = new UnknownMetadataCustody(operation, expected);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(
                custody, new StartupRehydrationRepository(start.matchId(), null, state, false))
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, recovery.status());
    assertEquals(operation, recovery.operationId());
    assertEquals(expected, recovery.expectedCopyIds());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void startupPropagatesStructuredReservationIdentityFailure() {
    List<CardCopyId> expected = copies();
    UUID operation = UUID.randomUUID();
    IntegrityFailureCustody custody = new IntegrityFailureCustody(operation, expected);

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, new InMemoryMatchRepository())
            .recoverStartup(
                snapshot -> new CardCatalog(List.of(), snapshot),
                Instant.parse("2026-01-01T00:00:00Z"));

    CardCustodyPort.RecoveryResult recovery = result.recoveries().getFirst();
    assertEquals(operation, recovery.operationId());
    assertEquals(expected, recovery.expectedCopyIds());
    assertEquals(expected, recovery.pendingCopyIds());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, recovery.failure());
    assertTrue(custody.recovered.isEmpty());
  }

  @Test
  void recoverConvertsThrownCustodyFailureToTypedDatabaseError() {
    UUID operation = UUID.randomUUID();
    List<CardCopyId> copies = copies();
    CardCustodyPort.RecoveryResult result =
        new MatchApplicationService(new ThrowingRecoveryCustody(copies)).recover(operation);

    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
    assertEquals(copies, result.expectedCopyIds());
    assertEquals(copies, result.pendingCopyIds());
  }

  @Test
  void rejectedInitialSaveKeepsConcurrentMatchingWinnerCustody() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start = match("initial-race", oneOwner, oneCopies, twoOwner, twoCopies);
    RaceInitialRepository repository = new RaceInitialRepository();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, repository)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation));

    assertTrue(result.accepted());
    assertEquals(repository.winner, result.state());
    assertFalse(custody.calls.contains("return"));
  }

  @Test
  void thrownInitialSaveKeepsConcurrentMatchingWinnerCustody() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start =
        match("initial-throw-race", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, new RaceInitialRepository(true))
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start,
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

    assertTrue(result.accepted());
    assertFalse(custody.calls.contains("return"));
  }

  @Test
  void rejectedInitialSaveLeavesCustodyWhenDurableWinnerReadIsAmbiguous() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start =
        match("initial-ambiguous", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, new AmbiguousInitialRepository())
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start,
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertEquals(
        new java.util.HashSet<>(oneCopies),
        new java.util.HashSet<>(result.pendingRecoveryCopyIds().subList(0, oneCopies.size())));
    assertEquals(
        new java.util.HashSet<>(twoCopies),
        new java.util.HashSet<>(
            result
                .pendingRecoveryCopyIds()
                .subList(oneCopies.size(), oneCopies.size() + twoCopies.size())));
    assertFalse(custody.calls.contains("return"));
  }

  @Test
  void startRejectsCopySetThatDoesNotMatchPhysicalDeckBeforeEscrow() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(
          new CardDefinition(
              new CardId(index),
              "No. " + index,
              "Card " + index,
              1,
              CardType.NORMAL,
              1,
              1,
              1,
              1,
              0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match =
        new MatchEngine.StartMatch(
            new MatchId("copy-validation"),
            new Deck(one),
            new Deck(two),
            new CardCatalog(definitions, "validation-v1"),
            new MatchRules(),
            5L,
            Instant.parse("2026-01-01T00:00:00Z"));
    List<CardCopyId> wrong = new ArrayList<>(one.stream().map(CardCopy::copyId).toList());
    wrong.set(0, new CardCopyId(UUID.randomUUID()));
    MatchApplicationService service = new MatchApplicationService(new RecordingCustody());
    var result =
        service.start(
            new MatchApplicationService.StartMatchRequest(
                match,
                oneOwner,
                wrong,
                UUID.randomUUID(),
                twoOwner,
                two.stream().map(CardCopy::copyId).toList(),
                UUID.randomUUID()));
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
      definitions.add(
          new CardDefinition(
              new CardId(index),
              "No. " + index,
              "Card " + index,
              1,
              CardType.NORMAL,
              1,
              1,
              1,
              1,
              0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match =
        new MatchEngine.StartMatch(
            new MatchId("random-custody"),
            new Deck(one.subList(0, 5)),
            new Deck(two.subList(0, 5)),
            new CardCatalog(definitions, "random-v1"),
            new MatchRules(org.aincraft.mobmatrix.domain.Rule.RANDOM),
            7L,
            Instant.parse("2026-01-01T00:00:00Z"),
            one,
            two);
    MatchEngine.MatchTransition pure = MatchEngine.start(match);
    List<CardCopyId> selectedOne =
        pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.ONE).stream()
            .map(card -> card.copyId())
            .toList();
    List<CardCopyId> selectedTwo =
        pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.TWO).stream()
            .map(card -> card.copyId())
            .toList();
    RecordingCustody custody = new RecordingCustody();
    var result =
        new MatchApplicationService(custody)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match,
                    oneOwner,
                    selectedOne,
                    UUID.randomUUID(),
                    twoOwner,
                    selectedTwo,
                    UUID.randomUUID()));
    assertTrue(result.accepted());
    assertEquals(List.of(selectedOne, selectedTwo), custody.preparedCopies);
  }

  @Test
  void randomStartRejectsWrongOwnerBeforeReservation() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner wrongOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 10; index++) {
      definitions.add(
          new CardDefinition(
              new CardId(index),
              "No. " + index,
              "Card " + index,
              1,
              CardType.NORMAL,
              1,
              1,
              1,
              1,
              0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    MatchEngine.StartMatch valid =
        new MatchEngine.StartMatch(
            new MatchId("random-owner"),
            new Deck(one.subList(0, 5)),
            new Deck(two.subList(0, 5)),
            new CardCatalog(definitions, "random-owner-v1"),
            new MatchRules(org.aincraft.mobmatrix.domain.Rule.RANDOM),
            11L,
            Instant.parse("2026-01-01T00:00:00Z"),
            one,
            two);
    List<CardCopyId> selected =
        MatchEngine.start(valid).state().hand(PlayerSeat.ONE).stream()
            .map(card -> card.copyId())
            .toList();
    RecordingCustody custody = new RecordingCustody();

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    valid,
                    wrongOwner,
                    selected,
                    UUID.randomUUID(),
                    twoOwner,
                    MatchEngine.start(valid).state().hand(PlayerSeat.TWO).stream()
                        .map(card -> card.copyId())
                        .toList(),
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OWNER, result.failure());
    assertTrue(custody.calls.isEmpty());
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

    var first =
        new MatchApplicationService.StartMatchRequest(
            match("bound-one", oneOwner, oneCopies, twoOwner, twoCopies),
            oneOwner,
            oneCopies,
            oneOperation,
            twoOwner,
            twoCopies,
            twoOperation);
    assertTrue(service.start(first).accepted());

    var retry =
        new MatchApplicationService.StartMatchRequest(
            match("bound-two", oneOwner, oneCopies, twoOwner, twoCopies),
            oneOwner,
            oneCopies,
            oneOperation,
            twoOwner,
            twoCopies,
            twoOperation);
    var result = service.start(retry);
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());

    CopyOwner changedOwner = CopyOwner.player(UUID.randomUUID());
    var ownerRetry =
        new MatchApplicationService.StartMatchRequest(
            match("bound-one", changedOwner, oneCopies, twoOwner, twoCopies),
            changedOwner,
            oneCopies,
            oneOperation,
            twoOwner,
            twoCopies,
            twoOperation);
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
    var request =
        new MatchApplicationService.StartMatchRequest(
            match("reject-first", oneOwner, oneCopies, twoOwner, twoCopies),
            oneOwner,
            oneCopies,
            UUID.randomUUID(),
            twoOwner,
            twoCopies,
            UUID.randomUUID());

    var result = new MatchApplicationService(custody).start(request);
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.COPY_NOT_FOUND, result.failure());
    assertEquals(List.of("prepare"), custody.calls);
    assertTrue(result.pendingRecoveryCopyIds().isEmpty());
    assertTrue(result.missingCopyIds().isEmpty());
  }

  @Test
  void defaultInitialSaveRejectsWithoutWritingEitherRow() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchState state =
        MatchEngine.start(match("atomic-default", oneOwner, oneCopies, twoOwner, twoCopies))
            .state();
    MatchRepository.StartBinding binding =
        new MatchRepository.StartBinding(
            state.matchId(),
            1,
            "atomic-default-identity",
            oneOwner,
            oneCopies,
            UUID.randomUUID(),
            twoOwner,
            twoCopies,
            UUID.randomUUID());
    boolean[] wroteMatch = {false};
    boolean[] wroteBinding = {false};
    MatchRepository repository =
        new MatchRepository() {
          @Override
          public SaveResult save(long expectedRevision, MatchState state) {
            wroteMatch[0] = true;
            return SaveResult.success(state.revision());
          }

          @Override
          public void saveStartBinding(MatchRepository.StartBinding binding) {
            wroteBinding[0] = true;
          }

          @Override
          public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
            return java.util.Optional.empty();
          }
        };

    MatchRepository.SaveResult result = repository.saveInitial(state, binding);

    assertFalse(result.accepted());
    assertEquals(MatchRepository.Failure.INVALID_STATE, result.failure());
    assertFalse(wroteMatch[0]);
    assertFalse(wroteBinding[0]);
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
    StartupCustody custody =
        new StartupCustody(activeOperation, start.matchId(), orphanOperation, failingOperation);
    MatchRepository repository =
        new MatchRepository() {
          @Override
          public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
            return java.util.Optional.empty();
          }

          public SaveResult save(long expectedRevision, MatchState state) {
            return SaveResult.success(state.revision());
          }

          @Override
          public List<MatchSnapshot> activeMatches() {
            return List.of(
                new MatchSnapshot(
                    start.matchId(), active.revision(), "ACTIVE", new byte[] {1}, "{}"));
          }

          @Override
          public java.util.Optional<MatchState> rehydrate(
              MatchSnapshot snapshot, java.util.function.Function<String, CardCatalog> resolver) {
            return java.util.Optional.of(active);
          }
        };

    MatchApplicationService.StartupRecoveryResult result =
        new MatchApplicationService(custody, repository)
            .recoverStartup(id -> start.catalog(), Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals(List.of(orphanOperation, failingOperation), custody.recovered);
    assertEquals(3, result.recoveries().size());
    assertEquals(
        CardCustodyPort.OperationStatus.RETURN_PENDING, result.recoveries().getFirst().status());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.recoveries().get(2).failure());
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
    MatchApplicationService.StartMatchRequest request =
        new MatchApplicationService.StartMatchRequest(
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
    MatchEngine.StartMatch start =
        match("swapped-operations", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    var request =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation);
    assertTrue(service.start(request).accepted());

    var swapped =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, oneCopies, twoOperation, twoOwner, twoCopies, oneOperation);
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
    MatchEngine.StartMatch start =
        new MatchEngine.StartMatch(
            new MatchId("draft-binding"),
            new Deck(
                oneCopies.stream()
                    .map(copy -> new CardCopy(copy, new CardId(1), oneOwner))
                    .toList()),
            new Deck(
                twoCopies.stream()
                    .map(copy -> new CardCopy(copy, new CardId(1), twoOwner))
                    .toList()),
            new CardCatalog(
                List.of(
                    new CardDefinition(
                        new CardId(1), "No. 1", "Card 1", 1, CardType.NORMAL, 1, 1, 1, 1, 0)),
                "draft-binding-catalog"),
            new MatchRules(org.aincraft.mobmatrix.domain.Rule.DRAFT),
            17L,
            Instant.parse("2026-01-01T00:00:00Z"));
    RecordingMatchRepository repository = new RecordingMatchRepository();
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody, repository);
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    var request =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, List.of(), oneOperation, twoOwner, List.of(), twoOperation);
    assertTrue(service.start(request).accepted());

    MatchApplicationService retryService =
        new MatchApplicationService(new RecordingCustody(), repository);
    var conflicting =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, List.of(), UUID.randomUUID(), twoOwner, List.of(), UUID.randomUUID());
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

    var result =
        new MatchApplicationService(custody)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start,
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

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
    MatchEngine.StartMatch start =
        new MatchEngine.StartMatch(
            new MatchId("house-owner-draft"),
            new Deck(
                oneCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), house)).toList()),
            new Deck(
                twoCopies.stream().map(copy -> new CardCopy(copy, new CardId(1), house)).toList()),
            new CardCatalog(
                List.of(
                    new CardDefinition(
                        new CardId(1), "No. 1", "Card 1", 1, CardType.NORMAL, 1, 1, 1, 1, 0)),
                "house-owner-draft-catalog"),
            new MatchRules(org.aincraft.mobmatrix.domain.Rule.DRAFT),
            5L,
            Instant.parse("2026-01-01T00:00:00Z"));

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(new RecordingCustody())
            .start(
                new MatchApplicationService.StartMatchRequest(
                    start,
                    house,
                    List.of(),
                    UUID.randomUUID(),
                    house,
                    List.of(),
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_OWNER, result.failure());
  }

  @Test
  void botStartSkipsHouseCustodyForEmptyHouseSelection() {
    CopyOwner player = CopyOwner.player(UUID.randomUUID());
    CopyOwner house = CopyOwner.house();
    List<CardCopyId> playerCopies = copies();
    List<CardCopyId> houseCopies = copies();
    MatchEngine.StartMatch start = match("bot-house", player, playerCopies, house, houseCopies);
    RecordingCustody custody = new RecordingCustody();
    UUID playerOperation = UUID.randomUUID();
    UUID houseOperation = UUID.randomUUID();

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody)
            .startBot(
                new MatchApplicationService.BotStartMatchRequest(
                    start, player, playerCopies, playerOperation, houseOperation));

    assertTrue(result.accepted());
    assertEquals(List.of(playerCopies), custody.preparedCopies);
    assertEquals(List.of("prepare", "escrow"), custody.calls);
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

      var service =
          new org.aincraft.mobmatrix.inventory.CardInventoryService(
              new org.aincraft.mobmatrix.inventory.CardItemCodec(
                  "mobmatrix", "stable-server-secret"),
              id -> null,
              journal);
      CardCustodyPort.RecoveryResult result = service.recover(operation);

      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(List.of(copy), result.pendingCopyIds());
      assertEquals(
          CardCustodyPort.OperationStatus.RETURN_PENDING,
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
    MatchEngine.StartMatch swap =
        new MatchEngine.StartMatch(
            base.matchId(),
            base.oneDeck(),
            base.twoDeck(),
            base.catalog(),
            new MatchRules(org.aincraft.mobmatrix.domain.Rule.SWAP),
            base.seed(),
            base.startTime());
    RecordingCustody custody = new RecordingCustody();

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    swap,
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

    assertTrue(result.accepted());
    assertEquals(List.of(oneCopies, twoCopies), custody.preparedCopies);
    assertEquals(
        new java.util.HashSet<>(oneCopies),
        new java.util.HashSet<>(
            result
                .state()
                .settlementCopies()
                .get(org.aincraft.mobmatrix.domain.PlayerSeat.ONE)
                .stream()
                .map(CardCopy::copyId)
                .toList()));
    assertEquals(
        new java.util.HashSet<>(twoCopies),
        new java.util.HashSet<>(
            result
                .state()
                .settlementCopies()
                .get(org.aincraft.mobmatrix.domain.PlayerSeat.TWO)
                .stream()
                .map(CardCopy::copyId)
                .toList()));
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

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, repository)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match("atomic-start-failure", oneOwner, oneCopies, twoOwner, twoCopies),
                    oneOwner,
                    oneCopies,
                    oneOperation,
                    twoOwner,
                    twoCopies,
                    twoOperation));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, result.failure());
    assertTrue(repository.find(new MatchId("atomic-start-failure")).isEmpty());
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
    assertEquals(CardCustodyPort.OperationStatus.RETURNED, custody.statuses.get(oneOperation));
    assertEquals(CardCustodyPort.OperationStatus.RETURNED, custody.statuses.get(twoOperation));
  }

  @Test
  void cleanupPreservesRecoveryWhenAdapterUsesUnsupportedDefaultAbort() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    DefaultAbortCustody custody = new DefaultAbortCustody();
    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, new FailingInitialRepository())
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match("default-abort", oneOwner, oneCopies, twoOwner, twoCopies),
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertFalse(custody.returnedOperations.isEmpty());
    assertTrue(result.pendingRecoveryCopyIds().containsAll(oneCopies));
    assertTrue(result.pendingRecoveryCopyIds().containsAll(twoCopies));
  }

  @Test
  void activeRetryRejectsCustodyAdapterWithAbsentDurableMetadata() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    MatchEngine.StartMatch start =
        match("strict-retry-binding", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingMatchRepository repository = new RecordingMatchRepository();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchApplicationService.StartMatchRequest request =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation);

    assertTrue(
        new MatchApplicationService(new RecordingCustody(), repository).start(request).accepted());
    MatchApplicationService.MatchStartResult retry =
        new MatchApplicationService(new AbsentMetadataCustody(), repository).start(request);

    assertFalse(retry.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, retry.failure());
  }

  @Test
  void activeRetryReportsDatabaseErrorWhenCustodyBindingReadFails() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    UUID oneOperation = UUID.randomUUID();
    UUID twoOperation = UUID.randomUUID();
    MatchEngine.StartMatch start =
        match("unavailable-retry-binding", oneOwner, oneCopies, twoOwner, twoCopies);
    RecordingMatchRepository repository = new RecordingMatchRepository();
    MatchApplicationService.StartMatchRequest request =
        new MatchApplicationService.StartMatchRequest(
            start, oneOwner, oneCopies, oneOperation, twoOwner, twoCopies, twoOperation);
    assertTrue(
        new MatchApplicationService(new RecordingCustody(), repository).start(request).accepted());

    MatchApplicationService.MatchStartResult retry =
        new MatchApplicationService(new AbsentMetadataCustody(true), repository).start(request);

    assertFalse(retry.accepted());
    assertEquals(CardCustodyPort.Failure.DATABASE_ERROR, retry.failure());
  }

  private static List<CardCopyId> copies() {
    return List.of(
        new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()));
  }

  private static MatchEngine.StartMatch match(
      String id,
      CopyOwner oneOwner,
      List<CardCopyId> oneCopies,
      CopyOwner twoOwner,
      List<CardCopyId> twoCopies) {
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(
          new CardDefinition(
              new CardId(index),
              "No. " + index,
              "Card " + index,
              1,
              CardType.NORMAL,
              1,
              1,
              1,
              1,
              0));
      one.add(new CardCopy(oneCopies.get(index - 1), new CardId(index), oneOwner));
      two.add(new CardCopy(twoCopies.get(index - 1), new CardId(index), twoOwner));
    }
    return new MatchEngine.StartMatch(
        new MatchId(id),
        new Deck(one),
        new Deck(two),
        new CardCatalog(definitions, id + "-catalog"),
        new MatchRules(),
        5L,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static final class CanonicalMatchRepository implements MatchRepository {
    private MatchState state;
    private int saves;

    private CanonicalMatchRepository(MatchState state) {
      this.state = state;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState successor) {
      saves++;
      if (expectedRevision != state.revision()) return SaveResult.stale(state.revision());
      state = successor;
      return SaveResult.success(successor.revision());
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.of(state);
    }
  }

  private static final class RecordingMatchRepository implements MatchRepository {
    private MatchState state;
    private MatchRepository.StartBinding binding;
    private int saves;

    @Override
    public SaveResult saveInitial(MatchState state, MatchRepository.StartBinding binding) {
      saves++;
      if (this.state != null) return SaveResult.stale(this.state.revision());
      this.state = state;
      this.binding = binding;
      return SaveResult.success(0L);
    }

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
      return state == null
          ? java.util.Optional.empty()
          : java.util.Optional.of(
              new MatchSnapshot(matchId, state.revision(), "ACTIVE", new byte[] {1}, "{}"));
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.ofNullable(state);
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
      return operationId.equals(active)
          ? java.util.Optional.of(matchId)
          : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.of(OperationStatus.ESCROWED);
    }

    @Override
    public java.util.Optional<OperationBinding> operationBinding(UUID operationId) {
      if (operationId.equals(active)) return java.util.Optional.empty();
      return java.util.Optional.of(
          new OperationBinding(
              CopyOwner.player(UUID.randomUUID()),
              List.of(),
              OperationStatus.ESCROWED,
              new MatchId("orphan-" + operationId)));
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

  private static final class AbsentMetadataCustody implements CardCustodyPort {
    private final boolean unavailable;

    private AbsentMetadataCustody() {
      this(false);
    }

    private AbsentMetadataCustody(boolean unavailable) {
      this.unavailable = unavailable;
    }

    @Override
    public java.util.Optional<CardCustodyPort.OperationBinding> operationBinding(UUID operationId) {
      if (unavailable) throw new IllegalStateException("database unavailable");
      return java.util.Optional.empty();
    }

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
    private final boolean pendingReturn;
    private final Map<UUID, Binding> matchBindings = new java.util.HashMap<>();
    private final Map<UUID, OperationStatus> statuses = new java.util.HashMap<>();

    @Override
    public java.util.Optional<CardCustodyPort.OperationBinding> operationBinding(UUID operationId) {
      Binding binding = matchBindings.get(operationId);
      if (binding == null) return java.util.Optional.empty();
      OperationStatus status = statuses.get(operationId);
      if (status == null) return java.util.Optional.empty();
      return java.util.Optional.of(
          new CardCustodyPort.OperationBinding(
              binding.owner(), binding.copyIds(), status, binding.matchId()));
    }

    private final List<List<CardCopyId>> preparedCopies = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private RecordingCustody() {
      this(false);
    }

    private RecordingCustody(boolean rejectPrepare) {
      this(rejectPrepare, false);
    }

    private RecordingCustody(boolean rejectPrepare, boolean pendingReturn) {
      this.rejectPrepare = rejectPrepare;
      this.pendingReturn = pendingReturn;
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return prepareInternal(owner, copyIds, operationId, null);
    }

    @Override
    public PrepareResult prepare(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      return prepareInternal(owner, copyIds, operationId, matchId);
    }

    private PrepareResult prepareInternal(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      if (!calls.contains("prepare")) calls.add("prepare");
      if (rejectPrepare)
        return PrepareResult.rejected(operationId, owner, copyIds, Failure.COPY_NOT_FOUND);
      Binding prior =
          matchBindings.putIfAbsent(operationId, new Binding(matchId, owner, List.copyOf(copyIds)));
      if (prior != null
          && (!java.util.Objects.equals(prior.matchId(), matchId)
              || !prior.owner().equals(owner)
              || !prior.copyIds().equals(copyIds))) {
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
      if (pendingReturn) {
        Binding binding = matchBindings.get(operationId);
        List<CardCopyId> expected = binding == null ? List.of() : binding.copyIds();
        return ReturnResult.pending(operationId, expected, List.of(), expected, expected);
      }
      statuses.put(operationId, OperationStatus.RETURNED);
      return ReturnResult.returned(operationId, List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }

    private record Binding(MatchId matchId, CopyOwner owner, List<CardCopyId> copyIds) {}
  }

  private static final class DefaultAbortCustody implements CardCustodyPort {
    private final Map<UUID, List<CardCopyId>> expected = new java.util.HashMap<>();
    private final List<UUID> returnedOperations = new ArrayList<>();

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      expected.put(operationId, List.copyOf(copyIds));
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public PrepareResult prepare(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      return prepare(owner, copyIds, operationId);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      returnedOperations.add(operationId);
      return ReturnResult.returned(operationId, expected.getOrDefault(operationId, List.of()));
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }
  }

  private static final class SaveFailureMatchRepository implements MatchRepository {
    private final MatchState state;
    private final MatchRepository.Failure failure;

    private SaveFailureMatchRepository(MatchState state, MatchRepository.Failure failure) {
      this.state = state;
      this.failure = failure;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState successor) {
      return new SaveResult(false, state.revision(), failure);
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.of(state);
    }
  }

  private static final class RaceInitialRepository implements MatchRepository {
    private MatchState winner;
    private MatchRepository.StartBinding binding;
    private final boolean throwAfterSave;

    private RaceInitialRepository() {
      this(false);
    }

    private RaceInitialRepository(boolean throwAfterSave) {
      this.throwAfterSave = throwAfterSave;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      return SaveResult.stale(state.revision());
    }

    @Override
    public SaveResult saveInitial(MatchState state, MatchRepository.StartBinding binding) {
      this.winner = state;
      this.binding = binding;
      if (throwAfterSave) throw new IllegalStateException("commit result unavailable");
      return SaveResult.stale(0L);
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return winner == null
          ? java.util.Optional.empty()
          : java.util.Optional.of(
              new MatchSnapshot(matchId, winner.revision(), "ACTIVE", new byte[] {1}, "{}"));
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.ofNullable(winner);
    }

    @Override
    public java.util.Optional<MatchRepository.StartBinding> findStartBinding(MatchId matchId) {
      return java.util.Optional.ofNullable(binding);
    }
  }

  private static final class AmbiguousInitialRepository implements MatchRepository {
    private boolean saveAttempted;

    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      return SaveResult.stale(state.revision());
    }

    @Override
    public SaveResult saveInitial(MatchState state, MatchRepository.StartBinding binding) {
      saveAttempted = true;
      return new SaveResult(false, -1L, MatchRepository.Failure.DATABASE_ERROR);
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      if (!saveAttempted) return java.util.Optional.empty();
      throw new IllegalStateException("database unavailable");
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      if (!saveAttempted) return java.util.Optional.empty();
      throw new IllegalStateException("database unavailable");
    }
  }

  private static final class ThrowingSaveMatchRepository implements MatchRepository {
    private final MatchState state;

    private ThrowingSaveMatchRepository(MatchState state) {
      this.state = state;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState successor) {
      throw new IllegalStateException("database unavailable");
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.of(state);
    }
  }

  private static final class CanonicalLoadFailingRepository implements MatchRepository {
    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      return SaveResult.stale(state.revision());
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      throw new IllegalStateException("database unavailable");
    }
  }

  private static final class EnumerationFailingCustody implements CardCustodyPort {
    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      return ReturnResult.pending(operationId, List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.failed(
          operationId, OperationStatus.RETURN_PENDING, Failure.DATABASE_ERROR);
    }

    @Override
    public List<UUID> openOperationIds() {
      throw new IllegalStateException("database unavailable");
    }
  }

  private static final class UnknownMetadataCustody implements CardCustodyPort {
    private final UUID operation;
    private final List<CardCopyId> copies;
    private final List<UUID> recovered = new ArrayList<>();

    private UnknownMetadataCustody(UUID operation, List<CardCopyId> copies) {
      this.operation = operation;
      this.copies = List.copyOf(copies);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new AssertionError("must preserve active-bound custody");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      throw new AssertionError("must preserve active-bound custody");
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchId> operationMatchId(UUID operationId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OperationBinding> operationBinding(UUID operationId) {
      return java.util.Optional.of(
          new OperationBinding(
              CopyOwner.player(UUID.randomUUID()), copies, OperationStatus.ESCROWED, null));
    }
  }

  private static final class BoundEscrowCustody implements CardCustodyPort {
    private final UUID operation;
    private final MatchId matchId;

    private BoundEscrowCustody(UUID operation, MatchId matchId) {
      this.operation = operation;
      this.matchId = matchId;
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new IllegalStateException("must preserve bound escrow");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      throw new IllegalStateException("must preserve bound escrow");
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.of(OperationStatus.ESCROWED);
    }

    @Override
    public java.util.Optional<MatchId> operationMatchId(UUID operationId) {
      return java.util.Optional.of(matchId);
    }
  }

  private static final class ActiveBoundStatusCustody implements CardCustodyPort {
    private final UUID operation;
    private final MatchId matchId;
    private final CopyOwner owner;
    private final List<CardCopyId> copies;
    private final OperationStatus status;
    private final boolean legacyMatchLookupAvailable;
    private final List<UUID> recovered = new ArrayList<>();

    private ActiveBoundStatusCustody(
        UUID operation,
        MatchId matchId,
        CopyOwner owner,
        List<CardCopyId> copies,
        OperationStatus status) {
      this(operation, matchId, owner, copies, status, true);
    }

    private ActiveBoundStatusCustody(
        UUID operation,
        MatchId matchId,
        CopyOwner owner,
        List<CardCopyId> copies,
        OperationStatus status,
        boolean legacyMatchLookupAvailable) {
      this.operation = operation;
      this.matchId = matchId;
      this.owner = owner;
      this.copies = List.copyOf(copies);
      this.status = status;
      this.legacyMatchLookupAvailable = legacyMatchLookupAvailable;
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new AssertionError("must preserve active-bound custody");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      throw new AssertionError("must preserve active-bound custody");
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.ofNullable(status);
    }

    @Override
    public java.util.Optional<MatchId> operationMatchId(UUID operationId) {
      return legacyMatchLookupAvailable
          ? java.util.Optional.of(matchId)
          : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OperationBinding> operationBinding(UUID operationId) {
      return java.util.Optional.of(
          new OperationBinding(
              owner, copies, status == null ? OperationStatus.PREPARED : status, matchId));
    }
  }

  private static final class GenericStartupFailureCustody implements CardCustodyPort {
    private final UUID operation;
    private final List<CardCopyId> copies;
    private final boolean failRecover;
    private final List<UUID> recovered = new ArrayList<>();

    private GenericStartupFailureCustody(
        UUID operation, List<CardCopyId> copies, boolean failRecover) {
      this.operation = operation;
      this.copies = List.copyOf(copies);
      this.failRecover = failRecover;
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      return ReturnResult.pending(operationId, copies, List.of(), copies, copies);
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      if (failRecover) throw new IllegalStateException("recovery unavailable");
      return RecoveryResult.none(operationId);
    }

    @Override
    public List<StartupEntry> startupEntries() {
      return List.of(StartupEntry.valid(operation, copies));
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      if (!failRecover) throw new IllegalStateException("status unavailable");
      return java.util.Optional.of(OperationStatus.RETURNED);
    }
  }

  private static final class MalformedStartupCustody implements CardCustodyPort {
    private final String rawOperation;
    private final List<CardCopyId> copies;

    private final List<UUID> recovered = new ArrayList<>();

    private MalformedStartupCustody(String rawOperation, List<CardCopyId> copies) {
      this.rawOperation = rawOperation;
      this.copies = List.copyOf(copies);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new AssertionError("must preserve malformed custody");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      throw new AssertionError("must preserve malformed custody");
    }

    @Override
    public List<StartupEntry> startupEntries() {
      return List.of(StartupEntry.malformed(rawOperation, copies));
    }
  }

  private static final class IntegrityFailureCustody implements CardCustodyPort {
    private final UUID operation;
    private final List<CardCopyId> copies;
    private final List<UUID> recovered = new ArrayList<>();

    private IntegrityFailureCustody(UUID operation, List<CardCopyId> copies) {
      this.operation = operation;
      this.copies = List.copyOf(copies);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new AssertionError("must preserve malformed custody");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      throw new AssertionError("must preserve malformed custody");
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      throw new SqliteCardRepository.CustodyIntegrityException(
          operation, copies, "malformed reservation");
    }
  }

  private static class ThrowingSettlementCustody implements CardCustodyPort {
    protected final List<CardCopyId> copies;

    private ThrowingSettlementCustody(List<CardCopyId> copies) {
      this.copies = List.copyOf(copies);
    }

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.rejected(operationId, owner, copyIds, Failure.DATABASE_ERROR);
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.rejected(operationId, OperationStatus.PREPARED, Failure.DATABASE_ERROR);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new IllegalStateException("database unavailable");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }

    @Override
    public java.util.Optional<OperationBinding> operationBinding(UUID operationId) {
      return java.util.Optional.of(
          new OperationBinding(
              CopyOwner.player(UUID.randomUUID()), copies, OperationStatus.ESCROWED, null));
    }
  }

  private static class ThrowingRecoveryCustody extends ThrowingSettlementCustody {
    private ThrowingRecoveryCustody(List<CardCopyId> copies) {
      super(copies);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      return ReturnResult.pending(operationId, copies, List.of(), copies, copies);
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      throw new IllegalStateException("database unavailable");
    }
  }

  private static final class BindingThrowingRecoveryCustody extends ThrowingRecoveryCustody {
    private BindingThrowingRecoveryCustody(List<CardCopyId> copies) {
      super(copies);
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(UUID.randomUUID());
    }

    @Override
    public java.util.Optional<OperationBinding> operationBinding(UUID operationId) {
      return java.util.Optional.of(
          new OperationBinding(
              CopyOwner.player(UUID.randomUUID()), copies, OperationStatus.ESCROWED, null));
    }
  }

  private static final class BadRowMatchRepository implements MatchRepository {
    private final MatchSnapshot healthy;
    private final MatchState healthyState;
    private final MatchId badMatch;

    private BadRowMatchRepository(MatchId healthyMatch, MatchState state, MatchId badMatch) {
      this.healthyState = state;
      this.healthy =
          new MatchSnapshot(healthyMatch, state.revision(), "ACTIVE", new byte[] {1}, "{}");
      this.badMatch = badMatch;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      return SaveResult.stale(state.revision());
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> rehydrate(
        MatchSnapshot snapshot, java.util.function.Function<String, CardCatalog> catalogResolver) {
      return java.util.Optional.of(healthyState);
    }

    @Override
    public ActiveMatchesResult activeMatchesReport() {
      return new ActiveMatchesResult(
          List.of(healthy),
          List.of(new ActiveMatchFailure(badMatch, MatchRepository.Failure.DATABASE_ERROR)));
    }
  }

  private static final class ActiveMatchesFailingRepository implements MatchRepository {
    @Override
    public SaveResult save(long expectedRevision, MatchState state) {
      return SaveResult.stale(state.revision());
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public List<MatchSnapshot> activeMatches() {
      throw new IllegalStateException("database unavailable");
    }
  }

  @Test
  void startRejectsSamePlayerInBothSeatsBeforePureStartOrCustody() {
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    RecordingCustody custody = new RecordingCustody();

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody)
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match("same-player-seats", owner, oneCopies, owner, twoCopies),
                    owner,
                    oneCopies,
                    UUID.randomUUID(),
                    owner,
                    twoCopies,
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.INVALID_OWNER, result.failure());
    assertTrue(custody.calls.isEmpty());
  }

  @Test
  void cleanupRetainsExpectedCopiesWhenEnumerationAndReturnFail() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = copies();
    List<CardCopyId> twoCopies = copies();
    CleanupThrowsAfterAcceptedCustody custody = new CleanupThrowsAfterAcceptedCustody();

    MatchApplicationService.MatchStartResult result =
        new MatchApplicationService(custody, new FailingInitialRepository())
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match("cleanup-outage", oneOwner, oneCopies, twoOwner, twoCopies),
                    oneOwner,
                    oneCopies,
                    UUID.randomUUID(),
                    twoOwner,
                    twoCopies,
                    UUID.randomUUID()));

    assertFalse(result.accepted());
    assertTrue(result.pendingRecoveryCopyIds().containsAll(oneCopies));
    assertTrue(result.pendingRecoveryCopyIds().containsAll(twoCopies));
    assertTrue(result.missingCopyIds().containsAll(oneCopies));
    assertTrue(result.missingCopyIds().containsAll(twoCopies));
  }

  private static final class CleanupThrowsAfterAcceptedCustody implements CardCustodyPort {
    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public PrepareResult prepare(
        CopyOwner owner, List<CardCopyId> copyIds, UUID operationId, MatchId matchId) {
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      throw new IllegalStateException("custody enumeration and return unavailable");
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }

    @Override
    public List<UUID> openOperationIds() {
      throw new IllegalStateException("custody enumeration unavailable");
    }
  }

  private static final class RehydrationFailureCustody implements CardCustodyPort {
    private final UUID operation;
    private final MatchId matchId;
    private final List<UUID> recovered = new ArrayList<>();

    private RehydrationFailureCustody(UUID operation, MatchId matchId) {
      this.operation = operation;
      this.matchId = matchId;
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
      return java.util.Optional.of(OperationStatus.ESCROWED);
    }

    @Override
    public java.util.Optional<MatchId> operationMatchId(UUID operationId) {
      return java.util.Optional.of(matchId);
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      recovered.add(operationId);
      throw new AssertionError("bound escrow must remain protected");
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
  }

  private static final class StartupRehydrationRepository implements MatchRepository {
    private final MatchId first;
    private final MatchId second;
    private final MatchState state;
    private final boolean rejectSave;
    private final boolean throwRehydrate;

    private StartupRehydrationRepository(
        MatchId first, MatchId second, MatchState state, boolean rejectSave) {
      this(first, second, state, rejectSave, false);
    }

    private StartupRehydrationRepository(
        MatchId first,
        MatchId second,
        MatchState state,
        boolean rejectSave,
        boolean throwRehydrate) {
      this.first = first;
      this.second = second;
      this.state = state;
      this.rejectSave = rejectSave;
      this.throwRehydrate = throwRehydrate;
    }

    @Override
    public SaveResult save(long expectedRevision, MatchState successor) {
      return rejectSave
          ? SaveResult.stale(state.revision())
          : SaveResult.success(successor.revision());
    }

    @Override
    public java.util.Optional<MatchSnapshot> find(MatchId matchId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
      return java.util.Optional.of(state);
    }

    @Override
    public List<MatchSnapshot> activeMatches() {
      List<MatchSnapshot> snapshots = new ArrayList<>();
      snapshots.add(new MatchSnapshot(first, state.revision(), "ACTIVE", new byte[] {1}, "{}"));
      if (second != null) {
        snapshots.add(new MatchSnapshot(second, state.revision(), "ACTIVE", new byte[] {2}, "{}"));
      }
      return snapshots;
    }

    @Override
    public java.util.Optional<MatchState> rehydrate(
        MatchSnapshot snapshot, java.util.function.Function<String, CardCatalog> resolver) {
      if (snapshot.matchId().equals(first)) {
        if (throwRehydrate) throw new IllegalStateException("snapshot unavailable");
        if (second != null) return java.util.Optional.empty();
      }
      return java.util.Optional.of(state);
    }
  }
}
