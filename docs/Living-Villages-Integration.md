# Recruits: Living Villages integration

MCA: Kingdoms integrates with [Recruits: Living Villages](https://modrinth.com/mod/recruits-living-villages) (mod id
`recruitsvillages`). Living Villages runs its villages on its own: their economy, buildings, armies and wars, and the
war's combat. MCA: Kingdoms gives each of those villages one settlement identity, records which realm holds it and which
government is recognized there, keeps agreements between realms in force, and keeps the villages' residents the same
people. Everything here is optional. A server without Living Villages, or with Recruits alone, behaves as before.

This page covers what the integration does, which versions it supports, how existing worlds are imported, the settings,
and the operator tools. Players' explanations are in the Book of Kingdoms under *Recruits: Living Villages*.

## Supported versions

| Component | Supported | How it is checked |
| --- | --- | --- |
| Recruits | `1.15.2` | Exact version, as for the existing Recruits integration. |
| Recruits: Living Villages | `1.1.0` release jar, SHA-256 `55a7da18…46dda8` | `mixin/LvGate` applies the hooks only to that exact jar, so a patched jar with the same version string is not mistaken for it. |
| MCA Reborn (optional) | `7.6.26+1.20.1` with Architectury `9.2.14` | Residents of integrated villages are created through MCA's own `VillagerFactory`. |

`mods.toml` accepts `recruitsvillages` `[1.1.0,)`, so a newer release launches. With any other build, the hooks are not
applied: the integration stays off, saved bridge records stay as they are (read-only), nothing new is bound, and
`/kingdoms livingvillages status` and Server health say why.

One exception protects residents. If a world already has residents of integrated villages and their protection hooks
cannot be active, the server refuses to start, before any provider tick, with the reason in the log. Living Villages would
otherwise discard and respawn those people. Install the audited jar, or set
`livingVillages.allowUnprotectedResidents = true` to accept that risk.

Living Villages ships no public API. Every provider member the integration uses is resolved once, by exact name and
signature, in `compat/livingvillages/LvAccess`. A missing member keeps the integration off.

## Capabilities

`/kingdoms livingvillages status` (and the *Living Villages integration status* operator task) lists each capability as
`READY`, `PARTIAL`, `UNSUPPORTED`, `UNAVAILABLE` or `RECOVERY_REQUIRED`, with its reason:

| Capability | What it covers |
| --- | --- |
| Metadata read, village enumeration | Reading Living Villages' village records. |
| Stable binding, realm membership | One settlement per village; realms kept across capital changes. |
| Diplomacy request | Arbitration of Living Villages' relation step; agreements written to Recruits. |
| War preflight, abstract combat, conquest observation | The war hooks (below). |
| Resident preservation, worker support | Keeping residents and workers the same people. Resident preservation needs a production (reobfuscated) runtime. |
| Economy receipts, UI access | Supply deliveries and the Living Villages screen. |

Every capability except reading, enumeration and the screen reads `RECOVERY_REQUIRED` while the bridge store is
read-only. Stable binding and conquest observation also read it after Living Villages' saved data has gone back in time
(a restored backup), until an operator runs `/kingdoms livingvillages recover provider`. Warfare capabilities read
`PARTIAL` while `political_warfare.enabled` is off.

## Settings

The integration's settings are the `[livingVillages]` section of `config/mcakingdoms-integrations-common.toml`, listed in
the [configuration reference](Configuration.md#mcakingdoms-integrations-commontoml--kingdom-gates-and-living-villages).
Turning a switch off never erases bindings, wars, agreements or residents; it stops new operations of that kind.
`enabled` takes effect at once: with it off, every hook leaves Living Villages to its own behaviour until it is turned on
again. A server that started with it off starts the bridge at its next start.

## Identity: settlement, realm, kingdom and control

- **Settlement.** Each real Living Villages village is one MCA: Kingdoms settlement, carrying the external reference
  `recruitsvillages:village=<village uuid>`. Its arrival title, Ledger page and War Room are that village's.
- **Kingdom.** The settlement's civic kingdom (who its people are) is assigned by MCA: Kingdoms' own rules. War never
  changes it.
- **Realm.** A Living Villages faction (a capital and its subordinate villages) is a realm, with its own id. Two
  realms of the same culture are still two realms, and an agreement with one does not bind the other. A realm keeps its
  id when its capital falls and its surviving villages take a new capital.
- **Control.** The realm that holds the village now. When it differs from the recognized realm, the village is
  **occupied**: its recognized government stands until a cession is agreed. Occupation does not rename the village,
  replace residents, change families or rewrite its kingdom.

A player faction whose Ledger is not placed yet has no physical village. It is kept as a realm only, never as a
settlement at the world origin.

## Existing worlds

The import runs on its own after both mods load, in bounded batches (`changedRecordsPerBatch` every `batchIntervalTicks`)
that resume across restarts:

1. A village whose settlement already carries its reference keeps it.
2. Otherwise an existing Overworld settlement that contains the village centre, or is anchored within `matchDistance`,
   and is not already another village, is that village. It keeps its UUID, name, kingdom, discovery and history; the
   Living Villages name stays on the binding. With `preserveExistingNames` off, an unlocked settlement takes the Living
   Villages name instead when no other settlement holds it, keeping its own as an alias.
3. Otherwise a new settlement is registered under the Living Villages name (or a generated name when that one is taken).
4. Two or more possible settlements leave the village **ambiguous** until an operator decides: bind it to one candidate
   or keep it separate. Nothing is guessed or merged.

Settlements are never matched by name. A settlement that is a Living Villages village is never merged with another
record automatically; the merge preflight refuses it. Importing grants no rewards, marks nothing discovered by any
player, and records existing control as a baseline, not as a battle. Running the import again
(`/kingdoms livingvillages migration apply`) changes nothing for completed bindings. If Living Villages is removed, the
records stay readable and unchanged; its absence is not read as the villages' destruction.

## Wars

Living Villages remains the combat executor; MCA: Kingdoms never computes an outcome. Before Living Villages
declares a war, lays an abstract siege, starts a battle, moves a siege between the world and its model, resolves a siege
by its model, or lets the AI pick a target, the bridge applies one preflight (`compat/livingvillages/LvPolicy.war`):

| Rule | Default |
| --- | --- |
| `political_warfare.enabled` and `militaryActions` are on | refused otherwise |
| An AI-against-AI war | allowed (`allowAiVsAiWars`) |
| A peace, ceasefire or alliance in force between the realms | refused |
| A truce after peace (`minimumTruceTicks`, one Minecraft day) | refused while it runs |
| A kingdom's protected capital seat | refused while `protectCapitals` and `respectExistingCapitalProtection` |
| An ordinary AI realm capital | allowed (`aiRealmCapitalPolicy = ALLOW_UNLESS_PROTECTED`) |
| A player-led realm's village while its leader is offline | refused (`playerTargetPolicy = PROVIDER_AND_KINGDOMS`, `requireDefenderOnline`) |
| A Ledger-less player faction | refused (Living Villages' own exclusion) |
| A player's war request | needs native command of the attacking realm, and the Propose mandate when a kingdom represents it for war |

A refused target is passed over in the AI's selection, so the AI considers its next neighbour. Villages outside the
integration keep Living Villages' behaviour. The existing Recruits siege guards stay in force: for an integrated village's
claim, a native siege may register, progress and complete only for the attackers of an accepted conflict, or when the
attacking realm passes the same preflight (then the undeclared siege becomes a recorded conflict).

Each accepted war is one durable conflict from declaration to outcome, whether it is fought in the world, out of view,
or both in turn: declarations, history and rewards are never repeated when players come and go. A village allied to both
sides sits the war out. Conquest is taken from Living Villages' own occupation, journaled before and after, and
acknowledged only after the world save that wrote Living Villages' data. A no-change occupation is kept as recovery
evidence, not a victory. After a restart, Living Villages has forgotten its running wars (they live in memory only). The
bridge lays each open conflict again as an abstract siege between the same villages, committing the troops now on their
rosters by Living Villages' own rule, and the same conflict continues. A conflict whose resolution was interrupted waits
for recovery instead. Nothing is rerolled or granted.

Combatants of realms bound by an agreement in force do not damage each other (`warfare/MilitaryTargetPolicy`), whatever
their native relation reads. A player's recruits on a temporary MCA: Kingdoms deployment are not also sent to a Living
Villages battle. Provider-owned soldiers are unowned, so mobilization and recruit transfers already exclude them.

## Agreements between realms

*War and peace with a realm* (`warfare.realm_peace`, or `/kingdoms livingvillages treaty propose`) proposes peace, a
ceasefire, an alliance or a cession between a party the player speaks for and a realm or kingdom:

- A **player-led realm** is spoken for by its Recruits faction leader.
- A **kingdom government** is spoken for by its ratifying officials, and represents a realm only through an explicit
  operator association (`/kingdoms livingvillages associate <realm> <kingdom> DIPLOMACY|DIPLOMACY_AND_WAR`), never inferred
  from names or culture.
- An **AI realm** answers at once by a recorded policy, from Living Villages' own opinion of the proposer, relative
  strength, recent losses and whether they are at war (`LvPolicy.consider`). The same facts give the same answer.

A peace or ceasefire that takes effect ends every war between the parties through Living Villages itself (survivors
recalled once, no conquest, casualties kept), sets both native relation directions to neutral, and is reported in force
only after both read back. An alliance sets both directions to ally. A cession records the controlling realm as the
recognized one for the covered settlements. Living Villages' periodic relation step cannot overturn an agreement in force:
the bridge holds the change back and counts it. A relation set to enemy by another path (a player in Recruits' diplomacy
screen) marks the agreement **breached** and announces it; nothing is written back automatically.

A Living Villages faction's diplomacy is faction-wide, so agreements cover whole realms; the covered villages are recorded.

## Residents

For an integrated village (bound, `persistentResidents` on):

- When players leave, residents stay in the world and are saved with their chunks, and workers stay at their posts;
  Living Villages finds the same people when players return instead of spawning a second set.
- A worker is promoted in place: the same villager takes the new rank, keeps its trades and gains the provider's offers
  for each new tier.
- A person Living Villages stops counting (surplus trimming, stray adoption, `/rvcleanup`, a duplicate worker) is kept
  and taken off the provider's books (tag `RVVillagerFaction` set to a sentinel no village owns, plus `mcakingdoms:lv_kept`),
  not deleted.
- Living Villages does not turn a resident into a soldier by discarding them; it recruits from its own manpower.
  Married people, parents, family members, office holders, followers and companions are never employed or converted
  automatically while `automaticRecruitmentOfProtectedResidents` is off.
- With MCA Reborn installed, new residents are MCA people made by MCA's `VillagerFactory`, either sex.
- A soldier stood down into the village's rolls keeps its name, health, effects and persistent mod data as well as its kit.
- The resident index records unloaded, away (dimension change), dead and unresolved states separately; only a confirmed
  death is a death.

Villages outside the integration keep Living Villages' own behaviour. Living Villages' own model still decides how many
new people it creates.

## Placing an ordinary village under management

*Enable Living Villages management here* (`villages.adopt`, `/kingdoms livingvillages adopt preview|apply <settlement>`)
is deliberate. Living Villages 1.1.0 has no adoption API, and its founding routine stamps a town hall, flattens a plot and
spawns starters, so the bridge creates the provider record itself: centred on the settlement, counted from the adult
villagers and beds actually there, with an empty treasury and no soldiers, and with its existing villagers tagged as its
people. The preview lists residents, beds, workstations, containers, blockers (an unloaded area, another village's
grounds, Recruits claims on the land) and what becomes active.

While `constructionInAdoptedVillages` is off, Living Villages does not schedule buildings, place templates, dig mines,
place its lectern, remove stray livestock or restock paddocks in an adopted village. The village then grows, taxes and
recruits by its own rules, and becomes an independent AI realm that others may attack within the war rules. With
`adoptOrdinaryVillagesAutomatically` on, eligible generated villages are adopted on their own, one per reconciliation
interval, by the same preview.

## Construction near other places

While `protectExternalBuildings` is on (the default), Living Villages may not found a natural village whose building ground
(`villageBuildRadius` around its centre) reaches another settlement or a player's protected home, and may not choose a spot
for a new building on another settlement's ground, a protected home, or where a block entity that is not its own already
stands (a bed, chest, barrel, furnace, lectern or sign; a crafting table or other plain block is not one). It tries its next spot instead, and a building it cannot place stays
unbuilt in the world. This is checked when Living Villages chooses ground; its existing buildings, their upgrades in place
and the roads between them are its own. A village an operator founds with Living Villages' command keeps its fixed layout.

## Economy

Living Villages' treasury and stores stay canonical. Pages show the configured currency by its item name (the provider
calls it emeralds whatever it is). *Provide supplies* hands over currency or a material the village already stocks:
the items are taken from the player's inventory, the provider's own store credit takes what fits, any surplus goes back,
and the delivery is journaled. Once the next save has made it durable, the player gains a small standing with the
village's kingdom, once per request. *Why is growth paused?* reports Living Villages' own reasons: beds, the next building's cost against the
treasury, housing capped by the ground, a siege, or construction held in an adopted village. Living Villages 1.1.0 has
no food shortage, so none is reported.

## Companions

- **MCA: Crime**: the jurisdiction snapshot of an integrated village names its controlling realm and whether it is
  occupied; local civil law continues and nothing is legalized by war.
- **MCA Conversations and Quests** (context values): `mcakingdoms.realm`, `mcakingdoms.realm_recognized`,
  `mcakingdoms.realm_occupied`, `mcakingdoms.realm_war` and, during a war, `mcakingdoms.realm_enemy`, for an entity with a
  civic identity that stands in an integrated village.
- **Kingdom standing** (mirrored by MCA: Reputation where it synchronizes standing): a supply delivery earns the delivering
  player a small standing with the village's kingdom, once per request, after the save that made the delivery durable.

## Interface

- **Kingdom Tasks** (Places › Living Villages, Conflict): *Living Villages village here*, *Read a Living Villages village*,
  *View its realm*, *Open village* (Living Villages' own screen), *Join the army*, *Why is growth paused?*, *Provide
  supplies*, *Living Villages announcements*, *War and peace with a realm*, *Sign / Reject / End an agreement with a
  realm*, and the operator tasks below. A task the server cannot run is listed with the reason from the same capability
  snapshot the services use.
- **Village Ledger**: *Living Villages realm and army* on a settlement's page (active when the client has Living Villages).
- **War Room**: a *Living Villages realms* section (the realm in control, the recognized government, the realms' wars)
  and a *War and peace with a realm* link.
- **Announcements**: agreements, breaches, cessions, capital changes, defeats and adoptions, once per player, after the
  save that made them durable. Living Villages already announces wars and conquests itself, so those are not repeated
  (`deduplicateNotifications`). Players can mute announcements outside their realm.

## Operator commands

| Command | Does |
| --- | --- |
| `/kingdoms livingvillages status` | Tuple, capabilities, policies (Kingdoms' and Living Villages' own), records and queues. |
| `/kingdoms livingvillages inspect <settlement>` | Binding, names, claims, realm, provenance, latest conflict, refusal and operation. |
| `/kingdoms livingvillages reconcile [settlement]` | Queues known villages (or one) for the next bounded batch. |
| `/kingdoms livingvillages migration preview` / `apply` | The import plan; running the import again. |
| `/kingdoms livingvillages conflicts` | Ambiguous or pending villages, wars and operations waiting for recovery. |
| `/kingdoms livingvillages recover <id>` / `recover provider` | Resolves one record after checking Living Villages again; accepts restored provider data as the baseline. |
| `/kingdoms livingvillages bind <village> <settlement>` / `separate <village>` | Decides an ambiguous village. |
| `/kingdoms livingvillages associate <realm> <kingdom> <scope>` / `dissociate <realm>` | Explicit government representation. |
| `/kingdoms livingvillages adopt preview|apply <settlement>` | Management of an ordinary village. |
| `/kingdoms livingvillages rename <settlement> <name>` | Renames in both mods; team ids and references never change. |

Players have `village`, `realm`, `open`, `growth`, `supply <kind> <amount>`, `treaty propose|sign|reject|end` and
`quiet on|off` under the same root.

## Records

`world/data/mcakingdoms_living_villages.dat` (schema 1) holds bindings, realms, conflicts, the operation journal,
agreements, residents, history and the import state. Consequential changes are written atomically before the provider
acts; a file this version cannot read is kept unchanged and read-only. Capacity is listed under
`/kingdoms maintenance capacity`.

## Validation

Validated on 2026-10-28 with the unchanged packaged mod on a dedicated Forge 47.4.23 server, with Recruits 1.15.2 and the
audited Living Villages 1.1.0 jar (`tools/test/integration_runtime.py --phase lv`). Six boots: the import, realms,
residents, an abstract conquest, capital succession, AI peace, arbitration, the war preflight and AI target selection;
then a restart, Living Villages removed, reinstalled, a future-schema bridge store, and an unaudited jar refused before
any world change. The `lv-mca` phase adds MCA Reborn 7.6.26: new residents are MCA villagers made by MCA's factory and
survive the provider folding the village. Results and jar hashes are in [certified compatibility](compatibility-certified.md);
the policies and the bridge document have unit tests (`compat/livingvillages`).

## Limits

- Overworld only, as Living Villages itself.
- A conflict's identity survives a restart, but Living Villages' own live battle does not: after a restart the war
  continues as an abstract siege until players are near again.
- The bridge cannot make Living Villages' cross-mod writes atomic: it journals intent and evidence before and after, and
  recovers from them (`recover`).
- Construction protection is checked where Living Villages chooses ground for a natural village or a new building. It
  recognizes another settlement, a protected home and block entities, not plain blocks, and it does not cover road grading
  between Living Villages' own buildings or the fixed layout of a village founded by command.
- Map visibility: Living Villages' own map markers are broadcast by Living Villages to every player; the bridge does not
  change that. Kingdoms announcements and pages show only settlements the player knows.
