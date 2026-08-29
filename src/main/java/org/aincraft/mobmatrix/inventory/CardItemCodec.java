package org.aincraft.mobmatrix.inventory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.Component;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Creates and validates the authoritative PDC identity of one physical paper card. */
public final class CardItemCodec {
  public static final int SCHEMA_VERSION = 1;
  public static final int MIN_CATALOG_ID = 1;
  public static final int MAX_CATALOG_ID = 475;

  private final NamespacedKey schemaKey;
  private final NamespacedKey catalogIdKey;
  private final NamespacedKey copyIdKey;
  private final NamespacedKey integrityKey;
  private final NamespacedKey ownerKindKey;
  private final NamespacedKey ownerIdKey;
  private final byte[] serverSecret;

  public CardItemCodec(String namespace, String serverSecret) {
    this(new NamespacedKey(requireNamespace(namespace), "schema"),
        new NamespacedKey(requireNamespace(namespace), "catalog_id"),
        new NamespacedKey(requireNamespace(namespace), "copy_uuid"),
        new NamespacedKey(requireNamespace(namespace), "integrity"),
        new NamespacedKey(requireNamespace(namespace), "owner_kind"),
        new NamespacedKey(requireNamespace(namespace), "owner_uuid"),
        requireSecret(serverSecret).getBytes(StandardCharsets.UTF_8));
  }

  /** Production-safe constructor; secret must be configured and stable across restarts. */
  public CardItemCodec(String namespace) {
    this(namespace, configuredSecret(namespace));
  }

  /** Production-safe constructor; secret must be configured and stable across restarts. */
  public CardItemCodec(Plugin plugin) {
    this(plugin, configuredSecret(Objects.requireNonNull(plugin, "plugin is required").getName()));
  }

  public CardItemCodec(Plugin plugin, String serverSecret) {
    this(Objects.requireNonNull(plugin, "plugin is required").getName(), serverSecret);
  }

  public CardItemCodec(NamespacedKey schemaKey, NamespacedKey catalogIdKey, NamespacedKey copyIdKey,
      NamespacedKey integrityKey, NamespacedKey ownerKindKey, NamespacedKey ownerIdKey,
      byte[] serverSecret) {
    this.schemaKey = Objects.requireNonNull(schemaKey, "schema key is required");
    this.catalogIdKey = Objects.requireNonNull(catalogIdKey, "catalog id key is required");
    this.copyIdKey = Objects.requireNonNull(copyIdKey, "copy id key is required");
    this.integrityKey = Objects.requireNonNull(integrityKey, "integrity key is required");
    this.ownerKindKey = Objects.requireNonNull(ownerKindKey, "owner kind key is required");
    this.ownerIdKey = Objects.requireNonNull(ownerIdKey, "owner id key is required");
    if (serverSecret == null || serverSecret.length < 16) {
      throw new IllegalArgumentException("server secret must contain at least 16 bytes");
    }
    this.serverSecret = serverSecret.clone();
  }

  /** Creates exactly one non-stackable paper item. */
  public ItemStack create(CardDefinition definition, CardCopyId copyId, CopyOwner owner) {
    Objects.requireNonNull(definition, "definition is required");
    Objects.requireNonNull(copyId, "copy id is required");
    Objects.requireNonNull(owner, "owner is required");
    int catalogId = definition.id().value();
    if (!isCatalogId(catalogId)) throw new IllegalArgumentException("catalog id outside range");
    ItemStack item = new ItemStack(Material.PAPER, 1);
    ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "paper metadata is required");
    String copyUuid = copyId.value().toString();
    String ownerKind = owner.kind().name();
    String ownerUuid = owner.playerId() == null ? "" : owner.playerId().toString();
    PersistentDataContainer pdc = meta.getPersistentDataContainer();
    pdc.set(schemaKey, PersistentDataType.INTEGER, SCHEMA_VERSION);
    pdc.set(catalogIdKey, PersistentDataType.INTEGER, catalogId);
    pdc.set(copyIdKey, PersistentDataType.STRING, copyUuid);
    pdc.set(ownerKindKey, PersistentDataType.STRING, ownerKind);
    pdc.set(ownerIdKey, PersistentDataType.STRING, ownerUuid);
    pdc.set(integrityKey, PersistentDataType.STRING,
        marker(SCHEMA_VERSION, catalogId, copyUuid, ownerKind, ownerUuid));
    meta.displayName(Component.text(definition.name() + " (" + definition.publicNumber() + ")"));
    meta.lore(java.util.List.of(
        Component.text("★".repeat(definition.stars()) + " " + definition.type().name()),
        Component.text("Top " + definition.top() + "  Right " + definition.right()),
        Component.text("Bottom " + definition.bottom() + "  Left " + definition.left())));
    item.setItemMeta(meta);
    return item;
  }
  public ItemStack create(CardDefinition definition, CardCopyId copyId, UUID owner) {
    return create(definition, copyId, CopyOwner.player(Objects.requireNonNull(owner, "owner is required")));
  }

  public ReadResult decode(ItemStack item) {
    return read(item);
  }


  /** Reads only PDC identity; display text is never used as an authority. */
  public ReadResult read(ItemStack item) {
    if (item == null || item.getType() != Material.PAPER) return ReadResult.invalid(InvalidReason.NOT_PAPER);
    if (item.getAmount() != 1) return ReadResult.invalid(InvalidReason.INVALID_AMOUNT);
    try {
      ItemMeta meta = item.getItemMeta();
      if (meta == null) return ReadResult.invalid(InvalidReason.MISSING_FIELD);
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      Integer schema = pdc.get(schemaKey, PersistentDataType.INTEGER);
      Integer catalogId = pdc.get(catalogIdKey, PersistentDataType.INTEGER);
      String copyUuid = pdc.get(copyIdKey, PersistentDataType.STRING);
      String ownerKind = pdc.get(ownerKindKey, PersistentDataType.STRING);
      String ownerUuid = pdc.get(ownerIdKey, PersistentDataType.STRING);
      String integrity = pdc.get(integrityKey, PersistentDataType.STRING);
      if (schema == null || catalogId == null || copyUuid == null || ownerKind == null
          || ownerUuid == null || integrity == null) {
        return ReadResult.invalid(InvalidReason.MISSING_FIELD);
      }
      if (schema != SCHEMA_VERSION) return ReadResult.invalid(InvalidReason.INVALID_SCHEMA);
      if (!isCatalogId(catalogId)) return ReadResult.invalid(InvalidReason.INVALID_CATALOG_ID);
      UUID copy = parseCanonicalUuid(copyUuid);
      CopyOwner owner;
      if ("HOUSE".equals(ownerKind) && ownerUuid.isEmpty()) {
        owner = CopyOwner.house();
      } else if ("PLAYER".equals(ownerKind)) {
        owner = CopyOwner.player(parseCanonicalUuid(ownerUuid));
      } else {
        return ReadResult.invalid(InvalidReason.INVALID_OWNER);
      }
      String expected = marker(schema, catalogId, copyUuid, ownerKind, ownerUuid);
      if (!constantTimeEquals(expected, integrity)) return ReadResult.invalid(InvalidReason.INVALID_INTEGRITY);
      String displayName = meta.hasDisplayName() && meta.displayName() != null
          ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName())
          : "";
      return ReadResult.valid(new CardItemData(new CardId(catalogId), new CardCopyId(copy), owner,
          schema, integrity, displayName));
    } catch (InvalidUuidException exception) {
      return ReadResult.invalid(InvalidReason.INVALID_UUID);
    } catch (IllegalArgumentException exception) {
      return ReadResult.invalid(InvalidReason.INVALID_VALUE);
    } catch (RuntimeException exception) {
      return ReadResult.invalid(InvalidReason.INVALID_VALUE);
    }
  }
  private static boolean isCatalogId(int catalogId) {
    return catalogId >= MIN_CATALOG_ID && catalogId <= MAX_CATALOG_ID;
  }

  public NamespacedKey schemaKey() { return schemaKey; }
  public NamespacedKey catalogIdKey() { return catalogIdKey; }
  public NamespacedKey copyIdKey() { return copyIdKey; }
  public NamespacedKey integrityKey() { return integrityKey; }
  public NamespacedKey ownerKindKey() { return ownerKindKey; }
  public NamespacedKey ownerIdKey() { return ownerIdKey; }


  public boolean hasIdentity(ItemStack item) {
    if (item == null || item.getItemMeta() == null) return false;
    PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
    var keys = pdc.getKeys();
    return keys.contains(schemaKey)
        || keys.contains(catalogIdKey)
        || keys.contains(copyIdKey)
        || keys.contains(integrityKey)
        || keys.contains(ownerKindKey)
        || keys.contains(ownerIdKey);
  }
  /**
   * Extracts only the canonical physical copy UUID, without asserting that the full card is valid.
   */
  public Optional<CardCopyId> identityCopyId(ItemStack item) {
    if (item == null || item.getItemMeta() == null) return Optional.empty();
    try {
      String raw = item.getItemMeta().getPersistentDataContainer().get(copyIdKey,
          PersistentDataType.STRING);
      return raw == null ? Optional.empty() : Optional.of(new CardCopyId(parseCanonicalUuid(raw)));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private String marker(int schema, int catalogId, String copyUuid, String ownerKind, String ownerUuid) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(serverSecret, "HmacSHA256"));
      String payload = schema + "|" + catalogId + "|" + copyUuid + "|" + ownerKind + "|" + ownerUuid;
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC unavailable", exception);
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null || !value.equals(value.trim())) throw new InvalidUuidException();
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new InvalidUuidException();
    }
    if (!parsed.toString().equals(value)) throw new InvalidUuidException();
    return parsed;
  }

  private static boolean constantTimeEquals(String expected, String actual) {
    return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8));
  }

  private static String requireNamespace(String namespace) {
    if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
    return namespace.toLowerCase(java.util.Locale.ROOT);
  }

  private static String requireSecret(String secret) {
    if (secret == null || secret.isBlank()) throw new IllegalArgumentException("server secret is required");
    return secret;
  }

  private static String configuredSecret(String namespace) {
    String normalized = requireNamespace(namespace);
    String property = System.getProperty("mobmatrix." + normalized + ".server-secret");
    String value = property == null || property.isBlank()
        ? System.getenv("MOBMATRIX_SERVER_SECRET") : property;
    return requireSecret(value);
  }
  private static final class InvalidUuidException extends IllegalArgumentException {
    private InvalidUuidException() {
      super("invalid UUID");
    }
  }


  public enum InvalidReason {
    NOT_PAPER,
    INVALID_AMOUNT,
    MISSING_FIELD,
    INVALID_SCHEMA,
    INVALID_CATALOG_ID,
    INVALID_UUID,
    INVALID_OWNER,
    INVALID_INTEGRITY,
    INVALID_VALUE
  }

  public static final class ReadResult {
    private final CardItemData data;
    private final InvalidReason reason;

    private ReadResult(CardItemData data, InvalidReason reason) {
      this.data = data;
      this.reason = reason;
    }

    public static ReadResult valid(CardItemData data) { return new ReadResult(Objects.requireNonNull(data), null); }
    public static ReadResult invalid(InvalidReason reason) { return new ReadResult(null, Objects.requireNonNull(reason)); }
    public boolean isValid() { return data != null; }
    public boolean valid() { return isValid(); }
    public boolean isInvalid() { return !isValid(); }
    public CardItemData data() { return data; }
    public InvalidReason reason() { return reason; }
    public InvalidReason error() { return reason; }
  }
}
