package org.aincraft.mobmatrix.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchState;

/**
 * Explicit non-durable repository for isolated runs and tests.
 *
 * <p>It retains the same initial-save and optimistic-CAS semantics as the durable repository; it
 * is not a production persistence substitute.
 */
public final class InMemoryMatchRepository implements MatchRepository {
  private final Map<MatchId, MatchState> states = new ConcurrentHashMap<>();
  private final Map<MatchId, StartBinding> bindings = new ConcurrentHashMap<>();

  @Override
  public synchronized SaveResult save(long expectedRevision, MatchState state) {
    Objects.requireNonNull(state, "state is required");
    if (expectedRevision < 0) return new SaveResult(false, -1L, Failure.INVALID_STATE);
    MatchState current = states.get(state.matchId());
    if (current == null) {
      if (expectedRevision != 0 || state.revision() != 0) {
        return new SaveResult(false, -1L, Failure.INVALID_STATE);
      }
      states.put(state.matchId(), state);
      return SaveResult.success(0L);
    }
    if (state.revision() != expectedRevision + 1) {
      return new SaveResult(false, current.revision(), Failure.INVALID_STATE);
    }
    if (current.revision() != expectedRevision) return SaveResult.stale(current.revision());
    states.put(state.matchId(), state);
    return SaveResult.success(state.revision());
  }

  @Override
  public synchronized SaveResult saveInitial(MatchState state, StartBinding binding) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(binding, "start binding is required");
    if (state.revision() != 0 || !state.matchId().equals(binding.matchId())) {
      return new SaveResult(false, -1L, Failure.INVALID_STATE);
    }
    if (states.containsKey(state.matchId()) || bindings.containsKey(binding.matchId())) {
      MatchState current = states.get(state.matchId());
      return SaveResult.stale(current == null ? -1L : current.revision());
    }
    states.put(state.matchId(), state);
    bindings.put(binding.matchId(), binding);
    return SaveResult.success(0L);
  }

  @Override
  public Optional<MatchSnapshot> find(MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    MatchState state = states.get(matchId);
    if (state == null) return Optional.empty();
    return Optional.of(new MatchSnapshot(matchId, state.revision(),
        state.isFinished() ? "FINISHED" : "ACTIVE", new byte[0], "{}"));
  }

  @Override
  public Optional<MatchState> findState(MatchId matchId, CardCatalog catalog) {
    Objects.requireNonNull(matchId, "match id is required");
    Objects.requireNonNull(catalog, "catalog is required");
    MatchState state = states.get(matchId);
    return state != null && state.catalog().snapshotId().equals(catalog.snapshotId())
        ? Optional.of(state) : Optional.empty();
  }

  @Override
  public Optional<StartBinding> findStartBinding(MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    return Optional.ofNullable(bindings.get(matchId));
  }

  @Override
  public synchronized void saveStartBinding(StartBinding binding) {
    Objects.requireNonNull(binding, "start binding is required");
    if (!states.containsKey(binding.matchId())) throw new IllegalStateException("match is not persisted");
    StartBinding prior = bindings.putIfAbsent(binding.matchId(), binding);
    if (prior != null && !prior.equals(binding)) throw new IllegalStateException("start binding mismatch");
  }

  @Override
  public List<MatchSnapshot> activeMatches() {
    List<MatchSnapshot> result = new ArrayList<>();
    for (MatchId matchId : states.keySet()) {
      find(matchId).filter(snapshot -> snapshot.status().equals("ACTIVE")).ifPresent(result::add);
    }
    return List.copyOf(result);
  }

  @Override
  public synchronized void delete(MatchId matchId) {
    states.remove(Objects.requireNonNull(matchId));
    bindings.remove(matchId);
  }

  @Override
  public Optional<MatchState> rehydrate(MatchSnapshot snapshot,
      Function<String, CardCatalog> catalogResolver) {
    Objects.requireNonNull(snapshot, "snapshot is required");
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    MatchState state = states.get(snapshot.matchId());
    return state == null ? Optional.empty() : Optional.of(state);
  }
}
