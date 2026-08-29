# MobMatrix Design

> **Status:** Approved for implementation on 2026-08-29. The first implementation is deliberately limited to direct two-player and bot matches; the deferred FFXIV modes remain outside the current delivery contract.

## Goal

Build a Paper plugin that reproduces the playable FFXIV Triple Triad board game for two Minecraft players or one player against a bot, using the current 475-card catalog, vanilla `PAPER` card items, and `aincraft-org/MapGui` for the board.

## Current scope

### Included

- Direct player-versus-player challenges on one Paper server.
- Player-versus-bot matches using the same authoritative rules engine.
- The current card snapshot: public No. 1–460 and Ex. 1–15, 475 records total.
- Card name, public number, rarity, type, sale value, and four printed side values.
- Vanilla `Material.PAPER` card copies in player inventories.
- MapGui popup board with hidden-information projections, hand selection, legal board-cell selection, rule display, timers, and result display.
- Basic rules and all board-level advanced rules: All Open, Three Open, Same, Plus, Combo, Sudden Death, Random, Order, Chaos, Reverse, Fallen Ace, Ascension, Descension, Swap, Draft, and Roulette.
- SQLite-backed active-match and physical-card reservation state sufficient to recover or return reserved cards after a plugin restart.

### Deferred

Battlehall instances, queue matchmaking, regional rule rotation, NPC roster/content, recurring tournaments, Open and Invitational short tournaments, tournament standings, tournament rewards, MGP/economy integration, and FFXIV quest progression are not part of this delivery. The core domain must not invent partial versions of those modes.

## Source and data policy

The official [FFXIV Triple Triad guide](https://na.finalfantasyxiv.com/lodestone/playguide/contentsguide/goldsaucer/tripletriad/) and official patch notes are the authority for game rules. The current catalog snapshot is generated from the [FFXIV Collect card API](https://ffxivcollect.com/api/triad/cards), cross-checked against [XIVAPI](https://v2.xivapi.com/docs/welcome/). The source API currently reports 475 records and provides an explicit public `number` field; internal record IDs and public numbers are stored separately.

The repository stores only the factual fields needed by the game. It does not bundle FFXIV card artwork, icons, URLs, or descriptive prose. The project attributes FFXIV Collect, XIVAPI, and Square Enix in the repository's data-source notice. FFXIV Collect's API acceptable-use terms are non-commercial; the initial distribution remains non-commercial unless a rights-compatible data source or permission is obtained.

A reviewed, versioned text-only snapshot is loaded locally at runtime. Server startup never depends on a network request. A later refresh tool fetches a candidate snapshot, validates count/IDs/numbers/stats/rarities/types, reports deltas, and promotes it atomically only after review.

## Architecture

Use one deployable plugin with strict package boundaries rather than premature Gradle subprojects:

- `domain`: pure Java. Card definitions, physical-copy references, board, hands, rules, clocks, random transcript, commands, events, projections, scoring, deck validation, and bot move policy. No Bukkit, Adventure, MapGui, JDBC, or filesystem imports.
- `application`: pure Java use cases and ports. Coordinates match commands, catalog lookup, card custody, persistence, clocks, and notifications. Ports exchange UUIDs and value records, never Bukkit objects.
- `paper`: plugin bootstrap, commands, listeners, player/entity lookup, scheduler boundaries, permissions, and lifecycle recovery.
- `inventory`: creation and validation of `PAPER` items, synchronous reservation/return operations, and inventory-event protection.
- `mapgui`: MapGui screens that render application projections and submit commands. Screens own cursor/selection state only.
- `storage`: SQLite schema/migrations and repositories. JDBC work runs away from the Paper primary thread.
- `bot`: a deterministic legal-move policy that receives a domain projection and emits ordinary domain commands. It owns no special-rule logic.

The application service is the only path that mutates a match. Closing a MapGui screen, changing a hotbar slot, disconnecting, or an inventory event never directly settles a match.

## Card and custody model

Every owned card is one physical, non-stackable `Material.PAPER` item. Its PDC contains:

- plugin schema version;
- immutable internal catalog card ID;
- immutable physical copy UUID;
- server-issued integrity marker.

The item display name and lore include the card name, explicit public number, stars, type, and four printed values. Display text is never parsed for identity.

A player's deck is five selected physical copy UUIDs. At match start the application creates a prepared reservation containing the exact serialized item snapshots, the primary-thread inventory adapter revalidates and removes exactly those five copies, and the application marks the reservation escrowed before the match becomes active. The selected copies are not consumed. The prepared reservation is the durable journal boundary: if the process stops between database and inventory steps, startup reconciles the journal by copy UUID and either completes escrow or restores the serialized item snapshots. At final settlement, the adapter returns exact copies; if the player is offline or the inventory has no room, the serialized snapshots remain in a durable recovery record for the next safe return. A failed preparation never starts a match.

The initial server trust model treats the custody ledger as authoritative and rejects a duplicated copy UUID while reserved. It does not claim to defeat a malicious administrator or a plugin that clones valid NBT; that requires a separately defined anti-duplication policy.

Bot cards are server-owned temporary copies. Draft offerings are house-owned temporary cards. Swap changes match-local ownership and always restores each physical copy to its original owner at settlement.

## Match contract

A match aggregate stores:

- match ID and revision;
- both seats and actor UUIDs (or the bot seat);
- five card-copy references per seat, with unavailable/played state;
- the nine board squares, card IDs, and current owner;
- immutable catalog snapshot ID;
- active rule set and all resolved random decisions;
- turn, placement count, and both clocks;
- Sudden Death continuation count;
- settlement status and reservation IDs.

Every command includes actor, match ID, expected revision, and idempotency key. Stale or duplicate MapGui clicks become structured rejections. Accepted transitions return events and a new projection; Paper adapters schedule the projection update only after persistence succeeds.

The normal match lifecycle is challenge → both deck reservations → active play → result or disconnect policy → card settlement → closed. For direct PvP, a disconnect during an active game receives the same persisted grace/automatic-play policy as a timed-out turn; the policy is explicit in configuration. A plugin restart reloads active rows and executes overdue deadlines once through idempotent timeout commands.

## Rules

### Basic play

The board is a 3×3 row-major grid. Each seat has five cards, so the unplayed tenth card is counted at scoring. The first seat is random. A turn places one remaining card on one empty square. An adjacent opposing card is captured when the placed facing value is strictly higher; equality does not capture. The winner controls more of the ten total cards; equal control is a draw.

Each seat has a 90-second deliberation bank and a separate five-second non-bankable thinking window at the start of every turn. The bank never receives unused grace time. When the bank is empty, failure to act inside the grace window produces a deterministic automatic legal move.

### Advanced rules

- **All Open:** reveal both complete hands.
- **Three Open:** reveal three randomly selected cards from each hand; the selection is recorded.
- **Same:** if at least two adjacent facing comparisons are equal, capture every matching opposing adjacent card. Friendly matching cards count toward the trigger but are never flipped.
- **Plus:** if at least two adjacent facing-pair sums are equal, capture every matching opposing adjacent card. Friendly cards count toward the trigger but are never flipped.
- **Combo:** only cards flipped by Same or Plus seed a breadth-first chain of ordinary captures. Combo does not recursively trigger Same or Plus.
- **Sudden Death:** a draw restarts from turn one using the board ownership at the prior result; after five additional consecutive drawn games the overall result is a draw.
- **Random:** replace the selected player's deck with five randomly selected available physical copies from that player's inventory; the selected copies are reserved before the match starts and the unused originals remain in inventory. This is the physical-copy adaptation of FFXIV's registered-card-list rule.
- **Order:** the deck's fixed order determines the next playable card.
- **Chaos:** the next playable card is randomly selected from the remaining hand.
- **Reverse:** the ordinary comparison operator is inverted so lower values capture higher values.
- **Fallen Ace:** normal comparison permits 1 to capture A/10; under Reverse, A/10 captures 1 as the special relationship.
- **Ascension:** typed cards use the number of existing same-type cards already in play as the placement-time positive modifier; effective values cap at A/10. The placed card is then included in the board's typed state for subsequent comparisons.
- **Descension:** typed cards use the number of existing same-type cards already in play as the placement-time negative modifier; effective values floor at 1. The placed card is then included in the board's typed state for subsequent comparisons.
- **Swap:** one random physical card from each player is exchanged before play and returned to its original owner after settlement; rarity does not constrain the exchange.
- **Draft:** each seat receives three generated five-card offering sets, chooses one card from each set to form a temporary five-card deck, and uses no player-owned cards for that match.
- **Roulette:** resolve a random compatible advanced-rule set before the first turn.

The placement resolver evaluates Same/Plus from one adjacent snapshot, unions their opposing targets, applies ordinary captures, and then performs Combo from the special-rule targets. Equality and sums are unaffected by Reverse; Reverse only changes ordinary/Fallen-Ace comparison. Rule incompatibilities are rejected before a match starts. Every RNG draw is seeded and recorded so a match can be replayed.

The Lodestone guide leaves some precedence details unspecified. The above ordering and Ascension/Descension timing are the explicit initial MobMatrix policy, isolated in the domain resolver and covered by executable vectors so the policy can change without touching Paper or MapGui code.

## Bot

The initial bot is a real deterministic heuristic, not a placeholder: enumerate legal card/square pairs, simulate each through the domain engine, score resulting control differential, immediate captures, corner/edge occupancy, and future mobility, then choose the highest score with the seeded RNG as the tie-breaker. It uses the same hand visibility and rule constraints as a player. Difficulty levels and FFXIV NPC personalities are deferred.

## Paper and MapGui UX

The first player path is:

1. An administrator issues physical cards for testing or an acquisition adapter supplies them.
2. The player selects five exact Paper copies in a deck screen.
3. `/mobmatrix challenge <player>` sends a direct challenge; `/mobmatrix bot` starts a bot match.
4. Both players accept and reserve cards.
5. MapGui opens a focused popup board. The local hand is shown; the player selects a legal card and then a legal empty cell. Right-click is the default activation gesture.
6. The screen renders rule notifications, captures, remaining hand cards, clocks, and the result. Hidden opponent cards remain masked unless an Open rule reveals them.

MapGui is used as an ephemeral projection/input surface. It is declared as a required runtime dependency, compiled as `io.github.flog99:mapgui-api:1.1.0` with `compileOnly`, and loaded before MobMatrix with `join-classpath: true`. The plugin does not shade MapGui and does not provide a second UI that can diverge from the board state.

## Persistence and threading

SQLite stores catalog snapshot metadata, card-copy custody, deck selections, active match snapshots/events, random transcript, reservations, serialized escrow item snapshots, and recovery records. Database state transitions are transactional and schema migrations are explicit and tested. Live inventory mutation is a separate primary-thread step guarded by the prepared journal; startup reconciliation makes the two-step operation recoverable rather than pretending a JDBC transaction can include Bukkit inventory state.

Paper events, PDC access, inventory scans/mutations, player messaging, and MapGui calls run on the primary thread. JDBC, catalog refresh, and serialization work run asynchronously. Async work returns immutable value results to a scheduled primary-thread continuation before touching Bukkit objects. Inventory click/drag/number-key/swap/drop/death/quit paths are blocked or reconciled while copies are escrowed.

## Verification contract

The domain test suite must exercise observable behavior, not implementation text:

- standard capture, equality, scoring, and unplayed-card counting;
- every advanced rule, including Reverse/Fallen Ace, Same+Plus union, Combo boundaries, typed-value timing, Swap restoration, Draft, Roulette compatibility, and Sudden Death's five-draw limit;
- hidden-information projections for each Open rule;
- deck rarity legality and duplicate physical-copy selection;
- deterministic RNG replay and stale/idempotent command rejection;
- 90-second bank plus five-second grace boundary and forced legal move;
- bot legal moves and completion under every supported rule profile;
- SQLite reservation/match recovery and exact-copy return paths.

The first Paper smoke scenario issues ten cards, starts a bot match, makes placements through the MapGui screen, completes the match, and verifies that the exact five reserved player copies are returned. The canonical project gate is `./gradlew clean check`; a real Paper `runServer` smoke is required for the UI stage.

## Deferred expansion boundary

The domain/application contracts may later add Battlehall, NPC, tournament, and regional-rule services, but the current plugin must not expose commands or configuration claiming those features. Those additions will receive separate design/implementation slices with their own persistence, pairing, reward, and recovery contracts.
