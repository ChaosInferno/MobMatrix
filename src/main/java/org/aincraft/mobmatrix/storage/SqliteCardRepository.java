package org.aincraft.mobmatrix.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyJournal;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;

/** JDBC repository for card ledger, reservations, exact escrow bytes, and recovery rows. */
public final class SqliteCardRepository implements CardCustodyJournal {
  private final Connection connection;

  public SqliteCardRepository(Database database) {
    this(Objects.requireNonNull(database, "database is required").connection());
  }

  public SqliteCardRepository(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection is required");
  }
  @Override
  public Optional<CardCustodyJournal.Entry> find(UUID operationId) {
    return findReservation(operationId).map(reservation -> new CardCustodyJournal.Entry(
        reservation.operationId(), reservation.owner(), reservation.copyIds(), reservation.snapshots(),
        reservation.status(), Set.copyOf(reservation.restoredCopyIds()), reservation.matchId()));
  }

  @Override
  public List<CardCustodyJournal.Entry> openEntries() {
    return findOpenReservations().stream().map(reservation -> new CardCustodyJournal.Entry(
        reservation.operationId(), reservation.owner(), reservation.copyIds(), reservation.snapshots(),
        reservation.status(), Set.copyOf(reservation.restoredCopyIds()), reservation.matchId())).toList();
  }

  public List<UUID> openOperationIds() {
    return openEntries().stream().map(CardCustodyJournal.Entry::operationId).toList();
  }

  public void saveCopy(StoredCardCopy copy) {
    Objects.requireNonNull(copy, "copy is required");
    inTransaction(() -> {
      try (var existing = connection.prepareStatement(
          "SELECT catalog_id, owner_kind, owner_uuid FROM card_copies WHERE copy_uuid = ?")) {
        existing.setString(1, copy.copyId().value().toString());
        try (var rows = existing.executeQuery()) {
          if (rows.next()) {
            CopyOwner owner = readOwner(rows.getString(2), rows.getString(3));
            if (rows.getInt(1) != copy.catalogId().value() || !owner.equals(copy.owner())) {
              throw new IllegalStateException("copy UUID already belongs to another card or owner");
            }
            return;
          }
        }
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO card_copies(copy_uuid, catalog_id, owner_kind, owner_uuid, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
        insert.setString(1, copy.copyId().value().toString());
        insert.setInt(2, copy.catalogId().value());
        writeOwner(insert, 3, 4, copy.owner());
        insert.executeUpdate();
      }
    });
  }
  public void saveCatalogSnapshot(CatalogSnapshot snapshot,
      java.util.Collection<CardDefinition> definitions) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(definitions, "definitions are required");
    inTransaction(() -> persistCatalogSnapshot(connection, snapshot, definitions));
  }

  /**
   * Persists a catalog snapshot only when its complete definition set is new or identical.
   * The caller owns the transaction so match revision-0 state and catalog rows can commit
   * atomically.
   */
  static void persistCatalogSnapshot(Connection connection, CatalogSnapshot snapshot,
      java.util.Collection<CardDefinition> definitions) throws SQLException {
    List<CardDefinition> expected = Objects.requireNonNull(definitions, "definitions are required")
        .stream().sorted(java.util.Comparator.comparingInt(definition -> definition.id().value())).toList();
    Objects.requireNonNull(snapshot, "snapshot is required");
    boolean exists;
    String persistedSource = null;
    String persistedDigest = null;
    String persistedMetadataJson = null;
    try (var query = connection.prepareStatement(
        "SELECT source, digest, metadata_json FROM catalog_snapshots WHERE snapshot_id = ?")) {
      query.setString(1, snapshot.snapshotId());
      try (var rows = query.executeQuery()) {
        exists = rows.next();
        if (exists) {
          persistedSource = rows.getString(1);
          persistedDigest = rows.getString(2);
          persistedMetadataJson = rows.getString(3);
        }
      }
    }
    if (exists) {
      if (!snapshot.source().equals(persistedSource)
          || !snapshot.digest().equals(persistedDigest)
          || !snapshot.metadataJson().equals(persistedMetadataJson)) {
        throw new IllegalArgumentException("catalog snapshot metadata already exists with different values");
      }
      List<CardDefinition> persisted = readDefinitions(connection, snapshot.snapshotId());
      if (!persisted.equals(expected)) {
        throw new IllegalArgumentException("catalog definitions already exist with different values");
      }
      return;
    }
    try (var insert = connection.prepareStatement("""
        INSERT INTO catalog_snapshots(snapshot_id, source, digest, created_at, metadata_json)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
        """)) {
      insert.setString(1, snapshot.snapshotId());
      insert.setString(2, snapshot.source());
      insert.setString(3, snapshot.digest());
      insert.setString(4, snapshot.metadataJson());
      insert.executeUpdate();
    }
    try (var cardInsert = connection.prepareStatement("""
        INSERT INTO card_definitions(snapshot_id, catalog_id, public_number, name, stars, type,
          top_value, right_value, bottom_value, left_value, sale_price)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      for (CardDefinition definition : expected) {
        cardInsert.setString(1, snapshot.snapshotId());
        cardInsert.setInt(2, definition.id().value());
        cardInsert.setString(3, definition.publicNumber());
        cardInsert.setString(4, definition.name());
        cardInsert.setInt(5, definition.stars());
        cardInsert.setString(6, definition.type().name());
        cardInsert.setInt(7, definition.top());
        cardInsert.setInt(8, definition.right());
        cardInsert.setInt(9, definition.bottom());
        cardInsert.setInt(10, definition.left());
        cardInsert.setInt(11, definition.salePrice());
        cardInsert.addBatch();
      }
      cardInsert.executeBatch();
    }
  }
  static void persistCatalogSnapshotForMatch(Connection connection, String snapshotId,
      java.util.Collection<CardDefinition> definitions) throws SQLException {
    Objects.requireNonNull(snapshotId, "snapshot id is required");
    List<CardDefinition> expected = Objects.requireNonNull(definitions, "definitions are required")
        .stream().sorted(java.util.Comparator.comparingInt(definition -> definition.id().value())).toList();
    boolean exists;
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM catalog_snapshots WHERE snapshot_id = ?")) {
      query.setString(1, snapshotId);
      try (var rows = query.executeQuery()) {
        exists = rows.next();
      }
    }
    if (exists) {
      if (!readDefinitions(connection, snapshotId).equals(expected)) {
        throw new IllegalArgumentException("catalog definitions already exist with different values");
      }
      return;
    }
    persistCatalogSnapshot(connection,
        new CatalogSnapshot(snapshotId, "match-state", "catalog-" + snapshotId, "{}"), expected);
  }

  private static List<CardDefinition> readDefinitions(Connection connection, String snapshotId)
      throws SQLException {
    List<CardDefinition> definitions = new ArrayList<>();
    try (var query = connection.prepareStatement("""
        SELECT catalog_id, public_number, name, stars, type, top_value, right_value,
          bottom_value, left_value, sale_price
        FROM card_definitions WHERE snapshot_id = ? ORDER BY catalog_id
        """)) {
      query.setString(1, snapshotId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          definitions.add(new CardDefinition(new CardId(rows.getInt(1)), rows.getString(2),
              rows.getString(3), rows.getInt(4), CardType.valueOf(rows.getString(5)),
              rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getInt(10)));
        }
      }
    }
    return List.copyOf(definitions);
  }


  public Optional<CatalogSnapshot> findCatalogSnapshot(String snapshotId) {
    try (var query = connection.prepareStatement(
        "SELECT source, digest, metadata_json FROM catalog_snapshots WHERE snapshot_id = ?")) {
      query.setString(1, Objects.requireNonNull(snapshotId));
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new CatalogSnapshot(snapshotId, rows.getString(1), rows.getString(2), rows.getString(3)));
      }
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }


  public Optional<StoredCardCopy> findCopy(CardCopyId copyId) {
    Objects.requireNonNull(copyId, "copy id is required");
    try (var query = connection.prepareStatement(
        "SELECT catalog_id, owner_kind, owner_uuid FROM card_copies WHERE copy_uuid = ?")) {
      query.setString(1, copyId.value().toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new StoredCardCopy(copyId, new CardId(rows.getInt(1)),
            readOwner(rows.getString(2), rows.getString(3))));
      }
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  @Override
  public void prepare(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots) {
    prepare(operationId, owner, copyIds, snapshots, null);
  }

  @Override
  public void prepare(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots, String matchId) {
    Objects.requireNonNull(operationId, "operation id is required");
    Objects.requireNonNull(owner, "owner is required");
    List<CardCopyId> copies = copyList(copyIds);
    List<SerializedItemSnapshot> exactSnapshots = List.copyOf(Objects.requireNonNull(snapshots));
    if (exactSnapshots.size() != copies.size()) {
      throw new IllegalArgumentException("every reserved copy requires an exact item snapshot");
    }
    Set<CardCopyId> snapshotIds = new HashSet<>();
    for (SerializedItemSnapshot snapshot : exactSnapshots) {
      if (!snapshotIds.add(snapshot.copyId()) || !copies.contains(snapshot.copyId())) {
        throw new IllegalArgumentException("snapshot copy does not match reservation");
      }
    }
    if (matchId != null && matchId.isBlank()) throw new IllegalArgumentException("match id cannot be blank");
    inTransaction(() -> {
      Reservation existing = findReservationInternal(operationId);
      if (existing != null) {
        if (!existing.owner().equals(owner) || !existing.copyIds().equals(copies)
            || !Objects.equals(existing.matchId(), matchId)) {
          throw new IllegalStateException("operation does not match existing reservation");
        }
        return;
      }
      for (CardCopyId copy : copies) {
        StoredCardCopy ledger = findCopyInternal(copy);
        if (ledger == null) throw new IllegalStateException("unknown card copy: " + copy);
        if (!ledger.owner().equals(owner)) throw new IllegalStateException("copy owner mismatch");
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO reservations(operation_id, owner_kind, owner_uuid, match_id, status, created_at, updated_at) "
              + "VALUES (?, ?, ?, ?, 'PREPARED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
        insert.setString(1, operationId.toString());
        writeOwner(insert, 2, 3, owner);
        insert.setString(4, matchId);
        insert.executeUpdate();
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO reservation_copies(operation_id, copy_uuid) VALUES (?, ?)")) {
        for (CardCopyId copy : copies) {
          insert.setString(1, operationId.toString());
          insert.setString(2, copy.value().toString());
          insert.addBatch();
        }
        insert.executeBatch();
      }
      saveSnapshotsInternal(operationId, exactSnapshots);
    });
  }

  public void saveSnapshots(UUID operationId, List<SerializedItemSnapshot> snapshots) {
    Objects.requireNonNull(operationId, "operation id is required");
    inTransaction(() -> saveSnapshotsInternal(operationId, List.copyOf(Objects.requireNonNull(snapshots))));
  }
  @Override
  public void transition(UUID operationId, OperationStatus target) {
    Objects.requireNonNull(operationId, "operation id is required");
    Objects.requireNonNull(target, "target status is required");
    inTransaction(() -> {
      Reservation reservation = findReservationInternal(operationId);
      if (reservation == null) throw new IllegalStateException("unknown operation: " + operationId);
      if (reservation.status() == OperationStatus.ABORTED
          && !reservation.restoredCopyIds().containsAll(reservation.copyIds())) {
        throw new IllegalStateException("cannot abort custody with unresolved copies");
      }
      if (reservation.status() == OperationStatus.ABORTED
          && !hasExactSnapshots(reservation)) {
        throw new IllegalStateException("aborted custody is missing exact snapshots");
      }
      if (reservation.status() == OperationStatus.RETURNED
          && !reservation.restoredCopyIds().containsAll(reservation.copyIds())) {
        throw new IllegalStateException("cannot return custody with unresolved copies");
      }
      if (reservation.status() == target) return;
      if (target == OperationStatus.RETURNED
          && !reservation.restoredCopyIds().containsAll(reservation.copyIds())) {
        throw new IllegalStateException("cannot return custody with unresolved copies");
      }
      if (target == OperationStatus.ABORTED
          && !reservation.restoredCopyIds().containsAll(reservation.copyIds())) {
        throw new IllegalStateException("cannot abort custody with unresolved copies");
      }
      if (!allowed(reservation.status(), target)) {
        throw new IllegalStateException("invalid custody transition " + reservation.status() + " -> " + target);
      }
      try (var update = connection.prepareStatement(
          "UPDATE reservations SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE operation_id = ?")) {
        update.setString(1, target.name());
        update.setString(2, operationId.toString());
        update.executeUpdate();
      }
    });
  }

  public Optional<Reservation> findReservation(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    try {
      return Optional.ofNullable(findReservationInternal(operationId));
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  public List<Reservation> findOpenReservations() {
    try (var query = connection.prepareStatement(
        "SELECT operation_id FROM reservations WHERE status IN ('PREPARED', 'ESCROWED', 'RETURN_PENDING') ORDER BY created_at")) {
      List<Reservation> result = new ArrayList<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) result.add(findReservationInternal(UUID.fromString(rows.getString(1))));
      }
      return List.copyOf(result);
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }
  @Override
  public void addRecovery(UUID operationId, CopyOwner owner, SerializedItemSnapshot snapshot) {
    Objects.requireNonNull(operationId, "operation id is required");
    Objects.requireNonNull(owner, "owner is required");
    Objects.requireNonNull(snapshot, "snapshot is required");
    inTransaction(() -> {
      Reservation reservation = findReservationInternal(operationId);
      if (reservation == null || !reservation.owner().equals(owner)
          || !reservation.copyIds().contains(snapshot.copyId())) {
        throw new IllegalStateException("recovery row does not match reservation");
      }
      SerializedItemSnapshot expected = reservation.snapshots().stream()
          .filter(value -> value.copyId().equals(snapshot.copyId())).findFirst().orElse(null);
      if (expected == null || !java.util.Arrays.equals(expected.bytes(), snapshot.bytes())) {
        throw new IllegalStateException("recovery snapshot does not match escrow snapshot");
      }
      try (var restored = connection.prepareStatement(
          "SELECT 1 FROM reservation_restored WHERE operation_id = ? AND copy_uuid = ?")) {
        restored.setString(1, operationId.toString());
        restored.setString(2, snapshot.copyId().value().toString());
        try (var rows = restored.executeQuery()) {
          if (rows.next()) return;
        }
      }
      try (var insert = connection.prepareStatement("""
          INSERT INTO recovery_rows(operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status)
          VALUES (?, ?, ?, ?, ?, 'PENDING')
          ON CONFLICT(operation_id, copy_uuid) DO UPDATE SET item_bytes = excluded.item_bytes,
            status = CASE WHEN recovery_rows.status = 'RESTORED' THEN 'RESTORED' ELSE 'PENDING' END
          """)) {
        insert.setString(1, operationId.toString());
        insert.setString(2, snapshot.copyId().value().toString());
        writeOwner(insert, 3, 4, owner);
        insert.setBytes(5, snapshot.bytes());
        insert.executeUpdate();
      }
    });
  }

  public List<RecoveryRow> pendingRecovery() {
    try (var query = connection.prepareStatement("""
        SELECT operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes
        FROM recovery_rows WHERE status = 'PENDING' ORDER BY recovery_id
        """)) {
      List<RecoveryRow> result = new ArrayList<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) result.add(new RecoveryRow(UUID.fromString(rows.getString(1)),
            new CardCopyId(UUID.fromString(rows.getString(2))), readOwner(rows.getString(3), rows.getString(4)),
            new SerializedItemSnapshot(new CardCopyId(UUID.fromString(rows.getString(2))), rows.getBytes(5))));
      }
      return List.copyOf(result);
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  @Override
  public void markRestored(UUID operationId, CardCopyId copyId) {
    Objects.requireNonNull(operationId, "operation id is required");
    Objects.requireNonNull(copyId, "copy id is required");
    inTransaction(() -> {
      Reservation reservation = findReservationInternal(operationId);
      if (reservation == null || !reservation.copyIds().contains(copyId)) {
        throw new IllegalStateException("copy is not reserved by operation");
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO reservation_restored(operation_id, copy_uuid) VALUES (?, ?) "
              + "ON CONFLICT(operation_id, copy_uuid) DO NOTHING")) {
        insert.setString(1, operationId.toString());
        insert.setString(2, copyId.value().toString());
        insert.executeUpdate();
      }
      try (var update = connection.prepareStatement(
          "UPDATE recovery_rows SET status = 'RESTORED' WHERE operation_id = ? AND copy_uuid = ?")) {
        update.setString(1, operationId.toString());
        update.setString(2, copyId.value().toString());
        update.executeUpdate();
      }
    });
  }
  private Reservation findReservationInternal(UUID operationId) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT owner_kind, owner_uuid, match_id, status FROM reservations WHERE operation_id = ?")) {
      query.setString(1, operationId.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return null;
        CopyOwner owner = readOwner(rows.getString(1), rows.getString(2));
        String matchId = rows.getString(3);
        OperationStatus status = OperationStatus.valueOf(rows.getString(4));
        List<CardCopyId> copies = new ArrayList<>();
        try (var copyQuery = connection.prepareStatement(
            "SELECT copy_uuid FROM reservation_copies WHERE operation_id = ? ORDER BY rowid")) {
          copyQuery.setString(1, operationId.toString());
          try (var copyRows = copyQuery.executeQuery()) {
            while (copyRows.next()) copies.add(new CardCopyId(UUID.fromString(copyRows.getString(1))));
          }
        }
        List<SerializedItemSnapshot> snapshots = new ArrayList<>();
        try (var snapshotQuery = connection.prepareStatement(
            "SELECT copy_uuid, item_bytes FROM escrow_items WHERE operation_id = ? ORDER BY rowid")) {
          snapshotQuery.setString(1, operationId.toString());
          try (var snapshotRows = snapshotQuery.executeQuery()) {
            while (snapshotRows.next()) {
              CardCopyId copy = new CardCopyId(UUID.fromString(snapshotRows.getString(1)));
              snapshots.add(new SerializedItemSnapshot(copy, snapshotRows.getBytes(2)));
            }
          }
        }
        List<CardCopyId> restored = new ArrayList<>();
        try (var restoredQuery = connection.prepareStatement(
            "SELECT copy_uuid FROM reservation_restored WHERE operation_id = ? ORDER BY rowid")) {
          restoredQuery.setString(1, operationId.toString());
          try (var restoredRows = restoredQuery.executeQuery()) {
            while (restoredRows.next()) restored.add(new CardCopyId(UUID.fromString(restoredRows.getString(1))));
          }
        }
        if (status == OperationStatus.RETURNED
            && !new HashSet<>(restored).containsAll(copies)) {
          throw new IllegalStateException("cannot hydrate returned custody with unresolved copies");
        }
        if (status == OperationStatus.ABORTED
            && !new HashSet<>(restored).containsAll(copies)) {
          throw new IllegalStateException("cannot hydrate aborted custody with unresolved copies");
        }
        if (status == OperationStatus.ABORTED
            && !hasExactSnapshots(copies, snapshots)) {
          throw new IllegalStateException("cannot hydrate aborted custody without exact snapshots");
        }
        return new Reservation(operationId, owner, copies, snapshots, status, restored, matchId);
      }
    }
  }

  private StoredCardCopy findCopyInternal(CardCopyId copyId) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT catalog_id, owner_kind, owner_uuid FROM card_copies WHERE copy_uuid = ?")) {
      query.setString(1, copyId.value().toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return null;
        return new StoredCardCopy(copyId, new CardId(rows.getInt(1)), readOwner(rows.getString(2), rows.getString(3)));
      }
    }
  }

  private void saveSnapshotsInternal(UUID operationId, List<SerializedItemSnapshot> snapshots) throws SQLException {
    try (var insert = connection.prepareStatement(
        "INSERT INTO escrow_items(operation_id, copy_uuid, item_bytes) VALUES (?, ?, ?) ON CONFLICT(operation_id, copy_uuid) DO UPDATE SET item_bytes = excluded.item_bytes")) {
      for (SerializedItemSnapshot snapshot : snapshots) {
        insert.setString(1, operationId.toString());
        insert.setString(2, snapshot.copyId().value().toString());
        insert.setBytes(3, snapshot.bytes());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private void inTransaction(SqlAction action) {
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        action.run();
        connection.commit();
      } catch (Exception exception) {
        try { connection.rollback(); } catch (SQLException ignored) { }
        if (exception instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(exception);
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  private static List<CardCopyId> copyList(List<CardCopyId> copies) {
    Objects.requireNonNull(copies, "copy ids are required");
    Set<CardCopyId> unique = new HashSet<>();
    for (CardCopyId copy : copies) {
      if (copy == null || !unique.add(copy)) throw new IllegalArgumentException("duplicate copy id");
    }
    return List.copyOf(copies);
  }

  private static boolean hasExactSnapshots(Reservation reservation) {
    return hasExactSnapshots(reservation.copyIds(), reservation.snapshots());
  }

  private static boolean hasExactSnapshots(List<CardCopyId> copies,
      List<SerializedItemSnapshot> snapshots) {
    Set<CardCopyId> snapshotIds = new HashSet<>();
    for (SerializedItemSnapshot snapshot : snapshots) {
      if (!snapshotIds.add(snapshot.copyId())) return false;
    }
    return snapshotIds.equals(new HashSet<>(copies));
  }
  private static boolean allowed(OperationStatus from, OperationStatus to) {
    return to == OperationStatus.ABORTED && (from == OperationStatus.PREPARED
        || from == OperationStatus.ESCROWED || from == OperationStatus.RETURN_PENDING
        || from == OperationStatus.RETURNED)
        || from == OperationStatus.PREPARED && (to == OperationStatus.ESCROWED
        || to == OperationStatus.RETURN_PENDING)
        || from == OperationStatus.ESCROWED && to == OperationStatus.RETURN_PENDING
        || from == OperationStatus.RETURN_PENDING && to == OperationStatus.RETURNED;
  }

  private static CopyOwner readOwner(String kind, String id) {
    if ("HOUSE".equals(kind)) return CopyOwner.house();
    if ("PLAYER".equals(kind) && id != null) return CopyOwner.player(UUID.fromString(id));
    throw new IllegalStateException("invalid owner in database");
  }

  private static void writeOwner(java.sql.PreparedStatement statement, int kindIndex, int idIndex,
      CopyOwner owner) throws SQLException {
    statement.setString(kindIndex, owner.kind().name());
    if (owner.playerId() == null) statement.setNull(idIndex, java.sql.Types.VARCHAR);
    else statement.setString(idIndex, owner.playerId().toString());
  }

  private static IllegalStateException failure(SQLException exception) {
    return new IllegalStateException("SQLite custody operation failed", exception);
  }

  @FunctionalInterface
  private interface SqlAction { void run() throws Exception; }

  public record StoredCardCopy(CardCopyId copyId, CardId catalogId, CopyOwner owner) {
    public StoredCardCopy {
      Objects.requireNonNull(copyId); Objects.requireNonNull(catalogId); Objects.requireNonNull(owner);
    }
  }
  public record Reservation(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots, OperationStatus status, List<CardCopyId> restoredCopyIds,
      String matchId) {
    public Reservation {
      Objects.requireNonNull(operationId); Objects.requireNonNull(owner);
      copyIds = List.copyOf(copyIds); snapshots = List.copyOf(snapshots); Objects.requireNonNull(status);
      restoredCopyIds = List.copyOf(Objects.requireNonNull(restoredCopyIds));
      if (matchId != null && matchId.isBlank()) throw new IllegalArgumentException("match id cannot be blank");
      if (!copyIds.containsAll(restoredCopyIds)) throw new IllegalArgumentException("restored copy is not reserved");
    }

    public Reservation(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, OperationStatus status, List<CardCopyId> restoredCopyIds) {
      this(operationId, owner, copyIds, snapshots, status, restoredCopyIds, null);
    }

    public Reservation(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, OperationStatus status) {
      this(operationId, owner, copyIds, snapshots, status, List.of(), null);
    }

    public List<CardCopyId> expectedCopyIds() { return copyIds; }
  }

  public record RecoveryRow(UUID operationId, CardCopyId copyId, CopyOwner owner,
      SerializedItemSnapshot snapshot) {
    public RecoveryRow {
      Objects.requireNonNull(operationId); Objects.requireNonNull(copyId);
      Objects.requireNonNull(owner); Objects.requireNonNull(snapshot);
    }
  }
  public record CatalogSnapshot(String snapshotId, String source, String digest, String metadataJson) {
    public CatalogSnapshot {
      if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshot id is required");
      if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
      if (digest == null || digest.isBlank()) throw new IllegalArgumentException("digest is required");
      Objects.requireNonNull(metadataJson, "metadata is required");
    }
  }
}
