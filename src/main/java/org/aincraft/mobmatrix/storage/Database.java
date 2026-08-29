package org.aincraft.mobmatrix.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/** Owned SQLite connection configured for safe concurrent persistence. */
public final class Database implements AutoCloseable {
  private final Connection connection;
  private final boolean closeConnection;

  public Database(Path path) {
    this(open(path), true);
  }

  public Database(String path) {
    this(openString(path), true);
  }

  public Database() {
    this(openString(":memory:"), true);
  }

  private static Connection openString(String path) {
    Objects.requireNonNull(path, "database path is required");
    if (":memory:".equals(path)) {
      try {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
      } catch (SQLException exception) {
        throw new IllegalStateException("unable to open SQLite database", exception);
      }
    }
    return open(Path.of(path));
  }

  public Database(Connection connection) {
    this(connection, true);
  }

  private Database(Connection connection, boolean closeConnection) {
    this.connection = Objects.requireNonNull(connection, "connection is required");
    this.closeConnection = closeConnection;
    try {
      configure(connection);
      SchemaMigrator.migrate(connection);
    } catch (SQLException exception) {
      closeQuietly(connection);
      throw new IllegalStateException("unable to configure SQLite database", exception);
    }
  }

  public Connection connection() {
    return connection;
  }

  public void migrate() {
    try {
      SchemaMigrator.migrate(connection);
    } catch (SQLException exception) {
      throw new IllegalStateException("unable to migrate SQLite database", exception);
    }
  }

  @Override
  public void close() {
    if (!closeConnection) return;
    closeQuietly(connection);
  }

  private static Connection open(Path path) {
    Objects.requireNonNull(path, "database path is required");
    try {
      Path absolute = path.toAbsolutePath();
      Path parent = absolute.getParent();
      if (parent != null) Files.createDirectories(parent);
      return DriverManager.getConnection("jdbc:sqlite:" + absolute);
    } catch (Exception exception) {
      throw new IllegalStateException("unable to open SQLite database", exception);
    }
  }

  private static void configure(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = ON");
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA synchronous = NORMAL");
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ignored) {
      // The original database error is more useful to callers.
    }
  }
}
