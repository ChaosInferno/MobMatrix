package org.aincraft.mobmatrix.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchState;

/** Persistence boundary for immutable match snapshots with optimistic revisions. */
public interface MatchRepository {
  SaveResult save(long expectedRevision, MatchState state);

  Optional<MatchSnapshot> find(MatchId matchId);

  default Optional<MatchSnapshot> load(MatchId matchId) {
    return find(matchId);
  }

  default List<MatchSnapshot> activeMatches() {
    return List.of();
  }

  default void delete(MatchId matchId) {}

  record SaveResult(boolean saved, long revision, Failure failure) {
    public SaveResult {
      Objects.requireNonNull(failure, "failure is required");
    }

    public static SaveResult success(long revision) { return new SaveResult(true, revision, Failure.NONE); }
    public static SaveResult stale(long revision) { return new SaveResult(false, revision, Failure.STALE_REVISION); }
    public boolean accepted() { return saved; }
  }

  enum Failure {
    NONE,
    STALE_REVISION,
    DATABASE_ERROR,
    INVALID_STATE
  }

  record MatchSnapshot(MatchId matchId, long revision, String status, byte[] stateBytes,
      String transcriptJson) {
    public MatchSnapshot {
      Objects.requireNonNull(matchId); Objects.requireNonNull(status);
      Objects.requireNonNull(stateBytes); stateBytes = stateBytes.clone();
      Objects.requireNonNull(transcriptJson);
      if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    @Override
    public byte[] stateBytes() { return stateBytes.clone(); }
  }
}
