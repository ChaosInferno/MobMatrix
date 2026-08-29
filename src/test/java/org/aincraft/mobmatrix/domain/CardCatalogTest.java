package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CardCatalogTest {
  @Test
  void currentSnapshotContainsAllPublicNumbers() {
    CardCatalog catalog = CatalogFixtures.currentSnapshot();
    assertEquals(475, catalog.all().size());
    assertTrue(catalog.all().stream().anyMatch(card -> card.publicNumber().equals("No. 460")));
    assertTrue(catalog.all().stream().anyMatch(card -> card.publicNumber().equals("Ex. 15")));
  }

  @Test
  void definitionsExposePrintedValuesAndImmutableCatalog() {
    CardDefinition card = new CardDefinition(
        new CardId(7), "No. 7", "Test", 4, CardType.NORMAL, 1, 2, 3, 10, 800);
    assertEquals(10, card.value(Side.LEFT));
    CardCatalog catalog = new CardCatalog(List.of(card), "snapshot");
    assertEquals(card, catalog.require(new CardId(7)));
    assertThrows(UnsupportedOperationException.class, () -> catalog.all().clear());
  }

  @Test
  void valueObjectsRejectInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new CardId(0));
    assertThrows(IllegalArgumentException.class, () -> new CardDefinition(
        new CardId(1), "", "Name", 1, CardType.NORMAL, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CardDefinition(
        new CardId(1), "No. 1", "Name", 6, CardType.NORMAL, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CardDefinition(
        new CardId(1), "No. 1", "Name", 1, CardType.NORMAL, 11, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CardCatalog(
        List.of(new CardDefinition(new CardId(1), "No. 1", "A", 1, CardType.NORMAL, 1, 1, 1, 1, 1),
            new CardDefinition(new CardId(1), "No. 2", "B", 1, CardType.NORMAL, 1, 1, 1, 1, 1)), "snapshot"));
  }

  @Test
  void copiesAreDistinctByUuidEvenForSameCard() {
    CardId id = new CardId(1);
    CardCopy first = new CardCopy(new CardCopyId(UUID.randomUUID()), id);
    CardCopy second = new CardCopy(new CardCopyId(UUID.randomUUID()), id);
    assertTrue(!first.copyId().equals(second.copyId()));
    assertEquals(first.cardId(), second.cardId());
  }
}
