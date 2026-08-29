package org.aincraft.mobmatrix.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.junit.jupiter.api.Test;

final class MatchApplicationServiceTest {
  @Test
  void prepareEscrowAndSettlementAreIdempotent() {
    RecordingCustody custody = new RecordingCustody();
    MatchApplicationService service = new MatchApplicationService(custody);
    UUID operation = UUID.randomUUID();
    CopyOwner owner = CopyOwner.player(UUID.randomUUID());
    List<CardCopyId> copies = List.of(new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()),
        new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()), new CardCopyId(UUID.randomUUID()));

    MatchApplicationService.StartResult started = service.prepare(owner, copies, operation);
    assertTrue(started.escrowed());
    service.prepare(owner, copies, operation);
    service.settle(operation);
    service.settle(operation);
    assertEquals(List.of("prepare", "escrow", "return"), custody.calls);
  }

  private static final class RecordingCustody implements CardCustodyPort {
    private final List<String> calls = new ArrayList<>();

    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      if (!calls.contains("prepare")) calls.add("prepare");
      return PrepareResult.success(operationId, owner, copyIds, List.of());
    }

    @Override
    public CustodyResult escrow(UUID operationId) {
      if (!calls.contains("escrow")) calls.add("escrow");
      return CustodyResult.success(operationId, OperationStatus.ESCROWED);
    }

    @Override
    public ReturnResult returnCopies(UUID operationId) {
      if (!calls.contains("return")) calls.add("return");
      return ReturnResult.returned(operationId, List.of());
    }

    @Override
    public RecoveryResult recover(UUID operationId) {
      return RecoveryResult.none(operationId);
    }
  }
}
