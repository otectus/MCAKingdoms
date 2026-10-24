# MCA: Kingdoms village identity: diagnosis and remediation plan

Companion to `REVIEW.md` and `docs/MCAKingdoms-Review-Remediation-Plan.md`. Written 2026-10-18 for
the next session to execute. Every claim below cites `file:line` in this repository, in a sibling
repository under `/home/otectus/Projects`, or in a decompiled dependency (see the appendix for how
those were obtained). No code was changed for this document.

**Status: diagnosis verified against source and against three Ultima saves; plan not started.**

Ground rules carried over from the earlier plan: Forge 1.20.1 / 47.x / Java 17 only; version
strings stay in `gradle.properties`; registration on the MOD bus, gameplay on the FORGE bus; common
code never imports `client`; sibling mods keep binding Kingdoms by reflection; iterate with
`.mcmod-tools/gradlew-quiet.sh <repo> compileJava` and finish each repo with its full `check`.

## 1. The bug

Report: in the Ultima pack, one naturally generated village is treated as several settlements by
default. Expected: every naturally generated village is exactly one settlement.

### 1.1 What the saves show

The three most recent Ultima worlds (`~/Documents/curseforge/minecraft/Instances/Ultima/saves/`)
all contain the same pattern: one record created by the structure detector, plus one or two
records created by the point-of-interest (POI) detector whose anchors lie **inside** the structure
record's bounds. Values are from `data/mcakingdoms_settlements.dat` (schema 1, read with a plain
NBT reader; the dump is reproduced in appendix A).

| World (played) | Record | Detector / source key | Anchor (x, y, z) | Bounds X / Z | Created (tick) | Kingdom |
|---|---|---|---|---|---|---|
| New World (21), 2026-10-18 | Dawnfield | STRUCTURE `village_plains:-124:82` | -1977, 128, 1324 | -2052..-1903 / 1268..1380 | 422 | serenum |
| | Peacecross | POI `poi:-127:82` | -2029, 110, 1324 | -2093..-1965 / 1260..1388 | 440 | serenum |
| New World (20), 2026-10-03 | Thornwatch | STRUCTURE `village_plains:-63:12` (+16 POI identities) | -1000, 81, 204 | -1054..-947 / 118..289 | 422 | yew |
| | Bellmeadow | POI `poi:-62:9` | -989, 68, 145 | -1084..-925 / 71..238 | 441 | serenum |
| | Hartwood | POI `poi:-65:16` | -1032, 67, 263 | -1098..-949 / 177..335 | 449 | yew |
| New World (19), 2026-10-01 | Summerwell | STRUCTURE `village_plains:-135:-124` (+19 POI identities) | -2187, 96, -2004 | -2261..-2113 / -2081..-1928 | 422 | serenum |
| | Valegate | POI `poi:-135:-123` | -2151, 66, -1967 | -2223..-2065 / -2076..-1884 | 445 | serenum |
| | Elderbough | POI `poi:-140:-124` | -2237, 66, -1974 | -2318..-2153 / -2075..-1906 | 460 | yew |

Observations that matter for the diagnosis:

- Every POI fragment's anchor is inside the structure record's bounds, so each fragment is a
  piece of the same village. The fragments appear 18 to 38 ticks after the structure record,
  during the initial scan of the chunks around spawn.
- In world 20 the one village ended up in **two kingdoms** (Thornwatch and Hartwood in `yew`,
  Bellmeadow in `serenum`), because each fragment was classified on its own anchor and bounds.
  In world 19, Elderbough went to `yew` on a weighted coverage of 0.038 (its trace is in the dump).
- Peacecross (world 21) has anchor Y 110, which is the mean Y of the POIs it saw, while the chunk
  scan is centred at sea level (see RC2). The village sits on `regions_unexplored:highland_fields`.
- Each world also holds one or two POI-only records far from any village and deep underground
  (world 21 Greenford at Y 48 in `prismachasm`; world 20 Clearbrook at Y 23; world 19 Greenford at
  Y 30 and Sunvale at Y 27). These are not the reported bug but come from the same detector
  (RC5).
- No record carries an `mca` external reference yet in these young worlds, so the MCA detector
  had not contributed; the splits come from the two built-in detectors alone. MCA was attached
  (`logs/latest.log` line 94365 in the Ultima instance: `detection=true, civicEvidence=true`).
- The pack runs the default discovery config (`config/mcakingdoms-common.toml` in the instance:
  radius 64, POI search 48, POI minimum 3, 2 scans per tick, merge distance 32).

## 2. How discovery works today

1. Every chunk load is queued: `McaKingdoms.chunkLoaded` (`src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java:160-164`)
   calls `enqueueChunk`; at start the 9x9 chunks around each level's spawn are queued
   (`McaKingdoms.java:283-292`). `serverTick` drains the queue (`McaKingdoms.java:175-179`).
2. `KingdomsServiceImpl.tickDiscovery` (`src/main/java/dev/otectus/mcakingdoms/core/KingdomsServiceImpl.java:497-514`)
   takes `chunkScansPerTick` (default 2, `src/main/java/dev/otectus/mcakingdoms/config/McaKingdomsConfig.java:27-29`)
   queued chunks per tick and calls `discover(level, center, 0)` with
   `center = (chunkX*16+8, seaLevel, chunkZ*16+8)` (`KingdomsServiceImpl.java:511-512`).
3. `discover` (`KingdomsServiceImpl.java:417-429`) runs every registered detector in insertion
   order (structure, then POI, `KingdomsServiceImpl.java:88-93`; MCA is added at server start,
   `src/main/java/dev/otectus/mcakingdoms/compat/mca/McaCompat.java:56`) and feeds each candidate
   to `registerCandidate`.
4. The detectors, with `radiusChunks == 0`:
   - `VillageStructureDetector.detect` (`src/main/java/dev/otectus/mcakingdoms/settlement/VillageStructureDetector.java:26-55`)
     reads `getAllStarts()` of the one chunk (`:33-35`), which only lists structures whose
     **start chunk** is this chunk. For a `#minecraft:village` start (`:36`) it emits one
     candidate: anchor = bounding-box centre, radius = half the larger span, bounds = the
     jigsaw bounding box (`:41-43`, `:47-48`), source key
     `<dim>:<structure>:<startX>:<startZ>` (`:44-46`), plus the same key as a
     `minecraft:structure` external reference (`:50`). Stable and unique per village.
   - `PoiClusterDetector.detect` (`src/main/java/dev/otectus/mcakingdoms/settlement/PoiClusterDetector.java:32-61`)
     asks `PoiManager.getInRange` for `#minecraft:village` POIs within `poiSearchRadius` (48) of
     the centre (`:33-37`), needs at least `poiMinimumCount` (3) (`:38`, `:41`), groups them by
     union-find with `LINK_DISTANCE` 40 (`:29`, `:40`, `:63-73`), and for each group emits a
     candidate anchored at the **centroid of the POIs it happened to see** (`:48`), with radius
     `max(64, extent + 16)` capped at 256 (`:30`, `:51-52`), square bounds around the anchor
     (`:55-56`), and source key `<dim>:poi:<centroidChunkX>:<centroidChunkZ>` (`:53-54`).
   - `McaCompat.McaDetector.detect` (`McaCompat.java:104-128`) returns MCA's nearest village
     whose box inflated by 16 contains the centre (`:109-112`), converted by
     `McaAccess.candidate` (`src/main/java/dev/otectus/mcakingdoms/compat/mca/McaAccess.java:120-147`)
     to an EXTERNAL candidate keyed `<dim>#<mcaVillageId>` with the same string as an `mca`
     external reference (`:140-143`). `McaCompat.McaEvidence.observe` (`McaCompat.java:130-165`)
     also calls `registerCandidate` for every MCA villager's home village (`:139-142`), every
     `evidenceInterval` ticks (`McaKingdoms.java:238-242`, `McaKingdomsConfig.java:39-41`).
5. `registerCandidate` (`KingdomsServiceImpl.java:252-307`) calls `match` (`:639-656`):
   identity match on detector key or external reference (`:640-649`), else the **spatial
   fallback** (`:650-655`): a record in the same dimension that has no strong structure identity
   when the candidate is a structure (`:652`, `:763-766`) and that either overlaps the candidate
   by at least 65% of the smaller area (`:653`, `:775-786`) or whose anchor is within
   `candidateMergeDistance` (32) of the candidate anchor (`:654`). Nearest anchor wins (`:655`).
   No match creates a new record with a fresh UUID, its own generated name and its own kingdom
   resolution (`:266-307`).
6. A match goes to `observe` (`:658-707`): identities and references are added (`:661-672`); a
   higher-ranked source replaces anchor and bounds (`:673-683`, ranks `:788-796`); an
   equal-ranked source that is not contained grows the bounds by union up to a span of
   `defaultSettlementRadius * 8` (`:684-697`). A lower-ranked source (POI into a STRUCTURE
   record) changes nothing spatially.
7. Lookups: `getSettlementAt` (`:137-147`) returns, among records whose bounds contain the
   position, the one with the **nearest anchor** (`:145`). The entry banner
   (`src/main/java/dev/otectus/mcakingdoms/presentation/ServerPresentationEvents.java:36-58`),
   automatic residence for vanilla villagers (`KingdomsServiceImpl.java:516-529`), jurisdiction,
   atlas sites and the Kingdom Tasks "here" targets all go through it.
8. Records are never merged by the mod itself. The only merge is the operator command
   `/kingdoms village merge` (`src/main/java/dev/otectus/mcakingdoms/command/KingdomsCommands.java:87-92`,
   `:223-234`) or the Kingdom Tasks entry (`src/main/java/dev/otectus/mcakingdoms/interaction/CoreTasks.java:22`),
   both calling `KingdomsServiceImpl.merge` (`KingdomsServiceImpl.java:378-414`), which redirects the source UUID
   (`KingdomsServiceImpl.java:409`, `src/main/java/dev/otectus/mcakingdoms/core/SettlementSavedData.java:88-99`) but
   leaves the target's bounds untouched.

## 3. Root causes

### RC1. Detectors mint unrelated identities for one village, and the only bridge between them is a one-shot spatial test with village-sized blind spots

The structure key (`VillageStructureDetector.java:44-46`) is the only key that identifies a
village. The POI key is the chunk of a centroid that moves with every scan
(`PoiClusterDetector.java:48`, `:53-54`), so consecutive scans of one village rarely share a key.
The MCA key is an MCA village id, and MCA may hold several villages for one vanilla village
(RC6). Reconciliation between keys happens once, at candidate time, through `match`'s fallback
(`KingdomsServiceImpl.java:650-655`) with thresholds of 32 blocks and 65% overlap. A vanilla
village is allowed to extend 80 blocks from its start (`max_distance_from_center: 80` in
`data/minecraft/worldgen/structure/village_plains.json`, 1.20.1 client data); the observed
structure boxes span 108 to 172 blocks. A POI scan centred more than roughly 32 blocks from the
structure's box centre, with a 129x129 candidate square, fails both tests.

World 21 arithmetic: Peacecross anchor is 52 blocks from Dawnfield's anchor (> 32). The
intersection of the two bounds is X -2052..-1965 (88) by Z 1268..1380 (113) = 9,944 blocks;
the smaller area is Peacecross's 129 x 129 = 16,641; 9,944 / 16,641 = 0.60 < 0.65. Both tests
fail, so `registerCandidate` created a second record (`KingdomsServiceImpl.java:266-307`).

### RC2. POI scans are fragmentary by construction, and blind on high terrain

`tickDiscovery` centres every scan at **sea level** (`KingdomsServiceImpl.java:511`).
`PoiManager.getInRange` filters with a three-dimensional distance (decompiled 1.20.1
`net/minecraft/world/entity/ai/village/poi/PoiManager.java:73-76`, via `Vec3i.distSqr`,
`net/minecraft/core/Vec3i.java:186-188` and `:201-206`). With radius 48 and centre Y 63, a POI at
Y 110 is 47 blocks up and is only found within sqrt(48^2 - 47^2) = 9.7 blocks horizontally; a POI
at Y 111 or above is never found. Peacecross's anchor Y of 110 is the mean Y of the POIs it saw,
so that fragment was built from the few POIs almost directly under one chunk centre, then given
a 64-block radius square (`PoiClusterDetector.java:51-52`). On lowland villages (worlds 19 and
20, Y 66-81) the reach is 44-48 blocks, so each scan still only sees the part of the village
within 48 blocks of its chunk centre: the centroid walks across the village as scanning
proceeds, and the fragments whose centroid lands more than the thresholds away from the
existing anchor become records (RC1).

### RC3. Records never merge after creation, and a structure record never grows

Nothing in the mod merges two existing records; `merge` is operator-only (section 2, step 8).
A STRUCTURE record rejects spatial growth from POI or MCA observations because growth requires an
equal rank (`KingdomsServiceImpl.java:684`), so its bounds are the jigsaw box forever even though
POI squares, MCA building boxes and player additions routinely extend past it. The 65% rule is
then evaluated against candidate squares that hang outside that box.

### RC4. Each fragment is named and classified independently

A new record runs the kingdom resolution on its own anchor and bounds
(`KingdomsServiceImpl.java:266-273`; sampling in
`src/main/java/dev/otectus/mcakingdoms/data/DefinitionSnapshot.java:214-243` uses the candidate's
own bounds corners). Fragments on a biome edge therefore resolve to different kingdoms (world 20),
and each fragment consumes a generated name, which is why the ledger shows several named places
for one village.

### RC5. Three village POIs anywhere make a "settlement"

The POI detector has no notion of a village beyond `poiMinimumCount` (3) tagged POIs
(`PoiClusterDetector.java:38`, `:41`). `#minecraft:village` is beds, job sites and bells
(`data/minecraft/tags/point_of_interest_type/village.json`). Modded underground structures with
three beds produce the Y 23-48 records in all three worlds. Not the reported bug, but the same
code path, and the plan's identity change makes it cheap to fix at the same time.

### RC6. MCA's own village model can also fork, and its ids die on merge

MCA 7.6.26 (`Village.java:45-47`: `MERGE_MARGIN = 64`) attaches a building to the nearest village
whose box inflated by 64 contains it (`VillageManager.java:229`, `Village.java:126-128`),
otherwise creates a new village (`VillageManager.java:273`) and then merges any village whose
box inflated by 64 intersects, keeping the id of the more populous one (`VillageManager.java:293-298`,
`Village.java:423-427`); a village counts once it has three buildings (`Village.java:429-431`,
`Config.java:122`). Villagers pick the nearest village within 48 blocks of their bed
(`Residency.java:80-93`). So MCA converges to one village per vanilla village, but while it does
so Kingdoms may register two MCA ids as two records, and after MCA's merge one of those ids no
longer exists. Kingdoms keeps every `mca` reference forever (`src/main/java/dev/otectus/mcakingdoms/core/SettlementRecord.java:159-162`)
and `getSettlementForMcaVillage` resolves by that reference
(`src/main/java/dev/otectus/mcakingdoms/api/KingdomsService.java:23-26`), so a record keyed only
by a dead MCA id lingers beside the live one. Not observed in the three saves (too young), but it
is the same failure once MCA has scanned.

### Why this is not just "two villages generated next to each other"

Vanilla places village starts on a 34-chunk grid with separation 8
(`data/minecraft/worldgen/structure_set/villages.json`), so two starts are at least 128 blocks
apart and their 80-block reaches can touch only in rare cells. All five fragment anchors above
are inside the structure box of the same village, and the POI identities attached to the
structure records (16 and 19 of them in worlds 20 and 19) show the same village being scanned
from many chunks. Genuinely adjacent villages must stay separate; the structure key is what
distinguishes them, and the plan keeps it that way.

## 4. Decisions to make first

Each has a recommended default so the work can start without waiting.

- **D1. Canonical identity for generated villages.** Recommended: the vanilla structure start
  (`<dim>:<structure>:<startX>:<startZ>`, the key the structure detector already writes) becomes
  the identity every detector resolves to, through the chunk's structure references. The
  alternative (bigger spatial thresholds) only moves the blind spot and still splits across
  kingdoms.
- **D2. Automatic reconciliation of records already split.** Recommended: on, by default, lazily
  as chunks are scanned plus a bounded start-up pass, with the politics preflight able to veto a
  pair (section 8.1). The alternative (operators run `/kingdoms village merge` by hand) leaves
  every existing Ultima world split.
- **D3. Which record survives a reconciliation merge.** Recommended: the record carrying the
  structure key, else the highest detection rank, else the oldest. Name and kingdom locks on the
  retired record transfer to the survivor when the survivor is unlocked; otherwise the survivor
  keeps its name and kingdom and the retired name becomes an alias (existing merge behaviour).
- **D4. POI-only settlements need a village signal.** Recommended: raise `poiMinimumCount` to 5,
  require at least one bed or bell in the cluster, and skip clusters whose mean Y is more than
  24 blocks below the surface. This stops the underground phantoms. It does not retire the
  existing phantom records; a `/kingdoms village retire` command is optional (D6).
- **D5. Manual creation inside a recognised village.** Recommended: a manual candidate whose
  anchor resolves to a village footprint joins that village (one village, one settlement); the
  Kingdom Tasks result says so. The alternative keeps today's behaviour (a second record 32+
  blocks away).
- **D6. Retire command for phantom records.** Optional; recommended for a later release.
  Reconciliation never deletes a record on its own.

## 5. Target design

### 5.1 One village, one identity

Add a footprint lookup, `settlement/VillageFootprints` (new):

```text
footprintsNear(level, pos, margin) -> List<Footprint(structureId, startChunk, box, key)>
```

For the chunk containing `pos` and the chunks within `margin` of it, read only loaded chunks
(`ServerChunkCache.getChunkNow`, present in 1.20.1), take `ChunkAccess.getAllReferences()`
(decompiled `net/minecraft/world/level/chunk/ChunkAccess.java:223`), keep structures in
`#minecraft:village` plus the configured extra tags, resolve starts with
`StructureManager.startsForStructure(SectionPos, Structure)`
(`net/minecraft/world/level/StructureManager.java:61`; `ServerLevel.structureManager()` exists in
1.20.1), keep `isValid()` starts (`StructureStart.java:118`) and return those whose
`getBoundingBox().inflatedBy(margin)` contains `pos` (`BoundingBox.inflatedBy`, `isInside`
exist). Prefer the box that contains `pos` without inflation, then the nearest box centre. The
key uses the exact format of `VillageStructureDetector.java:44-46`, so existing structure records
match by identity (`KingdomsServiceImpl.java:642`) and by the `minecraft:structure` reference
(`:645-647`) without migration.

Expose it as an SPI so GameTests and structure mods can contribute footprints:
`KingdomsService.registerVillageFootprintSource(ResourceLocation owner, VillageFootprintSource)`,
registered like detectors (`KingdomsServiceImpl.java:473-476`, `:847-858`). The built-in source is
the chunk-reference reader above.

### 5.2 Candidate normalisation in `registerCandidate`

Before `match`, a candidate that carries no `minecraft:structure` reference is resolved: anchor
first, then the four bounds corners; the first footprint found is attached as external reference
`minecraft:structure=<key>` and, when absent, as `styleId`. Enriched candidates then hit the
identity branch of `match`. STRUCTURE candidates are unchanged. Manual candidates follow D5.

### 5.3 Matching and reconciliation

`match` gains one step between identity and the spatial fallback: a candidate that carries a
structure key K also claims every record in the dimension that has **no** strong structure
identity and whose anchor lies inside K's box inflated by `structureMergeMargin`. Those records
are merged into the canonical record for K (D3) through the existing `merge`
(`KingdomsServiceImpl.java:378-414`), so redirects, aliases, retired slugs, identities, external
references, `SettlementMergedEvent` and the politics preflight all apply. If no record carries K
yet, the canonical record receives K as a strong structure identity, so the later structure
candidate matches by identity and is promoted to the jigsaw box (`:673-683`).

`merge` must also union the bounds (today it never touches `target.bounds`,
`KingdomsServiceImpl.java:378-414`), capped at the existing `defaultSettlementRadius * 8` span
(`KingdomsServiceImpl.java:689`); a record carrying a structure key
takes the union of the structure box and the observed footprints. `observe` allows growth from
any source that resolved to the record's own structure key, not only equal rank (`:684`).

Reconciliation runs from four places: (1) inside `registerCandidate` as above; (2) a start-up
pass over records without a strong structure identity, resolving their anchors from loaded
chunks or by reading at most `reconcileStartupChunkBudget` chunks at `STRUCTURE_REFERENCES`
status, spread over ticks; (3) whenever a chunk is scanned, for records whose anchors are in that
chunk; (4) `/kingdoms village reconcile [radiusChunks]` (permission 2) for the loaded area around
the operator. Each pass logs one summary line.

A merge the politics preflight rejects (`src/main/java/dev/otectus/mcakingdoms/politics/GovernmentService.java:939`,
`:960`, `:965`) is logged once per pair per server session with the rejection text and the
exact operator command, and that pair is not retried until the next start.

Dead MCA references (RC6): when an MCA candidate resolves to canonical record A and another
record B holds only `mca` references that `McaAccess.villages(level)`
(`McaAccess.java:101-118`) no longer lists, and B's anchor lies inside A's footprint, B is merged
into A by the same path.

### 5.4 POI detector

- Centre the scan at the surface (`LevelReader.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)`,
  both present in 1.20.1) or, better, filter by horizontal distance using
  `PoiManager.getInSquare` (`PoiManager.java:65`) and a 2D check. Recommended: horizontal.
- Candidate bounds become the cluster's real extent plus `EDGE_MARGIN` (min radius 32), not a
  64-block square; the footprint lookup runs on the cluster's POI positions (majority vote), and a
  resolved cluster takes the structure box as its bounds.
- Spatial fallback for clusters with no footprint (player-built or untagged mod villages):
  match a record without strong structure identity whose bounds inflated by `poiLinkDistance`
  intersect the candidate's extent. Two scans that share a POI always intersect, so a village is
  assembled in any scan order.
- D4 signals: `poiMinimumCount` 5, at least one `minecraft:home` or `minecraft:meeting` POI,
  mean Y within 24 blocks of the surface at the centroid.

### 5.5 MCA detector

Unchanged shape; `McaAccess.candidate` output is normalised by 5.2. `McaDetector.detect` keeps
the 16-block margin (`McaCompat.java:110`); the structure key, not MCA's id, is now the join.

### 5.6 Kingdom and name on merge

The canonical record keeps its kingdom and name unless D3's lock rule transfers them. A
reconciliation merge does not re-run classification; operators keep `/kingdoms village
reclassify`. Aliases keep every retired name searchable (`KingdomsServiceImpl.java:191-198`).

## 6. Migration for existing worlds

- **Saved data stays schema 1.** No new required fields; `SettlementSavedData.load` refuses any
  other schema (`SettlementSavedData.java:122-128`) and 0.1.1 must still be able to open the
  file after a downgrade. Add one optional root key `Reconciled` (int, absent = 0) marking that
  the start-up pass ran; records gain nothing new because the structure key already lives in
  `StrongStructureIdentities` and `ExternalRefValues["minecraft:structure"]`
  (`SettlementRecord.java:63-96`). `LegacyRenameTest` keeps passing (no new store, no new config
  file name).
- **Identity carry-over.** Old UUIDs stay resolvable through redirects
  (`SettlementSavedData.java:44`, `:51-59`, `:88-99`); civic identities are rewritten on read
  (`KingdomsServiceImpl.java:607-622`); `getSettlement(oldId)` returns the survivor.
- **Entry banner and discovery.** `SettlementKnowledge.merge`
  (`src/main/java/dev/otectus/mcakingdoms/knowledge/SettlementKnowledge.java:152-165`), wired to
  `SettlementMergedEvent` (`McaKingdoms.java:198-202`), moves both the discovery set and the
  per-player **visit** set (`SettlementKnowledge.java:104-114` records visits; `SettlementKnowledge.java:160-162`
  merges them), so a player who entered either fragment does not get the first-visit title again for the merged village, and a
  player who entered neither still gets it once. The banner itself re-sends when the settlement
  under the player changes (`ServerPresentationEvents.java:51-57`), so a player standing in the
  village during the merge sees the surviving name once.
- **Politics.** Capitals are re-pointed (`GovernmentService.java:971-977`); institution lookups
  resolve through `getSettlement` (`:960-965`).
- **Everything else keyed by settlement UUID** compares raw ids and needs normalisation (task
  T4): `src/main/java/dev/otectus/mcakingdoms/civic/CivicService.java:123` and `:245`
  (`CivicSavedData` `Site`, `Seed`, `Introduction`, `src/main/java/dev/otectus/mcakingdoms/civic/CivicSavedData.java:16-22`),
  `src/main/java/dev/otectus/mcakingdoms/warfare/CampaignService.java:85`, `:130`, `:178`, `:234`,
  `:264`, `:324-326`, `src/main/java/dev/otectus/mcakingdoms/warfare/CampaignState.java:96`,
  `src/main/java/dev/otectus/mcakingdoms/warfare/WarfareRuntime.java:142`,
  `src/main/java/dev/otectus/mcakingdoms/warfare/contracts/CivilianContractData.java:129`, `:141`,
  `src/main/java/dev/otectus/mcakingdoms/warfare/contracts/CivilianContractService.java:131`, `:246`,
  `src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionService.java:93`, `:285`,
  `src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionContracts.java:33`,
  `src/main/java/dev/otectus/mcakingdoms/evolution/drama/DramaService.java:79`,
  `GovernmentService.java:791`. Townstead binding ids are derived from the settlement UUID
  (`src/main/java/dev/otectus/mcakingdoms/api/townstead/TownsteadBuildingView.java:57-60`, used at
  `src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadServiceImpl.java:308`), so a
  retired fragment's building ids change after the merge; T4 recomputes them from the resolved
  settlement id and rewrites `Site.settlement`.
- **Phantom POI records** (RC5) are left alone; D6 covers their removal.
- **Pre-rename worlds** go through `LegacyRename` first (`McaKingdoms.java:85-86`, `McaKingdoms.java:50-51`),
  then the same reconciliation; nothing special.

## 7. Configuration

All in `mcakingdoms-common.toml`, section `settlementDiscovery`
(`McaKingdomsConfig.java:17-33`). Existing keys keep their meaning.

| Key | Default | Range | Purpose |
|---|---|---|---|
| `villageIdentity` | `STRUCTURE` | `STRUCTURE`, `SPATIAL` | `SPATIAL` restores today's matching for troubleshooting; reconciliation is off in that mode |
| `structureMergeMargin` | 48 | 0-128 | Blocks beyond a village's structure box that still belong to it (MCA's own border margin is 48) |
| `autoReconcile` | true | | Merge split records when a candidate resolves to a village |
| `reconcileOnStartup` | true | | Run the start-up pass |
| `reconcileStartupChunkBudget` | 64 | 0-4096 | Chunks the start-up pass may read at `STRUCTURE_REFERENCES` status; the rest reconcile lazily |
| `reconcileChunkReadsPerTick` | 4 | 1-64 | Spread of the start-up pass |
| `villageStructureTags` | `["minecraft:village"]` | | Extra structure tags or ids packs treat as villages |
| `poiSearchRadius` | 48 (unchanged) | 16-256 | Horizontal radius from now on |
| `poiLinkDistance` | 40 | 8-128 | Exposes `LINK_DISTANCE` |
| `poiMinimumCount` | 5 (was 3) | 1-64 | D4 |
| `poiRequireHomeOrMeeting` | true | | D4 |
| `poiSurfaceOnly` | true | | D4: cluster mean Y within 24 blocks of the surface |
| `candidateMergeDistance` | 32 (unchanged) | 0-256 | Spatial fallback only |
| `candidateOverlapFraction` | 0.5 (was a 0.65 constant) | 0.1-1.0 | Spatial fallback only |
| `defaultSettlementRadius` | 64 (unchanged) | 16-512 | Manual radius and growth cap |
| `chunkScansPerTick` | 2 (unchanged) | 1-64 | |

`README.md:135-138` documents the current table and needs the new rows.

## 8. Ripple effects

### 8.1 Inside Kingdoms

- Politics preflight can veto a reconciliation pair (`GovernmentService.java:939-965`); logged,
  not retried in-session. Capitals follow the merge (`:971-977`).
- Stores listed in section 6 get a `SettlementMergedEvent` listener that rewrites persisted ids
  and a read-time `resolveSettlementId` for anything compared live. New API:
  `KingdomsService.resolveSettlementId(UUID)` (exposes `SettlementSavedData.resolveId`).
- Atlas sites and routes resolve by position (`src/main/java/dev/otectus/mcakingdoms/worldcontext/WorldContextService.java:172-173`)
  and store settlement ids on routes (`:72`); the listener covers routes.
- Quest receipts: `src/main/java/dev/otectus/mcakingdoms/compat/quests/receipts/QuestCompletionBridge.java:95-100`
  stores the frozen settlement id; `CivicService.java:114` resolves it, `:123` compares raw (T4).
- Ledger and selectors: a merge bumps the data revision (`SettlementSavedData.java:97`), so
  pages refresh; the retired name stays searchable as an alias.
- Network: no packet changes; `PROTOCOL` stays `"5"`
  (`src/main/java/dev/otectus/mcakingdoms/network/NetworkHandler.java:24`). Only bump it if T6
  adds the structure key to `SettlementSummary`, which this plan does not recommend.

### 8.2 Add-ons (1.20.1)

- **MCAQuests.** Accepted kingdom-gated quests freeze a `KingdomBindingSnapshot.settlementId`
  (`/home/otectus/Projects/MCAQuests/src/main/java/dev/otectus/mcaquests/state/KingdomBindingSnapshot.java:11`)
  and compare it raw against the live capture
  (`/home/otectus/Projects/MCAQuests/src/main/java/dev/otectus/mcaquests/quest/kingdom/KingdomQuestLifecycle.java:100-104`),
  so a merge that retires the giver's fragment flips an active quest to `FAIL_KINGDOM`. Change:
  resolve both ids through Kingdoms before comparing (reflective call to the new
  `resolveSettlementId`, raw equality when the method is absent, so older Kingdoms keeps
  working). `CivicBuildingBinding.bindingId`
  (`/home/otectus/Projects/MCAQuests/src/main/java/dev/otectus/mcaquests/state/CivicBuildingBinding.java:10`)
  and the receipt copies
  (`/home/otectus/Projects/MCAQuests/src/main/java/dev/otectus/mcaquests/api/QuestCompletionReceipt.java:63`, `:81`)
  are consumed by Kingdoms, which recomputes binding ids from the resolved settlement (T4); no
  Quests change for those.
- **MCAReputation.** Keys standing by kingdom, never by settlement (no settlement references in
  `MCAReputation/src/main`). No change. Before the fix a fragment could carry a different
  kingdom than its village (world 20), so standing from that fragment went to that kingdom;
  the merge does not rewrite past standing.
- **MCAConversations.** Reads `mcakingdoms.village_id` and friends live from `getContext`
  (`KingdomsServiceImpl.java:219-243`), which resolves redirects on every read; `CivicBridge`
  deliberately persists no settlement id
  (`/home/otectus/Projects/MCAConversations/src/main/java/dev/otectus/mcaconversations/compat/CivicBridge.java:41`, `:124`).
  No change.
- **MCACrime.** No Kingdoms references in `MCACrime/src/main`; Kingdoms' own
  `JurisdictionPolicyBridge` resolves by position, so one jurisdiction per village after the fix.
  No change.

### 8.3 NeoForge 1.21.1 ports

Kingdoms has no 1.21.1 port (nothing under `/home/otectus/Projects/1.21.1 Ports/` for it).
`MCAQuests_1.21.1` has no `KingdomIntegration`; `MCAConversations_1.21.1` has only the
`KingdomsIdentity` probe; `MCAReputation_1.21.1` only the namespace gate and outbox. Nothing to
change there; keep the reflective `resolveSettlementId` optional so the probes stay valid.

## 9. Tasks

Order: T1, T2, T3 together are the fix; T4 before T3 is enabled by default; T5 can ship in the
same Quests release; T6 and T7 verify; T8 documents.

### T1. API and data groundwork

Files: `api/KingdomsService.java`, new `api/VillageFootprint.java`, new `api/VillageFootprintSource.java`,
`core/KingdomsServiceImpl.java`, `core/SettlementSavedData.java`, `config/McaKingdomsConfig.java`.

Steps: add `resolveSettlementId`; add the footprint source SPI and registry; add the config keys
of section 7 (keep `LINK_DISTANCE` as the fallback when the key is absent); add the optional
`Reconciled` root key with a round-trip in `save`/`load`; make `merge` union bounds (cap as `KingdomsServiceImpl.java:689`).

Verify: `compileJava`; `SettlementPersistenceTest` still passes; a new GameTest asserts the
schema stays 1 and `Reconciled` survives save and load.

### T2. Footprint lookup and candidate normalisation

Files: new `settlement/VillageFootprints.java`, `settlement/PoiClusterDetector.java`,
`core/KingdomsServiceImpl.java` (`registerCandidate`, `match`, `observe`), `compat/mca/McaAccess.java`
(no behaviour change expected; verify the candidate shape).

Steps: implement 5.1 and 5.2; POI changes of 5.4 (horizontal distance, extent bounds, signals,
footprint vote); growth rule of 5.3 in `observe`; the structure-key claim step in `match`.

Verify: `PoiClusterDetectorTest` extended (extent bounds, surface filter, home-or-meeting
signal); GameTests in section 10.2 (a) to (c).

### T3. Reconciliation

Files: new `core/SettlementReconciler.java`, `core/KingdomsServiceImpl.java`, `McaKingdoms.java`
(start-up pass hook after `SettlementKnowledge.adopt`, `McaKingdoms.java:105`), `command/KingdomsCommands.java`
(`reconcile` subcommand next to `merge`, `:87-92`; extend `debug`, `:93-98`, with the structure
key and footprint), `interaction/CoreTasks.java` (D5 wording).

Steps: implement 5.3 including the preflight-rejection log, the dead-MCA-reference rule and
the start-up budget; D3 survivor choice and lock transfer; one summary log line per pass.

Verify: GameTests (d) to (g); the fixture test built from appendix A.

### T4. Settlement-id normalisation in Kingdoms stores

Files: those listed in section 6 under "Everything else keyed by settlement UUID", plus
`compat/townstead/TownsteadServiceImpl.java:308`, `api/townstead/TownsteadBindingPersistence.java:16`,
`worldcontext/WorldContextService.java:72`.

Steps: each store subscribes to `SettlementMergedEvent` and rewrites persisted ids in one
transaction (the pattern is `GovernmentService.java:971-977`); live comparisons call
`resolveSettlementId`; Townstead binding ids are computed from the resolved settlement and the
old id is accepted on lookup for one release.

Verify: unit tests per store (`CampaignStateTest`, `CivilianContractDataTest`, `EvolutionStateTest`,
`DramaStateTest`, `CivicSavedDataTest`, `TownsteadBindingPersistenceTest` already exist and get a
merge case each); GameTest (e).

### T5. MCAQuests tolerance

Files: `/home/otectus/Projects/MCAQuests/src/main/java/dev/otectus/mcaquests/compat/KingdomIntegration.java`
(`capture`, `:186`), `quest/kingdom/KingdomQuestLifecycle.java:100-104`, and the tests that pin
the comparison (`KingdomLifecycle*Test`).

Steps: resolve both ids through the optional reflective `resolveSettlementId`; raw equality when
absent. No `mods.toml` range change (optional dependency, lower bound only).

Verify: `gradlew-quiet.sh MCAQuests check`; packaged phase `named` (section 10.3).

### T6. Tests (section 10)

### T7. Packaged-server and client verification (section 10.3, 10.4)

### T8. Documentation

Files: `README.md:40` (recognition paragraph) and `:135-138` (config table), `CHANGELOG.md`
(the 0.1.1 line at `:49` claimed large villages register once; the new entry should say what
actually changed), `MODMAP.md` Decisions (one line: "village identity is the structure start;
reconciliation merges fragments"), `docs/mcakingdoms.md:490-525` (the design's dedup ladder
gains the structure-key step).

## 10. Test plan

### 10.1 Unit tests (`src/test`)

- `PoiClusterDetectorTest`: extent-based bounds; horizontal distance (a POI 47 blocks up at the
  same x/z is found); D4 signals; `poiLinkDistance` from config.
- New `SettlementReconcilerTest` against an in-memory `SettlementSavedData`: survivor choice
  (structure key, rank, age), lock transfer, bounds union and cap, dead-MCA rule, rejected pair
  remembered once.
- New `SettlementSavedDataReconciledFlagTest`: schema stays 1; `Reconciled` round-trips; a file
  without the key loads as 0.
- Store tests listed in T4.

### 10.2 GameTests (`src/gameTest`, run with `tools/test/run_gametests.py`, which fails unless every
`@GameTest` completes)

Register a test footprint source with a synthetic box in each test (no real village is needed;
`/place structure` does not register starts, so the SPI is the seam):

- (a) POI candidate inside the footprint, then the structure candidate: one record, promoted to
  the box, bounds union covers the POI extent; and the reverse order gives the same single record.
- (b) An EXTERNAL candidate (shape of `McaAccess.candidate`) inside the footprint joins; a second
  MCA id inside the same footprint joins the same record (both references kept).
- (c) Two footprints 128 blocks apart: a POI cluster in each stays in its own record; a cluster
  with no footprint 200 blocks away becomes its own POI-only record; the existing
  `duplicateDiscoveryAndNearbyStructures` and `externalPromotionRetainsStrongStructureSeparation`
  tests (`src/gameTest/java/dev/otectus/mcakingdoms/test/KingdomGameTests.java:46-51`, `:223-228`)
  keep passing.
- (d) Pre-split fixture: seed two records as 0.1.1 wrote them (appendix A, world 21) through the
  real loader, register the Dawnfield footprint, scan one chunk: Peacecross redirects to
  Dawnfield, the alias is searchable, `getSettlementAt` at both anchors returns Dawnfield,
  `SettlementKnowledge` visits for both ids collapse to one and `recordVisit` returns false for
  the survivor.
- (e) Stores: a campaign, a civilian contract, an evolution scenario, a civic seed and a Townstead
  binding on the retired id resolve to the survivor after the merge event.
- (f) Preflight veto: both records hold the same local office; reconciliation leaves both, logs
  once, and `/kingdoms village reconcile` reports the pair.
- (g) Start-up pass: records without structure identity whose anchor chunk is loaded are
  reconciled on the first ticks; `Reconciled` is written; a second start does nothing.
- (h) D5: a manual candidate inside a footprint joins it; outside it creates a record.

### 10.3 Packaged-server phases (`tools/test/integration_runtime.py`, scenarios in
`tools/test/runtime/java/dev/otectus/mcakingdoms/acceptance/IntegrationScenario.java:225-240`)

- New phase `split-village`: the scenario writes a 0.1.1-shaped `mcakingdoms_settlements.dat`
  (two records, as in appendix A) into a fresh world, registers the footprint through the SPI,
  boots, asserts one summary log line and one settlement at both anchors, then `boot('split-village-restart')`
  asserts the file is stable and still one record. Follow the `migration` phase pattern
  (`integration_runtime.py:103-105`). Do not check the 8 MB Ultima region file into the repo; the
  synthetic fixture is enough because the SPI replaces the chunk read.
- Existing phases unchanged and re-run: `initial`, `named`, `r1`, `r2`, `r2-legacy`, `civic-loop`,
  `r3`, `r3-civilian`, `military`, `r4`, `r4-crash`, `politics`, `politics-cycle`, `migration`,
  plus `--crash-replay`. `r3` is known to be intermittent (MODMAP known issue); one rerun is
  acceptable, two is a regression.
- Family check: Quests, Reputation, Conversations and Crime jars from their current builds in
  the same `--mod` list, as the earlier remediation did.

### 10.4 Client

`tools/client-test/production_client.py` once, to see the entry title show the surviving name once
when walking across a village that was split before the update (use a copy of
`New World (21)` outside the repo; it is 29 MB and depends on the full pack).

## 11. Risks and open points

- `startsForStructure` loads the start chunk at `STRUCTURE_STARTS` status when it is not in
  memory; bounded by the budgets and by only reading references from loaded chunks. Measure on a
  world with a few hundred records before raising the defaults.
- Two villages whose boxes overlap (possible at 128-block start spacing) stay distinct by key; a
  POI cluster in the overlap goes to the box that contains its centroid. Document this.
- Untagged modded villages fall back to the POI path with the new extent-based matching; packs
  can add tags through `villageStructureTags`.
- A reconciliation merge can change which kingdom a fragment's residents belong to (the
  survivor's). That is the intended outcome of one village, one kingdom; the changelog must say
  it.
- The Astra debugger was not run for this diagnosis: the saved data confirmed the mechanism
  directly, which a source-only diagnosis could not have improved on.

## Appendix A. Save dumps used for the diagnosis

Read with a 60-line NBT reader (`/tmp/kd-tools/nbtdump.py`, not part of the repo; `BlockPos`
longs unpacked as 26/12/26 bits, UUIDs from the four-int arrays). Only the fields used above.

```text
New World (21)  Schema 1  Revision 10  records 3  redirects 0
Dawnfield   STRUCTURE  minecraft:overworld:minecraft:village_plains:-124:82
            anchor (-1977,128,1324) radius 75 bounds X -2052..-1903 Z 1268..1380 created 422
            biome regions_unexplored:highland_fields  style minecraft:village_plains
Peacecross  POI        minecraft:overworld:poi:-127:82
            anchor (-2029,110,1324) radius 64 bounds X -2093..-1965 Z 1260..1388 created 440
            biome regions_unexplored:orchard
Greenford   POI        minecraft:overworld:poi:-125:95 (+3 more poi identities)
            anchor (-1985,48,1532) radius 70 bounds X -2054..-1915 Z 1467..1602 created 886
            biome regions_unexplored:prismachasm
knowledge: one player, Known = {Dawnfield, Peacecross}, Visits = {Dawnfield, Peacecross}

New World (20)  Schema 1  Revision 38  records 4
Thornwatch  STRUCTURE  village_plains:-63:12 (+16 poi identities)  anchor (-1000,81,204)
            bounds X -1054..-947 Z 118..289  created 422  kingdom yew (coverage 0.675)
Bellmeadow  POI poi:-62:9 (+3)   anchor (-989,68,145)   bounds X -1084..-925 Z 71..238   created 441  serenum (0.900)
Hartwood    POI poi:-65:16 (+1)  anchor (-1032,67,263)  bounds X -1098..-949 Z 177..335  created 449  yew (0.675)
Clearbrook  POI poi:-68:0 (+3)   anchor (-1080,23,1)    bounds X -1145..-1001 Z -68..67   created 917  serenum

New World (19)  Schema 1  Revision 50  records 5
Summerwell  STRUCTURE  village_plains:-135:-124 (+19 poi identities)  anchor (-2187,96,-2004)
            bounds X -2261..-2113 Z -2081..-1928  created 422  serenum (fallback)
Valegate    POI poi:-135:-123 (+4)  anchor (-2151,66,-1967)  bounds X -2223..-2065 Z -2076..-1884  created 445  serenum
Elderbough  POI poi:-140:-124 (+6)  anchor (-2237,66,-1974)  bounds X -2318..-2153 Z -2075..-1906  created 460  yew (0.038)
Greenford   POI poi:-121:-138       anchor (-1933,30,-2193) created 1041
Sunvale     POI poi:-140:-139       anchor (-2225,27,-2209) created 1309
```

## Appendix B. External sources cited

- MCA 7.6.26+1.20.1: `Village.java`, `VillageManager.java`, `Residency.java`, `Config.java` were
  decompiled with ForgeFlower 2.0.629.0 from
  `~/Documents/curseforge/minecraft/Instances/Ultima/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar`
  (package `forge.net.mca`). Line numbers refer to that decompilation (`/tmp/mca-decomp/out/forge/net/mca/...`).
- Minecraft 1.20.1 (Forge 47.4.23 mapped jar in `~/.gradle/caches/forge_gradle/minecraft_user_repo/`):
  `PoiManager`, `Vec3i`, `StructureManager`, `ChunkAccess`, `StructureStart` decompiled the same
  way (`/tmp/mc-decomp/out/net/minecraft/...`); method presence for `ServerLevel.structureManager`,
  `ServerChunkCache.getChunkNow`, `BoundingBox.inflatedBy/isInside`, `LevelReader.getHeight` and
  `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` confirmed with `javap` on the same jar.
- Vanilla data (`client-extra.jar` for 1.20.1 in the same cache):
  `data/minecraft/worldgen/structure/village_plains.json`, `structure_set/villages.json`,
  `tags/worldgen/structure/village.json`, `tags/point_of_interest_type/village.json`.
