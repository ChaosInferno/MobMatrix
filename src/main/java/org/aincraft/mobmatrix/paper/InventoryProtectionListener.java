package org.aincraft.mobmatrix.paper;

import java.util.Objects;
import org.aincraft.mobmatrix.inventory.CardInventoryService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Delegates all reserved-copy movement guards to the primary-thread custody adapter. */
public final class InventoryProtectionListener implements Listener {
  private final CardInventoryService custody;

  public InventoryProtectionListener(CardInventoryService custody) {
    this.custody = Objects.requireNonNull(custody, "custody is required");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryClick(InventoryClickEvent event) {
    custody.handleInventoryClick(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    custody.handleInventoryDrag(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    custody.handlePlayerSwapHandItems(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDrop(PlayerDropItemEvent event) {
    custody.handleDrop(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDeath(PlayerDeathEvent event) {
    custody.handlePlayerDeath(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onAutomatedMove(InventoryMoveItemEvent event) {
    custody.handleInventoryMoveItem(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onAutomatedPickup(InventoryPickupItemEvent event) {
    custody.handleInventoryPickupItem(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onEntityPickup(EntityPickupItemEvent event) {
    custody.handleEntityPickupItem(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onItemFrame(PlayerInteractEntityEvent event) {
    custody.handlePlayerInteractEntity(event);
  }
}
