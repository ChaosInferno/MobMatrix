package org.aincraft.mobmatrix.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
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

  @Test
  void startRejectsCopySetThatDoesNotMatchPhysicalDeckBeforeEscrow() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, 1, 1, 1, 1, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match = new MatchEngine.StartMatch(new MatchId("copy-validation"), new Deck(one), new Deck(two),
        new CardCatalog(definitions, "validation-v1"), new MatchRules(), 5L,
        Instant.parse("2026-01-01T00:00:00Z"));
    List<CardCopyId> wrong = new ArrayList<>(one.stream().map(CardCopy::copyId).toList());
    wrong.set(0, new CardCopyId(UUID.randomUUID()));
    MatchApplicationService service = new MatchApplicationService(new RecordingCustody());
    var result = service.start(new MatchApplicationService.StartMatchRequest(match, oneOwner, wrong,
        UUID.randomUUID(), twoOwner, two.stream().map(CardCopy::copyId).toList(), UUID.randomUUID()));
    assertFalse(result.accepted());
    assertEquals(CardCustodyPort.Failure.MISMATCHED_OPERATION, result.failure());
  }

  @Test
  void randomStartReservesTheExactCopiesInThePureStartState() {
    CopyOwner oneOwner = CopyOwner.player(UUID.randomUUID());
    CopyOwner twoOwner = CopyOwner.player(UUID.randomUUID());
    List<CardDefinition> definitions = new ArrayList<>();
    List<CardCopy> one = new ArrayList<>();
    List<CardCopy> two = new ArrayList<>();
    for (int index = 1; index <= 10; index++) {
      definitions.add(new CardDefinition(new CardId(index), "No. " + index, "Card " + index,
          1, CardType.NORMAL, 1, 1, 1, 1, 0));
      one.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), oneOwner));
      two.add(new CardCopy(new CardCopyId(UUID.randomUUID()), new CardId(index), twoOwner));
    }
    var match = new MatchEngine.StartMatch(new MatchId("random-custody"), new Deck(one.subList(0, 5)),
        new Deck(two.subList(0, 5)), new CardCatalog(definitions, "random-v1"),
        new MatchRules(org.aincraft.mobmatrix.domain.Rule.RANDOM), 7L,
        Instant.parse("2026-01-01T00:00:00Z"), one, two);
    MatchEngine.MatchTransition pure = MatchEngine.start(match);
    List<CardCopyId> selectedOne = pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.ONE)
        .stream().map(card -> card.copyId()).toList();
    List<CardCopyId> selectedTwo = pure.state().hand(org.aincraft.mobmatrix.domain.PlayerSeat.TWO)
        .stream().map(card -> card.copyId()).toList();
    RecordingCustody custody = new RecordingCustody();
    var result = new MatchApplicationService(custody).start(new MatchApplicationService.StartMatchRequest(
        match, oneOwner, selectedOne, UUID.randomUUID(), twoOwner, selectedTwo, UUID.randomUUID()));
    assertTrue(result.accepted());
    assertEquals(List.of(selectedOne, selectedTwo), custody.preparedCopies);
  }

  private static final class RecordingCustody implements CardCustodyPort {
    private final List<List<CardCopyId>> preparedCopies = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    @Override
    public PrepareResult prepare(CopyOwner owner, List<CardCopyId> copyIds, UUID operationId) {
      if (!calls.contains("prepare")) calls.add("prepare");
      preparedCopies.add(List.copyOf(copyIds));
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
