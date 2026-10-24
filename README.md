# MCA: Kingdoms

MCA: Kingdoms is a server-authoritative settlement identity mod for Minecraft 1.20.1 and Forge. It recognizes villages, gives each one a persistent name and one of six kingdoms, and exposes that civic identity through in-game tasks, a ledger, entry overlays, optional commands, datapacks, and a Java API.

The initial release includes Serenum, Lunari, Madera, Anemosia, Yew, and Shimaguni. A settlement's biome and structure style influence its first kingdom assignment. After creation, its stable identity, name, kingdom, bounds, aliases, and discovery information are saved with the world rather than recalculated on every lookup.

Shimaguni (`mcakingdoms:shimaguni`) follows the pack's seven Shimaguni histories: an island empire, the Island Covenant, and the Tide Archive at Akatsura. New jungle, sparse-jungle and bamboo-jungle settlements default to Shimaguni; datapacks can extend its biome tag. Existing saved settlements keep their assignments. Its red heraldry uses a moon above waves, and its political profile is `mcakingdoms:shimaguni_charter`. Akatsura is reserved from random naming so it can be designated deliberately; no capital or historical alliance is created automatically. Generated village names and the civic honor are new thematic content inspired by those histories.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- MCA: Kingdoms must be installed on both the client and server

Minecraft Comes Alive Reborn is optional. The dependency metadata accepts MCA Reborn versions from `7.6` inclusive to `8.0` exclusive (`[7.6,8)`), while the adapter capability-probes four known MCA 7.x package roots. Runtime validation used exactly MCA Reborn `7.6.26+1.20.1` with Architectury API `9.2.14`; other versions in the accepted range have not been runtime-validated. MCA: Kingdoms runs without either mod.

## Installation

Place the `mcakingdoms` jar (`build/libs/mcakingdoms-<version>.jar`) in the `mods` directory of every client and server. The `-api.jar` is a compile-time artifact for addon developers, and the `-sources.jar` contains source attachments; neither belongs in a game instance's `mods` directory.

For MCA integration, also install MCA Reborn `7.6.26+1.20.1` and its Architectury dependency on both client and server. The validated combination uses Architectury API `9.2.14`.

## Upgrading from Ultima Kingdoms

This mod was called Ultima Kingdoms until 0.1.1. The rename changed its mod id (`ultima_kingdoms` to `mcakingdoms`), its package, its commands (`/ultima` to `/kingdoms`), its config file names and every name it saves. A world and configuration from Ultima Kingdoms carry over automatically the first time the renamed jar starts:

- Each saved-data file `world/data/ultima_kingdoms_*.dat` is copied to `mcakingdoms_*.dat`, with every `ultima_kingdoms:` id inside rewritten to `mcakingdoms:` (settlement slugs, kingdoms, organizations, petitions, honors, journal cursors).
- Each config file is copied to its new name (for example `ultima-kingdoms-warfare-common.toml` to `mcakingdoms-warfare-common.toml`).
- A villager's civic identity moves to its new key the first time the villager is observed. Players already welcomed are not welcomed again.
- Book of Kingdoms and Village Ledger items in inventories and containers load as the renamed items.
- A slug written with the old namespace, such as `ultima_kingdoms:aldwick`, still finds its settlement.

A file is copied only when its new name does not exist yet, and the old files are never changed or deleted. To redo a migration, stop the server, delete the new file and start again. Advancement, recipe-book and statistics progress recorded under the old namespace is not carried over: the welcome advancement can appear once more, and the two recipes unlock again when their items are held. A datapack that ships definitions under `data/ultima_kingdoms/` must move them to `data/mcakingdoms/`.

The companions bind the new id from MCA: Quests 1.7.1, MCA: Reputation 0.6.1, MCA: Conversations 1.8.0 and MCA: Crime 0.7.5 (all unreleased as of this build). For one release they also recognize a pack that still ships the old Ultima Kingdoms jar.

## Playing

MCA: Kingdoms examines loaded chunks for structures in Minecraft's village structure tag and for clusters of village points of interest. A newly recognized settlement receives a stable identity and a kingdom based on the supplied biome and structure-style definitions. Administrators can also create or discover settlements explicitly.

One generated village is one settlement. Its identity is the village's structure start: a point-of-interest cluster, an MCA village or a manual registration inside a generated village's footprint (its structure box plus `structureMergeMargin` blocks) belongs to that village, whichever is found first. Records that an earlier version split from one village are merged into it as their chunks are scanned and once at server start; the village keeps the name and kingdom of its structure record (or, failing one, of its oldest highest-ranked record) unless a merged record's name or kingdom was locked and the village's was not; the other names stay searchable as aliases, and old identifiers resolve to the village. A merge that politics or warfare refuses (for example, both records hold the same local office) is logged once, with the operator command to run once the conflict is resolved. Operators can run `/kingdoms village reconcile [radiusChunks]` for the loaded area around them. With the default settings, a point-of-interest cluster is considered only with at least five points, a bed or bell among them, and near the surface.

Craft a **Book of Kingdoms** from one ordinary book and one paper in any crafting grid. Use it for searchable chapters covering every system, from first steps to advanced government, warfare, world evolution and server administration. Chapter contents, search, and previous/next controls navigate the guide; scroll or focus the article and use the arrow/Page Up/Page Down keys to read. Command examples are optional reference text and do not execute.

Press **K** while playing to open **Kingdom Tasks**. Search for what you want to do, then follow the server's named choices. Consequential tasks show a review page with the selected people, places, terms, and effects before **Apply reviewed action**. The server carries record identity, revision, and evidence metadata with each choice, so players select readable names instead of copying technical values into chat. The Village Ledger, Kingdom pages, and War Room also open relevant tasks. See the [player experience guide](docs/Player-Experience-Review.md).

Menus use vanilla Minecraft panels, buttons, text fields and tooltips. Focus a record or article
with Tab to scroll it with the arrow keys, Page Up/Page Down, Home or End; mouse wheel and
scrollbar dragging also work. Settlement details scroll within the ledger, and the Kingdom
pages' Previous/Next buttons reach records beyond the visible rows. See the [GUI audit](docs/MCAKingdoms-GUI-Audit.md).


Craft the Village Ledger and use it to browse known settlements, filter them by kingdom, and inspect settlement details. Its recipe is:

```text
Paper  Paper    Paper
Paper  Book     Paper
       Compass
```

The first time a player enters a recognized settlement, the client shows its name, kingdom, and heraldry. The server remembers each player's visits with the world, so by default this overlay appears once per settlement; `config/mcakingdoms-client.toml` can show it on every entry or turn it off.

The initial release provides civic identity and presentation plus persistent kingdom/faction standing, with optional MCA Reputation integration. Reputation synchronization defaults to `SHADOW`. The current development tree also adds a political layer with explicit capital charters, secondary offices, petitions, institution recognition, honors, and peaceful agreements. R3 adds temporary native military orders and discovered route guidance. R4 adds opt-in evolving-world scenarios, political history, constitutional transitions, voluntary protection duties, organization lifecycle, family introductions, and negotiated native recruit transfers. See [political usage](docs/politics/usage.md), [ownership](docs/politics/ownership.md), and [compatibility evidence](docs/politics/compatibility-matrix.md).

The R1 civic network adds voluntary Lamplighters membership, verified quest service, independent qualification, workshop contacts, private regional introductions and native curated commissions. See [R1 implementation, provider requirements and validation](docs/R1-Civic-Foundation.md); the complete delivery includes companion-provider changes and twelve pack quest overlays.

R2 adds paid recognized-workshop commissions, receipt-backed civic honors, private Crime service suspension and restitution recovery, and signed hospitality introductions. See [R2 implementation and delivery](docs/R2-Institutions-and-Agreements.md) for the matching provider changes and configuration. R2 institutional services default to enabled.

R3 adds enabled-by-default campaigns, native diplomacy acknowledgment, occupation and settlement decisions, temporary mobilization, civilian recovery contracts, local jurisdiction, discovered world sites/routes and atlas/ledger views. See [R3 setup, commands, ownership and recovery](docs/R3-Native-Control.md).

R4 world evolution defaults off. Its authored scenarios and campaigns require explicit world opt-in, factual prerequisites, consent, and bounded outcomes. See [R4 setup, commands, provider extensions and recovery](docs/R4-Evolving-World.md).

## MCA Reborn integration

With the supported MCA version installed, MCA: Kingdoms reads MCA's village records to recognize settlements and reads an MCA villager's current home as positive residence evidence. An MCA villager's first observed civic residence becomes their origin if no origin is already recorded. Newborns inside an already recognized settlement can also receive that settlement as their initial civic identity.

The integration adds separate MCA: Kingdoms civic data to entities. It does not write MCA personal names, family relationships, homes, genetics, personalities, dialogue state, or relationship values. Renaming an MCA: Kingdoms settlement therefore changes its civic label without renaming its residents or changing their MCA family and home data.

## Optional command reference

Kingdom Tasks is the normal player interface. The commands below remain useful for console automation and players who prefer chat. Record selectors use readable names; quote a name when it contains spaces. Kingdom arguments in this basic command tree use namespaced definition names such as `mcakingdoms:serenum`. To supply a new settlement name, provide the radius first. The new name for `create` or `rename` consumes the rest of the command as plain text, so do not quote it; quotes there become part of the name. For example:

```text
/kingdoms village rename "Old Bellmeadow" New Bellmeadow
/kingdoms village info "Old Bellmeadow"
/kingdoms village create 64 New Bellmeadow
```

The following inspection commands are available to players:

```text
/kingdoms kingdom list
/kingdoms kingdom info <kingdom-definition-id>
/kingdoms village here
/kingdoms village info ["settlement name"]
```

The remaining commands require permission level 2:

```text
/kingdoms village create [radius] [name]
/kingdoms village rename "Old Bellmeadow" <new name...>
/kingdoms village setkingdom "Old Bellmeadow" <kingdom-definition-id>
/kingdoms village reclassify "Old Bellmeadow"
/kingdoms village lock "Old Bellmeadow"
/kingdoms village unlock "Old Bellmeadow"
/kingdoms village discover [radiusChunks]
/kingdoms village merge "Old Bellmeadow" "New Bellmeadow"
/kingdoms village debug ["settlement name"]
/kingdoms citizen info <entity>
/kingdoms citizen setorigin <entity> "Old Bellmeadow"
/kingdoms citizen setresidence <entity> "Old Bellmeadow"
/kingdoms reload
```

Manual settlement radii range from 16 to 512 blocks and default to 64. Discovery radii range from 1 to 32 chunks and default to 8.

The other systems share the same root. Each of these also keeps its historical hyphenated root (`/kingdoms-evolution` and `/kingdoms evolution` are the same command tree):

```text
/kingdoms politics …      government, petitions, agreements, elections and regency
/kingdoms guild …         civic chapters, membership and organization lifecycle
/kingdoms faction …       kingdom standing (operator maintenance)
/kingdoms warfare …       claims, campaigns, accords and mobilization
/kingdoms contract …      civilian recovery contracts
/kingdoms mobilization …  temporary native mobilization
/kingdoms world …         discovered sites, routes and encounters
/kingdoms evolution …     world evolution opportunities
/kingdoms protection …    protectorate pacts and obligations
/kingdoms transfer …      recruit transfers
/kingdoms drama …         authored drama proposals
```

Every one of these is also a Kingdom Tasks form, and most Book of Kingdoms topics open the task they describe.

## Configuration

The common config is written to `config/mcakingdoms-common.toml`:

| Key | Default | Range | Purpose |
| --- | ---: | ---: | --- |
| `settlementDiscovery.defaultSettlementRadius` | 64 | 16–512 | Horizontal radius for detected settlements; observation and merges do not grow a settlement's bounds past a width of eight times this radius |
| `settlementDiscovery.poiSearchRadius` | 48 | 16–256 | Horizontal radius searched for village POIs |
| `settlementDiscovery.poiMinimumCount` | 5 | 1–64 | POIs required to recognize a cluster |
| `settlementDiscovery.chunkScansPerTick` | 2 | 1–64 | Loaded chunks inspected per server tick |
| `settlementDiscovery.candidateMergeDistance` | 32 | 0–256 | Spatial fallback: anchor distance at which a candidate matches a settlement when no village record or shared identity decides (with `STRUCTURE` identity, point-of-interest clusters outside villages use `poiLinkDistance` instead) |
| `settlementDiscovery.villageIdentity` | `STRUCTURE` | `STRUCTURE`, `SPATIAL` | `STRUCTURE`: candidates resolve to the generated village they lie in. `SPATIAL`: distance and overlap matching only, with no merging; for troubleshooting |
| `settlementDiscovery.structureMergeMargin` | 48 | 0–128 | Blocks beyond a generated village's structure box that still belong to it |
| `settlementDiscovery.autoReconcile` | `true` | — | Merge records that turn out to be pieces of one generated village |
| `settlementDiscovery.reconcileOnStartup` | `true` | — | Reconcile every record once at server start, not only as its chunks are scanned |
| `settlementDiscovery.reconcileStartupChunkBudget` | 64 | 0–4096 | Unloaded chunks the start-up pass may read; the rest reconcile when their chunks load |
| `settlementDiscovery.reconcileChunkReadsPerTick` | 4 | 1–64 | Chunk reads the start-up pass spends per tick |
| `settlementDiscovery.villageStructureTags` | `["minecraft:village"]` | — | Structure tags or ids treated as generated villages |
| `settlementDiscovery.poiLinkDistance` | 40 | 8–128 | POIs closer than this, horizontally, form one cluster |
| `settlementDiscovery.poiRequireHomeOrMeeting` | `true` | — | A cluster needs a bed or bell to be a settlement |
| `settlementDiscovery.poiSurfaceOnly` | `true` | — | Ignore clusters whose mean height is more than 24 blocks below the surface |
| `settlementDiscovery.candidateOverlapFraction` | 0.5 | 0.1–1.0 | Spatial fallback: share of the smaller area two bounds must overlap for a candidate to match a settlement when no village record or shared identity decides (with `STRUCTURE` identity, point-of-interest clusters outside villages use `poiLinkDistance` instead) |
| `civicIdentity.evidenceInterval` | 200 | 20–24000 | Ticks between NPC civic-evidence checks |
| `politics.openFounding` | `true` | — | A capital resident may found that kingdom's first government with themselves as leader |

World evolution has its own file, `config/mcakingdoms-evolution-common.toml`; `autoEligibleRegions` (default `true`) treats every settlement of a governed kingdom as an evolution region unless an operator pauses it, once the world itself has opted in.

The client config is `config/mcakingdoms-client.toml`:

| Key | Default | Range | Purpose |
| --- | ---: | ---: | --- |
| `entryOverlay.enabled` | `true` | — | Show the settlement-entry title; `false` never shows it |
| `entryOverlay.firstVisitOnly` | `true` | — | Show it only the first time the player enters each settlement; `false` shows it on every entry |
| `entryOverlay.kingdomBordersOnly` | `false` | — | Show it only when the kingdom differs from the last settlement entered |
| `entryOverlay.durationTicks` | 80 | 20–400 | Display duration in client ticks |
| `entryOverlay.y` | 54 | 0–1000 | Vertical screen position in pixels |

Optional integrations use separate common configs. `config/mcakingdoms-integrations-common.toml` enables the optional `mcakingdoms:kingdom` condition registered with MCA Quests by default, and `config/mcakingdoms-townstead-common.toml` enables the Townstead adapter by default.

Townstead is optional. Building evidence for guild chapters, workshop contacts and institution recognition comes from MCA's own village buildings, so those work with MCA alone; Townstead's building query is used instead when it is installed. Townstead adds life-stage evidence for NPC officeholders (without it, any adult villager qualifies), calendar, needs, schedule, spirit and reaction data. Every Townstead binding is probed member by member rather than by version: a release that kept the members works, one that moved them fails closed for exactly that capability, and start-up logs the installed version and the capabilities that bound, with a warning when the installed version is not the validated one (`politics_townstead_version` in `gradle.properties`).

Guild and contract contacts share one distance, `regional_civic_network.contactRange` in `config/mcakingdoms-civic-common.toml` (default 8 blocks, 2 to 32, with a clear line of sight). Kingdom Tasks lists nearby contacts within it, and contract offers, acceptance and turn-in check it. MCA: Crime's workshop legality check keeps its own 8-block rule. Quest definitions use `kingdom_lifecycle`; conversation topics use `kingdom_gate`. A quest lifecycle defaults to `offer_only`, so authors must select `mode: "live"` explicitly when an accepted quest should follow current political state. MCA Reputation synchronization is configured in `config/mcakingdoms-factions-common.toml` and defaults to `reputationSync.mode = SHADOW`. Live synchronization requires MCA Reputation 0.6.1 or later. With 0.6.0, MCA: Kingdoms reads local standing but receives no standing changes.

## Datapacks

Definitions use schema `1` JSON files below `data/<namespace>/mcakingdoms/`:

```text
kingdoms/<id>.json
name_pools/<id>.json
biome_rules/<id>.json
structure_styles/<id>.json
```

Kingdom definitions select a translation key, name pool, heraldry icon and color, optional metadata and style hints, and whether the kingdom is the single fallback. The `petition_honor` metadata entry names the honor definition an approved honor petition grants for that kingdom (for example `"petition_honor": "mypack:harbor_light"`); a kingdom without one grants `mcakingdoms:open_bell`. Name pools provide canonical names, reserved and blacklisted names, weighted templates, and tokens. Biome rules accept biome IDs and `#tag` selectors with priorities and exclusions. Structure-style rules map structure style IDs to kingdoms by priority.

The [built-in resources](src/main/resources/data/mcakingdoms/mcakingdoms/) are working examples. `/kingdoms reload` reloads datapack definitions. Existing settlement identities are retained; use the explicit reclassification or administration commands when a saved settlement should change.

## Java API

Addons can compile against `mcakingdoms-<version>-api.jar`. Obtain the server-scoped service after MCA: Kingdoms attaches it during server startup:

```java
KingdomsService kingdoms = McaKingdomsApi.get(server);
```

Calling `McaKingdomsApi.get` before the service is ready throws `IllegalStateException`. Call all `KingdomsService` methods on the Minecraft server thread; off-thread calls also throw `IllegalStateException`.

`KingdomsService` exposes kingdom, settlement, residence, civic-identity, and dialogue-context queries; controlled settlement and civic mutations; and registrations for settlement detectors, kingdom resolvers, and civic-evidence providers. Forge events under `dev.otectus.mcakingdoms.api.event` report definition reloads and settlement or resident lifecycle changes. The API artifact contains API classes only and does not expose MCA types.

## Development

The project uses the included Gradle wrapper and a Java 17 toolchain.

```text
./gradlew build
./gradlew runClient
./gradlew runServer
./gradlew runData
```

`build` runs the unit checks, verifies the API jar boundary, and produces:

```text
build/libs/mcakingdoms-<version>.jar
build/libs/mcakingdoms-<version>-api.jar
build/libs/mcakingdoms-<version>-sources.jar
```

`<version>` is `mod_version` in `gradle.properties`.

Forge 47 reads mixin configs only from a jar's `MixinConfigs` manifest attribute, so every development run passes `-mixin.config=mcakingdoms.mixins.json` instead. The Recruits hooks still apply only to Recruits 1.15.2: put that jar in `run/mods` to exercise warfare in a development run.

MCA remains a runtime-only integration and is not placed on the compile classpath or linked into the public API artifact. Development GameTests with MCA are currently blocked by an upstream SRG mixin incompatibility; the exact MCA integration was instead validated with the unchanged packaged mod in a production Forge server runtime.

See the [changelog](CHANGELOG.md) for this release and the [design specification](docs/mcakingdoms.md) for the wider architecture and future design context.

## License

All Rights Reserved.
