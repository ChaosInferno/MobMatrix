package org.aincraft.mobmatrix.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.List;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class CardItemCodecTest {
  private final CardDefinition definition = new CardDefinition(
      new CardId(1), "No. 1", "Dodo", 1, CardType.NORMAL, 4, 2, 3, 4, 0);
  private final CardItemCodec codec = new CardItemCodec("mobmatrix", "test-server-secret");

  private static boolean paperAvailable() {
    try {
      new ItemStack(Material.PAPER);
      return true;
    } catch (Throwable unavailable) {
      return false;
    }
  }

  @Test
  void roundTripKeepsPhysicalIdentityAndReadableMetadata() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    CardCopyId copyId = new CardCopyId(UUID.randomUUID());
    ItemStack item = codec.create(definition, copyId, CopyOwner.player(UUID.randomUUID()));
    CardItemCodec.ReadResult result = codec.read(item);
    assertTrue(result.isValid());
    assertEquals(copyId, result.data().copyId());
    assertEquals(definition.id(), result.data().catalogId());
    assertTrue(result.data().displayName().contains("Dodo"));
    assertEquals(1, item.getAmount());
    assertEquals(Material.PAPER, item.getType());
  }

  @Test
  void createRejectsCatalogIdsOutsideAuthoritativeRange() {
    CardDefinition invalid = new CardDefinition(
        new CardId(476), "No. 476", "Invalid", 1, CardType.NORMAL, 4, 2, 3, 4, 0);
    assertThrows(IllegalArgumentException.class,
        () -> codec.create(invalid, new CardCopyId(UUID.randomUUID()), CopyOwner.house()));
  }

  @Test
  void malformedItemsReturnTypedInvalidResultsWithoutReadingDisplayText() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    ItemStack item = new ItemStack(Material.PAPER);
    CardItemCodec.ReadResult missing = codec.read(item);
    assertFalse(missing.isValid());
    assertEquals(CardItemCodec.InvalidReason.MISSING_FIELD, missing.reason());
    ItemStack other = new ItemStack(Material.STONE);
    assertEquals(CardItemCodec.InvalidReason.NOT_PAPER, codec.read(other).reason());
  }
  @Test
  void hasIdentityDetectsConfiguredKeysStoredWithUnexpectedPrimitiveType() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    ItemStack item = new ItemStack(Material.PAPER);
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer().set(codec.catalogIdKey(),
        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    assertTrue(codec.hasIdentity(item));
  }


  @Test
  void differentCopyIdsProduceNonStackableDistinctItems() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    ItemStack first = codec.create(definition, new CardCopyId(UUID.randomUUID()), CopyOwner.house());
    ItemStack second = codec.create(definition, new CardCopyId(UUID.randomUUID()), CopyOwner.house());
    assertNotEquals(first, second);
    assertEquals(1, first.getAmount());
    assertEquals(1, second.getAmount());
  }

  @Test
  void configuredSecretSurvivesCodecReconstruction() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    CardCopyId copyId = new CardCopyId(UUID.randomUUID());
    CardItemCodec first = new CardItemCodec("mobmatrix", "stable-server-secret");
    ItemStack minted = first.create(definition, copyId, CopyOwner.house());
    CardItemCodec restarted = new CardItemCodec("mobmatrix", "stable-server-secret");
    assertTrue(restarted.read(minted).isValid());
    assertEquals(copyId, restarted.read(minted).data().copyId());
  }
  @Test
  void malformedReservedIdentityCannotMove() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
    CardCopyId copyId = new CardCopyId(UUID.randomUUID());
    ItemStack item = codec.create(definition, copyId, CopyOwner.house());
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer().set(codec.integrityKey(),
        org.bukkit.persistence.PersistentDataType.STRING, "tampered");
    item.setItemMeta(meta);
    var journal = new org.aincraft.mobmatrix.application.InMemoryCardCustodyJournal();
    journal.prepare(UUID.randomUUID(), CopyOwner.player(UUID.randomUUID()), List.of(copyId),
        List.of(new org.aincraft.mobmatrix.application.SerializedItemSnapshot(copyId, new byte[] {1})));
    var service = new CardInventoryService(codec, id -> null, journal);
    assertFalse(codec.read(item).isValid());
    assertTrue(service.isReserved(item));
  }
}
