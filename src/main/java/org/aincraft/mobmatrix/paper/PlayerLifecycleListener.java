package org.aincraft.mobmatrix.paper;

import java.util.Objects;
import org.aincraft.mobmatrix.MobMatrixPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Connects Paper lifecycle events to persisted match timeout and custody recovery. */
public final class PlayerLifecycleListener implements Listener {
  private final MobMatrixPlugin plugin;

  public PlayerLifecycleListener(MobMatrixPlugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin is required");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onQuit(PlayerQuitEvent event) {
    plugin.handlePlayerQuit(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(PlayerJoinEvent event) {
    plugin.handlePlayerJoin(event.getPlayer());
  }
}
