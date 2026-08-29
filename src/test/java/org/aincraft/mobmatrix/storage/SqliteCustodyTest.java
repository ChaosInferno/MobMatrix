package org.aincraft.mobmatrix.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
import org.aincraft.mobmatrix.domain.DraftOfferings;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Square;
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
}
