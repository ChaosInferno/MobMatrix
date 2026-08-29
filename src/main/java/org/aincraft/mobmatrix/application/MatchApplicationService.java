package org.aincraft.mobmatrix.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.aincraft.mobmatrix.domain.MatchErrorCode;
import org.aincraft.mobmatrix.domain.MatchRejection;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;

/** Only orchestration path that commits match transitions around durable card custody. */
public final class MatchApplicationService {
  private final CardCustodyPort custody;
  private final MatchRepository matches;

  /** Uses explicit non-durable storage for isolated runs; production must inject a durable repository. */
  public MatchApplicationService(CardCustodyPort custody) {
    this(custody, new InMemoryMatchRepository());
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

    // Evaluate the pure start exactly once. Its selected physical copies and transcript are the
    // durable identity of this match and must be reused on an identical retry.
    MatchEngine.MatchTransition started = MatchEngine.start(request.match());
    if (!started.accepted()) {
      return MatchStartResult.rejected(request.match().matchId(), Failure.INVALID_STATE);
    }
    if (request.match().rules().has(org.aincraft.mobmatrix.domain.Rule.RANDOM)) {
      Failure randomValidation = validateRandomSelections(request, started.state());
      if (randomValidation != Failure.NONE) {
        return MatchStartResult.rejected(request.match().matchId(), randomValidation);
      }
    }
    ReservationPlan plan = reservationPlan(started.state(), request);
    if (plan == null) {
      return MatchStartResult.rejected(request.match().matchId(), Failure.MISMATCHED_OPERATION);
    }
    try {
      java.util.Optional<MatchState> existing = matches.findState(request.match().matchId(),
          request.match().catalog());
      if (existing.isPresent()) {
        MatchState state = existing.get();
        Optional<MatchRepository.StartBinding> binding =
            matches.findStartBinding(request.match().matchId());
        if (binding.isPresent() && startBindingMatches(binding.get(), request, plan)
            && sameInitialState(state, started.state())) {
          BindingCheck custodyCheck = operationBindingsMatch(request, state.matchId());
          if (custodyCheck == BindingCheck.MATCH) {
            return MatchStartResult.active(state.matchId(), state);
          }
          if (custodyCheck == BindingCheck.UNAVAILABLE) {
            return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR);
          }
        }
        return MatchStartResult.rejected(request.match().matchId(), Failure.MISMATCHED_OPERATION);
      }
      if (matches.find(request.match().matchId()).isPresent()) {
        // A repository that cannot rehydrate an existing row cannot safely prove retry identity.
        return MatchStartResult.rejected(request.match().matchId(), Failure.MISMATCHED_OPERATION);
      }
    } catch (RuntimeException exception) {
      return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR);
    }
    
    StartResult one;
    try {
      one = prepareForStart(request.oneOwner(), plan.oneCopies(), request.oneOperationId(),
          request.match().matchId());
    } catch (RuntimeException exception) {
      CleanupSummary cleanup = cleanup(request.oneOperationId(), plan.oneCopies());
      return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR, cleanup);
    }
    if (!one.accepted()) {
      // A rejection means no durable operation was accepted. Do not manufacture pending
      // recovery for copies that never entered custody.
      return MatchStartResult.rejected(request.match().matchId(), one.failure());
    }

    StartResult two;
    try {
      two = prepareForStart(request.twoOwner(), plan.twoCopies(), request.twoOperationId(),
          request.match().matchId());
    } catch (RuntimeException exception) {
      CleanupSummary cleanup = cleanup(request.oneOperationId(), plan.oneCopies());
      cleanup = cleanup.plus(cleanup(request.twoOperationId(), plan.twoCopies()));
      return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR, cleanup);
    }
    if (!two.accepted()) {
      CleanupSummary cleanup = cleanup(request.oneOperationId(), plan.oneCopies());
      // Only the accepted first operation is eligible for cleanup. A rejected second prepare
      // likewise did not create custody and must not yield phantom recovery rows.
      return MatchStartResult.rejected(request.match().matchId(), two.failure(), cleanup);
    }

    MatchRepository.SaveResult saved;
    try {
      saved = matches.saveInitial(started.state(), new MatchRepository.StartBinding(
          request.match().matchId(), 1, startIdentity(request), request.oneOwner(),
          plan.oneCopies(), request.oneOperationId(), request.twoOwner(), plan.twoCopies(),
          request.twoOperationId()));
    } catch (RuntimeException exception) {
      return initialSaveFailure(request, started.state(), plan, Failure.DATABASE_ERROR);
    }
    if (!saved.accepted()) {
      return initialSaveFailure(request, started.state(), plan, mapSaveFailure(saved.failure()));
    }
    return MatchStartResult.active(request.match().matchId(), started.state());
  }

  public MatchApplicationService(CardCustodyPort custody, MatchRepository matches) {
    this.custody = Objects.requireNonNull(custody, "custody port is required");
    this.matches = Objects.requireNonNull(matches, "match repository is required");
  }

  /** Prepare and escrow exactly five selected physical cards before exposing a match. */
  public synchronized StartResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
    return prepare(owner, copyIds, operationId, null);
  }

  private StartResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    Objects.requireNonNull(operationId, "operation id is required");
    List<CardCopyId> copies;
    try {
      copies = exactDeck(copyIds);
    } catch (IllegalArgumentException exception) {
      return StartResult.rejected(operationId, owner == null ? CopyOwner.house() : owner,
          copyIds == null ? List.of() : copyIds, Failure.INVALID_COPY_SET);
    }
    if (owner == null || !owner.isPlayer()) {
      return StartResult.rejected(operationId, owner == null ? CopyOwner.house() : owner, copies,
          Failure.INVALID_OWNER);
    }
    PrepareResult prepared;
    try {
      prepared = matchId == null
          ? custody.prepare(owner, copies, operationId)
          : custody.prepare(owner, copies, operationId, matchId);
    } catch (RuntimeException exception) {
      cleanup(operationId, copies);
      return StartResult.rejected(operationId, owner, copies, Failure.DATABASE_ERROR);
    }
    if (!prepared.accepted()) {
      return StartResult.rejected(operationId, owner, copies, prepared.failure());
    }
    CustodyResult escrowed;
    try {
      escrowed = custody.escrow(operationId);
    } catch (RuntimeException exception) {
      cleanup(operationId, copies);
      return StartResult.rejected(operationId, owner, copies, Failure.DATABASE_ERROR);
    }
    if (!escrowed.accepted() || escrowed.status() != OperationStatus.ESCROWED) {
      cleanup(operationId, copies);
      return StartResult.rejected(operationId, owner, copies,
          escrowed.failure() == Failure.NONE ? Failure.INVALID_STATE : escrowed.failure());
    }
    return StartResult.escrowed(operationId, owner, copies);
  }
  private StartResult prepareForStart(CopyOwner owner, List<CardCopyId> copies, UUID operationId,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    if (owner == null || !owner.isPlayer()) {
      return StartResult.rejected(operationId, owner == null ? CopyOwner.house() : owner,
          copies, Failure.INVALID_OWNER);
    }
    if (copies.isEmpty()) return StartResult.escrowed(operationId, owner, copies);
    return prepare(owner, copies, operationId, matchId);
  }
  /** Returns exact escrowed cards; custody itself provides durable idempotency. */
  public synchronized ReturnResult settle(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    try {
      return custody.returnCopies(operationId);
    } catch (RuntimeException exception) {
      return returnFailure(operationId);
    }
  }

  public synchronized ReturnResult settle(String operationId) {
    return settle(UUID.fromString(Objects.requireNonNull(operationId)));
  }
  /** Applies a pure domain command to the canonical persisted state before persisting the successor. */
  public synchronized ApplyResult apply(MatchState state, MatchCommand command) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(command, "command is required");
    MatchState canonical;
    try {
      Optional<MatchState> loaded = matches.findState(state.matchId(), state.catalog());
      if (loaded.isEmpty()) return ApplyResult.unavailable(state);
      canonical = Objects.requireNonNull(loaded.get(), "persisted state is required");
    } catch (RuntimeException exception) {
      return ApplyResult.unavailable(state);
    }
    MatchEngine.MatchTransition transition = MatchEngine.apply(canonical, command);
    if (!transition.accepted()) {
      if (command.expectedRevision() != canonical.revision()
          || transition.rejection().code() == MatchErrorCode.STALE_REVISION) {
        return ApplyResult.stale(transition, canonical.revision());
      }
      return ApplyResult.rejected(transition);
    }
    MatchRepository.SaveResult saved;
    try {
      saved = matches.save(canonical.revision(), transition.state());
    } catch (RuntimeException exception) {
      return new ApplyResult(transition, Failure.DATABASE_ERROR, canonical.revision());
    }
    if (!saved.accepted()) {
      Failure failure = switch (saved.failure()) {
        case STALE_REVISION -> Failure.STALE_REVISION;
        case DATABASE_ERROR -> Failure.DATABASE_ERROR;
        case INVALID_STATE, NONE -> Failure.INVALID_STATE;
      };
      return new ApplyResult(transition, failure, saved.revision());
    }
    return ApplyResult.accepted(transition);
  }



  private MatchStartResult initialSaveFailure(StartMatchRequest request, MatchState startedState,
      ReservationPlan plan, Failure failure) {
    InitialSaveResolution resolution = recheckInitialSave(request, startedState, plan);
    if (resolution.state() != null) {
      return MatchStartResult.active(request.match().matchId(), resolution.state());
    }
    if (resolution.ambiguous()) {
      return MatchStartResult.rejected(request.match().matchId(), Failure.DATABASE_ERROR);
    }
    CleanupSummary cleanup = cleanup(request.oneOperationId(), plan.oneCopies());
    cleanup = cleanup.plus(cleanup(request.twoOperationId(), plan.twoCopies()));
    return MatchStartResult.rejected(request.match().matchId(), failure, cleanup);
  }

  private InitialSaveResolution recheckInitialSave(StartMatchRequest request,
      MatchState startedState, ReservationPlan plan) {
    try {
      Optional<MatchState> existing = matches.findState(request.match().matchId(),
          request.match().catalog());
      if (existing.isPresent()) {
        MatchState state = existing.get();
        Optional<MatchRepository.StartBinding> binding =
            matches.findStartBinding(request.match().matchId());
        if (binding.isPresent() && startBindingMatches(binding.get(), request, plan)
            && sameInitialState(state, startedState)) {
          return operationBindingsMatchStrict(request, plan, state.matchId())
              ? InitialSaveResolution.found(state)
              : InitialSaveResolution.unknown();
        }
        return InitialSaveResolution.notFound();
      }
      return matches.find(request.match().matchId()).isPresent()
          ? InitialSaveResolution.unknown()
          : InitialSaveResolution.notFound();
    } catch (RuntimeException exception) {
      return InitialSaveResolution.unknown();
    }
  }

  private boolean operationBindingsMatchStrict(StartMatchRequest request, ReservationPlan plan,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    return bindingMatchesStrict(request.oneOperationId(), request.oneOwner(), plan.oneCopies(), matchId)
        && bindingMatchesStrict(request.twoOperationId(), request.twoOwner(), plan.twoCopies(), matchId);
  }

  private boolean bindingMatchesStrict(UUID operationId, CopyOwner owner,
      List<CardCopyId> copies, org.aincraft.mobmatrix.domain.MatchId matchId) {
    if (copies.isEmpty()) return true;
    Optional<CardCustodyPort.OperationBinding> binding =
        custody.operationBinding(operationId);
    if (binding.isEmpty()) return false;
    CardCustodyPort.OperationBinding value = binding.get();
    return value.status() == OperationStatus.ESCROWED
        && value.owner().equals(owner)
        && value.copyIds().equals(copies)
        && Objects.equals(value.matchId(), matchId);
  }

  private static Failure mapSaveFailure(MatchRepository.Failure failure) {
    return switch (failure) {
      case STALE_REVISION -> Failure.STALE_REVISION;
      case DATABASE_ERROR -> Failure.DATABASE_ERROR;
      case INVALID_STATE, NONE -> Failure.INVALID_STATE;
    };
  }

  private record InitialSaveResolution(MatchState state, boolean ambiguous) {
    private static InitialSaveResolution found(MatchState state) {
      return new InitialSaveResolution(state, false);
    }

    private static InitialSaveResolution notFound() {
      return new InitialSaveResolution(null, false);
    }

    private static InitialSaveResolution unknown() {
      return new InitialSaveResolution(null, true);
    }
  }

  private BindingCheck operationBindingsMatch(StartMatchRequest request,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    BindingCheck one = bindingMatchesIfNeeded(request.oneOperationId(), request.oneOwner(),
        request.oneCopies(), matchId);
    if (one != BindingCheck.MATCH) return one;
    return bindingMatchesIfNeeded(request.twoOperationId(), request.twoOwner(),
        request.twoCopies(), matchId);
  }

  private BindingCheck bindingMatchesIfNeeded(UUID operationId, CopyOwner owner,
      List<CardCopyId> copies, org.aincraft.mobmatrix.domain.MatchId matchId) {
    // Draft's intentionally empty house-custody plan has no custody operation to bind.
    if (copies.isEmpty()) return BindingCheck.MATCH;
    try {
      Optional<CardCustodyPort.OperationBinding> binding = custody.operationBinding(operationId);
      if (binding.isEmpty()) return BindingCheck.MISMATCH;
      CardCustodyPort.OperationBinding value = binding.get();
      return value.status() == OperationStatus.ESCROWED
          && value.owner().equals(owner)
          && value.copyIds().equals(copies)
          && Objects.equals(value.matchId(), matchId)
          ? BindingCheck.MATCH : BindingCheck.MISMATCH;
    } catch (RuntimeException exception) {
      return BindingCheck.UNAVAILABLE;
    }
  }

  private enum BindingCheck {
    MATCH,
    MISMATCH,
    UNAVAILABLE
  }
  private static boolean startBindingMatches(MatchRepository.StartBinding binding,
      StartMatchRequest request, ReservationPlan plan) {
    return binding.version() == 1
        && binding.requestIdentity().equals(startIdentity(request))
        && binding.oneOwner().equals(request.oneOwner())
        && binding.oneCopies().equals(plan.oneCopies())
        && binding.oneOperationId().equals(request.oneOperationId())
        && binding.twoOwner().equals(request.twoOwner())
        && binding.twoCopies().equals(plan.twoCopies())
        && binding.twoOperationId().equals(request.twoOperationId());
  }
  private static boolean sameInitialState(MatchState left, MatchState right) {
    return left.matchId().equals(right.matchId())
        && left.catalog().snapshotId().equals(right.catalog().snapshotId())
        && left.catalog().all().equals(right.catalog().all())
        && left.rules().equals(right.rules())
        && left.seed() == right.seed()
        && left.revision() == right.revision()
        && left.currentSeat() == right.currentSeat()
        && left.result() == null && right.result() == null
        && left.board().cells().equals(right.board().cells())
        && left.hands().equals(right.hands())
        && left.bankMillis().equals(right.bankMillis())
        && left.turnStartedAt().equals(right.turnStartedAt())
        && left.graceDeadline().equals(right.graceDeadline())
        && left.turnDeadline().equals(right.turnDeadline())
        && left.commandIds().equals(right.commandIds())
        && left.revealedCardsMap().equals(right.revealedCardsMap())
        && left.originalOwners().equals(right.originalOwners())
        && left.consecutiveDraws() == right.consecutiveDraws()
        && left.draftOfferings().equals(right.draftOfferings())
        && left.draftSelections().equals(right.draftSelections())
        && left.chaosChoices().equals(right.chaosChoices())
        && left.transcript().seed() == right.transcript().seed()
        && left.transcript().cursor() == right.transcript().cursor()
        && left.transcript().replayMode() == right.transcript().replayMode()
        && left.transcript().decisions().equals(right.transcript().decisions());
  }

  private static ReservationPlan reservationPlan(MatchState state, StartMatchRequest request) {
    boolean draft = request.match().rules().has(org.aincraft.mobmatrix.domain.Rule.DRAFT);
    boolean random = request.match().rules().has(org.aincraft.mobmatrix.domain.Rule.RANDOM);
    if (random) {
      Set<CardCopyId> oneOriginal = originalCopies(state, PlayerSeat.ONE);
      Set<CardCopyId> twoOriginal = originalCopies(state, PlayerSeat.TWO);
      if (!oneOriginal.equals(new HashSet<>(request.oneCopies()))
          || !twoOriginal.equals(new HashSet<>(request.twoCopies()))) return null;
      return new ReservationPlan(request.oneCopies(), request.twoCopies());
    }
    // Deck validation has already checked the requested UUIDs and owners. Keep this pre-SWAP
    // mapping: the post-SWAP hand intentionally contains one card from the opposite player.
    if (!draft) return new ReservationPlan(request.oneCopies(), request.twoCopies());
    List<CardCopyId> oneOwned = activeOwned(state, request.oneOwner());
    List<CardCopyId> twoOwned = activeOwned(state, request.twoOwner());
    return new ReservationPlan(oneOwned, twoOwned);
  }

  private static Set<CardCopyId> originalCopies(MatchState state, PlayerSeat seat) {
    Set<CardCopyId> result = new HashSet<>();
    state.originalOwners().forEach((copyId, owner) -> {
      if (owner == seat) result.add(copyId);
    });
    return result;
  }

  private static List<CardCopyId> activeOwned(MatchState state, CopyOwner owner) {
    List<CardCopyId> result = new ArrayList<>();
    for (var seat : org.aincraft.mobmatrix.domain.PlayerSeat.values()) {
      for (var card : state.hand(seat)) {
        if (card.copy().owner().equals(owner)) result.add(card.copyId());
      }
    }
    return List.copyOf(result);
  }


  private record ReservationPlan(List<CardCopyId> oneCopies, List<CardCopyId> twoCopies) {}

  private CleanupSummary cleanup(UUID operationId, List<CardCopyId> expected) {
    if (expected.isEmpty()) return new CleanupSummary(List.of(), List.of());
    boolean known = false;
    try {
      known = custody.openOperationIds().contains(operationId);
    } catch (RuntimeException ignored) {
      // A lookup failure must not turn an unaccepted operation into phantom recovery.
    }
    ReturnResult returned = null;
    try {
      returned = custody.returnCopies(operationId);
      if (returned.failure() == Failure.MISMATCHED_OPERATION && returned.expectedCopyIds().isEmpty()) {
        return new CleanupSummary(List.of(), List.of());
      }
      List<CardCopyId> pending = new ArrayList<>(returned.recoveryCopyIds());
      List<CardCopyId> missing = new ArrayList<>(returned.missingCopyIds());
      if (returned.complete() || returned.status() == OperationStatus.PREPARED) {
        CustodyResult aborted = custody.abort(operationId);
        if (!aborted.accepted() || aborted.status() != OperationStatus.ABORTED) {
          for (CardCopyId copyId : returned.expectedCopyIds()) {
            if (!pending.contains(copyId)) pending.add(copyId);
          }
        }
      }
      return new CleanupSummary(pending, missing);
    } catch (RuntimeException exception) {
      if (returned != null) {
        return new CleanupSummary(returned.expectedCopyIds(), returned.missingCopyIds());
      }
      return known ? new CleanupSummary(expected, expected)
          : new CleanupSummary(List.of(), List.of());
    }
  }

  private static String startIdentity(StartMatchRequest request) {
    StringBuilder value = new StringBuilder();
    append(value, request.match().matchId().value());
    append(value, request.match().catalog().snapshotId());
    for (var definition : request.match().catalog().all()) {
      append(value, definition.id().value());
      append(value, definition.publicNumber());
      append(value, definition.name());
      append(value, definition.stars());
      append(value, definition.type().name());
      append(value, definition.top());
      append(value, definition.right());
      append(value, definition.bottom());
      append(value, definition.left());
      append(value, definition.salePrice());
    }
    request.match().rules().rules().stream().map(Enum::name).sorted().forEach(item -> append(value, item));
    append(value, request.match().seed());
    append(value, request.match().startTime());
    append(value, request.oneOwner());
    append(value, request.twoOwner());
    append(value, request.oneOperationId());
    append(value, request.twoOperationId());
    appendPhysicalCopies(value, request.match().oneDeck().copies());
    appendPhysicalCopies(value, request.match().twoDeck().copies());
    appendPhysicalCopies(value, request.match().oneInventory());
    appendPhysicalCopies(value, request.match().twoInventory());
    appendIds(value, request.oneCopies());
    appendIds(value, request.twoCopies());
    request.match().oneDraftChoices().forEach(item -> append(value, item));
    request.match().twoDraftChoices().forEach(item -> append(value, item));
    return value.toString();
  }

  private static void appendPhysicalCopies(StringBuilder value,
      List<org.aincraft.mobmatrix.domain.CardCopy> copies) {
    append(value, copies.size());
    for (var copy : copies) {
      append(value, copy.copyId().value());
      append(value, copy.cardId().value());
      append(value, copy.owner());
    }
  }

  private static void appendIds(StringBuilder value, List<CardCopyId> copies) {
    append(value, copies.size());
    copies.forEach(copy -> append(value, copy.value()));
  }

  private static void append(StringBuilder value, Object item) {
    String text = String.valueOf(item);
    value.append(text.length()).append(':').append(text);
  }

  private static Failure validateDeckSelections(StartMatchRequest request) {
    if (overlaps(request.match())) return Failure.INVALID_COPY_SET;
    Failure one = validateDeck(request.oneCopies(), request.match().oneDeck(), request.oneOwner(),
        request.match().oneInventory(), request.match().rules());
    if (one != Failure.NONE) return one;
    return validateDeck(request.twoCopies(), request.match().twoDeck(), request.twoOwner(),
        request.match().twoInventory(), request.match().rules());
  }
  private static Failure validateRandomSelections(StartMatchRequest request, MatchState state) {
    Failure one = validateRandomSeat(request.oneCopies(), request.oneOwner(),
        request.match().oneInventory(), state, PlayerSeat.ONE);
    if (one != Failure.NONE) return one;
    return validateRandomSeat(request.twoCopies(), request.twoOwner(),
        request.match().twoInventory(), state, PlayerSeat.TWO);
  }

  private static Failure validateRandomSeat(List<CardCopyId> requested, CopyOwner owner,
      List<org.aincraft.mobmatrix.domain.CardCopy> inventory, MatchState state, PlayerSeat seat) {
    final List<CardCopyId> copies;
    try {
      copies = exactDeck(requested);
    } catch (IllegalArgumentException exception) {
      return Failure.INVALID_COPY_SET;
    }
    Map<CardCopyId, org.aincraft.mobmatrix.domain.CardCopy> inventoryById = new HashMap<>();
    for (var card : inventory) {
      if (inventoryById.put(card.copyId(), card) != null) return Failure.DUPLICATE_COPY;
    }
    Map<CardCopyId, org.aincraft.mobmatrix.domain.CardCopy> selectedById = new HashMap<>();
    for (var playerSeat : PlayerSeat.values()) {
      for (var card : state.hand(playerSeat)) {
        selectedById.put(card.copyId(), card.copy());
      }
    }
    for (CardCopyId copyId : copies) {
      if (state.originalOwners().get(copyId) != seat) return Failure.MISMATCHED_OPERATION;
      var selected = selectedById.get(copyId);
      var inventoryCard = inventoryById.get(copyId);
      if (selected == null || inventoryCard == null) return Failure.MISMATCHED_OWNER;
      if (!selected.owner().equals(owner) || !inventoryCard.owner().equals(owner)
          || !selected.cardId().equals(inventoryCard.cardId())) {
        return Failure.MISMATCHED_OWNER;
      }
    }
    return Failure.NONE;
  }


  private static boolean overlaps(org.aincraft.mobmatrix.domain.MatchEngine.StartMatch match) {
    Set<CardCopyId> one = new HashSet<>();
    match.oneDeck().copies().forEach(copy -> one.add(copy.copyId()));
    match.oneInventory().forEach(copy -> one.add(copy.copyId()));
    return match.twoDeck().copies().stream().anyMatch(copy -> one.contains(copy.copyId()))
        || match.twoInventory().stream().anyMatch(copy -> one.contains(copy.copyId()));
  }
  private static Failure validateDeck(List<CardCopyId> requested,
      org.aincraft.mobmatrix.domain.Deck deck, CopyOwner owner,
      List<org.aincraft.mobmatrix.domain.CardCopy> inventory,
      org.aincraft.mobmatrix.domain.MatchRules rules) {
    if (owner == null || !owner.isPlayer()) return Failure.INVALID_OWNER;
    if (rules.has(org.aincraft.mobmatrix.domain.Rule.DRAFT)) {
      return requested == null || requested.isEmpty() ? Failure.NONE : Failure.INVALID_COPY_SET;
    }
    List<CardCopyId> copies;
    try {
      copies = exactDeck(requested);
    } catch (IllegalArgumentException exception) {
      return Failure.INVALID_COPY_SET;
    }
    if (rules.has(org.aincraft.mobmatrix.domain.Rule.RANDOM)) return Failure.NONE;
    if (deck.copies().size() != 5) return Failure.INVALID_COPY_SET;
    Set<CardCopyId> deckIds = new HashSet<>();
    for (var card : deck.copies()) if (!deckIds.add(card.copyId())) return Failure.INVALID_COPY_SET;
    if (!deckIds.equals(new HashSet<>(copies))) return Failure.MISMATCHED_OPERATION;
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
    Objects.requireNonNull(operationId, "operation id is required");
    try {
      return custody.recover(operationId);
    } catch (RuntimeException exception) {
      return recoveryFailure(operationId);
    }
  }
  private ReturnResult returnFailure(UUID operationId) {
    Optional<CardCustodyPort.OperationBinding> binding = safeOperationBinding(operationId);
    List<CardCopyId> expected = binding.map(CardCustodyPort.OperationBinding::copyIds).orElse(List.of());
    return new ReturnResult(operationId, pendingStatus(binding), expected, List.of(), expected, expected,
        Failure.DATABASE_ERROR);
  }

  private RecoveryResult recoveryFailure(UUID operationId) {
    Optional<CardCustodyPort.OperationBinding> binding = safeOperationBinding(operationId);
    List<CardCopyId> expected = binding.map(CardCustodyPort.OperationBinding::copyIds).orElse(List.of());
    return new RecoveryResult(operationId, pendingStatus(binding), expected, List.of(), expected,
        Failure.DATABASE_ERROR);
  }

  private Optional<CardCustodyPort.OperationBinding> safeOperationBinding(UUID operationId) {
    try {
      return custody.operationBinding(operationId);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private static OperationStatus pendingStatus(
      Optional<CardCustodyPort.OperationBinding> binding) {
    if (binding.isEmpty()) return OperationStatus.RETURN_PENDING;
    OperationStatus status = binding.get().status();
    return status == OperationStatus.RETURNED || status == OperationStatus.ABORTED
        ? status : OperationStatus.RETURN_PENDING;
  }


  /** Reconciles every durable custody row and active match on plugin startup. */
  public synchronized StartupRecoveryResult recoverStartup(
      Function<String, CardCatalog> catalogResolver, Instant now) {
    Objects.requireNonNull(catalogResolver, "catalog resolver is required");
    Objects.requireNonNull(now, "time is required");

    // Read active match IDs before touching custody. An escrow bound to one of these IDs is still
    // in use and must not be returned during a restart.
    List<MatchRepository.MatchSnapshot> snapshots = List.of();
    Failure matchRepositoryFailure = Failure.NONE;
    boolean activeMatchesLoadFailed = false;
    try {
      snapshots = matches.activeMatches();
    } catch (RuntimeException ignored) {
      // A match repository outage must not prevent independent custody recovery. Keep bound
      // escrow rather than risking release when active IDs cannot be read.
      activeMatchesLoadFailed = true;
      matchRepositoryFailure = Failure.DATABASE_ERROR;
    }
    Set<org.aincraft.mobmatrix.domain.MatchId> activeMatchIds = new HashSet<>();
    for (MatchRepository.MatchSnapshot snapshot : snapshots) activeMatchIds.add(snapshot.matchId());

    List<RecoveryResult> recoveries = new ArrayList<>();
    Failure custodyEnumerationFailure = Failure.NONE;
    List<UUID> openOperations;
    try {
      openOperations = custody.openOperationIds();
    } catch (RuntimeException exception) {
      // Do not invent an operation ID for an unavailable enumeration. Active escrow remains
      // untouched, while active match rehydration below can proceed independently.
      openOperations = List.of();
      custodyEnumerationFailure = Failure.DATABASE_ERROR;
    }
    for (UUID operationId : openOperations) {
      try {
        Optional<OperationStatus> status = custody.operationStatus(operationId);
        Optional<org.aincraft.mobmatrix.domain.MatchId> binding =
            custody.operationMatchId(operationId);
        boolean activeEscrow = status.filter(value -> value == OperationStatus.ESCROWED)
            .isPresent() && (binding.map(activeMatchIds::contains).orElse(false)
                || (activeMatchesLoadFailed && binding.isPresent()));
        if (activeEscrow) continue;
        recoveries.add(custody.recover(operationId));
      } catch (RuntimeException exception) {
        recoveries.add(RecoveryResult.failed(operationId, OperationStatus.RETURN_PENDING,
            Failure.DATABASE_ERROR));
      }
    }

    List<MatchState> states = new ArrayList<>();
    for (MatchRepository.MatchSnapshot snapshot : snapshots) {
      try {
        java.util.Optional<MatchState> loaded = matches.rehydrate(snapshot, catalogResolver);
        if (loaded.isEmpty()) continue;
        MatchState current = loaded.get();
        if (!current.isFinished() && !now.isBefore(current.turnDeadline())) {
          UUID timeoutId = UUID.nameUUIDFromBytes(
              (current.matchId().value() + ":timeout:" + current.revision()).getBytes(
                  java.nio.charset.StandardCharsets.UTF_8));
          ApplyResult timeout = apply(current, new MatchCommand.Timeout(current.currentSeat(),
              current.revision(), timeoutId, now));
          if (timeout.accepted()) current = timeout.transition().state();
        }
        states.add(current);
      } catch (RuntimeException ignored) {
        // Missing catalog snapshots and unsupported state versions are isolated to one match.
      }
    }
    return new StartupRecoveryResult(states, recoveries, custodyEnumerationFailure,
        matchRepositoryFailure);
  }

  public StartupRecoveryResult recoverStartup(Function<String, CardCatalog> catalogResolver) {
    return recoverStartup(catalogResolver, Instant.now());
  }

  public record StartupRecoveryResult(List<MatchState> matches, List<RecoveryResult> recoveries,
      Failure custodyFailure, Failure matchFailure) {
    public StartupRecoveryResult(List<MatchState> matches, List<RecoveryResult> recoveries) {
      this(matches, recoveries, Failure.NONE, Failure.NONE);
    }

    public StartupRecoveryResult(List<MatchState> matches, List<RecoveryResult> recoveries,
        Failure custodyFailure) {
      this(matches, recoveries, custodyFailure, Failure.NONE);
    }

    public StartupRecoveryResult {
      matches = List.copyOf(Objects.requireNonNull(matches));
      recoveries = List.copyOf(Objects.requireNonNull(recoveries));
      Objects.requireNonNull(custodyFailure);
      Objects.requireNonNull(matchFailure);
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

  private record CleanupSummary(List<CardCopyId> pendingRecoveryCopyIds,
      List<CardCopyId> missingCopyIds) {
    private CleanupSummary {
      pendingRecoveryCopyIds = List.copyOf(pendingRecoveryCopyIds);
      missingCopyIds = List.copyOf(missingCopyIds);
    }

    private CleanupSummary plus(CleanupSummary other) {
      List<CardCopyId> pending = new ArrayList<>(pendingRecoveryCopyIds);
      pending.addAll(other.pendingRecoveryCopyIds);
      List<CardCopyId> missing = new ArrayList<>(missingCopyIds);
      missing.addAll(other.missingCopyIds);
      return new CleanupSummary(pending, missing);
    }
  }

  public record MatchStartResult(org.aincraft.mobmatrix.domain.MatchId matchId,
      MatchState state, boolean active, Failure failure, List<CardCopyId> pendingRecoveryCopyIds,
      List<CardCopyId> missingCopyIds) {
    public MatchStartResult(org.aincraft.mobmatrix.domain.MatchId matchId, MatchState state,
        boolean active, Failure failure) {
      this(matchId, state, active, failure, List.of(), List.of());
    }

    public MatchStartResult {
      Objects.requireNonNull(matchId);
      Objects.requireNonNull(failure);
      pendingRecoveryCopyIds = List.copyOf(Objects.requireNonNull(pendingRecoveryCopyIds));
      missingCopyIds = List.copyOf(Objects.requireNonNull(missingCopyIds));
    }

    static MatchStartResult active(org.aincraft.mobmatrix.domain.MatchId id, MatchState state) {
      return new MatchStartResult(id, state, true, Failure.NONE, List.of(), List.of());
    }

    static MatchStartResult rejected(org.aincraft.mobmatrix.domain.MatchId id, Failure failure) {
      return rejected(id, failure, new CleanupSummary(List.of(), List.of()));
    }

    static MatchStartResult rejected(org.aincraft.mobmatrix.domain.MatchId id, Failure failure,
        CleanupSummary cleanup) {
      return new MatchStartResult(id, null, false, failure, cleanup.pendingRecoveryCopyIds(),
          cleanup.missingCopyIds());
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

    public static StartResult rejected(UUID operationId, CopyOwner owner, List<CardCopyId> copies,
        Failure failure) {
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
      return new ApplyResult(transition, Failure.INVALID_STATE,
          transition.state() == null ? -1 : transition.state().revision());
    }

    static ApplyResult unavailable(MatchState callerState) {
      MatchEngine.MatchTransition transition = new MatchEngine.MatchTransition(callerState, List.of(),
          new MatchRejection(MatchErrorCode.INVALID_COMMAND, "canonical match state unavailable"));
      return new ApplyResult(transition, Failure.INVALID_STATE, callerState.revision());
    }

    static ApplyResult stale(MatchEngine.MatchTransition transition, long revision) {
      return new ApplyResult(transition, Failure.STALE_REVISION, revision);
    }

    public boolean accepted() { return failure == Failure.NONE; }
  }
}
