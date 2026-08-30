package org.aincraft.mobmatrix.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Commands accepted by the pure match aggregate. */
public sealed interface MatchCommand permits MatchCommand.PlaceCard, MatchCommand.Timeout {
  PlayerSeat actor();

  long expectedRevision();

  UUID commandId();

  Instant commandTime();

  record PlaceCard(
      PlayerSeat actor,
      CardCopyId copyId,
      Square square,
      long expectedRevision,
      UUID commandId,
      Instant commandTime)
      implements MatchCommand {
    public PlaceCard {
      Objects.requireNonNull(actor, "actor is required");
      Objects.requireNonNull(copyId, "copy id is required");
      Objects.requireNonNull(square, "square is required");
      if (expectedRevision < 0) throw new IllegalArgumentException("revision must not be negative");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(commandTime, "command time is required");
    }

    public PlaceCard(
        PlayerSeat actor,
        CardCopyId copyId,
        Square square,
        long expectedRevision,
        String commandId,
        Instant commandTime) {
      this(actor, copyId, square, expectedRevision, UUID.fromString(commandId), commandTime);
    }
  }

  record Timeout(PlayerSeat actor, long expectedRevision, UUID commandId, Instant commandTime)
      implements MatchCommand {
    public Timeout {
      Objects.requireNonNull(actor, "actor is required");
      if (expectedRevision < 0) throw new IllegalArgumentException("revision must not be negative");
      Objects.requireNonNull(commandId, "command id is required");
      Objects.requireNonNull(commandTime, "command time is required");
    }

    public Timeout(PlayerSeat actor, long expectedRevision, String commandId, Instant commandTime) {
      this(actor, expectedRevision, UUID.fromString(commandId), commandTime);
    }
  }
}
