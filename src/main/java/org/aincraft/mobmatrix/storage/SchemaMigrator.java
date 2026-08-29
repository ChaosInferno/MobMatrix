package org.aincraft.mobmatrix.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit, repeatable schema migrations for custody and active-match state. */
public final class SchemaMigrator {
  public static final int CURRENT_VERSION = 1;

  private SchemaMigrator() {}

  public static void migrate(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection is required");
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
    }
    int version = currentVersion(connection);
    if (version < 1) {
      boolean autoCommit = connection.getAutoCommit();
      try {
        connection.setAutoCommit(false);
        applyV1(connection);
        try (var insert = connection.prepareStatement(
            "INSERT INTO schema_migrations(version, applied_at) VALUES (1, CURRENT_TIMESTAMP)")) {
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException exception) {
        try { connection.rollback(); } catch (SQLException ignored) { }
        throw exception;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    }
    if (currentVersion(connection) > CURRENT_VERSION) {
      throw new SQLException("database schema is newer than this plugin");
    }
  }

  public static int currentVersion(Connection connection) throws SQLException {
    try (var query = connection.createStatement();
        var rows = query.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
      return rows.next() ? rows.getInt(1) : 0;
    }
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
            operation_id TEXT NOT NULL REFERENCES reservations(operation_id),
            copy_uuid TEXT NOT NULL,
            owner_kind TEXT NOT NULL CHECK (owner_kind IN ('PLAYER', 'HOUSE')),
            owner_uuid TEXT,
            item_bytes BLOB NOT NULL,
            status TEXT NOT NULL CHECK (status IN ('PENDING', 'RESTORED')),
            UNIQUE (operation_id, copy_uuid),
            CHECK ((owner_kind = 'PLAYER' AND owner_uuid IS NOT NULL) OR
                   (owner_kind = 'HOUSE' AND owner_uuid IS NULL))
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
}
