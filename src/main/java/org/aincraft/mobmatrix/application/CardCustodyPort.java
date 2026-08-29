package org.aincraft.mobmatrix.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;

/** Primary-thread custody boundary. Implementations must preserve exact physical copies. */
public interface CardCustodyPort {
  PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId);

  CustodyResult escrow(UUID operationId);

  ReturnResult returnCopies(UUID operationId);

  RecoveryResult recover(UUID operationId);
  /** Open durable operations to reconcile during startup. */
  default List<UUID> openOperationIds() {
    return List.of();
  }

  /** Durable match binding for an open operation, when supported by the adapter. */
  default Optional<org.aincraft.mobmatrix.domain.MatchId> operationMatchId(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    return Optional.empty();
  }

  /** Durable status for an open operation, when supported by the adapter. */
  default Optional<OperationStatus> operationStatus(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    return Optional.empty();
  }
  /** Durable identity of a prepared operation, including its ordered physical copies. */
  default Optional<OperationBinding> operationBinding(UUID operationId) {
    Objects.requireNonNull(operationId, "operation id is required");
    return Optional.empty();
  }

  record OperationBinding(CopyOwner owner, List<CardCopyId> copyIds, OperationStatus status,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    public OperationBinding {
      Objects.requireNonNull(owner, "owner is required");
      copyIds = List.copyOf(Objects.requireNonNull(copyIds, "copy ids are required"));
      Objects.requireNonNull(status, "status is required");
    }
  }

  default PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId,
      org.aincraft.mobmatrix.domain.MatchId matchId) {
    Objects.requireNonNull(matchId, "match id is required");
    return PrepareResult.rejected(operationId, owner, copyIds, Failure.MISMATCHED_OPERATION);
  }


  default PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, String operationId) {
    return prepare(owner, copyIds, parseOperation(operationId));
  }

  default CustodyResult escrow(String operationId) {
    return escrow(parseOperation(operationId));
  }

  default ReturnResult returnCopies(String operationId) {
    return returnCopies(parseOperation(operationId));
  }

  default RecoveryResult recover(String operationId) {
    return recover(parseOperation(operationId));
  }

  /**
   * Adapters must implement durable abort semantics. The default is explicitly unsupported and
   * cannot claim that an operation reached ABORTED.
   */
  default CustodyResult abort(UUID operationId) {
    return CustodyResult.rejected(Objects.requireNonNull(operationId), OperationStatus.ABORTED,
        Failure.INVALID_STATE);
  }

  private static UUID parseOperation(String operationId) {
    return UUID.fromString(Objects.requireNonNull(operationId, "operation id is required"));
  }

  enum OperationStatus {
    PREPARED,
    ESCROWED,
    RETURN_PENDING,
    RETURNED,
    ABORTED
  }

  enum Failure {
    NONE,
    INVALID_OWNER,
    INVALID_ITEM,
    INVALID_COPY_SET,
    DUPLICATE_COPY,
    COPY_NOT_FOUND,
    STALE_REVISION,
    MISMATCHED_OPERATION,
    MISMATCHED_OWNER,
    INVENTORY_CHANGED,
    NO_ROOM,
    PLAYER_OFFLINE,
    DATABASE_ERROR,
    INVALID_STATE
  }

  record PrepareResult(UUID operationId, CopyOwner owner, List<CardCopyId> expectedCopyIds,
      List<SerializedItemSnapshot> snapshots, Failure failure) {
    public PrepareResult {
      Objects.requireNonNull(operationId, "operation id is required");
      Objects.requireNonNull(owner, "owner is required");
      expectedCopyIds = List.copyOf(Objects.requireNonNull(expectedCopyIds, "copy ids are required"));
      snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots are required"));
      Objects.requireNonNull(failure, "failure is required");
    }

    public static PrepareResult success(UUID operationId, CopyOwner owner,
        List<CardCopyId> copyIds, List<SerializedItemSnapshot> snapshots) {
      return new PrepareResult(operationId, owner, copyIds, snapshots, Failure.NONE);
    }

    public static PrepareResult rejected(UUID operationId, CopyOwner owner,
        List<CardCopyId> copyIds, Failure failure) {
      return new PrepareResult(operationId, owner, copyIds, List.of(), failure);
    }

    public boolean accepted() { return failure == Failure.NONE; }
    public boolean success() { return accepted(); }
    public List<CardCopyId> copyIds() { return expectedCopyIds; }
    public List<CardCopyId> expectedCopies() { return expectedCopyIds; }
  }

  record CustodyResult(UUID operationId, OperationStatus status, Failure failure) {
    public CustodyResult {
      Objects.requireNonNull(operationId, "operation id is required");
      Objects.requireNonNull(status, "status is required");
      Objects.requireNonNull(failure, "failure is required");
    }

    public static CustodyResult success(UUID operationId, OperationStatus status) {
      return new CustodyResult(operationId, status, Failure.NONE);
    }

    public static CustodyResult rejected(UUID operationId, OperationStatus status, Failure failure) {
      return new CustodyResult(operationId, status, failure);
    }

    public boolean accepted() { return failure == Failure.NONE; }
    public boolean success() { return accepted(); }
  }

  record ReturnResult(UUID operationId, OperationStatus status, List<CardCopyId> expectedCopyIds,
      List<CardCopyId> returnedCopyIds, List<CardCopyId> missingCopyIds,
      List<CardCopyId> recoveryCopyIds, Failure failure) {
    public ReturnResult {
      Objects.requireNonNull(operationId, "operation id is required");
      Objects.requireNonNull(status, "status is required");
      expectedCopyIds = List.copyOf(Objects.requireNonNull(expectedCopyIds));
      returnedCopyIds = List.copyOf(Objects.requireNonNull(returnedCopyIds));
      missingCopyIds = List.copyOf(Objects.requireNonNull(missingCopyIds));
      recoveryCopyIds = List.copyOf(Objects.requireNonNull(recoveryCopyIds));
      Objects.requireNonNull(failure, "failure is required");
    }

    public static ReturnResult returned(UUID operationId, List<CardCopyId> expectedCopyIds) {
      return new ReturnResult(operationId, OperationStatus.RETURNED, expectedCopyIds, expectedCopyIds,
          List.of(), List.of(), Failure.NONE);
    }

    public static ReturnResult pending(UUID operationId, List<CardCopyId> expectedCopyIds,
        List<CardCopyId> returnedCopyIds, List<CardCopyId> missingCopyIds,
        List<CardCopyId> recoveryCopyIds) {
      return new ReturnResult(operationId, OperationStatus.RETURN_PENDING, expectedCopyIds,
          returnedCopyIds, missingCopyIds, recoveryCopyIds, Failure.NO_ROOM);
    }

    public boolean accepted() { return failure == Failure.NONE || status == OperationStatus.RETURN_PENDING; }
    public boolean complete() { return status == OperationStatus.RETURNED && missingCopyIds.isEmpty(); }
    public List<CardCopyId> missing() { return missingCopyIds; }
    public List<CardCopyId> recovery() { return recoveryCopyIds; }
  }

  record RecoveryResult(UUID operationId, OperationStatus status, List<CardCopyId> expectedCopyIds,
      List<CardCopyId> recoveredCopyIds, List<CardCopyId> pendingCopyIds, Failure failure) {
    public RecoveryResult {
      Objects.requireNonNull(operationId, "operation id is required");
      Objects.requireNonNull(status, "status is required");
      expectedCopyIds = List.copyOf(Objects.requireNonNull(expectedCopyIds));
      recoveredCopyIds = List.copyOf(Objects.requireNonNull(recoveredCopyIds));
      pendingCopyIds = List.copyOf(Objects.requireNonNull(pendingCopyIds));
      Objects.requireNonNull(failure, "failure is required");
    }

    public static RecoveryResult none(UUID operationId) {
      return new RecoveryResult(operationId, OperationStatus.RETURNED, List.of(), List.of(), List.of(), Failure.NONE);
    }

    public static RecoveryResult complete(UUID operationId, List<CardCopyId> expectedCopyIds) {
      return new RecoveryResult(operationId, OperationStatus.RETURNED, expectedCopyIds, expectedCopyIds,
          List.of(), Failure.NONE);
    }

    public boolean complete() { return pendingCopyIds.isEmpty() && failure == Failure.NONE; }
    public List<CardCopyId> recovery() { return pendingCopyIds; }

    public static RecoveryResult failed(UUID operationId, OperationStatus status, Failure failure) {
      return new RecoveryResult(operationId, status, List.of(), List.of(), List.of(), failure);
    }
  }
}
