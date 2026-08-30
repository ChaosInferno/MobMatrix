package org.aincraft.mobmatrix.application;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.aincraft.mobmatrix.domain.CardCopyId;

/** Exact serialized Paper item bytes held by durable custody storage. */
public record SerializedItemSnapshot(CardCopyId copyId, byte[] bytes) {
  public SerializedItemSnapshot {
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(bytes, "serialized bytes are required");
    if (bytes.length == 0) throw new IllegalArgumentException("serialized bytes must not be empty");
    bytes = bytes.clone();
  }

  public SerializedItemSnapshot(UUID copyId, byte[] bytes) {
    this(new CardCopyId(Objects.requireNonNull(copyId, "copy id is required")), bytes);
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  public String base64() {
    return Base64.getEncoder().encodeToString(bytes);
  }

  public byte[] serializedBytes() {
    return bytes();
  }

  public byte[] data() {
    return bytes();
  }

  public static SerializedItemSnapshot fromBase64(CardCopyId copyId, String encoded) {
    Objects.requireNonNull(encoded, "encoded bytes are required");
    return new SerializedItemSnapshot(copyId, Base64.getDecoder().decode(encoded));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SerializedItemSnapshot snapshot
        && copyId.equals(snapshot.copyId)
        && Arrays.equals(bytes, snapshot.bytes);
  }

  @Override
  public int hashCode() {
    return 31 * copyId.hashCode() + Arrays.hashCode(bytes);
  }
}
