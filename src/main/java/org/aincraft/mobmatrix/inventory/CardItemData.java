package org.aincraft.mobmatrix.inventory;

import java.util.Objects;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CopyOwner;

/** Validated identity and generated display metadata read from one Paper item. */
public record CardItemData(CardId catalogId, CardCopyId copyId, CopyOwner owner, int schemaVersion,
    String integrityMarker, String displayName) {
  public CardItemData {
    Objects.requireNonNull(catalogId, "catalog id is required");
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(owner, "owner is required");
    Objects.requireNonNull(integrityMarker, "integrity marker is required");
    Objects.requireNonNull(displayName, "display name is required");
    if (schemaVersion < 1) throw new IllegalArgumentException("schema version must be positive");
  }

  public CardId cardId() {
    return catalogId;
  }
}
