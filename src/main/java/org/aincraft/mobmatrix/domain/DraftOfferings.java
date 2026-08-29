package org.aincraft.mobmatrix.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** House-owned generated draft offers; no player physical copies are reused. */
public final class DraftOfferings {
  public record Offering(UUID offeringId, List<CardCopy> cards) {
    public Offering {
      Objects.requireNonNull(offeringId);
      cards = List.copyOf(Objects.requireNonNull(cards));
      if (cards.size() != 5) throw new IllegalArgumentException("offering must contain five cards");
    }
  }
  public record SeatOffers(PlayerSeat seat, List<Offering> offerings) {
    public SeatOffers {
      Objects.requireNonNull(seat); offerings = List.copyOf(Objects.requireNonNull(offerings));
      if (offerings.size() != 3) throw new IllegalArgumentException("draft must contain three offerings");
    }
  }
  public record Selection(PlayerSeat seat, List<Integer> offeringChoices, Deck deck) {
    public Selection {
      Objects.requireNonNull(seat);
      offeringChoices = List.copyOf(Objects.requireNonNull(offeringChoices));
      Objects.requireNonNull(deck);
      if (offeringChoices.size() != 3) throw new IllegalArgumentException("one selection is required per offering");
    }
  }

  private DraftOfferings() {}

  public static SeatOffers generate(PlayerSeat seat, CardCatalog catalog, RandomTranscript transcript) {
    Objects.requireNonNull(seat); Objects.requireNonNull(catalog); Objects.requireNonNull(transcript);
    List<CardDefinition> definitions = new ArrayList<>(catalog.all());
    List<Offering> offerings = new ArrayList<>();
    for (int offer = 0; offer < 3; offer++) {
      List<CardCopy> cards = new ArrayList<>();
      UUID offeringId = UUID.nameUUIDFromBytes((seat + "-offering-" + offer).getBytes(StandardCharsets.UTF_8));
      for (int card = 0; card < 5; card++) {
        int index = transcript.nextInt("draft-" + seat + "-" + offer + "-" + card, definitions.size());
        CardDefinition definition = definitions.get(index);
        UUID copyId = UUID.nameUUIDFromBytes((offeringId + "-" + card + "-" + definition.id()).getBytes(StandardCharsets.UTF_8));
        cards.add(new CardCopy(new CardCopyId(copyId), definition.id()));
      }
      offerings.add(new Offering(offeringId, cards));
    }
    return new SeatOffers(seat, offerings);
  }

  public static Selection select(SeatOffers offers, List<Integer> choices) {
    Objects.requireNonNull(offers); Objects.requireNonNull(choices);
    if (choices.size() != 3) throw new IllegalArgumentException("one selection is required per offering");
    List<CardCopy> selected = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      int choice = choices.get(i);
      if (choice < 0 || choice >= 5) throw new IllegalArgumentException("offering selection must be between 0 and 4");
      selected.add(offers.offerings().get(i).cards().get(choice));
    }
    for (Offering offering : offers.offerings()) for (CardCopy card : offering.cards()) {
      if (selected.size() == 5) break;
      if (!selected.contains(card)) selected.add(card);
    }
    return new Selection(offers.seat(), choices, new Deck(selected));
  }
  public static List<SeatOffers> generateBoth(CardCatalog catalog, RandomTranscript transcript) {
    return List.of(generate(PlayerSeat.ONE, catalog, transcript), generate(PlayerSeat.TWO, catalog, transcript));
  }
}
