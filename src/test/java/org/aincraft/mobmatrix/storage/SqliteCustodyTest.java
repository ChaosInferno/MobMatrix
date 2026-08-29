package org.aincraft.mobmatrix.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.junit.jupiter.api.Test;

final class SqliteCustodyTest {
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
        statement.execute("DELETE FROM schema_migrations WHERE version = 3");
      }
      SchemaMigrator.migrate(database.connection());
      assertEquals(3, SchemaMigrator.currentVersion(database.connection()));
      assertNull(repository.findReservation(existingOperation).orElseThrow().matchId());
      repository.prepare(newOperation, owner, List.of(newCopy),
          List.of(new SerializedItemSnapshot(newCopy, new byte[] {2})), "migrated-match");
      assertEquals("migrated-match", repository.findReservation(newOperation).orElseThrow().matchId());
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
}
