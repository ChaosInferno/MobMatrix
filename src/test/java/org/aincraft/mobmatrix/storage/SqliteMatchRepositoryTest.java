package org.aincraft.mobmatrix.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Square;
import org.junit.jupiter.api.Test;

final class SqliteMatchRepositoryTest {
  @Test
  void persistsAndReloadsStandardHouseMatchBeforePlacement() throws Exception {
    CardCatalog catalog = catalog();
    CopyOwner player = CopyOwner.player(UUID.randomUUID());
    MatchEngine.StartMatch start =
        new MatchEngine.StartMatch(
            new MatchId("standard-house-snapshot"),
            new Deck(deckCopies(player, 1)),
            new Deck(deckCopies(CopyOwner.house(), 11)),
            catalog,
            new MatchRules(Set.of()),
            41L,
            Instant.parse("2026-01-01T00:00:00Z"));
    MatchState initial = MatchEngine.start(start).state();
    var path = Files.createTempFile("mobmatrix-standard-house-match", ".sqlite");

    try (Database database = new Database(path)) {
      SqliteMatchRepository repository = new SqliteMatchRepository(database, "0123456789abcdef");
      assertTrue(repository.save(0L, initial).accepted());

      MatchState reloaded = repository.findState(initial.matchId(), catalog).orElseThrow();
      assertEquals(initial.houseOwnedCopies(), reloaded.houseOwnedCopies());
      assertEquals(initial.hand(PlayerSeat.TWO), reloaded.hand(PlayerSeat.TWO));

      MatchCommand.PlaceCard command =
          new MatchCommand.PlaceCard(
              reloaded.currentSeat(),
              reloaded.hand(reloaded.currentSeat()).getFirst().copyId(),
              Square.CENTER,
              reloaded.revision(),
              UUID.randomUUID(),
              reloaded.turnStartedAt());
      MatchEngine.MatchTransition transition = MatchEngine.apply(reloaded, command);
      assertTrue(transition.accepted());
      assertTrue(repository.save(reloaded.revision(), transition.state()).accepted());
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static CardCatalog catalog() {
    List<CardDefinition> definitions = new ArrayList<>();
    for (int index = 1; index <= 15; index++) {
      definitions.add(
          new CardDefinition(
              new CardId(index),
              "No. " + index,
              "Card " + index,
              1,
              CardType.NORMAL,
              1,
              1,
              1,
              1,
              100));
    }
    return new CardCatalog(definitions, "standard-house-catalog");
  }

  private static List<CardCopy> deckCopies(CopyOwner owner, int firstId) {
    List<CardCopy> copies = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      copies.add(
          new CardCopy(
              new CardCopyId(new UUID(firstId, index + 1L)), new CardId(firstId + index), owner));
    }
    return copies;
  }
}
