package org.aincraft.mobmatrix.application;

import java.util.Objects;
import org.aincraft.mobmatrix.domain.CardCopyId;

/** Name emphasizing that snapshots preserve a complete one-item stack. */
public record SerializedItemStack(CardCopyId copyId, byte[] bytes) {
  public SerializedItemStack {
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(bytes, "serialized bytes are required");
    if (bytes.length == 0) throw new IllegalArgumentException("serialized bytes must not be empty");
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  public SerializedItemSnapshot snapshot() {
    return new SerializedItemSnapshot(copyId, bytes);
  }
}
