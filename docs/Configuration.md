# Configuration reference

MCA: Kingdoms writes eight TOML files to the instance's `config/` folder the first time it starts. Every key keeps its shipped default until you change it; nothing in this mod rewrites a value you set. Seven files are **common** (read by the server, and by a single-player world's integrated server); `mcakingdoms-client.toml` is **client** only.

**When a change applies.** Forge watches these files and reloads one when it is saved. Most values are read each time they are used, so a saved edit applies from the next use without a restart; the tables mark the exceptions:

- **start** — read once when the server starts. Restart the server after changing it.
- **reload** — read when definitions are parsed. It applies at the next data pack reload (`/kingdoms reload`, or Reload server definitions in Kingdom Tasks).
- **start-up pass** — only affects the one-time start-up reconciliation described under `reconcileOnStartup`.

**Inspecting the effective state.** Operators open **Server health** in Kingdom Tasks (K by default) or run `/kingdoms health`. It lists the feature switches, installed providers and which capabilities bound, saved-data writability, how full each bounded record collection is ([capacity](Capacity-and-Maintenance.md)), the last definition reload, this start's Ultima migration, and discovery/reconciliation activity. Players see the public part through **What is available here?**. `/kingdoms faction diagnostics` reports the standing synchronization in detail.

**Units.** Durations are in *game ticks*: 20 ticks are one second while the server keeps up, so the conversions given below are nominal. Ticks do not advance while a server is stopped or a single-player world is paused, and a lagging server advances them more slowly than the clock. Distances are in blocks unless a key says chunks (16 blocks).

Presets for a peaceful and a warfare-focused pack are in [docs/presets](presets/README.md). They are examples to copy by hand; nothing applies them automatically.

## `mcakingdoms-common.toml` — discovery, identity, founding, residence and political presentation

Owner: `config/McaKingdomsConfig`. 47 keys.

| Key | Default | Allowed | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `settlementDiscovery.defaultSettlementRadius` | 64 | 16–512 | live | Horizontal radius given to a newly detected settlement. Observation and merges never grow a settlement wider than eight times this radius. |
| `settlementDiscovery.poiSearchRadius` | 48 | 16–256 | live | Radius searched for village points of interest (beds, bells, workstations) when a loaded chunk is examined. |
| `settlementDiscovery.poiMinimumCount` | 5 | 1–64 | live | Points of interest a cluster needs to be recognized as a settlement. |
| `settlementDiscovery.chunkScansPerTick` | 2 | 1–64 | live | Queued loaded chunks examined per server tick. Every loaded chunk joins this queue, so discovery after a large area loads takes a while; it never loads chunks itself. |
| `settlementDiscovery.candidateMergeDistance` | 32 | 0–256 | live | Spatial fallback only: anchor distance at which a candidate matches an existing settlement when no village record or shared identity decides. |
| `settlementDiscovery.villageIdentity` | `STRUCTURE` | `STRUCTURE`, `SPATIAL` | live | `STRUCTURE`: everything inside a generated village's footprint is that one village, and split records are merged. `SPATIAL`: distance and overlap matching only, no merging — for troubleshooting. |
| `settlementDiscovery.structureMergeMargin` | 48 | 0–128 | live | Blocks beyond a generated village's structure box that still belong to it. |
| `settlementDiscovery.autoReconcile` | `true` | — | live | Merge records that turn out to be pieces of one generated village as their chunks are examined. |
| `settlementDiscovery.reconcileOnStartup` | `true` | — | start | Also reconcile every record once at server start. The pass runs once per world: when it finishes, the world records reconciliation version 1 and later starts skip it. Server health shows whether it ran. |
| `settlementDiscovery.reconcileStartupChunkBudget` | 64 | 0–4096 | start-up pass | Unloaded chunks the start-up pass may read for structure references; records beyond the budget reconcile when their chunks load. |
| `settlementDiscovery.reconcileChunkReadsPerTick` | 4 | 1–64 | start-up pass | Chunk reads the start-up pass spends per tick. |
| `settlementDiscovery.villageStructureTags` | `["minecraft:village"]` | structure tags or ids; leading `#` optional | live | Structures treated as generated villages. |
| `settlementDiscovery.poiLinkDistance` | 40 | 8–128 | live | Points of interest closer than this, horizontally, form one cluster. |
| `settlementDiscovery.poiRequireHomeOrMeeting` | `true` | — | live | A cluster needs at least one bed or bell. |
| `settlementDiscovery.poiSurfaceOnly` | `true` | — | live | Ignore clusters whose mean height is more than 24 blocks below the surface. |
| `settlementDiscovery.candidateOverlapFraction` | 0.5 | 0.1–1.0 | live | Spatial fallback only: share of the smaller area two bounds must overlap for a candidate to match. |
| `civicIdentity.evidenceInterval` | 200 | 20–24000 | live | Ticks between home-evidence checks for each loaded NPC (default 10 seconds). |
| `civicIdentity.playerRegistration` | `true` | — | live | Players may register or move their own civic residence (Register my residence, Move my residence, `/kingdoms residence`). The server checks its own evidence: the player's respawn point set by sleeping in a bed (not a command-set spawn), in a loaded chunk, still a bed, inside a recognized settlement, while the player stands in that settlement. A provider that reports an authoritative player home takes precedence over the bed. It records a civic home only: no land, office, NPC home, guild membership or war duty. Off: only operators and addons record player residences; existing records stay. |
| `civicIdentity.residenceMoveCooldown` | 24000 | 0–2400000 | live | Game ticks after any residence change before a player may move their residence again (default one Minecraft day of server ticks, not wall time; counted on the overworld clock, so it continues across dimensions and restarts). 0 turns the wait off. A first registration does not wait. An operator can skip the wait for their own move with Move my residence now (operator), and can still set anyone's residence with Set civic residence. |
| `politics.openFounding` | `true` | — | live | A player whose recorded civic residence is in a kingdom may found that kingdom's first government, with the residence as capital and themselves as leader. Operators can always found. Players record a residence themselves with Register my residence (see `civicIdentity.playerRegistration`); operators (`/kingdoms citizen setresidence`, Set civic residence) and addons can also set one. Visiting, sleeping and trading alone do not. |
| `politics.politicalNotices` | `true` | — | live | Send committed foundings, accessions, vacancies and confirmed-death memorials to players who turned notices on in their own settings (off by default on each client). Only public facts are announced, once each; a local office is announced only to players who know its settlement; private mandates and proposals are never announced. Off: nothing is sent. |
| `politics.officeBadges` | `true` | — | live | Send each player the public offices of holders their client is tracking, for the badge line above them. Nothing is sent for entities a player is not tracking. Off: no badges are sent. Person pickers in Kingdom Tasks name public offices either way. |

### 0.3.0 additions: migration, MCA Capitals, heraldry, dynasty and development

Every one of these is safe by default on new and upgraded worlds: no law of succession, construction, economy or Capitals
mapping changes anything until an operator or a kingdom's leader turns it on.

| Key | Default | Allowed | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `migration.legacyProgress` | `false` | — | start | Once per world, before players load: move advancement and statistic progress recorded under the pre-rename namespace `ultima_kingdoms:` to `mcakingdoms:`. Each changed player file is kept as `<file>.ultima-bak` first. Recipe-book entries are not rewritten. |
| `capitals.interop` | `PER_KINGDOM` | `PER_KINGDOM`, `ALL_READ_ONLY` | live | With MCA Capitals installed: `PER_KINGDOM` lets Capitals govern the crown of each kingdom whose village is one of its governing capitals (MCA: Kingdoms then refuses crown actions there and keeps the civic layer); other kingdoms keep their MCA: Kingdoms governments. `ALL_READ_ONLY` keeps every crown action read-only, as before 0.3.0. Capitals is only read. Ignored without Capitals. See [Capitals interoperability](Capitals-Interoperability.md). |
| `heraldry.enabled` | `true` | — | live | Kingdom banners, shields and noticeboards bind to a kingdom when placed, and officials seal charters at a seal press. Off: new placements stand for no kingdom and no charter is sealed or revoked; existing records stay readable and the items still exist. |
| `dynasty.enabled` | `true` | — | live | Dynastic succession and the court. Every government starts with the law Manual only, which changes nothing; nothing happens until a leader or operator adopts another law. Off: no law is adopted, no succession case opens or advances and no title is granted; records stay readable. |
| `dynasty.acceptanceTicks` | 72000 | 1200–2400000 | live | Game ticks a player heir has to accept or decline the crown before the succession waits for review (three Minecraft days). |
| `dynasty.mourningTicks` | 24000 | 0–2400000 | live | Game ticks a kingdom mourns a ruler whose death was observed: its pages say so and its banners darken. Shown only; nobody is changed. 0 turns mourning off. |
| `dynasty.claimCooldownTicks` | 24000 | 0–2400000 | live | Game ticks after a claim is decided before the same person may file the same kind of claim again (an appeal). |
| `dynasty.announcements` | `true` | — | live | Tell a kingdom's connected officials when its succession law changes, a succession opens under it, someone renounces or declines, and a succession waits for review. The death or abdication itself is already a political notice; the request to an heir or a council is sent either way. |
| `dynasty.legitimacy.lawWeight` | 2 | 0–10 | live | Legitimacy points for a ruler seated under the kingdom's law of succession. Legitimacy is a reading only; it changes no authority. |
| `dynasty.legitimacy.electionWeight` | 2 | 0–10 | live | Points for a ruler chosen by the government's election. |
| `dynasty.legitimacy.councilWeight` | 1 | 0–10 | live | Points for a ruler the council confirmed. |
| `dynasty.legitimacy.lineageWeight` | 1 | 0–10 | live | Points for an heir by a family relation MCA records. |
| `dynasty.legitimacy.appointmentWeight` | 1 | 0–10 | live | Points for a ruler seated by the officials or as the founder. |
| `dynasty.legitimacy.recognitionWeight` | 1 | 0–10 | live | Points when the kingdom is party to an agreement in force. |
| `dynasty.legitimacy.governanceWeight` | 1 | 0–10 | live | Points when at least two other officials are seated. |
| `dynasty.legitimacy.contestWeight` | 2 | 0–10 | live | Points subtracted for each open contest of the succession, at most two. |
| `development.mode` | `OFF` | `OFF`, `OBSERVE`, `PROJECTS`, `MANAGED_GROWTH`, `FULL_ECONOMY` | live | How far settlement development may act; each mode includes the ones before it. `OFF`: nothing is planned, saved or built. `OBSERVE`: pages explain development from verified buildings and completed works. `PROJECTS`: officials post public works players fund with real deliveries; a funded work is a milestone, nothing is built. `MANAGED_GROWTH`: funded works with a small template are built on their surveyed plot in settlements that opted in, a few blocks at a time, in loaded chunks only. `FULL_ECONOMY`: adds an abstract, labelled goods ledger and capped abstract trade between settlements of kingdoms with an agreement in force; no real item or coin is created or moved. Lowering the mode pauses work and keeps every record. |
| `development.constructionPaused` | `false` | — | live | Pause all construction; funding and records continue. |
| `development.economyPaused` | `false` | — | live | Pause deliveries, abstract production and abstract trade; construction continues. |
| `development.blocksPerPass` | 4 | 1–32 | live | Most blocks construction places each second across the whole server. |
| `development.maxProjects` | 64 | 1–512 | live | Most open works on the server at once. |
| `development.projectsPerSettlement` | 4 | 1–16 | live | Most open works in one settlement at once. |
| `development.tradeCap` | 8 | 0–64 | live | Most abstract units of one commodity two settlements trade per Minecraft day (`FULL_ECONOMY`). 0 turns trade off. |
| `development.homeProtectionRadius` | 8 | 0–32 | live | Blocks around a player's protected home (their bed) that no construction touches. |
| `development.dimensions` | `["minecraft:overworld"]` | dimension ids | live | Dimensions where construction may happen; others are never built in. |

There is no tax, wallet or currency setting: no payment provider is integrated, so works are funded with items only.

With the defaults, a point-of-interest cluster counts only with at least five points, a bed or bell among them, and near the surface. Generated villages are recognized from their structure regardless of those three rules.

## `mcakingdoms-client.toml` — entry title, Kingdom Tasks, the Book and political presentation (client)

Owner: `presentation/PresentationConfig`. 11 keys in five sections. Each player sets these for their own client, either in this file or in the **MCA: Kingdoms settings** screen (Kingdom Tasks → Settings, or Mods → MCA: Kingdoms → Config). The screen saves on Apply; Cancel leaves the file unchanged.

Section `entryOverlay`:

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `enabled` | `true` | — | Show the settlement name, kingdom and heraldry when entering a recognized settlement. |
| `firstVisitOnly` | `true` | — | Show it only the first time you enter each settlement. The server remembers visits with the world. |
| `kingdomBordersOnly` | `false` | — | Show it only when the kingdom differs from the last settlement entered. |
| `durationTicks` | 80 | 20–400 | How long the title stays, in client ticks (default 4 seconds). The time does not run while another screen hides the title. |
| `y` | 54 | 0–1000 | Vertical position in pixels from the top of the screen. |

Sections `onboarding`, `tasks` and `guide`:

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `onboarding.showIntro` | `true` | — | Show the short introduction at the top of Kingdom Tasks' start view. Getting started stays available either way. |
| `tasks.openTo` | `START` | `START`, `ALL_TASKS` | What the Kingdom Tasks key opens: the start view (where you are, suggested next steps, favourites, recent tasks and features), or the full task list. |
| `guide.reducedMotion` | `false` | — | Show the first accepted item of a recipe ingredient instead of cycling through the alternatives every two seconds. |

Section `politics` (the **Politics** tab of the settings screen). Notices and badges only show what the server sends; the server settings `politics.politicalNotices` and `politics.officeBadges` decide whether anything is sent.

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `notices` | `OFF` | `OFF`, `CHAT`, `BANNER` | Political notices (foundings, accessions, vacancies and memorials) from the server's committed record. `CHAT` adds one chat line; `BANNER` adds the chat line and a short silent banner. No sound plays, and each moment is shown once. |
| `officeBadges` | `true` | — | Show a small line above public office holders near you naming their office (with a mark for a leader, and how many more offices they hold). Names, skins and equipment are never changed. |
| `officeBadgeDistance` | 16 | 4–48 | How far away, in blocks, office badges are drawn. |

Bookmarks, recent topics and tasks, favourite tasks and the last topic read are not settings: they are kept in `config/mcakingdoms-client-state.json` on the player's own computer (at most 32 bookmarks, 16 favourites and 8 recent entries of each kind). Deleting the file, or **Clear history** in the settings screen, forgets them; nothing about them is sent to a server.

## `mcakingdoms-civic-common.toml` — guilds and institutional services

Owner: `civic/CivicConfig`. 5 keys, section `regional_civic_network`.

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `enabled` | `true` | — | Guild chapter services. Off refuses new services and keeps memberships, appointments and history. |
| `institutionalServices` | `true` | — | Paid workshop commissions and the civilian effects of agreements. They also need their providers (MCA Quests receipts; MCA: Crime's workshop-legality check for workshop jobs). Off keeps accepted records. |
| `introductionRadius` | 4096 | 128–32768 | Largest distance between neighbouring chapters for introductions. No chunks are loaded to find a destination. |
| `contactRange` | 8 | 2–32 | How close, with a clear line of sight, a player must be to a guild or contract contact. Kingdom Tasks lists contacts within it. MCA: Crime's workshop legality check keeps its own 8-block rule. |
| `introductionCooldown` | 24000 | 20–168000 | Ticks between new introductions per player and guild (default 20 minutes). Asking again returns the same destination. |

## `mcakingdoms-factions-common.toml` — kingdom standing and MCA Reputation

Owner: `factions/config/FactionConfig`. 9 keys.

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `reputationSync.mode` | `SHADOW` | `OFF`, `SHADOW`, `MCA_TO_FACTION`, `BIDIRECTIONAL_SEMANTIC` | `OFF`: MCA Reputation is not read. `SHADOW` (observe only): changes are read and the standing they would cause is recorded, but standing does not change. `MCA_TO_FACTION`: eligible MCA reputation changes in a settlement also change standing with its kingdom. `BIDIRECTIONAL_SEMANTIC`: additionally delivers scoped kingdom-side effects to MCA communities. Live modes need MCA Reputation 0.6.1 or later; with 0.6.0 local standing is read but no changes arrive. Start in `SHADOW` and review `/kingdoms faction diagnostics` before switching. |
| `reputationSync.localDeltaContribution` | 0.5 | 0.0–4.0 | Share of an eligible MCA reputation change that becomes kingdom standing, rounded. 0 ignores every change. |
| `reputationSync.propagateDecay` | `false` | — | Also count MCA reputation decay. Deeds, resolutions and superseded records always count. |
| `reputationSync.propagateAdminChanges` | `false` | — | Also count changes made by operators or commands. |
| `reputationSync.factionOverlayMax` | 50 | 0–1000 | Largest amount, up or down, that kingdom standing adds to local MCA standing when both are combined. |
| `reputationSync.receiptRetentionTicks` | 12096000 | 1 or more | How long a processed change is remembered so it is never applied twice (default 168 hours). |
| `reputationSync.missingMappingCapacity` | 2048 | 1–100000 | Most changes held for review when their settlement has no kingdom yet. Operators assign them with Assign an unmapped standing event. |
| `reputationSync.settlementReassignmentPolicy` | `FREEZE_HISTORY` | `FREEZE_HISTORY` | Informational; this is the only policy. Standing already earned stays with the kingdom it was earned under when a settlement changes kingdom. |
| `migration.legacyAggregation` | `MEAN` | `MEAN`, `MEDIAN`, `MAX`, `MIN`, `SUM` | How several legacy standing values for one kingdom combine when imported. Preview with Preview legacy reputation migration first. |

This legacy *standing import* is separate from the Ultima Kingdoms *namespace migration* described in the [README](../README.md#upgrading-from-ultima-kingdoms).

## `mcakingdoms-warfare-common.toml` — warfare, contracts and world context

Owner: `warfare/WarfareConfig`. 13 keys, section `political_warfare`. Every switch keeps existing records readable when off.

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `enabled` | `true` | — | Master switch for everything in this file. |
| `militaryActions` | `true` | — | Campaigns, settlement control agreements and temporary recruit deployments. They also need Recruits exactly 1.15.2; another Recruits version leaves them unavailable. |
| `civilianContracts` | `true` | — | Civilian recovery contracts offered by settlement contacts. Contracts that belong to an evolving-world opportunity or protection obligation follow that system instead. |
| `targetProtection` | `true` | — | Limits what recruits and mapped native armies may attack: not their own faction or allies; villagers, players under safe conduct and neutral companions only under local enforcement rules. |
| `worldContext` | `true` | — | World sites, institution routes, route travel and encounters. |
| `protectCapitals` | `true` | — | Refuse campaigns and control changes against a kingdom's capital seat. |
| `requireDefenderOnline` | `true` | — | A siege of a bound settlement needs the native defending leader online. |
| `campaignNoticeTicks` | 1200 | 20–168000 | Ticks between declaring a campaign and its start (default 1 minute). |
| `campaignDurationTicks` | 168000 | 1200–2400000 | Ticks a campaign, or a proposed control agreement, stays open (default 2 hours 20 minutes). |
| `mobilizationTicks` | 6000 | 100–24000 | Ticks a temporary deployment lasts before the recruit's original orders return (default 5 minutes). |
| `unitsPerCommander` | 16 | 1–64 | Most recruits one player may have deployed at once. |
| `reconcileInterval` | 100 | 20–1200 | Ticks between bounded reconciliation batches against Recruits' saved state. |
| `claimsPerBatch` | 8 | 1–32 | Control records and campaigns examined per batch. |

## `mcakingdoms-evolution-common.toml` — evolving world budgets

Owner: `evolution/EvolutionConfig`. 5 keys, no section.

World evolution is **off for every world** until an operator opts that world in with Configure world evolution in Kingdom Tasks (or `/kingdoms evolution`); world events (dramatic scenarios) are a second, separate opt-in on the same form. This file only bounds the work once a world has opted in.

| Key | Default | Allowed | Meaning |
| --- | --- | --- | --- |
| `interval` | 1200 | 200–24000 | Ticks between evaluations of active regions (default 1 minute). |
| `regionBudget` | 4 | 1–16 | Most online players and regions examined per evaluation. No chunks are loaded. |
| `concurrentScenarios` | 8 | 1–64 | Most opportunities and world events open at once in one world. |
| `digestInterval` | 24000 | 1200–240000 | Ticks between opportunity digests for subscribed players (default 20 minutes, one Minecraft day). |
| `autoEligibleRegions` | `true` | — | Treat every settlement of a kingdom with a government as an eligible region unless an operator pauses it. It does not opt a world in. |

## `mcakingdoms-integrations-common.toml` — kingdom gates and Living Villages

Owner: `integration/IntegrationConfig`. 27 keys in two sections: `kingdomGating` (3) and `livingVillages` (24).

### Section `kingdomGating`

| Key | Default | Allowed | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `enabled` | `true` | — | live | Kingdom gates for addon content: the `mcakingdoms:kingdom` MCA Quests condition and conversation `kingdom_gate` topics. Off makes every gate answer no. |
| `unknownKingdomPolicy` | `DENY` | `DENY`, `ALLOW` | reload | What a gate answers when the kingdom cannot be determined, for MCA: Kingdoms datapack gates (`kingdom_gates/`) and MCA Quests conditions that omit `when_unknown`. A predicate that states `when_unknown` keeps it. Predicates an addon parses itself through `KingdomGateApi` keep that API's `DENY` default. |
| `cacheBySettlementRevision` | `true` | — | — | Reserved. No current adapter caches settlement contexts, so it has no effect; every check reads current records. |

### Section `livingVillages`

The [Recruits: Living Villages integration](Living-Villages-Integration.md). It is active only with Recruits 1.15.2 and the audited Living Villages 1.1.0 jar; with any other build, saved bridge records stay read-only and nothing new is bound. Turning a switch off never erases bindings, wars, agreements or residents; it stops new operations of that kind.

| Key | Default | Allowed | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `enabled` | `true` | — | live | Use the integration when the supported versions are installed. Off: every hook leaves Living Villages to its own behaviour until it is turned on again. A server that started with it off starts the bridge at its next start. |
| `autoBindNativeVillages` | `true` | — | live | Give every unambiguous Living Villages village one settlement, importing an existing world in bounded batches. Ambiguous matches wait for an operator. Off: villages with no record yet are not bound; existing bindings stay. |
| `autoCreateRealmRecords` | `true` | — | live | Keep a realm record for every Living Villages faction, including a player faction whose Ledger is not placed yet (a realm only, with no settlement). |
| `preserveExistingNames` | `true` | — | live | An existing settlement bound to a Living Villages village keeps its name, and the Living Villages name is kept on the binding. Off: an unlocked settlement takes the Living Villages name when no other settlement holds it, keeping its own as an alias. A locked name is always kept. |
| `adoptOrdinaryVillagesAutomatically` | `false` | — | live | Place eligible generated villages under Living Villages management on their own, at most one per reconciliation interval, by the same preview as the operator task (no blockers, not a capital seat). Off: only through *Enable Living Villages management here*. |
| `allowAiVsAiWars` | `true` | — | live | Let AI realms fight each other where Living Villages' rules and MCA: Kingdoms' warfare switches allow. |
| `playerTargetPolicy` | `PROVIDER_AND_KINGDOMS` | `PROVIDER_AND_KINGDOMS`, `PROVIDER_ONLY` | live | `PROVIDER_AND_KINGDOMS`: Living Villages' own exclusions plus MCA: Kingdoms' offline-defender protection for player-led targets (`requireDefenderOnline` in the warfare file). `PROVIDER_ONLY`: Living Villages' exclusions alone. |
| `aiRealmCapitalPolicy` | `ALLOW_UNLESS_PROTECTED` | `ALLOW_UNLESS_PROTECTED`, `PROTECT_ALL` | live | `ALLOW_UNLESS_PROTECTED`: an ordinary AI realm's capital can be attacked. `PROTECT_ALL`: no realm capital can be. |
| `respectExistingCapitalProtection` | `true` | — | live | Honour the warfare file's `protectCapitals` for a kingdom's capital seat that is also a Living Villages village. |
| `aiTreatyDecisions` | `true` | — | live | AI realms accept or refuse proposed agreements at once, by the recorded policy. Off: an AI realm does not answer by itself; only ratifying officials of a kingdom associated with it for diplomacy can sign for it. |
| `nativeRelationArbitration` | `true` | — | live | Living Villages' periodic relation step cannot overturn an agreement in force; the held-back change is counted. |
| `minimumTruceTicks` | 24000 | 0–2400000 | live | Game ticks after a peace or ceasefire before the same realms may fight again (one Minecraft day). |
| `persistentResidents` | `true` | — | live | Keep the residents of integrated villages as the same people: Living Villages may not discard or respawn them when players leave, at promotion, at cleanup or to balance its counts. Also decides whether the start-up check below applies. |
| `mcaResidents` | `true` | — | live | With MCA Reborn installed, new residents of integrated villages are created through MCA's villager factory (either sex, MCA genetics and names) instead of vanilla villagers. |
| `automaticRecruitmentOfProtectedResidents` | `false` | — | live | Let Living Villages turn a protected resident (married, with family, holding an office, or named by a player) into a soldier or worker on its own. Off keeps them in their role. |
| `constructionInAdoptedVillages` | `false` | — | live | Let Living Villages build, flatten, dig, place its lectern or restock livestock in a village placed under its management deliberately. Off keeps every existing building, field and animal as it is. |
| `protectExternalBuildings` | `true` | — | live | Living Villages may not found a natural village, or choose a spot for a new building, on another settlement's ground, a player's protected home, or where a block entity of someone else's already stands (a bed, chest, barrel, furnace, lectern or sign); it tries another spot. Its existing buildings, their upgrades and its roads are its own. |
| `companionContext` | `true` | — | live | Give installed MCA companions the verified village, realm and war context (MCA Conversations and Quests context values, MCA: Crime jurisdiction), and grant the kingdom standing a supply delivery earns. |
| `deduplicateNotifications` | `true` | — | live | Living Villages announces its own wars, sieges, battles and conquests. On: MCA: Kingdoms does not repeat them and announces agreements, breaches, cessions, capital changes, defeats and adoptions. Off: it announces those too. Each announcement reaches a player once, after the save that made it durable. |
| `changedRecordsPerBatch` | 8 | 1–64 | live | Changed village records reconciled per batch. |
| `batchIntervalTicks` | 20 | 1–1200 | live | Ticks between change batches. |
| `reconciliationIntervalTicks` | 200 | 20–24000 | live | Ticks between bounded checks of known bindings for missed changes, agreement upkeep and automatic adoption. |
| `matchDistance` | 48 | 0–256 | live | How far, in blocks, an existing settlement's anchor may be from a village centre and still be taken for the same place, when its bounds do not already contain the centre. |
| `allowUnprotectedResidents` | `false` | — | start | Operator acknowledgement: start a world that has residents of integrated villages even though their protection hooks are not active (another Living Villages build). Living Villages may then discard those residents. Off: such a world refuses to start, with the reason in the log. |

## `mcakingdoms-townstead-common.toml` — Townstead adapter

Owner: `compat/townstead/TownsteadIntegrationConfig`. 7 keys, section `townstead`. Used only when Townstead is installed.

| Key | Default | Allowed | Applies | Meaning |
| --- | --- | --- | --- | --- |
| `enabled` | `true` | — | start | Use Townstead for building evidence, NPC life stages, calendar, needs, schedule, spirit and reactions. Without it, building evidence comes from MCA's own village buildings. |
| `allowInternalFallback` | `true` | — | start | Permit isolated reflection for Townstead spirit and reaction data missing from its public API. |
| `buildingReconcileIntervalTicks` | 200 | 20–24000 | live | Ticks between building fingerprint checks for settlements near players. |
| `maxActiveSettlements` | 32 | 1–256 | live | Most settlements rechecked per interval. |
| `enablePoliticalReactions` | `true` | — | live | Let nearby Townstead villagers react when a player's kingdom standing changes. |
| `enableBlueprintCivicHeader` | `true` | — | live | Show the settlement's civic header above Townstead's Blueprint screen. |
| `blueprintRequestRange` | 192 | 32–1024 | live | Largest distance from a settlement anchor for Blueprint header requests. |

Townstead bindings are probed member by member at start. Server health lists the capabilities that bound; an installed version other than the validated one is reported, and a capability whose members moved fails closed on its own.
