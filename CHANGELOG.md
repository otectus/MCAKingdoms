# Changelog

## 0.3.0 — unreleased

Reliability, recovery and documentation repairs from the 27 October 2026 review, plus two approved additions: verified
player residence registration, and optional political notices with office badges. Clients and servers must update
together: the main network channel is now protocol 7. Upgrade, downgrade and recovery steps are in
[Upgrade and recovery](docs/Upgrade-and-Recovery.md). Every finding's disposition and tests are in the
[completion ledger](docs/Completion-Ledger.md).

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

### Documentation

- New: [completion ledger](docs/Completion-Ledger.md), [capacity and record maintenance](docs/Capacity-and-Maintenance.md),
  and the [upgrade and recovery runbook](docs/Upgrade-and-Recovery.md). The runbook covers runtime old-name aliases
  versus on-disk migration.
- README, CurseForge text, configuration reference, compatibility table and MODMAP updated together. Historical reviews
  and audits are labelled as such.

### Known limitations

- Collections other than transfers and opportunities still refuse new records at their bound. Their offline procedure
  is documented; reference-aware compaction is future work.
- Some result sentences produced deep inside the political, mobilization and campaign services are still English.
- Old-name runtime aliases remain until the companion contract is published and tested.

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
