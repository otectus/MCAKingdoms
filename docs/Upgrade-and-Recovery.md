# Upgrade and recovery runbook

Applies to MCA: Kingdoms 0.3.0. Audience: server operators. For capacity problems, see [Capacity and record maintenance](Capacity-and-Maintenance.md). For the Ultima Kingdoms rename, also read the README's [Upgrading from Ultima Kingdoms](../README.md#upgrading-from-ultima-kingdoms).

Never test an upgrade, a failure or a recovery on a player's only copy of a world. Use a copy, and keep the backup.

## Upgrading from 0.2.0

1. **Stop the server and back up** the entire world folder and `config/`. A partial backup is not a rollback: native troops, quests, inventories and political records span several files.
2. **Replace the jar on the server and on every client.** The main network channel is now protocol 7, so a 0.2.0 client cannot join a 0.3.0 server, and the reverse is also refused at login.
3. **Update companions** if you use them. The table in the README's [Compatibility](../README.md#compatibility) section separates the metadata range from what was validated. `tools/test/companions.json` lists the exact jars used for acceptance, with their SHA-256 hashes.
4. **Start the server once, before anyone joins.** If it stops during start-up, see [the refusals below](#when-the-server-refuses-to-start). Then open Server health, or run `/kingdoms health`, and check:
   - the providers and the capabilities that bound, including the **MCA Quests contract** line;
   - saved-data writability;
   - the **Capacity** section;
   - the last definition reload.
5. **Review the new settings.** New keys are added with their defaults the first time 0.3.0 starts; see the [configuration reference](Configuration.md):
   - common: `civicIdentity.playerRegistration` (on) and `civicIdentity.residenceMoveCooldown` (24,000 ticks);
   - common: `politics.politicalNotices` (on) and `politics.officeBadges` (on);
   - client: `politics.notices` (off), `politics.officeBadges` (on) and `politics.officeBadgeDistance` (16).

   With these defaults, players can register their own residence. Notices are still off until each player turns them on.
6. Reopen the server.

### What 0.3.0 writes that 0.2.0 does not

None of these is a store-format migration. Existing files load as they are.

- A recruit transfer offer that nobody acted on now ends in the **expired** phase.
- A residence the player registered is recorded with the source **registered**.
- An applied compaction writes `<world>/mcakingdoms_archive/*.json`.

## Downgrading

**Downgrading in place is not supported.** To go back to 0.2.0, restore the backup taken before the upgrade. Play since then is lost; that is the cost of going back. The reasons:

- 0.2.0 does not know the expired transfer phase, so it cannot interpret a transfer that 0.3.0 expired.
- 0.2.0 reads a registered residence as a migration record, losing how it was verified.
- 0.2.0 lacks the 0.3.0 load refusals described below. A 0.2.0 server could start empty over a settlement file it cannot read and overwrite it at the next save.
- The network protocols differ, so 0.2.0 clients cannot join a 0.3.0 server, and the reverse is also refused.

A store written by a **newer** build than the one running is refused, not trimmed. Run the newer build, or restore a backup made by the older one.

## When the server refuses to start

0.3.0 fails closed. It stops a world from starting when continuing could replace saved data with an empty store.

### The settlement store cannot be read

The log names the problem and ends with *"…refusing to initialize so the original save cannot be overwritten. The file mcakingdoms_settlements.dat is unchanged: restore it from a backup or repair the named record, then start again"*.

Settlements are the identity every other store points at. A file this build cannot read completely is therefore refused as a whole, never trimmed. That includes:

- an unsupported schema;
- a record that does not parse;
- two records with one id;
- a malformed, self-referencing or conflicting redirect;
- a redirect cycle, or one that ends at no settlement;
- two settlements claiming one external reference;
- a truncated or corrupt file.

To recover:

1. Keep the refused file as it is (copy it aside); it is evidence and possibly repairable.
2. If you just downgraded, run the build that wrote the file.
3. Otherwise restore the **whole world** from the newest backup that starts. Restoring `mcakingdoms_settlements.dat` alone from an older backup can leave other stores pointing at settlements the older file does not have.
4. Never delete the file to get the server running. Minecraft would create an empty store, and every reference to a settlement would break.

### Carrying a world over from Ultima Kingdoms failed

The log, and `world/data/mcakingdoms_migration_report.txt`, list each file as COPIED, SKIPPED_DESTINATION_EXISTS or FAILED, with the reason. The world does not start while any saved-data copy, or any copy of a common config file, failed. Nothing has been written in place of the old data, and the old files are unchanged.

To recover:

1. Fix the cause: file permissions, free disk space, or a damaged old file restored from a backup.
2. Start again. Copies that already succeeded are kept and skipped; only the failed ones are retried.
3. Do not delete existing `mcakingdoms_*` files to force a copy; they may hold newer play.

A failed copy of the client config file is reported but does not stop the world.

## When a store is read-only

Other stores refuse to write when their file holds something this build cannot read: politics, campaigns, evolution, transfers, protection, guilds and others. Server health lists each such store under saved-data writability, with the reason. The original payload is kept and saved back unchanged; services that need the store refuse their actions with an explanation. To recover:

1. Run a build that understands the file, or restore the whole world from a coherent backup.
2. Do not overwrite the file with an empty one. Native effects that already happened would lose their records.

## Pending and interrupted operations

A save or provider failure leaves a pending operation instead of reporting success. In Kingdom Tasks, the result says **done**, **waiting**, **refused** or **unknown**:

- **Check this action** reads the owner's current state again. It never sends the action a second time.
- Inspect and cancel tasks stay available while their feature's setting is off. So do verify and restore tasks, though those that act through Recruits still need Recruits installed. Only actions that start something new are unavailable.
- Recruit transfers:
  - An offer nobody acted on expires after its deadline. Ownership and equipment are unchanged, and both parties are told.
  - A transfer that reached Recruits keeps its recovery fence until it is confirmed, restored or reconciled by an operator.
- Never delete evidence to clear an error message.

## Companion problems

The Server health line **MCA Quests contract** reports how receipts are read and whether paid workshop jobs can complete:

| Value | Meaning | What to do |
| --- | --- | --- |
| `available` | The installed MCA Quests names an `mcakingdoms` institutional consumer and has the commission methods. | Nothing. |
| `legacy_consumer_contract` | MCA Quests has the methods but no `institutionalCompletionConsumer()`. This is a build from before the rename handoff. | Install a Quests build that carries the contract (see `tools/test/companions.json`). Paid workshop jobs are refused before any offer; ordinary guild deeds still work. |
| `foreign_consumer_namespace` | Quests names another mod's consumer. | Same as above. |
| `missing_institutional_methods` | The commission methods are absent. | Same as above. |
| `unsupported_completion_api` | The installed MCA Quests has no receipt API this build can bind, so no guild service credit arrives either. | Install a supported MCA Quests version. |
| `absent` / `not_probed` | MCA Quests is not installed, or not yet checked. | Install it if you want guild jobs. |

MCA: Capitals installed alongside turns native Kingdoms governance off; Server health says so.

## Old names: runtime aliases and on-disk migration

These are two different things with different futures.

**On-disk migration** copies a world and its configs from Ultima Kingdoms. It covers `ultima_kingdoms_*.dat` and the old config file names, done once per file and never touching the old file. It stays supported. It is not removed because a release has passed; a world can be upgraded from Ultima Kingdoms by any later version that keeps this support.

**Runtime aliases** let current play read old names:

- `ultima_kingdoms:` settlement slugs resolve;
- the two items saved under the old registry names load as the renamed items;
- a villager's or player's old civic-identity key moves on first read;
- the old welcome flag is honoured.

The companions also accept the old mod id. Removing these aliases is a separate, coordinated release step. It waits until the supported companion builds (see `tools/test/companions.json`; MCA Quests and MCA Reputation are not yet published) are released and upgrade tests pass with them. 0.3.0 keeps every alias.

Advancement, recipe-book and statistics progress recorded under `ultima_kingdoms:` is not carried over. This is a cosmetic limitation: the welcome advancement can appear once more, and the two recipes unlock again when their items are held.
