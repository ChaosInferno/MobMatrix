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
  public synchronized void prepare(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
      List<SerializedItemSnapshot> snapshots) {
    MutableEntry existing = entries.get(operationId);
    if (existing != null) {
      if (!existing.owner.equals(owner) || !existing.copyIds.equals(copyIds)) {
        throw new IllegalStateException("operation does not match existing reservation");
      }
      return;
    }
    entries.put(operationId, new MutableEntry(operationId, owner, List.copyOf(copyIds),
        List.copyOf(snapshots), CardCustodyPort.OperationStatus.PREPARED));
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
  public synchronized void transition(UUID operationId, CardCustodyPort.OperationStatus status) {
    MutableEntry entry = require(operationId);
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
        && entry.status == CardCustodyPort.OperationStatus.RETURNED) {
      // A failed start may retain a terminal journal row after every copy was returned.
    } else if (status == CardCustodyPort.OperationStatus.ABORTED
        && entry.status != CardCustodyPort.OperationStatus.PREPARED
        && entry.status != CardCustodyPort.OperationStatus.ESCROWED
        && entry.status != CardCustodyPort.OperationStatus.RETURN_PENDING) {
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
  public synchronized void addRecovery(UUID operationId, CopyOwner owner,
      SerializedItemSnapshot snapshot) {
    MutableEntry entry = require(operationId);
    if (!entry.owner.equals(owner) || !entry.copyIds.contains(snapshot.copyId())) {
      throw new IllegalStateException("recovery row does not match reservation");
    }
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
    private CardCustodyPort.OperationStatus status;

    private MutableEntry(UUID operationId, CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, CardCustodyPort.OperationStatus status) {
      this.operationId = operationId;
      this.owner = owner;
      this.copyIds = copyIds;
      this.snapshots = snapshots;
      this.status = status;
    }

    private Entry snapshot() {
      return new Entry(operationId, owner, copyIds, snapshots, status, restored);
    }
  }
}
