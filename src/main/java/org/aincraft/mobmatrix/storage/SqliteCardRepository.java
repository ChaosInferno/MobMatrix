package org.aincraft.mobmatrix.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

  @Override
  public List<UUID> openOperationIds() {
    try (var query = connection.prepareStatement("""
        SELECT operation_id FROM reservations
        ORDER BY created_at
        """)) {
      List<UUID> result = new ArrayList<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String rawOperationId = rows.getString(1);
          try {
            result.add(parseCanonicalUuid(rawOperationId));
          } catch (RuntimeException exception) {
            // UUID is the only identity this API can expose. Do not let one unrepresentable
            // operation hide later valid operation identities during startup enumeration.
          }
        }
      }
      return List.copyOf(result);
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }
  /**
   * Returns parseable copy identities from every reservation that is not durably proven terminal.
   * This deliberately reads raw operation IDs so malformed operation text cannot hide custody.
   */
  @Override
  public Set<CardCopyId> reservedCopyIds() {
    try {
      return scanReservationIdentities().copyIds();
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  @Override
  public boolean hasUnresolvedReservationIdentity() {
    try {
      return scanReservationIdentities().unresolved();
    } catch (SQLException exception) {
      throw failure(exception);
    }
  }

  private IdentityScan scanReservationIdentities() throws SQLException {
    Map<String, ReservationRow> reservations = new HashMap<>();
    Set<String> duplicateOperations = new HashSet<>();
    try (var query = connection.prepareStatement(
        "SELECT operation_id, owner_kind, owner_uuid, status FROM reservations ORDER BY rowid");
        var rows = query.executeQuery()) {
      while (rows.next()) {
        ReservationRow row = new ReservationRow(rows.getString(1), rows.getString(2),
            rows.getString(3), rows.getString(4));
        String key = operationKey(row.operationId());
        if (reservations.putIfAbsent(key, row) != null) duplicateOperations.add(key);
      }
    }
    Set<CardCopyId> copies = new HashSet<>();
    boolean unresolved = false;
    Map<String, Boolean> terminal = new HashMap<>();
    for (ReservationRow row : reservations.values()) {
      String key = operationKey(row.operationId());
      boolean proven = !duplicateOperations.contains(key)
          && isUuid(row.operationId())
          && provenTerminal(row.operationId(), row.ownerKind(), row.ownerUuid(), row.status());
      terminal.put(key, proven);
      if (!proven) unresolved = true;
    }
    unresolved |= scanRawChildTable("reservation_copies", reservations, duplicateOperations, terminal,
        copies, true);
    unresolved |= scanRawChildTable("escrow_items", reservations, duplicateOperations, terminal,
        copies, false);
    unresolved |= scanRawChildTable("reservation_restored", reservations, duplicateOperations,
        terminal, copies, false);
    unresolved |= scanRawChildTable("recovery_rows", reservations, duplicateOperations, terminal,
        copies, false);
    return new IdentityScan(Set.copyOf(copies), unresolved);
  }

  private boolean scanRawChildTable(String table, Map<String, ReservationRow> reservations,
      Set<String> duplicateOperations, Map<String, Boolean> terminal, Set<CardCopyId> copies,
      boolean reservationCopies) throws SQLException {
    boolean unresolved = false;
    try (var query = connection.prepareStatement(
        "SELECT operation_id, copy_uuid FROM " + table + " ORDER BY rowid");
        var rows = query.executeQuery()) {
      while (rows.next()) {
        String operationId = rows.getString(1);
        String rawCopyId = rows.getString(2);
        String key = operationKey(operationId);
        ReservationRow parent = reservations.get(key);
        boolean linked = parent != null
            && (reservationCopies || hasReservationCopy(operationId, rawCopyId));
        boolean proven = linked && !duplicateOperations.contains(key) && terminal.getOrDefault(key, false);
        CardCopyId copy = parseCopyId(rawCopyId);
        if (copy != null && !proven) copies.add(copy);
        if (!isUuid(operationId) || copy == null || !linked) unresolved = true;
      }
    }
    return unresolved;
  }

  private boolean hasReservationCopy(String operationId, String copyId) throws SQLException {
    if (operationId == null || copyId == null) return false;
    try (var query = connection.prepareStatement(
        "SELECT 1 FROM reservation_copies "
            + "WHERE lower(operation_id) = lower(?) AND lower(copy_uuid) = lower(?)")) {
      query.setString(1, operationId);
      query.setString(2, copyId);
      try (var rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static String operationKey(String operationId) {
    return operationId == null ? null : operationId.toLowerCase(java.util.Locale.ROOT);
  }

  static UUID parseCanonicalUuid(String value) {
    if (value == null) throw new IllegalArgumentException("UUID is required");
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("invalid UUID", exception);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("noncanonical UUID");
    }
    return parsed;
  }
  private static UUID parseStoredCanonicalUuid(String value, String message) {
    try {
      return parseCanonicalUuid(value);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(message, exception);
    }
  }

  private static boolean isUuid(String value) {
    try {
      parseCanonicalUuid(value);
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static CardCopyId parseCopyId(String value) {
    try {
      return new CardCopyId(parseCanonicalUuid(value));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private record ReservationRow(String operationId, String ownerKind, String ownerUuid,
      String status) {}

  private record IdentityScan(Set<CardCopyId> copyIds, boolean unresolved) {}



  public void saveCopy(StoredCardCopy copy) {
    Objects.requireNonNull(copy, "copy is required");
    inTransaction(() -> {
      try (var existing = connection.prepareStatement(
          "SELECT copy_uuid, catalog_id, owner_kind, owner_uuid FROM card_copies "
              + "WHERE lower(copy_uuid) = lower(?)")) {
        existing.setString(1, copy.copyId().value().toString());
        try (var rows = existing.executeQuery()) {
          if (rows.next()) {
            String rawCopyId = rows.getString(1);
            if (!copy.copyId().value().equals(parseStoredCanonicalUuid(rawCopyId,
                "invalid stored card-copy UUID"))) {
              throw new IllegalStateException("stored card-copy UUID does not match requested identity");
            }
            int catalogId = rows.getInt(2);
            CopyOwner owner = readOwner(rows.getString(3), rows.getString(4));
            if (rows.next()) {
              throw new IllegalStateException("duplicate card-copy UUID: " + copy.copyId());
            }
            if (catalogId != copy.catalogId().value() || !owner.equals(copy.owner())) {
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
    try {
      return Optional.ofNullable(findCopyInternal(copyId));
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
      java.util.Map<CardCopyId, String> storedCopyIds = new java.util.HashMap<>();
      for (CardCopyId copy : copies) {
        StoredCardCopy ledger;
        try {
          ledger = findCopyInternal(copy);
          if (ledger != null) storedCopyIds.put(copy, findStoredCopyUuidInternal(copy));
        } catch (RuntimeException exception) {
          throw new CustodyIntegrityException(operationId, List.of(copy),
              "invalid card-copy owner integrity data", exception);
        }
        if (ledger == null) throw new IllegalStateException("unknown card copy: " + copy);
        if (!ledger.owner().equals(owner)) throw new IllegalStateException("copy owner mismatch");
      }
      for (CardCopyId copy : copies) {
        validateCopyReuse(operationId, owner, copy);
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
          insert.setString(2, storedCopyIds.get(copy));
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
      if (target == OperationStatus.ABORTED
          && !hasExactSnapshots(reservation)) {
        throw new CustodyIntegrityException(operationId, reservation.copyIds(),
            "aborted custody is missing exact snapshots");
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
          "UPDATE reservations SET status = ?, updated_at = CURRENT_TIMESTAMP "
              + "WHERE lower(operation_id) = lower(?)")) {
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
    try {
      List<Reservation> result = new ArrayList<>();
      for (UUID operationId : openOperationIds()) {
        Reservation reservation = findReservationInternal(operationId);
        if (reservation != null
            && (reservation.status() == OperationStatus.PREPARED
                || reservation.status() == OperationStatus.ESCROWED
                || reservation.status() == OperationStatus.RETURN_PENDING)) {
          result.add(reservation);
        }
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
          "SELECT 1 FROM reservation_restored "
              + "WHERE lower(operation_id) = lower(?) AND lower(copy_uuid) = lower(?)")) {
        restored.setString(1, operationId.toString());
        restored.setString(2, snapshot.copyId().value().toString());
        try (var rows = restored.executeQuery()) {
          if (rows.next()) return;
        }
      }
      String storedOperationId = findStoredReservationOperationIdInternal(operationId);
      String storedCopyId = findStoredReservationCopyUuidInternal(operationId, snapshot.copyId());
      try (var insert = connection.prepareStatement("""
          INSERT INTO recovery_rows(operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status)
          VALUES (?, ?, ?, ?, ?, 'PENDING')
          ON CONFLICT(operation_id, copy_uuid) DO UPDATE SET item_bytes = excluded.item_bytes,
            status = CASE WHEN recovery_rows.status = 'RESTORED' THEN 'RESTORED' ELSE 'PENDING' END
          """)) {
        insert.setString(1, storedOperationId);
        insert.setString(2, storedCopyId);
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
        while (rows.next()) {
          UUID operationId;
          CardCopyId copyId;
          try {
            operationId = parseCanonicalUuid(rows.getString(1));
            copyId = new CardCopyId(parseCanonicalUuid(rows.getString(2)));
          } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid recovery identity data", exception);
          }
          CopyOwner owner;
          try {
            owner = readOwner(rows.getString(3), rows.getString(4));
          } catch (RuntimeException exception) {
            throw new CustodyIntegrityException(operationId, List.of(copyId),
                "invalid recovery owner integrity data", exception);
          }
          Reservation reservation = findReservationInternal(operationId);
          SerializedItemSnapshot expected = reservation == null ? null : reservation.snapshots().stream()
              .filter(snapshot -> snapshot.copyId().equals(copyId)).findFirst().orElse(null);
          byte[] bytes = rows.getBytes(5);
          if (reservation == null || !reservation.owner().equals(owner) || expected == null
              || bytes == null || bytes.length == 0 || !Arrays.equals(expected.bytes(), bytes)) {
            throw new CustodyIntegrityException(operationId,
                reservation == null ? List.of(copyId) : reservation.copyIds(),
                "recovery row does not match reservation");
          }
          result.add(new RecoveryRow(operationId, copyId, owner,
              new SerializedItemSnapshot(copyId, bytes)));
        }
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
      String storedOperationId = findStoredReservationOperationIdInternal(operationId);
      String storedCopyId = findStoredReservationCopyUuidInternal(operationId, copyId);
      try (var insert = connection.prepareStatement(
          "INSERT INTO reservation_restored(operation_id, copy_uuid) VALUES (?, ?) "
              + "ON CONFLICT(operation_id, copy_uuid) DO NOTHING")) {
        insert.setString(1, storedOperationId);
        insert.setString(2, storedCopyId);
        insert.executeUpdate();
      }
      try (var update = connection.prepareStatement(
          "UPDATE recovery_rows SET status = 'RESTORED' "
              + "WHERE lower(operation_id) = lower(?) AND lower(copy_uuid) = lower(?)")) {
        update.setString(1, operationId.toString());
        update.setString(2, copyId.value().toString());
        update.executeUpdate();
      }
    });
  }
  private void validateCopyReuse(UUID operationId, CopyOwner owner, CardCopyId copy)
      throws SQLException {
    try (var query = connection.prepareStatement("""
        SELECT operation_id FROM reservation_copies
        WHERE lower(copy_uuid) = lower(?)
        UNION ALL
        SELECT operation_id FROM escrow_items
        WHERE lower(copy_uuid) = lower(?)
        UNION ALL
        SELECT operation_id FROM reservation_restored
        WHERE lower(copy_uuid) = lower(?)
        UNION ALL
        SELECT operation_id FROM recovery_rows
        WHERE lower(copy_uuid) = lower(?)
        """)) {
      String rawCopyId = copy.value().toString();
      query.setString(1, rawCopyId);
      query.setString(2, rawCopyId);
      query.setString(3, rawCopyId);
      query.setString(4, rawCopyId);
      Map<String, String> seenOperationSpellings = new HashMap<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String rawOperationId = rows.getString(1);
          String operationKey = operationKey(rawOperationId);
          String previousSpelling = seenOperationSpellings.putIfAbsent(operationKey, rawOperationId);
          if (previousSpelling != null) {
            if (!previousSpelling.equals(rawOperationId)) {
              throw new CustodyIntegrityException(operationId, List.of(copy),
                  "ambiguous case-variant custody operation identity", null);
            }
            continue;
          }
          UUID existingOperation;
          try {
            existingOperation = parseCanonicalUuid(rawOperationId);
          } catch (RuntimeException exception) {
            throw new CustodyIntegrityException(operationId, List.of(copy),
                "invalid existing custody operation identity", exception);
          }
          if (existingOperation.equals(operationId)) {
            throw new CustodyIntegrityException(operationId, List.of(copy),
                "copy has an existing custody identity", null);
          }
          Reservation existing = findReservationInternal(existingOperation);
          if (existing == null) {
            throw new IllegalStateException("custody child has no reservation");
          }
          if (!existing.owner().equals(owner)) {
            throw new IllegalStateException("existing reservation owner does not match copy owner");
          }
          if (existing.status() != OperationStatus.RETURNED
              && existing.status() != OperationStatus.ABORTED) {
            throw new IllegalStateException("copy is not proven reusable");
          }
          if (!hasValidRecoveryRows(existingOperation, existing.owner(), existing.copyIds(),
              existing.snapshots()) || hasPendingRecovery(existingOperation)) {
            throw new IllegalStateException("copy is not proven reusable");
          }
        }
      }
    }
  }
  private Reservation findReservationInternal(UUID operationId) throws SQLException {
    boolean duplicateOperationIdentity = false;
    try (var count = connection.prepareStatement(
        "SELECT COUNT(*) FROM reservations WHERE lower(operation_id) = lower(?)")) {
      count.setString(1, operationId.toString());
      try (var rows = count.executeQuery()) {
        duplicateOperationIdentity = rows.next() && rows.getInt(1) > 1;
      }
    }
    try (var query = connection.prepareStatement(
        "SELECT operation_id, owner_kind, owner_uuid, match_id, status FROM reservations "
            + "WHERE lower(operation_id) = lower(?)")) {
      query.setString(1, operationId.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return null;
        String rawParentOperationId = rows.getString(1);
        String ownerKind = rows.getString(2);
        String ownerUuid = rows.getString(3);
        String matchId = rows.getString(4);
        String rawStatus = rows.getString(5);
        List<CardCopyId> copies = new ArrayList<>();
        RuntimeException decodeFailure = null;
        try {
          if (!operationId.equals(parseCanonicalUuid(rawParentOperationId))) {
            throw new IllegalArgumentException("parent operation identity does not match requested");
          }
        } catch (RuntimeException exception) {
          decodeFailure = exception;
        }

        // Scan every reservation-copy row even when one identity is malformed. The parseable
        // physical UUIDs are the only identities that can be retained for fail-closed recovery.
        try (var copyQuery = connection.prepareStatement(
            "SELECT operation_id, copy_uuid FROM reservation_copies "
                + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
          copyQuery.setString(1, operationId.toString());
          try (var copyRows = copyQuery.executeQuery()) {
            while (copyRows.next()) {
              try {
                if (!operationId.equals(parseCanonicalUuid(copyRows.getString(1)))) {
                  throw new IllegalArgumentException("child operation identity does not match parent");
                }
              } catch (RuntimeException exception) {
                if (decodeFailure == null) decodeFailure = exception;
              }
              try {
                copies.add(new CardCopyId(parseCanonicalUuid(copyRows.getString(2))));
              } catch (RuntimeException exception) {
                if (decodeFailure == null) decodeFailure = exception;
              }
            }
          }
        }

        List<SerializedItemSnapshot> snapshots = new ArrayList<>();
        try (var snapshotQuery = connection.prepareStatement(
            "SELECT operation_id, copy_uuid, item_bytes FROM escrow_items "
                + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
          snapshotQuery.setString(1, operationId.toString());
          try (var snapshotRows = snapshotQuery.executeQuery()) {
            while (snapshotRows.next()) {
              try {
                if (!operationId.equals(parseCanonicalUuid(snapshotRows.getString(1)))) {
                  throw new IllegalArgumentException("child operation identity does not match parent");
                }
                CardCopyId copy = new CardCopyId(parseCanonicalUuid(snapshotRows.getString(2)));
                snapshots.add(new SerializedItemSnapshot(copy, snapshotRows.getBytes(3)));
              } catch (RuntimeException exception) {
                if (decodeFailure == null) decodeFailure = exception;
              }
            }
          }
        }

        List<CardCopyId> restored = new ArrayList<>();
        try (var restoredQuery = connection.prepareStatement(
            "SELECT operation_id, copy_uuid FROM reservation_restored "
                + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
          restoredQuery.setString(1, operationId.toString());
          try (var restoredRows = restoredQuery.executeQuery()) {
            while (restoredRows.next()) {
              try {
                if (!operationId.equals(parseCanonicalUuid(restoredRows.getString(1)))) {
                  throw new IllegalArgumentException("child operation identity does not match parent");
                }
                restored.add(new CardCopyId(parseCanonicalUuid(restoredRows.getString(2))));
              } catch (RuntimeException exception) {
                if (decodeFailure == null) decodeFailure = exception;
              }
            }
          }
        }
        if (duplicateOperationIdentity || decodeFailure != null) {
          throw new CustodyIntegrityException(operationId, copies,
              duplicateOperationIdentity ? "duplicate case-variant reservation operation identity"
                  : "invalid reservation identity data", decodeFailure);
        }
        try {
          CopyOwner owner = readOwner(ownerKind, ownerUuid);
          OperationStatus status = OperationStatus.valueOf(rawStatus);
          if (!hasExactSnapshots(copies, snapshots)) {
            throw new CustodyIntegrityException(operationId, copies,
                "reservation is missing exact escrow snapshots");
          }
          if (status == OperationStatus.RETURNED
              && !new HashSet<>(restored).containsAll(copies)) {
            throw new IllegalStateException("cannot hydrate returned custody with unresolved copies");
          }
          if (status == OperationStatus.ABORTED
              && !new HashSet<>(restored).containsAll(copies)) {
            throw new IllegalStateException("cannot hydrate aborted custody with unresolved copies");
          }
          if (!hasValidRecoveryRows(operationId, owner, copies, snapshots)) {
            throw new CustodyIntegrityException(operationId, copies,
                "reservation has invalid recovery row identity");
          }
          return new Reservation(operationId, owner, copies, snapshots, status, restored, matchId);
        } catch (CustodyIntegrityException exception) {
          throw exception;
        } catch (RuntimeException exception) {
          throw new CustodyIntegrityException(operationId, copies,
              "invalid reservation integrity data", exception);
        }
      }
    }
  }
  private boolean hasValidRecoveryRows(UUID operationId, CopyOwner owner,
      List<CardCopyId> copies, List<SerializedItemSnapshot> snapshots) throws SQLException {
    return hasValidRecoveryRows(operationId.toString(), owner, copies, snapshots);
  }

  private boolean hasValidRecoveryRows(String rawOperationId, CopyOwner owner,
      List<CardCopyId> copies, List<SerializedItemSnapshot> snapshots) throws SQLException {
    UUID expectedOperation;
    try {
      expectedOperation = parseCanonicalUuid(rawOperationId);
    } catch (RuntimeException exception) {
      return false;
    }
    java.util.Map<CardCopyId, byte[]> expected = new java.util.HashMap<>();
    for (SerializedItemSnapshot snapshot : snapshots) expected.put(snapshot.copyId(), snapshot.bytes());
    Set<CardCopyId> seen = new HashSet<>();
    try (var query = connection.prepareStatement("""
        SELECT operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status
        FROM recovery_rows WHERE lower(operation_id) = lower(?)
        """)) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          CardCopyId copy;
          try {
            if (!expectedOperation.equals(parseCanonicalUuid(rows.getString(1)))) return false;
            copy = new CardCopyId(parseCanonicalUuid(rows.getString(2)));
          } catch (RuntimeException exception) {
            return false;
          }
          if (!expected.containsKey(copy) || !seen.add(copy)) return false;
          CopyOwner recoveryOwner;
          try {
            recoveryOwner = readOwner(rows.getString(3), rows.getString(4));
          } catch (RuntimeException exception) {
            return false;
          }
          if (!owner.equals(recoveryOwner)
              || (!"PENDING".equals(rows.getString(6)) && !"RESTORED".equals(rows.getString(6)))) {
            return false;
          }
          byte[] bytes = rows.getBytes(5);
          if (bytes == null || bytes.length == 0 || !Arrays.equals(expected.get(copy), bytes)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean hasPendingRecovery(UUID operationId) throws SQLException {
    return hasPendingRecovery(operationId.toString());
  }
  private boolean hasPendingRecovery(String rawOperationId) throws SQLException {
    UUID expectedOperation;
    try {
      expectedOperation = parseCanonicalUuid(rawOperationId);
    } catch (RuntimeException exception) {
      return true;
    }
    try (var query = connection.prepareStatement(
        "SELECT operation_id, status FROM recovery_rows WHERE lower(operation_id) = lower(?)")) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            if (!expectedOperation.equals(parseCanonicalUuid(rows.getString(1)))) return true;
          } catch (RuntimeException exception) {
            return true;
          }
          if ("PENDING".equals(rows.getString(2))) return true;
        }
      }
    }
    return false;
  }

  private boolean provenTerminal(String rawOperationId) throws SQLException {
    if (rawOperationId == null) return false;
    String ownerKind = null;
    String ownerUuid = null;
    String status = null;
    try (var query = connection.prepareStatement(
        "SELECT operation_id, owner_kind, owner_uuid, status FROM reservations "
            + "WHERE lower(operation_id) = lower(?)")) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return false;
        String storedOperationId = rows.getString(1);
        try {
          if (!parseCanonicalUuid(storedOperationId).toString().equals(storedOperationId)
              || !storedOperationId.equals(rawOperationId)) {
            return false;
          }
        } catch (RuntimeException exception) {
          return false;
        }
        ownerKind = rows.getString(2);
        ownerUuid = rows.getString(3);
        status = rows.getString(4);
        if (rows.next()) return false;
      }
    }
    return provenTerminal(rawOperationId, ownerKind, ownerUuid, status);
  }

  private boolean provenTerminal(String rawOperationId, String ownerKind, String ownerUuid,
      String rawStatus) throws SQLException {
    UUID expectedOperation;
    try {
      expectedOperation = parseCanonicalUuid(rawOperationId);
    } catch (RuntimeException exception) {
      return false;
    }
    if (!"RETURNED".equals(rawStatus) && !"ABORTED".equals(rawStatus)) return false;
    CopyOwner owner;
    try {
      owner = readOwner(ownerKind, ownerUuid);
    } catch (RuntimeException exception) {
      return false;
    }
    List<CardCopyId> copies = new ArrayList<>();
    try (var query = connection.prepareStatement(
        "SELECT operation_id, copy_uuid FROM reservation_copies "
            + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            if (!expectedOperation.equals(parseCanonicalUuid(rows.getString(1)))) return false;
            CardCopyId copy = new CardCopyId(parseCanonicalUuid(rows.getString(2)));
            if (copies.contains(copy)) return false;
            copies.add(copy);
          } catch (RuntimeException exception) {
            return false;
          }
        }
      }
    }
    if (copies.isEmpty()) return false;
    for (CardCopyId copy : copies) {
      try {
        StoredCardCopy ledger = findCopyInternal(copy);
        if (ledger == null || !ledger.owner().equals(owner)) return false;
      } catch (RuntimeException exception) {
        return false;
      }
    }
    List<SerializedItemSnapshot> snapshots = new ArrayList<>();
    Set<CardCopyId> snapshotIds = new HashSet<>();
    try (var query = connection.prepareStatement(
        "SELECT operation_id, copy_uuid, item_bytes FROM escrow_items "
            + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            if (!expectedOperation.equals(parseCanonicalUuid(rows.getString(1)))) return false;
            CardCopyId copy = new CardCopyId(parseCanonicalUuid(rows.getString(2)));
            byte[] bytes = rows.getBytes(3);
            if (!snapshotIds.add(copy) || bytes == null || bytes.length == 0) return false;
            snapshots.add(new SerializedItemSnapshot(copy, bytes));
          } catch (RuntimeException exception) {
            return false;
          }
        }
      }
    }
    if (!hasExactSnapshots(copies, snapshots)) return false;
    Set<CardCopyId> restored = new HashSet<>();
    try (var query = connection.prepareStatement(
        "SELECT operation_id, copy_uuid FROM reservation_restored "
            + "WHERE lower(operation_id) = lower(?) ORDER BY rowid")) {
      query.setString(1, rawOperationId);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            if (!expectedOperation.equals(parseCanonicalUuid(rows.getString(1)))) return false;
            CardCopyId copy = new CardCopyId(parseCanonicalUuid(rows.getString(2)));
            if (!restored.add(copy)) return false;
          } catch (RuntimeException exception) {
            return false;
          }
        }
      }
    }
    if (!restored.containsAll(copies) || restored.size() != copies.size()) return false;
    if (!hasValidRecoveryRows(rawOperationId, owner, copies, snapshots)
        || hasPendingRecovery(rawOperationId)) return false;
    return true;
  }

  private String findStoredCopyUuidInternal(CardCopyId copyId) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT copy_uuid FROM card_copies WHERE lower(copy_uuid) = lower(?)")) {
      query.setString(1, copyId.value().toString());
      try (var rows = query.executeQuery()) {
        String stored = null;
        while (rows.next()) {
          String rawCopyId = rows.getString(1);
          if (!copyId.value().equals(parseStoredCanonicalUuid(rawCopyId,
              "invalid stored card-copy UUID"))) {
            throw new IllegalStateException("stored card-copy UUID does not match requested identity");
          }
          if (stored != null) {
            throw new IllegalStateException("duplicate card-copy UUID: " + copyId);
          }
          stored = rawCopyId;
        }
        if (stored == null) throw new IllegalStateException("unknown card copy: " + copyId);
        return stored;
      }
    }
  }

  private String findStoredReservationOperationIdInternal(UUID operationId) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT operation_id FROM reservations WHERE lower(operation_id) = lower(?)")) {
      query.setString(1, operationId.toString());
      try (var rows = query.executeQuery()) {
        String stored = null;
        while (rows.next()) {
          String rawOperationId = rows.getString(1);
          if (!operationId.equals(parseStoredCanonicalUuid(rawOperationId,
              "invalid stored operation UUID"))) {
            throw new IllegalStateException("stored operation UUID does not match requested identity");
          }
          if (stored != null) {
            throw new IllegalStateException("duplicate reservation operation UUID: " + operationId);
          }
          stored = rawOperationId;
        }
        if (stored == null) throw new IllegalStateException("unknown operation: " + operationId);
        return stored;
      }
    }
  }
  private String findStoredReservationCopyUuidInternal(UUID operationId, CardCopyId copyId)
      throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT operation_id, copy_uuid FROM reservation_copies "
            + "WHERE lower(operation_id) = lower(?) AND lower(copy_uuid) = lower(?) ORDER BY rowid")) {
      query.setString(1, operationId.toString());
      query.setString(2, copyId.value().toString());
      try (var rows = query.executeQuery()) {
        String stored = null;
        while (rows.next()) {
          String rawOperationId = rows.getString(1);
          String rawCopyId = rows.getString(2);
          if (!operationId.equals(parseStoredCanonicalUuid(rawOperationId,
              "invalid stored operation UUID"))
              || !copyId.value().equals(parseStoredCanonicalUuid(rawCopyId,
                  "invalid stored card-copy UUID"))) {
            throw new IllegalStateException("stored reservation identity does not match request");
          }
          if (stored != null) {
            throw new IllegalStateException("duplicate reserved copy identity: " + copyId);
          }
          stored = rawCopyId;
        }
        if (stored == null) throw new IllegalStateException("copy is not reserved by operation");
        return stored;
      }
    }
  }
  private StoredCardCopy findCopyInternal(CardCopyId copyId) throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT copy_uuid, catalog_id, owner_kind, owner_uuid FROM card_copies "
            + "WHERE lower(copy_uuid) = lower(?)")) {
      query.setString(1, copyId.value().toString());
      try (var rows = query.executeQuery()) {
        StoredCardCopy found = null;
        while (rows.next()) {
          String rawCopyId = rows.getString(1);
          if (!copyId.value().equals(parseStoredCanonicalUuid(rawCopyId,
              "invalid stored card-copy UUID"))) {
            throw new IllegalStateException("stored card-copy UUID does not match requested identity");
          }
          if (found != null) {
            throw new IllegalStateException("duplicate card-copy UUID: " + copyId);
          }
          found = new StoredCardCopy(copyId, new CardId(rows.getInt(2)),
              readOwner(rows.getString(3), rows.getString(4)));
        }
        return found;
      }
    }
  }
  private void saveSnapshotsInternal(UUID operationId, List<SerializedItemSnapshot> snapshots)
      throws SQLException {
    String storedOperationId = findStoredReservationOperationIdInternal(operationId);
    try (var insert = connection.prepareStatement(
        "INSERT INTO escrow_items(operation_id, copy_uuid, item_bytes) VALUES (?, ?, ?) "
            + "ON CONFLICT(operation_id, copy_uuid) DO UPDATE SET item_bytes = excluded.item_bytes")) {
      for (SerializedItemSnapshot snapshot : snapshots) {
        insert.setString(1, storedOperationId);
        insert.setString(2, findStoredReservationCopyUuidInternal(operationId, snapshot.copyId()));
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
    if (copies.size() != new HashSet<>(copies).size() || snapshots.size() != copies.size()) {
      return false;
    }
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
    if ("HOUSE".equals(kind) && id == null) return CopyOwner.house();
    if ("PLAYER".equals(kind) && id != null) {
      try {
        return CopyOwner.player(parseCanonicalUuid(id));
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("invalid player owner UUID in database", exception);
      }
    }
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

  public static final class CustodyIntegrityException extends IllegalStateException
      implements CardCustodyPort.CustodyIntegrityFailure {
    private final UUID operationId;
    private final List<CardCopyId> expectedCopyIds;

    public CustodyIntegrityException(UUID operationId, List<CardCopyId> expectedCopyIds,
        String message) {
      this(operationId, expectedCopyIds, message, null);
    }

    public CustodyIntegrityException(UUID operationId, List<CardCopyId> expectedCopyIds,
        String message, Throwable cause) {
      super(message, cause);
      this.operationId = Objects.requireNonNull(operationId, "operation id is required");
      this.expectedCopyIds = List.copyOf(Objects.requireNonNull(expectedCopyIds,
          "expected copy ids are required"));
    }

    @Override
    public UUID operationId() {
      return operationId;
    }

    @Override
    public List<CardCopyId> expectedCopyIds() {
      return expectedCopyIds;
    }

    public CardCustodyPort.Failure failure() {
      return CardCustodyPort.Failure.INVALID_STATE;
    }
  }

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
