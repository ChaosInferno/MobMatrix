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
  public static final int CURRENT_VERSION = 5;

  private SchemaMigrator() {}

  public static void migrate(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection is required");
    ensureMigrationTable(connection);
    Set<Integer> markers = appliedMarkers(connection);
    int version = contiguousVersion(markers);
    for (int next = version + 1; next <= CURRENT_VERSION; next++) {
      applyMigration(connection, next);
    }
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
        default -> throw new SQLException("unsupported schema migration: " + version);
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
}
