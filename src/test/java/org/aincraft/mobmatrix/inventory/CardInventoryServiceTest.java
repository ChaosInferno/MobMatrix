package org.aincraft.mobmatrix.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyJournal;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.storage.Database;
import org.aincraft.mobmatrix.storage.SqliteCardRepository;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class CardInventoryServiceTest {
  @Test
  void directPrepareRejectsAnythingOtherThanFiveUniqueCopies() {
    CardInventoryService service =
        new CardInventoryService(new CardItemCodec("test", "0123456789abcdef"), id -> null);
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    UUID operation = UUID.randomUUID();
    List<CardCopyId> fourCopies =
        List.of(
            new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));
    CardCustodyPort.PrepareResult tooFew = service.prepare(owner, fourCopies, operation);
    assertEquals(CardCustodyPort.Failure.INVALID_COPY_SET, tooFew.failure());

    CardCopyId duplicate = new CardCopyId(UUID.randomUUID());
    List<CardCopyId> duplicateCopies =
        List.of(
            duplicate,
            duplicate,
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()),
            new CardCopyId(UUID.randomUUID()));
    CardCustodyPort.PrepareResult duplicateResult =
        service.prepare(owner, duplicateCopies, UUID.randomUUID());
    assertEquals(CardCustodyPort.Failure.INVALID_COPY_SET, duplicateResult.failure());
  }

  @Test
  void duplicateUuidRestoreStaysPendingAndQueuesExactRecoverySnapshots() throws Exception {
    var path = Files.createTempFile("mobmatrix-duplicate-restore", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies =
        List.of(new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));
    List<SerializedItemSnapshot> snapshots =
        List.of(
            new SerializedItemSnapshot(copies.get(0), new byte[] {1, 2, 3}),
            new SerializedItemSnapshot(copies.get(1), new byte[] {4, 5, 6}));
    try (Database database = new Database(path)) {
      SqliteCardRepository journal = new SqliteCardRepository(database);
      for (CardCopyId copy : copies) {
        journal.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      }
      journal.prepare(operation, owner, copies, snapshots);

      CardCustodyPort.ReturnResult result =
          CardInventoryService.restoreAmbiguous(
              operation, owner, copies, snapshots, Set.of(), journal);

      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(copies, result.expectedCopyIds());
      assertEquals(List.of(), result.returnedCopyIds());
      assertEquals(copies, result.missingCopyIds());
      assertEquals(copies, result.recoveryCopyIds());
      assertEquals(2, journal.pendingRecovery().size());
      assertArrayEquals(
          snapshots.get(0).bytes(), journal.pendingRecovery().get(0).snapshot().bytes());
      assertArrayEquals(
          snapshots.get(1).bytes(), journal.pendingRecovery().get(1).snapshot().bytes());
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
      CardInventoryService service =
          new CardInventoryService(
              new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);
      CardCustodyPort.CustodyResult result = service.abort(operation);
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(CardCustodyPort.Failure.NO_ROOM, result.failure());
      assertEquals(
          CardCustodyPort.OperationStatus.RETURN_PENDING,
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

      CardInventoryService service =
          new CardInventoryService(
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
      CardInventoryService service =
          new CardInventoryService(
              new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

      CardInventoryService.restoreAmbiguous(
          operation, owner, List.of(copy), List.of(snapshot), Set.of(), journal);

      CardCustodyPort.CustodyResult result = service.abort(operation);
      assertEquals(CardCustodyPort.OperationStatus.RETURN_PENDING, result.status());
      assertEquals(CardCustodyPort.Failure.NO_ROOM, result.failure());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void identityBearingInvalidItemsAreCounterfeitRegardlessOfMaterial() {
    assertTrue(
        CardInventoryService.isCounterfeit(
            CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.NOT_PAPER), true));
    assertTrue(
        CardInventoryService.isCounterfeit(
            CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.INVALID_SCHEMA), true));
    assertTrue(
        !CardInventoryService.isCounterfeit(
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
    assertFalse(
        CardInventoryService.exactItemMatchesSnapshot(
            valid, copy, owner, new byte[] {1, 2, 4}, exact));
    assertFalse(
        CardInventoryService.exactItemMatchesSnapshot(
            valid, copy, CopyOwner.house(), exact, exact));
    assertFalse(
        CardInventoryService.exactItemMatchesSnapshot(
            CardItemCodec.ReadResult.invalid(CardItemCodec.InvalidReason.INVALID_INTEGRITY),
            copy,
            owner,
            exact,
            exact));
  }

  @Test
  void itemFrameGuardOnlyCancelsReservedCards() {
    assertTrue(CardInventoryService.shouldCancelItemFrameInteraction(true, true, false));
    assertTrue(CardInventoryService.shouldCancelItemFrameInteraction(true, false, true));
    assertFalse(CardInventoryService.shouldCancelItemFrameInteraction(true, false, false));
    assertFalse(CardInventoryService.shouldCancelItemFrameInteraction(false, true, true));
  }

  @Test
  void malformedOpenCustodyRetainsEveryKnownReservedCopyUntilTerminalProof() {
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    CardCopyId laterCopy = new CardCopyId(UUID.randomUUID());
    UUID operation = UUID.randomUUID();
    MalformedJournal journal = new MalformedJournal(operation, List.of(copy, laterCopy));
    CardInventoryService service =
        new CardInventoryService(
            new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

    assertTrue(service.reservedCopyIds().containsAll(List.of(copy, laterCopy)));
    journal.terminal = true;
    assertFalse(service.reservedCopyIds().contains(copy));
    assertFalse(service.reservedCopyIds().contains(laterCopy));
  }

  private static final class MalformedJournal implements CardCustodyJournal {
    private final UUID operation;
    private final List<CardCopyId> copies;
    private boolean terminal;

    private MalformedJournal(UUID operation, List<CardCopyId> copies) {
      this.operation = operation;
      this.copies = List.copyOf(copies);
    }

    @Override
    public void prepare(
        UUID operationId,
        CopyOwner owner,
        List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots) {}

    @Override
    public Optional<Entry> find(UUID operationId) {
      if (terminal) {
        return Optional.of(
            new Entry(
                operation,
                CopyOwner.player(UUID.randomUUID()),
                copies,
                copies.stream()
                    .map(copy -> new SerializedItemSnapshot(copy, new byte[] {1}))
                    .toList(),
                OperationStatus.ABORTED,
                Set.copyOf(copies)));
      }
      throw new SqliteCardRepository.CustodyIntegrityException(
          operation, copies, "malformed reservation");
    }

    @Override
    public List<Entry> openEntries() {
      return List.of();
    }

    @Override
    public List<UUID> openOperationIds() {
      return List.of(operation);
    }

    @Override
    public void transition(UUID operationId, OperationStatus status) {}

    @Override
    public void markRestored(UUID operationId, CardCopyId copyId) {}

    @Override
    public void addRecovery(UUID operationId, CopyOwner owner, SerializedItemSnapshot snapshot) {}
  }

  @Test
  void unknownIdentityStateBlocksEveryCopyIdentity() {
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    CardCopyId unrelatedCopy = new CardCopyId(UUID.randomUUID());
    FlakyIdentityJournal journal = new FlakyIdentityJournal(Set.of(copy));
    CardInventoryService service =
        new CardInventoryService(
            new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

    assertTrue(service.isReserved(copy));
    journal.unresolved = true;
    assertTrue(service.isReserved(copy));
    assertTrue(service.isReserved(unrelatedCopy));
    assertTrue(service.reservedCopyIds().contains(copy));
  }

  @Test
  void identityRefreshRetainsPriorCopiesAndMarksUnknown() {
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    FlakyIdentityJournal journal = new FlakyIdentityJournal(Set.of(copy));
    CardInventoryService service =
        new CardInventoryService(
            new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

    assertTrue(service.reservedCopyIds().contains(copy));
    journal.failRefresh = true;
    assertTrue(service.reservedCopyIds().contains(copy));
    assertTrue(service.hasUnresolvedReservationIdentity());

    journal.failRefresh = false;
    journal.identities = Set.of();
    assertFalse(service.reservedCopyIds().contains(copy));
    assertFalse(service.hasUnresolvedReservationIdentity());
  }

  @Test
  void refreshRetainsPriorCopiesWhenEmptyReadPrecedesSecondReadFailure() {
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    FlakyIdentityJournal journal = new FlakyIdentityJournal(Set.of(copy));
    CardInventoryService service =
        new CardInventoryService(
            new CardItemCodec("test", "0123456789abcdef"), id -> null, journal);

    assertTrue(service.reservedCopyIds().contains(copy));
    journal.emptyThenFail = true;
    assertTrue(service.reservedCopyIds().contains(copy));
    assertTrue(service.hasUnresolvedReservationIdentity());
  }

  private static final class FlakyIdentityJournal implements CardCustodyJournal {
    private Set<CardCopyId> identities;
    private boolean failRefresh;
    private boolean emptyThenFail;
    private boolean throwSecondRead;
    private boolean unresolved;

    private FlakyIdentityJournal(Set<CardCopyId> identities) {
      this.identities = Set.copyOf(identities);
    }

    @Override
    public Set<CardCopyId> reservedCopyIds() {
      if (emptyThenFail) {
        emptyThenFail = false;
        throwSecondRead = true;
        return Set.of();
      }
      if (failRefresh) throw new IllegalStateException("identity refresh failed");
      return identities;
    }

    @Override
    public boolean hasUnresolvedReservationIdentity() {
      if (throwSecondRead) {
        throwSecondRead = false;
        unresolved = true;
        throw new IllegalStateException("identity uncertainty read failed");
      }
      return unresolved;
    }

    @Override
    public void prepare(
        UUID operationId,
        CopyOwner owner,
        List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots) {}

    @Override
    public Optional<Entry> find(UUID operationId) {
      return Optional.empty();
    }

    @Override
    public List<Entry> openEntries() {
      return List.of();
    }

    @Override
    public void transition(UUID operationId, OperationStatus status) {}

    @Override
    public void markRestored(UUID operationId, CardCopyId copyId) {}

    @Override
    public void addRecovery(UUID operationId, CopyOwner owner, SerializedItemSnapshot snapshot) {}
  }

  @Test
  void hydratesDurableReservedIdentitiesWhenOperationIdIsMalformed() throws Exception {
    var path = Files.createTempFile("mobmatrix-durable-reserved-identities", ".sqlite");
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
        pragma.execute("DROP TRIGGER active_copy_identity_integrity_guard");
      }
      try (var insert =
          database
              .connection()
              .prepareStatement(
                  """
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES ('malformed-operation', 'PLAYER', ?, NULL, 'PREPARED',
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, owner.playerId().toString());
        insert.executeUpdate();
      }
      try (var insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES ('malformed-operation', ?)")) {
        insert.setString(1, copy.value().toString());
        insert.executeUpdate();
      }

      CardInventoryService service =
          new CardInventoryService(
              new CardItemCodec("test", "0123456789abcdef"), id -> null, repository);
      assertTrue(service.reservedCopyIds().contains(copy));
      assertTrue(service.isReserved(copy));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void malformedReservedItemInInventoryForcesKeepInventoryScan() {
    boolean paperAvailable;
    try {
      new ItemStack(org.bukkit.Material.PAPER);
      paperAvailable = true;
    } catch (Throwable unavailable) {
      paperAvailable = false;
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(
        paperAvailable, "Paper API item registry requires a running Paper server");
    CardItemCodec codec = new CardItemCodec("mobmatrix", "0123456789abcdef");
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    ItemStack item =
        codec.create(
            new org.aincraft.mobmatrix.domain.CardDefinition(
                new CardId(1),
                "No. 1",
                "Dodo",
                1,
                org.aincraft.mobmatrix.domain.CardType.NORMAL,
                4,
                2,
                3,
                4,
                0),
            copy,
            CopyOwner.player(UUID.randomUUID()));
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer()
        .set(codec.integrityKey(), org.bukkit.persistence.PersistentDataType.STRING, "tampered");
    assertTrue(item.setItemMeta(meta));
    var journal = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    journal.prepare(
        UUID.randomUUID(),
        CopyOwner.player(UUID.randomUUID()),
        List.of(copy),
        List.of(new SerializedItemSnapshot(copy, new byte[] {1})));
    CardInventoryService service = new CardInventoryService(codec, id -> null, journal);
    Inventory inventory =
        (Inventory)
            java.lang.reflect.Proxy.newProxyInstance(
                Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("getSize")) return 1;
                  if (method.getName().equals("getItem")) return item;
                  throw new UnsupportedOperationException(method.getName());
                });

    assertTrue(!codec.read(item).isValid());
    assertTrue(service.hasReservedInventory(inventory));
  }

  @Test
  void everyInvalidIdentityBearingItemIsProtectedEvenWithoutReservationUncertainty() {
    boolean paperAvailable;
    try {
      new ItemStack(org.bukkit.Material.PAPER);
      paperAvailable = true;
    } catch (Throwable unavailable) {
      paperAvailable = false;
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(
        paperAvailable, "Paper API item registry requires a running Paper server");
    CardItemCodec codec = new CardItemCodec("mobmatrix", "0123456789abcdef");
    CardCopyId original = new CardCopyId(UUID.randomUUID());
    ItemStack changed =
        codec.create(
            new org.aincraft.mobmatrix.domain.CardDefinition(
                new CardId(1),
                "No. 1",
                "Dodo",
                1,
                org.aincraft.mobmatrix.domain.CardType.NORMAL,
                4,
                2,
                3,
                4,
                0),
            original,
            CopyOwner.player(UUID.randomUUID()));
    var changedMeta = changed.getItemMeta();
    changedMeta
        .getPersistentDataContainer()
        .set(
            codec.copyIdKey(),
            org.bukkit.persistence.PersistentDataType.STRING,
            UUID.randomUUID().toString());
    assertTrue(changed.setItemMeta(changedMeta));

    ItemStack malformed = changed.clone();
    var malformedMeta = malformed.getItemMeta();
    malformedMeta
        .getPersistentDataContainer()
        .set(codec.copyIdKey(), org.bukkit.persistence.PersistentDataType.STRING, "not-a-uuid");
    assertTrue(malformed.setItemMeta(malformedMeta));

    CardInventoryService service =
        new CardInventoryService(
            codec, id -> null, new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal());
    assertFalse(service.hasUnresolvedReservationIdentity());
    assertTrue(codec.read(changed).isInvalid());
    assertTrue(codec.read(malformed).isInvalid());
    assertTrue(service.isReserved(changed));
    assertTrue(service.isReserved(malformed));
  }
}
