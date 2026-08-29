package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CardCatalogTest {
  @Test
  void currentSnapshotContainsAllPublicNumbers() {
    CardCatalog catalog = CatalogFixtures.currentSnapshot();
    assertEquals(475, catalog.all().size());
    Set<String> expected = new HashSet<>();
    for (int number = 1; number <= 460; number++) {
      expected.add("No. " + number);
    }
    for (int number = 1; number <= 15; number++) {
      expected.add("Ex. " + number);
    }
    Set<String> actual = new HashSet<>();
    for (CardDefinition card : catalog.all()) {
      actual.add(card.publicNumber());
    }
    assertEquals(expected, actual);
  }
 
  @Test
  void rejectsGapsInInternalIdsWithinSuppliedCollection() {
    CardDefinition first = new CardDefinition(
        new CardId(1), "No. 1", "First", 1, CardType.NORMAL, 1, 1, 1, 1, 100);
    CardDefinition third = new CardDefinition(
        new CardId(3), "No. 3", "Third", 1, CardType.NORMAL, 1, 1, 1, 1, 100);

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> new CardCatalog(List.of(first, third), "snapshot"));

    assertTrue(exception.getMessage().contains("missing card id: 2"));
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
