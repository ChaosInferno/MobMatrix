package org.aincraft.mobmatrix.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.aincraft.mobmatrix.application.MatchRepository;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.MatchState;
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
          try (var insert = connection.prepareStatement("""
              INSERT INTO matches(match_id, revision, status, state_bytes, transcript_json, updated_at)
              VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
              """)) {
            insert.setString(1, state.matchId().value());
            insert.setLong(2, state.revision());
            insert.setString(3, state.isFinished() ? "FINISHED" : "ACTIVE");
            insert.setBytes(4, stateBytes);
            insert.setString(5, transcript);
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
            UPDATE matches SET revision = ?, status = ?, state_bytes = ?, transcript_json = ?,
              updated_at = CURRENT_TIMESTAMP WHERE match_id = ? AND revision = ?
            """)) {
          update.setLong(1, state.revision());
          update.setString(2, state.isFinished() ? "FINISHED" : "ACTIVE");
          update.setBytes(3, stateBytes);
          update.setString(4, transcript);
          update.setString(5, state.matchId().value());
          update.setLong(6, expectedRevision);
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

  @Override
  public Optional<MatchSnapshot> find(org.aincraft.mobmatrix.domain.MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    try (var query = connection.prepareStatement(
        "SELECT revision, status, state_bytes, transcript_json FROM matches WHERE match_id = ?")) {
      query.setString(1, matchId.value());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(new MatchSnapshot(matchId, rows.getLong(1), rows.getString(2),
            rows.getBytes(3), rows.getString(4)));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("SQLite match load failed", exception);
    }
  }

  /** Rehydrates an executable aggregate after resolving the persisted catalog snapshot. */
  public Optional<MatchState> findState(org.aincraft.mobmatrix.domain.MatchId matchId,
      org.aincraft.mobmatrix.domain.CardCatalog catalog) {
    return find(matchId).map(snapshot -> MatchStateSnapshotCodec.decode(snapshot.stateBytes(), catalog));
  }

  public MatchState rehydrate(MatchSnapshot snapshot,
      org.aincraft.mobmatrix.domain.CardCatalog catalog) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    return MatchStateSnapshotCodec.decode(snapshot.stateBytes(), catalog);
  }
  @Override
  public Optional<MatchState> rehydrate(MatchSnapshot snapshot,
      java.util.function.Function<String, org.aincraft.mobmatrix.domain.CardCatalog> catalogResolver) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    var catalog = Objects.requireNonNull(catalogResolver.apply(
        MatchStateSnapshotCodec.catalogSnapshotId(snapshot.stateBytes())), "catalog is required");
    return Optional.of(rehydrate(snapshot, catalog));
  }

  @Override
  public List<MatchSnapshot> activeMatches() {
    try (var query = connection.prepareStatement(
        "SELECT match_id, revision, status, state_bytes, transcript_json FROM matches WHERE status = 'ACTIVE'")) {
      List<MatchSnapshot> result = new ArrayList<>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          result.add(new MatchSnapshot(new org.aincraft.mobmatrix.domain.MatchId(rows.getString(1)), rows.getLong(2),
              rows.getString(3), rows.getBytes(4), rows.getString(5)));
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
