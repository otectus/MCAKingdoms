# Changelog

## 0.1.1 — unreleased

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
