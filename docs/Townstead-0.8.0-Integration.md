# Townstead 0.8.0 integration

MCA: Kingdoms 0.3.0 reads Townstead 0.8.0 through Townstead's public `api.v1` (generation 1, revision 2), keeps working
with Townstead 0.7.6 through the older facade, and works without Townstead at all. Townstead is never written: MCA:
Kingdoms reads its data, asks it to play reactions and tell stories, and keeps its own records in its own save file. This
page covers who owns what, the tested combinations, what players and operators see, how to recover from mistakes, and
what Townstead 0.8.0's public API cannot do yet.

The proposal for the Townstead side of a full partnership is a separate document:
[Townstead upstream API proposal](Townstead-Upstream-API-Proposal.md). Nothing in this release changes Townstead.

## Tested tuples

Packaged dedicated-server runs (Minecraft 1.20.1, Forge 47.4.23, Java 17), each in a fresh directory under
`build/ts080-evidence/runtime/`. Jar hashes are in [certified compatibility](compatibility-certified.md).

| Tuple | Result | Evidence |
| --- | --- | --- |
| Townstead 0.8.0+1.20.1 with MCA Reborn 7.7.1-beta.5+1.20.1 | **Certified.** `v1` backend (api.v1 generation 1, revision 2); four boots: first (capabilities, exact mapping, completion transitions, authority, live careers switch, example data pack, one Chronicle story), restart (nothing replayed), Townstead removed (store byte-identical, MCA evidence kept), Townstead restored | `ts080-10`, `ts080-initial-6` |
| Townstead 0.7.6+1.20.1 with MCA Reborn 7.6.26 (and Architectury 9.2.14, Patchouli) | **Certified.** `legacy` backend; the same four boots | `ts076-6`, `ts076-initial-4` |
| MCA Reborn 7.7.1-beta.5 without Townstead | `mca` backend; MCA's own buildings and completion | `mca77-4`, `mca77-initial-4` |
| MCA: Kingdoms alone | `none` backend; no optional class loaded | `alone-4` |
| Townstead 0.8.0 with MCA Reborn 7.6.26 | Not a tuple: Forge refuses to load Townstead ("requires mca … 7.7.1-beta.5+1.20.1"), as its own metadata says | `ts080-mca76-4` |
| The pack on Townstead 0.8.0: Recruits 1.15.2, MCA 7.7.1-beta.5, Architectury 9.2.14, MCA Quests 1.7.1, MCA: Crime 0.7.5, Patchouli, GeckoLib | Phases `r2`, `r3-civilian`, `r4`, `r4-service`, `r4-family`, `r4-extensions`, `r4-crash` pass; MCA Reputation 0.6.1 on MCA 7.7.1 passes `migration` | `reg-*-ts080-1` |
| MCA Capitals 1.3.7 with MCA 7.7.1-beta.5 and Townstead 0.8.0 | Phase `capitals` passes: Capitals' crown policy applies unchanged beside Townstead's `v1` backend | `reg-capitals-137-ts080-1` |
| Recruits: Living Villages 1.1.0 with MCA 7.7.1-beta.5 | Phase `lv-mca` passes: new residents are MCA people | `reg-lv-mca-ts080-2` |
| Townstead work and rest | A full 24,000-tick Townstead work and rest cycle: office queries answer throughout, profession and shifts unchanged, on 0.8.0 (`v1`) and 0.7.6 | `reg-politics-cycle-ts080-1`, `reg-politics-cycle-1` |

## Backends

At server start MCA: Kingdoms picks one backend for the whole session and says which in the log, in Server health and in
`/kingdoms compat townstead status`:

| Backend | When | Reads |
| --- | --- | --- |
| `v1` | Townstead installed, `townstead.enabled` and `townstead.usePublicApi` on, and its `api.v1` reports generation 1 | Townstead's public API: villages, buildings with completion, residents, needs, careers, work orders, storage counts, settings, events, Chronicles, politics (read only), spirit and reactions |
| `legacy` | Townstead 0.7.x (no `api.v1`), or `usePublicApi = false` | Townstead's 0.7.x facade, as in 0.2.x; spirit and reactions through isolated reflection when `allowInternalFallback` is on |
| `mca` | Townstead absent or `townstead.enabled = false`, MCA installed | MCA's own village buildings (with MCA's own completion record) |
| `none` | Neither installed | Nothing; every page says Townstead is not installed |

Buildings come from one source per session: a `v1` session whose building list is unusable reads MCA's list for that
whole session rather than switching mid-run. A Townstead `api.v1` with an unknown generation is not used; the facade is
tried instead, and the reason is logged.

Each capability is probed member by member when the backend binds. A capability whose members are missing is
`UNSUPPORTED` and the rest keep working. Whether a capability is bound and whether the world runs the Townstead system
behind it are separate answers: a bound capability whose world switch is off reads `DISABLED`, and turns usable again
when the switch is turned back on, without a restart (Townstead's settings-changed event refreshes it). States:
`ABSENT`, `DISABLED`, `UNSUPPORTED`, `READY`, `DEGRADED`, `STALE`, `RECOVERY_REQUIRED`. A Townstead system id that
Townstead does not list is reported unknown, never assumed on.

`POLITICAL_DELEGATION` is never bound by this release: Townstead 0.8.0 publishes no contract through which it accepts an
external government (see [API limits](#api-limits)).

## Who owns what

| Concept | Owner | MCA: Kingdoms does |
| --- | --- | --- |
| MCA village record, residents, buildings and their completion | MCA (Townstead's `api.v1` presents them) | Reads them; never edits a village or building |
| Townstead needs, careers, professions, skills, spirit, schedules, hangouts, calendar | Townstead | Reads public summaries; changes nothing |
| Work orders and village storage | Townstead | Shows orders and advisory counts; never reserves, withdraws, delivers or pays |
| Townstead factions, councils, seats, legitimacy | Townstead | Reads them (`READ_POLITICS`) as evidence for the authority resolver |
| Settlement identity, civic name, kingdom, residence, civic identity | MCA: Kingdoms | Maps each settlement to its MCA village by exact dimension-qualified reference |
| A kingdom's crown | MCA Capitals for a kingdom whose village is a governing Capitals capital, otherwise MCA: Kingdoms | Applies the existing Capitals policy unchanged |
| Local government (civic offices, recognitions) | Resolved per settlement: see [Authority](#authority) | Reports conflicts; never merges, chooses or erases records |
| Guild chapters and their services | MCA: Kingdoms | Townstead associations stay Townstead's; neither becomes the other |
| Arms | Townstead for local and faction arms on its guards; MCA: Kingdoms for its own screens | Optional fallback design on plain shields only (`heraldryFallback`, off by default) |
| Village news (Chronicles) | Townstead | Tells it about three kinds of committed events, at most once each; Kingdoms' official history stays in Kingdoms |
| Reactions | Townstead plays them | Asks nearby villagers to react when a player's kingdom standing changes |
| Rewards, payments, quest completion | Their owners (MCA Quests, MCA: Kingdoms' own ledgers) | Never pays or rewards from a Townstead event, story or stock count |

## Authority

`/kingdoms compat townstead authority <settlement>` (and **Government and services** in Kingdom Tasks) answers, for each
domain of one settlement, who governs it, in what state, from what evidence, and which MCA: Kingdoms actions are allowed.

| Domain | Answer |
| --- | --- |
| `CROWN` | MCA Capitals for a Capitals-governed kingdom (`EXTERNAL_ACTIVE`, or `EXTERNAL_UNAVAILABLE` when Capitals cannot be read; an unmapped capital is a `CONFLICT` waiting for `/kingdoms capitals report`); otherwise MCA: Kingdoms when the kingdom has an active government, or nobody yet. |
| `LOCAL_GOVERNMENT` | The rules below. |
| `ORGANIZATION` | MCA: Kingdoms for its guild chapters. |
| `NAMING` | MCA: Kingdoms keeps the civic name, MCA keeps the village name; a difference is shown, not synchronized. |
| `HERALDRY` | MCA: Kingdoms in its own screens; Townstead's local and faction arms on its guards. |

Local government, in order:

1. `politicalOwnership = false`: `DISABLED`; MCA: Kingdoms acts on its own records as before.
2. An operator's **release** to Townstead: `EXTERNAL_ACTIVE` while Townstead's politics can be read. MCA: Kingdoms then
   refuses its own settlement-scoped appointments and recognitions there, so there is never a second equivalent
   officeholder. While Townstead cannot be read (removed, or politics off) the release stands as `EXTERNAL_UNAVAILABLE`:
   MCA: Kingdoms does not take the government back on its own.
3. An operator's **adoption** of MCA: Kingdoms: `NATIVE`; MCA: Kingdoms keeps acting and the conflict counts as
   acknowledged. Townstead's own council keeps running inside Townstead, because Townstead accepts no external government.
4. A decision whose Townstead faction has changed since, or a Townstead save that looks restored from an older backup:
   `RECOVERY_REQUIRED`, with the evidence. The decision still applies until an operator reviews it.
5. No decision, an MCA: Kingdoms government and a populated Townstead council: `CONFLICT`. MCA: Kingdoms keeps acting
   exactly as before; nothing is merged or chosen.
6. Otherwise MCA: Kingdoms when its kingdom has a government, Townstead when only a Townstead faction exists, or nobody.

## Operator recovery

All under `/kingdoms compat townstead` (permission level 2), and in Kingdom Tasks under **Townstead villages**:

| Command | Effect |
| --- | --- |
| `status` | Installed versions, validated tuples, backend, every capability with its state and reason, the world's Townstead switches, mapping problems |
| `village <settlement>` | The settlement's id, other names, MCA village references (its own and any kept from a merge), Townstead's view of each, the age of the last consistent building reading and the local-government answer |
| `buildings <settlement>` | Each building with its type, family, tier, completion, source and availability |
| `queues` | Townstead change notices waiting, coalesced, dropped, handled and failed (failures are logged at most once a minute), building reconciliation, shared readings and the oldest one's age, reactions and Chronicle projections |
| `reconcile <settlement>` | Re-reads one settlement now. Nothing is awarded, changed, reset or replayed. |
| `authority <settlement>` | Every domain's answer with its evidence |
| `authority <settlement> preview adopt` / `preview release` | What a decision would change, before making it |
| `authority <settlement> adopt [note]` / `release [note]` | Records the decision with the Townstead evidence it was made on |
| `authority <settlement> clear` | Removes the decision; the evidence decides again. Records on both sides are unchanged. |
| `authority <settlement> acknowledge` | After checking a restored Townstead backup, records Townstead's current calendar day as the new baseline |

MCA: Kingdoms' decisions and baselines live in `world/data/mcakingdoms_townstead.dat`. A file from a newer version, or
one that cannot be read, is kept read-only and unchanged.

**Townstead removed.** Nothing is destroyed: settlements, governments, guild chapters, decisions and the
`mcakingdoms_townstead.dat` file stay as they were (the packaged run checks the file is byte-identical across a boot
without Townstead). Buildings are read from MCA again. Released local governments read `EXTERNAL_UNAVAILABLE`, and no
default council is installed in their place. The Village pages say Townstead is not installed.

**Townstead restored.** The same records become readable again; nothing is replayed, re-awarded or re-told.

**Restored Townstead backup.** The first time MCA: Kingdoms sees a settlement with Townstead it records Townstead's
calendar day. If Townstead's day later reads earlier than that, its save probably came from an older backup: the local
government reads `RECOVERY_REQUIRED` until an operator checks it and runs `acknowledge`.

**A building that is no longer complete.** A recognized institution whose building MCA records as incomplete is
`BUILDING_INCOMPLETE`: its services are suspended with that reason and its charter and history are kept. Finishing the
building again restores it on the next reading; nothing is rebound to another building on its own. Unloaded or
unreadable villages mean "wait", never "removed".

## What players see

- **Village life**: residents and how much of the roll Townstead knows, needs (hunger, thirst, temperature, energy)
  interpreted honestly (switched-off needs left out, heat and cold both counted as distress, "not simulated" kept apart
  from "no reading", too little coverage called insufficient information), spirit, culture, buildings with completion,
  today's date, and optional civic-aid suggestions that point at existing tasks.
- **Village work**: what worksites are asked to produce and advisory counts of goods in village storage (by default
  bread, wheat and logs; `advisoryStock`). Requested production is not delivered goods; counts never reserve anything.
- **Village news**: Townstead's public village digest, a page at a time. It is what villagers tell each other, not an
  official record: MCA: Kingdoms' own records are on the Kingdom page (History).
- **Government and services**: who governs each domain of the settlement, in plain words, and where to act.
- **Check a Townstead career**: whether the player's own career meets a requirement (a profession at a tier, or a
  learned skill). A Townstead tier is not a vanilla trade level or a guild rank.
- **Where a guild contact is**: when no contact of a guild is in reach, its service requirements and guild page name
  the settlement's own contact, how far away they are, and what Townstead says they are doing: on the way to or at a
  gathering place, or resting, working, meeting others or free, by their schedule. Nobody is moved, woken or called
  back, and no conversation is opened. Townstead's schedule does not reliably say when a shift resumes (weekly
  templates), so no return time is promised.
- The Village Ledger links these pages, and Townstead's Blueprint screen shows the settlement's name, kingdom, civic
  status and local-government answer with a button to Kingdom Tasks (`enableBlueprintCivicHeader`).
- The Book of Kingdoms has a **Townstead villages** topic in English, Spanish and Simplified Chinese.

Every page shows only settlements the player has discovered, reads shared cached summaries (one reading per settlement
for all viewers, refreshed by Townstead's change events), and loads no chunks.

## Gameplay connections

- **Institutions, guild chapters and development** use Townstead's building families and tiers
  (`mcakingdoms_townstead/institutions` lets a charter accept more families at a minimum tier). A new recognition or guild
  chapter needs a complete building; the Development page names unfinished buildings and does not count them, and a
  public work still needs its own deliveries. When completion cannot be read, the pre-0.3.0 rule applies and the
  evidence is labelled unverified.
- **Career requirements** (`careerRequirements`): data packs can make a guild service require a Townstead profession at a
  tier, any of several professions, or a learned skill (`mcakingdoms_townstead/careers`). Qualifications read the
  player's own career only and change nothing.
- **NPC officeholders**: adulthood comes from one shared rule (Townstead's life stage where it presents as one, else MCA's
  age state, else vanilla's baby flag).
- **Reactions**: when a player's kingdom standing changes, Townstead villagers nearby may react
  (`enablePoliticalReactions`; mappings in `mcakingdoms_townstead/reactions`).
- **Chronicles** (`chronicleProjection`): an institution recognized, an agreement concluded and a public work completed
  are told to Townstead once each, from the committed record, and only when a player stands in the settlement and the
  village's town range: Townstead scopes a story by its actor, so a remote treaty keeps only MCA: Kingdoms' own record.
  The attempt is recorded before Townstead is called, so a duplicate post, an exception or a missing template never
  leads to a second story or a second count; a restart replays nothing. Stories are never read back into rewards,
  standing or decisions. The templates are bundled under `data/mcakingdoms/chronicle_event/`.
- **Culture hints**: data packs can suggest a kingdom for new, unassigned settlements whose Townstead village has a given
  culture or founding profile (`mcakingdoms_townstead/culture_hints`). Explicit and locked assignments come first.
- **Heraldry fallback** (`heraldryFallback`, off by default): a plain shield issued to a guard of a kingdom's settlement
  takes the kingdom's mapped design (`mcakingdoms_townstead/livery`) when neither the settlement nor its Townstead faction
  has proclaimed arms. Decorated, named or already painted shields are never changed, and painting grants nothing.

## Configuration

Twenty-one keys in `mcakingdoms-townstead-common.toml`, section `townstead`; see the
[configuration reference](Configuration.md#mcakingdoms-townstead-commontoml--townstead-adapter). Townstead's own world
settings are never changed.

## Data packs

Mappings live in `data/<namespace>/mcakingdoms_townstead/<kind>/`; each file is validated on its own, and ids only
Townstead can confirm (professions, skills, cultures, founding profiles) are checked against the installed Townstead
after each reload. See the [datapack reference](Datapack-Reference.md#townstead-mappings). The
[example data pack](../examples/datapack/README.md) adds a career rule for its Harbor Archivists, a Townstead culture of
its own and a culture hint naming it; its `invalid/townstead_mapping` variant shows the diagnostic for a broken rule.

## API limits

What Townstead 0.8.0's public API cannot do, and what MCA: Kingdoms does instead:

| Missing upstream | Consequence in this release |
| --- | --- |
| An external civic-provider contract (Townstead's provider registry is internal and holds only MCA Capitals) | Political delegation is unavailable. Conflicts are reported and resolved by operator decisions on the MCA: Kingdoms side only; Townstead's council keeps running inside Townstead. |
| Provider-aware native political mutations (bootstrap and upkeep fill offices without asking) | A released settlement can still have its Townstead council refilled by Townstead; MCA: Kingdoms simply stops acting there. |
| A supported rename transaction | Civic and village names are shown side by side, never synchronized. |
| A heraldry or livery provider | The fallback paints only plain shields, after Townstead's own arms, through an ordinary equipment event. |
| A UI extension seam | The Blueprint header is drawn over Townstead's screen from Forge's screen events; there is no stable slot. |
| An idempotent, actorless Chronicle event | Stories need a present player, and Townstead counts before it looks for a template, so nothing is retried. |
| Order, inventory and career-reward transactions | No procurement, reservations, deliveries, payments or XP and skill awards are made from Townstead data. These features stay unavailable until Townstead offers receipts. |
| Needs helpers that skip switched-off needs and read heat and cold symmetrically | MCA: Kingdoms filters and interprets Townstead's readings itself. |
| Storage counts tied to the village's dimension | MCA: Kingdoms checks the dimension before asking and caches each count. |

## For addon developers

The public additions (capability states, building evidence and availability, village life, needs and careers views,
`PublicWorkCompletedEvent` and the detailed `TownsteadBuildingsChangedEvent`) are described in the
[Addon API guide](Addon-API.md#townstead). The API jar contains no Townstead types; the compile-only Townstead `api.v1`
jar in `libs/townstead-api` is built from Townstead's tagged source, pinned by SHA-256 and never shipped.

## Verification

- Unit tests (`check`): 395, 0 failures, 1 skipped (the 0.2.x probe of Townstead's 0.7.x facade, which needs that jar
  on the test classpath). They include the contract test of the compile-only `api.v1` jar against the published 0.8.0
  jar, member by member, and the check that no class outside `compat/townstead/v1` names a Townstead type.
- GameTests: 112 completed, all required passed (pinned seed).
- Packaged: the tuples above, and every phase of the 0.3.0 record rerun on the 0.7.6 tuple (`initial`, `politics`,
  `split-village`, `capitals`, `r4-fence`, `r4`, `r2`, `migration`, `r3`, `r3-civilian`, `military`, `r4-service`,
  `r4-family`, `r4-extensions`, `r4-crash`, `lv`, `lv-mca`; `reg-*`). The release jar's SHA-256 is in
  `build/ts080-evidence/final-jars.sha256`. The Living Villages phases, the 0.7.6 work cycle and Capitals 1.3.7 ran on
  it; the other runs used the build before the Living Villages factory fix, which differs from it only in
  `compat/livingvillages/LvResidents` and the new `McaCompat.root()`, code that runs only with Living Villages.

Not executed: an integrated client with the Blueprint header on screen (the header's server side, packet and
authorization are covered; its drawing and narration were not seen in a client), two-player sessions, and a
performance profile with many villages and viewers (the queues, budgets and caches are bounded by configuration, and
`/kingdoms compat townstead queues` reports them, but no budget was measured on a large server).
