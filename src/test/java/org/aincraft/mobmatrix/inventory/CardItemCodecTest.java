package org.aincraft.mobmatrix.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class CardItemCodecTest {
  private final CardDefinition definition = new CardDefinition(
      new CardId(1), "No. 1", "Dodo", 1, CardType.NORMAL, 4, 2, 3, 4, 0);
  private final CardItemCodec codec = new CardItemCodec("mobmatrix", "test-server-secret");

  @org.junit.jupiter.api.BeforeEach
  void requirePaperRegistry() {
    org.junit.jupiter.api.Assumptions.assumeTrue(paperAvailable(),
        "Paper API item registry requires a running Paper server");
  }

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
  void malformedItemsReturnTypedInvalidResultsWithoutReadingDisplayText() {
    ItemStack item = new ItemStack(Material.PAPER);
    CardItemCodec.ReadResult missing = codec.read(item);
    assertFalse(missing.isValid());
    assertEquals(CardItemCodec.InvalidReason.MISSING_FIELD, missing.reason());

    ItemStack other = new ItemStack(Material.STONE);
    assertEquals(CardItemCodec.InvalidReason.NOT_PAPER, codec.read(other).reason());
  }

  @Test
  void differentCopyIdsProduceNonStackableDistinctItems() {
    ItemStack first = codec.create(definition, new CardCopyId(UUID.randomUUID()), CopyOwner.house());
    ItemStack second = codec.create(definition, new CardCopyId(UUID.randomUUID()), CopyOwner.house());

    assertNotEquals(first, second);
    assertEquals(1, first.getAmount());
    assertEquals(1, second.getAmount());
  }
}
