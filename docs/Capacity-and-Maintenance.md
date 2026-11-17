# Capacity and record maintenance

Applies to MCA: Kingdoms 0.3.1. Audience: server operators.

Every saved-data store has defensive bounds. They keep save files, network replies and server ticks bounded no matter how long a world runs.

**A record that is still in use is never deleted to make room.** In use means open (a work in progress, an unfinished transfer), still owed (a refund not yet claimed), still being confirmed (a delivery waiting for the next save), or referred to by another record. Some collections drop their oldest *finished* records automatically once nothing depends on them; that is **retention**. When only records in use remain, the owning service **refuses new records of that kind**, with a message. Everything else keeps working: existing records stay readable, other systems keep running, and saves are unaffected.

This page covers:

- reading how full each collection is;
- what relieves each one;
- history, lasting effects and replay evidence;
- the two supported compactions;
- the offline procedure for collections that this version cannot safely compact.

## Reading capacity

- **Server health** (Kingdom Tasks, operators) and `/kingdoms health` have a **Capacity** section. It lists every collection at 80% or more, fullest first: near capacity in gold, full in red. When nothing is near, it names the fullest collection and its percentage.
- `/kingdoms maintenance capacity` (permission level 2, also from the console) lists **every** bounded collection: store, collection, used, bound, percentage, level and relief. It reads the stores and writes nothing.

"Near capacity" means 80% or more of the bound. "Full" means at the bound, where new records of that kind are refused.

## Inventory

The store is the file under `world/data/` (`<store>.dat`). The **Relief** column says what frees the collection:

- **Retention**: the oldest finished records go automatically once nothing depends on them. Records in use never go, so a full collection of records in use still refuses new ones. The Why column says what counts as finished.
- **Compaction**: an operator can archive finished records with `/kingdoms maintenance compact …`.
- **Kept as evidence**: the bound is the guarantee; these records stop a receipt, proof or cause from being used twice.
- **Offline procedure**: refused at the bound. See [the offline procedure](#offline-procedure).

| Store | Collection | Bound | Relief | Why the records are kept |
| --- | --- | ---: | --- | --- |
| `mcakingdoms_recruit_transfers` | transfers | 1,024 | Compaction (`transfers`) | An unfinished transfer is the recovery fence for a recruit whose native state may have changed. |
| `mcakingdoms_evolution` | opportunities | 4,096 | Compaction (`opportunities`) | Open opportunities hold contributions and service; finished ones are history. |
| `mcakingdoms_evolution` | cooldowns | 4,096 | Compaction (`opportunities`) | Lapsed cooldowns no longer decide anything. |
| `mcakingdoms_evolution` | digest schedule | 4,096 | Compaction (`opportunities`) | Only subscribed players need a digest schedule. |
| `mcakingdoms_evolution` | used evidence | 65,536 | Kept as evidence | Stops a receipt or cause from counting twice. |
| `mcakingdoms_politics` | governments | 128 | Offline procedure | One per governed kingdom; offices, mandates and history point at it. |
| `mcakingdoms_politics` | agreements | 4,096 | Offline procedure | Clauses, civilian effects and history refer to them. |
| `mcakingdoms_politics` | petitions | 4,096 | Offline procedure | Honors and history refer to them. |
| `mcakingdoms_politics` | institution recognitions | 4,096 | Offline procedure | Workshop commissions and services depend on recognition. |
| `mcakingdoms_politics` | honors | 8,192 | Offline procedure | Personal records that players and history refer to. |
| `mcakingdoms_politics` | elections | 512 | Offline procedure | Elected mandates keep their election as provenance. |
| `mcakingdoms_politics` | request receipts | 8,192 | Retention | Expire after their retention period. |
| `mcakingdoms_politics` | history | 32,768 | Retention | Each kingdom keeps its latest 256 facts; older ones are dropped. |
| `mcakingdoms_campaigns` | campaigns | 4,096 | Offline procedure | Control history, claims and recovery refer to them. |
| `mcakingdoms_campaigns` | control agreements | 4,096 | Offline procedure | Accord clauses and control history refer to them. |
| `mcakingdoms_campaigns` | settlement decisions | 4,096 | Offline procedure | Decide current control. |
| `mcakingdoms_campaigns` | request receipts | 16,384 | Offline procedure | Make retried native requests idempotent. |
| `mcakingdoms_campaigns` | history | 4,096 | Retention | The oldest entry is dropped first. |
| `mcakingdoms_evolution_drama` | world events | 256 | Offline procedure | Proposals, consents and outcomes of world events. |
| `mcakingdoms_evolution_drama` | request receipts | 1,024 | Offline procedure | Make a repeated proposal return the recorded one. |
| `mcakingdoms_protection` | protection pacts | 1,024 | Offline procedure | Obligations and service refer to them. |
| `mcakingdoms_protection` | pact obligations | 8,192 | Offline procedure | Record owed and delivered duties. |
| `mcakingdoms_protection` | used receipts | 8,192 | Kept as evidence | Stops a receipt from counting twice. |
| `mcakingdoms_civic_network` | guild chapters | 4,096 | Offline procedure | Contacts, services and introductions refer to them. |
| `mcakingdoms_civic_network` | chapter contacts | 16,384 | Offline procedure | Appointed contacts and their services. |
| `mcakingdoms_civic_network` | authored roles | 16,384 | Offline procedure | Content-authored contact roles. |
| `mcakingdoms_civic_network` | dismissed contacts | 16,384 | Kept as evidence | Stops a dismissed contact from being re-seeded. |
| `mcakingdoms_civic_network` | chapter introductions | 100,000 | Kept as evidence | Make an introduction once-only per player and guild. |
| `mcakingdoms_institutional_commissions` | commission contracts | 16,384 | Offline procedure | Payment, provider receipts and refusal state. |
| `mcakingdoms_documents` | documents | 8,192 | Offline procedure | Charters in players' hands name these records. |
| `mcakingdoms_documents` | receipts | 4,096 | Retention | Oldest go first. Every confirmed task or command makes a new request id, so no shipped path repeats an old one; a receipt answers an exact repeat with the first result. |
| `mcakingdoms_dynasty` | succession cases | 2,048 | Offline procedure | Closed cases beyond the bound are dropped oldest first; open cases never. |
| `mcakingdoms_dynasty` | claims | 4,096 | Offline procedure | Decisions are kept as the record of each claim. |
| `mcakingdoms_dynasty` | titles | 4,096 | Offline procedure | Title charters name these records. |
| `mcakingdoms_dynasty` | receipts | 4,096 | Retention | As for documents. |
| `mcakingdoms_development` | projects | 512 | Retention | The display history of works. A finished work beyond the bound goes oldest first, but only once its effects are kept elsewhere: a completed work's productive asset is recorded; a cancelled work owes no refund and has no delivery being confirmed. Open works never go; if nothing can go, a new work is refused. |
| `mcakingdoms_development` | productive assets | 8,192 | Offline procedure | What each completed work produces and earned (points, role, output), kept apart from the history. A work that would complete past the bound waits, with the reason shown. |
| `mcakingdoms_development` | refunds | 4,096 | Offline procedure | Owed to contributors of cancelled works until claimed. A cancellation that would owe more than the bound is refused. |
| `mcakingdoms_development` | inventory operations | 1,024 | Retention | Deliveries and refund claims. Settled ones go oldest first once half the bound is used; one still waiting for its save never goes. |
| `mcakingdoms_development` | receipts | 4,096 | Retention | Oldest go first. Each dropped receipt raises a watermark, and a request issued at or before it is refused as expired instead of being applied again. |
| `mcakingdoms_knowledge` | discoveries | 100,000 | Offline procedure | Each player's private settlement knowledge. |
| `mcakingdoms_knowledge` | visits | 100,000 | Offline procedure | First-visit titles already shown. |
| `mcakingdoms_waystones` | named waystones | 16,384 | Retention | One entry per waystone the integration named or is waiting for, and per waystone a player renamed. Settled entries (confirmed, expired, unresolved) go oldest first, then players' renames; a waystone keeps its current name either way, and a clash is checked against the current names of registered waystones. A name just given and not yet confirmed never goes. |
| `mcakingdoms_waystones` | waystones waiting for a settlement | 256 | Retention | Unnamed stones placed where a settlement may soon be recognized. Each waits at most `waystones.pendingTicks`; after that, or at once when the list is full, it is left as Waystones leaves it. |
| `mcakingdoms_townstead` | decisions | 4,096 | Offline procedure | An operator's decision about who governs a settlement's local government. |
| `mcakingdoms_townstead` | baselines | 8,192 | Retention | First sight of each settlement with Townstead, used to notice a restored Townstead backup. Baselines of settlements that no longer exist (merged into another) are dropped at server start. |
| `mcakingdoms_living_villages` | village bindings | 4,096 | Offline procedure | One per Living Villages village; settlements, conflicts and residents refer to them. |
| `mcakingdoms_living_villages` | realms | 4,096 | Offline procedure | Realm ids outlive capital changes; agreements, associations and history refer to them. |
| `mcakingdoms_living_villages` | conflicts | 2,048 | Retention | Finished conflicts beyond the bound are dropped oldest first; open ones never. |
| `mcakingdoms_living_villages` | operation journal | 4,096 | Retention | Published or abandoned operations beyond the bound are dropped oldest first; open ones never. |
| `mcakingdoms_living_villages` | treaties | 1,024 | Kept as evidence | Agreements between realms and their signatures; a full collection refuses new proposals. |
| `mcakingdoms_living_villages` | residents | 16,384 | Retention | Confirmed deaths beyond the bound are dropped oldest first; living residents never. |
| `mcakingdoms_living_villages` | history | 2,048 | Retention | The oldest entry is dropped first. |

Other bounds hold only open or per-request state and recover as it closes. Examples are per-government limits (64 offices, 128 mandates), per-request page sizes and pending-death queues. They are not listed.

## History, lasting effects and replay evidence

Records of one activity can play three different parts, and each is kept on its own terms. Public works (development) show all three:

- **History** is what players read: the list of finished works. It is disposable and goes by retention, but only after the next two are safe.
- **Lasting effects** are what the history caused: a completed work's productive asset (its output, earned points and role). Assets have their own collection and are never dropped with the history. Production and earned points come from the assets, so pruning history changes neither, and neither does removing a work's template from the datapacks.
- **Replay evidence** stops one request from acting twice. Development requests are issued by the server and carry their issue time. A receipt answers an exact repeat with its first result. Once a receipt is dropped for room, every request issued at or before it is refused as expired, so a delivery, refund or award never happens twice.

## Supported compaction

Two collections have finished records that no other record depends on. An operator may archive them. Compaction never runs on its own.

### Finished recruit transfers

```text
/kingdoms maintenance compact transfers          (dry run: counts, changes nothing)
/kingdoms maintenance compact transfers apply    (archives, then removes)
```

A transfer is archived only when:

- it is **complete**, **cancelled** or **expired**; and
- its deadline passed at least **72,000 game ticks** ago (three Minecraft days); and
- its native accounting never diverged.

Reconciled transfers stay as operator evidence. Every unfinished transfer keeps its recovery fence: offered, consented, applying, confirming or restoring.

Since 0.3.0, an offer that was never acted on expires by itself. To qualify, it must still be offered or consented, past its deadline, with no native snapshot and no divergence. Expiry runs every 200 ticks (ten seconds) and expires at most eight transfers per pass. Both parties are told; native ownership and equipment are unchanged. An expired offer is finished, so it becomes archivable three days later.

### Finished opportunities

```text
/kingdoms maintenance compact opportunities          (dry run)
/kingdoms maintenance compact opportunities apply
```

This compaction does three things:

- It archives opportunities that are resolved, declined or expired with a deadline at least 72,000 game ticks ago.
- It drops cooldowns that have already lapsed.
- It drops digest schedules of players who are no longer subscribed.

**Used evidence is never compacted.** It is what stops a receipt or a cause from counting twice.

### How an applied compaction works

1. The selected records are written as readable JSON to `<world>/mcakingdoms_archive/<store>-<gameTime>-<id>.json`. The file holds the store, the game time, the reason and the records. It is written to a temporary file, flushed and moved into place.
2. Only then are the records removed from the store, in one durable save.
3. If anything fails, nothing is removed that is not also in an archive file. A failed save after the archive was written leaves the records in both places, never in neither.

The archive folder sits outside `world/data/`, so Minecraft never loads it. Keep it with your backups. Archived records no longer appear in game.

### Before applying

1. Run the dry run and read the counts.
2. Back up the world. A backup taken while the server runs can be inconsistent. Prefer a stopped-server copy, or at least run `save-all flush` and copy the whole world folder.
3. Run the same command with `apply`, then `/kingdoms maintenance capacity` to confirm.

## Offline procedure

The collections marked **Offline procedure** hold records that other records point at:

- mandate provenance;
- agreement clauses;
- provider receipts;
- control history;
- private discovery.

This version cannot yet prove that removing any of them is safe. So it keeps **refusing new records at the bound** and does not delete anything. This is deliberate: deleting a record that something else still points at could re-award old work, erase current authority or free a recruit whose native state is uncertain.

The bounds are large compared with ordinary play. The smallest are 128 governments, 256 world events and 512 elections. When one of these collections reaches **near capacity**:

1. **Dry run.** Save the output of `/kingdoms maintenance capacity` (it changes nothing). Note which collection is near its bound and what is open in it. Then finish or withdraw open work through its normal tasks: proposals, elections, campaigns and world events in progress. That way no operation is mid-flight when you next stop the server.
2. **Relieve what can be relieved.** Run both compactions above, dry run first.
3. **Back up.** Stop the server and copy the **entire** world folder (including `world/data`, `playerdata`, `entities` and the dimension folders) and `config/`. One store is not a world rollback: native troops, quests, inventories and political records span several files.
4. **Do not edit or delete records by hand.** The table above names what refers to each collection, and stores validate their references when they load. A hand-edited file can be refused at the next start: a refused settlement store stops the server, and other stores become read-only. A file trimmed with an external NBT editor may also silently orphan native effects.
5. **Keep playing.** While a collection is full, only new records of that one kind are refused. Existing records, other systems and saves are unaffected.
6. **Plan the upgrade.** Send the saved capacity report with the issue. A later version will either ship a reference-aware compactor for that collection or raise its bound with a new storage schema. A store written by a newer schema is refused by older builds, so going back needs the backup from step 3.

Never delete a live `mcakingdoms_*.dat` file to make room. Minecraft would start that store empty, and every reference into it would break.

## Related

- [Upgrade and recovery runbook](Upgrade-and-Recovery.md): refused loads, failed migrations and downgrades.
- [Configuration reference](Configuration.md).
- [Completion ledger](Completion-Ledger.md): finding F12 and its tests.
