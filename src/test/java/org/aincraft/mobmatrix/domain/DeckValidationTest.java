package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DeckValidationTest {
  @Test
  void deckAllowsDuplicateCopiesButEnforcesCurrentRarityLimits() {
    List<CardCopy> copies = List.of(copy("a", 1), copy("b", 1), copy("c", 2), copy("d", 3), copy("e", 4));
    assertTrue(DeckValidation.validate(copies, fixtureCatalog()).isValid());

    List<CardCopy> illegal = List.of(copy("a", 5), copy("b", 4), copy("c", 4), copy("d", 1), copy("e", 2));
    assertTrue(DeckValidation.validate(illegal, fixtureCatalog()).errors().contains(DeckError.TOO_MANY_FOUR_OR_FIVE_STAR));
  }

  @Test
  void validatesDeckSizeCopyIdentityAndCatalogMembership() {
    CardCatalog catalog = fixtureCatalog();
    assertTrue(DeckValidation.validate(List.of(), catalog).errors().contains(DeckError.WRONG_CARD_COUNT));
    assertTrue(DeckValidation.validate(List.of(copy("a", 1), copy("b", 2), copy("c", 3), copy("d", 4), copy("e", 5), copy("f", 1)), catalog).errors().contains(DeckError.WRONG_CARD_COUNT));
    CardCopy duplicate = new CardCopy(new CardCopyId(UUID.nameUUIDFromBytes("a".getBytes())), new CardId(1));
    assertTrue(DeckValidation.validate(List.of(duplicate, duplicate, copy("c", 1), copy("d", 2), copy("e", 3)), catalog).errors().contains(DeckError.DUPLICATE_COPY_ID));
    assertTrue(DeckValidation.validate(List.of(copy("a", 1), copy("b", 2), copy("c", 3), copy("d", 4), new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(99))), catalog).errors().contains(DeckError.UNKNOWN_CARD));
  }

  @Test
  void allowsAnyNumberOfLowRarityCardsAndEnforcesFiveStarRules() {
    CardCatalog catalog = fixtureCatalog();
    assertTrue(DeckValidation.validate(List.of(copy("a", 1), copy("b", 1), copy("c", 2), copy("d", 3), copy("e", 4)), catalog).isValid());
    assertFalse(DeckValidation.validate(List.of(copy("a", 5), copy("b", 5), copy("c", 1), copy("d", 2), copy("e", 3)), catalog).isValid());
    assertFalse(DeckValidation.validate(List.of(copy("a", 5), copy("b", 4), copy("c", 4), copy("d", 1), copy("e", 2)), catalog).isValid());
    assertTrue(DeckValidation.validate(List.of(copy("a", 5), copy("b", 4), copy("c", 1), copy("d", 2), copy("e", 3)), catalog).isValid());
  }

  private static CardCatalog fixtureCatalog() {
    return new CardCatalog(List.of(
        definition(1, 1), definition(2, 2), definition(3, 3), definition(4, 4), definition(5, 5)), "fixture");
  }

  private static CardDefinition definition(int id, int stars) {
    return new CardDefinition(new CardId(id), "No. " + id, "Card " + id, stars, CardType.NORMAL, 1, 2, 3, 4, 100);
  }

  private static CardCopy copy(String key, int cardId) {
    return new CardCopy(new CardCopyId(UUID.nameUUIDFromBytes(key.getBytes())), new CardId(cardId));
  }
}
