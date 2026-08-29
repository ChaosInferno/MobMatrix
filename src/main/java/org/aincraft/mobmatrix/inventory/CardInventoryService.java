package org.aincraft.mobmatrix.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.CardCustodyPort.CustodyResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.Failure;
import org.aincraft.mobmatrix.application.CardCustodyPort.PrepareResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.RecoveryResult;
import org.aincraft.mobmatrix.application.CardCustodyPort.ReturnResult;
import org.aincraft.mobmatrix.application.SerializedItemSnapshot;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/** Primary-thread Paper adapter that journals and moves exact physical card items. */
public final class CardInventoryService implements CardCustodyPort {
  private final CardItemCodec codec;
  private final PlayerLookup players;
  private final org.aincraft.mobmatrix.application.CardCustodyJournal journal;
  private final Map<UUID, PreparedOperation> operations = new HashMap<>();
  private final Map<UUID, Set<CardCopyId>> malformedOperations = new HashMap<>();

  /** Production constructor: the journal must be durable and is hydrated before use. */
  public CardInventoryService(CardItemCodec codec, PlayerLookup players,
      org.aincraft.mobmatrix.application.CardCustodyJournal journal) {
    this.codec = Objects.requireNonNull(codec, "codec is required");
    this.players = Objects.requireNonNull(players, "player lookup is required");
    this.journal = Objects.requireNonNull(journal, "custody journal is required");
    hydrate();
  }

  /** Compatibility constructor for isolated tests; production wiring must supply a durable journal. */
  public CardInventoryService(CardItemCodec codec) {
    this(codec, id -> org.bukkit.Bukkit.getPlayer(id),
        new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal());
  }

  /** Compatibility constructor for isolated tests; production wiring must supply a durable journal. */
  public CardInventoryService(CardItemCodec codec, PlayerLookup players) {
    this(codec, players, new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal());
  }

  private void hydrate() {
    for (UUID operationId : journal.openOperationIds()) {
      try {
        var entry = journal.find(operationId).orElse(null);
        if (entry != null) {
          operations.put(entry.operationId(), new PreparedOperation(entry.owner(), entry.copyIds(),
              entry.snapshots(), entry.status(), entry.restoredCopyIds(), entry.matchId()));
        }
      } catch (RuntimeException exception) {
        if (exception instanceof CardCustodyPort.CustodyIntegrityFailure failure) {
          // A malformed open row has no trustworthy status. Keep every parsed physical identity
          // reserved until the journal can prove the operation reached a terminal status.
          malformedOperations.put(failure.operationId(), Set.copyOf(failure.expectedCopyIds()));
        }
        // Keep malformed durable rows out of the in-memory cache. Startup recovery will query the
        // journal again and convert the structured integrity failure into a pending result.
      }
    }
  }

  /** Refresh malformed rows, clearing protection only after durable terminal proof. */
  private void refreshMalformedOperations() {
    var iterator = malformedOperations.entrySet().iterator();
    while (iterator.hasNext()) {
      var malformed = iterator.next();
      try {
        var entry = journal.find(malformed.getKey()).orElse(null);
        if (entry == null) continue;
        if (entry.status() == OperationStatus.RETURNED || entry.status() == OperationStatus.ABORTED) {
          iterator.remove();
        } else {
          operations.put(entry.operationId(), new PreparedOperation(entry.owner(), entry.copyIds(),
              entry.snapshots(), entry.status(), entry.restoredCopyIds(), entry.matchId()));
          iterator.remove();
        }
      } catch (RuntimeException ignored) {
        // Integrity and unknown read failures both retain fail-closed protection.
      }
    }
  }

  @Override
  public synchronized PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
    return prepare(owner, copyIds, operationId, null);
  }

  @Override
  public synchronized PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds,
      UUID operationId, org.aincraft.mobmatrix.domain.MatchId matchId) {
    requirePrimaryThread();
    Objects.requireNonNull(operationId, "operation id is required");
    if (owner == null || !owner.isPlayer()) return PrepareResult.rejected(operationId,
        owner == null ? CopyOwner.house() : owner, copyIds == null ? List.of() : copyIds,
        Failure.INVALID_OWNER);
    if (matchId != null) Objects.requireNonNull(matchId, "match id is required");
    List<CardCopyId> copies;
    try {
      copies = exactCopies(copyIds);
    } catch (IllegalArgumentException exception) {
      return PrepareResult.rejected(operationId, owner, copyIds == null ? List.of() : copyIds,
          Failure.INVALID_COPY_SET);
    }
    PreparedOperation existing = operation(operationId);
    if (existing != null) {
      if (!existing.owner.equals(owner) || !existing.copyIds.equals(copies)
          || !Objects.equals(existing.matchId, matchId == null ? null : matchId.value())) {
        return PrepareResult.rejected(operationId, owner, copies, Failure.MISMATCHED_OPERATION);
      }
      if (existing.status == OperationStatus.ABORTED || existing.status == OperationStatus.RETURNED) {
        return PrepareResult.rejected(operationId, owner, copies, Failure.INVALID_STATE);
      }
      return PrepareResult.success(operationId, owner, copies, existing.snapshots);
    }
    Player player = players.find(owner.playerId());
    if (player == null) return PrepareResult.rejected(operationId, owner, copies, Failure.PLAYER_OFFLINE);
    Scan scan = scan(player.getInventory());
    if (scan.duplicate) return PrepareResult.rejected(operationId, owner, copies, Failure.DUPLICATE_COPY);
    if (scan.counterfeit) return PrepareResult.rejected(operationId, owner, copies, Failure.INVALID_ITEM);
    List<SerializedItemSnapshot> snapshots = new ArrayList<>();
    for (CardCopyId copy : copies) {
      Located located = scan.cards.get(copy);
      if (located == null) return PrepareResult.rejected(operationId, owner, copies, Failure.COPY_NOT_FOUND);
      if (!located.data.owner().equals(owner)) return PrepareResult.rejected(operationId, owner, copies,
          Failure.MISMATCHED_OWNER);
      snapshots.add(new SerializedItemSnapshot(copy, serialize(located.item.clone())));
    }
    // The durable PREPARED row is written before any inventory mutation.
    journal.prepare(operationId, owner, copies, snapshots, matchId == null ? null : matchId.value());
    PreparedOperation operation = new PreparedOperation(owner, copies, List.copyOf(snapshots),
        OperationStatus.PREPARED, Set.of(), matchId == null ? null : matchId.value());
    operations.put(operationId, operation);
    return PrepareResult.success(operationId, owner, copies, snapshots);
  }

  /** Convenience overload for code already holding a primary-thread Player reference. */
  public synchronized PrepareResult prepare(Player player, List<CardCopyId> copyIds, UUID operationId) {
    Objects.requireNonNull(player, "player is required");
    return prepare(CopyOwner.player(player.getUniqueId()), copyIds, operationId);
  }

  @Override
  public synchronized CustodyResult escrow(UUID operationId) {
    requirePrimaryThread();
    Objects.requireNonNull(operationId, "operation id is required");
    PreparedOperation operation = operation(operationId);
    if (operation == null) return CustodyResult.rejected(operationId, OperationStatus.ABORTED, Failure.MISMATCHED_OPERATION);
    if (operation.status == OperationStatus.ESCROWED || operation.status == OperationStatus.RETURN_PENDING
        || operation.status == OperationStatus.RETURNED) return CustodyResult.success(operationId, operation.status);
    if (operation.status != OperationStatus.PREPARED) return CustodyResult.rejected(operationId, operation.status, Failure.INVALID_STATE);
    Player player = players.find(operation.owner.playerId());
    if (player == null) return CustodyResult.rejected(operationId, operation.status, Failure.PLAYER_OFFLINE);
    Scan scan = scan(player.getInventory());
    if (scan.duplicate) return CustodyResult.rejected(operationId, operation.status, Failure.DUPLICATE_COPY);
    if (scan.counterfeit) return CustodyResult.rejected(operationId, operation.status, Failure.INVALID_ITEM);
    List<Integer> slots = new ArrayList<>();
    for (CardCopyId copy : operation.copyIds) {
      Located located = scan.cards.get(copy);
      if (located == null) return CustodyResult.rejected(operationId, operation.status,
          Failure.INVENTORY_CHANGED);
      if (!located.data.owner().equals(operation.owner)) {
        return CustodyResult.rejected(operationId, operation.status, Failure.MISMATCHED_OWNER);
      }
      SerializedItemSnapshot snapshot = operation.snapshots.stream()
          .filter(value -> value.copyId().equals(copy)).findFirst().orElse(null);
      if (snapshot == null || !Arrays.equals(serialize(located.item.clone()), snapshot.bytes())) {
        return CustodyResult.rejected(operationId, operation.status, Failure.INVENTORY_CHANGED);
      }
      slots.add(located.slot);
    }
    for (int slot : slots) player.getInventory().setItem(slot, null);
    journal.transition(operationId, OperationStatus.ESCROWED);
    operation.status = OperationStatus.ESCROWED;
    return CustodyResult.success(operationId, operation.status);
  }

  @Override
  public synchronized CustodyResult abort(UUID operationId) {
    requirePrimaryThread();
    Objects.requireNonNull(operationId, "operation id is required");
    PreparedOperation operation = operation(operationId);
    if (operation == null) return CustodyResult.rejected(operationId, OperationStatus.ABORTED, Failure.MISMATCHED_OPERATION);
    if (operation.status == OperationStatus.ABORTED) return CustodyResult.success(operationId, OperationStatus.ABORTED);
    if (operation.status == OperationStatus.PREPARED
        || operation.status == OperationStatus.ESCROWED
        || operation.status == OperationStatus.RETURN_PENDING) {
      ReturnResult result = returnCopies(operationId);
      if (!result.complete()) return CustodyResult.rejected(operationId, operation.status, Failure.NO_ROOM);
    }
    if (operation.status == OperationStatus.RETURNED) {
      journal.transition(operationId, OperationStatus.ABORTED);
      operation.status = OperationStatus.ABORTED;
      return CustodyResult.success(operationId, OperationStatus.ABORTED);
    }
    return CustodyResult.rejected(operationId, operation.status, Failure.INVALID_STATE);
  }

  @Override
  public synchronized ReturnResult returnCopies(UUID operationId) {
    requirePrimaryThread();
    Objects.requireNonNull(operationId, "operation id is required");
    PreparedOperation operation = operation(operationId);
    if (operation == null) return new ReturnResult(operationId, OperationStatus.ABORTED, List.of(), List.of(),
        List.of(), List.of(), Failure.MISMATCHED_OPERATION);
    if (operation.status == OperationStatus.RETURNED) {
      return ReturnResult.returned(operationId, operation.copyIds);
    }
    if (operation.status != OperationStatus.PREPARED && operation.status != OperationStatus.ESCROWED
        && operation.status != OperationStatus.RETURN_PENDING) {
      return new ReturnResult(operationId, operation.status, operation.copyIds, List.of(), operation.copyIds,
          operation.copyIds, Failure.INVALID_STATE);
    }
    if (operation.status == OperationStatus.PREPARED || operation.status == OperationStatus.ESCROWED) {
      journal.transition(operationId, OperationStatus.RETURN_PENDING);
      operation.status = OperationStatus.RETURN_PENDING;
      if (operation.status == OperationStatus.RETURN_PENDING) {
        for (SerializedItemSnapshot snapshot : operation.snapshots) {
          if (!operation.restored.contains(snapshot.copyId())) {
            journal.addRecovery(operationId, operation.owner, snapshot);
          }
        }
      }
    }
    Player player = players.find(operation.owner.playerId());
    if (player == null) {
      List<CardCopyId> pending = new ArrayList<>();
      for (SerializedItemSnapshot snapshot : operation.snapshots) {
        if (!operation.restored.contains(snapshot.copyId())) {
          journal.addRecovery(operationId, operation.owner, snapshot);
          pending.add(snapshot.copyId());
        }
      }
      return ReturnResult.pending(operationId, operation.copyIds, restored(operation), pending, pending);
    }
    return restore(operationId, operation, player.getInventory());
  }
  /** Test-only restoration seam; production restoration always resolves the owner's inventory. */
  synchronized ReturnResult returnCopies(UUID operationId, Inventory inventory) {
    requirePrimaryThread();
    PreparedOperation operation = operation(Objects.requireNonNull(operationId));
    if (operation == null) return new ReturnResult(operationId, OperationStatus.ABORTED, List.of(), List.of(),
        List.of(), List.of(), Failure.MISMATCHED_OPERATION);
    if (operation.owner.isPlayer()) {
      Player owner = players.find(operation.owner.playerId());
      if (owner == null || owner.getInventory() != Objects.requireNonNull(inventory,
          "inventory is required")) {
        return new ReturnResult(operationId, operation.status, operation.copyIds, List.of(),
            operation.copyIds, operation.copyIds, Failure.MISMATCHED_OWNER);
      }
    } else {
      return new ReturnResult(operationId, operation.status, operation.copyIds, List.of(),
          operation.copyIds, operation.copyIds, Failure.MISMATCHED_OWNER);
    }
    if (operation.status == OperationStatus.RETURNED) {
      return ReturnResult.returned(operationId, operation.copyIds);
    }
    if (operation.status != OperationStatus.PREPARED && operation.status != OperationStatus.ESCROWED
        && operation.status != OperationStatus.RETURN_PENDING) {
      return new ReturnResult(operationId, operation.status, operation.copyIds, List.of(), operation.copyIds,
          operation.copyIds, Failure.INVALID_STATE);
    }
    if (operation.status == OperationStatus.PREPARED || operation.status == OperationStatus.ESCROWED) {
      journal.transition(operationId, OperationStatus.RETURN_PENDING);
      operation.status = OperationStatus.RETURN_PENDING;
      for (SerializedItemSnapshot snapshot : operation.snapshots) {
        if (!operation.restored.contains(snapshot.copyId())) {
          journal.addRecovery(operationId, operation.owner, snapshot);
        }
      }
    }
    return restore(operationId, operation, inventory);
  }
  @Override
  public synchronized RecoveryResult recover(UUID operationId) {
    requirePrimaryThread();
    Objects.requireNonNull(operationId, "operation id is required");
    PreparedOperation operation = operation(operationId);
    if (operation == null) return RecoveryResult.none(operationId);
    if (operation.status == OperationStatus.RETURNED || operation.status == OperationStatus.ABORTED) {
      return RecoveryResult.complete(operationId, operation.status, operation.copyIds);
    }
    if (operation.status == OperationStatus.PREPARED) {
      // PREPARED may represent a crash after physical removal but before ESCROWED was durable.
      // Treat it as a return in progress rather than retrying removal forever.
      journal.transition(operationId, OperationStatus.RETURN_PENDING);
      operation.status = OperationStatus.RETURN_PENDING;
      for (SerializedItemSnapshot snapshot : operation.snapshots) {
        if (!operation.restored.contains(snapshot.copyId())) {
          journal.addRecovery(operationId, operation.owner, snapshot);
        }
      }
    }
    ReturnResult result = returnCopies(operationId);
    if (result.complete()) {
      return RecoveryResult.complete(operationId, result.status(), operation.copyIds);
    }
    return new RecoveryResult(operationId, operation.status, operation.copyIds, result.returnedCopyIds(),
        result.recoveryCopyIds(), result.failure());
  }
  @Override
  public synchronized List<UUID> openOperationIds() {
    return journal.openOperationIds();
  }
  @Override
  public synchronized java.util.Optional<org.aincraft.mobmatrix.domain.MatchId> operationMatchId(
      UUID operationId) {
    var entry = journal.find(Objects.requireNonNull(operationId)).orElse(null);
    return entry == null || entry.matchId() == null
        ? java.util.Optional.empty()
        : java.util.Optional.of(new org.aincraft.mobmatrix.domain.MatchId(entry.matchId()));
  }

  @Override
  public synchronized java.util.Optional<OperationStatus> operationStatus(UUID operationId) {
    var entry = journal.find(Objects.requireNonNull(operationId)).orElse(null);
    return entry == null ? java.util.Optional.empty()
        : java.util.Optional.of(entry.status());
  }

  @Override
  public synchronized java.util.Optional<CardCustodyPort.OperationBinding> operationBinding(
      UUID operationId) {
    var entry = journal.find(Objects.requireNonNull(operationId)).orElse(null);
    if (entry == null) return java.util.Optional.empty();
    return java.util.Optional.of(new CardCustodyPort.OperationBinding(entry.owner(), entry.copyIds(),
        entry.status(), entry.matchId() == null ? null
            : new org.aincraft.mobmatrix.domain.MatchId(entry.matchId())));
  }



  private PreparedOperation operation(UUID operationId) {
    var entry = journal.find(operationId).orElse(null);
    if (entry == null) {
      operations.remove(operationId);
      return null;
    }
    PreparedOperation existing = operations.get(operationId);
    if (existing == null) {
      existing = new PreparedOperation(entry.owner(), entry.copyIds(), entry.snapshots(),
          entry.status(), entry.restoredCopyIds(), entry.matchId());
      operations.put(operationId, existing);
    } else {
      // Durable transitions can be made by startup recovery or another adapter instance.
      // Refresh the cache before returning any result so later recover/abort calls cannot
      // act on a stale PREPARED/ESCROWED status.
      existing.status = entry.status();
      existing.restored.clear();
      existing.restored.addAll(entry.restoredCopyIds());
    }
    return existing;
  }

  private static List<CardCopyId> restored(PreparedOperation operation) {
    List<CardCopyId> result = new ArrayList<>();
    for (CardCopyId copy : operation.copyIds) if (operation.restored.contains(copy)) result.add(copy);
    return List.copyOf(result);
  }
  public synchronized boolean isReserved(CardCopyId copyId) {
    refreshMalformedOperations();
    for (PreparedOperation operation : operations.values()) {
      if (operation.status == OperationStatus.PREPARED || operation.status == OperationStatus.ESCROWED
          || operation.status == OperationStatus.RETURN_PENDING) {
        if (operation.copyIds.contains(copyId)) return true;
      }
    }
    for (Set<CardCopyId> copies : malformedOperations.values()) {
      if (copies.contains(copyId)) return true;
    }
    return false;
  }

  public boolean isReserved(ItemStack item) {
    CardItemCodec.ReadResult result = codec.read(item);
    return result.isValid() && isReserved(result.data().copyId());
  }

  public synchronized Set<CardCopyId> reservedCopyIds() {
    refreshMalformedOperations();
    Set<CardCopyId> result = new HashSet<>();
    for (PreparedOperation operation : operations.values()) {
      if (operation.status == OperationStatus.PREPARED || operation.status == OperationStatus.ESCROWED
          || operation.status == OperationStatus.RETURN_PENDING) result.addAll(operation.copyIds);
    }
    for (Set<CardCopyId> copies : malformedOperations.values()) result.addAll(copies);
    return Set.copyOf(result);
  }


  public boolean handleInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getCurrentItem()) || isReserved(event.getCursor());
    if (event.getHotbarButton() >= 0) {
      reserved |= isReserved(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()));
    }
    if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
      reserved |= isReserved(event.getWhoClicked().getInventory().getItemInOffHand());
    }
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  public boolean handleInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = event.getNewItems().values().stream().anyMatch(this::isReserved)
        || isReserved(event.getOldCursor());
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  public boolean handleDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getItemDrop().getItemStack());
    if (reserved) event.setCancelled(true);
    return reserved;
  }


  /** Quit inventories remain owned by the player; callers must not transfer reserved items. */
  public boolean hasReservedInventory(Inventory inventory) {
    requirePrimaryThread();
    Objects.requireNonNull(inventory, "inventory is required");
    return scan(inventory).cards.keySet().stream().anyMatch(this::isReserved);
  }
  public boolean handleNumberKey(org.bukkit.event.inventory.InventoryClickEvent event) {
    return handleInventoryClick(event);
  }
  public boolean handlePlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    // Death drops are mutable and may omit cards retained by the authoritative player inventory.
    boolean reserved = event.getDrops().stream().anyMatch(this::isReserved)
        || hasReservedInventory(event.getEntity().getInventory());
    if (reserved) event.setKeepInventory(true);
    return reserved;
  }

  public boolean handleSwap(org.bukkit.event.inventory.InventoryClickEvent event) {
    return handleInventoryClick(event);
  }
  /** Cancels offhand swaps that would move a reserved card outside custody. */
  public boolean handlePlayerSwapHandItems(
      org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getMainHandItem()) || isReserved(event.getOffHandItem());
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  /** Cancels automated hopper-style moves touching any reserved stack in either inventory. */
  public boolean handleInventoryMoveItem(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getItem())
        || containsReserved(event.getSource()) || containsReserved(event.getDestination());
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  /** Cancels entity pickup transfers of reserved physical cards. */
  public boolean handleEntityPickupItem(org.bukkit.event.entity.EntityPickupItemEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getItem().getItemStack());
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  /** Cancels rotation or placement interactions with item frames while holding reserved cards. */
  public boolean handlePlayerInteractEntity(
      org.bukkit.event.player.PlayerInteractEntityEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean targetIsItemFrame = event.getRightClicked() instanceof org.bukkit.entity.ItemFrame;
    boolean reservedMainHand = isReserved(event.getPlayer().getInventory().getItemInMainHand());
    boolean reservedOffHand = isReserved(event.getPlayer().getInventory().getItemInOffHand());
    boolean reserved = shouldCancelItemFrameInteraction(targetIsItemFrame, reservedMainHand,
        reservedOffHand);
    if (reserved) event.setCancelled(true);
    return reserved;
  }

  private boolean containsReserved(Inventory inventory) {
    Objects.requireNonNull(inventory, "inventory is required");
    for (ItemStack item : inventory.getContents()) {
      if (isReserved(item)) return true;
    }
    return false;
  }
  /** Cancels automated inventory pickup transfers of reserved physical cards. */
  public boolean handleInventoryPickupItem(
      org.bukkit.event.inventory.InventoryPickupItemEvent event) {
    requirePrimaryThread();
    Objects.requireNonNull(event, "event is required");
    boolean reserved = isReserved(event.getItem().getItemStack());
    if (reserved) event.setCancelled(true);
    return reserved;
  }



  static ReturnResult restoreAmbiguous(UUID operationId, CopyOwner owner,
      List<CardCopyId> copyIds, List<SerializedItemSnapshot> snapshots,
      Set<CardCopyId> restoredCopyIds,
      org.aincraft.mobmatrix.application.CardCustodyJournal journal) {

    Objects.requireNonNull(operationId, "operation id is required");
    Objects.requireNonNull(owner, "owner is required");
    Objects.requireNonNull(copyIds, "copy ids are required");
    Objects.requireNonNull(snapshots, "snapshots are required");
    Objects.requireNonNull(restoredCopyIds, "restored copies are required");
    Objects.requireNonNull(journal, "journal is required");
    journal.transition(operationId, OperationStatus.RETURN_PENDING);
    List<CardCopyId> returned = new ArrayList<>(restoredCopyIds);
    List<CardCopyId> pending = new ArrayList<>();
    for (SerializedItemSnapshot snapshot : snapshots) {
      if (!restoredCopyIds.contains(snapshot.copyId())) {
        journal.addRecovery(operationId, owner, snapshot);
        pending.add(snapshot.copyId());
      }
    }
    return ReturnResult.pending(operationId, copyIds, returned, pending, pending);
  }
  private ReturnResult restore(UUID operationId, PreparedOperation operation, Inventory inventory) {
    // A restored ledger row records a prior successful insertion, not permanent proof that the
    // item remains in the owner's inventory. Reconcile every reserved UUID against a fresh scan.
    List<CardCopyId> returned = new ArrayList<>();
    Scan scan = scan(inventory);
    // Never acknowledge any copy while the scan is ambiguous. A duplicate UUID or invalid
    // identity-bearing item means there are multiple or untrusted physical candidates; leave
    // every snapshot durable and pending until a later scan reconciles the inventory.
    if (scan.duplicate || scan.counterfeit) {
      return restoreAmbiguous(operationId, operation.owner, operation.copyIds, operation.snapshots,
          Set.of(), journal);
    }
    List<CardCopyId> missing = new ArrayList<>();
    for (SerializedItemSnapshot snapshot : operation.snapshots) {
      CardCopyId copy = snapshot.copyId();
      Located alreadyPresent = scan.cards.get(copy);
      if (alreadyPresent != null) {
        byte[] currentBytes = serialize(alreadyPresent.item.clone());
        if (exactItemMatchesSnapshot(CardItemCodec.ReadResult.valid(alreadyPresent.data), copy,
            operation.owner, currentBytes, snapshot.bytes())) {
          journal.markRestored(operationId, copy);
          operation.restored.add(copy);
          returned.add(copy);
          continue;
        }
        if (!alreadyPresent.data.owner().equals(operation.owner)) {
          journal.addRecovery(operationId, operation.owner, snapshot);
          missing.add(copy);
          continue;
        }
        // Same UUID with changed metadata is not the escrowed physical card. Replace it
        // with the durable bytes; never mark a modified item as restored.
        ItemStack exact = validatedSnapshot(snapshot, operation.owner);
        if (exact == null) {
          journal.addRecovery(operationId, operation.owner, snapshot);
          missing.add(copy);
          continue;
        }
        inventory.setItem(alreadyPresent.slot, null);
        Map<Integer, ItemStack> leftovers = inventory.addItem(exact);
        if (leftovers.isEmpty()) {
          journal.markRestored(operationId, copy);
          operation.restored.add(copy);
          returned.add(copy);
        } else {
          inventory.setItem(alreadyPresent.slot, alreadyPresent.item);
          journal.addRecovery(operationId, operation.owner, snapshot);
          missing.add(copy);
        }
        continue;
      }
      // The UUID may already be in the durable restored ledger but absent after downtime. Try
      // reinserting its exact snapshot rather than trusting that stale ledger row.
      ItemStack item = validatedSnapshot(snapshot, operation.owner);
      if (item == null) {
        journal.addRecovery(operationId, operation.owner, snapshot);
        missing.add(copy);
        continue;
      }
      Map<Integer, ItemStack> leftovers = inventory.addItem(item);
      if (leftovers.isEmpty()) {
        journal.markRestored(operationId, copy);
        operation.restored.add(copy);
        returned.add(copy);
      } else {
        journal.addRecovery(operationId, operation.owner, snapshot);
        missing.add(copy);
      }
    }
    if (missing.isEmpty() && operation.restored.containsAll(operation.copyIds)) {
      journal.transition(operationId, OperationStatus.RETURNED);
      operation.status = OperationStatus.RETURNED;
      return new ReturnResult(operationId, OperationStatus.RETURNED, operation.copyIds, returned,
          List.of(), List.of(), Failure.NONE);
    }
    for (CardCopyId copy : operation.copyIds) {
      if (!operation.restored.contains(copy) && !missing.contains(copy)) {
        SerializedItemSnapshot snapshot = operation.snapshots.stream()
            .filter(value -> value.copyId().equals(copy)).findFirst().orElse(null);
        if (snapshot != null) journal.addRecovery(operationId, operation.owner, snapshot);
        missing.add(copy);
      }
    }
    return ReturnResult.pending(operationId, operation.copyIds, returned, missing, missing);
  }

  private ItemStack validatedSnapshot(SerializedItemSnapshot snapshot, CopyOwner owner) {
    try {
      ItemStack item = deserialize(snapshot.bytes());
      CardItemCodec.ReadResult decoded = codec.read(item);
      if (!decoded.isValid() || !decoded.data().copyId().equals(snapshot.copyId())
          || !decoded.data().owner().equals(owner)) {
        return null;
      }
      return item;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static void requirePrimaryThread() {
    try {
      if (!org.bukkit.Bukkit.isPrimaryThread()) {
        throw new IllegalStateException("inventory custody must run on the Paper primary thread");
      }
    } catch (NullPointerException unavailable) {
      // Unit tests may use the Paper API without a running server.
    } catch (IllegalStateException unavailable) {
      // Unit tests may use the Paper API without a running server.
      if (unavailable.getMessage() != null
          && unavailable.getMessage().contains("primary thread")) throw unavailable;
    }
  }

  private Scan scan(Inventory inventory) {
    Map<CardCopyId, Located> cards = new HashMap<>();
    boolean duplicate = false;
    boolean counterfeit = false;
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack item = inventory.getItem(slot);
      CardItemCodec.ReadResult result = codec.read(item);
      if (!result.isValid()) {
        if (isCounterfeit(result, codec.hasIdentity(item))) counterfeit = true;
        continue;
      }
      if (cards.putIfAbsent(result.data().copyId(), new Located(slot, item, result.data())) != null) {
        duplicate = true;
      }
    }
    return new Scan(cards, duplicate, counterfeit);
  }
  static boolean isCounterfeit(CardItemCodec.ReadResult result, boolean hasIdentity) {
    return !Objects.requireNonNull(result, "read result is required").isValid() && hasIdentity;
  }
  static boolean exactItemMatchesSnapshot(CardItemCodec.ReadResult result, CardCopyId copyId,
      CopyOwner owner, byte[] actualBytes, byte[] snapshotBytes) {
    return result != null && result.isValid() && copyId != null && owner != null
        && actualBytes != null && snapshotBytes != null
        && result.data().copyId().equals(copyId)
        && result.data().owner().equals(owner)
        && Arrays.equals(actualBytes, snapshotBytes);
  }

  static boolean shouldCancelItemFrameInteraction(boolean targetIsItemFrame,
      boolean reservedMainHand, boolean reservedOffHand) {
    return targetIsItemFrame && (reservedMainHand || reservedOffHand);
  }

  private static List<CardCopyId> exactCopies(List<CardCopyId> copies) {
    Objects.requireNonNull(copies, "copy ids are required");
    if (copies.size() != 5) throw new IllegalArgumentException("exactly five copies are required");
    Set<CardCopyId> unique = new HashSet<>();
    for (CardCopyId copy : copies) {
      if (copy == null || !unique.add(copy)) throw new IllegalArgumentException("duplicate copy id");
    }
    return List.copyOf(copies);
  }

  private static byte[] serialize(ItemStack item) {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
      output.writeObject(item);
      output.flush();
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("unable to serialize card item", exception);
    }
  }

  private static ItemStack deserialize(byte[] bytes) {
    try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
      Object value = input.readObject();
      if (!(value instanceof ItemStack item)) throw new IllegalStateException("snapshot is not an ItemStack");
      return item;
    } catch (IOException | ClassNotFoundException exception) {
      throw new IllegalStateException("unable to deserialize card item", exception);
    }
  }

  @FunctionalInterface
  public interface PlayerLookup {
    Player find(UUID playerId);
  }

  private static final class PreparedOperation {
    private final CopyOwner owner;
    private final List<CardCopyId> copyIds;
    private final List<SerializedItemSnapshot> snapshots;
    private final String matchId;
    private final Set<CardCopyId> restored = new HashSet<>();
    private OperationStatus status;

    private PreparedOperation(CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, OperationStatus status) {
      this(owner, copyIds, snapshots, status, Set.of(), null);
    }

    private PreparedOperation(CopyOwner owner, List<CardCopyId> copyIds,
        List<SerializedItemSnapshot> snapshots, OperationStatus status, Set<CardCopyId> restored,
        String matchId) {
      this.owner = owner;
      this.copyIds = List.copyOf(copyIds);
      this.snapshots = List.copyOf(snapshots);
      this.restored.addAll(restored);
      this.status = status;
      this.matchId = matchId;
    }
  }
  private record Located(int slot, ItemStack item, CardItemData data) {}
  private record Scan(Map<CardCopyId, Located> cards, boolean duplicate, boolean counterfeit) {}
}
