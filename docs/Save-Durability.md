# Save durability

This page states what MCA: Kingdoms guarantees about its saved data, store by store, and what an operator does when a
guarantee cannot be met. It covers the 0.3.0 unification (audit item H06). The code is
`persistence/AtomicSavedDataWriter` and `persistence/DurableSavedData`; the tests are `AtomicSavedDataWriterTest`
(unit) and `SettlementLoadRefusalGameTests` (GameTests).

## One write policy

Every store file under `<world>/data/` is written the same way:

1. The new content is staged in a temporary file in the same folder and forced to disk.
2. The temporary file replaces the store with an **atomic move**. The folder is forced to disk where the platform allows.
3. Before a store is first replaced in a server session, the file that session loaded is copied to
   `<store>.dat.bak` (itself staged and moved atomically). The backup is the last generation known to have loaded.

There is no non-atomic fallback. Before 0.3.0, faction data fell back to an ordinary replacement when the filesystem
refused an atomic move. That fallback could leave a truncated file after a crash, so it was removed.

When a write fails:

- The previous store file is unchanged and no staged file is left behind.
- A change that is committed as it happens (a political decision, a campaign step, a deployment, a transfer) is
  **refused**. The player is told the change could not be saved, and nothing changed.
- A periodic save stays **dirty** and is retried at the next save. Vanilla Minecraft marks a store clean even when its
  write failed; these stores do not.
- Server health (Administration → Server health) lists the store and the cause.

## Unreadable store files

Minecraft starts a store empty when its file exists but cannot be decoded, for example after a crash truncated it, and
only logs an error. MCA: Kingdoms handles that case in two ways:

- **Settlements** (`mcakingdoms_settlements.dat`) refuse to load. The server stops before anything else reads them,
  because every other store points at settlement ids (F02).
- **Every other store** is protected: the first write of the session finds the existing file undecodable, refuses it,
  and refuses every later write for the session. The damaged file stays exactly as found and the `.bak` from the last
  session that loaded is kept. The store's changes are refused rather than written over the evidence. Server health
  shows a red line naming the store and the backup to restore.

## Stores

| Store file (`<world>/data/`) | Contents | How changes are committed | Unknown or future schema |
| --- | --- | --- | --- |
| `mcakingdoms_settlements.dat` | Settlement identity, aliases, redirects | Periodic save | Refused; the server stops (F02) |
| `mcakingdoms_knowledge.dat` | Which players know which settlements | Periodic save | Kept read-only, unchanged |
| `mcakingdoms_politics.dat` | Governments, offices, agreements, petitions, elections, history | Each decision written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_factions.dat` | Kingdom standing, receipts and cursors from companion events | Periodic save; companion receipts are acknowledged only after a save succeeds | Kept read-only, unchanged |
| `mcakingdoms_organizations.dat` | Guilds, ranks, memberships, deeds | Each operation written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_civic_network.dat`, `mcakingdoms_institutional_commissions.dat` | Civic contacts, commissions | Each operation written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_quest_receipts.dat` | MCA Quests effect intents | Each intent written before it is acted on | Kept read-only, unchanged |
| `mcakingdoms_campaigns.dat`, `mcakingdoms_control.dat` | Campaigns, accords, Recruits claim bindings | Each step written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_civilian_contracts.dat` | Civilian contracts | Each step written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_mobilization.dat` | Temporary deployments of recruits | The deployment is written before Recruits' orders change | Kept read-only, unchanged |
| `mcakingdoms_world_context.dat` | Discovered sites and routes | Each change written before it is acknowledged | Kept read-only, unchanged |
| `mcakingdoms_evolution.dat`, `mcakingdoms_evolution_drama.dat`, `mcakingdoms_protection.dat` | Opportunities, world events, protectorates | Each change written before it is announced (F09) | Kept read-only, unchanged |
| `mcakingdoms_recruit_transfers.dat` | Negotiated recruit transfers | The intent is written before Recruits changes, and the outcome after Recruits' save is verified | Kept read-only, unchanged |
| `mcakingdoms_capitals.dat` | MCA Capitals mappings (0.3.0) | Each mapping decision written before it takes effect | Kept read-only, unchanged |
| `mcakingdoms_documents.dat` | Sealed documents and chosen heraldic colours (0.3.0) | A document is written before its charter is sealed | Kept read-only, unchanged |
| `mcakingdoms_dynasty.dat` | Laws, succession cases, claims, titles, mourning, memorials (0.3.0) | Each decision written before its notices and political step | Kept read-only, unchanged |
| `mcakingdoms_development.dat` | Public works, construction journals, settlement development (0.3.0; only once `development.mode` is above `OFF`) | Each decision written before it takes effect; deliveries handed back if the write fails; each built block journaled | Kept read-only, unchanged |

Files written once, outside the stores: the legacy-migration report (`mcakingdoms_migration_report.txt`, informational)
and archives under `<world>/mcakingdoms_archive/` (written as new files, never replaced).
The client keeps its own preferences in `config/mcakingdoms-client-state.json`, written with the same staged atomic move.

## Restoring a backup

Restore only with the server **stopped**:

1. Copy the whole world folder somewhere safe first.
2. In `<world>/data/`, rename the damaged `<store>.dat` (for example to `<store>.dat.damaged`).
3. Copy `<store>.dat.bak` to `<store>.dat`.
4. Start the server and open Server health. The store should read **writable**.

The backup holds the store as it was when the last session first changed it. Changes made after that are not in it.
Other stores are not rolled back with it, and their references are checked when they load.

## Cross-mod effects

Writes that involve another mod (a Recruits transfer, an MCA Quests receipt, an MCA Reputation standing change) are not
made atomic across mods. Kingdoms writes its own intent first, asks the other mod, verifies the other mod's saved result
where that mod allows it, and only then records the outcome. After a crash, the saved intent is reconciled at start-up
or through the matching Check, Confirm or Restore task. See [Upgrade and recovery](Upgrade-and-Recovery.md).
