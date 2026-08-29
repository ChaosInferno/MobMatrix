package org.aincraft.mobmatrix.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class RandomTranscriptTest {
  @Test
  void seededTranscriptRecordsAndReplaysDecisions() {
    RandomTranscript source = new RandomTranscript(17L);
    int first = source.nextInt("first-seat", 2);
    int second = source.nextInt("open-card", 5);
    assertEquals(List.of(new RandomTranscript.Decision("first-seat", 2, first),
        new RandomTranscript.Decision("open-card", 5, second)), source.decisions());
    RandomTranscript replay = RandomTranscript.replay(source.decisions());
    assertEquals(first, replay.nextInt("first-seat", 2));
    assertEquals(second, replay.nextInt("open-card", 5));
  }

  @Test
  void transcriptRejectsInvalidAndMismatchedReads() {
    RandomTranscript transcript = new RandomTranscript(1L);
    assertThrows(IllegalArgumentException.class, () -> transcript.nextInt("", 2));
    assertThrows(IllegalArgumentException.class, () -> transcript.nextInt("x", 0));
    RandomTranscript replay = RandomTranscript.replay(List.of(new RandomTranscript.Decision("x", 2, 1)));
    assertThrows(IllegalArgumentException.class, () -> replay.nextInt("y", 2));
    assertThrows(IllegalArgumentException.class, () -> replay.nextInt("x", 3));
    assertEquals(1, replay.nextInt("x", 2));
    assertThrows(IllegalStateException.class, () -> replay.nextInt("x", 2));
  }
}
