package org.aincraft.mobmatrix.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/** Pure validation of a five physical-copy deck against a catalog. */
public final class DeckValidation {
  private DeckValidation() {}

  public static Result validate(List<CardCopy> copies, CardCatalog catalog) {
    Objects.requireNonNull(copies, "copies are required");
    Objects.requireNonNull(catalog, "catalog is required");
    Set<DeckError> errors = EnumSet.noneOf(DeckError.class);
    if (copies.size() != 5) {
      errors.add(DeckError.WRONG_CARD_COUNT);
    }
    Set<CardCopyId> copyIds = new HashSet<>();
    List<CardDefinition> definitions = new ArrayList<>();
    for (CardCopy copy : copies) {
      if (copy == null) {
        errors.add(DeckError.UNKNOWN_CARD);
        continue;
      }
      if (!copyIds.add(copy.copyId())) {
        errors.add(DeckError.DUPLICATE_COPY_ID);
      }
      try {
        definitions.add(catalog.require(copy.cardId()));
      } catch (IllegalArgumentException exception) {
        errors.add(DeckError.UNKNOWN_CARD);
      }
    }
    long fiveStars = definitions.stream().filter(card -> card.stars() == 5).count();
    long fourOrFiveStars = definitions.stream().filter(card -> card.stars() >= 4).count();
    long fourStars = definitions.stream().filter(card -> card.stars() == 4).count();
    if (fiveStars > 1) {
      errors.add(DeckError.TOO_MANY_FIVE_STAR);
    }
    if (fourOrFiveStars > 2) {
      errors.add(DeckError.TOO_MANY_FOUR_OR_FIVE_STAR);
    }
    if (fiveStars > 0 && fourStars > 1) {
      errors.add(DeckError.TOO_MANY_FOUR_STAR_WITH_FIVE);
    }
    List<DeckError> immutableErrors = List.copyOf(errors);
    Deck deck = null;
    if (immutableErrors.isEmpty()) {
      deck = new Deck(copies);
    }
    return new Result(deck, immutableErrors);
  }

  public record Result(Deck deck, List<DeckError> errors) {
    public Result {
      errors = List.copyOf(Objects.requireNonNull(errors, "errors are required"));
      if (errors.isEmpty() != (deck != null)) {
        throw new IllegalArgumentException("valid result must contain a deck and invalid result must not");
      }
    }

    public boolean isValid() {
      return errors.isEmpty();
    }

    public Optional<Deck> deckOptional() {
      return Optional.ofNullable(deck);
    }
  }
}
