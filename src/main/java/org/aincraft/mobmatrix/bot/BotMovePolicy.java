package org.aincraft.mobmatrix.bot;

import java.time.Instant;
import java.util.Objects;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.RandomTranscript;

/** Pure policy for selecting the next ordinary match command for one seat. */
public interface BotMovePolicy {
  /** Selects a move using the state's turn start as the command time. */
  default Decision choose(MatchState state, PlayerSeat seat, RandomTranscript transcript) {
    Objects.requireNonNull(state, "state is required");
    return choose(state, seat, transcript, state.turnStartedAt());
  }

  /** Selects a move at an explicit time, which keeps callers and tests deterministic. */
  Decision choose(MatchState state, PlayerSeat seat, RandomTranscript transcript, Instant now);

  /** A command or an expected exhausted-hand/board outcome. */
  sealed interface Decision permits Command, NoLegalMove {}

  /** A command accepted by {@code MatchEngine.apply} when applied to the source state. */
  record Command(MatchCommand.PlaceCard command) implements Decision {
    public Command {
      Objects.requireNonNull(command, "command is required");
    }
  }

  /** No legal card/square pair exists for the requested seat. */
  record NoLegalMove(PlayerSeat seat, long revision) implements Decision {
    public NoLegalMove {
      Objects.requireNonNull(seat, "seat is required");
      if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
  }
}
