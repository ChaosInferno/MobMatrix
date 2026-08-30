package org.aincraft.mobmatrix.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.MatchRepository;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.DraftOfferings;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class SqliteCustodyTest {
  private static final String MATCH_SECRET = "focused-match-secret-2026";
  private static String previousMatchSecret;

  @BeforeAll
  static void configureMatchSecret() {
    previousMatchSecret = System.getProperty("mobmatrix.match.server-secret");
    System.setProperty("mobmatrix.match.server-secret", MATCH_SECRET);
  }

  @AfterAll
  static void restoreMatchSecret() {
    if (previousMatchSecret == null) {
      System.clearProperty("mobmatrix.match.server-secret");
    } else {
      System.setProperty("mobmatrix.match.server-secret", previousMatchSecret);
    }
  }
  @Test
  void malformedReservationCopyRetainsEveryParseableIdentity() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-reservation-copy", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId first = new CardCopyId(UUID.randomUUID());
    CardCopyId second = new CardCopyId(UUID.randomUUID());
    CardCopyId third = new CardCopyId(UUID.randomUUID());
    List<CardCopyId> copies = List.of(first, second, third);
    List<SerializedItemSnapshot> snapshots = copies.stream()
        .map(copy -> new SerializedItemSnapshot(copy, new byte[] {1, 2}))
        .toList();
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      for (CardCopyId copy : copies) {
        repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      }
      repository.prepare(operation, owner, copies, snapshots);
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservation_copies SET copy_uuid = ? WHERE operation_id = ? AND copy_uuid = ?")) {
        update.setString(1, "not-a-copy-uuid");
        update.setString(2, operation.toString());
        update.setString(3, first.value().toString());
        update.executeUpdate();
      }

      var failure = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.findReservation(operation));
      assertEquals(List.of(second, third), failure.expectedCopyIds());
      assertEquals(operation, failure.operationId());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void malformedOperationIdDoesNotHideLaterValidOperationIds() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-operation-id", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy),
          List.of(new SerializedItemSnapshot(copy, new byte[] {3})));
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES ('not-an-operation', 'PLAYER', ?, NULL, 'PREPARED', '2000-01-01', '2000-01-01')
          """)) {
        insert.setString(1, owner.playerId().toString());
        insert.executeUpdate();
      }
      assertTrue(repository.openOperationIds().contains(operation));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void unresolvedReservationIdentityIsReportedForMalformedOperation() throws Exception {
    var path = Files.createTempFile("mobmatrix-unresolved-reservation-identity", ".sqlite");
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES ('malformed-operation', 'PLAYER', ?, NULL, 'PREPARED',
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, owner.playerId().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES ('malformed-operation', ?)")) {
        insert.setString(1, copy.value().toString());
        insert.executeUpdate();
      }

      assertTrue(repository.reservedCopyIds().contains(copy));
      assertTrue(repository.hasUnresolvedReservationIdentity());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void unresolvedReservationIdentityIsReportedForMalformedCopy() throws Exception {
    var path = Files.createTempFile("mobmatrix-unresolved-copy-identity", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy),
          List.of(new SerializedItemSnapshot(copy, new byte[] {4})));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservation_copies SET copy_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, "not-a-copy-uuid");
        update.setString(2, operation.toString());
        update.executeUpdate();
      }

      assertTrue(repository.reservedCopyIds().contains(copy));
      assertTrue(repository.hasUnresolvedReservationIdentity());
    }
    Files.deleteIfExists(path);
  }



  @Test
  void orphanIdentityRowsAcrossCustodyTablesRemainProtected() throws Exception {
    var path = Files.createTempFile("mobmatrix-orphan-identities", ".sqlite");
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId reservationCopy = new CardCopyId(UUID.randomUUID());
    CardCopyId escrowCopy = new CardCopyId(UUID.randomUUID());
    CardCopyId restoredCopy = new CardCopyId(UUID.randomUUID());
    CardCopyId recoveryCopy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, "orphan-reservation");
        insert.setString(2, reservationCopy.value().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO escrow_items(operation_id, copy_uuid, item_bytes) VALUES (?, ?, ?)")) {
        insert.setString(1, "orphan-escrow");
        insert.setString(2, escrowCopy.value().toString());
        insert.setBytes(3, new byte[] {1});
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_restored(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, "orphan-restored");
        insert.setString(2, restoredCopy.value().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO recovery_rows(operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status)
          VALUES (?, ?, 'PLAYER', ?, ?, 'PENDING')
          """)) {
        insert.setString(1, "orphan-recovery");
        insert.setString(2, recoveryCopy.value().toString());
        insert.setString(3, owner.playerId().toString());
        insert.setBytes(4, new byte[] {2});
        insert.executeUpdate();
      }

      assertEquals(Set.of(reservationCopy, escrowCopy, restoredCopy, recoveryCopy),
          repository.reservedCopyIds());
      assertTrue(repository.hasUnresolvedReservationIdentity());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void reservationsHaveUniqueCopiesAndDurableStatusTransitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-custody", ".sqlite");
    UUID operation = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1),
          CopyOwner.player(ownerId)));
      repository.prepare(operation, CopyOwner.player(ownerId), List.of(copy),
          List.of(new org.aincraft.mobmatrix.application.SerializedItemSnapshot(copy, new byte[] {1})));
      assertEquals(OperationStatus.PREPARED, repository.findReservation(operation).orElseThrow().status());
      assertThrows(IllegalStateException.class,
          () -> repository.prepare(UUID.randomUUID(), CopyOwner.player(UUID.randomUUID()), List.of(copy),
              List.of(new org.aincraft.mobmatrix.application.SerializedItemSnapshot(copy, new byte[] {1}))));
      repository.transition(operation, OperationStatus.ESCROWED);
      assertEquals(OperationStatus.ESCROWED, repository.findReservation(operation).orElseThrow().status());
    }
    try (Database reopened = new Database(path)) {
      assertEquals(OperationStatus.ESCROWED,
          new SqliteCardRepository(reopened).findReservation(operation).orElseThrow().status());
      assertTrue(reopened.connection().isValid(1));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void everyHydratedReservationRequiresExactEscrowSnapshots() throws Exception {
    var path = Files.createTempFile("mobmatrix-reservation-snapshot-integrity", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {4, 2});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      for (OperationStatus status : OperationStatus.values()) {
        try (var update = database.connection().prepareStatement(
            "UPDATE reservations SET status = ? WHERE operation_id = ?")) {
          update.setString(1, status.name());
          update.setString(2, operation.toString());
          update.executeUpdate();
        }
        try (var delete = database.connection().prepareStatement(
            "DELETE FROM escrow_items WHERE operation_id = ?")) {
          delete.setString(1, operation.toString());
          delete.executeUpdate();
        }
        var exception = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
            () -> repository.findReservation(operation));
        assertEquals(List.of(copy), exception.expectedCopyIds());
        assertEquals(operation, exception.operationId());
        repository.saveSnapshots(operation, List.of(snapshot));
      }
    }
    Files.deleteIfExists(path);
  }
  @Test
  void malformedHouseOwnerUuidIsRejectedWithReservationIdentity() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-house-owner", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {7, 3});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA ignore_check_constraints = ON");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET owner_kind = 'HOUSE', owner_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, UUID.randomUUID().toString());
        update.setString(2, operation.toString());
        update.executeUpdate();
      }
      var failure = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.findReservation(operation));
      assertEquals(operation, failure.operationId());
      assertEquals(List.of(copy), failure.expectedCopyIds());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void malformedReservationOwnerAndStatusPreserveIdentity() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-reservation", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {7, 3});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));

      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET owner_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, "not-a-uuid");
        update.setString(2, operation.toString());
        update.executeUpdate();
      }
      var ownerFailure = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.findReservation(operation));
      assertEquals(operation, ownerFailure.operationId());
      assertEquals(List.of(copy), ownerFailure.expectedCopyIds());
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA ignore_check_constraints = ON");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET owner_uuid = ?, status = ? WHERE operation_id = ?")) {
        update.setString(1, owner.playerId().toString());
        update.setString(2, "CORRUPT");
        update.setString(3, operation.toString());
        update.executeUpdate();
      }
      var statusFailure = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.findReservation(operation));
      assertEquals(operation, statusFailure.operationId());
      assertEquals(List.of(copy), statusFailure.expectedCopyIds());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void inMemoryPrepareRejectsIncompleteOrMismatchedSnapshots() {
    var journal = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId first = new CardCopyId(UUID.randomUUID());
    CardCopyId second = new CardCopyId(UUID.randomUUID());
    List<CardCopyId> copies = List.of(first, second);
    assertThrows(IllegalArgumentException.class,
        () -> journal.prepare(operation, owner, copies,
            List.of(new SerializedItemSnapshot(first, new byte[] {1}))));
    assertThrows(IllegalArgumentException.class,
        () -> journal.prepare(operation, owner, copies,
            List.of(new SerializedItemSnapshot(first, new byte[] {1}),
                new SerializedItemSnapshot(first, new byte[] {2}))));
    assertThrows(IllegalArgumentException.class,
        () -> journal.prepare(operation, owner, copies,
            List.of(new SerializedItemSnapshot(first, new byte[] {1}),
                new SerializedItemSnapshot(new CardCopyId(UUID.randomUUID()), new byte[] {2}))));
  }
  @Test
  void inMemoryRejectsActiveCopyOverlapButAllowsProvenTerminalReuse() {
    var journal = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {5});
    journal.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
    assertThrows(IllegalStateException.class,
        () -> journal.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));
    journal.transition(firstOperation, OperationStatus.RETURN_PENDING);
    journal.markRestored(firstOperation, copy);
    journal.transition(firstOperation, OperationStatus.RETURNED);
    journal.prepare(secondOperation, owner, List.of(copy), List.of(snapshot));
    assertEquals(OperationStatus.PREPARED,
        journal.find(secondOperation).orElseThrow().status());
  }
  @Test
  void malformedTerminalRowsAreEnumeratedForStartupValidation() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-terminal-enumeration", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy),
          List.of(new SerializedItemSnapshot(copy, new byte[] {1})));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA ignore_check_constraints = ON");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET status = 'RETURNED' WHERE operation_id = ?")) {
        update.setString(1, operation.toString());
        update.executeUpdate();
      }
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM escrow_items WHERE operation_id = ?")) {
        delete.setString(1, operation.toString());
        delete.executeUpdate();
      }
      assertTrue(repository.openOperationIds().contains(operation));
      var failure = assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.findReservation(operation));
      assertEquals(operation, failure.operationId());
      assertEquals(List.of(copy), failure.expectedCopyIds());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void triggerRejectsMalformedTerminalOwnersBeforeReuse() throws Exception {
    var path = Files.createTempFile("mobmatrix-copy-guard-owner", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    UUID thirdOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {6});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      repository.transition(firstOperation, OperationStatus.ESCROWED);
      repository.transition(firstOperation, OperationStatus.RETURN_PENDING);
      repository.markRestored(firstOperation, copy);
      repository.transition(firstOperation, OperationStatus.RETURNED);
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA ignore_check_constraints = ON");
      }

      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET owner_kind = 'HOUSE', owner_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, UUID.randomUUID().toString());
        update.setString(2, firstOperation.toString());
        update.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, owner.playerId().toString());
        insert.executeUpdate();
      }
      assertThrows(java.sql.SQLException.class, () -> {
        try (var insert = database.connection().prepareStatement(
            "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
          insert.setString(1, secondOperation.toString());
          insert.setString(2, copy.value().toString());
          insert.executeUpdate();
        }
      });

      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET owner_kind = 'PLAYER', owner_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, "not-a-player-uuid");
        update.setString(2, firstOperation.toString());
        update.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, thirdOperation.toString());
        insert.setString(2, owner.playerId().toString());
        insert.executeUpdate();
      }
      assertThrows(java.sql.SQLException.class, () -> {
        try (var insert = database.connection().prepareStatement(
            "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
          insert.setString(1, thirdOperation.toString());
          insert.setString(2, copy.value().toString());
          insert.executeUpdate();
        }
      });
    }
    Files.deleteIfExists(path);
  }


  @Test
  void unknownOrUnprovenTerminalCopyCannotBeReused() throws Exception {
    var path = Files.createTempFile("mobmatrix-copy-guard", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {2});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA ignore_check_constraints = ON");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservations SET status = 'UNKNOWN' WHERE operation_id = ?")) {
        update.setString(1, firstOperation.toString());
        update.executeUpdate();
      }
      assertThrows(RuntimeException.class,
          () -> repository.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void migrationRecreatesCopyGuardWithTerminalProof() throws Exception {
    var path = Files.createTempFile("mobmatrix-copy-guard-migration", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {3});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      repository.transition(firstOperation, OperationStatus.ESCROWED);
      repository.transition(firstOperation, OperationStatus.RETURN_PENDING);
      repository.markRestored(firstOperation, copy);
      repository.transition(firstOperation, OperationStatus.RETURNED);
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM escrow_items WHERE operation_id = ?")) {
        delete.setString(1, firstOperation.toString());
        delete.executeUpdate();
      }
      try (var statement = database.connection().createStatement()) {
        statement.execute("DROP TRIGGER active_copy_reservation_guard");
        statement.execute("""
            CREATE TRIGGER active_copy_reservation_guard
            BEFORE INSERT ON reservation_copies
            WHEN EXISTS (
              SELECT 1 FROM reservation_copies existing
              JOIN reservations reservation ON reservation.operation_id = existing.operation_id
              WHERE existing.copy_uuid = NEW.copy_uuid
                AND reservation.status IN ('PREPARED', 'ESCROWED', 'RETURN_PENDING')
            )
            BEGIN SELECT RAISE(ABORT, 'old guard'); END
            """);
        statement.execute("DELETE FROM schema_migrations WHERE version >= 6");
        SchemaMigrator.migrate(database.connection());
      }
      assertThrows(RuntimeException.class,
          () -> repository.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void abortedTransitionRequiresExactEscrowSnapshotsBeforeIdempotence() throws Exception {
    var path = Files.createTempFile("mobmatrix-aborted-snapshot-integrity", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {8, 6});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      repository.markRestored(operation, copy);
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM escrow_items WHERE operation_id = ?")) {
        delete.setString(1, operation.toString());
        delete.executeUpdate();
      }
      assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.transition(operation, OperationStatus.ABORTED));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void reservationIdentityBindsOperationToMatchAcrossReopen() throws Exception {
    var path = Files.createTempFile("mobmatrix-match-binding", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {4, 2});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot), "match-a");
      assertEquals("match-a", repository.findReservation(operation).orElseThrow().matchId());
      assertThrows(IllegalStateException.class,
          () -> repository.prepare(operation, owner, List.of(copy), List.of(snapshot), "match-b"));
    }
    try (Database database = new Database(path)) {
      assertEquals("match-a", new SqliteCardRepository(database).findReservation(operation)
          .orElseThrow().matchId());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void v2DatabaseMigrationAddsMatchBindingWithoutLosingReservations() throws Exception {
    var path = Files.createTempFile("mobmatrix-v2-migration", ".sqlite");
    UUID existingOperation = UUID.randomUUID();
    UUID newOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId existingCopy = new CardCopyId(UUID.randomUUID());
    CardCopyId newCopy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(existingCopy, new CardId(1), owner));
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(newCopy, new CardId(1), owner));
      repository.prepare(existingOperation, owner, List.of(existingCopy),
          List.of(new SerializedItemSnapshot(existingCopy, new byte[] {1})));
      try (var statement = database.connection().createStatement()) {
        statement.execute("ALTER TABLE reservations DROP COLUMN match_id");
        statement.execute("DELETE FROM schema_migrations WHERE version >= 3");
      }
      SchemaMigrator.migrate(database.connection());
      assertEquals(SchemaMigrator.CURRENT_VERSION, SchemaMigrator.currentVersion(database.connection()));
      assertNull(repository.findReservation(existingOperation).orElseThrow().matchId());
      repository.prepare(newOperation, owner, List.of(newCopy),
          List.of(new SerializedItemSnapshot(newCopy, new byte[] {2})), "migrated-match");
      assertEquals("migrated-match", repository.findReservation(newOperation).orElseThrow().matchId());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void migrationRejectsNonContiguousMarkers() throws Exception {
    var path = Files.createTempFile("mobmatrix-migration-gap", ".sqlite");
    try (Database database = new Database(path)) {
      try (var statement = database.connection().createStatement()) {
        statement.execute("DELETE FROM schema_migrations WHERE version = 3");
      }
      assertThrows(java.sql.SQLException.class,
          () -> SchemaMigrator.migrate(database.connection()));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void migrationRepairsPartialV3V4V5DdlAndRecordsV5Marker() throws Exception {
    var path = Files.createTempFile("mobmatrix-migration-partial", ".sqlite");
    try (Database database = new Database(path)) {
      try (var statement = database.connection().createStatement()) {
        statement.execute("ALTER TABLE reservations DROP COLUMN match_id");
        statement.execute("ALTER TABLE matches DROP COLUMN state_digest");
        statement.execute("DROP TABLE match_start_bindings");
        statement.execute("DELETE FROM schema_migrations WHERE version >= 3");
      }
      SchemaMigrator.migrate(database.connection());
      assertEquals(SchemaMigrator.CURRENT_VERSION,
          SchemaMigrator.currentVersion(database.connection()));
      try (var query = database.connection().prepareStatement(
          "SELECT 1 FROM schema_migrations WHERE version = 5")) {
        assertTrue(query.executeQuery().next());
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void matchSnapshotRejectsMissingAndMismatchedIntegrity() throws Exception {
    var path = Files.createTempFile("mobmatrix-match-integrity", ".sqlite");
    MatchState missing = snapshotState("missing-integrity");
    MatchState mismatched = snapshotState("mismatched-integrity");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, missing).accepted());
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET state_digest = NULL WHERE match_id = ?")) {
        update.setString(1, missing.matchId().value());
        update.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(missing.matchId(), missing.catalog()));
      assertThrows(IllegalArgumentException.class,
          () -> repository.rehydrate(repository.find(missing.matchId()).orElseThrow(),
              missing.catalog()));

      assertTrue(repository.save(0L, mismatched).accepted());
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET state_bytes = ? WHERE match_id = ?")) {
        update.setBytes(1, new byte[] {1, 2, 3});
        update.setString(2, mismatched.matchId().value());
        update.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(mismatched.matchId(), mismatched.catalog()));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void matchSnapshotUsesKeyedMacAndRejectsWrongKeyAndLegacyHash() throws Exception {
    var path = Files.createTempFile("mobmatrix-match-hmac", ".sqlite");
    MatchState original = snapshotState("hmac-integrity");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database, MATCH_SECRET);
      assertTrue(repository.save(0L, original).accepted());
      MatchRepository.MatchSnapshot persisted = repository.find(original.matchId()).orElseThrow();
      assertTrue(persisted.stateDigest().startsWith("hmac-sha256-v1:"));

      SqliteMatchRepository wrongKey = new SqliteMatchRepository(database,
          "different-match-secret-2026");
      assertThrows(IllegalArgumentException.class,
          () -> wrongKey.findState(original.matchId(), original.catalog()));

      byte[] tampered = persisted.stateBytes();
      tampered[tampered.length - 1] ^= 1;
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET state_bytes = ?, state_digest = ? WHERE match_id = ?")) {
        update.setBytes(1, tampered);
        update.setString(2, legacySha256(tampered));
        update.setString(3, original.matchId().value());
        update.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(original.matchId(), original.catalog()));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void successorCasRejectsTamperedAuthenticatedPredecessor() throws Exception {
    var path = Files.createTempFile("mobmatrix-predecessor-cas", ".sqlite");
    MatchState original = snapshotState("predecessor-cas");
    MatchEngine.MatchTransition transition = MatchEngine.apply(original, new MatchCommand.PlaceCard(
        original.currentSeat(), original.hand(original.currentSeat()).getFirst().copyId(),
        Square.CENTER, original.revision(), UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:01Z")));
    assertTrue(transition.accepted());
    MatchState successor = transition.state();
    assertEquals(1L, successor.revision());
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database, MATCH_SECRET);
      assertTrue(repository.save(0L, original).accepted());
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET state_digest = NULL WHERE match_id = ?")) {
        update.setString(1, original.matchId().value());
        update.executeUpdate();
      }
      MatchRepository.SaveResult result = repository.save(0L, successor);
      assertFalse(result.accepted());
      assertEquals(MatchRepository.Failure.INVALID_STATE, result.failure());
      try (var query = database.connection().prepareStatement(
          "SELECT revision, state_digest FROM matches WHERE match_id = ?")) {
        query.setString(1, original.matchId().value());
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(original.revision(), rows.getLong(1));
          assertNull(rows.getString(2));
        }
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void successorSaveRejectsAlteredImmutableCatalogDefinitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-successor-catalog", ".sqlite");
    MatchState original = snapshotState("successor-catalog");
    MatchEngine.MatchTransition transition = MatchEngine.apply(original, new MatchCommand.PlaceCard(
        original.currentSeat(), original.hand(original.currentSeat()).getFirst().copyId(),
        Square.CENTER, original.revision(), UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:01Z")));
    assertTrue(transition.accepted());
    MatchState successor = transition.state();
    List<CardDefinition> alteredDefinitions = new ArrayList<>(successor.catalog().all());
    CardDefinition first = alteredDefinitions.getFirst();
    alteredDefinitions.set(0, new CardDefinition(first.id(), first.publicNumber(), "Altered",
        first.stars(), first.type(), first.top(), first.right(), first.bottom(), first.left(),
        first.salePrice()));
    CardCatalog alteredCatalog = new CardCatalog(alteredDefinitions, successor.catalog().snapshotId());
    MatchState altered = MatchState.rehydrate(successor.matchId(), alteredCatalog, successor.rules(),
        successor.board(), successor.hands(), successor.currentSeat(), successor.revision(),
        successor.result(), successor.bankMillis(), successor.turnStartedAt(), successor.graceDeadline(),
        successor.turnDeadline(), successor.commandIds(), successor.seed(), successor.transcript(),
        successor.revealedCardsMap(), successor.originalOwners(), successor.consecutiveDraws(),
        successor.draftOfferings(), successor.draftSelections(), successor.chaosChoices());
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database, MATCH_SECRET);
      assertTrue(repository.save(0L, original).accepted());
      assertThrows(IllegalArgumentException.class, () -> repository.save(0L, altered));
      try (var query = database.connection().prepareStatement(
          "SELECT revision FROM matches WHERE match_id = ?")) {
        query.setString(1, original.matchId().value());
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(original.revision(), rows.getLong(1));
        }
      }
    }
    Files.deleteIfExists(path);
  }


  @Test
  void matchRepositoryRejectsUnavailableOrShortSecret() throws Exception {
    var path = Files.createTempFile("mobmatrix-match-secret", ".sqlite");
    try (Database database = new Database(path)) {
      assertThrows(IllegalArgumentException.class,
          () -> new SqliteMatchRepository(database, "too-short"));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void custodyJournalsRejectAbortedUntilEveryCopyIsRestored() throws Exception {
    CardCopyId first = new CardCopyId(UUID.randomUUID());
    CardCopyId second = new CardCopyId(UUID.randomUUID());
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(first, second);
    List<SerializedItemSnapshot> snapshots = List.of(
        new SerializedItemSnapshot(first, new byte[] {1}),
        new SerializedItemSnapshot(second, new byte[] {2}));

    var memory = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    UUID memoryOperation = UUID.randomUUID();
    memory.prepare(memoryOperation, owner, copies, snapshots);
    assertThrows(IllegalStateException.class,
        () -> memory.transition(memoryOperation, OperationStatus.ABORTED));
    memory.markRestored(memoryOperation, first);
    assertThrows(IllegalStateException.class,
        () -> memory.transition(memoryOperation, OperationStatus.ABORTED));
    memory.markRestored(memoryOperation, second);
    memory.transition(memoryOperation, OperationStatus.ABORTED);
    assertThrows(IllegalArgumentException.class,
        () -> new org.aincraft.mobmatrix.application.CardCustodyJournal.Entry(memoryOperation,
            owner, copies, snapshots, OperationStatus.ABORTED, java.util.Set.of()));

    var path = Files.createTempFile("mobmatrix-aborted-invariant", ".sqlite");
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(first, new CardId(1), owner));
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(second, new CardId(2), owner));
      UUID operation = UUID.randomUUID();
      repository.prepare(operation, owner, copies, snapshots);
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.ABORTED));
      repository.markRestored(operation, first);
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.ABORTED));
      repository.markRestored(operation, second);
      repository.transition(operation, OperationStatus.ABORTED);
      assertEquals(OperationStatus.ABORTED,
          repository.findReservation(operation).orElseThrow().status());
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM reservation_restored WHERE operation_id = ? AND copy_uuid = ?")) {
        delete.setString(1, operation.toString());
        delete.setString(2, second.value().toString());
        delete.executeUpdate();
      }
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.ABORTED));
      assertThrows(IllegalStateException.class, () -> repository.findReservation(operation));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void custodyJournalsRejectReturnedUntilEveryCopyIsRestored() throws Exception {
    CardCopyId first = new CardCopyId(UUID.randomUUID());
    CardCopyId second = new CardCopyId(UUID.randomUUID());
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(first, second);
    List<SerializedItemSnapshot> snapshots = List.of(
        new SerializedItemSnapshot(first, new byte[] {1}),
        new SerializedItemSnapshot(second, new byte[] {2}));

    var memory = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    UUID memoryOperation = UUID.randomUUID();
    memory.prepare(memoryOperation, owner, copies, snapshots);
    memory.transition(memoryOperation, OperationStatus.RETURN_PENDING);
    assertThrows(IllegalStateException.class,
        () -> memory.transition(memoryOperation, OperationStatus.RETURNED));
    memory.markRestored(memoryOperation, first);
    assertThrows(IllegalStateException.class,
        () -> memory.transition(memoryOperation, OperationStatus.RETURNED));
    memory.markRestored(memoryOperation, second);
    memory.transition(memoryOperation, OperationStatus.RETURNED);
    memory.transition(memoryOperation, OperationStatus.RETURNED);
    assertThrows(IllegalArgumentException.class,
        () -> new org.aincraft.mobmatrix.application.CardCustodyJournal.Entry(memoryOperation,
            owner, copies, snapshots, OperationStatus.RETURNED, java.util.Set.of()));

    var path = Files.createTempFile("mobmatrix-returned-invariant", ".sqlite");
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(first, new CardId(1), owner));
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(second, new CardId(2), owner));
      UUID operation = UUID.randomUUID();
      repository.prepare(operation, owner, copies, snapshots);
      repository.transition(operation, OperationStatus.RETURN_PENDING);
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.RETURNED));
      repository.markRestored(operation, first);
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.RETURNED));
      repository.markRestored(operation, second);
      repository.transition(operation, OperationStatus.RETURNED);
      repository.transition(operation, OperationStatus.RETURNED);
      assertEquals(OperationStatus.RETURNED,
          repository.findReservation(operation).orElseThrow().status());
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM reservation_restored WHERE operation_id = ? AND copy_uuid = ?")) {
        delete.setString(1, operation.toString());
        delete.setString(2, second.value().toString());
        delete.executeUpdate();
      }
      assertThrows(IllegalStateException.class,
          () -> repository.transition(operation, OperationStatus.RETURNED));
      assertThrows(IllegalStateException.class, () -> repository.findReservation(operation));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void legacyAbortedRowWithMissingSnapshotsIsNotHydratedAsComplete() throws Exception {
    var path = Files.createTempFile("mobmatrix-legacy-aborted", ".sqlite");
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {3, 1, 4});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      repository.markRestored(operation, copy);
      repository.transition(operation, OperationStatus.ABORTED);
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM escrow_items WHERE operation_id = ?")) {
        delete.setString(1, operation.toString());
        delete.executeUpdate();
      }
      assertThrows(IllegalStateException.class, () -> repository.findReservation(operation));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void startBindingSurvivesRepositoryReconstruction() throws Exception {
    var path = Files.createTempFile("mobmatrix-start-binding", ".sqlite");
    MatchId matchId = new MatchId("durable-start-binding");
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> oneCopies = List.of(new CardCopyId(UUID.randomUUID()));
    List<CardCopyId> twoCopies = List.of(new CardCopyId(UUID.randomUUID()));
    MatchRepository.StartBinding expected = new MatchRepository.StartBinding(matchId, 1, "identity",
        oneOwner, oneCopies, UUID.randomUUID(), twoOwner, twoCopies, UUID.randomUUID());
    try (Database database = new Database(path)) {
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO matches(match_id, revision, status, state_bytes, transcript_json, updated_at) "
              + "VALUES (?, 0, 'ACTIVE', ?, '{}', CURRENT_TIMESTAMP)")) {
        insert.setString(1, matchId.value());
        insert.setBytes(2, new byte[] {1});
        insert.executeUpdate();
      }
      new SqliteMatchRepository(database).saveStartBinding(expected);
    }
    try (Database database = new Database(path)) {
      assertEquals(expected, new SqliteMatchRepository(database).findStartBinding(matchId).orElseThrow());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void startBindingIsIdempotentButRejectsIdentityOverwrite() throws Exception {
    var path = Files.createTempFile("mobmatrix-start-binding-immutable", ".sqlite");
    MatchId matchId = new MatchId("immutable-start-binding");
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(new CardCopyId(UUID.randomUUID()));
    MatchRepository.StartBinding expected = new MatchRepository.StartBinding(matchId, 1, "identity",
        owner, copies, UUID.randomUUID(), owner, copies, UUID.randomUUID());
    try (Database database = new Database(path)) {
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO matches(match_id, revision, status, state_bytes, transcript_json, updated_at) "
              + "VALUES (?, 0, 'ACTIVE', ?, '{}', CURRENT_TIMESTAMP)")) {
        insert.setString(1, matchId.value());
        insert.setBytes(2, new byte[] {1});
        insert.executeUpdate();
      }
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      repository.saveStartBinding(expected);
      repository.saveStartBinding(expected);
      MatchRepository.StartBinding changed = new MatchRepository.StartBinding(matchId, 2, "identity",
          owner, copies, expected.oneOperationId(), owner, copies, expected.twoOperationId());
      assertThrows(IllegalArgumentException.class, () -> repository.saveStartBinding(changed));
      assertEquals(expected, repository.findStartBinding(matchId).orElseThrow());
    }
    Files.deleteIfExists(path);
  }


  @Test
  void markingRestoredClearsRecoveryInSameTransaction() throws Exception {
    var path = Files.createTempFile("mobmatrix-recovery", ".sqlite");
    UUID operation = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    CopyOwner owner = CopyOwner.player(ownerId);
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {9, 8, 7});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      repository.addRecovery(operation, owner, snapshot);
      assertEquals(1, repository.pendingRecovery().size());
      repository.markRestored(operation, copy);
      assertTrue(repository.pendingRecovery().isEmpty());
      assertEquals(List.of(copy), repository.findReservation(operation).orElseThrow()
          .restoredCopyIds());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void journalPersistsRestoredCopiesAndRejectsArbitraryRecoveryOwners() throws Exception {
    var path = Files.createTempFile("mobmatrix-journal", ".sqlite");
    UUID operation = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    CopyOwner owner = CopyOwner.player(ownerId);
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {9, 8, 7});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(operation, owner, List.of(copy), List.of(snapshot));
      repository.markRestored(operation, copy);
      assertEquals(List.of(copy), repository.findReservation(operation).orElseThrow().restoredCopyIds());
      assertArrayEquals(snapshot.bytes(), repository.findReservation(operation).orElseThrow()
          .snapshots().get(0).bytes());
      assertThrows(IllegalStateException.class, () -> repository.addRecovery(operation,
          CopyOwner.player(UUID.randomUUID()), snapshot));
    }
    try (Database reopened = new Database(path)) {
      assertEquals(List.of(operation), reopened == null ? List.of() : new SqliteCardRepository(reopened)
          .findOpenReservations().stream().map(SqliteCardRepository.Reservation::operationId).toList());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void completeMatchSnapshotRehydratesExecutableState() throws Exception {
    var path = Files.createTempFile("mobmatrix-match", ".sqlite");
    List<CardDefinition> definitions = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, index, index, index, index, 0));
    }
    CardCatalog catalog = new CardCatalog(definitions, "catalog-v1");
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index + 1), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index + 1), twoOwner));
    }
    MatchEngine.MatchTransition transition = MatchEngine.start(new MatchEngine.StartMatch(
        new MatchId("rehydrate-match"), new Deck(one), new Deck(two), catalog, new MatchRules(),
        41L, Instant.parse("2026-01-01T00:00:00Z")));
    assertTrue(transition.accepted());
    var original = transition.state();
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      var restored = repository.findState(original.matchId(), catalog).orElseThrow();
      assertEquals(original.matchId(), restored.matchId());
      assertEquals(original.revision(), restored.revision());
      assertEquals(original.catalog().snapshotId(), restored.catalog().snapshotId());
      assertEquals(original.currentSeat(), restored.currentSeat());
      assertEquals(original.hand(PlayerSeat.ONE), restored.hand(PlayerSeat.ONE));
      assertEquals(original.hand(PlayerSeat.TWO), restored.hand(PlayerSeat.TWO));
      assertEquals(original.randomDecisions(), restored.randomDecisions());
      assertEquals(original.turnDeadline(), restored.turnDeadline());
    }
    Files.deleteIfExists(path);
  }
  @Test
  void saveInitialRollsBackMatchWhenBindingInsertFails() throws Exception {
    var path = Files.createTempFile("mobmatrix-atomic-initial", ".sqlite");
    MatchState state = snapshotState("atomic-initial");
    CopyOwner oneOwner = state.hand(PlayerSeat.ONE).getFirst().copy().owner();
    CopyOwner twoOwner = state.hand(PlayerSeat.TWO).getFirst().copy().owner();
    List<CardCopyId> oneCopies = state.hand(PlayerSeat.ONE).stream().map(card -> card.copyId()).toList();
    List<CardCopyId> twoCopies = state.hand(PlayerSeat.TWO).stream().map(card -> card.copyId()).toList();
    MatchRepository.StartBinding binding = new MatchRepository.StartBinding(state.matchId(), 1,
        "atomic-initial-identity", oneOwner, oneCopies, UUID.randomUUID(), twoOwner, twoCopies,
        UUID.randomUUID());
    try (Database database = new Database(path)) {
      try (var statement = database.connection().createStatement()) {
        statement.execute("""
            CREATE TRIGGER reject_initial_binding
            BEFORE INSERT ON match_start_bindings
            BEGIN SELECT RAISE(ABORT, 'injected binding failure'); END
            """);
      }
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertThrows(IllegalStateException.class, () -> repository.saveInitial(state, binding));
      try (var query = database.connection().prepareStatement(
          "SELECT COUNT(*) FROM matches WHERE match_id = ?")) {
        query.setString(1, state.matchId().value());
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1));
        }
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void snapshotRejectsIncompleteOriginalOwnerMetadata() {
    MatchState original = snapshotState("missing-owner");
    Map<CardCopyId, PlayerSeat> owners = new HashMap<>(original.originalOwners());
    owners.remove(original.hand(PlayerSeat.ONE).getFirst().copyId());
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(), original.turnDeadline(),
        original.commandIds(), original.seed(), original.transcript(), original.revealedCardsMap(), owners,
        original.consecutiveDraws(), original.draftOfferings(), original.draftSelections(),
        original.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsExtraOriginalOwnerMetadata() {
    MatchState original = snapshotState("extra-owner");
    Map<CardCopyId, PlayerSeat> owners = new HashMap<>(original.originalOwners());
    owners.put(new CardCopyId(UUID.randomUUID()), PlayerSeat.ONE);
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(), original.turnDeadline(),
        original.commandIds(), original.seed(), original.transcript(), original.revealedCardsMap(), owners,
        original.consecutiveDraws(), original.draftOfferings(), original.draftSelections(),
        original.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsOriginalOwnerMappedToWrongSeat() {
    MatchState original = snapshotState("wrong-owner-seat");
    Map<CardCopyId, PlayerSeat> owners = new HashMap<>(original.originalOwners());
    CardCopyId copy = original.hand(PlayerSeat.ONE).getFirst().copyId();
    owners.put(copy, PlayerSeat.TWO);
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(), original.turnDeadline(),
        original.commandIds(), original.seed(), original.transcript(), original.revealedCardsMap(), owners,
        original.consecutiveDraws(), original.draftOfferings(), original.draftSelections(),
        original.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsEmptyOwnerInferenceAfterPlacement() {
    MatchState initial = snapshotState("placed-empty-owners");
    MatchState placed = MatchEngine.apply(initial, new MatchCommand.PlaceCard(initial.currentSeat(),
        initial.hand(initial.currentSeat()).getFirst().copyId(), Square.CENTER, initial.revision(),
        UUID.randomUUID(), Instant.parse("2026-01-01T00:00:01Z"))).state();
    MatchState tampered = MatchState.rehydrate(placed.matchId(), placed.catalog(), placed.rules(),
        placed.board(), placed.hands(), placed.currentSeat(), placed.revision(), placed.result(),
        placed.bankMillis(), placed.turnStartedAt(), placed.graceDeadline(), placed.turnDeadline(),
        placed.commandIds(), placed.seed(), placed.transcript(), placed.revealedCardsMap(), Map.of(),
        placed.consecutiveDraws(), placed.draftOfferings(), placed.draftSelections(),
        placed.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            placed.catalog()));
  }


  @Test
  void snapshotRejectsChaosChoiceInNonChaosState() {
    MatchState original = snapshotState("non-chaos-choice");
    Map<PlayerSeat, CardCopyId> choices = Map.of(PlayerSeat.ONE,
        original.hand(PlayerSeat.ONE).getFirst().copyId());
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(), original.turnDeadline(),
        original.commandIds(), original.seed(), original.transcript(), original.revealedCardsMap(),
        original.originalOwners(), original.consecutiveDraws(), original.draftOfferings(),
        original.draftSelections(), choices);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsChaosChoiceOutsideThatSeatsHand() {
    MatchState original = chaosSnapshotState("chaos-out-of-hand");
    PlayerSeat seat = original.currentSeat();
    CardCopyId foreign = original.hand(seat == PlayerSeat.ONE ? PlayerSeat.TWO : PlayerSeat.ONE)
        .getFirst().copyId();
    Map<PlayerSeat, CardCopyId> choices = Map.of(seat, foreign);
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(), original.turnDeadline(),
        original.commandIds(), original.seed(), original.transcript(), original.revealedCardsMap(),
        original.originalOwners(), original.consecutiveDraws(), original.draftOfferings(),
        original.draftSelections(), choices);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }
  @Test
  void snapshotRejectsNonDraftHouseOwnedActiveCopy() {
    MatchState original = snapshotState("active-house-copy");
    PlayerSeat seat = PlayerSeat.ONE;
    List<org.aincraft.mobmatrix.domain.HandCard> oneHand =
        new ArrayList<>(original.hand(seat));
    org.aincraft.mobmatrix.domain.HandCard card = oneHand.getFirst();
    CardCopy houseCopy = new CardCopy(card.copyId(), card.cardId(), CopyOwner.house());
    oneHand.set(0, new org.aincraft.mobmatrix.domain.HandCard(houseCopy,
        original.catalog().require(card.cardId())));
    Map<PlayerSeat, List<org.aincraft.mobmatrix.domain.HandCard>> hands = new EnumMap<>(
        PlayerSeat.class);
    hands.put(PlayerSeat.ONE, oneHand);
    hands.put(PlayerSeat.TWO, original.hand(PlayerSeat.TWO));
    Map<CardCopyId, PlayerSeat> owners = new HashMap<>(original.originalOwners());
    owners.remove(card.copyId());
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), hands, original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(),
        original.turnDeadline(), original.commandIds(), original.seed(), original.transcript(),
        original.revealedCardsMap(), owners, original.consecutiveDraws(), original.draftOfferings(),
        original.draftSelections(), original.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsChaosChoiceForOpponentSeat() {
    MatchState original = chaosSnapshotState("chaos-opponent-choice");
    PlayerSeat opponent = original.currentSeat() == PlayerSeat.ONE ? PlayerSeat.TWO : PlayerSeat.ONE;
    Map<PlayerSeat, CardCopyId> choices = Map.of(opponent,
        original.hand(opponent).getFirst().copyId());
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(),
        original.result(), original.bankMillis(), original.turnStartedAt(),
        original.graceDeadline(), original.turnDeadline(), original.commandIds(), original.seed(),
        original.transcript(), original.revealedCardsMap(), original.originalOwners(),
        original.consecutiveDraws(), original.draftOfferings(), original.draftSelections(), choices);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void snapshotRejectsChaosChoiceMissingForNonemptyCurrentHand() {
    MatchState original = chaosSnapshotState("chaos-missing-choice");
    MatchState tampered = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), original.hands(), original.currentSeat(), original.revision(),
        original.result(), original.bankMillis(), original.turnStartedAt(),
        original.graceDeadline(), original.turnDeadline(), original.commandIds(), original.seed(),
        original.transcript(), original.revealedCardsMap(), original.originalOwners(),
        original.consecutiveDraws(), original.draftOfferings(), original.draftSelections(), Map.of());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void emptyCurrentChaosHandMayHaveNoChoice() {
    MatchState original = chaosSnapshotState("chaos-empty-current-hand");
    Map<PlayerSeat, List<org.aincraft.mobmatrix.domain.HandCard>> hands = new EnumMap<>(
        PlayerSeat.class);
    hands.put(original.currentSeat(), List.of());
    PlayerSeat opponent = original.currentSeat() == PlayerSeat.ONE ? PlayerSeat.TWO : PlayerSeat.ONE;
    hands.put(opponent, original.hand(opponent));
    Map<CardCopyId, PlayerSeat> owners = new HashMap<>(original.originalOwners());
    for (var card : original.hand(original.currentSeat())) owners.remove(card.copyId());
    MatchState valid = MatchState.rehydrate(original.matchId(), original.catalog(), original.rules(),
        original.board(), hands, original.currentSeat(), original.revision(), original.result(),
        original.bankMillis(), original.turnStartedAt(), original.graceDeadline(),
        original.turnDeadline(), original.commandIds(), original.seed(), original.transcript(),
        original.revealedCardsMap(), owners, original.consecutiveDraws(), original.draftOfferings(),
        original.draftSelections(), Map.of());

    MatchState decoded = MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(valid),
        original.catalog());
    assertEquals(valid.matchId(), decoded.matchId());
    assertEquals(valid.hands(), decoded.hands());
    assertEquals(valid.originalOwners(), decoded.originalOwners());
  }

  @Test
  void findStateAndRehydrateRejectSnapshotRowIdentityMismatch() throws Exception {
    var path = Files.createTempFile("mobmatrix-row-identity", ".sqlite");
    MatchState original = snapshotState("embedded-match-id");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      MatchRepository.MatchSnapshot snapshot = repository.find(original.matchId()).orElseThrow();
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET match_id = ? WHERE match_id = ?")) {
        update.setString(1, "row-match-id");
        update.setString(2, original.matchId().value());
        update.executeUpdate();
      }
      MatchId rowId = new MatchId("row-match-id");
      assertThrows(IllegalArgumentException.class, () -> repository.find(rowId));
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(rowId, original.catalog()));
      MatchRepository.MatchSnapshot tampered = new MatchRepository.MatchSnapshot(rowId,
          snapshot.revision(), snapshot.status(), snapshot.stateBytes(), snapshot.transcriptJson(),
          snapshot.stateDigest());
      assertThrows(IllegalArgumentException.class,
          () -> repository.rehydrate(tampered, original.catalog()));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void findStateAndRehydrateRejectSnapshotRevisionMismatch() throws Exception {
    var path = Files.createTempFile("mobmatrix-row-revision", ".sqlite");
    MatchState original = snapshotState("embedded-revision");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      MatchRepository.MatchSnapshot snapshot = repository.find(original.matchId()).orElseThrow();
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET revision = ? WHERE match_id = ?")) {
        update.setLong(1, original.revision() + 1);
        update.setString(2, original.matchId().value());
        update.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(original.matchId(), original.catalog()));
      MatchRepository.MatchSnapshot tampered = new MatchRepository.MatchSnapshot(
          original.matchId(), original.revision() + 1, snapshot.status(), snapshot.stateBytes(),
          snapshot.transcriptJson(), snapshot.stateDigest());
      assertThrows(IllegalArgumentException.class,
          () -> repository.rehydrate(tampered, original.catalog()));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void findStateRejectsSameIdAlteredPersistedCatalogDefinition() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-identity", ".sqlite");
    MatchState original = snapshotState("catalog-identity");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      List<CardDefinition> alteredDefinitions = new ArrayList<>(original.catalog().all());
      CardDefinition first = alteredDefinitions.getFirst();
      alteredDefinitions.set(0, new CardDefinition(first.id(), first.publicNumber(), "Altered",
          first.stars(), first.type(), first.top(), first.right(), first.bottom(), first.left(),
          first.salePrice()));
      CardCatalog altered = new CardCatalog(alteredDefinitions, original.catalog().snapshotId());

      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(original.matchId(), altered));
    }
    Files.deleteIfExists(path);
  }
  @Test
  void findStateRejectsAbsentPersistedCatalogDefinitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-absent", ".sqlite");
    MatchState original = snapshotState("catalog-absent");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      try (var delete = database.connection().prepareStatement(
          "DELETE FROM card_definitions WHERE snapshot_id = ?")) {
        delete.setString(1, original.catalog().snapshotId());
        delete.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class,
          () -> repository.findState(original.matchId(), original.catalog()));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void directRevisionZeroSavePersistsCompleteCatalogDefinitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-save", ".sqlite");
    MatchState original = snapshotState("catalog-save");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertTrue(repository.save(0L, original).accepted());
      try (var query = database.connection().prepareStatement(
          "SELECT COUNT(*) FROM card_definitions WHERE snapshot_id = ?")) {
        query.setString(1, original.catalog().snapshotId());
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(original.catalog().all().size(), rows.getInt(1));
        }
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void catalogSnapshotCannotBeOverwrittenWithAlteredDefinitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-overwrite", ".sqlite");
    MatchState original = snapshotState("catalog-overwrite");
    SqliteCardRepository.CatalogSnapshot snapshot = new SqliteCardRepository.CatalogSnapshot(
        original.catalog().snapshotId(), "test", "digest", "{}");
    try (Database database = new Database(path)) {
      SqliteCardRepository cards = new SqliteCardRepository(database);
      cards.saveCatalogSnapshot(snapshot, original.catalog().all());
      List<CardDefinition> alteredDefinitions = new ArrayList<>(original.catalog().all());
      CardDefinition first = alteredDefinitions.getFirst();
      alteredDefinitions.set(0, new CardDefinition(first.id(), first.publicNumber(), "Altered",
          first.stars(), first.type(), first.top(), first.right(), first.bottom(), first.left(),
          first.salePrice()));
      assertThrows(IllegalArgumentException.class,
          () -> cards.saveCatalogSnapshot(snapshot, alteredDefinitions));
    }
    Files.deleteIfExists(path);
  }

  @Test
  void catalogSnapshotMetadataCannotBeOverwritten() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-metadata", ".sqlite");
    MatchState original = snapshotState("catalog-metadata");
    SqliteCardRepository.CatalogSnapshot snapshot = new SqliteCardRepository.CatalogSnapshot(
        original.catalog().snapshotId(), "source", "digest", "{\"version\":1}");
    try (Database database = new Database(path)) {
      SqliteCardRepository cards = new SqliteCardRepository(database);
      cards.saveCatalogSnapshot(snapshot, original.catalog().all());
      List<SqliteCardRepository.CatalogSnapshot> altered = List.of(
          new SqliteCardRepository.CatalogSnapshot(snapshot.snapshotId(), "altered-source",
              snapshot.digest(), snapshot.metadataJson()),
          new SqliteCardRepository.CatalogSnapshot(snapshot.snapshotId(), snapshot.source(),
              "altered-digest", snapshot.metadataJson()),
          new SqliteCardRepository.CatalogSnapshot(snapshot.snapshotId(), snapshot.source(),
              snapshot.digest(), "{\"version\":2}"));
      for (SqliteCardRepository.CatalogSnapshot candidate : altered) {
        assertThrows(IllegalArgumentException.class,
            () -> cards.saveCatalogSnapshot(candidate, original.catalog().all()));
        assertEquals(snapshot, cards.findCatalogSnapshot(snapshot.snapshotId()).orElseThrow());
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void matchSaveReusesMetadataFromPreImportedCatalogSnapshot() throws Exception {
    var path = Files.createTempFile("mobmatrix-catalog-preimport", ".sqlite");
    MatchState original = snapshotState("catalog-preimport");
    SqliteCardRepository.CatalogSnapshot imported = new SqliteCardRepository.CatalogSnapshot(
        original.catalog().snapshotId(), "official-source", "official-digest",
        "{\"release\":\"2026.08\"}");
    try (Database database = new Database(path)) {
      SqliteCardRepository cards = new SqliteCardRepository(database);
      cards.saveCatalogSnapshot(imported, original.catalog().all());
      SqliteMatchRepository matches = new SqliteMatchRepository(database);

      assertTrue(matches.save(0L, original).accepted());
      assertEquals(imported, cards.findCatalogSnapshot(imported.snapshotId()).orElseThrow());
    }
    Files.deleteIfExists(path);
  }

  @Test
  void snapshotRejectsNegativeOriginalOwnerCount() {
    MatchState original = snapshotState("negative-owner-count");
    byte[] encoded = MatchStateSnapshotCodec.encode(original);
    putInt(encoded, originalOwnerCountOffset(encoded), -1);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(encoded, original.catalog()));
  }

  @Test
  void snapshotRejectsNegativeNestedHandCount() {
    MatchState original = snapshotState("negative-hand-count");
    byte[] encoded = MatchStateSnapshotCodec.encode(original);
    putInt(encoded, firstHandCountOffset(encoded), -1);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(encoded, original.catalog()));
  }
  @Test
  void snapshotRejectsOversizedSharedDecodeByteBudget() {
    MatchState original = snapshotState("oversized-snapshot");
    byte[] encoded = MatchStateSnapshotCodec.encode(original);
    byte[] oversized = java.util.Arrays.copyOf(encoded, 16 * 1024 * 1024 + 1);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(oversized, original.catalog()));
  }

  @Test
  void nonSwapSuddenDeathCapturedCardRehydratesAfterRestart() {
    MatchState started = snapshotState("sudden-death-captured");
    CardCopy captured = started.hand(PlayerSeat.ONE).getFirst().copy();
    Map<PlayerSeat, List<org.aincraft.mobmatrix.domain.HandCard>> hands = new EnumMap<>(PlayerSeat.class);
    hands.put(PlayerSeat.ONE, started.hand(PlayerSeat.ONE).subList(1, started.hand(PlayerSeat.ONE).size()));
    List<org.aincraft.mobmatrix.domain.HandCard> opposing = new ArrayList<>(started.hand(PlayerSeat.TWO));
    opposing.add(new org.aincraft.mobmatrix.domain.HandCard(captured,
        started.catalog().require(captured.cardId())));
    hands.put(PlayerSeat.TWO, opposing);
    MatchState suddenDeath = MatchState.rehydrate(started.matchId(), started.catalog(),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.SUDDEN_DEATH), started.board(), hands,
        started.currentSeat(), started.revision(), started.result(), started.bankMillis(),
        started.turnStartedAt(), started.graceDeadline(), started.turnDeadline(),
        started.commandIds(), started.seed(), started.transcript(), started.revealedCardsMap(),
        started.originalOwners(), started.consecutiveDraws(), started.draftOfferings(),
        started.draftSelections(), started.chaosChoices());

    MatchState restored = MatchStateSnapshotCodec.decode(
        MatchStateSnapshotCodec.encode(suddenDeath), suddenDeath.catalog());

    assertEquals(suddenDeath.hand(PlayerSeat.TWO), restored.hand(PlayerSeat.TWO));
    assertEquals(PlayerSeat.ONE, restored.originalOwners().get(captured.copyId()));
  }

  @Test
  void plainInitialSnapshotWithEmptyOriginalOwnersRehydrates() {
    MatchState started = snapshotState("plain-empty-owners");
    MatchState plain = MatchState.rehydrate(started.matchId(), started.catalog(), started.rules(),
        started.board(), started.hands(), started.currentSeat(), started.revision(), started.result(),
        started.bankMillis(), started.turnStartedAt(), started.graceDeadline(), started.turnDeadline(),
        started.commandIds(), started.seed(), started.transcript(), started.revealedCardsMap(),
        Map.of(), started.consecutiveDraws(), started.draftOfferings(), started.draftSelections(),
        started.chaosChoices());

    MatchState restored = MatchStateSnapshotCodec.decode(
        MatchStateSnapshotCodec.encode(plain), plain.catalog());

    assertEquals(plain.hands(), restored.hands());
    assertEquals(plain.hand(PlayerSeat.ONE).size() + plain.hand(PlayerSeat.TWO).size(),
        restored.originalOwners().size());
  }

  @Test
  void snapshotRejectsEmptyOwnerInferenceAfterSuddenDeathProgress() {
    MatchState started = snapshotState("sudden-death-empty-board");
    MatchState postSuddenDeath = MatchState.rehydrate(started.matchId(), started.catalog(),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.SUDDEN_DEATH), started.board(),
        started.hands(), started.currentSeat(), 0L, null, started.bankMillis(),
        started.turnStartedAt(), started.graceDeadline(), started.turnDeadline(),
        started.commandIds(), started.seed(), started.transcript(), started.revealedCardsMap(),
        Map.of(), 1, started.draftOfferings(), started.draftSelections(), started.chaosChoices());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(
            MatchStateSnapshotCodec.encode(postSuddenDeath), postSuddenDeath.catalog()));
  }

  @Test
  void snapshotRejectsDuplicateHandAndBoardCopyUuid() {
    MatchState initial = snapshotState("duplicate-snapshot");
    PlayerSeat actor = initial.currentSeat();
    CardCopyId placedCopy = initial.hand(actor).getFirst().copyId();
    MatchState placed = MatchEngine.apply(initial, new MatchCommand.PlaceCard(actor, placedCopy,
        Square.CENTER, initial.revision(), UUID.randomUUID(), Instant.parse("2026-01-01T00:00:01Z")))
        .state();
    CardCopyId handCopy = placed.hand(actor).getFirst().copyId();
    UUID boardUuid = placed.board().copyAt(Square.CENTER).copyId().value();
    byte[] tampered = replaceUuid(MatchStateSnapshotCodec.encode(placed), boardUuid, handCopy.value());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(tampered, placed.catalog()));
  }
  @Test
  void legitimateDraftSnapshotRehydratesRepeatedOfferingReferences() {
    MatchState original = draftSnapshotState("draft-valid");

    MatchState restored = MatchStateSnapshotCodec.decode(
        MatchStateSnapshotCodec.encode(original), original.catalog());

    assertEquals(original.draftOfferings(), restored.draftOfferings());
    assertEquals(original.hand(PlayerSeat.ONE), restored.hand(PlayerSeat.ONE));
    assertEquals(original.draftSelection(PlayerSeat.ONE).deck().copies(),
        restored.draftSelection(PlayerSeat.ONE).deck().copies());
  }

  @Test
  void draftSnapshotRejectsPlayerOwnedOfferingCopy() {
    MatchState original = draftSnapshotState("draft-owner");
    DraftOfferings.SeatOffers offers = original.draftOfferings(PlayerSeat.ONE);
    DraftOfferings.Offering first = offers.offerings().getFirst();
    List<CardCopy> cards = new ArrayList<>(first.cards());
    CardCopy copy = cards.getFirst();
    cards.set(0, new CardCopy(copy.copyId(), copy.cardId(), CopyOwner.player(UUID.randomUUID())));
    List<DraftOfferings.Offering> changed = new ArrayList<>(offers.offerings());
    changed.set(0, new DraftOfferings.Offering(first.offeringId(), cards));
    Map<PlayerSeat, DraftOfferings.SeatOffers> offerings = new EnumMap<>(original.draftOfferings());
    offerings.put(PlayerSeat.ONE, new DraftOfferings.SeatOffers(PlayerSeat.ONE, changed));

    MatchState tampered = withDraftOfferings(original, offerings, original.draftSelections());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void draftSnapshotRejectsOfferingIdsDuplicatedAcrossSeats() {
    MatchState original = draftSnapshotState("draft-offering-id");
    DraftOfferings.SeatOffers one = original.draftOfferings(PlayerSeat.ONE);
    DraftOfferings.SeatOffers two = original.draftOfferings(PlayerSeat.TWO);
    List<DraftOfferings.Offering> changed = new ArrayList<>(two.offerings());
    DraftOfferings.Offering duplicate = changed.getFirst();
    changed.set(0, new DraftOfferings.Offering(one.offerings().getFirst().offeringId(),
        duplicate.cards()));
    Map<PlayerSeat, DraftOfferings.SeatOffers> offerings = new EnumMap<>(original.draftOfferings());
    offerings.put(PlayerSeat.TWO, new DraftOfferings.SeatOffers(PlayerSeat.TWO, changed));

    MatchState tampered = withDraftOfferings(original, offerings, original.draftSelections());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void draftSnapshotRejectsOfferingCopyDuplicatedByAuthoritativeHand() {
    MatchState original = draftSnapshotState("draft-active-duplicate");
    DraftOfferings.SeatOffers offers = original.draftOfferings(PlayerSeat.ONE);
    DraftOfferings.Offering first = offers.offerings().getFirst();
    List<CardCopy> cards = new ArrayList<>(first.cards());
    CardCopy handCopy = original.hand(PlayerSeat.ONE).getFirst().copy();
    CardId differentCard = handCopy.cardId().equals(new CardId(1)) ? new CardId(2) : new CardId(1);
    cards.set(0, new CardCopy(handCopy.copyId(), differentCard, CopyOwner.house()));
    List<DraftOfferings.Offering> changed = new ArrayList<>(offers.offerings());
    changed.set(0, new DraftOfferings.Offering(first.offeringId(), cards));
    Map<PlayerSeat, DraftOfferings.SeatOffers> offerings = new EnumMap<>(original.draftOfferings());
    offerings.put(PlayerSeat.ONE, new DraftOfferings.SeatOffers(PlayerSeat.ONE, changed));

    MatchState tampered = withDraftOfferings(original, offerings, original.draftSelections());

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void draftSnapshotRejectsSelectionReferenceOutsideOfferings() {
    MatchState original = draftSnapshotState("draft-reference");
    DraftOfferings.Selection selection = original.draftSelection(PlayerSeat.ONE);
    CardCopy foreign = new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(1),
        CopyOwner.player(UUID.randomUUID()));
    List<CardCopy> selected = new ArrayList<>(selection.selectedCards());
    selected.set(0, foreign);
    List<CardCopy> deck = new ArrayList<>(selection.deck().copies());
    deck.set(0, foreign);
    Map<CardCopyId, UUID> provenance = new HashMap<>(selection.provenance());
    provenance.remove(selection.selectedCards().getFirst().copyId());
    provenance.put(foreign.copyId(), selection.provenance().values().iterator().next());
    DraftOfferings.Selection changed = DraftOfferings.Selection.restore(PlayerSeat.ONE,
        selection.offeringChoices(), selected, new Deck(deck), provenance);
    Map<PlayerSeat, DraftOfferings.Selection> selections = new EnumMap<>(original.draftSelections());
    selections.put(PlayerSeat.ONE, changed);

    MatchState tampered = withDraftOfferings(original, original.draftOfferings(), selections);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }

  @Test
  void draftSnapshotRejectsMalformedProvenanceReference() {
    MatchState original = draftSnapshotState("draft-provenance");
    DraftOfferings.Selection selection = original.draftSelection(PlayerSeat.ONE);
    Map<CardCopyId, UUID> provenance = new HashMap<>(selection.provenance());
    provenance.put(selection.selectedCards().getFirst().copyId(), UUID.randomUUID());
    DraftOfferings.Selection changed = DraftOfferings.Selection.restore(PlayerSeat.ONE,
        selection.offeringChoices(), selection.selectedCards(), selection.deck(), provenance);
    Map<PlayerSeat, DraftOfferings.Selection> selections = new EnumMap<>(original.draftSelections());
    selections.put(PlayerSeat.ONE, changed);

    MatchState tampered = withDraftOfferings(original, original.draftOfferings(), selections);

    assertThrows(IllegalArgumentException.class,
        () -> MatchStateSnapshotCodec.decode(MatchStateSnapshotCodec.encode(tampered),
            original.catalog()));
  }



  @Test
  void saveRejectsRevisionJumpsForNewAndExistingRows() throws Exception {
    var path = Files.createTempFile("mobmatrix-cas", ".sqlite");
    List<CardDefinition> definitions = List.of(
        new CardDefinition(new CardId(1), "No. 1", "One", 1, CardType.NORMAL, 1, 1, 1, 1, 0),
        new CardDefinition(new CardId(2), "No. 2", "Two", 1, CardType.NORMAL, 2, 2, 2, 2, 0),
        new CardDefinition(new CardId(3), "No. 3", "Three", 1, CardType.NORMAL, 3, 3, 3, 3, 0),
        new CardDefinition(new CardId(4), "No. 4", "Four", 1, CardType.NORMAL, 4, 4, 4, 4, 0),
        new CardDefinition(new CardId(5), "No. 5", "Five", 1, CardType.NORMAL, 5, 5, 5, 5, 0));
    CardCatalog catalog = new CardCatalog(definitions, "cas-v1");
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index)));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index)));
    }
    var state = MatchEngine.start(new MatchEngine.StartMatch(new MatchId("cas-match"),
        new Deck(one), new Deck(two), catalog, new MatchRules(), 3L,
        Instant.parse("2026-01-01T00:00:00Z"))).state();
    var jumped = MatchStateTestSupport.withRevision(state, 2L);
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database);
      assertEquals(MatchRepository.Failure.INVALID_STATE, repository.save(0L, jumped).failure());
      assertTrue(repository.save(0L, state).accepted());
      assertEquals(MatchRepository.Failure.INVALID_STATE,
          repository.save(0L, state).failure());
    }
    Files.deleteIfExists(path);
  }
  private static MatchState chaosSnapshotState(String id) {
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, index, index, index, index, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    return MatchEngine.start(new MatchEngine.StartMatch(new MatchId(id), new Deck(one), new Deck(two),
        new CardCatalog(definitions, id + "-catalog"), new MatchRules(
            org.aincraft.mobmatrix.domain.Rule.CHAOS), 7L,
        Instant.parse("2026-01-01T00:00:00Z"))).state();
  }

  private static MatchState draftSnapshotState(String id) {
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());

    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, index, index, index, index, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    return MatchEngine.start(new MatchEngine.StartMatch(new MatchId(id), new Deck(one), new Deck(two),
        new CardCatalog(definitions, id + "-catalog"), new MatchRules(
            org.aincraft.mobmatrix.domain.Rule.DRAFT), 7L,
        Instant.parse("2026-01-01T00:00:00Z"), List.of(), List.of(),
        List.of(0, 1, 2), List.of(2, 3, 4))).state();
  }

  private static MatchState withDraftOfferings(MatchState state,
      Map<PlayerSeat, DraftOfferings.SeatOffers> offerings,
      Map<PlayerSeat, DraftOfferings.Selection> selections) {
    return MatchState.rehydrate(state.matchId(), state.catalog(), state.rules(), state.board(),
        state.hands(), state.currentSeat(), state.revision(), state.result(), state.bankMillis(),
        state.turnStartedAt(), state.graceDeadline(), state.turnDeadline(), state.commandIds(),
        state.seed(), state.transcript(), state.revealedCardsMap(), state.originalOwners(),
        state.consecutiveDraws(), offerings, selections, state.chaosChoices());
  }

  private static MatchState snapshotState(String id) {
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, index, index, index, index, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    return MatchEngine.start(new MatchEngine.StartMatch(new MatchId(id), new Deck(one), new Deck(two),
        new CardCatalog(definitions, id + "-catalog"), new MatchRules(), 7L,
        Instant.parse("2026-01-01T00:00:00Z"))).state();
  }

  private static int firstHandCountOffset(byte[] encoded) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
      readSnapshotPrelude(in);
      return encoded.length - in.available();
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static int originalOwnerCountOffset(byte[] encoded) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
      readSnapshotPrelude(in);
      for (int seat = 0; seat < 2; seat++) {
        int count = in.readInt();
        for (int index = 0; index < count; index++) skipCopy(in);
      }
      for (int index = 0; index < 9; index++) {
        if (in.readBoolean()) {
          skipCopy(in);
          in.skipNBytes(4);
          in.readUTF();
          for (int side = 0; side < 4; side++) {
            if (in.readBoolean()) in.skipNBytes(4);
          }
        }
      }
      for (int seat = 0; seat < 2; seat++) skipUuidSet(in);
      return encoded.length - in.available();
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void readSnapshotPrelude(DataInputStream in) throws IOException {
    in.readInt();
    in.readInt();
    in.readUTF();
    in.readLong();
    in.readUTF();
    int rules = in.readInt();
    for (int index = 0; index < rules; index++) in.readUTF();
    in.readUTF();
    in.skipNBytes(36);
    in.skipNBytes(16);
    if (in.readBoolean()) {
      in.skipNBytes(8);
      in.skipNBytes(8);
      in.readUTF();
    }
    in.skipNBytes(8);
    skipUuidSet(in);
    in.skipNBytes(8);
    in.readBoolean();
    in.skipNBytes(4);
    int decisions = in.readInt();
    for (int index = 0; index < decisions; index++) {
      in.readUTF();
      in.skipNBytes(8);
    }
  }

  private static void skipCopy(DataInputStream in) throws IOException {
    in.skipNBytes(16);
    in.skipNBytes(4);
    in.readUTF();
    if (in.readBoolean()) in.skipNBytes(16);
  }

  private static void skipUuidSet(DataInputStream in) throws IOException {
    int count = in.readInt();
    in.skipNBytes(16L * count);
  }
  private static String legacySha256(byte[] bytes) {
    try {
      return "sha256-v1:" + java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void putInt(byte[] encoded, int offset, int value) {
    ByteBuffer.wrap(encoded, offset, Integer.BYTES).putInt(value);
  }
  private static byte[] replaceUuid(byte[] source, UUID from, UUID to) {
    byte[] result = source.clone();
    byte[] fromBytes = ByteBuffer.allocate(16).putLong(from.getMostSignificantBits())
        .putLong(from.getLeastSignificantBits()).array();
    byte[] toBytes = ByteBuffer.allocate(16).putLong(to.getMostSignificantBits())
        .putLong(to.getLeastSignificantBits()).array();
    for (int offset = 0; offset <= result.length - fromBytes.length; offset++) {
      boolean matches = true;
      for (int index = 0; index < fromBytes.length; index++) {
        if (result[offset + index] != fromBytes[index]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        System.arraycopy(toBytes, 0, result, offset, toBytes.length);
        return result;
      }
    }
    throw new AssertionError("UUID not present in encoded snapshot");
  }


  private static final class MatchStateTestSupport {
    private static org.aincraft.mobmatrix.domain.MatchState withRevision(
        org.aincraft.mobmatrix.domain.MatchState state, long revision) {
      return org.aincraft.mobmatrix.domain.MatchState.rehydrate(state.matchId(), state.catalog(),
          state.rules(), state.board(), state.hands(), state.currentSeat(), revision, state.result(),
          state.bankMillis(), state.turnStartedAt(), state.graceDeadline(), state.turnDeadline(),
          state.commandIds(), state.seed(), state.transcript(), state.revealedCardsMap(),
          state.originalOwners(), state.consecutiveDraws(), state.draftOfferings(),
          state.draftSelections(), state.chaosChoices());
    }
  }
  @Test
  void findAndActiveMatchesRejectTamperedStatusBeforeExposure() throws Exception {
    var path = Files.createTempFile("mobmatrix-match-status", ".sqlite");
    MatchState state = snapshotState("tampered-status");
    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database, MATCH_SECRET);
      assertTrue(repository.save(0L, state).accepted());
      try (var update = database.connection().prepareStatement(
          "UPDATE matches SET status = 'FINISHED' WHERE match_id = ?")) {
        update.setString(1, state.matchId().value());
        update.executeUpdate();
      }
      assertThrows(IllegalArgumentException.class, () -> repository.find(state.matchId()));
      assertThrows(IllegalArgumentException.class, repository::activeMatches);
    }
    Files.deleteIfExists(path);
  }
  @Test
  void canonicalCopyUuidReuseBlocksCaseVariantActiveReservation() throws Exception {
    var path = Files.createTempFile("mobmatrix-canonical-copy-guard", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {9, 1});
    String upperCopy = copy.value().toString().toUpperCase(java.util.Locale.ROOT);
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var update = database.connection().prepareStatement(
          "UPDATE reservation_copies SET copy_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, upperCopy);
        update.setString(2, firstOperation.toString());
        update.executeUpdate();
      }

      assertThrows(IllegalStateException.class,
          () -> repository.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));

      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, owner.playerId().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, copy.value().toString());
        assertThrows(java.sql.SQLException.class, insert::executeUpdate);
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void pendingRecoveryBlocksTerminalReuseInJavaAndTrigger() throws Exception {
    var path = Files.createTempFile("mobmatrix-pending-recovery-guard", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {8, 4});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      repository.transition(firstOperation, OperationStatus.ESCROWED);
      repository.transition(firstOperation, OperationStatus.RETURN_PENDING);
      repository.addRecovery(firstOperation, owner, snapshot);
      repository.markRestored(firstOperation, copy);
      repository.transition(firstOperation, OperationStatus.RETURNED);
      try (var update = database.connection().prepareStatement(
          "UPDATE recovery_rows SET status = 'PENDING' WHERE operation_id = ?")) {
        update.setString(1, firstOperation.toString());
        update.executeUpdate();
      }

      assertThrows(IllegalStateException.class,
          () -> repository.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));

      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, owner.playerId().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, copy.value().toString());
        assertThrows(java.sql.SQLException.class, insert::executeUpdate);
      }
    }
    Files.deleteIfExists(path);
  }

  @Test
  void malformedRecoveryOwnerBlocksTerminalReuseInJavaAndTrigger() throws Exception {
    var path = Files.createTempFile("mobmatrix-malformed-recovery-owner", ".sqlite");
    UUID firstOperation = UUID.randomUUID();
    UUID secondOperation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot snapshot = new SerializedItemSnapshot(copy, new byte[] {2, 9});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1), owner));
      repository.prepare(firstOperation, owner, List.of(copy), List.of(snapshot));
      repository.transition(firstOperation, OperationStatus.ESCROWED);
      repository.transition(firstOperation, OperationStatus.RETURN_PENDING);
      repository.addRecovery(firstOperation, owner, snapshot);
      repository.markRestored(firstOperation, copy);
      repository.transition(firstOperation, OperationStatus.RETURNED);
      try (var update = database.connection().prepareStatement(
          "UPDATE recovery_rows SET owner_uuid = ? WHERE operation_id = ?")) {
        update.setString(1, UUID.randomUUID().toString());
        update.setString(2, firstOperation.toString());
        update.executeUpdate();
      }

      assertThrows(SqliteCardRepository.CustodyIntegrityException.class,
          () -> repository.prepare(secondOperation, owner, List.of(copy), List.of(snapshot)));

      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, owner.playerId().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, secondOperation.toString());
        insert.setString(2, copy.value().toString());
        assertThrows(java.sql.SQLException.class, insert::executeUpdate);
      }
    }
    Files.deleteIfExists(path);
  }
  @Test
  void identityEnumerationProtectsMalformedOperationCopiesButReleasesProvenTerminalCopies()
      throws Exception {
    var path = Files.createTempFile("mobmatrix-identity-enumeration", ".sqlite");
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    CardCopyId activeCopy = new CardCopyId(UUID.randomUUID());
    CardCopyId terminalCopy = new CardCopyId(UUID.randomUUID());
    SerializedItemSnapshot terminalSnapshot = new SerializedItemSnapshot(terminalCopy, new byte[] {5, 5});
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(activeCopy, new CardId(1), owner));
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(terminalCopy, new CardId(1), owner));
      try (var pragma = database.connection().createStatement()) {
        pragma.execute("PRAGMA foreign_keys = OFF");
      }
      try (var insert = database.connection().prepareStatement("""
          INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status,
            created_at, updated_at)
          VALUES (?, 'PLAYER', ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """)) {
        insert.setString(1, "malformed-operation");
        insert.setString(2, owner.playerId().toString());
        insert.setString(3, "PREPARED");
        insert.executeUpdate();
        insert.setString(1, "terminal-operation");
        insert.setString(3, "RETURNED");
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, "malformed-operation");
        insert.setString(2, activeCopy.value().toString());
        insert.executeUpdate();
        insert.setString(1, "terminal-operation");
        insert.setString(2, terminalCopy.value().toString());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO escrow_items(operation_id, copy_uuid, item_bytes) VALUES (?, ?, ?)")) {
        insert.setString(1, "terminal-operation");
        insert.setString(2, terminalCopy.value().toString());
        insert.setBytes(3, terminalSnapshot.bytes());
        insert.executeUpdate();
      }
      try (var insert = database.connection().prepareStatement(
          "INSERT INTO reservation_restored(operation_id, copy_uuid) VALUES (?, ?)")) {
        insert.setString(1, "terminal-operation");
        insert.setString(2, terminalCopy.value().toString());
        insert.executeUpdate();
      }
      assertEquals(Set.of(activeCopy), repository.reservedCopyIds());
      UUID reusableOperation = UUID.randomUUID();
      repository.prepare(reusableOperation, owner, List.of(terminalCopy), List.of(terminalSnapshot));
      assertEquals(Set.of(activeCopy, terminalCopy), repository.reservedCopyIds());
    }
    Files.deleteIfExists(path);
  }
}
