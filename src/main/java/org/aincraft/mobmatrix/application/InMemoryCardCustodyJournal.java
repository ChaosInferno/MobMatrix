package org.aincraft.mobmatrix.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;

/**
 * Small compatibility journal for isolated unit tests. Production wiring must use a durable
 * implementation such as {@code SqliteCardRepository}.
 */
public final class InMemoryCardCustodyJournal implements CardCustodyJournal {
  private final Map<UUID, MutableEntry> entries = new HashMap<>();

  @Override
  public synchronized void prepare(
      UUID operationId,
      CopyOwner owner,
      List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots) {
    prepare(operationId, owner, copyIds, snapshots, null);
  }

  @Override
  public synchronized void prepare(
      UUID operationId,
      CopyOwner owner,
      List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots,
      String matchId) {
    List<CardCopyId> copies = List.copyOf(copyIds);
    List<SerializedItemSnapshot> exactSnapshots = List.copyOf(snapshots);
    if (!hasExactSnapshots(copies, exactSnapshots)) {
      throw new IllegalArgumentException("every reserved copy requires an exact item snapshot");
    }
    MutableEntry existing = entries.get(operationId);
    if (existing != null) {
      if (!existing.owner.equals(owner)
          || !existing.copyIds.equals(copies)
          || !java.util.Objects.equals(existing.matchId, matchId)) {
        throw new IllegalStateException("operation does not match existing reservation");
      }
      return;
    }
    for (MutableEntry other : entries.values()) {
      if ((other.status == CardCustodyPort.OperationStatus.PREPARED
              || other.status == CardCustodyPort.OperationStatus.ESCROWED
              || other.status == CardCustodyPort.OperationStatus.RETURN_PENDING)
          && other.copyIds.stream().anyMatch(copies::contains)) {
        throw new IllegalStateException("copy is already reserved");
      }
    }
    entries.put(
        operationId,
        new MutableEntry(
            operationId,
            owner,
            copies,
            exactSnapshots,
            CardCustodyPort.OperationStatus.PREPARED,
            matchId));
  }

  @Override
  public synchronized Optional<Entry> find(UUID operationId) {
    MutableEntry entry = entries.get(operationId);
    return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
  }

  @Override
  public synchronized List<Entry> openEntries() {
    List<Entry> result = new ArrayList<>();
    for (MutableEntry entry : entries.values()) {
      if (entry.status == CardCustodyPort.OperationStatus.PREPARED
          || entry.status == CardCustodyPort.OperationStatus.ESCROWED
          || entry.status == CardCustodyPort.OperationStatus.RETURN_PENDING) {
        result.add(entry.snapshot());
      }
    }
    return List.copyOf(result);
  }

  @Override
  public synchronized Set<CardCopyId> reservedCopyIds() {
    Set<CardCopyId> result = new HashSet<>();
    for (MutableEntry entry : entries.values()) {
      if (entry.status == CardCustodyPort.OperationStatus.PREPARED
          || entry.status == CardCustodyPort.OperationStatus.ESCROWED
          || entry.status == CardCustodyPort.OperationStatus.RETURN_PENDING) {
        result.addAll(entry.copyIds);
      }
    }
    return Set.copyOf(result);
  }

  @Override
  public synchronized void transition(UUID operationId, CardCustodyPort.OperationStatus status) {
    MutableEntry entry = require(operationId);
    if (status == CardCustodyPort.OperationStatus.RETURNED
        && !hasExactSnapshots(entry.copyIds, entry.snapshots)) {
      throw new IllegalStateException("cannot return custody without exact snapshots");
    }
    if (status == CardCustodyPort.OperationStatus.RETURNED
        && !entry.restored.containsAll(entry.copyIds)) {
      throw new IllegalStateException("cannot return custody with unresolved copies");
    }
    if (status == CardCustodyPort.OperationStatus.ABORTED
        && !hasExactSnapshots(entry.copyIds, entry.snapshots)) {
      throw new IllegalStateException("cannot abort custody without exact snapshots");
    }
    if (status == CardCustodyPort.OperationStatus.ABORTED
        && !entry.restored.containsAll(entry.copyIds)) {
      throw new IllegalStateException("cannot abort custody with unresolved copies");
    }
    if (entry.status == status) return;
    if (status == CardCustodyPort.OperationStatus.ESCROWED
        && entry.status != CardCustodyPort.OperationStatus.PREPARED) {
      throw new IllegalStateException("invalid custody transition");
    }
    if (status == CardCustodyPort.OperationStatus.RETURN_PENDING
        && entry.status != CardCustodyPort.OperationStatus.ESCROWED
        && entry.status != CardCustodyPort.OperationStatus.PREPARED) {
      throw new IllegalStateException("invalid custody transition");
    }
    if (status == CardCustodyPort.OperationStatus.RETURNED
        && entry.status != CardCustodyPort.OperationStatus.RETURN_PENDING) {
      throw new IllegalStateException("invalid custody transition");
    }
    if (status == CardCustodyPort.OperationStatus.ABORTED
        && entry.status != CardCustodyPort.OperationStatus.PREPARED
        && entry.status != CardCustodyPort.OperationStatus.ESCROWED
        && entry.status != CardCustodyPort.OperationStatus.RETURN_PENDING
        && entry.status != CardCustodyPort.OperationStatus.RETURNED) {
      throw new IllegalStateException("invalid custody transition");
    }
    entry.status = status;
  }

  @Override
  public synchronized void markRestored(UUID operationId, CardCopyId copyId) {
    MutableEntry entry = require(operationId);
    if (!entry.copyIds.contains(copyId)) throw new IllegalStateException("copy is not reserved");
    entry.restored.add(copyId);
  }

  @Override
  public synchronized void addRecovery(
      UUID operationId, CopyOwner owner, SerializedItemSnapshot snapshot) {
    MutableEntry entry = require(operationId);
    if (!entry.owner.equals(owner) || !entry.copyIds.contains(snapshot.copyId())) {
      throw new IllegalStateException("recovery row does not match reservation");
    }
  }

  private static boolean hasExactSnapshots(
      List<CardCopyId> copies, List<SerializedItemSnapshot> snapshots) {
    if (copies.size() != new HashSet<>(copies).size() || snapshots.size() != copies.size()) {
      return false;
    }
    Set<CardCopyId> snapshotIds = new HashSet<>();
    for (SerializedItemSnapshot snapshot : snapshots) {
      if (!snapshotIds.add(snapshot.copyId())) return false;
    }
    return snapshotIds.equals(new HashSet<>(copies));
  }

  private MutableEntry require(UUID operationId) {
    MutableEntry entry = entries.get(operationId);
    if (entry == null) throw new IllegalStateException("unknown operation: " + operationId);
    return entry;
  }

  private static final class MutableEntry {
    private final UUID operationId;
    private final CopyOwner owner;
    private final List<CardCopyId> copyIds;
    private final List<SerializedItemSnapshot> snapshots;
    private final Set<CardCopyId> restored = new HashSet<>();
    private final String matchId;
    private CardCustodyPort.OperationStatus status;

    private MutableEntry(
        UUID operationId,
        CopyOwner owner,
        List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots,
        CardCustodyPort.OperationStatus status,
        String matchId) {
      this.operationId = operationId;
      this.owner = owner;
      this.copyIds = copyIds;
      this.snapshots = snapshots;
      this.status = status;
      this.matchId = matchId;
    }

    private Entry snapshot() {
      return new Entry(operationId, owner, copyIds, snapshots, status, restored, matchId);
    }
  }
}
