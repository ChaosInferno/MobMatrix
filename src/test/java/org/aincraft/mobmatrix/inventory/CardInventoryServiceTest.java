package org.aincraft.mobmatrix.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CardInventoryServiceTest {
  @Test
  void duplicateUuidScanMustRemainPendingBeforeAnyRestoration() {
    assertTrue(CardInventoryService.restorationMustRemainPending(true, false));
    assertTrue(CardInventoryService.restorationMustRemainPending(true, true));
    assertFalse(CardInventoryService.restorationMustRemainPending(false, false));
  }
}
