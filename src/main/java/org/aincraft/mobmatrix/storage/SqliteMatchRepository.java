package org.aincraft.mobmatrix.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.aincraft.mobmatrix.application.MatchRepository;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Side;

/** Optimistic-CAS repository for immutable active match snapshots and random transcripts. */
public final class SqliteMatchRepository implements MatchRepository {
  private final Connection connection;

  public SqliteMatchRepository(Database database) {
    this(Objects.requireNonNull(database, "database is required").connection());
  }

  public SqliteMatchRepository(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection is required");
  }

  @Override
  public SaveResult save(long expectedRevision, MatchState state) {
    Objects.requireNonNull(state, "state is required");
    if (expectedRevision < 0) return new SaveResult(false, -1, Failure.INVALID_STATE);
    byte[] stateBytes = MatchStateSnapshotCodec.encode(state);
    String transcript = transcriptJson(state);
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        long current = currentRevision(state.matchId().value());
        if (current < 0) {
          if (expectedRevision != 0 || state.revision() != 0) {
            connection.rollback();
            return new SaveResult(false, -1, Failure.INVALID_STATE);
          }
          persistCatalogSnapshot(connection, state.catalog());
          try (var insert = connection.prepareStatement("""
              INSERT INTO matches(match_id, revision, status, state_bytes, state_digest, transcript_json, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
              """)) {
            insert.setString(1, state.matchId().value());
            insert.setLong(2, state.revision());
            insert.setString(3, state.isFinished() ? "FINISHED" : "ACTIVE");
            insert.setBytes(4, stateBytes);
            insert.setString(5, digest(stateBytes));
            insert.setString(6, transcript);
            insert.executeUpdate();
          }
          connection.commit();
          return SaveResult.success(0L);
        }
        if (state.revision() != expectedRevision + 1) {
          connection.rollback();
          return new SaveResult(false, current, Failure.INVALID_STATE);
        }
        if (current != expectedRevision) {
          connection.rollback();
          return SaveResult.stale(current);
        }
        try (var update = connection.prepareStatement("""
            UPDATE matches SET revision = ?, status = ?, state_bytes = ?, state_digest = ?,
              transcript_json = ?, updated_at = CURRENT_TIMESTAMP WHERE match_id = ? AND revision = ?
            """)) {
          update.setLong(1, state.revision());
          update.setString(2, state.isFinished() ? "FINISHED" : "ACTIVE");
          update.setBytes(3, stateBytes);
          update.setString(4, digest(stateBytes));
          update.setString(5, transcript);
          update.setString(6, state.matchId().value());
          update.setLong(7, expectedRevision);
          if (update.executeUpdate() != 1) {
            connection.rollback();
            return SaveResult.stale(current);
          }
        }
        connection.commit();
        return SaveResult.success(state.revision());
      } catch (SQLException | RuntimeException exception) {
        try { connection.rollback(); } catch (SQLException ignored) { }
        if (exception instanceof SQLException sql
            && sql.getMessage() != null && sql.getMessage().contains("matches.match_id")) {
          return new SaveResult(false, -1L, Failure.INVALID_STATE);
        }
        if (exception instanceof RuntimeException runtime) throw runtime;
        throw exception;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite match save failed", exception);
    }
  }
  /**
   * Inserts the revision-0 match and its durable start identity in one SQLite transaction.
   */
  @Override
  public SaveResult saveInitial(MatchState state, StartBinding binding) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(binding, "start binding is required");
    if (state.revision() != 0 || !state.matchId().equals(binding.matchId())) {
      return new SaveResult(false, -1L, Failure.INVALID_STATE);
    }
    byte[] stateBytes = MatchStateSnapshotCodec.encode(state);
    String transcript = transcriptJson(state);
    try {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        long current = currentRevision(state.matchId().value());
        if (current >= 0) {
          connection.rollback();
          return SaveResult.stale(current);
        }
        persistCatalogSnapshot(connection, state.catalog());
        try (var insert = connection.prepareStatement("""
            INSERT INTO matches(match_id, revision, status, state_bytes, state_digest, transcript_json, updated_at)
            VALUES (?, 0, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
          insert.setString(1, state.matchId().value());
          insert.setString(2, state.isFinished() ? "FINISHED" : "ACTIVE");
          insert.setBytes(3, stateBytes);
          insert.setString(4, digest(stateBytes));
          insert.setString(5, transcript);
          insert.executeUpdate();
        }
        insertStartBinding(connection, binding);
        connection.commit();
        return SaveResult.success(0L);
      } catch (SQLException | RuntimeException exception) {
        try { connection.rollback(); } catch (SQLException ignored) { }
        if (exception instanceof RuntimeException runtime) throw runtime;
        throw exception;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite initial match save failed", exception);
    }
  }

  @Override
  public Optional<MatchSnapshot> find(org.aincraft.mobmatrix.domain.MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    try (var query = connection.prepareStatement(
        "SELECT revision, status, state_bytes, transcript_json, state_digest FROM matches WHERE match_id = ?")) {
      query.setString(1, matchId.value());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new MatchSnapshot(matchId, rows.getLong(1), rows.getString(2),
            rows.getBytes(3), rows.getString(4), rows.getString(5)));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite match load failed", exception);
    }
  }

  /** Rehydrates only when a persisted catalog definition set exactly matches the caller's catalog. */
  @Override
  public Optional<MatchState> findState(org.aincraft.mobmatrix.domain.MatchId matchId,
      CardCatalog catalog) {
    Objects.requireNonNull(matchId, "match id is required");
    Objects.requireNonNull(catalog, "catalog is required");
    return find(matchId).map(snapshot -> {
      verifyIntegrity(snapshot);
      if (!snapshot.matchId().equals(matchId)) {
        throw new IllegalArgumentException("snapshot row match ID does not match request");
      }
      requirePersistedCatalogMatch(MatchStateSnapshotCodec.catalogSnapshotId(snapshot.stateBytes()), catalog);
      MatchState state = MatchStateSnapshotCodec.decode(snapshot.stateBytes(), catalog);
      if (!state.matchId().equals(matchId)) {
        throw new IllegalArgumentException("snapshot state match ID does not match row");
      }
      if (state.revision() != snapshot.revision()) {
        throw new IllegalArgumentException("snapshot state revision does not match row");
      }
      return state;
    });
  }
  public MatchState rehydrate(MatchSnapshot snapshot, CardCatalog catalog) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(catalog, "catalog is required");
    verifyIntegrity(snapshot);
    String snapshotCatalogId = MatchStateSnapshotCodec.catalogSnapshotId(snapshot.stateBytes());
    requirePersistedCatalogMatch(snapshotCatalogId, catalog);
    MatchState state = MatchStateSnapshotCodec.decode(snapshot.stateBytes(), catalog);
    if (!state.matchId().equals(snapshot.matchId())) {
      throw new IllegalArgumentException("snapshot state match ID does not match row");
    }
    if (state.revision() != snapshot.revision()) {
      throw new IllegalArgumentException("snapshot state revision does not match row");
    }
    return state;
  }
  public Optional<MatchState> rehydrate(MatchSnapshot snapshot,
      java.util.function.Function<String, CardCatalog> catalogResolver) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    verifyIntegrity(snapshot);
    var catalog = Objects.requireNonNull(catalogResolver.apply(
        MatchStateSnapshotCodec.catalogSnapshotId(snapshot.stateBytes())), "catalog is required");

    return Optional.of(rehydrate(snapshot, catalog));
  }
  private static void persistCatalogSnapshot(Connection connection, CardCatalog catalog)
      throws SQLException {
    SqliteCardRepository.persistCatalogSnapshotForMatch(connection, catalog.snapshotId(), catalog.all());
  }
  private void requirePersistedCatalogMatch(String snapshotId, CardCatalog catalog) {
    try {
      boolean persisted;
      try (var query = connection.prepareStatement(
          "SELECT 1 FROM catalog_snapshots WHERE snapshot_id = ?")) {
        query.setString(1, snapshotId);
        try (var rows = query.executeQuery()) {
          persisted = rows.next();
        }
      }
      if (!persisted) {
        throw new IllegalArgumentException("persisted catalog snapshot is absent");
      }
      List<CardDefinition> definitions = new ArrayList<>();
      try (var query = connection.prepareStatement("""
          SELECT catalog_id, public_number, name, stars, type, top_value, right_value,
            bottom_value, left_value, sale_price
          FROM card_definitions WHERE snapshot_id = ? ORDER BY catalog_id
          """)) {
        query.setString(1, snapshotId);
        try (var rows = query.executeQuery()) {
          while (rows.next()) {
            definitions.add(new CardDefinition(new org.aincraft.mobmatrix.domain.CardId(rows.getInt(1)),
                rows.getString(2), rows.getString(3), rows.getInt(4),
                CardType.valueOf(rows.getString(5)), rows.getInt(6), rows.getInt(7),
                rows.getInt(8), rows.getInt(9), rows.getInt(10)));
          }
        }
      }
      if (!definitions.equals(new ArrayList<>(catalog.all()))) {
        throw new IllegalArgumentException("catalog definitions do not match persisted snapshot");
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite catalog lookup failed", exception);
    }
  }


  @Override
  public List<MatchSnapshot> activeMatches() {
    try (var query = connection.prepareStatement(
        "SELECT match_id, revision, status, state_bytes, transcript_json, state_digest FROM matches WHERE status = 'ACTIVE'")) {
      List<MatchSnapshot> result = new ArrayList<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          result.add(new MatchSnapshot(new org.aincraft.mobmatrix.domain.MatchId(rows.getString(1)), rows.getLong(2),
              rows.getString(3), rows.getBytes(4), rows.getString(5), rows.getString(6)));
        }
      }
      return List.copyOf(result);
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite active match load failed", exception);
    }
  }

  @Override
  public void delete(org.aincraft.mobmatrix.domain.MatchId matchId) {
    try (var delete = connection.prepareStatement("DELETE FROM matches WHERE match_id = ?")) {
      delete.setString(1, Objects.requireNonNull(matchId).value());
      delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite match delete failed", exception);
    }
  }
  @Override
  public Optional<StartBinding> findStartBinding(org.aincraft.mobmatrix.domain.MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    try (var query = connection.prepareStatement("""
        SELECT binding_version, request_identity, one_owner_kind, one_owner_uuid,
          one_operation_id, one_copy_ids, two_owner_kind, two_owner_uuid,
          two_operation_id, two_copy_ids
        FROM match_start_bindings WHERE match_id = ?
        """)) {
      query.setString(1, matchId.value());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new StartBinding(matchId, rows.getInt(1), rows.getString(2),
            readOwner(rows.getString(3), rows.getString(4)), readCopies(rows.getString(6)),
            java.util.UUID.fromString(rows.getString(5)),
            readOwner(rows.getString(7), rows.getString(8)), readCopies(rows.getString(10)),
            java.util.UUID.fromString(rows.getString(9))));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite start binding load failed", exception);
    }
  }

  @Override
  public void saveStartBinding(StartBinding binding) {
    Objects.requireNonNull(binding, "start binding is required");
    try {
      Optional<StartBinding> existing = findStartBinding(binding.matchId());
      if (existing.isPresent()) {
        if (!existing.get().equals(binding)) {
          throw new IllegalArgumentException("start binding identity mismatch");
        }
        return;
      }
      insertStartBinding(connection, binding);
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite start binding save failed", exception);
    }
  }

  private static void insertStartBinding(Connection connection, StartBinding binding)
      throws SQLException {
    try (var insert = connection.prepareStatement("""
        INSERT INTO match_start_bindings(match_id, binding_version, request_identity,
          one_owner_kind, one_owner_uuid, one_operation_id, one_copy_ids,
          two_owner_kind, two_owner_uuid, two_operation_id, two_copy_ids)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      insert.setString(1, binding.matchId().value());
      insert.setInt(2, binding.version());
      insert.setString(3, binding.requestIdentity());
      writeOwner(insert, 4, 5, binding.oneOwner());
      insert.setString(6, binding.oneOperationId().toString());
      insert.setString(7, writeCopies(binding.oneCopies()));
      writeOwner(insert, 8, 9, binding.twoOwner());
      insert.setString(10, binding.twoOperationId().toString());
      insert.setString(11, writeCopies(binding.twoCopies()));
      insert.executeUpdate();
    }
  }

  private static String writeCopies(List<org.aincraft.mobmatrix.domain.CardCopyId> copies) {
    return copies.stream().map(copy -> copy.value().toString()).collect(java.util.stream.Collectors.joining(","));
  }

  private static List<org.aincraft.mobmatrix.domain.CardCopyId> readCopies(String value) {
    if (value == null || value.isEmpty()) return List.of();
    return java.util.Arrays.stream(value.split(",", -1))
        .map(text -> new org.aincraft.mobmatrix.domain.CardCopyId(java.util.UUID.fromString(text))).toList();
  }

  private static org.aincraft.mobmatrix.domain.CopyOwner readOwner(String kind, String uuid) {
    return "HOUSE".equals(kind) ? org.aincraft.mobmatrix.domain.CopyOwner.house()
        : org.aincraft.mobmatrix.domain.CopyOwner.player(java.util.UUID.fromString(uuid));
  }


  private static void writeOwner(java.sql.PreparedStatement statement, int kindIndex,
      int uuidIndex, org.aincraft.mobmatrix.domain.CopyOwner owner) throws SQLException {
    if (owner.isPlayer()) {
      statement.setString(kindIndex, "PLAYER");
      statement.setString(uuidIndex, owner.playerId().toString());
    } else {
      statement.setString(kindIndex, "HOUSE");
      statement.setNull(uuidIndex, java.sql.Types.VARCHAR);
    }
  }

  private static String digest(byte[] stateBytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(stateBytes);
      char[] hex = "0123456789abcdef".toCharArray();
      StringBuilder result = new StringBuilder("sha256-v1:");
      for (byte value : hash) {
        int unsigned = value & 0xff;
        result.append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void verifyIntegrity(MatchSnapshot snapshot) {
    String persisted = snapshot.stateDigest();
    if (persisted == null || persisted.isBlank()
        || !MessageDigest.isEqual(persisted.getBytes(StandardCharsets.US_ASCII),
            digest(snapshot.stateBytes()).getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("match snapshot integrity verification failed");
    }
  }

  private long currentRevision(String matchId) throws SQLException {
    try (var query = connection.prepareStatement("SELECT revision FROM matches WHERE match_id = ?")) {
      try (var rows = query.executeQuery()) {
        return rows.next() ? rows.getLong(1) : -1L;
      }
    }
  }

  private static String transcriptJson(MatchState state) {
    var transcript = state.transcript();
    StringBuilder json = new StringBuilder("{\"seed\":").append(transcript.seed())
        .append(",\"cursor\":").append(transcript.cursor())
        .append(",\"replay\":").append(transcript.replayMode()).append(",\"decisions\":[");
    boolean first = true;
    for (var decision : transcript.decisions()) {
      if (!first) json.append(',');
      first = false;
      json.append("{\"decision\":\"").append(escape(decision.decision())).append("\",\"bound\":")
          .append(decision.bound()).append(",\"value\":").append(decision.value()).append('}');
    }
    return json.append("]}").toString();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
