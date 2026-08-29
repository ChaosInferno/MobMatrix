package org.aincraft.mobmatrix.domain;

import java.util.Objects;
import java.util.UUID;

/** Settlement owner for one physical card copy. House-owned cards never return to players. */
public record CopyOwner(Kind kind, UUID playerId) {
  public enum Kind {
    PLAYER,
    HOUSE
  }

  public CopyOwner {
    Objects.requireNonNull(kind, "owner kind is required");
    if (kind == Kind.PLAYER && playerId == null) {
      throw new IllegalArgumentException("player owner requires a UUID");
    }
    if (kind == Kind.HOUSE && playerId != null) {
      throw new IllegalArgumentException("house owner must not have a player UUID");
    }
  }

  public static CopyOwner player(UUID playerId) {
    return new CopyOwner(Kind.PLAYER, Objects.requireNonNull(playerId, "player id is required"));
  }

  public static CopyOwner house() {
    return new CopyOwner(Kind.HOUSE, null);
  }

  public static CopyOwner of(UUID playerId) {
    return player(playerId);
  }

  public boolean isPlayer() {
    return kind == Kind.PLAYER;
  }

  public boolean isHouse() {
    return kind == Kind.HOUSE;
  }

  public UUID id() {
    return playerId;
  }
}
