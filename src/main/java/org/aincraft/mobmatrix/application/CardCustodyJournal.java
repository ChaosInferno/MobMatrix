package org.aincraft.mobmatrix.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;

/**
 * Durable journal for physical-card custody. Implementations persist the complete operation before
 * inventory mutation and persist each restored copy after its primary-thread insertion.
 */
public interface CardCustodyJournal {
  void prepare(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots);

  /** Binds a reservation to the match that will consume it before inventory mutation. */
  default void prepare(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots, String matchId) {
    if (matchId != null) {
      throw new UnsupportedOperationException("journal does not support match-bound custody");
    }
    prepare(operationId, owner, copyIds, snapshots);
  }
  Optional<Entry> find(UUID operationId);

  List<Entry> openEntries();
  /**
   * Enumerates open operation identities without hydrating their rows. Durable implementations
   * override this so malformed rows can still be surfaced individually during startup recovery.
   */
  default List<UUID> openOperationIds() {
    return openEntries().stream().map(Entry::operationId).toList();
  }


  void transition(UUID operationId, CardCustodyPort.OperationStatus status);
  void markRestored(UUID operationId, CardCopyId copyId);

  void addRecovery(UUID operationId, CopyOwner owner, SerializedItemSnapshot snapshot);
  record Entry(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots, CardCustodyPort.OperationStatus status,
      Set<CardCopyId> restoredCopyIds, String matchId) {
    public Entry(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, CardCustodyPort.OperationStatus status,
        Set<CardCopyId> restoredCopyIds) {
      this(operationId, owner, copyIds, snapshots, status, restoredCopyIds, null);
    }

    public Entry {
      operationId = java.util.Objects.requireNonNull(operationId, "operation id is required");
      owner = java.util.Objects.requireNonNull(owner, "owner is required");
      copyIds = List.copyOf(java.util.Objects.requireNonNull(copyIds, "copy ids are required"));
      snapshots = List.copyOf(java.util.Objects.requireNonNull(snapshots, "snapshots are required"));
      status = java.util.Objects.requireNonNull(status, "status is required");
      restoredCopyIds = Set.copyOf(java.util.Objects.requireNonNull(restoredCopyIds,
          "restored copies are required"));
      if (matchId != null && matchId.isBlank()) {
        throw new IllegalArgumentException("match id cannot be blank");
      }
      if (!copyIds.containsAll(restoredCopyIds)) {
        throw new IllegalArgumentException("restored copy is not reserved");
      }
      if (status == CardCustodyPort.OperationStatus.RETURNED
          && !hasExactSnapshots(copyIds, snapshots)) {
        throw new IllegalArgumentException("returned custody is missing exact escrow snapshots");
      }
      if (status == CardCustodyPort.OperationStatus.RETURNED
          && !restoredCopyIds.containsAll(copyIds)) {
        throw new IllegalArgumentException("returned custody has unresolved copies");
      }
      if (status == CardCustodyPort.OperationStatus.ABORTED) {
        Set<CardCopyId> snapshotIds = new java.util.HashSet<>();
        for (SerializedItemSnapshot snapshot : snapshots) {
          if (!snapshotIds.add(snapshot.copyId())) {
            throw new IllegalArgumentException("duplicate aborted snapshot copy");
          }
        }
        if (copyIds.size() != snapshotIds.size()
            || !restoredCopyIds.containsAll(copyIds)
            || !snapshotIds.equals(new java.util.HashSet<>(copyIds))) {
          throw new IllegalArgumentException("aborted custody has unresolved or missing snapshots");
        }
      }
    }

    public List<CardCopyId> expectedCopyIds() { return copyIds; }
    private static boolean hasExactSnapshots(List<CardCopyId> copies,
        List<SerializedItemSnapshot> snapshots) {
      if (copies.size() != new java.util.HashSet<>(copies).size()
          || snapshots.size() != copies.size()) {
        return false;
      }
      Set<CardCopyId> snapshotIds = new java.util.HashSet<>();
      for (SerializedItemSnapshot snapshot : snapshots) {
        if (!snapshotIds.add(snapshot.copyId())) return false;
      }
      return snapshotIds.equals(new java.util.HashSet<>(copies));
    }
  }
}
