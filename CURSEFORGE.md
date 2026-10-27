# MCA: Kingdoms

Give every village a lasting place in the world.

MCA: Kingdoms recognizes settlements, assigns each one a permanent name and kingdom, and keeps that identity stable across restarts and datapack changes. Villages become part of Serenum, Lunari, Madera, Anemosia, Yew, or Shimaguni according to their biome and structure style when first discovered.

## Getting started

1. Put the main MCA: Kingdoms jar in the `mods` folder on both the client and the server. MCA Reborn is optional. The API and sources jars are development artifacts and are not installed.
2. Press the **Kingdom Tasks key — K by default** (rebind it under Controls, in the MCA: Kingdoms category).
3. Walk into a village: the first time, a title names it and its kingdom.
4. In Kingdom Tasks, open **Getting started** and choose **Read this settlement**. **Your known settlements** and **Read a kingdom** are the other first steps; **What is available here?** shows which optional features the server enables.
5. Craft the books when you want them:
   - **Book of Kingdoms** — one book and one paper, shapeless. It explains every system, and its topics open the matching tasks.
   - **Village Ledger** — paper across the top row; paper, book, paper in the middle row; a compass in the bottom middle. It lists the settlements you have discovered, by kingdom.

If Read this settlement finds nothing yet, walk further into the village and try again: settlements are recognized from village structures and from clusters of beds, bells and workstations in loaded chunks.

## Features

- Kingdom Tasks: search what you want to do in your own language, choose people and places by name, and review every change before applying it
- Persistent settlement names, kingdom membership, territory, and discovery records
- Register your own civic residence: the server checks that your bed-set respawn point is in the settlement and that you are there, and a cooldown applies to moves. It is a civic record, not a land claim.
- Optional political notices for foundings, accessions, vacancies and memorials, turned on per player, plus office badges above public office holders. Names and skins are never changed.
- Six kingdoms with distinct name pools, biome rules, colors, and heraldry
- Automatic detection of village structures and village point-of-interest clusters
- The Book of Kingdoms guide and the Village Ledger, with kingdom filters and detail pages
- Configurable settlement-entry titles and heraldry
- Operator tools: Server health, reload reports that name rejected definitions, village reconciliation, and `/kingdoms help`
- Datapack-defined kingdoms, names, biome and structure rules, politics, guilds and world content
- Public API for addons
- Optional Minecraft Comes Alive Reborn integration
- English, Spanish and Simplified Chinese

The core is durable village and civic identity. Kingdom standing, governments, guilds, warfare, world sites and an evolving world are optional layers built on it. Each depends on server settings and, for some, companion mods; the in-game Book of Kingdoms and **What is available here?** explain what applies on your server.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- Required on both client and server

## Minecraft Comes Alive Reborn

MCA Reborn is optional. MCA: Kingdoms works as a standalone settlement system when MCA is absent.

The dependency metadata accepts MCA Reborn `7.6` up to but not including `8.0`. Runtime validation used exactly MCA Reborn `7.6.26+1.20.1` with Architectury API `9.2.14`; other versions in that range have not been validated. With MCA, MCA: Kingdoms recognizes MCA villages and records an MCA villager's civic origin and residence from positive home evidence.

MCA: Kingdoms civic identity is stored separately from MCA identity. The integration does not overwrite a villager's personal name, family relationships, home, genetics, personality, dialogue state, or relationship values. A resident can remain the same MCA character while their settlement is renamed or administered through MCA: Kingdoms.

## Other companions

All optional:

- Townstead `0.7.6` (validated).
- Recruits exactly `1.15.2` for military actions. Other Recruits versions run with the military integration unavailable.
- MCA Quests for guild jobs and commissions. Paid workshop jobs also need a Quests build with the MCA: Kingdoms institutional contract, which the public 1.7.1 source lacks; Server health shows whether yours has it.
- MCA Reputation `0.6.1` or later for live standing synchronization.
- MCA: Crime for paid workshop jobs.
- MCA Conversations for kingdom-gated topics.

The README's compatibility table separates the accepted range, the validated jar and the public source. With MCA: Capitals installed, MCA: Kingdoms turns its own governments off.

## For modpack authors

All geography and naming definitions are datapack resources. Packs can add or replace kingdom definitions, name pools, biome and biome-tag rules, structure-style rules, political definitions, guild rules and world content without rebuilding the mod. A reload reports each definition domain as accepted or rejected, and a rejected domain keeps its previous definitions. Existing settlements retain their saved identities after a reload unless an operator explicitly changes them. Peaceful and warfare configuration presets are included in the documentation.

The release also includes a classes-only API artifact for addons that need settlement lookup, civic identity, context values, extension registrations, or lifecycle events without depending on MCA classes.

MCA: Kingdoms is All Rights Reserved.
