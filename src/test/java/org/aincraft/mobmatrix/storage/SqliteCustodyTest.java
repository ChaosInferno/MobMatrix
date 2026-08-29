package org.aincraft.mobmatrix.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort.OperationStatus;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.junit.jupiter.api.Test;

final class SqliteCustodyTest {
  @Test
  void reservationsHaveUniqueCopiesAndDurableStatusTransitions() throws Exception {
    var path = Files.createTempFile("mobmatrix-custody", ".sqlite");
    UUID operation = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    CardCopyId copy = new CardCopyId(UUID.randomUUID());
    try (Database database = new Database(path)) {
      SqliteCardRepository repository = new SqliteCardRepository(database);
      repository.saveCopy(new SqliteCardRepository.StoredCardCopy(copy, new CardId(1),
          CopyOwner.player(ownerId)));
      repository.prepare(operation, CopyOwner.player(ownerId), List.of(copy),
          List.of(new org.aincraft.mobmatrix.application.SerializedItemSnapshot(copy, new byte[] {1})));
      assertEquals(OperationStatus.PREPARED, repository.findReservation(operation).orElseThrow().status());
      assertThrows(IllegalStateException.class,
          () -> repository.prepare(UUID.randomUUID(), CopyOwner.player(UUID.randomUUID()), List.of(copy),
              List.of(new org.aincraft.mobmatrix.application.SerializedItemSnapshot(copy, new byte[] {1}))));
      repository.transition(operation, OperationStatus.ESCROWED);
      assertEquals(OperationStatus.ESCROWED, repository.findReservation(operation).orElseThrow().status());
    }
    try (Database reopened = new Database(path)) {
      assertEquals(OperationStatus.ESCROWED,
          new SqliteCardRepository(reopened).findReservation(operation).orElseThrow().status());
      assertTrue(reopened.connection().isValid(1));
    }
    Files.deleteIfExists(path);
  }
}
