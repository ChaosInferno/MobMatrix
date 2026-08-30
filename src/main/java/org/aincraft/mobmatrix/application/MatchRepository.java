package org.aincraft.mobmatrix.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchState;

/** Persistence boundary for immutable match snapshots with optimistic revisions. */
public interface MatchRepository {
  SaveResult save(long expectedRevision, MatchState state);

  /**
   * Persists a revision-0 active match and its durable start binding atomically.
   *
   * <p>Repositories that cannot commit both records as one operation must reject the request
   * without writing either record. Implementations backed by durable storage should override this
   * method with a transaction.
   */
  default SaveResult saveInitial(MatchState state, StartBinding binding) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(binding, "start binding is required");
    return new SaveResult(false, -1L, Failure.INVALID_STATE);
  }

  Optional<MatchSnapshot> find(MatchId matchId);

  /** Loads the executable state needed to compare an idempotent start request. */
  default Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
    Objects.requireNonNull(matchId, "match id is required");
    Objects.requireNonNull(catalog, "catalog is required");
    return Optional.empty();
  }

  /** Durable identity captured with the initial active snapshot for idempotent start retries. */
  default Optional<StartBinding> findStartBinding(MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    return Optional.empty();
  }

  default void saveStartBinding(StartBinding binding) {
    Objects.requireNonNull(binding, "start binding is required");
  }

  record StartBinding(
      MatchId matchId,
      int version,
      String requestIdentity,
      CopyOwner oneOwner,
      List<CardCopyId> oneCopies,
      UUID oneOperationId,
      CopyOwner twoOwner,
      List<CardCopyId> twoCopies,
      UUID twoOperationId) {
    public StartBinding {
      Objects.requireNonNull(matchId, "match id is required");
      if (version < 1) throw new IllegalArgumentException("binding version must be positive");
      if (requestIdentity == null || requestIdentity.isBlank()) {
        throw new IllegalArgumentException("request identity is required");
      }
      Objects.requireNonNull(oneOwner);
      oneCopies = List.copyOf(Objects.requireNonNull(oneCopies));
      Objects.requireNonNull(oneOperationId);
      Objects.requireNonNull(twoOwner);
      twoCopies = List.copyOf(Objects.requireNonNull(twoCopies));
      Objects.requireNonNull(twoOperationId);
    }
  }

  default Optional<MatchSnapshot> load(MatchId matchId) {
    return find(matchId);
  }

  default List<MatchSnapshot> activeMatches() {
    return List.of();
  }

  /**
   * Loads active rows while isolating corruption to the affected row. Implementations that do not
   * support row-level diagnostics retain the legacy all-or-nothing behavior through this default.
   */
  default ActiveMatchesResult activeMatchesReport() {
    return new ActiveMatchesResult(activeMatches(), List.of());
  }

  record ActiveMatchesResult(List<MatchSnapshot> matches, List<ActiveMatchFailure> failures) {
    public ActiveMatchesResult {
      matches = List.copyOf(Objects.requireNonNull(matches, "matches are required"));
      failures = List.copyOf(Objects.requireNonNull(failures, "failures are required"));
    }
  }

  record ActiveMatchFailure(MatchId matchId, Failure failure) {
    public ActiveMatchFailure {
      Objects.requireNonNull(matchId, "match id is required");
      Objects.requireNonNull(failure, "failure is required");
    }
  }

  default void delete(MatchId matchId) {}

  /** Rehydrates a snapshot after resolving its catalog snapshot ID. */
  default Optional<MatchState> rehydrate(
      MatchSnapshot snapshot, Function<String, CardCatalog> catalogResolver) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    return Optional.empty();
  }

  default List<MatchState> activeStates(Function<String, CardCatalog> catalogResolver) {
    return activeMatches().stream()
        .map(snapshot -> rehydrate(snapshot, catalogResolver))
        .flatMap(Optional::stream)
        .toList();
  }

  record SaveResult(boolean saved, long revision, Failure failure) {
    public SaveResult {
      Objects.requireNonNull(failure, "failure is required");
    }

    public static SaveResult success(long revision) {
      return new SaveResult(true, revision, Failure.NONE);
    }

    public static SaveResult stale(long revision) {
      return new SaveResult(false, revision, Failure.STALE_REVISION);
    }

    public boolean accepted() {
      return saved;
    }
  }

  enum Failure {
    NONE,
    STALE_REVISION,
    DATABASE_ERROR,
    INVALID_STATE
  }

  record MatchSnapshot(
      MatchId matchId,
      long revision,
      String status,
      byte[] stateBytes,
      String transcriptJson,
      String stateDigest) {
    public MatchSnapshot(
        MatchId matchId, long revision, String status, byte[] stateBytes, String transcriptJson) {
      this(matchId, revision, status, stateBytes, transcriptJson, null);
    }

    public MatchSnapshot {
      Objects.requireNonNull(matchId);
      Objects.requireNonNull(status);
      Objects.requireNonNull(stateBytes);
      stateBytes = stateBytes.clone();
      Objects.requireNonNull(transcriptJson);
      if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    @Override
    public byte[] stateBytes() {
      return stateBytes.clone();
    }
  }
}
