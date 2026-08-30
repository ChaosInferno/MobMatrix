package org.aincraft.mobmatrix;

import de.flog99.mapgui.MapGui;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aincraft.mobmatrix.application.CardCustodyPort;
import org.aincraft.mobmatrix.application.MatchApplicationService;
import org.aincraft.mobmatrix.application.MatchApplicationService.ApplyResult;
import org.aincraft.mobmatrix.application.MatchApplicationService.MatchStartResult;
import org.aincraft.mobmatrix.application.MatchRepository;
import org.aincraft.mobmatrix.application.MatchView;
import org.aincraft.mobmatrix.bot.BotMovePolicy;
import org.aincraft.mobmatrix.bot.HeuristicBot;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopy;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.aincraft.mobmatrix.domain.CardId;
import org.aincraft.mobmatrix.domain.CardType;
import org.aincraft.mobmatrix.domain.CopyOwner;
import org.aincraft.mobmatrix.domain.Deck;
import org.aincraft.mobmatrix.domain.HandCard;
import org.aincraft.mobmatrix.domain.MatchCommand;
import org.aincraft.mobmatrix.domain.MatchEngine;
import org.aincraft.mobmatrix.domain.MatchId;
import org.aincraft.mobmatrix.domain.MatchRules;
import org.aincraft.mobmatrix.domain.MatchState;
import org.aincraft.mobmatrix.domain.PlayerSeat;
import org.aincraft.mobmatrix.domain.Rule;
import org.aincraft.mobmatrix.inventory.CardInventoryService;
import org.aincraft.mobmatrix.inventory.CardItemCodec;
import org.aincraft.mobmatrix.mapgui.DeckScreen;
import org.aincraft.mobmatrix.mapgui.MatchScreen;
import org.aincraft.mobmatrix.paper.InventoryProtectionListener;
import org.aincraft.mobmatrix.paper.MobMatrixCommand;
import org.aincraft.mobmatrix.paper.PlayerLifecycleListener;
import org.aincraft.mobmatrix.storage.Database;
import org.aincraft.mobmatrix.storage.SqliteCardRepository;
import org.aincraft.mobmatrix.storage.SqliteCardRepository.CardIssuance;
import org.aincraft.mobmatrix.storage.SqliteMatchRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Paper boundary for catalog, custody, command, and match lifecycle wiring. */
public final class MobMatrixPlugin extends JavaPlugin {
  private static final String CATALOG_RESOURCE = "/catalog/cards.csv";
  private static final String CATALOG_HEADER =
      "id,public_number,name,stars,type,top,right,bottom,left,sale_price";
  private static final int CATALOG_SIZE = 475;
  private static final int NO_CARD_COUNT = 460;
  private static final int EX_CARD_COUNT = 15;
  private static final int DEFAULT_MAX_GIVE = 64;
  private static final int MAX_PENDING_CHALLENGES = 128;

  private final Map<UUID, PendingChallenge> pendingChallenges = new HashMap<>();
  private final Map<UUID, List<CardCopyId>> selectedDecks = new HashMap<>();
  private final Map<MatchId, ActiveMatch> activeMatches = new HashMap<>();
  private final Map<UUID, BukkitTask> disconnectTasks = new HashMap<>();
  private BukkitTask botTask;
  private Database database;
  private SqliteCardRepository cards;
  private SqliteMatchRepository matchRepository;
  private CardCatalog catalog;
  private CardItemCodec codec;
  private CardInventoryService custody;
  private MatchApplicationService application;
  private HeuristicBot bot;
  private int maxGiveAmount = DEFAULT_MAX_GIVE;
  private long challengeExpiryMillis = 60_000L;
  private boolean timeoutOnDisconnect;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    try {
      CatalogLoad loaded = loadCatalog();
      catalog = loaded.catalog();
      String secret = requireSecret(getConfig().getString("server-secret"));
      maxGiveAmount = Math.max(1, getConfig().getInt("max-give-amount", DEFAULT_MAX_GIVE));
      challengeExpiryMillis =
          Math.max(1L, getConfig().getLong("challenge-expiry-seconds", 60L) * 1_000L);
      timeoutOnDisconnect =
          !"none".equalsIgnoreCase(getConfig().getString("disconnect-policy", "timeout"));

      database =
          new Database(
              getDataFolder()
                  .toPath()
                  .resolve(getConfig().getString("database-file", "mobmatrix.sqlite")));
      database.migrate();
      cards = new SqliteCardRepository(database);
      cards.saveCatalogSnapshot(loaded.snapshot(), catalog.all());
      matchRepository = new SqliteMatchRepository(database, secret);
      codec = new CardItemCodec(this, secret);
      custody = new CardInventoryService(codec, Bukkit::getPlayer, cards);
      application = new MatchApplicationService(custody, matchRepository);
      for (Player player : Bukkit.getOnlinePlayers()) reconcilePendingIssuances(player);
      bot = new HeuristicBot();

      MatchApplicationService.StartupRecoveryResult recovery =
          application.recoverStartup(
              snapshot ->
                  catalog.snapshotId().equals(snapshot) ? catalog : throwUnknownCatalog(snapshot),
              Instant.now());
      for (MatchState state : recovery.matches()) registerRecoveredMatch(state);
      for (ActiveMatch active : List.copyOf(activeMatches.values())) {
        if (active.state.isFinished()) finishIfNeeded(active);
        else refreshMatch(active);
      }
      getLogger()
          .info(
              "Recovered "
                  + recovery.matches().size()
                  + " active MobMatrix matches and "
                  + recovery.recoveries().size()
                  + " custody operations.");

      MobMatrixCommand command = new MobMatrixCommand(this);
      registerCommand("mobmatrix", List.of("mm"), command);
      getServer().getPluginManager().registerEvents(new InventoryProtectionListener(custody), this);
      getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);
      botTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBots, 1L, 1L);
    } catch (RuntimeException exception) {
      closeResources();
      getLogger().severe("MobMatrix failed to enable: " + exception.getMessage());
      throw exception;
    }
  }

  @Override
  public void onDisable() {
    if (botTask != null) botTask.cancel();
    botTask = null;
    for (BukkitTask task : disconnectTasks.values()) task.cancel();
    disconnectTasks.clear();
    for (Player player : Bukkit.getOnlinePlayers()) player.closeInventory();
    closeResources();
  }

  private void closeResources() {
    if (database != null) database.close();
    database = null;
  }

  private CatalogLoad loadCatalog() {
    try (InputStream stream = getResource("catalog/cards.csv")) {
      if (stream == null) throw new IllegalStateException("missing " + CATALOG_RESOURCE);
      byte[] raw = stream.readAllBytes();
      String digest = hex(MessageDigest.getInstance("SHA-256").digest(raw));
      List<CardDefinition> definitions = parseCatalog(raw);
      CardCatalog loaded = new CardCatalog(definitions, "cards-" + digest);
      String metadata =
          "{\"records\":"
              + definitions.size()
              + ",\"public_numbers\":"
              + NO_CARD_COUNT
              + ",\"exp_numbers\":"
              + EX_CARD_COUNT
              + "}";
      return new CatalogLoad(
          loaded,
          new SqliteCardRepository.CatalogSnapshot(
              loaded.snapshotId(), CATALOG_RESOURCE, digest, metadata));
    } catch (IOException exception) {
      throw new UncheckedIOException("unable to read " + CATALOG_RESOURCE, exception);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  public static List<CardDefinition> parseCatalog(byte[] raw) {
    Objects.requireNonNull(raw, "catalog bytes are required");
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new java.io.ByteArrayInputStream(raw), StandardCharsets.UTF_8))) {
      if (!CATALOG_HEADER.equals(reader.readLine()))
        throw new IllegalStateException("unexpected catalog header");
      List<CardDefinition> definitions = new ArrayList<>();
      Set<String> publicNumbers = new HashSet<>();
      int noCount = 0;
      int exCount = 0;
      for (String line; (line = reader.readLine()) != null; ) {
        String[] fields = parseCsvLine(line);
        if (fields.length != 10) throw new IllegalStateException("malformed catalog row");
        CardDefinition definition =
            new CardDefinition(
                new CardId(Integer.parseInt(fields[0])),
                fields[1],
                fields[2],
                Integer.parseInt(fields[3]),
                CardType.valueOf(fields[4].toUpperCase(Locale.ROOT)),
                Integer.parseInt(fields[5]),
                Integer.parseInt(fields[6]),
                Integer.parseInt(fields[7]),
                Integer.parseInt(fields[8]),
                Integer.parseInt(fields[9]));
        definitions.add(definition);
        if (!publicNumbers.add(definition.publicNumber()))
          throw new IllegalStateException("duplicate public number");
        if (definition.publicNumber().startsWith("No.")) noCount++;
        else if (definition.publicNumber().startsWith("Ex.")) exCount++;
        else throw new IllegalStateException("unknown public number label");
      }
      if (definitions.size() != CATALOG_SIZE
          || noCount != NO_CARD_COUNT
          || exCount != EX_CARD_COUNT) {
        throw new IllegalStateException("catalog must contain exactly 460 No. and 15 Ex. records");
      }
      return List.copyOf(definitions);
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalStateException("malformed catalog", exception);
    }
  }

  private static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
          field.append('"');
          index++;
        } else quoted = !quoted;
      } else if (character == ',' && !quoted) {
        fields.add(field.toString());
        field.setLength(0);
      } else field.append(character);
    }
    if (quoted) throw new IllegalStateException("unterminated CSV quote");
    fields.add(field.toString());
    return fields.toArray(String[]::new);
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private static String requireSecret(String secret) {
    if (secret == null || secret.isBlank() || secret.length() < 16) {
      throw new IllegalStateException("server-secret must contain at least 16 characters");
    }
    return secret;
  }

  private CardCatalog throwUnknownCatalog(String snapshot) {
    throw new IllegalStateException("unknown catalog snapshot: " + snapshot);
  }

  private void registerRecoveredMatch(MatchState state) {
    Optional<MatchRepository.StartBinding> binding =
        matchRepository.findStartBinding(state.matchId());
    if (binding.isEmpty()) return;
    MatchRepository.StartBinding value = binding.orElseThrow();
    UUID one = value.oneOwner().isPlayer() ? value.oneOwner().playerId() : null;
    UUID two = value.twoOwner().isPlayer() ? value.twoOwner().playerId() : null;
    activeMatches.put(
        state.matchId(),
        new ActiveMatch(
            state, one, two, value.oneOperationId(), value.twoOperationId(), two == null));
  }

  public CardCatalog catalog() {
    return Objects.requireNonNull(catalog, "plugin is not enabled");
  }

  public int maxGiveAmount() {
    return maxGiveAmount;
  }

  public CardItemCodec codec() {
    return Objects.requireNonNull(codec, "plugin is not enabled");
  }

  public CardInventoryService custody() {
    return Objects.requireNonNull(custody, "plugin is not enabled");
  }

  public MatchApplicationService application() {
    return Objects.requireNonNull(application, "plugin is not enabled");
  }

  public GiveResult give(Player target, CardDefinition definition, int amount) {
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(definition, "definition is required");
    MobMatrixCommand.Validation validation =
        MobMatrixCommand.validateAmount(amount, maxGiveAmount());
    if (!validation.accepted()) return new GiveResult(false, validation.message());
    Inventory inventory = target.getInventory();
    long empty =
        java.util.Arrays.stream(inventory.getStorageContents()).filter(Objects::isNull).count();
    if (empty < amount)
      return new GiveResult(false, "Not enough inventory capacity for all requested cards.");
    CopyOwner owner = CopyOwner.player(target.getUniqueId());
    List<CardIssuance> issuances = new ArrayList<>(amount);
    List<ItemStack> items = new ArrayList<>(amount);
    try {
      for (int index = 0; index < amount; index++) {
        CardCopyId copyId = new CardCopyId(UUID.randomUUID());
        ItemStack item = codec().create(definition, copyId, owner);
        CardIssuance issuance =
            new CardIssuance(
                copyId, definition.id(), owner, CardInventoryService.serializeItem(item.clone()));
        cards.saveIssuance(issuance);
        issuances.add(issuance);
        items.add(item);
      }
      for (int index = 0; index < issuances.size(); index++) {
        if (!deliverIssuance(target, issuances.get(index), items.get(index))) {
          reconcilePendingIssuances(target);
          return new GiveResult(
              false, "Card grant pending recovery; retry after inventory space is available.");
        }
      }
      return new GiveResult(true, "Gave " + amount + " copy of " + definition.publicNumber() + ".");
    } catch (RuntimeException exception) {
      getLogger()
          .warning(
              "Card grant requires durable recovery for "
                  + target.getUniqueId()
                  + ": "
                  + exception.getMessage());
      reconcilePendingIssuances(target);
      return new GiveResult(false, "Card grant pending recovery; no card identity was discarded.");
    }
  }

  private boolean deliverIssuance(Player player, CardIssuance issuance, ItemStack item) {
    List<LocatedItem> existing = locate(player.getInventory(), issuance.copyId());
    if (existing.size() > 1) return false;
    if (existing.size() == 1) {
      if (!inventoryMatchesIssuance(existing.getFirst().item(), issuance)) return false;
      cards.markIssuanceDelivered(issuance.copyId());
      return true;
    }
    ItemStack exact = item == null ? issuanceItem(issuance) : item.clone();
    if (exact == null) return false;
    if (!inventoryMatchesIssuance(exact, issuance)) return false;
    if (!player.getInventory().addItem(exact).isEmpty()) return false;
    List<LocatedItem> delivered = locate(player.getInventory(), issuance.copyId());
    if (delivered.size() != 1 || !inventoryMatchesIssuance(delivered.getFirst().item(), issuance))
      return false;
    cards.markIssuanceDelivered(issuance.copyId());
    return true;
  }

  private void reconcilePendingIssuances(Player player) {
    try {
      CopyOwner owner = CopyOwner.player(player.getUniqueId());
      for (CardIssuance issuance : cards.pendingIssuances(owner)) {
        if (!issuance.owner().equals(owner)) continue;
        deliverIssuance(player, issuance, null);
      }
    } catch (RuntimeException exception) {
      getLogger()
          .warning(
              "Card issuance recovery remains pending for "
                  + player.getUniqueId()
                  + ": "
                  + exception.getMessage());
    }
  }

  private ItemStack issuanceItem(CardIssuance issuance) {
    try {
      ItemStack item = CardInventoryService.deserializeItem(issuance.itemBytes());
      return inventoryMatchesIssuance(item, issuance) ? item : null;
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private boolean inventoryMatchesIssuance(ItemStack item, CardIssuance issuance) {
    CardItemCodec.ReadResult result = codec().read(item);
    return result.isValid()
        && result.data().copyId().equals(issuance.copyId())
        && result.data().catalogId().equals(issuance.catalogId())
        && result.data().owner().equals(issuance.owner())
        && java.util.Arrays.equals(
            CardInventoryService.serializeItem(item.clone()), issuance.itemBytes());
  }

  private List<LocatedItem> locate(Inventory inventory, CardCopyId copyId) {
    List<LocatedItem> found = new ArrayList<>();
    ItemStack[] contents = inventory.getStorageContents();
    for (int slot = 0; slot < contents.length; slot++) {
      ItemStack item = contents[slot];
      if (item != null && containsCopyIdentity(item, copyId))
        found.add(new LocatedItem(slot, item));
    }
    return List.copyOf(found);
  }

  private boolean containsCopyIdentity(ItemStack item, CardCopyId copyId) {
    if (codec().identityCopyId(item).filter(copyId::equals).isPresent()) return true;
    if (item == null || item.getItemMeta() == null) return false;
    try {
      String raw =
          item.getItemMeta()
              .getPersistentDataContainer()
              .get(codec().copyIdKey(), org.bukkit.persistence.PersistentDataType.STRING);
      return raw != null && raw.trim().equalsIgnoreCase(copyId.value().toString());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  public List<HandCard> ownedCards(Player player) {
    Objects.requireNonNull(player, "player is required");
    Map<CardCopyId, HandCard> found = new HashMap<>();
    for (ItemStack item : player.getInventory().getStorageContents()) {
      if (item == null) continue;
      CardItemCodec.ReadResult result = codec().read(item);
      if (!result
          .data()
          .owner()
          .equals(org.aincraft.mobmatrix.domain.CopyOwner.player(player.getUniqueId()))) continue;
      CardDefinition definition = catalog().require(result.data().catalogId());
      HandCard card =
          new HandCard(
              new CardCopy(
                  result.data().copyId(), result.data().catalogId(), result.data().owner()),
              definition);
      if (found.put(result.data().copyId(), card) != null) return List.of();
    }
    return found.values().stream()
        .sorted(Comparator.comparing(card -> card.copyId().value()))
        .toList();
  }

  public void setSelectedDeck(Player player, Collection<CardCopyId> copyIds) {
    Objects.requireNonNull(player, "player is required");
    MobMatrixCommand.Validation validation = MobMatrixCommand.validateDeck(copyIds);
    if (!validation.accepted()) throw new IllegalArgumentException(validation.message());
    Set<CardCopyId> owned =
        ownedCards(player).stream()
            .map(HandCard::copyId)
            .collect(java.util.stream.Collectors.toSet());
    List<CardCopyId> selected = List.copyOf(copyIds);
    if (!owned.containsAll(selected))
      throw new IllegalArgumentException("selected cards are not owned");
    selectedDecks.put(player.getUniqueId(), selected);
  }

  public void openDeck(Player player) {
    Objects.requireNonNull(player, "player is required");
    List<HandCard> cards = ownedCards(player);
    if (cards.size() < 5) {
      player.sendMessage("You need five valid physical cards before opening a deck.");
      return;
    }
    MapGui.get()
        .open(
            player,
            new DeckScreen(
                cards,
                selected -> {
                  try {
                    setSelectedDeck(player, selected);
                    player.sendMessage("Deck selected.");
                  } catch (IllegalArgumentException exception) {
                    player.sendMessage(exception.getMessage());
                  }
                }));
  }

  public boolean isBusy(UUID playerId) {
    if (playerId == null) return false;
    purgeExpiredChallenges();
    return activeMatches.values().stream()
        .anyMatch(match -> playerId.equals(match.onePlayer) || playerId.equals(match.twoPlayer));
  }

  public boolean hasChallengeFrom(UUID playerId) {
    purgeExpiredChallenges();
    return pendingChallenges.values().stream()
        .anyMatch(challenge -> challenge.from.equals(playerId));
  }

  public boolean hasChallengeTo(UUID playerId) {
    purgeExpiredChallenges();
    return pendingChallenges.values().stream().anyMatch(challenge -> challenge.to.equals(playerId));
  }

  public ChallengeResult challenge(Player from, Player to) {
    MobMatrixCommand.ChallengeValidation validation =
        MobMatrixCommand.validateChallenge(
            from.getUniqueId(),
            to.getUniqueId(),
            to.isOnline(),
            isBusy(from.getUniqueId()),
            isBusy(to.getUniqueId())
                || hasChallengeFrom(to.getUniqueId())
                || hasChallengeTo(to.getUniqueId()),
            hasChallengeTo(from.getUniqueId()),
            hasChallengeFrom(from.getUniqueId()));
    if (!validation.accepted()) return new ChallengeResult(false, validation.message());
    purgeExpiredChallenges();
    if (pendingChallenges.size() >= MAX_PENDING_CHALLENGES)
      return new ChallengeResult(false, "Too many pending challenges.");
    UUID id =
        UUID.nameUUIDFromBytes(
            ("challenge:" + from.getUniqueId() + ":" + to.getUniqueId())
                .getBytes(StandardCharsets.UTF_8));
    PendingChallenge pending =
        new PendingChallenge(
            id,
            from.getUniqueId(),
            to.getUniqueId(),
            System.currentTimeMillis() + challengeExpiryMillis);
    pendingChallenges.put(id, pending);
    to.sendMessage(from.getName() + " challenged you to MobMatrix. Use /mobmatrix accept.");
    return new ChallengeResult(true, "Challenge sent to " + to.getName() + ".");
  }

  public ChallengeResult accept(Player player) {
    PendingChallenge pending = incoming(player.getUniqueId());
    if (pending == null) return new ChallengeResult(false, "You have no pending challenge.");
    Player challenger = Bukkit.getPlayer(pending.from);
    if (challenger == null) return new ChallengeResult(false, "The challenger is offline.");
    if (isBusy(player.getUniqueId()) || isBusy(challenger.getUniqueId()))
      return new ChallengeResult(false, "Both players must be free.");
    List<CardCopyId> oneIds = selectedDecks.get(challenger.getUniqueId());
    List<CardCopyId> twoIds = selectedDecks.get(player.getUniqueId());
    MobMatrixCommand.Validation oneDeck = MobMatrixCommand.validateDeck(oneIds);
    MobMatrixCommand.Validation twoDeck = MobMatrixCommand.validateDeck(twoIds);
    if (!oneDeck.accepted() || !twoDeck.accepted())
      return new ChallengeResult(false, "Both players must select exactly five cards.");
    List<CardCopy> one = copiesFor(challenger, oneIds);
    List<CardCopy> two = copiesFor(player, twoIds);
    if (one.size() != 5 || two.size() != 5)
      return new ChallengeResult(false, "Selected cards are missing from inventory.");
    MatchId matchId = new MatchId(pending.id);
    Set<Rule> configuredRules = configuredRules();
    MatchRules rules = new MatchRules(configuredRules);
    MatchEngine.StartMatch match =
        new MatchEngine.StartMatch(
            matchId,
            new Deck(one),
            new Deck(two),
            catalog(),
            rules,
            seedFor(matchId),
            Instant.now(),
            one,
            two);
    UUID oneOperation = operation(matchId, "one");
    UUID twoOperation = operation(matchId, "two");
    List<CardCopyId> oneReservation = rules.has(Rule.DRAFT) ? List.of() : oneIds;
    List<CardCopyId> twoReservation = rules.has(Rule.DRAFT) ? List.of() : twoIds;
    MatchStartResult result =
        application()
            .start(
                new MatchApplicationService.StartMatchRequest(
                    match,
                    org.aincraft.mobmatrix.domain.CopyOwner.player(challenger.getUniqueId()),
                    oneReservation,
                    oneOperation,
                    org.aincraft.mobmatrix.domain.CopyOwner.player(player.getUniqueId()),
                    twoReservation,
                    twoOperation));
    if (!result.accepted()) return reportStartFailure(result);
    pendingChallenges.remove(pending.id);
    activeMatches.put(
        matchId,
        new ActiveMatch(
            result.state(),
            challenger.getUniqueId(),
            player.getUniqueId(),
            oneOperation,
            twoOperation,
            false));
    openMatch(challenger, result.state(), PlayerSeat.ONE);
    openMatch(player, result.state(), PlayerSeat.TWO);
    return new ChallengeResult(true, "Challenge accepted.");
  }

  public ChallengeResult decline(Player player) {
    PendingChallenge pending = incoming(player.getUniqueId());
    if (pending == null) return new ChallengeResult(false, "You have no pending challenge.");
    pendingChallenges.remove(pending.id);
    Player challenger = Bukkit.getPlayer(pending.from);
    if (challenger != null) challenger.sendMessage(player.getName() + " declined your challenge.");
    return new ChallengeResult(true, "Challenge declined.");
  }

  public ChallengeResult cancel(Player player) {
    PendingChallenge pending = outgoing(player.getUniqueId());
    if (pending == null) return new ChallengeResult(false, "You have no outgoing challenge.");
    pendingChallenges.remove(pending.id);
    return new ChallengeResult(true, "Challenge cancelled.");
  }

  public ChallengeResult startBot(Player player) {
    if (isBusy(player.getUniqueId()))
      return new ChallengeResult(false, "You are already in a match.");
    List<CardCopyId> ids = selectedDecks.get(player.getUniqueId());
    MobMatrixCommand.Validation validation = MobMatrixCommand.validateDeck(ids);
    if (!validation.accepted()) return new ChallengeResult(false, validation.message());
    List<CardCopy> one = copiesFor(player, ids);
    if (one.size() != 5)
      return new ChallengeResult(false, "Selected cards are missing from inventory.");
    MatchId matchId = freshBotMatchId();
    List<CardCopy> house = houseDeck(matchId);
    Set<Rule> configuredRules = configuredRules();
    MatchRules rules = new MatchRules(configuredRules);
    MatchEngine.StartMatch match =
        new MatchEngine.StartMatch(
            matchId,
            new Deck(one),
            new Deck(house),
            catalog(),
            rules,
            seedFor(matchId),
            Instant.now(),
            one,
            house);
    UUID playerOperation = operation(matchId, "player");
    UUID houseOperation = operation(matchId, "house");
    List<CardCopyId> reservation = rules.has(Rule.DRAFT) ? List.of() : ids;
    MatchStartResult result =
        application()
            .startBot(
                new MatchApplicationService.BotStartMatchRequest(
                    match,
                    org.aincraft.mobmatrix.domain.CopyOwner.player(player.getUniqueId()),
                    reservation,
                    playerOperation,
                    houseOperation));
    if (!result.accepted()) return reportStartFailure(result);
    activeMatches.put(
        matchId,
        new ActiveMatch(
            result.state(), player.getUniqueId(), null, playerOperation, houseOperation, true));
    openMatch(player, result.state(), PlayerSeat.ONE);
    return new ChallengeResult(true, "Bot match started.");
  }

  private List<CardCopy> copiesFor(Player player, List<CardCopyId> ids) {
    Map<CardCopyId, HandCard> cards = new HashMap<>();
    for (HandCard card : ownedCards(player)) cards.put(card.copyId(), card);
    List<CardCopy> result = new ArrayList<>();
    if (ids != null)
      for (CardCopyId id : ids) {
        HandCard card = cards.get(id);
        if (card == null) return List.of();
        result.add(card.copy());
      }
    return List.copyOf(result);
  }

  private List<CardCopy> houseDeck(MatchId matchId) {
    List<CardDefinition> definitions =
        catalog().all().stream()
            .sorted(Comparator.comparingInt(definition -> definition.id().value()))
            .limit(5)
            .toList();
    List<CardCopy> result = new ArrayList<>();
    for (int index = 0; index < definitions.size(); index++) {
      CardDefinition definition = definitions.get(index);
      UUID copy =
          UUID.nameUUIDFromBytes(
              ("house:" + matchId.value() + ":" + index).getBytes(StandardCharsets.UTF_8));
      result.add(
          new CardCopy(
              new CardCopyId(copy),
              definition.id(),
              org.aincraft.mobmatrix.domain.CopyOwner.house()));
    }
    return List.copyOf(result);
  }

  private Set<Rule> configuredRules() {
    Set<Rule> rules = new HashSet<>();
    for (String raw : getConfig().getStringList("rules")) {
      try {
        rules.add(Rule.valueOf(raw.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        getLogger().warning("Ignoring unknown rule: " + raw);
      }
    }
    return Set.copyOf(rules);
  }

  private long seedFor(MatchId id) {
    return getConfig().getLong("bot-seed", 449748L) ^ id.value().hashCode();
  }

  private static UUID operation(MatchId matchId, String seat) {
    return UUID.nameUUIDFromBytes(
        ("operation:" + matchId.value() + ":" + seat).getBytes(StandardCharsets.UTF_8));
  }

  static MatchId freshBotMatchId() {
    return new MatchId(UUID.randomUUID().toString());
  }

  private ChallengeResult reportStartFailure(MatchStartResult result) {
    if (!result.pendingRecoveryCopyIds().isEmpty() || !result.missingCopyIds().isEmpty()) {
      return new ChallengeResult(
          false,
          "Match pending recovery; pending="
              + result.pendingRecoveryCopyIds()
              + ", missing="
              + result.missingCopyIds());
    }
    return new ChallengeResult(false, "Match could not start: " + result.failure());
  }

  private void tickBots() {
    for (ActiveMatch active : List.copyOf(activeMatches.values())) {
      if (!active.bot || active.state.isFinished() || active.state.currentSeat() != PlayerSeat.TWO)
        continue;
      applyBotTurn(active, Instant.now());
      if (activeMatches.containsKey(active.state.matchId()) && !active.state.isFinished()) {
        refreshMatch(active);
      }
    }
  }

  private void applyBotTurn(ActiveMatch active, Instant now) {
    BotTurnResult result =
        advanceBotTurn(
            active.state, bot, now, command -> application().apply(active.state, command));
    if (result.applied()) {
      active.state = result.state();
      finishIfNeeded(active);
    } else if (!result.waiting() && result.result() != null) {
      getLogger()
          .warning(
              "Bot command rejected for "
                  + active.state.matchId()
                  + ": "
                  + result.result().failure());
    }
  }

  static BotTurnResult advanceBotTurn(
      MatchState state,
      BotMovePolicy policy,
      Instant now,
      java.util.function.Function<MatchCommand, ApplyResult> applier) {
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(policy, "bot policy is required");
    Objects.requireNonNull(now, "time is required");
    Objects.requireNonNull(applier, "command applier is required");
    if (state.isFinished() || state.currentSeat() != PlayerSeat.TWO) {
      return BotTurnResult.waiting(state);
    }
    MatchCommand command;
    if (!now.isBefore(state.turnDeadline())) {
      command =
          new MatchCommand.Timeout(
              PlayerSeat.TWO,
              state.revision(),
              UUID.nameUUIDFromBytes(
                  ("bot-timeout:" + state.matchId().value() + ":" + state.revision())
                      .getBytes(StandardCharsets.UTF_8)),
              now);
    } else {
      BotMovePolicy.Decision decision =
          policy.choose(state, PlayerSeat.TWO, state.transcript(), now);
      if (decision instanceof BotMovePolicy.NoLegalMove) return BotTurnResult.waiting(state);
      command = ((BotMovePolicy.Command) decision).command();
    }
    ApplyResult result = Objects.requireNonNull(applier.apply(command), "apply result is required");
    return result.accepted()
        ? BotTurnResult.applied(result.transition().state(), command, result)
        : BotTurnResult.rejected(state, command, result);
  }

  private void finishIfNeeded(ActiveMatch active) {
    if (!active.state.isFinished()) return;
    boolean playerCopies = !active.state.rules().has(Rule.DRAFT);
    CardCustodyPort.ReturnResult one =
        playerCopies
            ? application().settle(active.oneOperation)
            : CardCustodyPort.ReturnResult.returned(active.oneOperation, List.of());
    CardCustodyPort.ReturnResult two =
        active.bot || !playerCopies ? null : application().settle(active.twoOperation);
    if (!one.complete()) {
      getLogger()
          .warning(
              "Player card return pending for "
                  + active.state.matchId()
                  + ": "
                  + one.missingCopyIds());
    }
    if (two != null && !two.complete()) {
      getLogger()
          .warning(
              "Player card return pending for "
                  + active.state.matchId()
                  + ": "
                  + two.missingCopyIds());
    }
    if (one.complete() && (two == null || two.complete())) {
      activeMatches.remove(active.state.matchId());
    }
  }

  public void handlePlayerQuit(Player player) {
    closeMatch(player);
    if (!timeoutOnDisconnect) return;
    for (ActiveMatch active : activeMatches.values()) {
      if (active.bot
          ? !player.getUniqueId().equals(active.onePlayer)
          : (!player.getUniqueId().equals(active.onePlayer)
              && !player.getUniqueId().equals(active.twoPlayer))) continue;
      PlayerSeat seat =
          player.getUniqueId().equals(active.onePlayer) ? PlayerSeat.ONE : PlayerSeat.TWO;
      if (active.state.currentSeat() != seat || active.state.isFinished()) continue;
      long delay = Math.max(1L, active.state.remainingTurnMillis(seat, Instant.now()) / 50L + 1L);
      BukkitTask prior =
          disconnectTasks.put(
              player.getUniqueId(),
              Bukkit.getScheduler()
                  .runTaskLater(
                      this, () -> timeoutDisconnected(active.state.matchId(), seat), delay));
      if (prior != null) prior.cancel();
    }
  }

  private void timeoutDisconnected(MatchId matchId, PlayerSeat seat) {
    ActiveMatch active = activeMatches.get(matchId);
    if (active == null || active.state.isFinished() || active.state.currentSeat() != seat) return;
    MatchCommand.Timeout timeout =
        new MatchCommand.Timeout(
            seat,
            active.state.revision(),
            UUID.nameUUIDFromBytes(
                ("disconnect-timeout:" + matchId.value() + ":" + active.state.revision())
                    .getBytes(StandardCharsets.UTF_8)),
            Instant.now());
    ApplyResult result = application().apply(active.state, timeout);
    if (result.accepted()) {
      active.state = result.transition().state();
      finishIfNeeded(active);
      if (activeMatches.containsKey(matchId)
          && active.bot
          && active.state.currentSeat() == PlayerSeat.TWO) {
        applyBotTurn(active, Instant.now());
      }
      if (activeMatches.containsKey(matchId) && !active.state.isFinished()) refreshMatch(active);
    }
  }

  public void handlePlayerJoin(Player player) {
    BukkitTask task = disconnectTasks.remove(player.getUniqueId());
    if (task != null) task.cancel();
    reconcilePendingIssuances(player);
    for (UUID operation : custody().openOperationIds()) {
      try {
        Optional<CardCustodyPort.OperationBinding> binding = custody().operationBinding(operation);
        if (binding.isEmpty()
            || !binding.orElseThrow().owner().isPlayer()
            || !player.getUniqueId().equals(binding.orElseThrow().owner().playerId())) {
          continue;
        }
        Optional<MatchId> matchId = custody().operationMatchId(operation);
        ActiveMatch active = matchId.map(activeMatches::get).orElse(null);
        if (active == null || active.state.isFinished()) application().recover(operation);
      } catch (RuntimeException exception) {
        getLogger()
            .warning(
                "Unable to recover custody operation "
                    + operation
                    + " on join: "
                    + exception.getMessage());
      }
    }
    ActiveMatch active =
        activeMatches.values().stream()
            .filter(
                match ->
                    player.getUniqueId().equals(match.onePlayer)
                        || player.getUniqueId().equals(match.twoPlayer))
            .findFirst()
            .orElse(null);
    if (active != null && !active.state.isFinished()) {
      PlayerSeat seat =
          player.getUniqueId().equals(active.onePlayer) ? PlayerSeat.ONE : PlayerSeat.TWO;
      openMatch(player, active.state, seat);
    }
  }

  private void openMatch(Player player, MatchState state, PlayerSeat seat) {
    ActiveMatch active = activeMatches.get(state.matchId());
    if (active == null) return;
    MatchView view =
        MatchView.from(
            org.aincraft.mobmatrix.domain.MatchProjection.forViewer(state, seat, Instant.now()),
            state.rules());
    MapGui.get()
        .open(player, new MatchScreen(view, seat, intent -> handleMatchIntent(player, intent)));
  }

  private record LocatedItem(int slot, ItemStack item) {}

  private void handleMatchIntent(Player player, MatchScreen.Intent intent) {
    Objects.requireNonNull(intent, "intent is required");
    if (intent instanceof MatchScreen.PlaceCardIntent place) {
      applyMatchCommand(
          player,
          new MatchCommand.PlaceCard(
              place.actor(),
              place.copyId(),
              place.square(),
              place.expectedRevision(),
              place.commandId(),
              place.commandTime()));
    } else if (intent instanceof MatchScreen.TimeoutIntent timeout) {
      applyMatchCommand(
          player,
          new MatchCommand.Timeout(
              timeout.actor(),
              timeout.expectedRevision(),
              timeout.commandId(),
              timeout.commandTime()));
    }
  }

  private void refreshMatch(ActiveMatch active) {
    Player one = active.onePlayer == null ? null : Bukkit.getPlayer(active.onePlayer);
    if (one != null) openMatch(one, active.state, PlayerSeat.ONE);
    Player two = active.twoPlayer == null ? null : Bukkit.getPlayer(active.twoPlayer);
    if (two != null) openMatch(two, active.state, PlayerSeat.TWO);
  }

  private void applyMatchCommand(Player player, MatchCommand command) {
    ActiveMatch active =
        activeMatches.values().stream()
            .filter(
                match ->
                    player.getUniqueId().equals(match.onePlayer)
                        || player.getUniqueId().equals(match.twoPlayer))
            .findFirst()
            .orElse(null);
    if (active == null) return;
    PlayerSeat seat =
        player.getUniqueId().equals(active.onePlayer) ? PlayerSeat.ONE : PlayerSeat.TWO;
    if (command.actor() != seat) {
      player.sendMessage("That is not your turn.");
      return;
    }
    ApplyResult applied = application().apply(active.state, command);
    if (!applied.accepted()) {
      player.sendMessage("Move rejected: " + applied.failure());
      if (applied.failure() == CardCustodyPort.Failure.STALE_REVISION) {
        openMatch(player, active.state, seat);
      }
      return;
    }
    active.state = applied.transition().state();
    finishIfNeeded(active);
    if (activeMatches.containsKey(active.state.matchId())
        && active.bot
        && active.state.currentSeat() == PlayerSeat.TWO) {
      applyBotTurn(active, Instant.now());
    }
    if (activeMatches.containsKey(active.state.matchId()) && !active.state.isFinished()) {
      refreshMatch(active);
    }
  }

  private void closeMatch(Player player) {
    // MapGUI owns session lifecycle. Closing a screen never settles custody or deletes a match row.
    player.closeInventory();
  }

  private void purgeExpiredChallenges() {
    long now = System.currentTimeMillis();
    pendingChallenges.values().removeIf(value -> value.expiresAt < now);
  }

  private PendingChallenge incoming(UUID player) {
    purgeExpiredChallenges();
    return pendingChallenges.values().stream()
        .filter(value -> value.to.equals(player))
        .findFirst()
        .orElse(null);
  }

  private PendingChallenge outgoing(UUID player) {
    purgeExpiredChallenges();
    return pendingChallenges.values().stream()
        .filter(value -> value.from.equals(player))
        .findFirst()
        .orElse(null);
  }

  private record CatalogLoad(CardCatalog catalog, SqliteCardRepository.CatalogSnapshot snapshot) {}

  private record PendingChallenge(UUID id, UUID from, UUID to, long expiresAt) {}

  private static final class ActiveMatch {
    private MatchState state;
    private final UUID onePlayer;
    private final UUID twoPlayer;
    private final UUID oneOperation;
    private final UUID twoOperation;
    private final boolean bot;

    private ActiveMatch(
        MatchState state,
        UUID onePlayer,
        UUID twoPlayer,
        UUID oneOperation,
        UUID twoOperation,
        boolean bot) {
      this.state = Objects.requireNonNull(state);
      this.onePlayer = onePlayer;
      this.twoPlayer = twoPlayer;
      this.oneOperation = Objects.requireNonNull(oneOperation);
      this.twoOperation = Objects.requireNonNull(twoOperation);
      this.bot = bot;
    }
  }

  public record GiveResult(boolean accepted, String message) {}

  public record ChallengeResult(boolean accepted, String message) {}

  static record BotTurnResult(
      MatchState state, MatchCommand command, ApplyResult result, boolean waiting) {
    static BotTurnResult waiting(MatchState state) {
      return new BotTurnResult(state, null, null, true);
    }

    static BotTurnResult applied(MatchState state, MatchCommand command, ApplyResult result) {
      return new BotTurnResult(state, command, result, false);
    }

    static BotTurnResult rejected(MatchState state, MatchCommand command, ApplyResult result) {
      return new BotTurnResult(state, command, result, false);
    }

    boolean applied() {
      return result != null && result.accepted();
    }
  }
}
