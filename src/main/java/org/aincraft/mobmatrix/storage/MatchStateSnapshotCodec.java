package org.aincraft.mobmatrix.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.Board;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.DraftOfferings;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchResult;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.RandomTranscript;
import org.aincraft.mobmatrix.domain.Rule;
import org.aincraft.mobmatrix.domain.Side;
import org.aincraft.mobmatrix.domain.Square;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;

/** Complete, versioned binary codec for executable MatchState snapshots. */
public final class MatchStateSnapshotCodec {
  private static final int MAGIC = 0x4D4D5332;
  public static final int VERSION = 2;

  private MatchStateSnapshotCodec() {}
  public static String catalogSnapshotId(byte[] bytes) {
    Objects.requireNonNull(bytes, "snapshot bytes are required");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC || in.readInt() != VERSION) {
        throw new IllegalArgumentException("unsupported match snapshot version");
      }
      in.readUTF();
      in.readLong();
      return in.readUTF();
    } catch (IOException exception) {
      throw new IllegalArgumentException("invalid match state snapshot", exception);
    }
  }

  public static byte[] encode(MatchState state) {
    Objects.requireNonNull(state, "state is required");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(state.matchId().value());
        out.writeLong(state.revision());
        out.writeUTF(state.catalog().snapshotId());
        List<Rule> rules = state.rules().rules().stream().sorted(Comparator.comparing(Enum::name)).toList();
        out.writeInt(rules.size());
        for (Rule rule : rules) out.writeUTF(rule.name());
        out.writeUTF(state.currentSeat().name());
        writeInstant(out, state.turnStartedAt());
        writeInstant(out, state.graceDeadline());
        writeInstant(out, state.turnDeadline());
        for (PlayerSeat seat : PlayerSeat.values()) out.writeLong(state.bankMillis().get(seat));
        MatchResult result = state.result();
        out.writeBoolean(result != null);
        if (result != null) {
          out.writeInt(result.score(PlayerSeat.ONE));
          out.writeInt(result.score(PlayerSeat.TWO));
          out.writeUTF(result.outcome().name());
        }
        out.writeLong(state.seed());
        writeUuids(out, state.commandIds());
        RandomTranscript transcript = state.transcript();
        out.writeLong(transcript.seed());
        out.writeBoolean(transcript.replayMode());
        out.writeInt(transcript.cursor());
        List<RandomTranscript.Decision> decisions = transcript.decisions();
        out.writeInt(decisions.size());
        for (RandomTranscript.Decision decision : decisions) {
          out.writeUTF(decision.decision());
          out.writeInt(decision.bound());
          out.writeInt(decision.value());
        }
        for (PlayerSeat seat : PlayerSeat.values()) {
          List<HandCard> hand = state.hand(seat);
          out.writeInt(hand.size());
          for (HandCard card : hand) writeCopy(out, card.copy());
        }
        for (Board.Cell cell : state.board().cells()) {
          out.writeBoolean(cell != null);
          if (cell != null) {
            writeCopy(out, cell.copy());
            out.writeInt(cell.cardId().value());
            out.writeUTF(cell.owner().name());
            for (Side side : Side.values()) {
              Integer value = cell.effectiveValues().get(side);
              out.writeBoolean(value != null);
              if (value != null) out.writeInt(value);
            }
          }
        }
        for (PlayerSeat seat : PlayerSeat.values()) writeCopyIds(out, state.revealedCards(seat));
        out.writeInt(state.originalOwners().size());
        for (var entry : state.originalOwners().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(value -> value.value().toString()))).toList()) {
          writeUuid(out, entry.getKey().value());
          out.writeUTF(entry.getValue().name());
        }
        out.writeInt(state.consecutiveDraws());
        out.writeInt(state.draftOfferings().size());
        for (var entry : state.draftOfferings().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
          out.writeUTF(entry.getKey().name());
          out.writeInt(entry.getValue().offerings().size());
          for (DraftOfferings.Offering offering : entry.getValue().offerings()) {
            writeUuid(out, offering.offeringId());
            out.writeInt(offering.cards().size());
            for (CardCopy copy : offering.cards()) writeCopy(out, copy);
          }
        }
        out.writeInt(state.draftSelections().size());
        for (var entry : state.draftSelections().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
          out.writeUTF(entry.getKey().name());
          DraftOfferings.Selection selection = entry.getValue();
          out.writeInt(selection.offeringChoices().size());
          for (int choice : selection.offeringChoices()) out.writeInt(choice);
          out.writeInt(selection.selectedCards().size());
          for (CardCopy copy : selection.selectedCards()) writeCopy(out, copy);
          out.writeInt(selection.deck().copies().size());
          for (CardCopy copy : selection.deck().copies()) writeCopy(out, copy);
          out.writeInt(selection.provenance().size());
          for (var provenance : selection.provenance().entrySet().stream()
              .sorted(Map.Entry.comparingByKey(Comparator.comparing(value -> value.value().toString()))).toList()) {
            writeUuid(out, provenance.getKey().value());
            writeUuid(out, provenance.getValue());
          }
        }
        writeCopyIdSet(out, state.houseOwnedCopies());
        out.writeInt(state.chaosChoices().size());
        for (var entry : state.chaosChoices().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
          out.writeUTF(entry.getKey().name());
          writeUuid(out, entry.getValue().value());
        }
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("unable to serialize match state", exception);
    }
  }

  public static MatchState decode(byte[] bytes, CardCatalog catalog) {
    Objects.requireNonNull(bytes, "snapshot bytes are required");
    Objects.requireNonNull(catalog, "catalog is required");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC) throw new IllegalArgumentException("unknown match snapshot format");
      if (in.readInt() != VERSION) throw new IllegalArgumentException("unsupported match snapshot version");
      var matchId = new org.aincraft.mobmatrix.domain.MatchId(in.readUTF());
      long revision = in.readLong();
      String catalogSnapshotId = in.readUTF();
      if (!catalog.snapshotId().equals(catalogSnapshotId)) {
        throw new IllegalArgumentException("catalog snapshot does not match match state");
      }
      Set<Rule> rules = new HashSet<>();
      for (int i = in.readInt(); i > 0; i--) rules.add(Rule.valueOf(in.readUTF()));
      PlayerSeat currentSeat = PlayerSeat.valueOf(in.readUTF());
      Instant turnStartedAt = readInstant(in);
      Instant graceDeadline = readInstant(in);
      Instant turnDeadline = readInstant(in);
      Map<PlayerSeat, Long> banks = new EnumMap<>(PlayerSeat.class);
      for (PlayerSeat seat : PlayerSeat.values()) banks.put(seat, in.readLong());
      MatchResult result = null;
      if (in.readBoolean()) {
        Map<PlayerSeat, Integer> scores = new EnumMap<>(PlayerSeat.class);
        scores.put(PlayerSeat.ONE, in.readInt());
        scores.put(PlayerSeat.TWO, in.readInt());
        result = new MatchResult(scores, MatchResult.Outcome.valueOf(in.readUTF()));
      }
      long seed = in.readLong();
      Set<UUID> commandIds = readUuids(in);
      long transcriptSeed = in.readLong();
      boolean replay = in.readBoolean();
      int cursor = in.readInt();
      List<RandomTranscript.Decision> decisions = new ArrayList<>();
      for (int i = in.readInt(); i > 0; i--) {
        decisions.add(new RandomTranscript.Decision(in.readUTF(), in.readInt(), in.readInt()));
      }
      RandomTranscript transcript = RandomTranscript.restore(transcriptSeed, decisions, replay, cursor);
      Map<CardCopyId, CardCopy> activeCopies = new HashMap<>();
      Map<PlayerSeat, List<HandCard>> hands = new EnumMap<>(PlayerSeat.class);
      for (PlayerSeat seat : PlayerSeat.values()) {
        List<HandCard> hand = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
          CardCopy copy = readCopy(in);
          requireUniqueActiveCopy(activeCopies, copy);
          hand.add(new HandCard(copy, catalog.require(copy.cardId())));
        }
        hands.put(seat, List.copyOf(hand));
      }
      Board board = Board.empty();
      for (int index = 0; index < 9; index++) {
        if (!in.readBoolean()) continue;
        CardCopy copy = readCopy(in);
        requireUniqueActiveCopy(activeCopies, copy);
        catalog.require(copy.cardId());
        CardId cardId = new CardId(in.readInt());
        if (!copy.cardId().equals(cardId)) throw new IllegalArgumentException("board card identity mismatch");
        PlayerSeat owner = PlayerSeat.valueOf(in.readUTF());
        Map<Side, Integer> effective = new EnumMap<>(Side.class);
        for (Side side : Side.values()) if (in.readBoolean()) effective.put(side, in.readInt());
        board = board.place(Square.of(index), copy, owner, effective);
      }
      Map<PlayerSeat, Set<CardCopyId>> revealed = new EnumMap<>(PlayerSeat.class);
      for (PlayerSeat seat : PlayerSeat.values()) revealed.put(seat, readCopyIds(in));
      Map<CardCopyId, PlayerSeat> originalOwners = new HashMap<>();
      for (int i = in.readInt(); i > 0; i--) {
        CardCopyId copyId = new CardCopyId(readUuid(in));
        PlayerSeat owner = PlayerSeat.valueOf(in.readUTF());
        if (originalOwners.putIfAbsent(copyId, owner) != null) {
          throw new IllegalArgumentException("duplicate original-owner copy UUID");
        }
      }
      boolean ownershipMetadataRequired = rules.contains(Rule.RANDOM) || rules.contains(Rule.SWAP);
      boolean inferredOwnership = originalOwners.isEmpty() && !ownershipMetadataRequired;
      if (inferredOwnership) {
        for (PlayerSeat seat : PlayerSeat.values()) {
          for (HandCard card : hands.get(seat)) {
            if (card.copy().owner().isPlayer()) originalOwners.put(card.copyId(), seat);
          }
        }
        for (Board.Cell cell : board.cells()) {
          if (cell != null && cell.copy().owner().isPlayer()) {
            originalOwners.put(cell.copy().copyId(), cell.owner());
          }
        }
      }
      Set<CardCopyId> expectedOriginalOwners = new HashSet<>();
      Map<UUID, PlayerSeat> originalSeatByOwner = new HashMap<>();
      for (var entry : activeCopies.entrySet()) {
        CardCopy copy = entry.getValue();
        if (!copy.owner().isPlayer()) continue;
        expectedOriginalOwners.add(copy.copyId());
        PlayerSeat originalSeat = originalOwners.get(copy.copyId());
        PlayerSeat priorSeat = originalSeatByOwner.putIfAbsent(copy.owner().playerId(), originalSeat);
        if (originalSeat == null || (!inferredOwnership && priorSeat != null && priorSeat != originalSeat)) {
          throw new IllegalArgumentException("original-owner seat mismatch");
        }
      }
      if (!originalOwners.keySet().equals(expectedOriginalOwners)) {
        throw new IllegalArgumentException("original-owner metadata does not cover active copies");
      }
      int consecutiveDraws = in.readInt();
      Map<PlayerSeat, DraftOfferings.SeatOffers> offerings = new EnumMap<>(PlayerSeat.class);
      Set<UUID> offeringIds = new HashSet<>();
      Map<CardCopyId, CardCopy> offeringCopies = new HashMap<>();
      for (int i = in.readInt(); i > 0; i--) {
        PlayerSeat seat = PlayerSeat.valueOf(in.readUTF());
        if (offerings.containsKey(seat)) throw new IllegalArgumentException("duplicate draft offering seat");
        List<DraftOfferings.Offering> seatOfferings = new ArrayList<>();
        for (int j = in.readInt(); j > 0; j--) {
          UUID offeringId = readUuid(in);
          if (!offeringIds.add(offeringId)) {
            throw new IllegalArgumentException("duplicate draft offering UUID");
          }
          List<CardCopy> cards = new ArrayList<>();
          for (int k = in.readInt(); k > 0; k--) {
            CardCopy copy = readCopy(in);
            if (!copy.owner().equals(CopyOwner.house())) {
              throw new IllegalArgumentException("draft offering copy must be house-owned");
            }
            catalog.require(copy.cardId());
            CardCopy active = activeCopies.get(copy.copyId());
            if (active != null && !active.equals(copy)) {
              throw new IllegalArgumentException("draft offering copy conflicts with active copy");
            }
            if (offeringCopies.putIfAbsent(copy.copyId(), copy) != null) {
              throw new IllegalArgumentException("duplicate draft offering copy UUID");
            }
            cards.add(copy);
          }
          seatOfferings.add(new DraftOfferings.Offering(offeringId, cards));
        }
        if (offerings.putIfAbsent(seat, new DraftOfferings.SeatOffers(seat, seatOfferings)) != null) {
          throw new IllegalArgumentException("duplicate draft offering seat");
        }
      }
      Map<PlayerSeat, DraftOfferings.Selection> selections = new EnumMap<>(PlayerSeat.class);
      for (int i = in.readInt(); i > 0; i--) {
        PlayerSeat seat = PlayerSeat.valueOf(in.readUTF());
        if (selections.containsKey(seat)) throw new IllegalArgumentException("duplicate draft selection seat");
        DraftOfferings.SeatOffers seatOffers = offerings.get(seat);
        if (seatOffers == null) throw new IllegalArgumentException("selection has no offerings");
        List<Integer> choices = new ArrayList<>();
        for (int j = in.readInt(); j > 0; j--) choices.add(in.readInt());
        List<CardCopy> selected = new ArrayList<>();
        for (int j = in.readInt(); j > 0; j--) {
          CardCopy copy = readCopy(in);
          selected.add(requireOfferingReference(copy, offeringCopies));
        }
        List<CardCopy> deckCards = new ArrayList<>();
        for (int j = in.readInt(); j > 0; j--) {
          CardCopy copy = readCopy(in);
          deckCards.add(requireOfferingReference(copy, offeringCopies));
        }
        Map<CardCopyId, UUID> provenance = new HashMap<>();
        for (int j = in.readInt(); j > 0; j--) {
          CardCopyId copyId = new CardCopyId(readUuid(in));
          UUID offeringId = readUuid(in);
          if (provenance.putIfAbsent(copyId, offeringId) != null) {
            throw new IllegalArgumentException("duplicate draft provenance copy UUID");
          }
        }
        validateSelection(seatOffers, choices, selected, deckCards, provenance, offeringCopies);
        selections.put(seat, DraftOfferings.Selection.restore(seat, choices, selected,
            new Deck(deckCards), provenance));
      }
      boolean draft = rules.contains(Rule.DRAFT);
      if (draft && (offerings.size() != PlayerSeat.values().length
          || selections.size() != PlayerSeat.values().length)) {
        throw new IllegalArgumentException("draft snapshot must contain both seat metadata records");
      }
      if (!draft && (!offerings.isEmpty() || !selections.isEmpty())) {
        throw new IllegalArgumentException("non-draft snapshot contains draft metadata");
      }
      Set<CardCopyId> persistedHouseCopies = readCopyIdSet(in);
      Map<PlayerSeat, CardCopyId> chaos = new EnumMap<>(PlayerSeat.class);
      for (int i = in.readInt(); i > 0; i--) {
        PlayerSeat seat = PlayerSeat.valueOf(in.readUTF());
        CardCopyId choice = new CardCopyId(readUuid(in));
        if (chaos.putIfAbsent(seat, choice) != null) {
          throw new IllegalArgumentException("duplicate chaos choice seat");
        }
      }
      boolean chaosRule = rules.contains(Rule.CHAOS);
      if (!chaosRule && !chaos.isEmpty()) {
        throw new IllegalArgumentException("non-chaos snapshot contains chaos choices");
      }
      for (var entry : chaos.entrySet()) {
        if (hands.get(entry.getKey()).stream()
            .noneMatch(card -> card.copyId().equals(entry.getValue()))) {
          throw new IllegalArgumentException("chaos choice is not in that seat's hand");
        }
      }
      MatchState state = MatchState.rehydrate(matchId, catalog, new MatchRules(rules), board, hands,
          currentSeat, revision, result, banks, turnStartedAt, graceDeadline, turnDeadline,
          commandIds, seed, transcript, revealed, originalOwners, consecutiveDraws, offerings,
          selections, chaos);
      if (!state.houseOwnedCopies().equals(persistedHouseCopies)) {
        throw new IllegalArgumentException("house-owned snapshot metadata mismatch");
      }
      if (in.available() != 0) throw new IllegalArgumentException("trailing match snapshot bytes");
      return state;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof IllegalArgumentException illegal) throw illegal;
      throw new IllegalArgumentException("invalid match state snapshot", exception);
    }
  }

  private static void writeCopy(DataOutputStream out, CardCopy copy) throws IOException {
    writeUuid(out, copy.copyId().value());
    out.writeInt(copy.cardId().value());
    out.writeUTF(copy.owner().kind().name());
    out.writeBoolean(copy.owner().playerId() != null);
    if (copy.owner().playerId() != null) writeUuid(out, copy.owner().playerId());
  }

  private static CardCopy readCopy(DataInputStream in) throws IOException {
    CardCopyId copyId = new CardCopyId(readUuid(in));
    CardId cardId = new CardId(in.readInt());
    String kind = in.readUTF();
    boolean hasPlayerId = in.readBoolean();
    CopyOwner owner;
    if ("HOUSE".equals(kind) && !hasPlayerId) owner = CopyOwner.house();
    else if ("PLAYER".equals(kind) && hasPlayerId) owner = CopyOwner.player(readUuid(in));
    else throw new IllegalArgumentException("invalid copy owner");
    return new CardCopy(copyId, cardId, owner);
  }
  private static void requireUniqueActiveCopy(Map<CardCopyId, CardCopy> activeCopies,
      CardCopy copy) {
    if (activeCopies.putIfAbsent(copy.copyId(), copy) != null) {
      throw new IllegalArgumentException("duplicate active card copy UUID");
    }
  }
  private static CardCopy requireOfferingReference(CardCopy copy,
      Map<CardCopyId, CardCopy> offeringCopies) {
    if (!copy.owner().equals(CopyOwner.house())) {
      throw new IllegalArgumentException("draft reference must be house-owned");
    }
    CardCopy authoritative = offeringCopies.get(copy.copyId());
    if (!copy.equals(authoritative)) {
      throw new IllegalArgumentException("draft reference is outside offerings");
    }
    return authoritative;
  }

  private static void validateSelection(DraftOfferings.SeatOffers offers,
      List<Integer> choices, List<CardCopy> selected, List<CardCopy> deck,
      Map<CardCopyId, UUID> provenance, Map<CardCopyId, CardCopy> offeringCopies) {
    if (choices.size() != offers.offerings().size() || selected.size() != choices.size()) {
      throw new IllegalArgumentException("draft selection shape mismatch");
    }
    Set<CardCopyId> selectedIds = new HashSet<>();
    for (int index = 0; index < choices.size(); index++) {
      int choice = choices.get(index);
      if (choice < 0 || choice >= offers.offerings().get(index).cards().size()) {
        throw new IllegalArgumentException("draft choice is outside offering");
      }
      CardCopy expected = offers.offerings().get(index).cards().get(choice);
      if (!expected.equals(selected.get(index)) || !selectedIds.add(expected.copyId())) {
        throw new IllegalArgumentException("draft selection does not match offering");
      }
    }
    Set<CardCopyId> deckIds = new HashSet<>();
    for (CardCopy copy : deck) {
      requireOfferingReference(copy, offeringCopies);
      if (!deckIds.add(copy.copyId())) {
        throw new IllegalArgumentException("draft deck contains duplicate copy UUID");
      }
    }
    if (provenance.size() != deck.size() || !provenance.keySet().equals(deckIds)) {
      throw new IllegalArgumentException("draft provenance does not match deck");
    }
    for (var entry : provenance.entrySet()) {
      CardCopy copy = offeringCopies.get(entry.getKey());
      if (copy == null || !copy.owner().equals(CopyOwner.house())) {
        throw new IllegalArgumentException("draft provenance copy is outside offerings");
      }
      DraftOfferings.Offering offering = offers.offerings().stream()
          .filter(candidate -> candidate.offeringId().equals(entry.getValue()))
          .findFirst().orElse(null);
      if (offering == null || !offering.cards().contains(copy)) {
        throw new IllegalArgumentException("draft provenance offering mismatch");
      }
    }
    for (int index = 0; index < selected.size(); index++) {
      UUID offeringId = provenance.get(selected.get(index).copyId());
      if (!offers.offerings().get(index).offeringId().equals(offeringId)) {
        throw new IllegalArgumentException("draft selected provenance mismatch");
      }
    }
  }


  private static void writeCopyIds(DataOutputStream out, Set<CardCopyId> copies) throws IOException {
    writeCopyIdSet(out, copies);
  }

  private static void writeCopyIdSet(DataOutputStream out, Set<CardCopyId> copies) throws IOException {
    out.writeInt(copies.size());
    for (CardCopyId copy : copies.stream().sorted(Comparator.comparing(value -> value.value().toString())).toList()) {
      writeUuid(out, copy.value());
    }
  }

  private static Set<CardCopyId> readCopyIds(DataInputStream in) throws IOException {
    return readCopyIdSet(in);
  }

  private static Set<CardCopyId> readCopyIdSet(DataInputStream in) throws IOException {
    Set<CardCopyId> result = new HashSet<>();
    for (int i = in.readInt(); i > 0; i--) result.add(new CardCopyId(readUuid(in)));
    return Set.copyOf(result);
  }

  private static void writeUuids(DataOutputStream out, Set<UUID> values) throws IOException {
    out.writeInt(values.size());
    for (UUID value : values.stream().sorted().toList()) writeUuid(out, value);
  }

  private static Set<UUID> readUuids(DataInputStream in) throws IOException {
    Set<UUID> result = new HashSet<>();
    for (int i = in.readInt(); i > 0; i--) result.add(readUuid(in));
    return Set.copyOf(result);
  }

  private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  private static void writeInstant(DataOutputStream out, Instant value) throws IOException {
    out.writeLong(value.getEpochSecond());
    out.writeInt(value.getNano());
  }

  private static Instant readInstant(DataInputStream in) throws IOException {
    return Instant.ofEpochSecond(in.readLong(), in.readInt());
  }
}
