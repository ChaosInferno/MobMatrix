package org.aincraft.mobmatrix.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort.CustodyResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.CardCustodyPort.PrepareResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.RecoveryResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.ReturnResult;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchState;

/** Only orchestration path that commits match transitions around durable card custody. */
public final class MatchApplicationService {
  private final CardCustodyPort custody;
  private final MatchRepository matches;
  private final Map<UUID, StartResult> starts = new HashMap<>();
  private final Map<UUID, ReturnResult> settlements = new HashMap<>();

  public MatchApplicationService(CardCustodyPort custody) {
    this(custody, null);
  }

  /**
   * Performs both seat reservations and writes the active match only after both escrows and
   * the domain start transition have succeeded.
   */
  public synchronized MatchStartResult start(StartMatchRequest request) {
    Objects.requireNonNull(request, "start request is required");
    StartResult one = prepare(request.oneOwner(), request.oneCopies(), request.oneOperationId());
    if (!one.accepted()) return MatchStartResult.rejected(request.match().matchId(), one.failure());
    StartResult two = prepare(request.twoOwner(), request.twoCopies(), request.twoOperationId());
    if (!two.accepted()) {
      custody.returnCopies(request.oneOperationId());
      return MatchStartResult.rejected(request.match().matchId(), two.failure());
    }
    MatchEngine.MatchTransition started = MatchEngine.start(request.match());
    if (!started.accepted()) {
      custody.returnCopies(request.oneOperationId());
      custody.returnCopies(request.twoOperationId());
      return MatchStartResult.rejected(request.match().matchId(), Failure.INVALID_STATE);
    }
    if (matches != null) {
      MatchRepository.SaveResult saved = matches.save(0L, started.state());
      if (!saved.accepted()) {
        custody.returnCopies(request.oneOperationId());
        custody.returnCopies(request.twoOperationId());
        return MatchStartResult.rejected(request.match().matchId(), Failure.STALE_REVISION);
      }
    }
    return MatchStartResult.active(request.match().matchId(), started.state());
  }

  public MatchApplicationService(CardCustodyPort custody, MatchRepository matches) {
    this.custody = Objects.requireNonNull(custody, "custody port is required");
    this.matches = matches;
  }

  /** Prepare and escrow exactly five selected physical cards before exposing a match. */
  public synchronized StartResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    StartResult previous = starts.get(operationId);
    if (previous != null) {
      if (!previous.owner().equals(owner) || !previous.copyIds().equals(copyIds)) {
        return StartResult.rejected(operationId, owner, copyIds, Failure.MISMATCHED_OPERATION);
      }
      return previous;
    }
    List<CardCopyId> copies;
    try {
      copies = exactDeck(copyIds);
    } catch (IllegalArgumentException exception) {
      return StartResult.rejected(operationId, owner, copyIds == null ? List.of() : copyIds, Failure.INVALID_COPY_SET);
    }
    if (owner == null || !owner.isPlayer()) {
      return StartResult.rejected(operationId, owner == null ? CopyOwner.house() : owner, copies, Failure.INVALID_OWNER);
    }
    PrepareResult prepared = custody.prepare(owner, copies, operationId);
    if (!prepared.accepted()) {
      return StartResult.rejected(operationId, owner, copies, prepared.failure());
    }
    CustodyResult escrowed = custody.escrow(operationId);
    if (!escrowed.accepted() || escrowed.status() != OperationStatus.ESCROWED) {
      custody.abort(operationId);
      custody.recover(operationId);
      return StartResult.rejected(operationId, owner, copies,
          escrowed.failure() == Failure.NONE ? Failure.INVALID_STATE : escrowed.failure());
    }
    StartResult result = StartResult.escrowed(operationId, owner, copies);
    starts.put(operationId, result);
    return result;
  }

  public synchronized StartResult prepare(CopyOwner owner, List<CardCopyId> copyIds, String operationId) {
    return prepare(owner, copyIds, UUID.fromString(Objects.requireNonNull(operationId)));
  }

  /** Returns exact escrowed cards; repeated settlement does not return twice. */
  public synchronized ReturnResult settle(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    ReturnResult previous = settlements.get(operationId);
    if (previous != null && previous.status() == OperationStatus.RETURNED) return previous;
    ReturnResult result = custody.returnCopies(operationId);
    settlements.put(operationId, result);
    return result;
  }

  public synchronized ReturnResult settle(String operationId) {
    return settle(UUID.fromString(Objects.requireNonNull(operationId)));
  }

  /** Applies a pure domain command, persisting the accepted successor before callers render/notify. */
  public synchronized ApplyResult apply(MatchState state, MatchCommand command) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(command, "command is required");
    MatchEngine.MatchTransition transition = MatchEngine.apply(state, command);
    if (!transition.accepted()) return ApplyResult.rejected(transition);
    if (matches != null) {
      MatchRepository.SaveResult saved = matches.save(state.revision(), transition.state());
      if (!saved.accepted()) return ApplyResult.stale(transition, saved.revision());
    }
    return ApplyResult.accepted(transition);
  }

  /** Reconciles an operation journal on startup without touching JDBC from inventory code. */
  public synchronized RecoveryResult recover(UUID operationId) {
    return custody.recover(Objects.requireNonNull(operationId, "operation id is required"));
  }

  private static List<CardCopyId> exactDeck(List<CardCopyId> copyIds) {
    Objects.requireNonNull(copyIds, "copy ids are required");
    if (copyIds.size() != 5) throw new IllegalArgumentException("a deck must contain exactly five copies");
    Set<CardCopyId> unique = new HashSet<>();
    for (CardCopyId copy : copyIds) if (copy == null || !unique.add(copy)) throw new IllegalArgumentException("duplicate copy id");
    return List.copyOf(copyIds);
  }

  public record StartMatchRequest(MatchEngine.StartMatch match, CopyOwner oneOwner,
      List<CardCopyId> oneCopies, UUID oneOperationId, CopyOwner twoOwner,
      List<CardCopyId> twoCopies, UUID twoOperationId) {
    public StartMatchRequest {
      Objects.requireNonNull(match); Objects.requireNonNull(oneOwner); Objects.requireNonNull(twoOwner);
      oneCopies = List.copyOf(Objects.requireNonNull(oneCopies));
      twoCopies = List.copyOf(Objects.requireNonNull(twoCopies));
      Objects.requireNonNull(oneOperationId); Objects.requireNonNull(twoOperationId);
      if (oneOperationId.equals(twoOperationId)) throw new IllegalArgumentException("operations must differ");
    }
  }

  public record MatchStartResult(org.aincraft.mobmatrix.domain.MatchId matchId,
      MatchState state, boolean active, Failure failure) {
    public MatchStartResult {
      Objects.requireNonNull(matchId); Objects.requireNonNull(failure);
    }

    static MatchStartResult active(org.aincraft.mobmatrix.domain.MatchId id, MatchState state) {
      return new MatchStartResult(id, state, true, Failure.NONE);
    }

    static MatchStartResult rejected(org.aincraft.mobmatrix.domain.MatchId id, Failure failure) {
      return new MatchStartResult(id, null, false, failure);
    }

    public boolean accepted() { return active; }
  }

  public record StartResult(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      boolean escrowed, Failure failure) {
    public StartResult {
      Objects.requireNonNull(operationId); Objects.requireNonNull(owner);
      copyIds = List.copyOf(copyIds); Objects.requireNonNull(failure);
    }

    public static StartResult escrowed(UUID operationId, CopyOwner owner, List<CardCopyId> copies) {
      return new StartResult(operationId, owner, copies, true, Failure.NONE);
    }

    public static StartResult rejected(UUID operationId, CopyOwner owner, List<CardCopyId> copies, Failure failure) {
      return new StartResult(operationId, owner, copies, false, failure);
    }

    public boolean accepted() { return escrowed; }
  }

  public record ApplyResult(MatchEngine.MatchTransition transition, Failure failure, long currentRevision) {
    public ApplyResult {
      Objects.requireNonNull(transition); Objects.requireNonNull(failure);
    }

    static ApplyResult accepted(MatchEngine.MatchTransition transition) {
      return new ApplyResult(transition, Failure.NONE, transition.state().revision());
    }

    static ApplyResult rejected(MatchEngine.MatchTransition transition) {
      return new ApplyResult(transition, Failure.INVALID_STATE, transition.state() == null ? -1 : transition.state().revision());
    }

    static ApplyResult stale(MatchEngine.MatchTransition transition, long revision) {
      return new ApplyResult(transition, Failure.MISMATCHED_OPERATION, revision);
    }

    public boolean accepted() { return failure == Failure.NONE; }
  }
}
