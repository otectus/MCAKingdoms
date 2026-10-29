# Datapack reference

Every MCA: Kingdoms definition is a JSON file in a data pack. This page lists each folder, its fields and limits, and what a reload does when a file is wrong. The runtime loaders are authoritative; this page follows them as of this version. The [built-in definitions](../src/main/resources/data/mcakingdoms/) are working examples of every format, and the [example pack](../examples/datapack/README.md) adds one of each common kind.

## What goes where

| Kind of content | Shipped as | Read by |
| --- | --- | --- |
| Kingdoms, names, assignment rules, politics, guilds, gates, world context, evolution | a **data pack** (`data/…`), on the server | MCA: Kingdoms, at every data pack reload |
| Kingdom names, definition titles and their translations; Book of Kingdoms pages | a **resource pack** (`assets/<namespace>/lang/…`, `assets/mcakingdoms/guide/<language>/…`), on each client | the client |
| Quests behind guild jobs and commissions | the modpack's **MCA Quests content** | MCA Quests; a guild definition only names those quests |

A definition never implements a provider operation by itself: naming an outcome, a quest or a building family only refers to something the owning mod already does.

## Folders

`<ns>` is your pack's namespace. A definition's id is `<ns>:<path inside the folder, without .json>`, so `data/example/mcakingdoms/kingdoms/harbor_league.json` defines `example:harbor_league`. A file with the same id in a later pack replaces the earlier one, as usual for data packs.

| Folder | Defines | Schema | On a rejected file |
| --- | --- | --- | --- |
| `data/<ns>/mcakingdoms/kingdoms/` | kingdoms | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms/name_pools/` | settlement name pools | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms/biome_rules/` | biome-to-kingdom rules | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms/structure_styles/` | structure-style-to-kingdom rules | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms/governments/`, `offices/`, `agreements/`, `petitions/`, `honors/`, `institution_charters/` | political definitions | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms/kingdom_gates/` | named kingdom gates for addon content | `"schema": 1` optional | reload fails |
| `data/<ns>/mcakingdoms_factions/factions/` | guilds and other voluntary organizations | `"schema": 1` required | reload fails |
| `data/<ns>/mcakingdoms_factions/civic_network/` | a guild's chapter, building and commission rules | `"schema": 1` required | that domain keeps its previous rules |
| `data/<ns>/warfare/world/` | world-site and encounter selectors | none | that domain keeps its previous selectors |
| `data/<ns>/evolution/scenarios/` | evolving-world opportunities | `"schema": 1` required | reload fails |
| `data/<ns>/evolution/drama/` | world event templates | none | reload fails |
| `data/<ns>/mcakingdoms/succession_laws/` | laws of succession built on a lineage law | `"schema": 1` required | that domain keeps its previous laws and ranks |
| `data/<ns>/mcakingdoms/noble_ranks/` | court ranks and their per-kingdom styles | `"schema": 1` required | that domain keeps its previous laws and ranks |
| `data/<ns>/mcakingdoms/development_templates/` | public works for settlement development | `"schema": 1` required | that domain keeps its previous templates |

Any other folder under `data/<ns>/mcakingdoms/` is an error ("unknown definition folder").

## Reload and failure behaviour

`/kingdoms reload` and **Reload server definitions** in Kingdom Tasks reload all data packs and report each MCA: Kingdoms domain:

- **Accepted** — "Reload complete. Definitions in use: …" with the counts per kind.
- **A folder marked "reload fails" rejects a file** — the whole reload fails, as with any data pack error, so nothing changes: "Reload failed. No definitions changed; the definitions that were active before remain in use." The report names the domain, the file, the field, what was found and what is expected (at most three problems per domain; the server log has all of them).
- **Civic rules or world-context selectors reject a file** — the rest of the reload commits, and only that domain keeps its previous definitions: "Reload completed with rejected definitions; previous definitions remain active for the affected domains."
- **The next valid reload** replaces the report; an earlier failure is not shown again.

Server health keeps the last report. At server start, a rejected file in a "reload fails" folder stops the data packs from loading, the same as any data pack error: fix the file and start again (or, on a single-player world, Minecraft offers to start with vanilla data only). If civic rules or selectors are rejected at the very first load, that domain has no definitions until a valid reload, and the report says so.

Saved records are not rewritten by a reload. Settlements keep their names and kingdoms; an accepted job, proposal, agreement or opportunity keeps the terms it was accepted under. Removing a definition that saved records still use makes the dependent actions unavailable (for example, a government whose charter disappeared is dormant) rather than deleting anything.

## Core definitions

### Kingdoms — `mcakingdoms/kingdoms/`

| Field | Required | Value |
| --- | --- | --- |
| `schema` | yes | `1` |
| `id` | no | if present, must equal the file's id |
| `translation_key` | yes | language key for the kingdom's name, from a resource pack (see [Names and translations](#names-and-translations)) |
| `name_pool` | yes | id of a loaded name pool |
| `heraldry.icon` | yes | id of a heraldry icon; the client draws `<namespace>:textures/gui/heraldry/<path>.png`, so a new icon needs a resource pack texture (the example reuses `mcakingdoms:shimaguni`) |
| `heraldry.color` | yes | `"#RRGGBB"` or an integer from 0 to 16777215 |
| `style_hints` | no | structure style ids used when no structure-style rule matches |
| `metadata` | no | string-to-string map; `petition_honor` names the honor an approved honor petition grants (default `mcakingdoms:open_bell`); `heraldry_secondary` (`"#RRGGBB"`) is the second tincture of the kingdom's banners and shields (default: a darker shade of `heraldry.color`); `heraldry_motto` is a translation key shown when a banner is used |
| `fallback` | no | `true` on exactly one kingdom across all packs; it receives settlements no rule matches |

At least one kingdom must exist, and exactly one must be the fallback (the built-in fallback is Serenum).

### Name pools — `mcakingdoms/name_pools/`

| Field | Required | Value |
| --- | --- | --- |
| `schema` | yes | `1` |
| `id` | no | if present, must equal the file's id |
| `canonical` | yes | non-empty list of names, unique after normalization; used first |
| `reserved` | no | names never generated (kept for deliberate designation) |
| `blacklist` | no | names never generated |
| `templates` | yes | non-empty list of `{"format": "{prefix}{suffix}", "weight": 3}`; each format needs at least one `{token}`, every token must exist, weight at least 1 |
| `tokens` | yes | object of token name (`a-z`, `0-9`, `_`) to a non-empty list of strings |
| `retries` | no | 1–256, default 64 |

### Biome rules — `mcakingdoms/biome_rules/`

| Field | Required | Value |
| --- | --- | --- |
| `schema` | yes | `1` |
| `kingdom` | yes | id of a loaded kingdom |
| `priority` | yes | integer; higher wins |
| `include` | yes | non-empty list of biome ids or `#biome_tag` ids |
| `exclude` | no | biome ids or tags that never match |
| `environmental` | no | `true` for a weaker rule consulted only after structure styles |

Several points around a new settlement are sampled. At each point the best rule wins: an exact biome id before a tag, then the higher priority. If any point matched an exact id, tag matches are ignored. The kingdom with the most sample weight wins. Rules are consulted in this order: an explicit kingdom (manual registration or an addon), ordinary biome rules, structure-style rules (then kingdom `style_hints`), environmental biome rules, the fallback kingdom.

### Structure styles — `mcakingdoms/structure_styles/`

| Field | Required | Value |
| --- | --- | --- |
| `schema` | yes | `1` |
| `kingdom` | yes | id of a loaded kingdom |
| `priority` | no | integer, default 0; higher wins |
| `styles` | yes | non-empty list of structure style ids (for example `minecraft:desert`) |

## Political definitions — `mcakingdoms/<family>/`

The folder decides the kind: `governments` → `government`, `offices` → `office`, `agreements` → `agreement`, `petitions` → `petition`, `honors` → `honor`, `institution_charters` → `institution`. Field names are camelCase. Write every field shown in the [built-in examples](../src/main/resources/data/mcakingdoms/mcakingdoms/governments/serenum_charter.json); a missing list fails the file.

| Field | Value |
| --- | --- |
| `schema` | `1` |
| `kind` | must match the folder |
| `title` | translation key (or plain text), at most 128 characters |
| `offices` | office ids; a government must include `mcakingdoms:leader`; at most 32 |
| `permissions` | for offices: `APPOINT`, `SEAT`, `PROPOSE`, `RATIFY`, `REVIEW`, `HONOR`, `RECOGNIZE`, `DELEGATE` |
| `requires` | agreement ids that must already be in force (agreements); at most 16 |
| `buildingTypes` | building families an institution needs (for example `workshop`); at most 64 |
| `durationTicks` | 0 to 2400000000 |
| `maxOffices` | 1–16 |
| `independentApproval` | `true` or `false` |
| `commissionPool` | id or `""` |
| `clauses` | agreements only: `HOSPITALITY`, `INTRODUCTIONS`, `WORKSHOP_ACCESS`, `CIVIC_AID`, `INTELLIGENCE_EXCHANGE`; an agreement with none is ceremonial |

Every id in `offices` must be a loaded office, and every id in `requires` a loaded agreement.

## Kingdom gates — `mcakingdoms/kingdom_gates/`

A named gate that MCA Quests conditions (`mcakingdoms:kingdom` with `"gate": "<id>"`) and conversation `kingdom_gate` topics can refer to.

| Field | Required | Value |
| --- | --- | --- |
| `schema` | no | `1` if present |
| `subject` | no | whose kingdom is checked: `giver_residence` (default), `giver_origin`, `player_location`, `giver_location`, `explicit_settlement` |
| `include` | no | kingdom ids; empty means any kingdom not excluded |
| `exclude` | no | kingdom ids that never match |
| `when_unknown` | no | `deny` or `allow` when the kingdom cannot be determined; if omitted, `kingdomGating.unknownKingdomPolicy` in `mcakingdoms-integrations-common.toml` decides (default `deny`) |

No other field is allowed.

## Guilds — `mcakingdoms_factions/factions/`

Strict: unknown fields are errors. At most 128 organizations.

| Field | Value |
| --- | --- |
| `schema` | `1` |
| `id` | must equal the file's id |
| `kind` | `guild`, `order`, `institution` or `civic_group` |
| `military` | `false` (military organizations belong to Recruits and are refused) |
| `name_key`, `description_key` | translation keys of `a-z0-9_.-`, at most 128 and 160 characters |
| `membership.exclusive_group` | id or `null`; members may hold only one organization of a group |
| `membership.conflicts` | organization ids, at most 64; conflicts must be listed on both sides |
| `ranks` | 1–32 of `{"id", "minimum_standing", "permissions"}`, with strictly increasing `minimum_standing` |
| `services` | up to 64 of `{"permission", "minimum_rank", "minimum_standing", "required_deeds"}`, optionally with both `neutral_minimum_standing` and `neutral_required_deeds` (never easier than member access); the permission must be granted by the minimum rank |
| `deeds` | up to 128 of `{"quest_id", "credit"}`; credit 1–10000; each quest once |

Example: [Lamplighters](../src/main/resources/data/mcakingdoms/mcakingdoms_factions/factions/lamplighters.json).

## Guild chapter rules — `mcakingdoms_factions/civic_network/`

One file per organization; at most 128. Unknown fields are errors.

| Field | Value |
| --- | --- |
| `schema` | `1` |
| `organization` | the organization id |
| `sponsor_quests` | quest ids whose verified completion counts as service, at most 128 |
| `building_families` | 1–16 building families a chapter's institution may use (`a-z0-9_`) |
| `commissions` | quest ids offered as commissions, at most 32 |

The quests themselves come from the pack's MCA Quests content; if none of the listed commission quests is installed, the service says so instead of opening an empty menu. Example: [Lamplighters](../src/main/resources/data/mcakingdoms/mcakingdoms_factions/civic_network/lamplighters.json).

## World context — `warfare/world/`

No schema field. Every file adds to one shared list, and **each structure or entity id may appear in only one file across all packs**; listing one twice rejects the domain.

| Field | Value |
| --- | --- |
| `structures` | `{"id": "<structure id>", "role": "<role>"}`; the built-in roles are `settlement`, `hostile_outpost`, `hostile_stronghold`, `archaeological_site`, `abandoned_institution` and `ruin` |
| `encounters` | `{"id": "<entity id>", "role": "<role>", "repeat_budget": 1–32 (default 1), "contribution_window": 20–12000 ticks (default 20)}` |

Roles are lower-case tokens of up to 48 characters. Example: [defaults](../src/main/resources/data/mcakingdoms/warfare/world/defaults.json).

## Evolving-world opportunities — `evolution/scenarios/`

At most 64. Opportunities appear only in worlds an operator opted in.

| Field | Value |
| --- | --- |
| `schema` | `1` |
| `title` | text shown to players, at most 128 characters (shown as written; not translated) |
| `trigger` | `INSTITUTION`, `INTERREGNUM`, `OCCUPATION`, `DEMAND`, `FAMILY` or `GRIEVANCE` |
| `outcomes` | 2–6 distinct of `AID`, `MEDIATE`, `INTRODUCE`, `NEGOTIATE`, `SUCCEED`, `DECLINE`; `DECLINE` is required |
| `duration`, `cooldown` | 1200–24000000 ticks |
| `contributions` | 1–64 |
| `dramatic` | `true` makes it a world-event opportunity, offered only where world events are also enabled |

## World event templates — `evolution/drama/`

At most 32. No schema field; field names are camelCase.

| Field | Value |
| --- | --- |
| `title` | text, at most 128 characters |
| `kind` | `CAMPAIGN`, `REBELLION`, `SCHISM`, `INVASION` or `REGENCY` |
| `outcomes` | from `DECLARE`, `NEGOTIATE`, `EXIT`, `RECOVER`, `FOUND_SCHISM`, `APPOINT_REGENT`; must include `EXIT` or `NEGOTIATE` |
| `goal` | campaign goal, required for `CAMPAIGN`, `REBELLION` and `INVASION`: `DEFEND`, `RELIEF_ACCESS`, `WITHDRAWAL`, `AUTONOMY`, `TRANSFER` |
| `objective` | text, at most 512 characters |
| `durationTicks` | 1200–2419200 |
| `maxParticipants` | 2–8 |
| `operationBudget` | 1–16 |
| `requiresOccupation` | must be `true` for `REBELLION` |
| `organizationTemplate`, `organizationName` | required for `SCHISM` |

## Succession laws — `mcakingdoms/succession_laws/`

A government starts with `mcakingdoms:manual_only` and changes law only when its leader or an operator adopts one. Five
laws are built in (`manual_only`, `designated_then_lineage`, `equal_primogeniture`, `elective_monarchy`,
`council_confirmation`) and cannot be replaced. A data-pack law is built on one of the two lineage laws:

| Field | Required | Meaning |
| --- | --- | --- |
| `schema` | yes | `1` |
| `base` | yes | `designated_then_lineage` (the named successor first) or `equal_primogeniture` |
| `title` | yes | translation key of the law's name |
| `relations` | yes | at least one of `child`, `grandchild`, `sibling`, and optionally `recognized` (members the crown recognized); the order is always children, grandchildren, siblings, recognized, each by stable person id |
| `confirmation` | no | `none` (default) or `council`: a majority of the council confirms the heir |
| `minor_regency` | no | `true` (default): a minor heir waits under a regency until verified adulthood; `false`: such a succession waits for review |
| `players_eligible`, `npcs_eligible` | no | `true` (default): whether players and villagers may inherit |

An elected successor always comes first and an elected ruler's office is never decided by lineage, whatever the law.
Example: `data/example/mcakingdoms/succession_laws/harbor_council.json`.

## Court ranks — `mcakingdoms/noble_ranks/`

A rank is ceremonial: it grants no office, permission, Recruits command or Crime privilege. Four ranks ship
(`first_rank`, `second_rank`, `companion`, `courtier`), each with a style for every built-in kingdom.

| Field | Required | Meaning |
| --- | --- | --- |
| `schema` | yes | `1` |
| `tier` | yes | 1 to 16; lower tiers are listed first |
| `title` | yes | translation key of the rank's general name |
| `address` | no | translation key of its form of address |
| `landed` | no | `true`: granted for one settlement of the kingdom |
| `court` | no | `true`: a court function rather than a rank of nobility |
| `styles` | no | kingdom id to translation key: what that kingdom calls the rank (at most 64) |

Example: `data/example/mcakingdoms/noble_ranks/harbor_warden.json`.

## Development templates — `mcakingdoms/development_templates/`

A public work a settlement can undertake when `development.mode` allows it. Eight ship: `lamp_post`, `noticeboard`,
`garden`, `path_segment`, `market_stall`, `wall_segment` (buildable), `workshop_upgrade` and `civic_hall` (funded
milestones, never built).

| Field | Required | Meaning |
| --- | --- | --- |
| `schema` | yes | `1` |
| `role` | yes | `road`, `noticeboard`, `lighting`, `workshop`, `market`, `wall`, `garden` or `civic_hall` |
| `title` | yes | translation key of the work's name |
| `demand` | yes | 1 to 8 entries, each `{"item": "<id>", "count": n}` or `{"tag": "<item tag>", "count": n}` (1 to 256); players deliver real items |
| `kingdoms` | no | kingdom ids that may undertake it (default: all) |
| `minimum_mode` | no | `projects` (default), `managed_growth` or `full_economy` |
| `blocks` | no | at most 64 `{"x", "y", "z", "state"}` entries inside a 5 x 5 x 5 box (x and z from -4 to 4, y from 0 to 4). No block entity (except the court noticeboard), bed, door, gate, redstone component, point of interest, air or fluid. Without blocks the work is a milestone |
| `placement` | no | `air` (default): each block goes where there is air or a replaceable plant; `ground`: each block replaces natural ground with air above |
| `tier_points` | no | 0 to 10 (default 1) towards the settlement's development tier |
| `abstract_output` | no | commodity name to 1–16 abstract units a day, in `FULL_ECONOMY` only; abstract units are never items |

Example: `data/example/mcakingdoms/development_templates/harbor_beacon.json`.

## Authoring commands

| Command | What it does |
| --- | --- |
| `/kingdoms reload` | Reloads all data packs and reports each domain (above). |
| `/kingdoms definitions diagnose` | Shows the last reload attempt domain by domain, with the first problems of a rejected domain. |
| `/kingdoms definitions validate` | Checks the succession laws, court ranks and development templates of the data packs now loaded, without installing them. Other domains are validated by `/kingdoms reload`. |
| `/kingdoms definitions explain <id>` | Shows one active succession law, court rank or development template as the server reads it. |

## Names and translations

Data packs cannot ship language files. Kingdom names (`translation_key`) and political titles are translation keys that clients resolve from a resource pack; the example ships one in [examples/resourcepack](../examples/resourcepack/mcakingdoms-example-lang). Without it, players see the key itself. Server-side English text (logs, console output, English search) uses MCA: Kingdoms' bundled English only, so a pack's own keys appear there as keys. A key that is plain text, such as `"Harbor League"`, displays as written everywhere but cannot be translated. Scenario and world event titles are always shown as written.

Book of Kingdoms pages are resource-pack files; see [Book of Kingdoms pages](#book-of-kingdoms-pages).

## Book of Kingdoms pages

Book pages are read by each client from resource packs: `assets/mcakingdoms/guide/<language>/*.json`. The reader loads `en_us` first, then the player's language; a topic with the same `id` replaces the English topic whole, so the two versions are never mixed. Up to 64 files per language are read, each at most 1,000,000 bytes and 128 entries, with 512 topics in total. Pages are read again when resource packs reload.

A file is `{"schema": 2, "entries": [ … ]}`. A file without `schema` is schema 1, the earlier format, and still loads: its entries give `body` (a list of paragraph strings) instead of `blocks`. Any other schema number skips the file.

| Entry field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | `a-z`, `0-9` and `_`, up to 80 characters, unique in the file |
| `category` | yes | the chapter: `basics`, `settlements`, `civic`, `politics`, `warfare`, `evolution`, `operators` or `reference` |
| `title`, `summary` | yes | up to 100 and 400 characters |
| `keywords` | no | up to 16 words or phrases (40 characters each) that search ranks right after the title |
| `blocks` | yes in schema 2 | 1–64 typed blocks, below. An entry with both `body` and `blocks` is rejected |
| `commands` | no | up to 80 lines starting with `/`. Shown directly in the `operators` and `reference` chapters, and behind *Commands and advanced details* elsewhere |
| `task` | no | the Kingdom Tasks id that **Open task** opens |
| `operator`, `player_task` | no | `operator: true` marks `task` as operator-only; readers without permission are offered `player_task` instead, or told the task is for operators |

Every block has an `id` (`a-z`, `0-9` and `_`, up to 40 characters, unique within its topic; keep the same ids in translations) and a `type`:

| Type | Fields | Shows |
| --- | --- | --- |
| `paragraph` | `text` (up to 4000 characters) | a paragraph |
| `heading` | `text` (200) | a section heading |
| `steps` | `items`: 1–20 strings or `{"text", "link"}` objects | numbered steps; a step's link is a button under it |
| `recipe` | `recipe` (a recipe id), optional `output` (an item id) and `caption` | the recipe as the connected server defines it now: the grid or ingredient list, quantities, station, accepted alternatives and a text version. If the server has removed the recipe, the page says it is unavailable on this server and offers up to three other recipes that make `output` |
| `item` | `item`, `count` (1–64, default 1), `text` | an item with a note |
| `callout` | `tone` (`tip`, `requirement`, `consequence`, `note` or `warning`), optional `title`, `text` | a labelled note |
| `image` | `texture` (`<namespace>:textures/<path>.png` from a resource pack), `width` and `height` (up to 256), optional `texture_width` and `texture_height` (up to 512), `alt`, optional `caption` | a picture; `alt` is what search and narration read |
| `comparison` | `columns`: 2–4 of `{"title", "item"?, "points"}` with 1–8 points, `alt` | columns side by side |
| `diagram` | `nodes`: 2–8 of `{"id", "label", "item"?, "note"?}`, `edges`: up to 12 of `{"from", "to", "label"?}`, `layout` (`row`, the default, or `column`), `alt` | boxes joined by arrows |
| `requirements` | `rows`: 1–10 of `{"label", "state", "detail"?}` with `state` `met`, `unmet`, `unknown` or `example`; optional `title` and `note` | an example checklist. Book checklists are static; `source`, if given, must be `static`. Live checks are in the Guilds screen and Kingdom Tasks |
| `links` | `links`: 1–8 of `{"kind", "target", "label"?, "operator"?}`, `kind` being `guide` (a topic id) or `task` (a task id); `operator: true` marks an operator-only task | buttons to topics or tasks |
| `kingdoms` | optional `caption` | the connected server's kingdoms with their heraldry |

Text may contain `{key:tasks}`, which shows the player's current Kingdom Tasks key; there is no other placeholder. Links name topic and task ids only: a page cannot run a command, open a web address or change the world, and the server checks access whenever a task opens.

The in-game reader is lenient: a broken block becomes a line saying part of the topic could not be shown, with the reason in the client log; a broken entry is skipped; malformed JSON or an unsupported schema skips its file. The unit tests read the bundled pages strictly, so any of these fails the build.

## Troubleshooting exercise

The folders under [examples/datapack/invalid](../examples/datapack/invalid) each break the example pack in one way. Copy one folder's `data` over the example pack's and reload; the report shows the diagnostic below (these exact texts are checked by the unit tests).

| Variant | What is wrong | Diagnostic |
| --- | --- | --- |
| `malformed_json` | the kingdom file lost its closing brace | `Invalid MCA: Kingdoms definition example:mcakingdoms/kingdoms/harbor_league.json: malformed JSON: End of input at line 9 column 1 path $.metadata` |
| `wrong_type` | `heraldry.color` is `"teal"` | `… heraldry.color must be an RGB integer from 0 to 16777215 or a #RRGGBB string (found "teal")` |
| `missing_reference` | `name_pool` names a pool that does not exist | `… name_pool references missing name pool example:harbour; add data/example/mcakingdoms/name_pools/harbour.json or name an existing pool` |
| `unsupported_schema` | `"schema": 2` | `… unsupported schema 2; this version reads schema 1 only` |
| `wrong_political_kind` | a government file says `"kind": "office"` | `example:mcakingdoms/governments/harbor_league_charter.json: kind must be "government" for the governments folder (found "office")` |
| `duplicate_world_structure` | a selector lists `minecraft:village_plains`, which the built-in defaults already list | `World-context selectors rejected — data/example/warfare/world/harbor_sites.json: structure minecraft:village_plains is listed twice`, and only that domain keeps its previous selectors |

The first five fail the whole reload ("Reload failed. No definitions changed…"); the last one is domain-local ("Reload completed with rejected definitions; previous definitions remain active for the affected domains.").
