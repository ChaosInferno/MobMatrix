package org.aincraft.mobmatrix;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.inventory.CardItemCodec;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class MobMatrixPluginTest {
  @Test
  void rejectsTheKnownDefaultServerSecret() throws Exception {
    Method requireSecret = MobMatrixPlugin.class.getDeclaredMethod("requireSecret", String.class);
    requireSecret.setAccessible(true);

    assertThrows(
        InvocationTargetException.class,
        () ->
            requireSecret.invoke(null, "change-this-to-a-stable-random-secret-at-least-16-bytes"));
  }

  @Test
  void preservesTheCapturedChallengeStartTimeAcrossRetries() {
    Instant captured = Instant.parse("2026-01-01T00:00:00Z");
    Instant retry = captured.plusSeconds(30);

    assertEquals(captured, MobMatrixPlugin.stableChallengeStartTime(captured, retry));
    assertEquals(retry, MobMatrixPlugin.stableChallengeStartTime(null, retry));
  }

  @Test
  void timesOutOnlyOfflineCurrentTurnsAfterTheirDeadline() {
    Instant deadline = Instant.parse("2026-01-01T00:00:10Z");

    assertTrue(
        MobMatrixPlugin.isDisconnectedTurnDue(
            true, false, false, false, PlayerSeat.ONE, PlayerSeat.ONE, deadline, deadline));
    assertTrue(
        MobMatrixPlugin.isDisconnectedTurnDue(
            true,
            false,
            false,
            false,
            PlayerSeat.TWO,
            PlayerSeat.TWO,
            deadline,
            deadline.plusMillis(1)));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            true, false, true, false, PlayerSeat.ONE, PlayerSeat.ONE, deadline, deadline));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            true, false, false, false, PlayerSeat.ONE, PlayerSeat.TWO, deadline, deadline));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            true, false, false, true, PlayerSeat.ONE, PlayerSeat.ONE, deadline, deadline));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            false, false, false, false, PlayerSeat.ONE, PlayerSeat.ONE, deadline, deadline));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            true, true, false, false, PlayerSeat.ONE, PlayerSeat.ONE, deadline, deadline));
    assertTrue(
        !MobMatrixPlugin.isDisconnectedTurnDue(
            true,
            false,
            false,
            false,
            PlayerSeat.ONE,
            PlayerSeat.ONE,
            deadline,
            deadline.minusMillis(1)));
  }

  @Test
  void ignoresMalformedPhysicalItemsWhileReadingOwnedCards() throws Exception {
    Assumptions.assumeTrue(
        paperAvailable(), "Paper API item registry requires a running Paper server");
    UUID playerId = UUID.randomUUID();
    MobMatrixPlugin plugin = pluginWithCatalog(singleCardCatalog());
    PlayerStub player = playerStub(playerId, new ItemStack(Material.PAPER));

    assertDoesNotThrow(() -> assertTrue(plugin.ownedCards(player.player()).isEmpty()));
  }

  @Test
  void ignoresValidCardsUnknownToTheCurrentCatalog() throws Exception {
    Assumptions.assumeTrue(
        paperAvailable(), "Paper API item registry requires a running Paper server");
    UUID playerId = UUID.randomUUID();
    CardDefinition first =
        new CardDefinition(new CardId(1), "No. 1", "First", 1, CardType.NORMAL, 1, 1, 1, 1, 100);
    CardDefinition second =
        new CardDefinition(new CardId(2), "No. 2", "Second", 1, CardType.NORMAL, 1, 1, 1, 1, 100);
    CardCatalog full = new CardCatalog(List.of(first, second), "full");
    MobMatrixPlugin plugin = pluginWithCatalog(new CardCatalog(List.of(first), "limited"));
    CardItemCodec codec = new CardItemCodec("test", "stable-server-secret");
    setField(plugin, "codec", codec);
    ItemStack item =
        codec.create(
            full.require(new CardId(2)),
            new org.aincraft.mobmatrix.domain.CardCopyId(UUID.randomUUID()),
            org.aincraft.mobmatrix.domain.CopyOwner.player(playerId));
    PlayerStub player = playerStub(playerId, item);

    assertDoesNotThrow(() -> assertTrue(plugin.ownedCards(player.player()).isEmpty()));
  }

  private static MobMatrixPlugin pluginWithCatalog(CardCatalog catalog) throws Exception {
    MobMatrixPlugin plugin = new MobMatrixPlugin();
    setField(plugin, "catalog", catalog);
    setField(plugin, "codec", new CardItemCodec("test", "stable-server-secret"));
    return plugin;
  }

  private static CardCatalog singleCardCatalog() {
    return new CardCatalog(
        List.of(
            new CardDefinition(
                new CardId(1), "No. 1", "First", 1, CardType.NORMAL, 1, 1, 1, 1, 100)),
        "single");
  }

  private static PlayerStub playerStub(UUID playerId, ItemStack item) {
    Inventory inventory =
        (Inventory)
            Proxy.newProxyInstance(
                MobMatrixPluginTest.class.getClassLoader(),
                new Class<?>[] {Inventory.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getStorageContents")) return new ItemStack[] {item};
                  if (method.getName().equals("getSize")) return 1;
                  if (method.getName().equals("getItem")) return item;
                  return defaultValue(method.getReturnType());
                });
    org.bukkit.entity.Player player =
        (org.bukkit.entity.Player)
            Proxy.newProxyInstance(
                MobMatrixPluginTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Player.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getUniqueId")) return playerId;
                  if (method.getName().equals("getInventory")) return inventory;
                  return defaultValue(method.getReturnType());
                });
    return new PlayerStub(player);
  }

  private static boolean paperAvailable() {
    try {
      new ItemStack(Material.PAPER);
      return true;
    } catch (Throwable unavailable) {
      return false;
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == char.class) return '\0';
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0F;
    if (type == double.class) return 0D;
    throw new AssertionError("unsupported primitive return type: " + type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private record PlayerStub(org.bukkit.entity.Player player) {}
}
