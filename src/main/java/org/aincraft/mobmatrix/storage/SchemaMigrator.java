package org.aincraft.mobmatrix.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit, repeatable schema migrations for custody and active-match state. */
public final class SchemaMigrator {
  public static final int CURRENT_VERSION = 8;

  private SchemaMigrator() {}

  public static void migrate(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection is required");
    ensureMigrationTable(connection);
    Set<Integer> markers = appliedMarkers(connection);
    int version = contiguousVersion(markers);
    for (int next = version + 1; next <= CURRENT_VERSION; next++) {
      applyMigration(connection, next);
    }
    if (CURRENT_VERSION >= 7) applyV7(connection);
    if (CURRENT_VERSION >= 8) applyV8(connection);
    validateSchema(connection);
  }

  private static void ensureMigrationTable(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            applied_at TEXT NOT NULL
          )
          """);
    }
  }

  private static void applyMigration(Connection connection, int version) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    try {
      connection.setAutoCommit(false);
      switch (version) {
        case 1 -> applyV1(connection);
        case 2 -> applyV2(connection);
        case 3 -> applyV3(connection);
        case 4 -> applyV4(connection);
        case 5 -> applyV5(connection);
        case 6 -> applyV6(connection);
        case 7 -> applyV7(connection);
        case 8 -> applyV8(connection);
      }
      try (var insert = connection.prepareStatement(
          "INSERT INTO schema_migrations(version, applied_at) VALUES (?, CURRENT_TIMESTAMP)")) {
        insert.setInt(1, version);
        insert.executeUpdate();
      }
      connection.commit();
    } catch (SQLException | RuntimeException exception) {
      try { connection.rollback(); } catch (SQLException ignored) { }
      if (exception instanceof SQLException sql) throw sql;
      throw exception;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }
  private static void ensureStartBindings(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS match_start_bindings (
            match_id TEXT PRIMARY KEY REFERENCES matches(match_id) ON DELETE CASCADE,
            binding_version INTEGER NOT NULL CHECK (binding_version > 0),
            request_identity TEXT NOT NULL,
            one_owner_kind TEXT NOT NULL CHECK (one_owner_kind IN ('PLAYER', 'HOUSE')),
            one_owner_uuid TEXT,
            one_operation_id TEXT NOT NULL,
            one_copy_ids TEXT NOT NULL,
            two_owner_kind TEXT NOT NULL CHECK (two_owner_kind IN ('PLAYER', 'HOUSE')),
            two_owner_uuid TEXT,
            two_operation_id TEXT NOT NULL,
            two_copy_ids TEXT NOT NULL,
            CHECK ((one_owner_kind = 'PLAYER' AND one_owner_uuid IS NOT NULL) OR
                   (one_owner_kind = 'HOUSE' AND one_owner_uuid IS NULL)),
            CHECK ((two_owner_kind = 'PLAYER' AND two_owner_uuid IS NOT NULL) OR
                   (two_owner_kind = 'HOUSE' AND two_owner_uuid IS NULL))
          )
          """);
    }
  }


  public static int currentVersion(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection is required");
    return contiguousVersion(appliedMarkers(connection));
  }

  private static Set<Integer> appliedMarkers(Connection connection) throws SQLException {
    Set<Integer> markers = new HashSet<>();
    try (var query = connection.createStatement();
        var rows = query.executeQuery("SELECT version FROM schema_migrations")) {
      while (rows.next()) {
        int version = rows.getInt(1);
        if (version < 1 || version > CURRENT_VERSION || !markers.add(version)) {
          throw new SQLException("invalid schema migration marker: " + version);
        }
      }
    }
    return markers;
  }

  private static int contiguousVersion(Set<Integer> markers) throws SQLException {
    int version = 0;
    while (markers.contains(version + 1)) version++;
    if (markers.size() != version) {
      throw new SQLException("schema migration markers are non-contiguous");
    }
    return version;
  }

  private static void validateSchema(Connection connection) throws SQLException {
    String[][] required = {
        {"catalog_snapshots", "snapshot_id", "source", "digest", "created_at", "metadata_json"},
        {"card_definitions", "snapshot_id", "catalog_id", "public_number", "name", "stars", "type",
            "top_value", "right_value", "bottom_value", "left_value", "sale_price"},
        {"card_copies", "copy_uuid", "catalog_id", "owner_kind", "owner_uuid", "created_at"},
        {"reservations", "operation_id", "owner_kind", "owner_uuid", "match_id", "status",
            "created_at", "updated_at"},
        {"reservation_copies", "operation_id", "copy_uuid"},
        {"escrow_items", "operation_id", "copy_uuid", "item_bytes"},
        {"matches", "match_id", "revision", "status", "state_bytes", "state_digest",
            "transcript_json", "updated_at"},
        {"recovery_rows", "recovery_id", "operation_id", "copy_uuid", "owner_kind", "owner_uuid",
            "item_bytes", "status"},
        {"reservation_restored", "operation_id", "copy_uuid"},
        {"match_start_bindings", "match_id", "binding_version", "request_identity",
            "one_owner_kind", "one_owner_uuid", "one_operation_id", "one_copy_ids",
            "two_owner_kind", "two_owner_uuid", "two_operation_id", "two_copy_ids"}
    };
    for (String[] table : required) {
      Set<String> columns = tableColumns(connection, table[0]);
      if (columns.isEmpty()) {
        throw new SQLException("schema migration incomplete: missing table " + table[0]);
      }
      for (int index = 1; index < table.length; index++) {
        if (!columns.contains(table[index])) {
          throw new SQLException("schema migration incomplete: missing column "
              + table[0] + "." + table[index]);
        }
      }
    }
    if (!hasRequiredCopyUuidIndex(connection)) {
      throw new SQLException("schema migration incomplete: missing case-insensitive card copy UUID index");
    }
  }

  private static Set<String> tableColumns(Connection connection, String table) throws SQLException {
    Set<String> columns = new HashSet<>();
    try (var query = connection.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
      while (query.next()) columns.add(query.getString("name"));
    }
    return columns;
  }

  private static void applyV1(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS catalog_snapshots (
            snapshot_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            digest TEXT NOT NULL,
            created_at TEXT NOT NULL,
            metadata_json TEXT NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS card_definitions (
            snapshot_id TEXT NOT NULL REFERENCES catalog_snapshots(snapshot_id),
            catalog_id INTEGER NOT NULL,
            public_number TEXT NOT NULL,
            name TEXT NOT NULL,
            stars INTEGER NOT NULL,
            type TEXT NOT NULL,
            top_value INTEGER NOT NULL,
            right_value INTEGER NOT NULL,
            bottom_value INTEGER NOT NULL,
            left_value INTEGER NOT NULL,
            sale_price INTEGER NOT NULL,
            PRIMARY KEY (snapshot_id, catalog_id),
            UNIQUE (snapshot_id, public_number)
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS card_copies (
            copy_uuid TEXT PRIMARY KEY,
            catalog_id INTEGER NOT NULL,
            owner_kind TEXT NOT NULL CHECK (owner_kind IN ('PLAYER', 'HOUSE')),
            owner_uuid TEXT,
            created_at TEXT NOT NULL,
            CHECK ((owner_kind = 'PLAYER' AND owner_uuid IS NOT NULL) OR
                   (owner_kind = 'HOUSE' AND owner_uuid IS NULL))
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS reservations (
            operation_id TEXT PRIMARY KEY,
            owner_kind TEXT NOT NULL CHECK (owner_kind IN ('PLAYER', 'HOUSE')),
            owner_uuid TEXT,
            status TEXT NOT NULL CHECK (status IN ('PREPARED', 'ESCROWED', 'RETURN_PENDING', 'RETURNED', 'ABORTED')),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            CHECK ((owner_kind = 'PLAYER' AND owner_uuid IS NOT NULL) OR
                   (owner_kind = 'HOUSE' AND owner_uuid IS NULL))
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS reservation_copies (
            operation_id TEXT NOT NULL REFERENCES reservations(operation_id) ON DELETE CASCADE,
            copy_uuid TEXT NOT NULL REFERENCES card_copies(copy_uuid),
            PRIMARY KEY (operation_id, copy_uuid)
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS escrow_items (
            operation_id TEXT NOT NULL REFERENCES reservations(operation_id) ON DELETE CASCADE,
            copy_uuid TEXT NOT NULL,
            item_bytes BLOB NOT NULL,
            PRIMARY KEY (operation_id, copy_uuid),
            FOREIGN KEY (operation_id, copy_uuid)
              REFERENCES reservation_copies(operation_id, copy_uuid) ON DELETE CASCADE
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS matches (
            match_id TEXT PRIMARY KEY,
            revision INTEGER NOT NULL CHECK (revision >= 0),
            status TEXT NOT NULL,
            state_bytes BLOB NOT NULL,
            transcript_json TEXT NOT NULL,
            updated_at TEXT NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS recovery_rows (
            recovery_id INTEGER PRIMARY KEY AUTOINCREMENT,
            operation_id TEXT NOT NULL,
            copy_uuid TEXT NOT NULL,
            owner_kind TEXT NOT NULL CHECK (owner_kind IN ('PLAYER', 'HOUSE')),
            owner_uuid TEXT,
            item_bytes BLOB NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('PENDING', 'RESTORED')),
            UNIQUE (operation_id, copy_uuid),
            FOREIGN KEY (operation_id) REFERENCES reservations(operation_id),
            FOREIGN KEY (operation_id, copy_uuid)
              REFERENCES reservation_copies(operation_id, copy_uuid),
            CHECK ((owner_kind = 'PLAYER' AND owner_uuid IS NOT NULL) OR
                   (owner_kind = 'HOUSE' AND owner_uuid IS NULL))
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS reservation_restored (
            operation_id TEXT NOT NULL,
            copy_uuid TEXT NOT NULL,
            PRIMARY KEY (operation_id, copy_uuid),
            FOREIGN KEY (operation_id, copy_uuid)
              REFERENCES reservation_copies(operation_id, copy_uuid) ON DELETE CASCADE
          )
          """);
      statement.execute("""
          CREATE TRIGGER IF NOT EXISTS active_copy_reservation_guard
          BEFORE INSERT ON reservation_copies
          WHEN EXISTS (
            SELECT 1 FROM reservation_copies existing
            JOIN reservations reservation ON reservation.operation_id = existing.operation_id
            WHERE existing.copy_uuid = NEW.copy_uuid
              AND reservation.status IN ('PREPARED', 'ESCROWED', 'RETURN_PENDING')
          )
          BEGIN
            SELECT RAISE(ABORT, 'copy is already reserved');
          END
          """);
      statement.execute("CREATE INDEX IF NOT EXISTS recovery_pending ON recovery_rows(status, owner_uuid)");
    }
  }
  private static void applyV2(Connection connection) throws SQLException {
    Map<Integer, Set<String>> foreignKeys = new HashMap<>();
    try (var query = connection.createStatement().executeQuery("PRAGMA foreign_key_list(recovery_rows)")) {
      while (query.next()) {
        foreignKeys.computeIfAbsent(query.getInt("id"), ignored -> new HashSet<>())
            .add(query.getString("from") + "->" + query.getString("to"));
      }
    }
    boolean composite = foreignKeys.values().stream().anyMatch(keys -> keys.contains("operation_id->operation_id")
        && keys.contains("copy_uuid->copy_uuid"));
    try (var statement = connection.createStatement()) {
      if (!composite) {
        try (var invalid = statement.executeQuery("""
            SELECT COUNT(*) FROM recovery_rows rr
            WHERE NOT EXISTS (
              SELECT 1 FROM reservations r
              JOIN reservation_copies rc ON rc.operation_id = r.operation_id AND rc.copy_uuid = rr.copy_uuid
              WHERE r.operation_id = rr.operation_id
                AND r.owner_kind = rr.owner_kind
                AND COALESCE(r.owner_uuid, '') = COALESCE(rr.owner_uuid, '')
            )
            """)) {
          if (invalid.next() && invalid.getLong(1) > 0) {
            throw new SQLException("recovery rows contain arbitrary copy or owner data");
          }
        }
        statement.execute("ALTER TABLE recovery_rows RENAME TO recovery_rows_legacy");
        statement.execute("DROP INDEX IF EXISTS recovery_pending");
        statement.execute("""
            CREATE TABLE recovery_rows (
              recovery_id INTEGER PRIMARY KEY AUTOINCREMENT,
              operation_id TEXT NOT NULL,
              copy_uuid TEXT NOT NULL,
              owner_kind TEXT NOT NULL CHECK (owner_kind IN ('PLAYER', 'HOUSE')),
              owner_uuid TEXT,
              item_bytes BLOB NOT NULL,
              status TEXT NOT NULL CHECK (status IN ('PENDING', 'RESTORED')),
              UNIQUE (operation_id, copy_uuid),
              FOREIGN KEY (operation_id) REFERENCES reservations(operation_id),
              FOREIGN KEY (operation_id, copy_uuid)
                REFERENCES reservation_copies(operation_id, copy_uuid),
              CHECK ((owner_kind = 'PLAYER' AND owner_uuid IS NOT NULL) OR
                     (owner_kind = 'HOUSE' AND owner_uuid IS NULL))
            )
            """);
      statement.execute("""
          INSERT INTO recovery_rows(recovery_id, operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status)
          SELECT recovery_id, operation_id, copy_uuid, owner_kind, owner_uuid, item_bytes, status
          FROM recovery_rows_legacy
          """);
        statement.execute("DROP TABLE recovery_rows_legacy");
      }
      statement.execute("""
          CREATE TABLE IF NOT EXISTS reservation_restored (
            operation_id TEXT NOT NULL,
            copy_uuid TEXT NOT NULL,
            PRIMARY KEY (operation_id, copy_uuid),
            FOREIGN KEY (operation_id, copy_uuid)
              REFERENCES reservation_copies(operation_id, copy_uuid) ON DELETE CASCADE
          )
          """);
      statement.execute("CREATE INDEX IF NOT EXISTS recovery_pending ON recovery_rows(status, owner_uuid)");
    }
  }
  private static void applyV3(Connection connection) throws SQLException {
    if (!tableColumns(connection, "reservations").contains("match_id")) {
      try (var statement = connection.createStatement()) {
        statement.execute("ALTER TABLE reservations ADD COLUMN match_id TEXT");
      }
    }
  }

  private static void applyV4(Connection connection) throws SQLException {
    if (!tableColumns(connection, "matches").contains("state_digest")) {
      try (var statement = connection.createStatement()) {
        statement.execute("ALTER TABLE matches ADD COLUMN state_digest TEXT");
      }
    }
  }

  private static void applyV5(Connection connection) throws SQLException {
    ensureStartBindings(connection);
  }

  private static void applyV6(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS active_copy_reservation_guard");
      statement.execute("""
          CREATE TRIGGER active_copy_reservation_guard
          BEFORE INSERT ON reservation_copies
          WHEN EXISTS (
            SELECT 1
            FROM reservations orphan_terminal
            WHERE orphan_terminal.status IN ('RETURNED', 'ABORTED')
              AND lower(orphan_terminal.operation_id) <> lower(NEW.operation_id)
              AND length(NEW.operation_id) = 36
              AND substr(NEW.operation_id, 9, 1) = '-'
              AND substr(NEW.operation_id, 14, 1) = '-'
              AND substr(NEW.operation_id, 19, 1) = '-'
              AND substr(NEW.operation_id, 24, 1) = '-'
              AND lower(NEW.operation_id) = NEW.operation_id
              AND NOT EXISTS (
                SELECT 1
                FROM reservation_copies expected
                WHERE lower(expected.operation_id) = lower(orphan_terminal.operation_id)
              )
          )
          OR EXISTS (
            SELECT 1
            FROM (
              SELECT operation_id, copy_uuid FROM reservation_copies
              UNION ALL
              SELECT operation_id, copy_uuid FROM escrow_items
              UNION ALL
              SELECT operation_id, copy_uuid FROM reservation_restored
              UNION ALL
              SELECT operation_id, copy_uuid FROM recovery_rows
            ) existing
            LEFT JOIN reservations reservation
              ON lower(reservation.operation_id) = lower(existing.operation_id)
            WHERE lower(existing.copy_uuid) = lower(NEW.copy_uuid)
              AND (
                existing.operation_id IS NULL
                OR length(existing.operation_id) <> 36
                OR substr(existing.operation_id, 9, 1) <> '-'
                OR substr(existing.operation_id, 14, 1) <> '-'
                OR substr(existing.operation_id, 19, 1) <> '-'
                OR substr(existing.operation_id, 24, 1) <> '-'
                OR existing.operation_id GLOB '*[^0-9A-Fa-f-]*'
                OR existing.operation_id <> lower(existing.operation_id)
                OR (SELECT COUNT(*) FROM reservations duplicate
                    WHERE lower(duplicate.operation_id) = lower(existing.operation_id)) <> 1
                OR reservation.status IS NULL
                OR reservation.status NOT IN ('RETURNED', 'ABORTED')
                OR reservation.owner_kind IS NULL
                OR reservation.owner_kind NOT IN ('PLAYER', 'HOUSE')
                OR (reservation.owner_kind = 'HOUSE' AND reservation.owner_uuid IS NOT NULL)
                OR (reservation.owner_kind = 'PLAYER' AND (
                  reservation.owner_uuid IS NULL
                  OR length(reservation.owner_uuid) <> 36
                  OR substr(reservation.owner_uuid, 9, 1) <> '-'
                  OR substr(reservation.owner_uuid, 14, 1) <> '-'
                  OR substr(reservation.owner_uuid, 19, 1) <> '-'
                  OR substr(reservation.owner_uuid, 24, 1) <> '-'
                  OR reservation.owner_uuid GLOB '*[^0-9A-Fa-f-]*'
                  OR reservation.owner_uuid <> lower(reservation.owner_uuid)
                ))
                OR EXISTS (
                  SELECT 1 FROM reservation_copies malformed
                  WHERE lower(malformed.operation_id) = lower(existing.operation_id)
                    AND (
                      malformed.copy_uuid IS NULL
                      OR length(malformed.copy_uuid) <> 36
                      OR substr(malformed.copy_uuid, 9, 1) <> '-'
                      OR substr(malformed.copy_uuid, 14, 1) <> '-'
                      OR substr(malformed.copy_uuid, 19, 1) <> '-'
                      OR substr(malformed.copy_uuid, 24, 1) <> '-'
                      OR malformed.copy_uuid GLOB '*[^0-9A-Fa-f-]*'
                      OR malformed.copy_uuid <> lower(malformed.copy_uuid)
                    )
                OR EXISTS (
                  SELECT 1 FROM (
                    SELECT operation_id, copy_uuid FROM reservation_copies
                    UNION ALL
                    SELECT operation_id, copy_uuid FROM escrow_items
                    UNION ALL
                    SELECT operation_id, copy_uuid FROM reservation_restored
                    UNION ALL
                    SELECT operation_id, copy_uuid FROM recovery_rows
                  ) child
                  WHERE lower(child.operation_id) = lower(existing.operation_id)
                    AND (
                      child.operation_id IS NULL
                      OR child.operation_id <> lower(child.operation_id)
                      OR child.copy_uuid IS NULL
                      OR length(child.copy_uuid) <> 36
                      OR substr(child.copy_uuid, 9, 1) <> '-'
                      OR substr(child.copy_uuid, 14, 1) <> '-'
                      OR substr(child.copy_uuid, 19, 1) <> '-'
                      OR substr(child.copy_uuid, 24, 1) <> '-'
                      OR child.copy_uuid GLOB '*[^0-9A-Fa-f-]*'
                      OR child.copy_uuid <> lower(child.copy_uuid)
                    )
                )
                )
                OR EXISTS (
                  SELECT 1 FROM reservation_copies first_copy
                  JOIN reservation_copies duplicate_copy
                    ON lower(first_copy.operation_id) = lower(duplicate_copy.operation_id)
                    AND lower(first_copy.copy_uuid) = lower(duplicate_copy.copy_uuid)
                    AND first_copy.rowid < duplicate_copy.rowid
                  WHERE lower(first_copy.operation_id) = lower(existing.operation_id)
                )
                OR NOT EXISTS (
                  SELECT 1
                  FROM reservation_copies expected
                  WHERE lower(expected.operation_id) = lower(existing.operation_id)
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_copies expected
                  WHERE lower(expected.operation_id) = lower(existing.operation_id)
                    AND NOT EXISTS (
                      SELECT 1
                      FROM card_copies ledger
                      WHERE lower(ledger.copy_uuid) = lower(expected.copy_uuid)
                        AND length(ledger.copy_uuid) = 36
                        AND substr(ledger.copy_uuid, 9, 1) = '-'
                        AND substr(ledger.copy_uuid, 14, 1) = '-'
                        AND substr(ledger.copy_uuid, 19, 1) = '-'
                        AND substr(ledger.copy_uuid, 24, 1) = '-'
                        AND ledger.copy_uuid GLOB '[0-9a-f-]*'
                        AND ledger.copy_uuid NOT GLOB '*[^0-9a-f-]*'
                        AND ledger.copy_uuid = lower(ledger.copy_uuid)
                        AND ledger.owner_kind = reservation.owner_kind
                        AND (
                          (ledger.owner_kind = 'HOUSE' AND ledger.owner_uuid IS NULL)
                          OR (ledger.owner_kind = 'PLAYER'
                            AND lower(ledger.owner_uuid) = lower(reservation.owner_uuid)
                            AND length(ledger.owner_uuid) = 36
                            AND substr(ledger.owner_uuid, 9, 1) = '-'
                            AND substr(ledger.owner_uuid, 14, 1) = '-'
                            AND substr(ledger.owner_uuid, 19, 1) = '-'
                            AND substr(ledger.owner_uuid, 24, 1) = '-'
                            AND ledger.owner_uuid GLOB '[0-9a-f-]*'
                            AND ledger.owner_uuid NOT GLOB '*[^0-9a-f-]*'
                            AND ledger.owner_uuid = lower(ledger.owner_uuid))
                        )
                    )
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_copies expected
                  WHERE lower(expected.operation_id) = lower(existing.operation_id)
                    AND (SELECT COUNT(*) FROM card_copies ledger
                         WHERE lower(ledger.copy_uuid) = lower(expected.copy_uuid)) <> 1
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_copies expected
                  LEFT JOIN escrow_items snapshot
                    ON lower(snapshot.operation_id) = lower(expected.operation_id)
                    AND lower(snapshot.copy_uuid) = lower(expected.copy_uuid)
                  WHERE lower(expected.operation_id) = lower(existing.operation_id)
                    AND (snapshot.copy_uuid IS NULL
                      OR snapshot.item_bytes IS NULL
                      OR length(snapshot.item_bytes) = 0)
                )
                OR EXISTS (
                  SELECT 1
                  FROM escrow_items snapshot
                  LEFT JOIN reservation_copies expected
                    ON lower(expected.operation_id) = lower(snapshot.operation_id)
                    AND lower(expected.copy_uuid) = lower(snapshot.copy_uuid)
                  WHERE lower(snapshot.operation_id) = lower(existing.operation_id)
                    AND (expected.copy_uuid IS NULL
                      OR snapshot.item_bytes IS NULL
                      OR length(snapshot.item_bytes) = 0)
                )
                OR EXISTS (
                  SELECT 1
                  FROM escrow_items first_snapshot
                  JOIN escrow_items duplicate_snapshot
                    ON lower(first_snapshot.operation_id) = lower(duplicate_snapshot.operation_id)
                    AND lower(first_snapshot.copy_uuid) = lower(duplicate_snapshot.copy_uuid)
                    AND first_snapshot.rowid < duplicate_snapshot.rowid
                  WHERE lower(first_snapshot.operation_id) = lower(existing.operation_id)
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_copies expected
                  LEFT JOIN reservation_restored restored
                    ON lower(restored.operation_id) = lower(expected.operation_id)
                    AND lower(restored.copy_uuid) = lower(expected.copy_uuid)
                  WHERE lower(expected.operation_id) = lower(existing.operation_id)
                    AND restored.copy_uuid IS NULL
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_restored restored
                  LEFT JOIN reservation_copies expected
                    ON lower(expected.operation_id) = lower(restored.operation_id)
                    AND lower(expected.copy_uuid) = lower(restored.copy_uuid)
                  WHERE lower(restored.operation_id) = lower(existing.operation_id)
                    AND expected.copy_uuid IS NULL
                )
                OR EXISTS (
                  SELECT 1
                  FROM reservation_restored first_restored
                  JOIN reservation_restored duplicate_restored
                    ON lower(first_restored.operation_id) = lower(duplicate_restored.operation_id)
                    AND lower(first_restored.copy_uuid) = lower(duplicate_restored.copy_uuid)
                    AND first_restored.rowid < duplicate_restored.rowid
                  WHERE lower(first_restored.operation_id) = lower(existing.operation_id)
                )
                OR EXISTS (
                  SELECT 1
                  FROM recovery_rows first_recovery
                  JOIN recovery_rows duplicate_recovery
                    ON lower(first_recovery.operation_id) = lower(duplicate_recovery.operation_id)
                    AND lower(first_recovery.copy_uuid) = lower(duplicate_recovery.copy_uuid)
                    AND first_recovery.rowid < duplicate_recovery.rowid
                  WHERE lower(first_recovery.operation_id) = lower(existing.operation_id)
                )
                OR EXISTS (
                  SELECT 1
                  FROM recovery_rows recovery
                  WHERE lower(recovery.operation_id) = lower(existing.operation_id)
                    AND (
                      recovery.status IS NULL
                      OR recovery.status NOT IN ('PENDING', 'RESTORED')
                      OR recovery.status = 'PENDING'
                      OR NOT EXISTS (
                        SELECT 1
                        FROM reservation_copies expected
                        WHERE lower(expected.operation_id) = lower(existing.operation_id)
                          AND lower(expected.copy_uuid) = lower(recovery.copy_uuid)
                      )
                      OR NOT EXISTS (
                        SELECT 1
                        FROM reservations owner_check
                        WHERE lower(owner_check.operation_id) = lower(existing.operation_id)
                          AND recovery.owner_kind = owner_check.owner_kind
                          AND (
                            (owner_check.owner_kind = 'HOUSE' AND recovery.owner_uuid IS NULL)
                            OR (owner_check.owner_kind = 'PLAYER'
                              AND recovery.owner_uuid IS NOT NULL
                              AND lower(recovery.owner_uuid) = lower(owner_check.owner_uuid))
                          )
                      )
                      OR NOT EXISTS (
                        SELECT 1
                        FROM escrow_items snapshot
                        WHERE lower(snapshot.operation_id) = lower(existing.operation_id)
                          AND lower(snapshot.copy_uuid) = lower(recovery.copy_uuid)
                          AND snapshot.item_bytes = recovery.item_bytes
                          AND recovery.item_bytes IS NOT NULL
                          AND length(recovery.item_bytes) > 0
                      )
                    )
                )
                OR (reservation.status IN ('RETURNED', 'ABORTED')
                    AND EXISTS (
                      SELECT 1
                      FROM reservation_copies expected
                      LEFT JOIN recovery_rows proof
                        ON lower(proof.operation_id) = lower(expected.operation_id)
                        AND lower(proof.copy_uuid) = lower(expected.copy_uuid)
                        AND proof.status = 'RESTORED'
                        AND proof.owner_kind = reservation.owner_kind
                        AND (
                          (reservation.owner_kind = 'HOUSE' AND proof.owner_uuid IS NULL)
                          OR (reservation.owner_kind = 'PLAYER'
                            AND lower(proof.owner_uuid) = lower(reservation.owner_uuid))
                        )
                        AND EXISTS (
                          SELECT 1
                          FROM escrow_items snapshot
                          WHERE lower(snapshot.operation_id) = lower(expected.operation_id)
                            AND lower(snapshot.copy_uuid) = lower(expected.copy_uuid)
                            AND snapshot.item_bytes = proof.item_bytes
                            AND proof.item_bytes IS NOT NULL
                            AND length(proof.item_bytes) > 0
                        )
                      WHERE lower(expected.operation_id) = lower(existing.operation_id)
                        AND proof.copy_uuid IS NULL
                    ))
              )
          )
          BEGIN
            SELECT RAISE(ABORT, 'copy is not proven reusable');
          END
          """);
    }
    try (var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS active_copy_identity_integrity_guard");
      String invalidNewOperation = canonicalUuidViolation("NEW.operation_id", false);
      String invalidNewCopy = canonicalUuidViolation("NEW.copy_uuid", false);
      String invalidLedgerCopy = canonicalUuidViolation("row.copy_uuid", false);
      String invalidLedgerOwner = canonicalUuidViolation("row.owner_uuid", true);
      String invalidReservationOperation = canonicalUuidViolation("row.operation_id", false);
      String invalidReservationOwner = canonicalUuidViolation("row.owner_uuid", true);
      String invalidBindingOperation = canonicalUuidViolation("row.one_operation_id", false)
          + " OR " + canonicalUuidViolation("row.two_operation_id", false);
      String invalidBindingOwner = canonicalUuidViolation("row.one_owner_uuid", true)
          + " OR " + canonicalUuidViolation("row.two_owner_uuid", true);
      String invalidChildOperation = canonicalUuidViolation("row.operation_id", false);
      String invalidChildCopy = canonicalUuidViolation("row.copy_uuid", false);
      statement.execute("""
          CREATE TRIGGER active_copy_identity_integrity_guard
          BEFORE INSERT ON reservation_copies
          WHEN %s
            OR %s
            OR EXISTS (
              SELECT 1 FROM card_copies row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM reservations row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM reservation_copies row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM escrow_items row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM reservation_restored row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM match_start_bindings row
              WHERE %s OR %s
            )
            OR EXISTS (
              SELECT 1 FROM recovery_rows row
              WHERE %s OR %s OR %s
            )
          BEGIN
            SELECT RAISE(ABORT, 'custody identity is not canonical');
          END
          """.formatted(
          invalidNewOperation,
          invalidNewCopy,
          invalidLedgerCopy,
          invalidLedgerOwner,
          invalidReservationOperation,
          invalidReservationOwner,
          invalidChildOperation,
          invalidChildCopy,
          invalidChildOperation,
          invalidChildCopy,
          invalidChildOperation,
          invalidChildCopy,
          invalidBindingOperation,
          invalidBindingOwner,
          invalidChildOperation,
          invalidChildCopy,
          invalidReservationOwner));
    }
  }
  /** Reinstalls the reservation guard for databases already marked through V6. */
  private static void applyV7(Connection connection) throws SQLException {
    applyV6(connection);
  }

  private static boolean hasRequiredCopyUuidIndex(Connection connection) throws SQLException {
    try (var query = connection.createStatement().executeQuery("PRAGMA index_list(card_copies)")) {
      while (query.next()) {
        if ("card_copies_copy_uuid_ci".equals(query.getString("name")) && query.getInt("unique") == 1) {
          return true;
        }
      }
    }
    return false;
  }

  private record IdentityColumn(String table, String column, boolean nullable) {}

  private static String canonicalUuidViolation(String column, boolean nullable) {
    String violation = "(" + column + " IS NULL"
        + " OR length(" + column + ") <> 36"
        + " OR substr(" + column + ", 9, 1) <> '-'"
        + " OR substr(" + column + ", 14, 1) <> '-'"
        + " OR substr(" + column + ", 19, 1) <> '-'"
        + " OR substr(" + column + ", 24, 1) <> '-'"
        + " OR length(replace(" + column + ", '-', '')) <> 32"
        + " OR replace(" + column + ", '-', '') GLOB '*[^0-9a-f]*'"
        + " OR " + column + " <> lower(" + column + "))";
    return nullable ? "(" + column + " IS NOT NULL AND " + violation + ")" : violation;
  }

  private static void rejectInvalidUuidColumns(Connection connection, IdentityColumn... columns)
      throws SQLException {
    for (IdentityColumn identity : columns) {
      String sql = "SELECT " + identity.column() + " FROM " + identity.table()
          + " WHERE " + canonicalUuidViolation(identity.column(), identity.nullable());
      try (var query = connection.createStatement(); var rows = query.executeQuery(sql)) {
        if (rows.next()) {
          throw new SQLException("noncanonical " + identity.table() + "." + identity.column()
              + " UUID: " + rows.getString(1));
        }
      }
    }
  }

  private static void rejectInvalidCopyListColumns(Connection connection, String... columns)
      throws SQLException {
    for (String column : columns) {
      try (var query = connection.createStatement().executeQuery(
          "SELECT " + column + " FROM match_start_bindings")) {
        while (query.next()) {
          String raw = query.getString(1);
          if (raw == null || raw.isEmpty()) continue;
          for (String copy : raw.split(",", -1)) {
            try {
              SqliteCardRepository.parseCanonicalUuid(copy);
            } catch (RuntimeException exception) {
              throw new SQLException("noncanonical match_start_bindings." + column
                  + " copy UUID: " + copy, exception);
            }
          }
        }
      }
    }
  }

  private static void applyV8(Connection connection) throws SQLException {
    rejectInvalidUuidColumns(connection,
        new IdentityColumn("card_copies", "copy_uuid", false),
        new IdentityColumn("card_copies", "owner_uuid", true),
        new IdentityColumn("reservations", "operation_id", false),
        new IdentityColumn("reservations", "owner_uuid", true),
        new IdentityColumn("reservation_copies", "operation_id", false),
        new IdentityColumn("reservation_copies", "copy_uuid", false),
        new IdentityColumn("escrow_items", "operation_id", false),
        new IdentityColumn("escrow_items", "copy_uuid", false),
        new IdentityColumn("reservation_restored", "operation_id", false),
        new IdentityColumn("reservation_restored", "copy_uuid", false),
        new IdentityColumn("recovery_rows", "operation_id", false),
        new IdentityColumn("recovery_rows", "copy_uuid", false),
        new IdentityColumn("recovery_rows", "owner_uuid", true),
        new IdentityColumn("match_start_bindings", "one_owner_uuid", true),
        new IdentityColumn("match_start_bindings", "one_operation_id", false),
        new IdentityColumn("match_start_bindings", "two_owner_uuid", true),
        new IdentityColumn("match_start_bindings", "two_operation_id", false));
    rejectInvalidCopyListColumns(connection, "one_copy_ids", "two_copy_ids");
    try (var query = connection.createStatement().executeQuery("""
        SELECT lower(copy_uuid), COUNT(*)
        FROM card_copies
        GROUP BY lower(copy_uuid)
        HAVING COUNT(*) > 1
        """)) {
      if (query.next()) {
        throw new SQLException("duplicate logical card-copy UUID: " + query.getString(1));
      }
    }
    try (var statement = connection.createStatement()) {
      statement.execute("""
          CREATE UNIQUE INDEX IF NOT EXISTS card_copies_copy_uuid_ci
          ON card_copies(lower(copy_uuid))
          """);
    }
  }
}
