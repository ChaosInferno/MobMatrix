package org.aincraft.mobmatrix.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.storage.Database;
import org.aincraft.mobmatrix.storage.SqliteCardRepository;
import org.junit.jupiter.api.Test;

final class CardInventoryServiceTest {
  @Test
  void directPrepareRejectsAnythingOtherThanFiveUniqueCopies() {
    CardInventoryService service = new CardInventoryService(
        new CardItemCodec("test", "0123456789abcdef"), id -> null);
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    UUID operation = UUID.randomUUID();
    List<CardCopyId> fourCopies = List.of(
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));
    CardCustodyPort.PrepareResult tooFew = service.prepare(owner, fourCopies, operation);
    assertEquals(CardCustodyPort.Failure.INVALID_COPY_SET, tooFew.failure());

    CardCopyId duplicate = new CardCopyId(UUID.randomUUID());
    List<CardCopyId> duplicateCopies = List.of(duplicate, duplicate,
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()));
    CardCustodyPort.PrepareResult duplicateResult = service.prepare(owner, duplicateCopies,
        UUID.randomUUID());
    assertEquals(CardCustodyPort.Failure.INVALID_COPY_SET, duplicateResult.failure());
  }

  @Test
  void duplicateUuidRestoreStaysPendingAndQueuesExactRecoverySnapshots() throws Exception {
    var path = Files.createTempFile("mobmatrix-duplicate-restore", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));
    List<SerializedItemSnapshot> snapshots = List.of(
        new SerializedItemSnapshot(copies.get(0), new byte[] {1, 2, 3}),
        new SerializedItemSnapshot(copies.get(1), new byte[] {4, 5, 6}));
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      for (CardCopyId copy : copies) {
        journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      }
      journal.prepare(operation, owner, copies, snapshots);

      CardCustodyPort.ReturnResult result = CardInventoryService.restoreAmbiguous(
          operation, owner, copies, snapshots, Set.of(), journal);

      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(copies, result.expectedCopyIds());
      assertEquals(List.of(), result.returnedCopyIds());
      assertEquals(copies, result.missingCopyIds());
      assertEquals(copies, result.recoveryCopyIds());
      assertEquals(2, journal.pendingRecovery().size());
      assertArrayEquals(snapshots.get(0).bytes(), journal.pendingRecovery().get(0).snapshot().bytes());
      assertArrayEquals(snapshots.get(1).bytes(), journal.pendingRecovery().get(1).snapshot().bytes());
      assertEquals(List.of(), journal.findReservation(operation).orElseThrow().restoredCopyIds());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void preparedAbortMustAttemptRestoreBeforeTerminalAbort() throws Exception {
    var path = Files.createTempFile("mobmatrix-prepared-abort", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {1, 2, 3});
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      journal.prepare(operation, owner, List.of(copy), List.of(snapshot));
      CardInventoryService service = new CardInventoryService(
          new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);
      CardCustodyPort.CustodyResult result = service.abort(operation);
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(CardCustodyPort.Failure.NO_ROOM, result.failure());
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING,
          journal.findReservation(operation).orElseThrow().status());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void recoverPreservesAbortedTerminalStatus() throws Exception {
    var path = Files.createTempFile("mobmatrix-aborted-recovery", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {7, 8, 9});
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      journal.prepare(operation, owner, List.of(copy), List.of(snapshot));
      journal.markRestored(operation, copy);
      journal.transition(operation, CardCustodyPort.OperationStatus.ABORTED);

      CardInventoryService service = new CardInventoryService(
          new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);
      CardCustodyPort.RecoveryResult result = service.recover(operation);

      assertEquals(CardCustodyPort.OperationStatus.ABORTED, result.status());
      assertTrue(result.complete());
      assertEquals(List.of(copy), result.expectedCopyIds());
      assertEquals(List.of(copy), result.recoveredCopyIds());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void laterAbortSeesDurableReturnPendingAfterAmbiguousRestore() throws Exception {
    var path = Files.createTempFile("mobmatrix-restore-status", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {3, 2, 1});
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      journal.prepare(operation, owner, List.of(copy), List.of(snapshot));
      CardInventoryService service = new CardInventoryService(
          new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

      CardInventoryService.restoreAmbiguous(operation, owner, List.of(copy), List.of(snapshot),
          Set.of(), journal);

      CardCustodyPort.CustodyResult result = service.abort(operation);
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(CardCustodyPort.Failure.NO_ROOM, result.failure());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void identityBearingInvalidItemsAreCounterfeitRegardlessOfMaterial() {
    assertTrue(CardInventoryService.isCounterfeit(
        CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.NOT_PAPER), true));
    assertTrue(CardInventoryService.isCounterfeit(
        CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.INVALID_SCHEMA), true));
    assertTrue(!CardInventoryService.isCounterfeit(
        CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.INVALID_SCHEMA), false));
  }

  @Test
  void restoreRequiresExactOwnerAndSerializedBytes() {
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardItemData data = new CardItemData(new CardId(1), copy, owner, 1, "marker", "Card");
    CardItemCodec.ReadResult valid = CardItemCodec.ReadResult.valid(data);
    byte[] exact = {1, 2, 3};

    assertTrue(CardInventoryService.exactItemMatchesSnapshot(valid, copy, owner, exact, exact));
    assertFalse(CardInventoryService.exactItemMatchesSnapshot(valid, copy, owner,
        new byte[] {1, 2, 4}, exact));
    assertFalse(CardInventoryService.exactItemMatchesSnapshot(valid, copy,
        CopyOwner.house(), exact, exact));
    assertFalse(CardInventoryService.exactItemMatchesSnapshot(
        CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.INVALID_INTEGRITY),
        copy, owner, exact, exact));
  }

  @Test
  void itemFrameGuardOnlyCancelsReservedCards() {
    assertTrue(CardInventoryService.shouldCancelItemFrameInteraction(true, true, false));
    assertTrue(CardInventoryService.shouldCancelItemFrameInteraction(true, false, true));
    assertFalse(CardInventoryService.shouldCancelItemFrameInteraction(true, false, false));
    assertFalse(CardInventoryService.shouldCancelItemFrameInteraction(false, true, true));
  }
}
