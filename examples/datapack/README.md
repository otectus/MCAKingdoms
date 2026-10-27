# Example data pack: the Harbor League

A small, working data pack that adds one of each common MCA: Kingdoms definition beside the built-in ones. The unit tests load it with the real loaders on every build (`ExamplePack*Test`), so it stays in step with this version. The fields are explained in the [datapack reference](../../docs/Datapack-Reference.md).

## What it adds

| File | Adds |
| --- | --- |
| `data/example/mcakingdoms/kingdoms/harbor_league.json` | the kingdom `example:harbor_league` (not a fallback; it reuses the Shimaguni heraldry icon with its own colour) |
| `data/example/mcakingdoms/name_pools/harbor_league.json` | its name pool |
| `data/example/mcakingdoms/biome_rules/harbor_coasts.json` | beaches, snowy beaches and stony shores belong to it (priority 120) |
| `data/example/mcakingdoms/governments/harbor_league_charter.json` | a government charter with a leader, an envoy and a local steward |
| `data/example/mcakingdoms/kingdom_gates/harbor_league_only.json` | the gate `example:harbor_league_only`: true only where the player stands in a Harbor League settlement |
| `data/example/warfare/world/harbor_sites.json` | shipwrecks and warm and cold ocean ruins as world sites, drowned as a site-defense encounter |
| `data/example/evolution/scenarios/harbor_relief.json` | an ordinary relief opportunity (aid, mediate or decline; two contributions) |

The matching resource pack, [`../resourcepack/mcakingdoms-example-lang`](../resourcepack/mcakingdoms-example-lang), names the kingdom and the charter in English, Spanish and Simplified Chinese. Data packs cannot carry language files; without the resource pack, players see `kingdom.example.harbor_league` instead of the name.

## Try it

Use a copy of a test world.

1. Copy `mcakingdoms-example` into the world's `datapacks/` folder.
2. Give each client the resource pack: copy `../resourcepack/mcakingdoms-example-lang` into the client's `resourcepacks/` folder and enable it (a server can also offer it as its server resource pack).
3. Run `/kingdoms reload` (or Reload server definitions in Kingdom Tasks) as an operator, or restart the server.

Expected results:

- The reload reports "Reload complete." Compared with a reload without the pack, the counts in "Definitions in use" grow by one kingdom, one name pool, one biome rule, one political definition, one kingdom gate and one evolution scenario, three world structure selectors and one encounter rule.
- **Browse kingdoms** lists the Harbor League. Settlements recognized *from now on* on beaches and stony shores are assigned to it; existing settlements keep their kingdoms. An operator can move one with **Change civic kingdom**.
- **Establish a government** offers the Harbor League Charter as a government type.
- An MCA Quests condition `{"type": "mcakingdoms:kingdom", "gate": "example:harbor_league_only"}` passes only for a player standing in a Harbor League settlement.
- The relief opportunity can appear only in a world an operator opted in with **Configure world evolution**.

Removing the pack later does not rewrite saves. A settlement already assigned to the Harbor League keeps that kingdom id and shows it as an unavailable kingdom until the pack returns or an operator reassigns it; a government founded with the charter becomes dormant.

## Invalid variants

The folders in [`invalid/`](invalid) each break one file of the example. Copy a folder's `data` over the example pack's and reload to see the diagnostic; `expected.txt` names the loader domain and the text the report must contain, and the [troubleshooting table](../../docs/Datapack-Reference.md#troubleshooting-exercise) shows the full messages. Restore the example's original file afterwards.
