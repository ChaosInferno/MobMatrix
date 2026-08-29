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
    byte[] stateBytes = serialize(state);
    String transcript = transcriptJson(state);
    try {
      connection.setAutoCommit(false);
      try {
        long current = currentRevision(state.matchId().value());
        if (current < 0) {
          if (expectedRevision != state.revision()) {
            connection.rollback();
            return SaveResult.stale(-1);
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
          return SaveResult.success(state.revision());
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
        if (exception instanceof RuntimeException runtime) throw runtime;
        throw exception;
      } finally {
        connection.setAutoCommit(true);
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

  private static byte[] serialize(MatchState state) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(1);
        out.writeUTF(state.matchId().value());
        out.writeLong(state.revision());
        out.writeUTF(state.currentSeat().name());
        out.writeUTF(state.catalog().snapshotId());
        out.writeUTF(state.rules().rules().stream().map(Enum::name).sorted().toList().toString());
        out.writeLong(state.turnStartedAt().toEpochMilli());
        out.writeLong(state.graceDeadline().toEpochMilli());
        out.writeLong(state.turnDeadline().toEpochMilli());
        for (PlayerSeat seat : PlayerSeat.values()) {
          out.writeLong(state.remainingBankMillis(seat));
          out.writeInt(state.hand(seat).size());
          for (var hand : state.hand(seat)) {
            out.writeLong(hand.copyId().value().getMostSignificantBits());
            out.writeLong(hand.copyId().value().getLeastSignificantBits());
            out.writeInt(hand.cardId().value());
          }
        }
        for (Board.Cell cell : state.board().cells()) {
          out.writeBoolean(cell != null);
          if (cell != null) {
            out.writeLong(cell.copy().copyId().value().getMostSignificantBits());
            out.writeLong(cell.copy().copyId().value().getLeastSignificantBits());
            out.writeInt(cell.cardId().value());
            out.writeUTF(cell.owner().name());
            for (Side side : Side.values()) out.writeInt(cell.effectiveValues().getOrDefault(side, 0));
          }
        }
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("unable to serialize match", exception);
    }
  }

  private static String transcriptJson(MatchState state) {
    StringBuilder json = new StringBuilder("[");
    boolean first = true;
    for (var decision : state.randomDecisions()) {
      if (!first) json.append(',');
      first = false;
      json.append("{\"decision\":\"").append(escape(decision.decision())).append("\",\"bound\":")
          .append(decision.bound()).append(",\"value\":").append(decision.value()).append('}');
    }
    return json.append(']').toString();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
