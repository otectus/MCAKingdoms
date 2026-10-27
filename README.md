# MCA: Kingdoms

MCA: Kingdoms is a server-authoritative settlement identity mod for Minecraft 1.20.1 and Forge. It recognizes villages, gives each one a persistent name and one of six kingdoms, and exposes that civic identity through Kingdom Tasks, two craftable books, entry titles, optional commands, datapacks, and a Java API. Governments, guilds, warfare and an evolving world build on that identity; each is optional and follows the server's settings.

The initial release includes Serenum, Lunari, Madera, Anemosia, Yew, and Shimaguni. A settlement's biome and structure style influence its first kingdom assignment. After creation, its stable identity, name, kingdom, bounds, aliases, and discovery information are saved with the world rather than recalculated on every lookup.

Shimaguni (`mcakingdoms:shimaguni`) follows the pack's seven Shimaguni histories: an island empire, the Island Covenant, and the Tide Archive at Akatsura. New jungle, sparse-jungle and bamboo-jungle settlements default to Shimaguni; datapacks can extend its biome tag. Existing saved settlements keep their assignments. Its red heraldry uses a moon above waves, and its political profile is `mcakingdoms:shimaguni_charter`. Akatsura is reserved from random naming so it can be designated deliberately; no capital or historical alliance is created automatically. Generated village names and the civic honor are new thematic content inspired by those histories.

## First session

1. Put the `mcakingdoms` jar in the `mods` folder of every client and the server. [MCA Reborn](#mca-reborn-integration) is optional.
2. Join a world and press the **Kingdom Tasks key — K by default**. It opens on a start view: where you are, up to three suggested next steps, your favourite and recent tasks, and the optional features this server has. Change the key under Options → Controls → Key Binds, in the **MCA: Kingdoms** category; the Controls… button on the Getting started panel opens that screen.
3. Walk into a village. The first time you enter a recognized settlement, a title shows its name, kingdom and heraldry.
4. In Kingdom Tasks, open **Getting started** and choose **Read this settlement**. **Your known settlements** and **Read a kingdom** are the other two read-only first steps. **What is available here?** lists which optional features this server enables.
5. Craft the two books when you want them:
   - **Book of Kingdoms**: one book and one paper, shapeless. Searchable chapters explain every system; most topics open the task they describe.
   - **Village Ledger**: paper across the top row; paper, book, paper in the middle row; a compass in the bottom middle. It lists the settlements you have discovered (plus any the server made public), by kingdom.

   ```text
   Paper  Paper    Paper
   Paper  Book     Paper
          Compass
   ```

   Both recipes appear in the recipe book once you hold a book (the Ledger also needs a compass); they can be crafted at any time. The Book's *Craft the Book of Kingdoms* and *Craft the Village Ledger* topics draw them as crafting grids from the connected server's current recipes, so a modpack that changes a recipe shows the changed one.

If Read this settlement finds nothing, you are not inside a recognized settlement yet: settlements are recognized from village structures and from clusters of village beds, bells and workstations in loaded chunks. Walk further in and try again. Without MCA Reborn, settlements, kingdoms, discovery, the Ledger and the built-in records all still work; MCA adds villagers' home evidence.

## Guides by audience

| You are | Start with |
| --- | --- |
| A player | [First session](#first-session), then the Book of Kingdoms in game; [Playing](#playing) summarizes the systems. |
| A server operator | [Operating a server](#operating-a-server), the [configuration reference](docs/Configuration.md) and, for an existing world, [Upgrading from Ultima Kingdoms](#upgrading-from-ultima-kingdoms). |
| A modpack creator | [Compatibility](#compatibility), [configuration presets](docs/presets/README.md) and [Installation](#installation). |
| A datapack author | The [datapack reference](docs/Datapack-Reference.md) and its [example pack](examples/datapack/README.md). |
| An addon developer | The [addon API guide](docs/Addon-API.md) and its [compile-checked example](examples/addon/README.md). |

The Book of Kingdoms is the in-game rules reference. The documents under `docs/` whose names start with R1–R4, and `docs/mcakingdoms.md`, are design and implementation records: where they differ from this README, the configuration reference or the datapack and API references, those current references win.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- MCA: Kingdoms installed on both the client and server

## Compatibility

Every companion is optional. Three things are recorded separately for each one:

- what the dependency metadata **accepts** (Forge loads it);
- the exact jar that was **validated** in acceptance runs (each listed with its SHA-256 in [`tools/test/companions.json`](tools/test/companions.json));
- what the newest inspected **public source** carries.

An accepted but unvalidated version may work, may bind fewer capabilities, or may be refused by a capability check. Server health reports which.

| Provider | Accepted by metadata | Validated artifact | Behaviour and public source |
| --- | --- | --- | --- |
| MCA Reborn | `[7.6,8)` | `7.6.26+1.20.1` | The adapter capability-probes four known MCA 7.x package roots. |
| Architectury API | No MCA: Kingdoms dependency | `9.2.14` | Install the version MCA Reborn itself requires. |
| Townstead | `[0.7.6,0.9)` | `0.7.6+1.20.1` | Bindings are probed member by member; Server health lists the capabilities that bound. |
| Recruits | `[1.15.2,)` permits launch | `1.15.2` | Military actions bind only to exactly `1.15.2`. Another version runs with the military integration unavailable, and Server health says so. |
| MCA Quests | `[1.6.5,)` | `1.7.1`, built from unreleased local work | Guild jobs, commissions and completion receipts use its public receipt API. **Paid workshop jobs** also need `McaQuestsApi.institutionalCompletionConsumer()` naming an `mcakingdoms` consumer. The public 1.7.1 source (`784f5d5`) does not have it; unreleased Quests work (1.7.2) adds it. Without it, Server health reports `legacy_consumer_contract` and paid workshop jobs are refused before any offer; ordinary guild work is unaffected. |
| MCA Reputation | `[0.6.0,)` | `0.6.1`, built from unreleased local work | Read-only with 0.6.0; live standing synchronization needs 0.6.1 or later. Carrying a pre-rename standing journal over to `mcakingdoms:factions` needs Reputation's unreleased outbox handoff. Without it, the renamed consumer starts at the journal tail, and Kingdoms reports the skipped range as a journal gap. |
| MCA: Crime | `[0.7.5,)` | `0.7.5` | Its workshop-legality provider is required for paid workshop jobs. The public 0.8.0 source has the same interface. |
| MCA Conversations | Not declared | `1.8.0` | Bound at runtime when present (conversation `kingdom_gate` topics). The public 1.8.1 source recognizes both mod ids. |
| MCA: Capitals | Not declared | — | When installed, MCA: Kingdoms turns its native governance off. No interoperability between the two political systems is claimed. |

The companions bind the renamed `mcakingdoms` id. For now they also recognize a pack that still ships the old Ultima Kingdoms jar. Removing that is a coordinated release step; see [Upgrade and recovery](docs/Upgrade-and-Recovery.md#old-names-runtime-aliases-and-on-disk-migration).

## Installation

Place the `mcakingdoms` jar (`build/libs/mcakingdoms-<version>.jar`) in the `mods` directory of every client and server. The `-api.jar` is a compile-time artifact for addon developers, and the `-sources.jar` contains source attachments; neither belongs in a game instance's `mods` directory.

For MCA integration, also install MCA Reborn `7.6.26+1.20.1` and its Architectury dependency on both client and server. The validated combination uses Architectury API `9.2.14`.

## Playing

MCA: Kingdoms examines loaded chunks for structures in Minecraft's village structure tag and for clusters of village points of interest. A newly recognized settlement receives a stable identity and a kingdom based on the supplied biome and structure-style definitions. Operators can also register or discover settlements explicitly.

One generated village is one settlement. Its identity is the village's structure start: a point-of-interest cluster, an MCA village or a manual registration inside a generated village's footprint (its structure box plus `structureMergeMargin` blocks) belongs to that village, whichever is found first. Records that an earlier version split from one village are merged into it as their chunks are scanned, and once at server start until the world records that start-up pass as done; the village keeps the name and kingdom of its structure record (or, failing one, of its oldest highest-ranked record) unless a merged record's name or kingdom was locked and the village's was not; the other names stay searchable as aliases, and old identifiers resolve to the village. A merge that politics or warfare refuses (for example, both records hold the same local office) is logged once and left intact. With the default settings, a point-of-interest cluster is considered only with at least five points, a bed or bell among them, and near the surface.

**Kingdom Tasks** (K by default) is the normal interface, presented as a parchment folio with leather edges and white controls. Wide start pages place suggestions beside your recent and favourite tasks; compact windows use one scrollable column. Ctrl+F focuses search or the current answer field. It opens on the start view described under [First session](#first-session); **All tasks** lists everything you can use and **Categories…** groups it as Places, Guilds and jobs, Government, Exploration, Conflict, World events and Help (operator tools separately). Search by words in any order — "join guild", "rename village" — or by a goal such as "paid work", "make peace" or "where am I", in your own language; "village" and "settlement", "job" and "commission" find the same tasks. A task this server has turned off, or whose companion mod is missing, says so in the list instead of failing after you choose it.

The server offers named choices, so you pick people, places and records by name, never by copying identifiers. Where a task has conditions — a guild's standing threshold, a contact nearby, a responsibility in a kingdom, a shared transfer destination — it lists them first, each marked **✓ Ready**, **✗ Needed** or **? Could not check**; the server checks every condition again when you apply. Tasks that change records end on a review page listing the selected people, places, terms and what will change, and nothing changes until you press the button that names the action (for example *Join Lamplighters* or *Send agreement proposal*); you can go back and edit any answer, and answers that depend on it are asked again. The result says whether the action was done, is waiting for another step, was refused (and why), or is not confirmed; when no reply arrives, **Check this action** reads the current state instead of sending the action again. Read-only tasks show their result at once; **☆ Favourite** on a result keeps that task on your start view.

The **Book of Kingdoms** is a leather-bound royal chronicle with parchment leaves, illuminated openings, chapter ribbons and an illustrated frontispiece. Single arrows turn pages; double arrows change topics. Ctrl+F focuses search and Alt+Left goes back. It has chapters from first steps to government, guilds, warfare, the evolving world and server administration. Topics use numbered steps, crafting grids, labelled tips and requirements, diagrams of multi-step processes (agreements, recruit transfers) and a gallery of the server's kingdoms. Search ranks titles first, then keywords, summaries and the text. **Back** returns to the previous topic at the same place, **Home** opens *Start here*, and **☆ Bookmark** keeps a topic under **Saved** with your recent topics. Links open other topics or the task a topic describes; a topic about an operator-only task offers a related player task, or says that only operators can use it. Command references are folded under *Commands and advanced details* except in the operator and reference chapters. In a narrow window the contents and the article share the screen through a **Contents** / **Article** switch.

The **Village Ledger** uses the same manuscript design. On wide screens, its settlement index stays on the facing page while you read a record. It searches the settlements you know by name or former name, sorts them by name or distance, and filters by kingdom from a picker that shows each kingdom's heraldry. A settlement's details show its kingdom, dimension, location and distance, and link to its government, guilds, War Room and tasks. The **Guilds** screen shows your membership, standing and rank progress, each service with its member and independent requirements, the nearest contact, guild jobs and recent service; joining, leaving and using a service go through the reviewed Kingdom Tasks steps. **Kingdom** pages start with the kingdom's state, leader, capital, regency, open elections and your own responsibilities, and show records as labelled fields with their actions (sign an agreement, review a petition, revalidate an institution). The **War Room** separates control, campaigns, orders and civilian work; its buttons open real tasks for the settlement shown, not chat commands, and a button whose task this server cannot run (military actions without Recruits 1.15.2, for example) says so and explains why.

War Room, Guilds, government, Settings, Kingdom Tasks and both book items use the shared [manuscript design](docs/Book-Interface-Design.md), with native button, text-field, focus, tooltip and narration behavior. Ivory pages sit inside crimson, green and walnut bindings, with white text on dark leather controls. The Book, Ledger and Tasks each have their own illustration: a royal landscape, riverside settlement or working guild courtyard. Settings sliders, cycle controls and its history-clear confirmation share the same materials, while the settlement banner and Blueprint header use compact parchment plaques. The Ledger kingdom picker pages through every choice even in compact windows. Tab moves through the buttons and into an article or record, where it moves from link to link; Enter or Space activates the selected link, and the arrow keys, Page Up/Page Down, Home and End scroll. The mouse wheel and scrollbar dragging also work. Recipes, diagrams and galleries have a text version for the narrator. See the [GUI audit](docs/MCAKingdoms-GUI-Audit.md).

The first time a player enters a recognized settlement, the client shows its name, kingdom and heraldry. The server remembers each player's visits with the world, so by default this title appears once per settlement; the **MCA: Kingdoms settings** screen (Kingdom Tasks → Settings, or Mods → MCA: Kingdoms → Config) can show it on every entry, only at kingdom borders, or never, and previews its timing and position. The same screen chooses what the Kingdom Tasks key opens, hides the start view's introduction, stops recipe ingredients from cycling, and clears your bookmarks and history. The title waits while another screen is open, and is dropped if you leave the settlement or change dimension first.

Beyond identity, the optional systems are:

- **Kingdom standing**, with optional MCA Reputation synchronization that starts in observe-only `SHADOW` mode.
- **Your residence**: **Register my residence** in Kingdom Tasks records a settlement as your civic home. The server first checks its own evidence: your respawn point, set by sleeping in a bed, lies inside the settlement, and you are standing there. **Move my residence** changes it after a wait (one Minecraft day of server time by default), and **Check my residence** shows what the server sees. It is a civic record, not land ownership: it grants no office, land, guild membership or war duty. Sleeping, visiting or trading never moves it. `/kingdoms residence status|register|move` are the command equivalents.
- **Governments**: capital charters, offices, mandates, petitions, institution recognition, honors, peaceful agreements, elections and regency. With `politics.openFounding` on (the default), a player whose recorded civic residence is in a kingdom can found its first government with themselves as leader; operators can always found. See [political usage](docs/politics/usage.md) and [ownership](docs/politics/ownership.md).
- **Political notices and office badges** (optional). Each player can turn on notices under Settings → Politics, as a chat line or a chat line plus a silent banner. Notices cover foundings, accessions, vacancies and a memorial for a confirmed death. They come from the committed political record, once each; private mandates and proposals are never announced. A small line above public office holders near you names their office, without changing their name, skin or equipment. Kingdom Tasks' person pickers and the council list name offices too.
- **Guilds and service**: Lamplighters membership, verified quest service, chapter contacts, introductions, commissions and paid workshop jobs. Guild jobs are quests from the modpack's content and need MCA Quests. See the [civic foundation record](docs/R1-Civic-Foundation.md) and [institutions record](docs/R2-Institutions-and-Agreements.md).
- **Warfare and exploration**: campaigns, settlement control agreements, temporary recruit deployments (Recruits 1.15.2), civilian recovery contracts, and discovered world sites and routes. See the [native control record](docs/R3-Native-Control.md).
- **An evolving world**: opportunities, protection pacts, organization history, family introductions and recruit transfers. It is off for every world until an operator opts that world in; `autoEligibleRegions` then decides which settlements are eligible regions, it never opts a world in. See the [evolving world record](docs/R4-Evolving-World.md).

## MCA Reborn integration

With the supported MCA version installed, MCA: Kingdoms reads MCA's village records to recognize settlements and reads an MCA villager's current home as positive residence evidence. An MCA villager's first observed civic residence becomes their origin if no origin is already recorded. Newborns inside an already recognized settlement can also receive that settlement as their initial civic identity. Players' residences are never inferred from movement, sleeping or trading. A player registers one explicitly, with the server checking their bed-set respawn point and presence; an operator or an addon can also record one.

The integration adds separate MCA: Kingdoms civic data to entities. It does not write MCA personal names, family relationships, homes, genetics, personalities, dialogue state, or relationship values. Renaming an MCA: Kingdoms settlement therefore changes its civic label without renaming its residents or changing their MCA family and home data.

## Operating a server

- **Server health** (Kingdom Tasks, operators) and `/kingdoms health` (also from the console) report the feature switches, installed providers and the capabilities that bound, saved-data writability, the last definition reload, this start's Ultima migration results, and discovery and reconciliation activity: work queued and done, refused merges, and whether the one-time start-up pass is recorded as done for this world.
- **Reloading definitions** (`/kingdoms reload`, or Reload server definitions in Kingdom Tasks) reports each definition domain as accepted or rejected. A rejected domain keeps its previous definitions, and the report names the file, field and expected value. See the [datapack reference](docs/Datapack-Reference.md#reload-and-failure-behaviour).
- **Reconcile nearby village records** (or `/kingdoms village reconcile [radiusChunks]`) merges split records of one generated village in the loaded area around you. Its review shows how many records are in scope and what a merge keeps; the result reports what was merged and what lies in no known village.
- **Capacity**: Server health's Capacity section lists every record collection at 80% or more of its bound, and `/kingdoms maintenance capacity` lists them all. Finished recruit transfers and opportunities can be archived with a dry-run-first command. Nothing is deleted automatically. See [Capacity and record maintenance](docs/Capacity-and-Maintenance.md).
- **Fail-closed starts**: the server stops instead of starting empty in two cases: a settlement file this version cannot read completely, and a failed Ultima Kingdoms carry-over. The log names the file and the remedy. See the [upgrade and recovery runbook](docs/Upgrade-and-Recovery.md), which also covers downgrades, read-only stores, interrupted operations and companion contract problems.
- `/kingdoms help [topic]` prints command syntax. Clicking a line puts it in the chat box; nothing runs until you send it.

## Upgrading from Ultima Kingdoms

This mod was called Ultima Kingdoms until 0.1.1. The rename changed its mod id (`ultima_kingdoms` to `mcakingdoms`), its package, its commands (`/ultima` to `/kingdoms`), its config file names and every name it saves. A world and configuration from Ultima Kingdoms carry over automatically the first time the renamed jar starts:

- Each saved-data file `world/data/ultima_kingdoms_*.dat` is copied to `mcakingdoms_*.dat`, with every `ultima_kingdoms:` id inside rewritten to `mcakingdoms:` (settlement slugs, kingdoms, organizations, petitions, honors, journal cursors).
- Each config file is copied to its new name (for example `ultima-kingdoms-warfare-common.toml` to `mcakingdoms-warfare-common.toml`).
- A villager's civic identity moves to its new key the first time the villager is observed. Players already welcomed are not welcomed again.
- Book of Kingdoms and Village Ledger items in inventories and containers load as the renamed items.
- A slug written with the old namespace, such as `ultima_kingdoms:aldwick`, still finds its settlement.

A file is copied only when its new name does not exist yet, and the old files are never changed or deleted. Advancement, recipe-book and statistics progress recorded under the old namespace is not carried over: the welcome advancement can appear once more, and the two recipes unlock again when their items are held. A datapack that ships definitions under `data/ultima_kingdoms/` must move them to `data/mcakingdoms/`. Content ids under the pack's own `ultima:` namespace (for example `ultima:civic/lamplighters/workshop_lanterns`) are not the old `ultima_kingdoms:` namespace and stay as they are.

**Upgrade runbook:**

1. Stop the server and back up the complete world folder and `config/`.
2. Update the companions to builds that bind `mcakingdoms` (see [Compatibility](#compatibility)), and move any pack-owned datapack folders from `data/ultima_kingdoms/` to `data/mcakingdoms/`.
3. Start the server once, before anyone joins. Open Server health or run `/kingdoms health`: the migration section lists each file as copied, skipped because its new name already existed, or failed. Only this start's results are shown; earlier starts are not recorded.
4. If every file copied, check that settlements, known places, governments, guild memberships and kingdom standing look right, then reopen the server.
5. If a file failed, the server does not start. Since 0.3.0 a failed copy of any saved-data file, or of a common config file, stops the world before any store is created. The log and `world/data/mcakingdoms_migration_report.txt` name each failed file and why. Nothing was written in its place, and the old file is untouched. Fix the cause (permissions, disk space, or a damaged old file restored from the backup) and start again. Copies that succeeded are kept; only the failed ones are retried. Do not delete existing `mcakingdoms_*` files to force a copy: they may hold newer play.
6. The kingdom standing *import* from older standing data is a separate, operator-reviewed step (Preview legacy reputation migration, then Import reviewed legacy reputation). It is not part of the namespace migration.

Copying old files is permanent **on-disk migration** support. Reading old names during play is a separate set of **runtime aliases**: old slugs, item names and civic-identity keys. Those aliases are removed only in a coordinated release with the companions, and the on-disk migration stays. See [Upgrade and recovery](docs/Upgrade-and-Recovery.md#old-names-runtime-aliases-and-on-disk-migration).

## Optional command reference

Kingdom Tasks is the normal player interface. The commands below remain useful for console automation and players who prefer chat; `/kingdoms help [topic]` prints the same syntax in game. Record selectors use readable names; quote a name when it contains spaces. Kingdom arguments in this basic command tree use namespaced definition names such as `mcakingdoms:serenum`. To supply a new settlement name, provide the radius first. The new name for `create` or `rename` consumes the rest of the command as plain text, so do not quote it; quotes there become part of the name. For example:

```text
/kingdoms village rename "Old Bellmeadow" New Bellmeadow
/kingdoms village info "Old Bellmeadow"
/kingdoms village create 64 New Bellmeadow
```

The following commands are available to players:

```text
/kingdoms help [topic]
/kingdoms kingdom list
/kingdoms kingdom info <kingdom-definition-id>
/kingdoms village here
/kingdoms village info ["settlement name"]
/kingdoms residence status
/kingdoms residence register
/kingdoms residence move
```

The remaining commands require permission level 2:

```text
/kingdoms health
/kingdoms village create [radius] [name]
/kingdoms village rename "Old Bellmeadow" <new name...>
/kingdoms village setkingdom "Old Bellmeadow" <kingdom-definition-id>
/kingdoms village reclassify "Old Bellmeadow"
/kingdoms village lock "Old Bellmeadow"
/kingdoms village unlock "Old Bellmeadow"
/kingdoms village discover [radiusChunks]
/kingdoms village reconcile [radiusChunks]
/kingdoms village merge "Old Bellmeadow" "New Bellmeadow"
/kingdoms village debug ["settlement name"]
/kingdoms citizen info <entity>
/kingdoms citizen setorigin <entity> "Old Bellmeadow"
/kingdoms citizen setresidence <entity> "Old Bellmeadow"
/kingdoms residence move now
/kingdoms maintenance capacity
/kingdoms maintenance compact transfers [apply]
/kingdoms maintenance compact opportunities [apply]
/kingdoms reload
```

`residence move now` moves the operator's own residence without waiting for the cooldown; the evidence checks still apply. The `compact` commands are dry runs until `apply` is added.

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
/kingdoms drama …         world event proposals
```

Every one of these is also a Kingdom Tasks form.

## Configuration

MCA: Kingdoms writes eight files to `config/`. The [configuration reference](docs/Configuration.md) lists every key with its default, range, units and when a change applies; [presets](docs/presets/README.md) show a peaceful and a warfare-focused setup to copy by hand.

| File | Covers |
| --- | --- |
| `mcakingdoms-common.toml` | Settlement discovery and village identity, reconciliation, resident founding (`politics.openFounding`), political notices and office badges, NPC civic evidence and player residence registration (22 keys) |
| `mcakingdoms-client.toml` | Each player's settlement entry title, what Kingdom Tasks opens to, its introduction, recipe animation, and political notices and office badges (also in the in-game settings screen) |
| `mcakingdoms-civic-common.toml` | Guild chapter services, institutional services and the contact range (default 8 blocks, 2 to 32) |
| `mcakingdoms-factions-common.toml` | Kingdom standing and MCA Reputation synchronization (`SHADOW` by default) |
| `mcakingdoms-warfare-common.toml` | Military actions, civilian contracts, target protection and world context |
| `mcakingdoms-evolution-common.toml` | Evolving-world budgets; each world still opts in separately |
| `mcakingdoms-integrations-common.toml` | Kingdom gates for MCA Quests and conversation content |
| `mcakingdoms-townstead-common.toml` | The Townstead adapter |

Townstead is optional. Building evidence for guild chapters, workshop contacts and institution recognition comes from MCA's own village buildings, so those work with MCA alone; Townstead's building query is used instead when it is installed. Townstead adds life-stage evidence for NPC officeholders (without it, any adult villager qualifies), calendar, needs, schedule, spirit and reaction data. Quest definitions use `kingdom_lifecycle`; conversation topics use `kingdom_gate`. A quest lifecycle defaults to `offer_only`, so authors must select `mode: "live"` explicitly when an accepted quest should follow current political state.

## Datapacks

Core definitions use schema `1` JSON files below `data/<namespace>/mcakingdoms/`:

```text
kingdoms/<id>.json
name_pools/<id>.json
biome_rules/<id>.json
structure_styles/<id>.json
```

Political definitions, organizations and civic rules, kingdom gates, world-context selectors, evolution scenarios and world event templates have their own folders. The [datapack reference](docs/Datapack-Reference.md) lists every folder with its fields, limits, reload behaviour and failure handling, and the [example pack](examples/datapack/README.md) is a working starting point. The [built-in resources](src/main/resources/data/mcakingdoms/mcakingdoms/) are also working examples. Existing settlement identities are retained across reloads; use the explicit reclassification or administration tasks when a saved settlement should change.

## Java API

Addons compile against `mcakingdoms-<version>-api.jar` and run with the main jar installed. Obtain the server-scoped service after MCA: Kingdoms attaches it during server startup:

```java
KingdomsService kingdoms = McaKingdomsApi.get(server);
```

Calling `McaKingdomsApi.get` before the service is ready throws `IllegalStateException`. Call all `KingdomsService` methods on the Minecraft server thread; off-thread calls also throw `IllegalStateException`. The API artifact contains API classes only and does not expose MCA types. The [addon API guide](docs/Addon-API.md) documents lifetimes, mutations, errors and visibility, and the [addon example](examples/addon/README.md) is compiled by `./gradlew check`.

## Development

The project uses the included Gradle wrapper and a Java 17 toolchain.

```text
./gradlew build
./gradlew runClient
./gradlew runServer
./gradlew runData
```

`build` runs the unit checks, compiles the addon example against the API jar, verifies the API jar boundary, and produces:

```text
build/libs/mcakingdoms-<version>.jar
build/libs/mcakingdoms-<version>-api.jar
build/libs/mcakingdoms-<version>-sources.jar
```

`<version>` is `mod_version` in `gradle.properties`. `python3 tools/test/run_gametests.py` runs the GameTests; move `run/world` aside first, because several settlement tests expect a fresh world.

The packaged acceptance and client scripts under `tools/test/` and `tools/client-test/` find their prerequisites through `tools/test/environment.py`, never through paths in the source. Those prerequisites are an accepted EULA file, a Forge installation, a modpack instance, Java 17, an Xvfb binary and a Gradle wrapper. Supply them as environment variables (`MCAKINGDOMS_EULA`, `MCAKINGDOMS_MC_INSTALL`, `MCAKINGDOMS_PACK`, `MCAKINGDOMS_JAVA`, `MCAKINGDOMS_XVFB`, `MCAKINGDOMS_GRADLEW_QUIET`) or in an ignored `tools/test/local-environment.json` (see `local-environment.example.json`). `python3 tools/test/environment.py` reports what was found, and a missing prerequisite stops a script with its name. `python3 tools/test/verify_companions.py --pack` checks the instance's companion jars against `tools/test/companions.json`.

Forge 47 reads mixin configs only from a jar's `MixinConfigs` manifest attribute, so every development run passes `-mixin.config=mcakingdoms.mixins.json` instead. The Recruits hooks still apply only to Recruits 1.15.2: put that jar in `run/mods` to exercise warfare in a development run.

MCA remains a runtime-only integration and is not placed on the compile classpath or linked into the public API artifact. Development GameTests with MCA are currently blocked by an upstream SRG mixin incompatibility; the exact MCA integration was instead validated with the unchanged packaged mod in a production Forge server runtime.

See the [changelog](CHANGELOG.md) for this release and the [design specification](docs/mcakingdoms.md) for the wider architecture and design history.

## License

All Rights Reserved.
