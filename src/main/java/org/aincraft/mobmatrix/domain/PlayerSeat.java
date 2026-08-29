package org.aincraft.mobmatrix.domain;

/** The two seats in a match. */
public enum PlayerSeat {
  ONE,
  TWO;

  public PlayerSeat opponent() {
    return this == ONE ? TWO : ONE;
  }
}
