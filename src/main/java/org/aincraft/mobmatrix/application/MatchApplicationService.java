package org.aincraft.mobmatrix.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.aincraft.mobmatrix.application.CardCustodyPort.CustodyResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.application.CardCustodyPort.PrepareResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.RecoveryResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.ReturnResult;
import org.aincraft.mobmatrix.domain.CardCatalog;
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
    Failure validation = validateDeckSelections(request);
    if (validation != Failure.NONE) {
      return MatchStartResult.rejected(request.match().matchId(), validation);
    }
    StartResult one = prepare(request.oneOwner(), request.oneCopies(), request.oneOperationId());
    if (!one.accepted()) return MatchStartResult.rejected(request.match().matchId(), one.failure());
    StartResult two;
    try {
      two = prepare(request.twoOwner(), request.twoCopies(), request.twoOperationId());
    } catch (RuntimeException exception) {
      cleanup(request.oneOperationId());
      return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR);
    }
    if (!two.accepted()) {
      cleanup(request.oneOperationId());
      return MatchStartResult.rejected(request.match().matchId(), two.failure());
    }
    MatchEngine.MatchTransition started = MatchEngine.start(request.match());
    if (!started.accepted()) {
      cleanup(request.oneOperationId());
      cleanup(request.twoOperationId());
      return MatchStartResult.rejected(request.match().matchId(), Failure.INVALID_STATE);
    }
    if (matches != null) {
      MatchRepository.SaveResult saved;
      try {
        saved = matches.save(0L, started.state());
      } catch (RuntimeException exception) {
        cleanup(request.oneOperationId());
        cleanup(request.twoOperationId());
        return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR);
      }
      if (!saved.accepted()) {
        cleanup(request.oneOperationId());
        cleanup(request.twoOperationId());
        Failure failure = saved.failure() == MatchRepository.Failure.STALE_REVISION
            ? Failure.STALE_REVISION : Failure.INVALID_STATE;
        return MatchStartResult.rejected(request.match().matchId(), failure);
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
      cleanup(operationId);
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
  private void cleanup(UUID operationId) {
    ReturnResult returned = custody.returnCopies(operationId);
    if (returned.complete() || returned.status() == OperationStatus.PREPARED) {
      custody.abort(operationId);
    }
  }

  private static Failure validateDeckSelections(StartMatchRequest request) {
    Failure one = validateDeck(request.oneCopies(), request.match().oneDeck(), request.oneOwner(),
        request.match().oneInventory(), request.match().rules());
    if (one != Failure.NONE) return one;
    return validateDeck(request.twoCopies(), request.match().twoDeck(), request.twoOwner(),
        request.match().twoInventory(), request.match().rules());
  }

  private static Failure validateDeck(List<CardCopyId> requested, org.aincraft.mobmatrix.domain.Deck deck,
      CopyOwner owner, List<org.aincraft.mobmatrix.domain.CardCopy> inventory,
      org.aincraft.mobmatrix.domain.MatchRules rules) {
    List<CardCopyId> copies;
    try {
      copies = exactDeck(requested);
    } catch (IllegalArgumentException exception) {
      return Failure.INVALID_COPY_SET;
    }
    if (deck.copies().size() != 5) return Failure.INVALID_COPY_SET;
    Set<CardCopyId> deckIds = new HashSet<>();
    for (var card : deck.copies()) if (!deckIds.add(card.copyId())) return Failure.INVALID_COPY_SET;
    if (!deckIds.equals(new HashSet<>(copies))) return Failure.MISMATCHED_OPERATION;
    if (rules.has(org.aincraft.mobmatrix.domain.Rule.RANDOM)
        || rules.has(org.aincraft.mobmatrix.domain.Rule.DRAFT)) return Failure.NONE;
    Map<CardCopyId, org.aincraft.mobmatrix.domain.CardCopy> inventoryById = new HashMap<>();
    for (var card : inventory) {
      if (inventoryById.put(card.copyId(), card) != null) return Failure.DUPLICATE_COPY;
    }
    for (var card : deck.copies()) {
      if (!card.owner().equals(owner)) return Failure.MISMATCHED_OWNER;
      var inventoryCard = inventoryById.get(card.copyId());
      if (inventoryCard == null || !inventoryCard.cardId().equals(card.cardId())
          || !inventoryCard.owner().equals(owner)) return Failure.MISMATCHED_OWNER;
    }
    return Failure.NONE;
  }
  /** Reconciles one operation journal entry on demand. */
  public synchronized RecoveryResult recover(UUID operationId) {
    return custody.recover(Objects.requireNonNull(operationId, "operation id is required"));
  }

  /** Reconciles every durable custody row and active match on plugin startup. */
  public synchronized StartupRecoveryResult recoverStartup(
      Function<String, CardCatalog> catalogResolver, Instant now) {
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    Objects.requireNonNull(now, "time is required");
    List<RecoveryResult> recoveries = new ArrayList<>();
    for (UUID operationId : custody.openOperationIds()) recoveries.add(custody.recover(operationId));
    List<MatchState> states = new ArrayList<>();
    if (matches != null) {
      for (MatchState loaded : matches.activeStates(catalogResolver)) {
        MatchState current = loaded;
        if (!current.isFinished() && !now.isBefore(current.turnDeadline())) {
          UUID timeoutId = UUID.nameUUIDFromBytes(
              (current.matchId().value() + ":timeout:" + current.revision()).getBytes(
                  java.nio.charset.StandardCharsets.UTF_8));
          ApplyResult timeout = apply(current, new MatchCommand.Timeout(current.currentSeat(),
              current.revision(), timeoutId, now));
          if (timeout.accepted()) current = timeout.transition().state();
        }
        states.add(current);
      }
    }
    return new StartupRecoveryResult(states, recoveries);
  }

  public StartupRecoveryResult recoverStartup(Function<String, CardCatalog> catalogResolver) {
    return recoverStartup(catalogResolver, Instant.now());
  }

  public record StartupRecoveryResult(List<MatchState> matches, List<RecoveryResult> recoveries) {
    public StartupRecoveryResult {
      matches = List.copyOf(Objects.requireNonNull(matches));
      recoveries = List.copyOf(Objects.requireNonNull(recoveries));
    }
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
      return new ApplyResult(transition, Failure.STALE_REVISION, revision);
    }

    public boolean accepted() { return failure == Failure.NONE; }
  }
}
