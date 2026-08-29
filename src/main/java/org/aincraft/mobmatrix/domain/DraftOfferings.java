package org.aincraft.mobmatrix.domain;

import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** House-owned generated draft offers; no player physical copies are reused. */
public final class DraftOfferings {
  /** The deterministic policy resolves three selections to a five-card temporary hand. */
  public static final String FIVE_CARD_POLICY = "SELECT_ONE_FROM_EACH_THEN_FIRST_TWO_REMAINING";

  public record Offering(UUID offeringId, List<CardCopy> cards) {
    public Offering {
      Objects.requireNonNull(offeringId, "offering id is required");
      cards = List.copyOf(Objects.requireNonNull(cards, "cards are required"));
      if (cards.size() != 5) throw new IllegalArgumentException("offering must contain five cards");
      Set<CardCopyId> ids = new HashSet<>();
      for (CardCopy card : cards) {
        if (card == null || !ids.add(card.copyId())) {
          throw new IllegalArgumentException("offering must contain unique physical copies");
        }
      }
    }
  }

  public record SeatOffers(PlayerSeat seat, List<Offering> offerings) {
    public SeatOffers {
      Objects.requireNonNull(seat, "seat is required");
      offerings = List.copyOf(Objects.requireNonNull(offerings, "offerings are required"));
      if (offerings.size() != 3) throw new IllegalArgumentException("draft must contain three offerings");
    }

    public boolean contains(CardCopyId copyId) {
      Objects.requireNonNull(copyId, "copy id is required");
      return offerings.stream().flatMap(offering -> offering.cards().stream())
          .anyMatch(copy -> copy.copyId().equals(copyId));
    }
  }

  /** Immutable selection, including selected provenance and deterministic five-card result. */
  public static final class Selection {
    private final PlayerSeat seat;
    private final List<Integer> offeringChoices;
    private final List<CardCopy> selectedCards;
    private final Deck deck;
    private final Map<CardCopyId, UUID> provenance;
    private final String policy;

    private Selection(PlayerSeat seat, List<Integer> offeringChoices, List<CardCopy> selectedCards,
        Deck deck, Map<CardCopyId, UUID> provenance) {
      this.seat = Objects.requireNonNull(seat, "seat is required");
      this.offeringChoices = List.copyOf(Objects.requireNonNull(offeringChoices, "offering choices are required"));
      this.selectedCards = List.copyOf(Objects.requireNonNull(selectedCards, "selected cards are required"));
      this.deck = Objects.requireNonNull(deck, "deck is required");
      this.provenance = Map.copyOf(Objects.requireNonNull(provenance, "provenance is required"));
      this.policy = FIVE_CARD_POLICY;
      if (this.offeringChoices.size() != 3 || this.selectedCards.size() != 3) {
        throw new IllegalArgumentException("one selection is required per offering");
      }
      if (this.provenance.size() != 5 && !this.provenance.isEmpty()) {
        throw new IllegalArgumentException("every draft card must have offering provenance");
      }
    }

    /** Source-compatible constructor; use select(SeatOffers, ...) to establish provenance. */
    public Selection(PlayerSeat seat, List<Integer> offeringChoices, Deck deck) {
      this(seat, offeringChoices, deck.copies().subList(0, 3), deck, Map.of());
    }

    public PlayerSeat seat() { return seat; }
    public List<Integer> offeringChoices() { return offeringChoices; }
    public List<CardCopy> selectedCards() { return selectedCards; }
    public Deck deck() { return deck; }
    public Map<CardCopyId, UUID> provenance() { return provenance; }
    public String policy() { return policy; }
  }

  private DraftOfferings() {}

  public static SeatOffers generate(PlayerSeat seat, CardCatalog catalog, RandomTranscript transcript) {
    Objects.requireNonNull(seat, "seat is required");
    Objects.requireNonNull(catalog, "catalog is required");
    Objects.requireNonNull(transcript, "transcript is required");
    List<CardDefinition> definitions = new ArrayList<>(catalog.all());
    if (definitions.isEmpty()) throw new IllegalArgumentException("catalog must contain cards");
    List<Offering> offerings = new ArrayList<>();
    for (int offer = 0; offer < 3; offer++) {
      List<CardCopy> cards = new ArrayList<>();
      UUID offeringId = UUID.nameUUIDFromBytes((seat + "-offering-" + offer)
          .getBytes(StandardCharsets.UTF_8));
      for (int card = 0; card < 5; card++) {
        int index = transcript.nextInt("draft-" + seat + "-" + offer + "-" + card, definitions.size());
        CardDefinition definition = definitions.get(index);
        UUID copyId = UUID.nameUUIDFromBytes((offeringId + "-" + card + "-" + definition.id())
            .getBytes(StandardCharsets.UTF_8));
        cards.add(new CardCopy(new CardCopyId(copyId), definition.id()));
      }
      offerings.add(new Offering(offeringId, cards));
    }
    return new SeatOffers(seat, offerings);
  }

  public static Selection select(SeatOffers offers, List<Integer> choices) {
    Objects.requireNonNull(offers, "offers are required");
    Objects.requireNonNull(choices, "choices are required");
    if (choices.size() != offers.offerings().size()) {
      throw new IllegalArgumentException("one selection is required per offering");
    }
    List<CardCopy> selected = new ArrayList<>();
    Map<CardCopyId, UUID> provenance = new HashMap<>();
    for (int i = 0; i < offers.offerings().size(); i++) {
      int choice = choices.get(i);
      Offering offering = offers.offerings().get(i);
      if (choice < 0 || choice >= offering.cards().size()) {
        throw new IllegalArgumentException("offering selection must be between 0 and 4");
      }
      CardCopy card = offering.cards().get(choice);
      selected.add(card);
      provenance.put(card.copyId(), offering.offeringId());
    }
    List<CardCopy> result = new ArrayList<>(selected);
    for (Offering offering : offers.offerings()) {
      for (CardCopy card : offering.cards()) {
        if (result.size() == 5) break;
        if (provenance.putIfAbsent(card.copyId(), offering.offeringId()) == null) result.add(card);
      }
      if (result.size() == 5) break;
    }
    return new Selection(offers.seat(), choices, selected, new Deck(result), provenance);
  }

  /** Card-based setup API validates each selected physical copy belongs to its corresponding offer. */
  public static Selection select(SeatOffers offers, Collection<CardCopy> choices) {
    Objects.requireNonNull(offers, "offers are required");
    Objects.requireNonNull(choices, "choices are required");
    List<CardCopy> selectedChoices = List.copyOf(choices);
    if (selectedChoices.size() != offers.offerings().size()) {
      throw new IllegalArgumentException("one selection is required per offering");
    }
    List<Integer> indexes = new ArrayList<>();
    Set<CardCopyId> selected = new HashSet<>();
    for (int i = 0; i < selectedChoices.size(); i++) {
      CardCopy choice = Objects.requireNonNull(selectedChoices.get(i), "selected card is required");
      if (!selected.add(choice.copyId())) throw new IllegalArgumentException("duplicate draft selection");
      int index = offers.offerings().get(i).cards().indexOf(choice);
      if (index < 0) throw new IllegalArgumentException("selected card is not in its offering");
      indexes.add(index);
    }
    return select(offers, indexes);
  }

  public static List<SeatOffers> generateBoth(CardCatalog catalog, RandomTranscript transcript) {
    return List.of(generate(PlayerSeat.ONE, catalog, transcript), generate(PlayerSeat.TWO, catalog, transcript));
  }
}
