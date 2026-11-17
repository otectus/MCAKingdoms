# Changelog

## 0.3.1 — unreleased

Recovery, lifecycle and interaction repairs from the 9 November 2026 review (AUD-01 to AUD-18), settlement names for
Waystones (WAY-01) and a clearer first session (UX-01). Update clients and servers together. Upgrade and downgrade steps
are in [Upgrade and recovery](docs/Upgrade-and-Recovery.md#upgrading-from-030-to-031); what was tested, and what was not,
is in [Testing 0.3.1](docs/testing/0.3.1/README.md).

### Waystones

- With exactly Waystones 14.1.21 and Balm 7.3.44 installed, a normal waystone inside a recognized settlement takes the
  settlement's name the first time it needs one: a generated stone at its first activation, a newly placed unnamed stone
  before its settings screen opens. A clash gets a number, such as `Oakfield (2)`. Existing names, and every name a
  player chooses, are kept. Sharestones, warp plates and portstones are unchanged. Naming happens once; established
  stones are never renamed when a settlement is renamed or merged.
- A stone placed where a settlement is about to be recognized waits briefly (`waystones.pendingTicks`) and is named once
  the settlement is found, unless someone named it meanwhile. No chunk is loaded.
- On the tested Waystones jar, renames accepted in Waystones' settings screen are recorded, so the player's choice wins
  for good. Each name given is recorded before Waystones receives it and confirmed at the next save; after a crash,
  names are settled from what Waystones saved, never renumbered or applied twice.
- Kingdom Tasks *Why this waystone has its name* and *Name this waystone after the settlement*; Server health and
  `/kingdoms waystones status`; `/kingdoms waystones backfill [apply]` for existing worlds, which never touches a named
  stone. A Book topic in English, Spanish and Chinese. See [Waystones integration](docs/Waystones-Integration.md).

### Durability and recovery

- **Settlement identity** (AUD-01): a new settlement, a merge and a kingdom change are written at once, before any event,
  dependent record or message, and refused if the write fails. Other stores follow merges and kingdom changes through a
  durable identity outbox, retried at start and every minute until each store has its records. Server health lists what
  still waits.
- **Deliveries and refunds** (AUD-02): items moving between a player's inventory and a public work are journaled with a
  marker in the player's own saved data. They count at once and are confirmed by the player's next save; after a crash,
  each is settled from the saved copy Minecraft loaded. Nothing is taken or refunded twice.
- **Construction** (AUD-03 to AUD-05): each block is journaled before it is placed and counts as built only after world
  saves show it; after a crash, each journaled block is settled from the world. Every placement rechecks the settlement's
  consent, the approver's authority in the settlement's current kingdom, protected homes, claims and the block there. A
  changed block stops the work with a stated reason instead of being placed again. Only cells proved to be the work's
  count as its blocks, and block states are compared in full (only neighbour-derived shape properties are normalized).
  A resurvey never builds over a newly occupied cell. A rollback restores only blocks still exactly the work's, under
  every protection mod, and reads each restore back; changed, protected and unloaded cells are reported, and the
  rollback stays open while any cell waits.
- **Charters** (AUD-09): a charter is sealed in the ledger first; its delivery is confirmed by the player's next save.
  A charter that never reached a saved inventory can be put back onto a blank charter from the same record.
- **Disabled features** (AUD-08): stopping or cancelling a work, withdrawing consent, protecting a home, rolling back,
  claiming refunds, revoking charters and the dynasty's cleanup decisions (withdrawing a claim, resigning or revoking
  a title) stay available while their feature is off; only new activity is refused, with the reason.

### Development records

- **Merges and kingdom changes** (AUD-06): works, assets and goods follow the surviving settlement, and authority comes
  from the settlement's current kingdom. Merged goods beyond the cap are kept as overflow, never discarded.
- **Assets and retention** (AUD-07): what each completed work produces and earned is kept as a productive asset, apart
  from the history of works, so pruning history or removing a template changes no output or points. Development
  requests are issued by the server with their issue time; once an old receipt is dropped, a request issued before it
  is refused as expired instead of being applied again. A full works register now says so instead of reporting a save
  failure.
- The store moves to schema 2; a schema 1 file is read as it is and written back as schema 2 at its next change.

### Discovery, bounds and reloads

- **Discovery** (AUD-11): a village bed, bell or workstation placed or broken schedules a rescan of its chunk, so a
  settlement built in loaded chunks is recognized without reloading them. Players are swept in turn, and *Look for a
  settlement around me* asks for the loaded area now, at most once every 30 seconds. The queue is bounded (16,384
  chunks), deduplicated and fair between dimensions; requested scans go first.
- **Bounds** (AUD-12): settlement bounds are limited to 16,384 blocks a side before anything is allocated. A saved record
  beyond that is kept for repair and reported, not indexed by position; very large settlements are found without
  per-chunk index entries.
- **Townstead mappings** (AUD-13) are staged during a reload and published only when the reload succeeds; a failed
  reload keeps the previous mappings.

### Interaction

- **One capability model** (AUD-14): task availability, start-view features and summaries come from the same checks,
  and a check that cannot be answered shows as *unknown* with its reason instead of available or unavailable.
- **Reviews** (AUD-10): a review is stale only when a part it shows changed, and names that part; unrelated activity on
  the server no longer invalidates it. *Check this action* reads the live state of a waiting operation.
- **Townstead tasks** (AUD-15): village life, work and news are listed under Places, government and services under
  Government, and *Check a Townstead career* under Guilds. The career check offers Townstead's professions and skills by
  name, asks for a tier only for a profession, and says it cannot check right now, not "not qualified", when Townstead's
  answer is unavailable.
- **Pinned task** (AUD-16): a pinned task's status is cleared on disconnect, so another server never shows it.
- **First session** (UX-01): inside a settlement, the start view's second action follows what the server offers: its
  War Room where conflict is on, otherwise its village life when Townstead reads it, otherwise how it is developing.
  Outside one, it offers *Why not here?* and *Look for a settlement around me*, and says when a look at the area is
  already on its way. *Where you are* now comes before the introduction, so it shows without scrolling at large GUI
  scales.

### Documentation and testing

- Capacity, configuration, durability, upgrade, political usage, README and compatibility pages corrected to the
  implemented behaviour (AUD-17), with the matching Book and task text in English, Spanish and Chinese.
- `tools/test/run_gametests.py` uses the run directory Gradle actually uses, starts each run in a fresh directory with
  a pinned seed, and judges the result from that run's own log (AUD-18).

## 0.3.0 — unreleased

Reliability, recovery and documentation repairs from the 27 October 2026 review, plus two approved additions: verified
player residence registration, and optional political notices with office badges. Clients and servers must update
together: the main network channel is now protocol 7. Upgrade, downgrade and recovery steps are in
[Upgrade and recovery](docs/Upgrade-and-Recovery.md). Every finding's disposition and tests are in the
[completion ledger](docs/Completion-Ledger.md).

### Dynasty, heraldry, MCA Capitals and development (audit handoff)

Everything here is off or inert on upgraded worlds until a leader, official or operator chooses it; see
[Migrating to 0.3.0](docs/migration-0.3.0.md) and the [audit implementation status](docs/audit-implementation-status.md).

- **Laws of succession** (new Succession tab on the Kingdom page, *Adopt a law of succession*). Every government
  starts with **Manual only**, the 0.2.0 behaviour. The other laws: the named heir, then children, grandchildren and
  siblings from MCA's family tree; equal primogeniture; council confirmation of the heir; elective monarchy, which
  leaves the throne to an election; or a data-pack law. A vacancy
  opens one succession that survives restarts. Adult NPC heirs are seated, player heirs accept or decline the crown, and
  minor heirs wait under a regency until their adulthood is verified. An elected ruler or elected successor is never
  replaced by lineage, and evidence that cannot be verified waits for review instead of being guessed.
- **The court**: ceremonial ranks that each kingdom names in its own style; claims to contest the order, be
  recognized in the ruling house or be restored after renouncing, decided by an official and kept; an explainable
  legitimacy reading that changes no authority; memorials, and a day of mourning shown on pages and darkened banners.
- **Physical heraldry**: kingdom banners, heraldic shields and court noticeboards that stand for the kingdom where they
  are placed; a seal press and kingdom charters. A charter is official only while the ledger holds what it proves,
  and reads as historical, revoked or unverified otherwise. It never grants authority by itself.
- **MCA Capitals**: each kingdom whose village is a governing Capitals capital has its crown governed by Capitals;
  its civic offices, agreements, guilds and honors continue here, and other kingdoms are unaffected. Capitals is only
  read. Operators review mappings with `/kingdoms capitals report|accept|release`.
- **Settlement development** (`development.mode`, **off** by default): public works funded with real deliveries,
  taken once and refunded if a work is cancelled; small works built block by block on a surveyed plot in settlements
  that allow it, never over protected land, beds, storage, doors, redstone or workstations, never loading a chunk, and
  with rollback; an optional abstract goods ledger with capped abstract trade that never creates or moves a real item.
  A Development tab, eleven Kingdom Tasks, and `/kingdoms development` and `/kingdoms economy` commands.
- **Book and screens**: six diagrams with localized descriptions; history filters by kind, date and visibility; pin one
  task to the top of the screen (with a key you can bind); the Ledger shows a known place's direction and a sketch of
  the known places around you. On a short screen the Kingdom page's nine tabs become one Section picker, so the record
  keeps its room.
- **Data packs**: succession laws, court ranks and development templates; `heraldry_secondary` and `heraldry_motto`
  kingdom metadata; `/kingdoms definitions diagnose|validate|explain`. The example pack shows each.
- **Stabilization**: the R3 siege fixture no longer rewrites config mid-run (20 consecutive packaged runs); a Recruits
  transfer's durability check now saves only the unit's chunk and Recruits' own files (p99 21 ms for 100 units, from
  hundreds of milliseconds); a provider matrix is logged at every start; every store uses one atomic save policy.

### Recruits: Living Villages integration

Optional, and active only with Recruits 1.15.2 and the audited Recruits: Living Villages 1.1.0 jar (checked by SHA-256).
Without it, or with another build, nothing changes and saved bridge records stay read-only. See
[Living Villages integration](docs/Living-Villages-Integration.md).

- **One settlement per village**: existing worlds are imported in bounded, resumable batches. A village inside an
  existing settlement is that settlement, keeping its id, name, kingdom and history; two possible settlements wait for an
  operator; nothing is matched by name. A player faction whose Ledger is not placed is a realm only.
- **Realms**: each Living Villages faction is a realm that keeps its id when its capital falls. Control and the
  recognized government are recorded separately, so a conquered village is occupied, not renamed or reassigned.
- **Wars**: Living Villages still fights them. Before it declares, besieges, starts a battle or lets its AI choose a
  target, one preflight applies MCA: Kingdoms' warfare switches, agreements, truces and protected capitals. A refused
  target is passed over. Each war is one durable conflict; conquest is acknowledged after the save that wrote it, and a
  war Living Villages forgets at a restart continues as an abstract siege.
- **Agreements between realms**: peace, ceasefire, alliance and cession, spoken for by a player-led realm's leader, a
  kingdom's ratifying officials through an explicit operator association, or an AI realm by a recorded policy. Living
  Villages' relation step cannot overturn an agreement in force.
- **Residents**: the people of integrated villages stay in the world when players leave, workers are promoted in place,
  and the people Living Villages would trim, turn away as strays or clean up are kept. With MCA Reborn, new residents are
  MCA people.
- **Construction**: Living Villages does not found a natural village or place a new building on another settlement's
  ground, a protected home or someone's standing bed, chest, furnace or other block entity (`protectExternalBuildings`).
- With MCA Reborn 7.7.x, new residents of integrated villages are MCA people too: MCA's villager factory is looked up
  under MCA 7.7's new package as well as 7.6's (before, they fell back to the provider's own villagers).
- Ordinary villages can be placed under Living Villages management deliberately, with construction held; supply
  deliveries; 23 Kingdom Tasks, a War Room section, a Ledger link, a Book chapter, and `/kingdoms livingvillages`.
- Server health and the provider matrix (`/kingdoms integrations`, the start-up log) report the integration. The Kingdom
  Tasks index now sends each distinct unavailability reason once, which keeps the larger catalogue well inside its
  payload budget.

### Townstead 0.8.0 integration

Optional. Townstead 0.8.0 is read through its public `api.v1`; Townstead 0.7.6 keeps working through its older facade,
and nothing changes without Townstead. Townstead is never written. See
[Townstead 0.8.0 integration](docs/Townstead-0.8.0-Integration.md).

- **One backend per session**, chosen at start (`v1`, `legacy`, `mca` or `none`), with every capability probed member by
  member and reported with its state and reason (`/kingdoms compat townstead status`, Server health). A capability
  whose Townstead system the world switched off reads `DISABLED` and recovers live when it is switched back on.
- **Buildings with completion**: Townstead's effective types, families and tiers, and MCA's own completion record. A
  new recognition or guild chapter needs a complete building, and the Development page names unfinished buildings
  without counting them; a recognized building that becomes incomplete suspends its institution
  (`BUILDING_INCOMPLETE`) and keeps its charter and history. Unloaded or unreadable
  villages mean "wait", never "removed", and nothing is rebound on its own. Townstead's change events refresh a
  settlement promptly; the bounded fair poll catches the rest.
- **Village life, Village work, Village news and Government and services** pages (Kingdom Tasks and the Village
  Ledger): residents and coverage, needs read honestly (switched-off needs left out, heat and cold both distress),
  spirit, culture, worksites and advisory storage counts, Townstead's village digest, and who governs each part of a
  settlement. Optional civic-aid suggestions point at existing tasks. The Blueprint header shows the local-government
  answer and links to Kingdom Tasks.
- **Who governs what**: one resolver per settlement and domain (crown, local government, guilds, names, arms). MCA
  Capitals' crown policy applies unchanged. Where both mods keep a local government, the conflict is reported and
  nothing is merged; operators preview and then adopt, release, clear or acknowledge with
  `/kingdoms compat townstead authority`. Decisions and restored-backup baselines live in
  `mcakingdoms_townstead.dat`. Political delegation is unavailable: Townstead 0.8.0 accepts no external government.
- **Careers**: data packs can make guild services require a Townstead profession at a tier, one of several professions,
  or a learned skill; *Check a Townstead career* explains a requirement. Qualification reads only.
- **Where a guild contact is**: with no contact in reach, a guild's requirements and page name this settlement's own
  contact, their distance, and what Townstead's hangouts or schedule say they are doing. Nobody is moved or woken.
- **Village news**: an institution recognized, an agreement concluded and a public work completed are told to Townstead's
  Chronicles once each, only with a player present, and never read back into rewards (`chronicleProjection`).
  `PublicWorkCompletedEvent` is posted after a public work is committed.
- **Data-driven mappings** under `data/<namespace>/mcakingdoms_townstead/` (institutions, careers, culture hints,
  suggestions, livery, Chronicle triggers, reactions), validated per file and against the installed Townstead after each
  reload. An opt-in heraldry fallback paints only plain guard shields (`heraldryFallback`, off by default).
- One adulthood rule for officeholders, guild contacts and dynasty checks (Townstead's life stage, else MCA's age state,
  else vanilla's baby flag).
- The Townstead civic header channel is protocol 2: clients and servers must update together.
- 14 new `TownsteadCapability` constants (appended), `TownsteadCapabilityState`, building evidence and availability,
  village-life, needs and career views, `systemEnabled`, the detailed `TownsteadBuildingsChangedEvent` states,
  `InstitutionView.Status.BUILDING_INCOMPLETE` and three Townstead `ProgressionPredicate` kinds. The API jar still
  contains no Townstead types; the compile-only Townstead `api.v1` jar is built from Townstead's tagged source, pinned by
  SHA-256 and checked member by member against the published 0.8.0 jar.
- Book of Kingdoms topic **Townstead villages**; 13 Kingdom Tasks; English, Spanish and Simplified Chinese throughout.

### New for players

- **Register my residence** and **Move my residence** in Kingdom Tasks (and `/kingdoms residence status|register|move`).
  The server verifies its own evidence: your respawn point set by sleeping in a bed, in a loaded chunk, inside a
  recognized settlement, while you stand there. Client coordinates, the world spawn, discovery and passing through are
  never proof. The review shows the current record, the evidence, the origin rule and any wait, and names the reason
  and the next step for any condition that is not met. A move waits
  `residenceMoveCooldown` (24,000 ticks of server time by default). Registering the settlement you already have does
  nothing. Residence grants no office, land, NPC home, guild membership or war duty, and an open election is never
  rewritten. A fresh survival player can now register and found their kingdom's first government without an operator.
- **Political notices** (optional, off until each player turns them on under Settings → Politics). Foundings,
  accessions, vacancies and a memorial for a confirmed death come from the committed political record, once each, in
  neutral words that keep each constitution's own titles. Private mandates and proposals are never announced. A local
  office is announced only to players who know its settlement. They appear as a chat line, optionally with a silent
  banner.
- **Office badges**: a small line above public office holders near you, for example "♛ Leader of Serenum", with "+N"
  for further offices. Badges are sent only for entities your client tracks and cleared on dismissal, succession,
  unload, dimension change and disconnect. Names, custom names, skins and equipment are never changed. Person pickers
  and the council list name offices too. Server health notes that Townstead offers no political narrative bridge, so
  notices present this mod's own record.
- Kingdom Tasks results are now truthful. A pending, refused or unconfirmed operation is no longer shown as done, and
  **Check this action** reads the owner's current state without sending the action again (warfare, recruit transfers,
  world events, the evolving world and government).
- Inspect and cancel tasks stay reachable when the feature that creates those records is switched off. Native verify and
  restore tasks need only their provider.
- A recruit transfer offer that nobody acts on now expires after its deadline. Both players are told, nothing native
  changes, and the recruit is free to be offered again.
- The Book's kingdom gallery stops after three attempts and offers **Try again** instead of retrying on every frame. It
  refreshes once when the server reports a change: your own discovery, or a definition reload.
- Election, regency, transition and civic results arrive as translation keys and show in your language.
- A task without questions (such as Register my residence) no longer shows an empty "Your choices" heading or an
  "Edit an answer" hint on its review.
- New Book topics: *Register your residence*, *Political notices and office badges*, and, for operators, *Capacity and
  record maintenance*. Existing topics on residence, transfers, server health and saves were updated in all three
  languages.
- The Chinese text uses one term each for guilds (行会), settlements (聚居地), opportunities (机会), recruits (新兵) and
  transfers (移交) throughout the Book and the interface.

### Saves and recovery

- A player's civic identity survives death and returning from the End (`PlayerEvent.Clone`). Only Kingdoms' own key is
  copied.
- The settlement store is validated as a whole: records, ids, bounds, redirects (cycles, dangling targets, live
  sources) and external references. A file this version cannot read completely is refused and the server stops, with
  the file left exactly as it was. Previously such a load could start over and overwrite the save.
- A failed Ultima Kingdoms carry-over now stops the world before any store exists, instead of starting that store
  empty. Successful copies are kept and resumed. `world/data/mcakingdoms_migration_report.txt` lists each step.
- Evolving-world announcements are sent only after the opportunity is saved.

### Fairness and scale

- Political deadlines (agreements, petitions, elections and regencies) are maintained per domain, ordered by deadline
  and bounded per pass. One busy domain can no longer starve the others.
- Evolving-world activity is credited from players' actual presence, not from consecutive samples, so eight populated
  regions all advance under a budget of four. Digests go oldest first.
- Settlement pages reuse one sorted snapshot per change: 25,000 settlements page in about 35 ms instead of about 10 s.
  Ledger search totals count every match you can see.
- Political and civic reviews are voided only by changes to the records, governments, definitions and authority they
  name.

### Operators

- **Capacity**: Server health and `/kingdoms maintenance capacity` show how full every bounded record collection is,
  with what relieves it. Finished recruit transfers and opportunities can be archived to
  `<world>/mcakingdoms_archive/` with `/kingdoms maintenance compact transfers|opportunities`, a dry run until
  `apply`. Archives are written before anything leaves the store. Used evidence is never compacted, and nothing is
  deleted automatically. See [Capacity and record maintenance](docs/Capacity-and-Maintenance.md).
- **MCA Quests contract**: Server health reports whether paid workshop jobs can complete: `available`,
  `legacy_consumer_contract`, `foreign_consumer_namespace` or `missing_institutional_methods`. A provider without the
  `mcakingdoms` institutional consumer is refused before any offer, not after payment.
- New settings: `civicIdentity.playerRegistration`, `civicIdentity.residenceMoveCooldown`, `politics.politicalNotices` and
  `politics.officeBadges` (common); `politics.notices`, `politics.officeBadges` and `politics.officeBadgeDistance` (client).
  See the [configuration reference](docs/Configuration.md).

### Compatibility and tooling

- The README's compatibility table separates the metadata range, the validated jar and the public source.
  `tools/test/companions.json` records the exact companion jars used for acceptance, by SHA-256, and
  `tools/test/verify_companions.py --pack` checks an instance against it. The public MCA Quests 1.7.1 source does not
  carry the institutional consumer that paid workshop jobs need; the validated pack jar was built from unreleased work.
- Acceptance and client scripts read their prerequisites from `MCAKINGDOMS_*` environment variables or an ignored
  `tools/test/local-environment.json` (`python3 tools/test/environment.py` reports them). They no longer use paths from
  the maintainer's machine. Historical provider-patch verification moved to `tools/history/`.
- The R3 acceptance fixture sets `militaryActions = false` before launch and asserts the native siege flag at once,
  instead of toggling the setting mid-run.
- `tools/test/companions.json` lists Recruits: Living Villages 1.1.0 by SHA-256, and `verify_companions.py` reads a
  `modId` followed by a TOML comment. The packaged `lv` phases of `tools/test/integration_runtime.py` cover the
  integration, its restart, removal, reinstallation, a future-schema store and the refusal of an unaudited jar.
- `companions.json` records Townstead 0.8.0 and MCA 7.7.1-beta.5 by SHA-256 and certifies the Townstead 0.8.0 tuple. The
  packaged `townstead` phase covers the 0.8.0 tuple (live switches, completion, authority, one Chronicle story, the
  example data pack), its restart, a boot without Townstead and the restored install, and runs on 0.7.6, MCA alone and
  no companion. Townstead 0.8.0 beside MCA 7.6.26 is refused by Forge, as Townstead's own metadata requires.
- The pack phases also pass on MCA Reborn 7.7.1-beta.5 with Townstead 0.8.0, and MCA Capitals 1.3.7 (which needs MCA
  7.7.1) passes the packaged `capitals` phase there; it was checked by class inspection only before. Acceptance fixtures
  find MCA's package under 7.6 or 7.7, give recognized buildings the blocks MCA's completion counts, and define the
  `workshop` building type, which no MCA or Townstead release defines.

### Documentation

- New: [completion ledger](docs/Completion-Ledger.md), [capacity and record maintenance](docs/Capacity-and-Maintenance.md),
  and the [upgrade and recovery runbook](docs/Upgrade-and-Recovery.md). The runbook covers runtime old-name aliases
  versus on-disk migration.
- New: [Living Villages integration](docs/Living-Villages-Integration.md), and the `[livingVillages]` settings in the
  configuration reference.
- New: [Townstead 0.8.0 integration](docs/Townstead-0.8.0-Integration.md) (ownership, tested tuples, operator recovery,
  API limits) and the [Townstead upstream API proposal](docs/Townstead-Upstream-API-Proposal.md); the Townstead settings
  and mapping formats in the configuration and datapack references.
- README, CurseForge text, configuration reference, compatibility table and MODMAP updated together. Historical reviews
  and audits are labelled as such.

### Known limitations

- Collections other than transfers and opportunities still refuse new records at their bound. Their offline procedure
  is documented; reference-aware compaction is future work.
- Old-name runtime aliases remain until the companion contract is published and tested.
- MCA Capitals is read only: delegating crown actions to Capitals waits for a public mutation API.
- Townstead: political delegation, a shared rename, a heraldry provider, actorless or idempotent Chronicle events, and
  procurement, storage or career-reward transactions all wait for Townstead contracts (see the
  [upstream proposal](docs/Townstead-Upstream-API-Proposal.md)). Until then conflicts are resolved by operator decisions
  on the MCA: Kingdoms side, names are shown side by side, and no reward or payment comes from Townstead data.
- Living Villages: Overworld only, as the provider. Its live battle does not survive a restart (the war continues as an
  abstract siege). Resident protection needs the production runtime. Living Villages 1.1.0 has no adoption API and no
  food shortage, so adoption creates the provider record directly and supplies are its currency and materials. Its own
  map markers still go to every player, and road grading between its own buildings is not checked.
- Not included: a JEI recipe hand-off, a Map Atlases link, physical caravans, inter-house mediation and the optional
  funeral contract (reasons in the [audit implementation status](docs/audit-implementation-status.md)).

## 0.2.0 — unreleased

Usability, navigation, localization and GUI improvements across the Book of Kingdoms, Village Ledger, Kingdom Tasks,
Guilds, government pages and War Room, including both usability implementation passes and the manuscript book overhaul.

### GUI and accessibility

- War Room, Guilds, every kingdom-management tab and Settings now use the same bounded manuscript folios, ivory paper,
  dark ink, leather controls, white labels, selected states and gilt focus cues as the books and Kingdom Tasks. Settings
  sliders, cycle controls and history-clear confirmation match too. The embedded Blueprint header, heraldry frames and
  settlement arrival banner use compact parchment plaques; arrival positioning and fade timing are preserved.
- Compact kingdom records keep mouse-wheel scrolling inside the detail pane when the pointer is over it, so reading
  a long record does not accidentally page the record list.
- Book, Ledger and Tasks gain quieter ivory parchment, distinct crimson/green/walnut bindings, high-contrast leather
  controls with white text, recessed inputs, clear selected/disabled/focus states and visible scrollbar grips. Each has
  its own framed illustration: the royal landscape, a riverside settlement or a guild workshop courtyard. Clipped button
  labels retain full tooltips; the Book banner preserves the illustration's proportions.
- The Ledger kingdom picker pages through every known kingdom at compact GUI sizes, and its settlement index shows
  where the visible rows sit within the current server page.
- Kingdom Tasks shares the Book and Ledger's parchment, leather binding, gilt dividers, ink headings and white controls.
  Its start page separates suggestions from favourite/recent tasks on wide screens and uses one scrollable column in
  compact windows. The catalogue, categories, record choices, text forms, edits, reviews, results, errors and Getting started
  use the same theme. Ctrl+F focuses search or the current input; long hints are clipped with an ellipsis and a full tooltip.
- Operational screens use vanilla Minecraft widgets, tooltips, narration, keyboard focus and scrolling. Task and record
  layouts provide labelled fields, contextual Help, clear empty states and controls for content beyond the first page.
  All Kingdoms-owned screens share the reusable manuscript theme while retaining native control and narration behavior.
- Book layouts adapt to GUI scale and resolution without shrinking the Minecraft font. Tab/Shift+Tab reach controls and
  article links, Enter/Space activate them, and mouse scrolling, scrollbar dragging and keyboard navigation remain available.
  Recipes, diagrams, requirements and kingdom heraldry have text equivalents for narration.

### Manuscript books

- The Book of Kingdoms and Village Ledger share textured parchment, leather bindings, page edges, gilt corners,
  ink typography, illustrated openings and original item sprites. Wide layouts show facing leaves; compact layouts
  preserve normal-size Minecraft text on a single leaf.
- The Book adds chapter ribbons, a bookmark ribbon, separate reading-page and topic controls, page-turn sounds,
  Ctrl+F search and Alt+Left history. Following a cross-reference switches to its chapter; Back restores its origin.
- The Ledger keeps its index beside the selected record, with illustrated field notes before a settlement is selected.
- Guild service, government founding, world evolution and military declarations gain localized flow illustrations.
  Live crafting grids remain server-driven, with full recipe narration and less redundant prose on the page.
- Book input text, search placeholders and button labels use white text with Minecraft shadows.
- Both item icons are angled closed books with visible parchment edges: a crimson chronicle with a gold crown and
  red bookmark, and a green ledger with a brass compass and leather strap. The transparent sprites stay at Minecraft's
  native 16-pixel item resolution.
- Shared components, asset provenance and runtime evidence: [Book interface design](docs/Book-Interface-Design.md).

### Usability

Two usability passes: U1–U11 (2026-10-23/24) and the visual guidebook plan UX-01–UX-12
([MCAKingdoms_Usability_Implementation_Plan.md](MCAKingdoms_Usability_Implementation_Plan.md), 2026-10-26). Per-package
record and validation in [docs/Usability-Implementation-Record.md](docs/Usability-Implementation-Record.md).

**Players: the Book, Kingdom Tasks and the screens (2026-10-26)**

- The Book of Kingdoms draws both crafting recipes as grids from the connected server's current recipes, with
  quantities, the station (inventory grid or crafting table), accepted alternatives and a text version for the
  narrator. A data pack that changes or removes a recipe shows as changed, or as unavailable on this server, once
  `/reload` finishes, even with the Book open.
- Book topics use numbered steps with links, labelled tips, requirements and consequences, comparison columns,
  diagrams of agreement and recruit-transfer stages, and a gallery of the connected server's kingdoms. New topics:
  *Two books, two purposes*, *Craft the Book of Kingdoms*, *Craft the Village Ledger*, *Kingdom Tasks, step by step*
  and *Kingdom, government and control* (70 topics in each bundled language). Text naming the Kingdom Tasks key shows
  the key actually bound.
- The Book has Back (to the same reading position), Home, Previous and Next topic, bookmarks and recent topics under
  Saved, a "Topic N of M" line, search ranked by title, keywords, summary and text, command references folded on
  player topics, links reachable with Tab and Enter, and a Contents / Article switch in narrow windows.
- Kingdom Tasks opens on a start view: where you are, up to three suggested next steps (a consent or signature waiting
  for you, an open vote, the settlement here, a nearby guild contact), favourite and recent tasks, and this server's
  features. **Categories…** groups tasks as Places, Guilds and jobs, Government, Exploration, Conflict, World events
  and Help; goal phrases such as "paid work", "make peace", "become ruler" and "where am I" find their tasks; tasks
  this server has turned off are marked in the list.
- Tasks list their conditions before you start — guild thresholds for members and independents, contact range,
  the responsibility an action needs in a kingdom, a shared transfer destination, each requirement of resident
  founding — as ✓ Ready, ✗ Needed or ? Could not check. The confirm button names the action ("Join Lamplighters",
  "Send agreement proposal", "Confirm troop transfer"); its result says Done, Waiting, Not done (with the reason) or
  Not confirmed, and offers Check this action, the same task again and related tasks. A read-only task simply shows what
  it read, with Read again. Error pages can copy support details to the clipboard.
- The Village Ledger searches known settlements by name or former name, sorts by name or distance, filters by kingdom
  from a heraldry picker, keeps the selected settlement by identity when the list refreshes (and says so when a rename,
  merge or filter removes it), and shows details with links to the settlement's government, guilds, War Room and tasks.
- The Guilds screen shows membership, standing and rank progress, every service with its member and independent
  requirements, the nearest contact, guild jobs and recent service. Joining, leaving and using a service open the
  reviewed Kingdom Tasks steps instead of acting at once.
- Kingdom pages open with the kingdom's state, leader, capital, regency, open elections and your responsibilities;
  records are labelled fields with their own actions (sign, review, revalidate), history rows are dated, and each tab
  has an empty state and a Help topic. The War Room separates control, campaigns, orders and civilian work, and marks
  each action this server cannot run, such as military actions without Recruits, before you open it.
- An **MCA: Kingdoms settings** screen (Kingdom Tasks → Settings, or Mods → Config) sets the entry title with a live
  preview, what the Tasks key opens, the start view introduction and recipe animation, and clears bookmarks and history.
- The welcome message and advancement name the Kingdom Tasks key as bound.

**Players: earlier usability work (U1–U11)**

- Kingdom Tasks has a **Getting started** panel: three read-only first steps (Read this settlement, Your known settlements,
  Read a kingdom), both recipes, what MCA Reborn adds, why guild jobs need MCA Quests content, how reviews work, and a
  Controls shortcut. The Kingdom Tasks key (K by default) has its own MCA: Kingdoms category in Controls.
- The Village Ledger recipe appears in the recipe book once a book or compass is held, like the Book of Kingdoms.
- Search matches every word in any order, ignores case and accents, and knows a few synonyms (village / settlement /
  town, in three languages). The task list is searched in the player's own language, resource packs included. Empty
  results say whether nothing matched the search or nothing is available, and offer Clear; focus and page are kept
  across selections and refreshes.
- Forms show bounds and units before you type (settlement radius 16–512 blocks), number their steps, keep a rejected
  entry for correction, and keep earlier answers when you go Back. Reviews offer **Edit** for each answer; answers
  that depend on an edited one are asked again, and the old review can no longer be applied.
- An unavailable feature says why — turned off, a companion mod missing or unsupported, content missing, a requirement
  not met, changed meanwhile, a participant offline, a result pending, saved data read-only — and what to do.
  **What is available here?** lists the server's features and your own access. Chat notices name the exact task
  that reviews them.
- **Resident founding works**: with `politics.openFounding` on (the default), a player whose recorded civic residence
  is in an ungoverned kingdom can found its first government from Kingdom Tasks or `/kingdoms politics bootstrap`, with
  their residence as capital and themselves as leader. **Check whether you can found a government** explains each case.
- Book links open tasks the reader can use; a topic about an operator task offers a related player task or says it is
  for operators. The entry title waits while a screen or F1 hides it and is dropped after leaving the settlement or
  changing dimension.
- Spanish and Simplified Chinese now cover every task title, help text and field, choice and enum labels, political
  rows and history, War Room lines, notices, capability explanations, and all 70 Book of Kingdoms topics.

**Operators**

- **Server health** (task and `/kingdoms health`): switches, providers and bound capabilities, saved-data writability,
  the last reload, this start's Ultima migration (copied / skipped / failed), and reconciliation work queued and done.
- A definition reload reports each domain as accepted or rejected, names the file, field, found value and expected form,
  and says which domains kept their previous definitions. A rejection is no longer reported as success.
- **Reconcile nearby village records** task; the merge review shows the kept and retired records, locks, what moves and
  what blocks it; assignment evidence shows the structure identity. `/kingdoms help [topic]` prints command syntax.
- `kingdomGating.unknownKingdomPolicy` now applies to MCA: Kingdoms' data pack gates and MCA Quests conditions that omit
  `when_unknown`; it was documented but never read. `cacheBySettlementRevision` and `settlementReassignmentPolicy` are
  documented as reserved and informational. Every config key has a comment; Forge refreshes comment text in existing
  files on the next start, and no value changes.

**Modpack creators, datapack authors and addon developers**

- README: first-session route, guides by audience, one compatibility table, an upgrade runbook. New
  [configuration reference](docs/Configuration.md) with peaceful and warfare [presets](docs/presets/README.md),
  [datapack reference](docs/Datapack-Reference.md) with a tested [example pack](examples/datapack/README.md) and
  invalid variants, [addon API guide](docs/Addon-API.md) with an [example](examples/addon/README.md) compiled by
  `check`, and `KingdomsService` contracts in Javadoc.

**Fixed**

- The Book said a village cluster needs three points of interest (five), evolution regions had to be chosen one by one
  (eligible automatically unless paused) and only operators could found governments, and it named tasks that had been
  renamed. The R1 design record gave the contact range as 2–16 blocks (2–32). The CurseForge page gave the MCA range as
  an exact version and omitted the Book recipe and Kingdom Tasks.
- *Browse paid workshop jobs* said MCA: Crime was optional; the service requires its legality check and MCA Quests.
- Text answers prefilled by another screen were dropped, and Back erased the previous answer.
- Joining or leaving a guild in Kingdom Tasks was refused as "changed since your review" whenever any other player
  joined or left a guild in the meantime; the review now depends only on your own memberships and the guilds defined.
- Synonyms no longer match inside other words ("job" found "Request neutral resource access" through "quest").
- Guild commission refusals shared one message for several causes; each cause now has its own.
- More than 300 task strings, most of them political, had no language entries; agreement clauses, guild progress, campaign,
  accord and deployment choices, and the evolving-world digest were English-only.

**Compatibility**

- Network protocols: main channel 6, `interactions` 4, `civic` 4, `politics` 4, `war_room` 4. Client and server must
  run this build. The `civic` channel is read-only; guild actions go through Kingdom Tasks.
- Book of Kingdoms resource packs in the old format (schema 1, `body` paragraphs) still load. Schema 2 adds typed
  blocks; the [datapack reference](docs/Datapack-Reference.md#book-of-kingdoms-pages) documents both.
- `mcakingdoms-client.toml` gains `onboarding.showIntro`, `tasks.openTo` and `guide.reducedMotion`; bookmarks,
  favourites and recent topics and tasks are kept in `config/mcakingdoms-client-state.json` on each client.
- Saves, data pack schema 1, server config keys and values, and public API signatures are unchanged (the API gained
  Javadoc).

## 0.1.1

Implementation of the findings in [docs/MCAKingdoms-Audit.md](docs/MCAKingdoms-Audit.md), and of the
2026-10-01 review ([REVIEW.md](REVIEW.md), [remediation plan](docs/MCAKingdoms-Review-Remediation-Plan.md)).

### Renamed

- Ultima Kingdoms is now **MCA: Kingdoms**: mod id `mcakingdoms`, package `dev.otectus.mcakingdoms`,
  commands under `/kingdoms`, config files `mcakingdoms-*.toml`, saved data `mcakingdoms_*`.
- A world and configuration from Ultima Kingdoms carry over automatically (README, "Upgrading from Ultima
  Kingdoms"): saved-data files are copied with their `ultima_kingdoms:` ids rewritten, config files are
  copied to their new names, a villager's civic identity moves to its new key when first observed, the
  welcome flag is honoured, Book of Kingdoms and Village Ledger items are remapped, and old slugs still
  resolve. Old files are never changed or deleted. Advancement, recipe-book and statistics progress under
  the old namespace is not migrated. A user-written explanation that happens to contain `ultima_kingdoms:`
  inside saved data is rewritten too.
- The companions bind the new id from MCA: Quests 1.7.1, MCA: Reputation 0.6.1, MCA: Conversations 1.8.0
  and MCA: Crime 0.7.5, and recognise the old Ultima Kingdoms jar for one release. Older companion builds
  cannot see this mod: their Kingdoms integrations stay off without errors.
- The completion-receipt consumer is the one MCA: Quests names (`McaQuestsApi.institutionalCompletionConsumer()`,
  `mcakingdoms:regional_civic_network`), so the two mods cannot disagree on it again; MCA: Quests migrates
  receipts addressed to the pre-rename id.

### Changed

- Kingdoms menus now use Minecraft's native scrolling text fields and standard 20-pixel task
  buttons, with full task descriptions in vanilla tooltips and narration. The ledger has a
  standard Done button and scrollable settlement details; political Previous/Next controls
  also reach records that do not fit on the current screen. The entry banner uses the vanilla
  advancement-toast texture and unscaled text, retaining heraldry, timing, position and visit rules.
  Existing actions, translations and server protocols are preserved. See the
  [GUI audit](docs/MCAKingdoms-GUI-Audit.md) for the inventory and validation.

- The optional companion ranges in `gradle.properties` are lower bounds only: `mcaquests [1.6.5,)`,
  `mcacrime [0.7.5,)`, `mcareputation [0.6.0,)`, and Townstead `[0.7.6,0.9)` to match MCA: Quests
  (0.8 adds `api.v1`; 0.9 has not been seen). Forge enforces an optional range when the mod is
  present, so the old upper bounds would have refused to launch with the next Crime, Reputation or
  Townstead release even though every one of those bridges already fails closed on API drift.
  Recruits is `[1.15.2,)` on the same grounds: the integration is audited for exactly 1.15.2, and that is
  enforced at runtime rather than by refusing to launch. `RecruitsMixinPlugin` applies the Recruits
  mixins only to 1.15.2 and every Recruits adapter refuses any other version, so a newer Recruits now
  launches with the integration off and one warning naming the installed version.
  `ModsTomlRangesTest` fails the build if an optional dependency is pinned to one exact version.

### Fixed

- Only villagers and entities tagged `mcakingdoms:civic_residents` receive automatic civic residence; hostile mobs and livestock no longer become residents.
- Recruit transfer confirmation flushes entity and overworld data instead of saving every dimension, and repeated verification is throttled.
- Political request receipts expire after a game day and are pruned during deadline maintenance, so the receipt store no longer fills permanently.
- Kingdom Tasks results name people and places instead of printing identifiers, and the protocol no longer rewrites result text by pattern matching.
- An elected successor or leader cannot be displaced by appointment authority; election winners are recorded as elected mandates.
- Evolving-world scenarios find institutions beyond the first page; withdrawing a non-receipt contribution allows contributing again.
- Campaign declaration and accord signature report a pending native application instead of an error after the record was committed; withdrawal restores the relation that stood before the declaration.
- The ledger and settlement selectors explain when settlement discovery is read-only; an unwritable discovery file no longer stops the server.
- Village point-of-interest clusters are grouped by proximity, but that alone did not stop a large village, or one on
  high ground, from registering as several settlements with their own names and kingdoms. The village identity
  changes below do.
- The legacy government screen channel is read-only; all mutations go through reviewed Kingdom Tasks. Its
  query no longer carries a request at all (politics channel protocol 2).
- Townstead is optional for the civic network: building evidence falls back to MCA's own village buildings
  when Townstead is absent, so chapters, workshop contacts and institution recognition work with MCA alone.
- Political NPC evidence and institution recognition no longer require exactly Townstead 0.7.6: they need
  the Townstead capabilities they read, probed member by member, so a Townstead release inside
  `[0.7.6,0.9)` that keeps those members works. Start-up warns when the installed Townstead is not the
  validated one.
- Organization lifecycle results, progression reasons, scoped-contract refusals, the evolving contract kinds
  and the MCA: Quests condition description have English text instead of raw keys. `LangCoverageTest`
  fails the build when a named key has none.
- Honor petitions grant the honor the kingdom definition names (`petition_honor` metadata) instead of a
  fixed table, so datapack kingdoms award their own.
- The commission petition, which every submission refused, is removed; paid commissions are reached through
  recognized workshops. The overview page reports the providers' actual status instead of a fixed line.
- Guild and contract contacts share one distance, `contactRange` (now up to 32 blocks): Kingdom Tasks lists
  only contacts within it, and contract offers, acceptance and turn-in check the same value.
- Kingdom Tasks explains a mistyped number or a stale choice in words, and an unexpected failure no longer
  shows Java exception text.
- World evolution no longer writes its save on every evaluation when only the schedule moved.
- Development runs apply the mixin config (`-mixin.config`), which Forge 47 otherwise reads only from the
  packaged jar's manifest.

### Changed

- The settlement-entry title appears only the first time a player enters each settlement. Setting the client option `entryOverlay.firstVisitOnly` (default `true`) to `false` shows it on every entry again, and `entryOverlay.enabled` still turns it off. The server records each player's visits in `mcakingdoms_knowledge.dat`, apart from discovery, so being introduced to a settlement does not use up its title; a world from an earlier build shows each title once more. The main channel is version 5.
- Kingdom Tasks text is translatable (`task.mcakingdoms.*`, `interaction.mcakingdoms.*` keys with English fallbacks); the interactions protocol is version 2.
- Non-consequential tasks run as soon as their fields are complete; opening screens prefill the settlement they show; list rows show two lines; results offer "Same task again" and "Related tasks".
- Constitutional transition rules may choose a council-only or council-plus-residents electorate.
- Declaring a campaign, signing an accord and fulfilling or refusing a protectorate obligation adjust the actor's standing with the affected kingdom.
- Counterparts receive a chat notice when a campaign, accord, election, transfer, drama or protectorate awaits them, and new opportunities are announced to the government.
- A kingdom's own government may map and bind its native claims; a capital resident may found the first government (`politics.openFounding`); governed settlements are evolution regions by default (`autoEligibleRegions`).
- Every `/kingdoms-<domain>` command root is also available as `/kingdoms <domain>`.
- First login shows a welcome line and the Book of Kingdoms opens the task each topic describes.

### Village identity

- One generated village is one settlement. Every detector resolves to the village's structure start: a
  point-of-interest cluster, an MCA village or a manual registration inside a village's footprint (its structure box
  plus `structureMergeMargin`, default 48 blocks) belongs to that village, whichever is found first. Neighbouring
  villages stay distinct by their starts.
- Settlement records an earlier build split from one village are merged into it: as their chunks are scanned, once at
  server start (`reconcileOnStartup`, reading at most `reconcileStartupChunkBudget` unloaded chunks), and with
  `/kingdoms village reconcile [radiusChunks]`. The record holding the structure start survives, else the highest
  detection rank, else the oldest. A locked name or kingdom on a merged record carries over to an unlocked survivor;
  otherwise the survivor keeps its own and the other names stay searchable as aliases. Old ids resolve to the survivor.
  A merge that politics or warfare refuses is logged once, with the command to run once the conflict is resolved, and
  not retried until the server restarts. The start-up pass and the command each log one summary line; a scan logs one
  only when it merges or meets a new refusal.
- A merged fragment's residents now belong to the survivor's kingdom, which is the point of one village, one kingdom.
  Their recorded origin kingdom and any faction standing are not rewritten.
- Discovery and first-visit records of merged fragments fold into the survivor, so nobody sees a second first-visit
  title for one village. Campaigns, accords, sovereign decisions (where both had one, the survivor's stands), civilian
  contracts, notices, binding history, evolution regions, scenarios and cooldowns, protectorate pacts and obligations,
  dramas, civic chapter sites, appointments, introductions and route ends move to the survivor, and settlement ids
  saved before the merge resolve to it through the redirects. Townstead binding ids are recomputed for the survivor; a
  binding saved before the merge still resolves.
- A merge now takes the union of both settlements' bounds, up to eight times `defaultSettlementRadius` across.
- Point-of-interest detection measures distance horizontally, reads only points in loaded chunks, and gives a cluster
  bounds that follow its extent (16 blocks beyond its outermost points, at least 32 from its centre) instead of a fixed
  square. A cluster is considered only with `poiMinimumCount` points (default now 5), a bed or bell among them
  (`poiRequireHomeOrMeeting`) and a mean height no more than 24 blocks below the surface (`poiSurfaceOnly`). An
  existing config file keeps the value it already holds (the Ultima pack's holds 3); set 5 to apply the new default.
  Underground point-of-interest settlements recorded by earlier builds are not removed.
- New settlement discovery keys: `villageIdentity` (`SPATIAL` restores distance and overlap matching without merging,
  for troubleshooting), `structureMergeMargin`, `autoReconcile`, `reconcileOnStartup`, `reconcileStartupChunkBudget`,
  `reconcileChunkReadsPerTick`, `villageStructureTags`, `poiLinkDistance`, `poiRequireHomeOrMeeting`, `poiSurfaceOnly`
  and `candidateOverlapFraction` (0.5; it was a fixed 0.65).
- API: `KingdomsService.resolveSettlementId` and `registerVillageFootprintSource`, with `VillageFootprint` and
  `VillageFootprintSource`, for structure mods to describe their villages. `BoundCivicBuilding.forSettlement` and
  `TownsteadBindingPersistence.load(tag, resolver)` carry a binding over to a merged settlement.
- `/kingdoms village debug` shows the structure identity and the village footprint at the anchor. Registering a
  settlement inside a recognised village joins that village, and the command and Kingdom Tasks say so.
- The settlements file stays at schema 1 with one optional root key, `Reconciled`, so an earlier 0.1.1 build still
  opens it.

### Compatibility

- Checked against the current companion trees. MCA Quests 1.7.0 and MCA Conversations 1.8.0 ship their R1–R3 provider halves natively. After the rename, every reflective binding in both directions matches again with the companion versions named under "Renamed". The old private provider patches moved from `pack/r1`–`r3/providers` to [docs/history/provider-patches](docs/history/provider-patches/README.md).
- MCA Crime 0.7.5 now ships the workshop-legality (`InstitutionalServiceApi`) and jurisdiction (`JurisdictionPolicyApi`) providers. Without them, workshop commissions and native law enforcement stayed unavailable.
- MCA: Quests 1.7.1 compares an accepted quest's settlement through `resolveSettlementId` when this mod provides it, so
  a merge that retires the quest giver's settlement no longer fails a kingdom-bound quest on its own.
- Live faction synchronization requires MCA Reputation 0.6.1, the first release with the standing journal. With 0.6.0, local standing is read-only.
- Faction synchronization recovers on its own when MCA Reputation's standing journal lapses this server's cursor (`GAP`), which happens when synchronization was off, stalled or uninstalled for longer than the journal's retention bound (`standingJournalMaxEntries`). The skipped changes cannot be replayed: the cursor moves durably to where the journal's kept entries begin, one warning names how many were skipped, and the faction status line counts them (`source_gaps`, `source_skipped`). Until now a `GAP` stopped synchronization for good. Faction standing is deliberately not re-derived from MCA Reputation's standing baselines at that point, because a baseline already includes every change applied before the gap and importing it would count them twice.
- The Book of Kingdoms names these provider versions; "Check installed integrations" also lists MCA Conversations.

## 0.1.0

Initial release for Minecraft 1.20.1 and Forge.

### Added

- Persistent, server-authoritative settlement records with stable UUIDs, names, slugs, kingdom assignments, territory bounds, aliases, locks, and discovery provenance.
- Automatic village recognition from tagged village structures and configurable village-POI clusters, plus manual creation, discovery, merge, rename, locking, and reclassification commands.
- Six data-driven kingdoms: Serenum, Lunari, Madera, Anemosia, Shimaguni, and Yew, with biome rules, structure-style rules, name pools, heraldry, and Serenum as the default fallback.
- Schema 1 datapack definitions with validation and reload support.
- Village Ledger item and server-synchronized settlement browser with kingdom filtering and detail views.
- Configurable settlement-entry overlay with kingdom heraldry.
- Persistent NPC origin and residence identity, administrative citizen commands, dialogue-context values, and lifecycle events.
- Optional, exact-version MCA Reborn `7.6.26+1.20.1` integration for MCA village discovery and villager home evidence, isolated from MCA personal and family data.
- Public server API for settlement and civic queries, mutations, extension registrations, and Forge lifecycle events.
- Separate API and sources artifacts alongside the main mod jar.

### Compatibility

- Minecraft `1.20.1`
- Forge `47.x`; built with `47.4.23`
- Java `17`
- Optional MCA setup tested with MCA Reborn `7.6.26+1.20.1` and Architectury API `9.2.14`
