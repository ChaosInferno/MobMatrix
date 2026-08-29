package org.aincraft.mobmatrix;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProjectFoundationTest {
  @Test
  void testRuntimeUsesRequiredJavaVersion() {
    assertTrue(Runtime.version().feature() >= 25);
  }
}
