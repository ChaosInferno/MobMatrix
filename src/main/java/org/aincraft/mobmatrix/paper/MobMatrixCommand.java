package org.aincraft.mobmatrix.paper;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.aincraft.mobmatrix.MobMatrixPlugin;
import org.aincraft.mobmatrix.domain.CardCatalog;
import org.aincraft.mobmatrix.domain.CardCopyId;
import org.aincraft.mobmatrix.domain.CardDefinition;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** The intentionally small public command surface for the playable Paper slice. */
public final class MobMatrixCommand implements CommandExecutor, TabCompleter, BasicCommand {
  private static final List<String> COMMANDS =
      List.of("give", "deck", "challenge", "bot", "accept", "decline", "cancel");

  private final MobMatrixPlugin plugin;

  public MobMatrixCommand(MobMatrixPlugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin is required");
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return dispatch(sender, args);
  }

  @Override
  public void execute(CommandSourceStack source, String[] args) {
    dispatch(source.getSender(), args);
  }

  @Override
  public Collection<String> suggest(CommandSourceStack source, String[] args) {
    return complete(source.getSender(), args);
  }

  @Override
  public String permission() {
    return "mobmatrix.use";
  }

  private boolean dispatch(CommandSender sender, String[] args) {
    if (args == null || args.length == 0) {
      sender.sendMessage("/mobmatrix <give|deck|challenge|bot|accept|decline|cancel>");
      return true;
    }
    String subcommand = args[0].toLowerCase(Locale.ROOT);
    return switch (subcommand) {
      case "give" -> give(sender, args);
      case "deck" -> deck(sender, args);
      case "challenge" -> challenge(sender, args);
      case "bot" -> bot(sender, args);
      case "accept" -> transition(sender, args, Transition.ACCEPT);
      case "decline" -> transition(sender, args, Transition.DECLINE);
      case "cancel" -> transition(sender, args, Transition.CANCEL);
      default -> {
        sender.sendMessage("Unknown MobMatrix command. Use /mobmatrix.");
        yield true;
      }
    };
  }

  private boolean give(CommandSender sender, String[] args) {
    if (args.length < 3 || args.length > 5) {
      sender.sendMessage("Usage: /mobmatrix give <player> <No./Ex. number> [amount]");
      return true;
    }
    Player target = Bukkit.getPlayerExact(args[1]);
    if (target == null) {
      sender.sendMessage("That player must be online.");
      return true;
    }
    String label = args[2];
    int amountIndex = 3;
    if ((args[2].equalsIgnoreCase("no.") || args[2].equalsIgnoreCase("ex.")) && args.length >= 4) {
      label = args[2] + " " + args[3];
      amountIndex = 4;
    }
    Optional<CardDefinition> definition = lookupPublicNumber(plugin.catalog(), label);
    if (definition.isEmpty()) {
      sender.sendMessage("Unknown or ambiguous public card number: " + label);
      return true;
    }
    int amount;
    try {
      amount = args.length > amountIndex ? Integer.parseInt(args[amountIndex]) : 1;
    } catch (NumberFormatException exception) {
      sender.sendMessage("Amount must be a positive whole number.");
      return true;
    }
    Validation validation = validateAmount(amount, plugin.maxGiveAmount());
    if (!validation.accepted()) {
      sender.sendMessage(validation.message());
      return true;
    }
    MobMatrixPlugin.GiveResult result = plugin.give(target, definition.orElseThrow(), amount);
    sender.sendMessage(result.message());
    return true;
  }

  private boolean deck(CommandSender sender, String[] args) {
    if (args.length != 1) {
      sender.sendMessage("Usage: /mobmatrix deck");
      return true;
    }
    Player player = playerSender(sender);
    if (player == null) return true;
    plugin.openDeck(player);
    return true;
  }

  private boolean challenge(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /mobmatrix challenge <player>");
      return true;
    }
    Player player = playerSender(sender);
    if (player == null) return true;
    Player target = Bukkit.getPlayerExact(args[1]);
    ChallengeValidation validation =
        validateChallenge(
            player.getUniqueId(),
            target == null ? null : target.getUniqueId(),
            target != null,
            plugin.isBusy(player.getUniqueId()),
            target != null && plugin.isBusy(target.getUniqueId()),
            plugin.hasChallengeFrom(player.getUniqueId()),
            plugin.hasChallengeTo(player.getUniqueId()));
    if (!validation.accepted()) {
      sender.sendMessage(validation.message());
      return true;
    }
    sender.sendMessage(plugin.challenge(player, target).message());
    return true;
  }

  private boolean bot(CommandSender sender, String[] args) {
    if (args.length != 1) {
      sender.sendMessage("Usage: /mobmatrix bot");
      return true;
    }
    Player player = playerSender(sender);
    if (player == null) return true;
    sender.sendMessage(plugin.startBot(player).message());
    return true;
  }

  private boolean transition(CommandSender sender, String[] args, Transition transition) {
    if (args.length != 1) {
      sender.sendMessage("Usage: /mobmatrix " + transition.name().toLowerCase(Locale.ROOT));
      return true;
    }
    Player player = playerSender(sender);
    if (player == null) return true;
    MobMatrixPlugin.ChallengeResult result =
        switch (transition) {
          case ACCEPT -> plugin.accept(player);
          case DECLINE -> plugin.decline(player);
          case CANCEL -> plugin.cancel(player);
        };
    sender.sendMessage(result.message());
    return true;
  }

  private Player playerSender(CommandSender sender) {
    if (sender instanceof Player player) return player;
    sender.sendMessage("This command can only be used by a player.");
    return null;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    return complete(sender, args);
  }

  private List<String> complete(CommandSender sender, String[] args) {
    if (args == null || args.length == 0) return COMMANDS;
    if (args.length == 1) {
      String prefix = args[0].toLowerCase(Locale.ROOT);
      return COMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }
    if (args.length == 2
        && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("challenge"))) {
      String prefix = args[1].toLowerCase(Locale.ROOT);
      return Bukkit.getOnlinePlayers().stream()
          .map(Player::getName)
          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
          .sorted()
          .toList();
    }
    if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
      String prefix = args[2].toLowerCase(Locale.ROOT);
      return plugin.catalog().all().stream()
          .map(CardDefinition::publicNumber)
          .filter(number -> number.toLowerCase(Locale.ROOT).startsWith(prefix))
          .toList();
    }
    return List.of();
  }

  /** Resolves exact No./Ex. labels, accepting a bare positive number as No. N. */
  public static Optional<CardDefinition> lookupPublicNumber(CardCatalog catalog, String label) {
    Objects.requireNonNull(catalog, "catalog is required");
    if (label == null || label.isBlank()) return Optional.empty();
    String candidate = label.trim();
    if (candidate.chars().allMatch(Character::isDigit)) candidate = "No. " + candidate;
    String normalized = candidate.toLowerCase(Locale.ROOT);
    List<CardDefinition> matches =
        catalog.all().stream()
            .filter(card -> card.publicNumber().trim().toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  /** Compatibility alias used by command-validation tests. */
  public static Optional<CardDefinition> lookupCard(CardCatalog catalog, String label) {
    return lookupPublicNumber(catalog, label);
  }

  public static Validation validateAmount(int amount, int maximum) {
    if (amount <= 0) return Validation.rejected("Amount must be positive.");
    if (maximum <= 0 || amount > maximum) {
      return Validation.rejected("Amount must be between 1 and " + Math.max(1, maximum) + ".");
    }
    return Validation.ok();
  }

  public static Validation validateDeck(Collection<CardCopyId> copyIds) {
    if (copyIds == null || copyIds.size() != 5) {
      return Validation.rejected("Select exactly five physical cards first.");
    }
    long distinct = copyIds.stream().filter(Objects::nonNull).distinct().count();
    return distinct == 5
        ? Validation.ok()
        : Validation.rejected("A deck cannot contain duplicate physical cards.");
  }

  public static ChallengeValidation validateChallenge(
      UUID sender,
      UUID target,
      boolean online,
      boolean senderBusy,
      boolean targetBusy,
      boolean senderHasIncoming,
      boolean senderHasOutgoing) {
    if (sender == null)
      return ChallengeValidation.rejected("This command can only be used by a player.");
    if (!online || target == null)
      return ChallengeValidation.rejected("That player must be online.");
    if (sender.equals(target))
      return ChallengeValidation.rejected("You cannot challenge yourself.");
    if (senderBusy || targetBusy)
      return ChallengeValidation.rejected("Both players must be free to challenge.");
    if (senderHasIncoming || senderHasOutgoing) {
      return ChallengeValidation.rejected("You already have a pending challenge.");
    }
    return ChallengeValidation.ok();
  }

  public record Validation(boolean accepted, String message) {
    public static Validation ok() {
      return new Validation(true, "");
    }

    public static Validation rejected(String message) {
      return new Validation(false, message);
    }
  }

  public record ChallengeValidation(boolean accepted, String message) {
    public static ChallengeValidation ok() {
      return new ChallengeValidation(true, "");
    }

    public static ChallengeValidation rejected(String message) {
      return new ChallengeValidation(false, message);
    }
  }

  private enum Transition {
    ACCEPT,
    DECLINE,
    CANCEL
  }
}
