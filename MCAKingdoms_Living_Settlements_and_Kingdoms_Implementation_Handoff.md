# MCA: Kingdoms — Living Settlements and Kingdoms

## Implementation handoff for a coding agent

**Prepared:** 3 November 2026. **Target:** Minecraft 1.20.1, Forge 47.x, Java 17. **Repository:** [otectus/MCAKingdoms](https://github.com/otectus/MCAKingdoms). **Reviewed baseline:** [`5602f2ca5c90ee06138408f55494a6629f048a9c`](https://github.com/otectus/MCAKingdoms/commit/5602f2ca5c90ee06138408f55494a6629f048a9c), whose metadata identifies version 0.3.0; the changelog labels that version unreleased.

This is a specification for implementation, not a claim that the new systems exist or have passed runtime tests. Source inspection informed the findings below; no Minecraft acceptance run was performed for this handoff. Recheck the repository head and installed dependency artifacts before editing, preserve newer work, and record any difference from these pins. Names marked **proposed** are design suggestions, not existing APIs.

Build a medieval society in which settlements have persistent people, physical supplies, productive work, and governments with their own motives. Rulers make decisions, officials organize their execution, workers supply the civilian and military economy, and armies can change the political map. Players can live within this society, influence its leaders, hold office, govern, fight, build, transport supplies, or leave those activities to NPCs. Preserve the current mod's identity, books, task interface, saved records, and companion integrations.

The emphasis is **politics and warfare supported by a complete physical economy**. Do not deliver an abstract statistics simulator with decorative workers, or a collection of randomly triggered war events. Equally, do not discard the repository's already substantial political, civic, succession, construction, and recovery systems.

## 1. User decisions: binding requirements

Later answers supersede earlier answers. In particular, the initial preference for player-initiated major decisions was explicitly revised: **NPC rulers make all their own decisions, with potential influence from players**. Player proximity is an event-start condition, not permission to overrule that autonomy.

| ID | Required behavior |
| --- | --- |
| R01 | NPC rulers pursue goals, govern, negotiate, form alliances, and decide whether to wage war. No player approval is required for an NPC ruler's political decision. |
| R02 | The ruler holds executive authority; appointed officials organize and carry out orders. Do not impose a new mandatory council-voting system. Preserve existing constitutions and election records during upgrades. |
| R03 | Players influence rulers through personal trust, friendship, marriage/family connections, quests, military/civic service, office/rank, and political leverage such as secrets, threats, and favors. Supply donations are useful economically but must not automatically become an additional purchased political-influence system. |
| R04 | Secrets, favors, and related political actions integrate with MCA: Conversations and MCA: Quests. Quests is required; Conversations compatibility is required when that optional mod is installed. |
| R05 | NPCs recruit, train, equip, supply, command, and move armies, fight battles, administer occupations, and organize recovery. Players may assist in each activity without having to micromanage it. |
| R06 | Support occupation and annexation, independence movements and rebellions, rival-ruler/claimant civil wars, and limited conflicts over resources, borders, and trade rights. Peace and alliances must function. Vassalage/tribute and restoration campaigns were not selected as additional required feature families. |
| R07 | A complete civilian and military economy uses actual workers, items, storage, production, consumption, and deliveries. A completed building must not create goods merely by existing. |
| R08 | Settlement stockpiles centrally supply residents, workplaces, and armies. Do not substitute a required private-household/business ownership simulation. Private player property remains separately owned. |
| R09 | Settlement trade uses a supported installed currency provider, particularly Custom Trade Currency; otherwise it uses emeralds. Barter is not the requested default. |
| R10 | Routine production and trade continue while the server runs and players are elsewhere. Background operation preserves actual item and person state; it is not a second abstract economy. No real-time progression while the server process is stopped is requested. |
| R11 | Each major battle or political upheaval can start only when an online player affiliated with an involved kingdom is nearby. An unrelated spectator is insufficient. |
| R12 | Once that specific battle/event starts, it finishes even if the qualifying player leaves or disconnects. Subsequent major events wait for their own qualifying presence. Do not authorize an entire multi-battle war with one presence check. |
| R13 | NPCs plan expansions, construct and upgrade buildings, and establish new settlements when conditions justify them. Material, workforce, housing, location, and supply requirements must be real. |
| R14 | Warfare can kill soldiers and participating players, seize supplies, damage fortifications and military/civilian buildings, displace/capture/kill civilians, and target player-owned homes and possessions. These are supported default war capabilities within valid conflicts, not features silently disabled because the targets belong to players. |
| R15 | Required complete-experience stack: MCA Reborn, MCA: Quests, Villager Recruits, Villager Workers. Townstead is optional and its contributions must have fully simulated equivalents when absent. |
| R16 | Living Villages, MCA: Mob Compat, MCA: Crime, MCA: Reputation, MCA: Capitals, and MCA: Conversations were not made mandatory. Preserve useful existing interoperability without relying on them to make the core simulation work. |
| R17 | Population grows through births and migration, supplemented by limited generated immigrants attracted by suitable conditions. Existing people retain identity and family history. |
| R18 | Shortages cause hunger, emigration, dissatisfaction, production problems, unrest, and potentially rebellion. **No deaths from deprivation.** Combat and other independently existing causes remain distinct. |
| R19 | Design and test primarily for approximately 5–15 active settlements and a few players. This is a performance target, not permission to erase or ignore additional saved settlements. |
| R20 | Support two startup modes: independent settlements, and established kingdoms supplied by datapacks. Neither should require the other. |
| R21 | Automatically include recognized settlements in existing saves while preserving rulers, ownership, identities, and history. Do not require an opt-in ceremony for every village. |
| R22 | In-world ruler/official interactions and management screens provide connected ways to perform the same authorized actions. |

Where this document supplies numerical defaults or additional design details, they are **recommended implementation defaults**, not further user answers. Keep them configurable and document the final values. Do not weaken the above requirements to accommodate a convenient provider API; implement a suitable bridge or coordinated companion change and test it.

## 2. What the current code actually provides

The repository's README and current configuration references take precedence over historical R1–R4 design documents when those disagree. Preserve historical documents as context, not proof that a feature is implemented.

| Area | Verified baseline | Consequence for this expansion |
| --- | --- | --- |
| Identity | Stable settlement records, structure/POI recognition, saved kingdom assignment, discovery, merge redirects, civic residence. | Extend these IDs and redirects. Do not replace them with position/name matching or regenerate records on load. |
| Politics | Existing offices, permissions, charters, agreements, petitions, elections, regency, succession, dynasty/court, and optional Capitals crown ownership. | Reuse political invariants and records; introduce lawful NPC actors and autonomous planning rather than a parallel government database. |
| Political mutations | Key government/declaration paths expect a connected `ServerPlayer`; military paths also inspect the player's Recruits authority. | An NPC UUID passed through player-only methods is not enough. Refactor actor authorization explicitly. |
| Evolution | Bounded authored opportunities and scenarios, with activity credited from player presence; current scenario progression can pause with regional inactivity. | This does not implement either an autonomous society or the requested affiliated-player, start-only event gate. |
| Development | `DevelopmentService` lets officials propose works, players contribute plain items, and opted-in settlements build surveyed works in bounded loaded-chunk passes. | Preserve delivery receipts, construction safeguards, and recovery behavior; add realm-funded work and actual worker execution. |
| Templates | `DevelopmentTemplate` is limited to 64 placements and a small offset range. Its validator excludes most block entities, beds, doors/gates, POIs, redstone, and fluids. | Existing templates are small civic works, not full functional houses, farms, workshops, or castles. A separate staged building format is necessary. |
| Economy | `DevelopmentService.economy()` produces abstract goods from completed works and exchanges them in capped pairs under agreements. Its class comment explicitly excludes real items, money, tax, and offline catch-up. | Implement a physical economy; do not reinterpret `abstractGoods` as withdrawable items or minted currency. |
| Persistence | `DevelopmentSavedData`, durable store helpers, atomic file replacement, request receipts, project journals, capacity reporting. | Reuse those patterns, but prove cross-inventory/region/provider crash recovery separately. Atomic replacement of one file is not a transaction over a player's inventory, a chunk, and several mods. |
| Protection | `PlotSurvey` avoids protected homes and existing occupied blocks and checks Forge placement events. `sameBlock` compares registry block IDs, not every state property. | Preserve protection checks. Strengthen operation-specific fingerprints before extending the journal to doors, machinery, storage, demolition, or repair. |

Primary current-code references: [development service][K-development], [template validator][K-template], [development storage][K-dev-store], [plot survey][K-survey], [atomic writer][K-atomic], [configuration][K-config], [companion evidence manifest][K-companions].

### 2.1 Dependency evidence and release discipline

The repository currently declares the major companions optional, and does not declare Workers. R15 therefore requires an intentional dependency-policy change. Make the four required mods dependencies of the delivered complete simulation distribution, with explicit tested ranges and transitive dependencies. Townstead and the other integrations remain optional. If retaining a legacy identity-only distribution or profile, label it clearly; it must not be presented as the complete experience.

Keep separate columns for **metadata accepted**, **source inspected**, **jar ABI verified**, and **packaged combination tested**. A matching version string is insufficient. In particular, the existing `companions.json` documents locally built Quests artifacts whose public source has a different contract. Treat its old successful runs as historical evidence, not certification of this expansion.

Do not choose NeoForge dependencies simply because a multi-loader repository's root properties show NeoForge. Inspect the Forge 1.20.1 sub-build and its actual jar. Do not silently migrate this project to Minecraft 1.21.1.

## 3. Architecture: one society, one owner for each fact

Introduce a server-scoped simulation coordinator, using existing services wherever their authority and lifecycle fit. Keep the logical server thread authoritative for world, entity, inventory, and provider mutations. Pure planning can use immutable snapshots; any result must be revalidated against current revisions before commit. Never retain live world objects for a background thread to mutate.

**Proposed components** below are responsibilities, not mandatory class names:

| Component | Owns | Must not own |
| --- | --- | --- |
| `SocietyCoordinator` | Bounded scheduling, simulation time, capabilities, work queues, handovers. | A second copy of every companion's live entity state. |
| Existing settlement service + `PolityRegistry` extension | Settlement identity, dynamic realm membership, independent polities, redirects and historical control. | Invented geopolitical ownership derived only from biome. |
| `RulerPlanner` / `OfficialDispatcher` | Motives, lawful decisions, delegated work, explainable priorities. | Direct writes bypassing government and inventory invariants. |
| `PopulationService` / native-character bridge | Residency, household membership references, work availability, migration and custody transitions. | Replacing MCA's canonical family tree or arbitrary NPC respawning. |
| `StockpileService` / `EconomicTransactionService` | Registered public inventories, exact item custody, reservations, treasury, receipts. | Claims that arbitrary player chests belong to a settlement. |
| `ProductionPlanner` / Workers adapter | Recipe-backed jobs, native work assignment, quotas, tools, inputs and outputs. | A second harvest or crafting output for a native task already completed. |
| `TradeService` | Orders, offers, route reservations, caravan cargo, payment settlement. | Instant teleportation between publicly visible remote chests. |
| `DevelopmentPlanner` / construction adapter | Surveyed plots, material-backed plans, builders, completion evidence. | Unchecked schematic pasting or free materials through paid-build shortcuts. |
| `ConflictService` / existing warfare services | War aims, battle instances, occupation, peace, territorial changes and receipts. | A second Recruits faction-owner authority. |
| `MajorEventGate` | Eligibility to start each bounded major event and durable admission token. | Approval for all later events in a campaign. |
| `InfluenceService` | Political requests, obligations, evidence-backed secrets, decision modifiers. | Converting gossip into proven facts or silently exposing private knowledge. |
| Townstead adapter / internal provider | Equivalent needs, routines, skill, social and village-life simulation under a single selected owner per capability. | Simultaneous drain/reward loops in both mods. |

### 3.1 Identity and political geography

Separate the following concepts, even if the existing saved schema uses the word “kingdom” for more than one:

- **Cultural definition:** names, architecture, heraldry defaults, customs, biome affinities, and datapack styling.
- **Political realm:** a persistent governing entity with its own identity, ruler, treasury, diplomacy, and succession.
- **Settlement membership:** the settlement's recognized political relationship, distinct from temporary military occupation.
- **Military control:** who currently controls a location, with provenance and a durable conquest outcome.
- **Personal residence, native ownership, employment, and military allegiance:** distinct relationships; none should be inferred from merely standing inside a border.

Several independent settlements can share cultural styling without sharing a ruler, treasury, or war. Two newly founded kingdoms must be possible within one cultural definition. A capital changing hands must not change a realm's identity. Existing realm/settlement/office IDs and aliases must survive migration.

In independent-start mode, a new politically uninitialized settlement receives its own polity and civic leadership where vacant, using suitable existing adults when possible. This startup topology does not split an existing kingdom merely because its throne is vacant. For existing saves, migrate recognized membership and government history first, then fill only genuinely missing simulation metadata. In datapack-established mode, a validated preset explicitly supplies realm definitions and deterministic membership/initialization rules. New realm presets must not retroactively overwrite existing affiliations, named rulers, or player-created governments. Persist chosen startup mode per world; changing a config or reloading a datapack does not silently rewrite history.

### 3.2 Typed actors and bounded authority

Refactor mutation services around a **proposed** actor context such as player, NPC officeholder, and narrowly scoped system executor. Authenticate player actors from the server connection. Resolve NPC officeholders from persisted government records and current life/custody status. A system executor may only finish a previously authorized operation or run a defined migration; it has no blanket operator power.

Both player commands and NPC planners should call the same domain operation with actor, polity, authority revision, request ID, reason, and expected target revision. Recheck authority at commit and at subsequent irreversible steps. Preserve player permissions, target ownership, constitutional constraints, Capitals ownership, and existing treaties.

Do not execute console commands as NPCs, fabricate a logged-in player, or use `FakePlayer` to grant political permission. A scoped Forge interaction proxy may still be necessary for a native block-placement/claim hook; it must carry the already-authorized operation and cannot become the source of authority. Where native Workers/Recruits require player ownership, implement explicit **realm custodianship** adapters rather than assigning every NPC to an arbitrary online player.

## 4. Autonomous rulers and delegated government

### 4.1 Decisions with reasons

Give each ruler persistent, bounded traits: ambition, caution, generosity, loyalty to promises, martial preference, trustfulness, and priorities rooted in family and local culture. Derive changing concerns from actual evidence: food runway, shelter, employment, treasury, border access, recent casualties, rival claims, service, grievances, and obligations. Persist random seeds and decisions so save/reload does not reroll rulers into different people.

Use a transparent utility-based planner with hard legality and feasibility filters, rather than a global language-model dependency or arbitrary event probabilities. For example:

`decision utility = need relief + strategic value + personal/relationship preference − resource cost − military risk − diplomatic cost`.

This is a proposed scoring structure, not a fixed formula. Publish the final factors and units. A plan should say “secure grain imports because reserves cover two days,” not merely expose a numerical aggression score. Prevent oscillation with minimum plan duration, hysteresis, cooldowns, memory of failed attempts, and changing-world invalidation. Compare peace, trade, reduced mobilization, and construction before treating every shortage as a reason for war.

Officials can prioritize tasks within a mandate and report failure, but do not become a compulsory parliament. Supply roles such as steward, quartermaster, marshal, envoy, and local governor, with each kingdom's appropriate titles. The ruler appoints/dismisses officials, and vacancies produce a transparent backlog or limited direct ruler work. Respect existing offices instead of duplicating them under new names. An official's death, capture, removal, or absence interrupts only authority-sensitive work; already committed payments and completed deliveries remain valid.

### 4.2 Diplomacy and internal stability

Implement proposals, counters, acceptance, rejection, expiry, obligations, breach evidence, and fulfillment receipts for peace, alliance, access, border/resource agreements, and trade rights. Neither an alliance nor a trade agreement automatically grants access to private inventories or ownership of another realm's troops. Existing truces and agreements remain authoritative.

Military disputes need explicit claims, goals, target settlements/routes, escalation reasons, and settlement terms. Rebellions arise from resident/official/claimant support, grievances and organization; civil wars concern competing legitimacy and command. Avoid a random timer that spawns an unlimited rebel army. Rebels need people, accessible arms, supplies, and somewhere to assemble. Some failures can produce nonviolent concessions or secession negotiations.

Track needs-driven unrest without deprivation deaths. Resident anger can grow in the background, but the coup, revolt, violent uprising, forced secession, or regime overthrow is a major event subject to R11–R12. Pending events retain reasons and can de-escalate if grievances are addressed; they must not secretly change territorial or office records before admission.

Ordinary diplomatic discussions, routine appointments, and fulfillment of existing agreements can continue in the background. Classify major consequences centrally: a peaceful forced merger, crown seizure, or cession that changes sovereignty must not bypass the gate merely because it uses a diplomacy endpoint. Preserve already existing verified succession/death workflows; never keep a confirmed-dead ruler in office to postpone bookkeeping. If a succession entails a contested upheaval, open a pending major event while lawful interim administration continues.

## 5. Player-presence rule: a durable event-start gate

Create a shared classification and admission service used by all native and optional-provider entry points. Campaign declaration, movement, recruitment, and preparations may occur without nearby players. Beginning a destructive battle or committing a major political upheaval requires a distinct admission check.

**Recommended eligibility definition:** a living, connected, nonspectating player in the same dimension, within a configured radius of the event footprint, with server-verified residence, office, or explicit enlisted/service affiliation to an involved polity. A side's actively participating allied polity can qualify only if recorded as an actual event participant. Mere exploration, discovery, friendship, client claims, or presence in an unrelated allied realm do not qualify. Make the final affiliation rules visible in diagnostics and test alternate dimensions, teleportation, respawn, and residence-change cooldowns.

Use a proposed default radius of 192 blocks from a battle footprint/settlement event location, subject to playtesting; avoid global “someone is in this region” flags. No authority is granted by being the qualifying witness.

| State | Permitted behavior | Transition |
| --- | --- | --- |
| Planned/preparing | Diplomacy, legitimate recruitment, supply procurement, travel to staging positions; normal civilian economy. | Reach feasible staged plan. |
| Ready/waiting for presence | Explain why the event waits; revalidate aims, actors, supplies and target. No battle damage, hostile civilian capture, sovereignty change, or starvation blockade as a substitute for the withheld battle. | Valid affiliated player + current feasibility. |
| Admitted | Persist event ID, sides, bounded objective, roster/custody snapshot, eligibility evidence, admission time and resource commitments. | Commit once, then begin. |
| Active | Native visible combat or reconciled offscreen continuation of this event; no repeated proximity requirement. | Victory, retreat, surrender, failed objective or bounded stalemate. |
| Resolving | Persist casualties, custody, cargo, damage and political consequences idempotently. | One committed outcome. |
| Complete | Recovery, receipts, notices; new battle gets a new event ID. | Campaign may prepare its next event, which waits separately. |

Define an event as one bounded battle/siege objective or one political resolution, not an entire war, endless siege, or open-ended rebellion. Capture adjacent settlements, open a new front, initiate the next field battle, or stage another coup only under a new admission. Reinforcement rules must belong to the admitted event, with limits; do not use infinite reinforcements to make an event never end. Provide timeout/stalemate/retreat behavior so disconnecting does not leave an immortal active event consuming the world indefinitely.

Freeze the permitted political consequences in the admission token as well. Applying the occupation or other outcome already authorized for this battle is part of completing that event and must not require a second presence check after the player leaves. A later annexation decision, expanded war objective, or newly targeted settlement is a separate event. Reconciliation of an already committed outcome is never a new political decision.

If the last qualifying player leaves, complete the admitted event without forcing the player back or pausing all AI. If its chunks unload, hand the event to the same persistent roster/cargo model used for offscreen continuation. Resolve actual participants; do not spawn replacement casualties and leave originals alive. Once complete, do not start the next event merely because a prior campaign token exists. With no player affiliated to either side, new major events remain pending by design; show this plainly. No hidden neutral-spectator fallback.

Civilian emergency defense against unrelated vanilla mobs remains ordinary entity behavior. Scheduled raids, punitive expeditions, simulated bandit armies or blockades must not become a bypass around the major-event rule.

## 6. A conserved physical economy

### 6.1 Stockpiles and economic ownership

Central stockpiles can consist of several registered, physically located stores: food reserve, materials yard, workshop inputs, armory, treasury, and relief reserve. Keep their capacities, access, item filters, locations, carrier access and physical destruction meaningful. Use exact `ItemStack` semantics, including relevant NBT, damage, enchantments, container remainders, and modded stack limits. Commodity categories help planning; they do not replace real stack custody.

Enrollment does not nationalize every chest inside settlement bounds. Recognize provider work/storage areas only through verified ownership or a documented public-storage designation. Preserve private homes, player inventories, existing worker owners, and native work-area permissions. Provide clear actions to designate public storage, donate items, loan equipment, inspect reservations, and withdraw with an authorized role. Existing officials do not automatically own each resident's possessions.

Represent separately: physically present stock, reserved stock, goods in transit, escrow, committed consumption, and amounts merely forecast. Show available stock as actual stock minus reservations; do not add in-transit cargo to both endpoints. A treasury's displayed balance is derived from actual currency stacks and custody records, not an independent spendable number.

Use stable inventory identities: dimension, owner/provider, block/entity/store identifier and lifecycle generation. A replacement chest at the same position is not automatically the old chest. Double chests and overlapping storage areas must not count twice. Audit hopper/pipe changes, hand extraction, broken containers, inserted invalid items, locked chests, and claim permission changes. Never mutate an unloaded arbitrary block entity using stale cached contents.

### 6.2 Transaction protocol and crash consistency

Design a common operation protocol before implementing production or caravans. A suggested lifecycle is `INTENT → RESERVED → IN_CUSTODY → APPLYING → COMMITTED`, with explicit `BLOCKED`, `CANCELLED`, `RECOVERY_REQUIRED` and compensating operations. Each operation includes a unique ID, source/target inventory identities, exact stacks, expected revisions, owner, purpose, provider capability version, custody epoch, and linked contract/recipe/job.

The implementation must define which side owns each stack at **every** persisted boundary. A snapshot by itself is not proof that an extraction was saved. Account for crashes after extracting from a native container but before saving the operation, after saving escrow but before the provider's chunk save, and after inserting an output but before acknowledging completion. If a provider cannot support safe recovery, add an audited transfer fence/save verification or restrict that inventory to a mediated transactional store; do not claim exactly-once guarantees that rely on wishful save ordering.

Use a recoverable operation journal and provider-specific acknowledgements. Make retries idempotent and verify durable provider state before acknowledging a transfer. Reuse the repository's recruitment-transfer intent/readback pattern, but do not assume its entity protocol also proves generic inventory atomicity. Never simply refund after an unknown result: that can duplicate goods already delivered. Hold uncertainty in visible recovery state.

For a transfer, the exact multiset of stacks must be conserved across source, carrier/escrow, destination, recorded drops and legitimate sinks. For production, conservation is recipe-based: consumed inputs, tools/fuel changes, crafting remainders, outputs, waste and recorded destruction must match the declared operation. Include explicit world sources such as harvested blocks and bounded immigration starting possessions; do not label the economy mathematically closed when legitimate mint recipes, drops, vanilla traders or player imports are enabled.

Inventory-full outcomes wait in real carrier storage or bounded durable escrow. They do not disappear, get paid as completed, or become repeated free item drops. Cancelled construction refunds only unconsumed material; repairing damaged buildings requires supplies again. A rollback cannot return both the original block's material and a compensating item while leaving a usable block in the world.

### 6.3 Production chains and labor

Deliver a functioning default content set, not just extension interfaces:

| Chain | Physical prerequisites and activities | Consumers and visible consequences |
| --- | --- | --- |
| Food | Seed/stock, cultivable land or legitimate gathering/fishing/livestock site, tools, workers, harvested items, cooking inputs/fuel and containers. | Meals, reserve stocks, provisioning and relief shipments; hunger and poor output when supply fails. |
| Timber | Surveyed harvest plots, available trees, axes/tool wear, hauling, replanting and processing recipes. | Buildings, fuel, tools and fortifications; visible managed forestry rather than endless output from a lumbermill statistic. |
| Stone and metals | Surveyed finite extraction sites, actual block/resource evidence, tools, ore processing, furnace fuel, recipes and transport. | Tools, weapons, armor and construction; depletion and replacement-site planning. |
| Tools and equipment | Audited crafting/smelting/repair recipes, ingredient substitutions supported by those recipes, workstations and skilled labor. | Replacement tools, trained/equipped troops; no gear spawned merely by recruitment. |
| Housing and services | Material-backed buildings, beds, working access, public water/food/rest/service capacity. | Population capacity, attraction, daily routines and resilience. |
| Military supply | Real food, suitable weapons/armor, ammunition where applicable, repair materials and carriers. | Muster readiness, march endurance, resupply, retreat and occupation support. |

The baseline should be complete using supported vanilla recipes and the required stack. Add datapack/provider extensions for modded recipes without promising generic automation of every machine or hands-on minigame. Unsupported recipe types must say why they cannot be automated and remain available to players; never synthesize output by reading an arbitrary recipe result without its actual costs and process. Handle tag reloads, NBT ingredients, catalysts, damaged tools, byproducts, recipe remainders, furnace experience and fuel semantics explicitly.

Schedule work through actual roles, shifts, rest, skill, path availability and workplace capacity. A citizen cannot harvest, build, escort and fight simultaneously. Child, wounded, captured, migrating, resting and already assigned people are not free labor. Training takes time and supported practice/equipment, and consumes capacity; do not award profession XP every planner tick. Population needs feed concrete demand forecasts, then officials select jobs within real capacity.

Prevent deadlocks: reserve emergency food; prioritize replacement tools needed to produce food; keep a minimum civilian workforce during mobilization; allow importing bottleneck resources; avoid recruiting the last farmer while blaming famine on the population. Record the causal reason when a job stalls: unavailable worker, missing input, reserved stock, broken tool, full output, blocked path, missing recipe, inaccessible worksite or provider unavailable.

### 6.4 Trade, currency and transport

Trade planners compare genuine surpluses and needs, reserve local minimums, estimate route/risk/transport cost, and negotiate bounded quotes. Use checked integer monetary units, quantity limits, expiry and maximum price steps. Include cargo specification, sender/receiver, currency provider/profile revision, payment timing, route, carrier capacity, security terms, and who bears losses. Initial balancing can use recipe/labor-based reference prices adjusted by shortage and transport cost; prevent unlimited markup loops and self-trade profit.

Preferred payment policy for the first complete implementation: reserve a buyer's actual currency at acceptance, reserve seller goods, transfer custody to the caravan on departure, verify destination receipt, then settle the escrowed payment once. Permit explicitly authored alternatives later. Lost/stolen cargo produces the contracted consequence, not an automatic replacement shipment or a second payment. Repairing a road or escorting a caravan should improve a real route or delivery, not only increment a generic stat.

Use native couriers where the certified Workers adapter can support the route and transaction contract. Manifest every shipment. Cargo visibly exists in the carrier/escrow model; arrivals unload only what remains. Caravans can wait, reroute, turn back, request escorts, or shelter. Scripted attacks against them are major combat events where applicable, not an offscreen casualty loophole. Ordinary travel obstacles and existing live-world hostile mobs remain distinguishable from orchestrated war attacks.

**Custom Trade Currency findings:** source [`28841f418a12d6dbb4da2c763eb578f1464c33ad`](https://github.com/otectus/CustomTradeCurrency/commit/28841f418a12d6dbb4da2c763eb578f1464c33ad) identifies 0.8.0 for Forge 1.20.1. Its [public API boundary][CTC-api] covers merchant conversion/profile hooks and exemptions, not a treasury/banking service. Public Java access to `CurrencyManager` does not make it a stable supported API.

Prefer a coordinated additive public **currency snapshot API** in CTC: enabled state, selected global profile, accepted stack matcher, integer denominations, stable semantic revision, and change notification. If using existing internals temporarily, isolate and certify an exact ABI; do not invent a universal currency API. Other physical-item currency providers need named adapters with equivalent tests. Default selection: explicit operator choice, otherwise supported enabled CTC, otherwise configured supported provider, otherwise emeralds. Resolve multiple providers deterministically and show the selected ladder.

Support exact change for noncanonical denominations, not only decimal coin ladders. Reject an unpayable amount or retain a documented escrow; do not round both legs in a way that creates profit. Respect provider-defined stack matching and item containers. Do not recursively rewrite coin-sack NBT or assume a renamed ingot is money. Do not conflate a merchant-specific conversion override with world treasury currency.

If no provider is installed, use emeralds. If a provider disappears or changes after a contract was accepted, keep that contract's frozen monetary meaning and physical balances; suspend/review affected settlement rather than converting old coins to an equal number of emeralds. Reinstall or explicitly cancel/revalue through a documented transition. Initial liquidity, if needed, must be a finite, persisted-once world-start provision or legitimate item source. No recurring free money for taxes, wages, imports or quest rewards.

## 7. Loaded and background simulation are two execution modes

Background operation is required for routine economy, but the server must not force-load every citizen, worksite and caravan. The same persistent people and inventory operations use exclusive execution ownership in either mode. Separate the **existence of a person/item** from whether its rendering/native AI is currently active.

Use a monotonic simulation tick clock derived from actual server ticks, not wall-clock elapsed time or repeated `/time set` changes. Keep calendar/daylight as a separate input. Store the last processed tick and custody epochs. Bound catch-up after scheduler delay; do not execute days of mutations in a single tick or silently multiply production by elapsed downtime.

At unload or provider handover:

1. Stop admitting new work for the affected unit/store and freeze its current lease.
2. Resolve or journal any in-progress native transfer; collect actual inventory, health, equipment, progress, position, orders and provider state from the verified owner.
3. Persist the handover epoch and acknowledged custody before enabling the alternative executor.
4. Advance only work whose prerequisites remain authoritatively owned and valid. A stale count of a remote chest is not authority to spend it.
5. Before native ticking resumes on load, reconcile committed operations and custody once, then release the background lease. A stale entity or container may not operate for one tick against pre-handover items.

For owned managed stores, use a provider-approved inventory serialization/lease or a transactional stockpile block whose canonical item storage is available to both executors. Arbitrary third-party chests without a safe handover can participate while loaded or through goods already moved into escrow. They must never be silently duplicated into an always-writable cached inventory.

Offscreen manufacturing and provisioning can continue from reserved physical supplies with validated capacity and worker availability. Offscreen harvesting requires an explicit resource-site model with exclusive, finite resource reservations and a reconciliation protocol for the corresponding world changes. Renewable farms require recorded plots, seed/replant obligations, yield limits, and elapsed growing conditions. Unsurveyed terrain is not a source. Where an adapter cannot safely reconcile block harvesting, let that particular task wait and maintain other routine production/trade; do not pretend the unimplemented task completed. The delivered default chains must include functioning background paths, not make every chain depend on a permanently loaded chunk.

All resource-site commits must prevent a block being harvested once offscreen and again after load. Handle external forced chunk loading, player edits after materialization, incompatible block changes, and lost leases. Never overwrite a player's intervening build to make an old projection true. Admission must fail safely when exclusive authority cannot be established.

Already-started battles use an event-scoped offscreen resolver with the persisted actual roster, health, equipment, ammunition, supplies, terrain/fortification state and objectives. Persist random seed and each resolution step. Reconcile deaths through appropriate native death/loot/family hooks, damage/capture outcomes, surviving inventories and entity identities. Missing native support is an implementation blocker requiring an adapter, not permission to credit a victory by a dice roll and respawn a fresh army. If players return mid-event, resume from the committed resolution boundary.

## 8. Population and Townstead parity

### 8.1 People remain people

Keep canonical MCA person UUID/family evidence separate from any native worker/recruit body binding. Preserve names, appearance/genetics, family, relationships, equipment, ownership, profession, XP, orders, memories, contracts and whereabouts through work assignment, promotion, capture, migration, succession and unload/reload. Never convert a worker into a different entity merely to assign a government job. If a provider genuinely changes body identity, journal a verified one-to-one body transition.

MCA owns family links, marriage and native pregnancy/birth evidence. Reuse `McaFamilyEvidence`; unload, absence, missing snapshot, and entity discard for travel are not death. Do not operate a second birth counter that invents children while MCA also completes a pregnancy. A background birth path, if needed, must cooperate with the selected MCA build and commit one actual child and family relationship.

Migration moves existing persons/households with their belongings and obligations. Destination decisions consider beds, food/water, safety, work, relatives and travel. Limited generated immigrants are allowed, but need explicit provenance, valid spawn/arrival, finite seed possessions, cooldowns and housing/supply checks. Do not refill a town instantly after a battle. Avoid unbounded nation growth by accounting for settlement capacity, land, carrying cost and available public resources.

Hunger can reduce work, increase dissatisfaction, encourage emigration, and create support for rebellion. People must not die or silently despawn because food, water, pay, rest or shelter is lacking. Apply this to background resolution and native provider behavior. Suppress only managed deprivation-caused fatality/removal; do not confer combat invulnerability or erase unrelated wounds. Unpaid soldiers/workers can desert, resign, relocate or join a supported movement through persistent outcomes.

### 8.2 Townstead source facts and ownership

Townstead source [`c0c1634c498c0cba192bd1391d1141880c8223fb`](https://github.com/AetherianArtificer/Townstead/commit/c0c1634c498c0cba192bd1391d1141880c8223fb) identifies 0.8.0 and supports Forge 1.20.1 through its Stonecutter [Forge build][T-build]. That build pins MCA `7.7.1-beta.5+1.20.1`; this differs from Kingdoms' older validated MCA 7.6.26/Townstead 0.7.6 pair. Certify complete tuples and MCA migration separately.

API v1 revision 2 offers needs/schedule/profession operations, social/Chronicle/event surfaces, and village snapshots. However, [WorkApi][T-work] and PoliticsApi are read-only. Do not call imaginary enqueue, stock withdrawal, annexation or appointment methods. Townstead's [unloaded villager record][T-record] explicitly contains last-seen data that does not advance while unloaded. Its event notifications occur after changes; they are not a cancellable transaction veto.

The existing Kingdoms fallback primarily reads MCA building evidence. It does not meet the user's full simulation requirement. Implement an internal resident/village-life provider, not a set of empty getters or disabled features.

| Contribution to the expansion | Townstead present | Townstead absent: required internal equivalent |
| --- | --- | --- |
| Food, water and need levels | Normalize semantic ranges/directions and coordinate one consumption owner. | Actual meal/water acquisition, consumption, shortages, readable need levels and effects. |
| Energy, shelter and rest | Use audited routines/need operations and native home evidence. | Bed/home capacity, work/rest cycles, recovery and nonlethal deprivation. |
| Daily/weekly schedules | Adopt one provider schedule owner; use supported template/custom-shift operations. | Persistent work, rest, meals, meeting and free-time routines tied to workplaces and relationships. |
| Worksites and orders | Read evidence via API; use a separately audited execution/transaction bridge where mutation is needed. | Same productive chains through Workers and Kingdoms-owned orchestration; no abstract-output substitute. |
| Profession/career/skills | Supported award operations, prerequisites, caps and skill evidence. | Training/practice progression with comparable economic and quest consequences and persistent records. |
| Buildings and public services | Verified building/service evidence and reported coverage. | Functional building recognition, capacity, service access and repair state. |
| Spirit/community context | Interpret provider components accurately; spirit is not interchangeable with legitimacy. | Equivalent building/service/community inputs, separately modeled morale, unrest and ruler legitimacy. |
| Social context and routines | Verified personality/social reads and supported reactions where available. | Persistent social context, household ties, meetings, recent needs/service history, and influence inputs. |
| Calendar/customs/local events | Read effective settings and relevant calendar/community context. | Data-driven local schedules, observances and event signals needed by the simulation. |
| Historical evidence | Publish/consume committed facts with deduplication; keep beliefs distinct. | Durable native event history and viewer-filtered narrative/quest signals. |
| Life stages/families | MCA remains canonical; use provider observations without inventing transitions. | The same MCA-backed age/family/birth lifecycle and migration behavior. |
| Governance coordination | One political writer; audit any provider handoff separately. | Kingdoms' ruler/official/polity systems fully operate. |

Maintain a detailed parity checklist against the final audited Townstead contribution set. Full simulation parity means equivalent gameplay outcomes and continuous state, not copied assets or a byte-for-byte clone of Townstead's code/UI. Do not silently omit a contribution because the optional provider is absent.

Select one executor per person per concern. Townstead installation must not double hunger drain, meals, XP, working hours, births, output or morale. Adding/removing/upgrading it requires a quiesce/snapshot/reconcile/epoch transition; keep unsupported provider-specific state for reinstall. Townstead installed with an explicitly disabled mechanic differs from Townstead absent: respect effective world settings and report the disabled capability, rather than secretly enabling a duplicate internal ticker.

Townstead can enable lethal need behavior via `needs.canKill`, and Recruits can inflict starvation damage through `RecruitsStarving`. Test those adverse configurations. Add a scoped provider policy/adapter to uphold R18 for enrolled people; do not rely only on upstream defaults. Also intercept persistent-person disappearance caused by native unpaid-unit `DESPAWN` behavior. A source build that cannot support these contracts cannot be certified as complete.

Townstead has its own political system. Its internal `CivicProviders.Provider` is a candidate cooperation point, not a stable API v1 mutation surface. Establish one crown/government owner for each mapped realm; never let two providers independently seat rulers. Preserve existing external Capitals authority rather than usurping it.

## 9. Native workers, construction and settlement expansion

### 9.1 Certify the native execution layer

Research located the official [Workers 2.0.3 Forge 1.20.1 release][W-release]. Its source candidate is `d15dd8201b16c4ca24e1e49890f9f921306128b0`; jar/source equivalence remains to be verified. Current inspected Workers main `075a8a16e73615fc926dd54d245046d350a8c385` declares 2.1.0 and contains newer Craftsman/Workshop/event functionality. Do not assume those additions exist in a player's 2.0.3 jar.

Similarly, inspected Recruits main `e06f8c9fc666e256e133f27bbf9a5716a9676334` still declares 1.15.2 despite newer Governor work. The release-version source candidate is `1cc10948f6e04fd146f9eb7fb84421033629dc1a`. Kingdoms' existing exact-version gate therefore needs an artifact/capability check, not just the string `1.15.2`. Reuse the historically tested jar only after verifying the new adapter contract. Update `tools/test/companions.json` with the actual artifacts used.

Released Workers already provides farmer, miner, lumberjack, builder, merchant, fisherman, animal-farmer, courier and cook behaviors, real inventories and work/storage areas. Build on them. If using the source-ahead workshop API, either release and certify the coordinated dependency or implement the necessary production capability against supported released behavior. Do not leave the promised manufacturing economy dependent on an unreleased API without a functioning supported path.

`AbstractWorkerEntity` inherits through Recruits classes and its `shouldWork()` requires native ownership and suitable follow state. Therefore:

- Select Workers before generic Recruits in every adapter/classifier; civilians must not be conscripted because `instanceof AbstractRecruitEntity` happens to succeed.
- Government employment is separate from a player's native ownership. An NPC government cannot steal privately owned workers or troops during automatic enrollment.
- Explicitly implement native work-area/team/owner permission mapping for realm-owned employment; native `getOwner()` may expect an online player.
- Keep one work/upkeep controller. Workers' native storage-upkeep goal and a Kingdoms logistics controller must not both take the same food, wages or ammunition.
- Preserve native worker screens, owner commands and work-area editing. Offer civic dialogue through a clear additional action or mode; do not recreate the worker-opens-recruit-screen failure.
- Government assignments are persistent leases. Authorized direct player orders can temporarily supersede them with an explicit resume/cancel choice; stale planner output cannot immediately undo a player's command.

Audit native chunk-loading and travel helpers. Some Workers/Recruits paths can force-load chunks. Do not turn 15 settlements into hundreds of permanent chunk tickets. Scope or disable native tickets for managed background units and release them on handover, death, removal and shutdown; preserve unrelated player units' explicitly configured behavior.

### 9.2 Building planner and physical execution

Extend rather than merely enlarge the existing small civic-work templates. Supply a new staged functional building definition with footprint, entrance/path connections, supported rotation, foundation/terrain policy, material list, work stages, service capacity, bed/workstation/storage requirements, and finish validation. Include houses, food stores, farms, wells/water access, basic processing/crafting workplaces, barracks, defensive structures, public offices and roads. Architecture should follow datapack culture and terrain while keeping readable Minecraft construction.

Build stages: select need → reserve safe plot → survey → estimate exact materials and labor → reserve supplies → assign native builder → foundation/frame/enclosure/fittings → validate → register function. Split large works into bounded stages. Handle multiblock beds/doors, orientation, support, container initialization, POI registration and neighbor updates deliberately. Do not import arbitrary schematic block-entity contents, entities, command blocks, scripts or inventories as free construction output.

Workers execute placements; Kingdoms plans, supplies and observes the operation. Reconcile completion against actual world state, not only a native “finished” event. Retain the existing per-block before/after journal principles, strengthen fingerprints for relevant state and block-entity identity, and require actual durable placement evidence. If a builder's newer currency-paid path bypasses materials, disable that path for simulation projects: wages may pay labor, never turn money directly into free construction blocks.

Construction must not bulldoze existing homes, farms, redstone, workstations, storage or other player structures simply because the polity has expanded. Wartime damage is a separate authorization path. Use bounded site searches in already known/generated territory, respect world borders and claim hooks, and report blocked projects. Planned work may wait for safe materialization; routine manufacturing and deliveries should still run in the background.

New settlement founding requires surplus people who actually relocate, food reserves, transport, building supplies, a valid site and an initial administrative plan. Send a founding expedition and preserve its member identities. Create the recognized settlement only when the site satisfies real settlement criteria; partial camps remain camps. Record one founding operation so repeated chunk discovery or restart cannot create duplicate towns. Existing settlement merging/redirects must absorb the resulting evidence correctly.

## 10. Armies, destructive warfare and recovery

### 10.1 Forces and logistics

An army is a persistent list of actual units with commanders, roles, equipment, health, experience, orders, supplies, allegiance and authorized employment. It is not a power score that spawns a new contingent each battle. Muster civilians only through an explicit eligibility/employment/promotion path; respect adult status, essential civilian jobs, existing commitments and player ownership. Training, gear and recruitment costs are physical operations. Recruitment pauses when supplies or capacity are insufficient.

Use Recruits for supported native combat, formations and equipment handling. Kingdoms supplies strategic objectives, rally/muster routes, logistics, formation/role choices, retreat and recovery. Preserve existing order snapshots, transfer vetoes, and provider persistence verification. Implement missing doctrines through a versioned adapter; do not assume the current DEFENSE/ESCORT/SCOUT paths already provide autonomous campaigning.

Commanders evaluate actual military strength, terrain access, morale, losses, supplies, civilian risk and victory goals. Plans can choose to defend, escort, raid a valid military target, contest a route, besiege a settlement, retreat, negotiate or demobilize. No strategic teleportation across dimensions, unlimited recruitment, replenished arrows, or replacement armor without supply receipts. Unpaid or undersupplied units can resign/desert rather than vanish or starve to death.

### 10.2 Targeting and battlefield consequences

The current [MilitaryTargetPolicy][K-target] protects civilians, and campaign checks can protect capitals or require the defending native leader online. Introduce a simulation event policy implementing the selected rules rather than globally disabling safeguards. For the new full-simulation defaults, capital capture and approved civilian/player-property consequences must be possible under admitted conflicts. Preserve explicit existing operator choices during upgrade and show which effective setting prevents an action.

Every war effect needs event ID, actor/unit, involved polities, target relation, location, current treaty/custody status and authorization. Target classes can distinguish field forces, defenses, supplies, command buildings, civilian property and people. Ruler doctrine and war aims determine actual choices: the ability to harm civilians does not require indiscriminate slaughter in every conflict. Evacuation, capture, surrender and limited objectives should be viable and materially useful.

Implement the approved effects completely:

- Real casualties with native death, equipment/drop and family consequences; normal player respawn behavior remains intact.
- Stockpile capture/looting as actual inventory custody changes, with no automatic duplication for the victor.
- Damage/breaching of designated defenses and military/civilian buildings, including targeted player property within the valid conflict scope.
- Civilian flight, refugee arrivals, capture, custody, release/escape and return, preserving individual identity and possessions still held.
- Fire/explosion/siege damage only through bounded effects with valid world/protection checks, accountable drop handling and a recoverable damage ledger.
- Structural damage that changes building function, housing, route access and output until real repairs restore them.

Respect external claim/protection APIs and explicit server policy; being a valid military target does not justify disabling all external protections or spoofing operator access. If a claim provider denies damage, show it and choose another objective. Supply a documented integration policy for servers that want wartime access to protected land. Do not silently make all player property immune in Kingdoms itself, contrary to the selected scope.

Avoid combat-target feedback loops: an enemy scoreboard team alone is not permission to attack any civilian anywhere. Prevent allied damage, neutral household conversion into enemies by association, repeated target reassignment, spawn trapping, and a ceasefire being ignored by an old native goal. Existing safe-conduct and surrender commitments must be honored where applicable.

### 10.3 Occupation, annexation and fragmentation

Occupation records military control separately from sovereignty, culture and lawful ownership. Governors maintain order, distribute real supplies, arrange relief, negotiate surrender and rebuild. Resistance depends on treatment, shortages, loyalties and credible claimants. Civilians do not become replacement NPCs when the flag changes.

Annexation, independence and civil-war splits are explicit revisioned transitions. Preserve and rebind applicable offices, charters, contracts, routes, courts, residence, debts, family connections and historical references; invalidate incompatible authority with an explanation rather than deleting records. Handle a conquered capital without deleting the whole realm. Resolve conflicting claims, regency and active elections through existing law-aware services. A newly recognized polity receives its own identity and treasury; it does not gain a duplicate copy of the predecessor's inventory.

Peace stops new hostile admissions and terminates or resolves an active battle at a defined committed boundary. Prisoner/cargo/restoration terms must be observable transactions. Recovery creates repair, escort, relief, resettlement and supply quests tied to actual damage and need. Do not reset the settlement to a pristine template or refill its people and stores for free.

## 11. Influence, secrets, favors and companion contracts

### 11.1 Ruler influence without mind control

Use personal trust, family relationships, verified service, office responsibilities and concrete obligations as distinct inputs to a ruler's decisions. A trusted spouse can receive an audience or a better hearing without gaining unlimited treasury authority. A successful soldier may earn a title or a bounded favor, but does not automatically command all armies. Rulers may refuse, counteroffer, defer for lack of resources, or accept with conditions. Preserve reasons and downstream consequences.

Provide political acts such as request an audience, petition for a project, recommend an official, propose an alliance, argue against war, offer military service, negotiate a grievance, present evidence, ask a favor, call in an obligation, threaten disclosure, reveal a secret, or seek a negotiated concession. Each act uses the same server-authorized operation as the management UI. Do not derive success from the wording of a client-side dialogue choice.

### 11.2 Evidence, knowledge and obligations

Define a **proposed** political secret record with stable ID, subjects, causal source/event, factual/rumor/contested status, evidence provenance, discoverability, known-to grants, allowed disclosures, revision, expiry/resolution and consequence policy. Define a favor with creditor/debtor identities, originating service/contract, obligation, limits, due rules, settlement status and a single redemption receipt.

Keep world truth, a person's belief, a player's knowledge, and authorized disclosure separate. Realistic secrets can concern a broken treaty, concealed supplies, a claimant's documented promise, a forged order, or an official's diversion of supplies, but generate them only from actual simulated or explicitly authored facts. Do not invent offenses for arbitrary existing villagers. Rumors can influence beliefs and provoke investigation; they are not proof for titles, rewards, ownership or guilt. No confidence amplification from hearing the same rumor repeatedly.

Conversations already has episodes, provenance/privacy, rumors and commitments, but its [generic event observer is unavailable][C-commitment] in inspected 1.8.1 source. Add a versioned political action/knowledge/obligation bridge. Conversations owns presentation and conversation history; Kingdoms owns political facts, decisions and favor settlement. Never parse dialogue prose into authoritative evidence or directly rewrite private history NBT as an integration shortcut.

Filter secrets on the server before sending every screen, topic label, map, tooltip, notice, error, count or debug response. An unauthorized player should not learn that a particular person has a secret merely by seeing a disabled action or count. Explicit disclosure commits who learned what, from whom, and any betrayal consequence. NPC death, migration, conversion, crown succession, template reload or provider removal must not clone an obligation, resurrect a debtor, or automatically mark an unverifiable promise broken.

Conversations absent: the same political functions, requests, secrets and favor redemption remain available through Kingdom Tasks, officials and Quests with a native text/dialogue fallback. Optional compatibility must not become a hidden required mod.

### 11.3 Quests: finish the real receipt and payment integration

At inspected public source [`784f5d5b7061ae3e8c441467204e7e8095379169`](https://github.com/otectus/MCAQuests/commit/784f5d5b7061ae3e8c441467204e7e8095379169), Quests identifies 1.7.1 and exposes durable completion receipts through [McaQuestsApi][Q-api]. Its institutional consumer still names `ultima_kingdoms:regional_civic_network`; the expected modern accessor is absent. [InstitutionalCommissionBridge][Q-payment] also restricts its supported payment definition to one fixed unenchanted emerald reward because additional rewards are not safely tracked across retry. These are implementation blockers for renamed, custom-currency political commissions, not resolved by increasing a minimum-version string or editing a reward JSON.

Coordinate additive changes in Quests: namespaced consumer identity, legacy alias/migration, versioned contract capability, per-payment/reward progress, and a physical treasury-backed payment/delivery protocol. Keep already accepted legacy commissions completable under their original frozen terms. Do not remove the emerald guard until the replacement replay-safe payment contract exists.

The required flow is: read committed receipt → validate issuer/accepted context/contract instance → commit Kingdoms effects and dedupe key → acknowledge provider receipt. Deduplicate by provider epoch, receipt ID and effect namespace; retain consumer history beyond the provider's short acknowledgement tombstones. A raw quest event can notify a UI but must not independently mint payment, service credit, cargo or a favor. Missing/corrupt/future-schema receipt state suspends the affected action with a clear reason.

Bind jobs to concrete instances: a specific convoy, named damaged building, captive, pending negotiation, supply deficit or investigation. Freeze polity, issuer, destination, accepted item/currency terms and relevant world context. A generic historical quest completion cannot satisfy a newly created war contract. When a native delivery objective consumes items, escrow/transfer those exact goods to the destination once; do not credit a fresh copy simply because a completion event arrived.

Use the existing keyed `QuestDialogueHooks.addResolver(id, resolver)` chain so political dialogue coexists with Conversations; return no override for unrelated quests. Do not overwrite the legacy global resolver or duplicate native acceptance, capacity, cooldown and completion checks. Test Quests with and without Conversations and with compatible Reputation/Crime integrations present. Crime is optional: resolve any legacy paid-work legality dependency by supplying a proper core policy for the new contracts, not by making Crime an unapproved hard requirement or blindly treating all actions as legal.

## 12. Player experience and presentation

Extend Kingdom Tasks, the Book of Kingdoms, Village Ledger, government screens, Guilds and War Room using the existing manuscript style and accessible controls. Do not introduce a disconnected debug dashboard as the normal player interface. Public offices, workplaces, stores, roads, damaged buildings and caravans should visibly explain what the records mean.

| View/action | Information and interaction required |
| --- | --- |
| Settlement overview | Population, occupied/available housing, urgent needs, public stock and runway, current work, recent events, ruler and officials; explain the top few problems and remedies. |
| Economy/logistics | Actual stock versus reserved/in-transit, treasury and selected currency, production bottlenecks, shipment location/status, unavailable items, transaction history and authorized supply actions. |
| Ruler/official audience | Known priorities and concerns, relationship context, petitions, evidence, favors, requests and reasoned response; no omniscient hidden plan display. |
| Government management | Appointments, priorities, delegation, project budgets, diplomacy and civil administration, all filtered by current office authority. |
| War Room | War aims, participating forces, real supply readiness, staged/current battle, presence waiting reason, casualties/captives, occupation and recovery. |
| Development | Surveyed plan and building preview, costs already reserved, builder/status, blockers, next stage and functional completion evidence. |
| Local news/history | Committed public developments, private notices only to entitled recipients, discoverable facts, filters and bounded notification frequency. |
| Help and server health | Requirements, provider capabilities, active authority owner, full-parity status, missing contracts, migration progress, backlogs and recovery actions. |

Both dialogue and screens invoke one operation ID/revisioned action flow. Show the exact proposed change, authority and cost before a player submits an irreversible management action. NPC decisions are autonomous and need no human review screen. Results must distinguish proposed, waiting, underway, completed, refused and recovery required. Retry reads an existing operation before issuing another one.

Support citizens, officials and rulers without requiring command use. Give an ordinary player meaningful participation: carry a measured shipment, escort a convoy, join training, repair a breach, investigate evidence, negotiate for an NPC, visit a captive, volunteer for relief, or help a founding expedition. Completing work updates its actual world operation and associated quest exactly once.

Keep native worker/recruit interaction reachable, including owner commands and held-item/sneaking behaviors. Test with MCA dialogue and optional Mob Compat; ensure worker-first dispatch and clear choices between native controls and civic conversation. Respect multiplayer ownership and avoid actions silently targeting whichever NPC is nearest to the client.

Use localization keys and the repository's existing supported language conventions; do not hardcode visible enum/debug identifiers. Ensure GUI scale, scrolling, keyboard focus, narration, readable contrast and color-independent states. Provide configurable ambient announcements and visual intensity. Never spam a waiting battle announcement each tick or each visit.

## 13. Datapacks, configuration and published contracts

Add versioned definitions for realm presets, ruler doctrines, official roles, production categories/recipes, worksite/building templates, population attraction, trade policy, army loadouts, war objectives, major-event classes and social evidence/favor types. Reuse existing loaders and diagnostics where possible; mark proposed new schemas separately from current schema-1 kingdom definitions.

Established-realm definitions need a stable seed ID, cultural definition reference, explicit member-selection rules with conflict diagnostics, leadership/office initialization rules, startup diplomacy, architecture and finite initial-resource policy. Two selectors matching a settlement must not resolve nondeterministically. New seeds are applied once; datapack reload cannot repeatedly create rulers, stockpiles, armies, immigrants or money. Independent-start mode remains functional with no established-realm pack.

Validate definitions as a coherent set before replacing live ones. Report missing item/recipe/provider IDs, invalid dimensions, unsupported building states, impossible costs, negative/overflowing amounts, cycles, inaccessible stages, overlapping realm selectors, unbounded offspring/immigration, and missing narrative targets. Freeze accepted job/quote definitions or version them for recovery. Removal of a definition makes dependent pending work inspectable and cancellable; it must not delete people, cargo or contractual obligations.

Separate settings into understandable groups: world start/participation; ruler and diplomacy pace; population and nonlethal needs; economy/stockpiles/currency; work/construction; military/destruction; presence gating; provider ownership; performance/history; presentation. Display effective settings and ownership, especially when an older server config preserves stricter civilian/capital protection. Never silently overwrite unrelated provider settings.

Offer diagnostic actions in Kingdom Tasks with command equivalents. Suggested command families are **proposed**, not current syntax: simulation status/why, polity mapping, person/worker ownership, stockpile accounting, transaction inspect/recover, event gate explanation, parity report, and migration report. Operator repair must inspect before changing state and record its own receipt. Avoid a generic “fix all” that deletes disputed records or regenerates inventories.

Publish versioned provider-neutral DTOs and operation outcomes through the existing API artifact; do not expose native MCA/Workers/Recruits classes in the core API. Preserve legacy clients/consumers with explicit mapping or a documented protocol/API version change. Add a compile-checked addon example showing a simple observer, a permitted action request, and refusal handling.

## 14. Persistence, migration and compatibility

### 14.1 Automatic enrollment without destructive adoption

Enrollment runs after canonical settlement reconciliation, automatically and in bounded resumable batches. Its job is to register simulation participation and map known people, governments, buildings and public resources. It is not authorization to rename a town, replace a ruler, confiscate private stores, rehire privately owned units, change native teams, or reinterpret cultural membership as a fresh kingdom seed.

Persist an enrollment version, operation ID, phase cursor and provenance for each record. Use idempotent mappings so duplicate discovery, chunk events and restarts do not add residents twice. Resolve saved redirects for every settlement reference. If two existing authorities conflict, preserve both records and mark the contested feature for explicit reconciliation; keep unrelated routine simulation running. An unloaded ruler is not an empty throne.

Keep an explicit migration crosswalk from legacy kingdom-definition keys to political realm identities across government, dynasty, civic organizations, reputation, quests, agreements, warfare, UI and provider mappings. Preserve names, heraldry, offices, claims, discovery, residence, treaties, active quests, native orders, families, equipment and history. Missing datapacks or temporarily absent optional mods retain dormant references rather than default reassignment.

### 14.2 Legacy goods and construction

Legacy `abstractGoods` have never represented withdrawable stacks. Archive or retain them as historical development indicators; do not mint matching items/currency during migration. Initialize physical public stock from verified real inventories and any explicitly defined finite new-world provisions, not from accumulated abstract output. Existing development contribution/refund receipts remain honored once.

Retain funded and partially built civic works, their journals and player contributions. Migrate only when the new executor understands their exact state; otherwise let the legacy path complete under its original rules. Do not make the new builder consume again for blocks already funded, or refund a contribution whose material remains built into the world.

### 14.3 Safe persistence contract

Follow the existing durable-store recovery/future-schema behavior. Use monotonic revisions and durable IDs for plans, people, inventories, shipments, jobs, events and effects. Persist an outbox for cross-provider events and an inbox/dedupe ledger for their acknowledgements. Reference-aware retention must keep any IDs still used by quests, obligations, pending transactions or provider receipts.

Define schema migrations, checkpoints, resumability, backup/restore behavior, and corruption diagnosis for each new store. A damaged future-schema file must never be interpreted as an empty society. Distinguish recoverable unknown provider state from genuinely absent records. Atomic file replacement is useful, but operations crossing inventories, entities, chunks and mods require a tested journal and readback protocol. Never announce success before the owning durable operation commits.

Provider add/remove/upgrade: stop new dependent work, reconcile custody, preserve outstanding records, switch execution ownership through an epoch transition, and reactivate only after capability verification. Required companion absence blocks the complete simulation distribution with a clear requirement; unsupported required ABI must not run destructive partial behavior. Optional absence selects the designed fallback or preserves dormant external authority. Explicitly test Townstead removal/reinstall, Conversations absence, currency loss, Living Villages absence/presence and Capitals-owned crowns.

### 14.4 Required source/artifact matrix

| Component | Reviewed evidence | Coding-agent action |
| --- | --- | --- |
| Kingdoms | `5602f2ca…`; metadata 0.3.0, MC1.20.1/Forge47.4.23/Java17. | Reconcile current head; preserve newer fixes and exact build/protocol conventions. |
| MCA Reborn | Kingdoms historical 7.6.26 tuple; current Townstead Forge build expects 7.7.1-beta.5. | Resolve actual Forge artifact/source, namespace and family/save behavior; certify an upgrade path rather than silently replacing jars. |
| Townstead | `c0c1634c…`, 0.8.0; Forge sub-build; API v1 revision 2. | Certify present and absent full-function variants; add mutation/ownership contracts where public API is read-only. |
| Recruits | Historical tested 1.15.2 jar; release source candidate `1cc10948…`; source-ahead main `e06f8c9f…` also says 1.15.2. | Pin artifact hash/ABI; add realm authority, role classification, starvation/despawn and event-gate adapters. |
| Workers | Published 2.0.3; candidate `d15dd820…`; main `075a8a16…` says 2.1.0. | Certify released capability or a separately released coordinated tuple; implement manufacturing gaps without free-material shortcuts. |
| MCA: Quests | `784f5d5b…`, public 1.7.1 has legacy institutional identity and emerald-only payment restriction. | Publish and test modern consumer/payment/delivery contracts, preserving accepted legacy jobs. |
| MCA: Conversations | `184f8095…`, public 1.8.1 has history/privacy but no functioning generic event-observed commitment resolver. | Add optional political knowledge/favor/action contract and native functional fallback. |
| Custom Trade Currency | `28841f41…`, 0.8.0; public merchant API, internal currency manager. | Add snapshot contract or exact pinned bridge; certify real denomination/escrow behavior and emerald fallback. |

Do not copy native implementations or bundle provider classes as a shortcut to integration. Work through legitimate APIs/adapters and coordinated upstream changes. Keep artifact acquisition and licenses documented in the implementation repository without claiming that source visibility makes redistribution unrestricted.

## 15. Performance and networking budgets

Test the intended 5-, 10- and 15-settlement worlds, each with a documented population/workforce and a few players. Suggested stress fixtures: 20–40 residents per settlement, multiple simultaneous production chains and caravans, 2–3 players in separate regions, and two admitted battles with bounded rosters. These are proposed test workloads, not fixed population limits or measured performance claims.

Use fair round-robin/due-time scheduling and per-domain budgets. Spread ruler decisions, trade pairing, workplace scans, site search, path recalculation, needs reconciliation, serialization and history maintenance across ticks. Index jobs by due time, residents by settlement, inventories by owner/location, and routes by endpoints; avoid rescanning every entity, container or realm pair each tick. Batching must not starve a remote settlement forever.

Suggested initial engineering goals on a declared reference server: average incremental coordinator cost below 2 ms/tick, p99 incremental work below 5 ms/tick, and sustained 20 TPS under the target fixture with total tick budget measured separately. Profile real provider AI, pathfinding, disk I/O, network payloads and garbage collection; adjust quotas using evidence. Do not report these goals as achieved until measurements exist. Record CPU, memory, heap, JVM, mods, world and workload in the report.

Avoid per-item synchronous disk flushes across the entire world. Batch where correctness permits, but never acknowledge a committed transaction before its durable boundary. Track queue ages, simulation lag, stalled operations, active entity counts, chunk tickets, provider handover failures and save latency. Hitting a bound produces backpressure and a visible reason, not discarded jobs or regenerated stock.

Send viewer-authorized deltas and paged snapshots. Validate payload lengths, identifiers, amounts, enum values, permissions and expected revisions. Do not trust client prices, quantities, ruler identity, affiliation, nearby coordinates, secret knowledge or completion claims. Enqueue server mutations on the server thread and avoid chunk loads from unchecked client positions. The Forge [capabilities][F-cap], [SavedData][F-save] and [networking][F-net] documentation are supporting references; the existing repository's durability protocol remains the project-specific persistence baseline.

## 16. Implementation sequence with completion gates

Deliver the full agreed scope through coherent stages. Staging is an engineering sequence, not permission to stop after a scaffold or market a partial economy as complete.

| Stage | Concrete implementation | Gate before moving on |
| --- | --- | --- |
| 0 — Audit and baseline | Reconcile source pins, exact required jars, current tests, API manifest, native owner/menu behavior and copies of legacy-world fixtures. | Reproducible build; accurate capability matrix; known incompatible combinations identified. |
| 1 — Domain identity and authority | Stable polities, legacy crosswalk, player/NPC actor validation, automatic enrollment, migration and one-owner registry. | Independent same-culture towns work; legacy leaders/private ownership survive restart; NPC decisions need no online player. |
| 2 — Physical transactions | Public stockpiles, exact stack custody, reservations, escrow, treasury, currency provider and crash recovery. | Fault-injection conservation suite passes; custom coins and emerald fallback work without duplicated refunds. |
| 3 — Resident/work simulation | Townstead present/absent parity, native workforce adapters, schedules, needs, actual production, population growth and migration. | Full civilian supply chain operates loaded and background; no duplicate people/output or deprivation death. |
| 4 — Trade and development | Caravans, physical imports/exports, construction stages, functional buildings, expansion and founding. | Two towns specialize/trade/build without manual micromanagement or free resources. |
| 5 — Government and narrative | Autonomous rulers, officials, diplomatic contracts, influence, secrets/favors, Quests/Conversations bridge changes. | A ruler's reasoned decision can be influenced through a real quest or obligation and executed once. |
| 6 — Conflict and recovery | Armies/logistics, event gate, offscreen active-event continuation, destructive effects, occupation/annexation, rebellion/civil war, peace and recovery. | Current event finishes after player departure; new event waits; all people/items/control outcomes reconcile once. |
| 7 — Integration and release | Complete connected UX, localization/help, datapacks, performance, upgraded-world testing, full-stack certification, documentation. | All binding requirements pass, no placeholder core path, measured evidence and recovery instructions shipped. |

Coordinated companion changes are part of the implementation plan, not promises that an external API will eventually appear. Prepare reviewable patches/PRs in the relevant repositories when needed, keep compatibility shims bounded and removable, and pin the resulting tested artifacts. Do not publish unrelated provider changes or rewrite their release history.

## 17. Acceptance tests and release evidence

Use unit/property tests for pure planners and invariants, GameTests for world effects, and packaged client/server integration tests for actual provider behavior and native menus. Existing project tests and the addon/API checks must continue to pass. Do not replace native integration tests with mocks and call the stack certified.

| Test group | Required cases and measurable result |
| --- | --- |
| Decisions and authority | NPC rulers propose/execute ordinary lawful decisions with zero online players; forged NPC actors fail; officials cannot exceed mandates; no new compulsory council veto; existing external crown owner is respected. |
| Startup modes | Two same-culture independent towns have different governments/treasuries; established datapack realm seeds apply once; conflicts are diagnosed; reload does not reroll the world. |
| Existing saves | Automatic enrollment preserves ruler/office/kingdom history, native ownership, private stock, families, quests and inventory; vacant throne does not split legacy realm; interrupted enrollment resumes; settlement merges redirect every new reference. |
| Gate eligibility | Nearby neutral, far affiliated, spectator and other-dimension players fail; nearby affiliated player on either involved side succeeds; false client residence fails; event gate works for rebellion/coup as well as siege. |
| Event continuation | Disconnect, travel away, chunk unload and server restart during a started battle preserve its token and finish that event; its authorized occupation consequence applies; next battle or separate annexation waits. |
| Gate bypasses | Native provider AI, commands, quest scripts, follow-up attacks, reinforcement spawning and sovereignty endpoints cannot evade admission. No infinite event stretching into an entire campaign. |
| Production | Every default chain consumes the correct ingredients/fuel/tools and yields exact outputs/remainders once; no available worker, missing recipe/input, blocked path or full output stalls with a truthful reason. |
| Physical accounting | Deposits/withdrawals, hoppers, double chests, overlapping storage areas, destroyed stores, concurrent jobs, transport loss, player intervention and refunds conserve custody. NBT-rich/modded items and stack limits survive. |
| Fault injection | Terminate at each journal/provider/chunk/player-inventory save boundary during extraction, escrow, crafting, delivery, payment, building, capture and conquest. Restart yields one outcome, or explicit recoverable uncertainty—never an invented second copy. |
| Background continuity | Load/unload and external chunk loading cannot run both executors; no stale inventory access; farmland/resource-site harvesting cannot be collected twice; unrelated player changes are not overwritten. |
| Currency | Absent provider uses emeralds; supported CTC uses actual configured ladder; exact change/noncanonical denominations, overflow, insufficient funds, full refunds, quote expiry, reload and removal preserve contractual value. |
| Population | Actual MCA births produce one child/family link; migration preserves UUIDs and belongings; generated immigrants obey limits; no duplication on death/travel/unload; no empty town refilled instantly. |
| Nonlethal deprivation | Test Townstead absent/present, `needs.canKill=true`, Recruits starvation enabled and unpaid despawn behavior. Managed people become hungry/unhappy/migrate/desert but do not die or silently disappear from deprivation. Combat damage remains effective. |
| Townstead parity | Run the same food/rest/work/skill/service/social/migration scenario with and without Townstead; all required gameplay functions remain operative; no double consumption, output, XP or births. Add/remove/reinstall preserves state. |
| Native identity/UI | Worker classes are excluded from generic military selection; native worker and recruit controls remain accessible; owner/team permissions persist; promotion/capture/return preserves progression, family and equipment. |
| Construction | Real builder consumes materials once; paid-build path cannot create free blocks; multi-stage house becomes functional; blocked site/claim, changed block, half-door/bed, container initialization and rollback recover safely. |
| Military and destruction | Supply shortages constrain mobilization; actual ammunition/equipment persists; admitted wars can damage civilians/player property under policy; private property is not seized outside valid wartime actions; ceasefire stops stale attack goals. |
| Political outcomes | Occupation differs from annexation; capital fall preserves realm identity; independence and civil-war successor polities do not share/mint treasuries; institution/quest/dynasty references remain valid or visibly transitioned. |
| Captivity and recovery | Prisoner identity/custody survives unload/restart; release/escape occurs once; refugees arrive as the same people; repairs and relief consume actual resources and restore real services. |
| Quests | Duplicate event and receipt replay yield one service award/favor/payment/delivery; legacy accepted emerald jobs still finish; custom payment contract is replay-safe; post-install consumer does not award all historical quests. |
| Secrets/favors | Unauthorized viewers receive no revealing payload/label/count; rumors cannot become proof by repetition; one favor redeemed once through concurrent UI/dialogue requests; dead/missing debtor does not reset obligation. |
| Optional providers | No Conversations still has functional influence/knowledge actions; no Living Villages or Mob Compat still has full core simulation; presence of either selects one authority; optional Crime/Reputation/Capitals do not become accidental requirements. |
| Native/API drift | Wrong same-version jar fails capability certification clearly; source-ahead Workshop/Governor APIs are not assumed in release artifacts; required mismatch cannot begin destructive simulation. |
| UX/network | Keyboard/narration/GUI scales, stale revisions, double-clicks, packet fuzzing, unauthorized actions, private data filtering, pending/error/recovery status and reconnection all behave correctly. |
| Scale and soak | 5/10/15-settlement runs, several clients, split regions and multiple caravans/events; bounded pathing/queues/tickets; no starvation of scheduler domains; at least a multi-hour soak plus restart/provider-transition passes with conservation reports. |

Run the repository's `./gradlew build`/`check` pipeline and compile-checked addon example, then appropriate `tools/test/run_gametests.py`, packaged acceptance and `tools/client-test/` scenarios according to the repository's current environment instructions. Record exact commands, hashes, outcome, runtime limits and skipped coverage. Do not claim a skipped provider run passed. Use fresh worlds where fixtures require them and dedicated copies of old-world fixtures for migration.

### Required end-to-end demonstrations

1. **Independent towns:** three settlements develop distinct priorities; farmers, miners and builders create real goods and buildings; a courier exchanges actual cargo for emeralds. The same scenario works using custom currency and with Townstead absent.
2. **Influence and conflict:** a player builds personal/service credibility, obtains evidence through a real quest, uses a favor in a ruler conversation, and changes a concrete diplomatic decision without acquiring unauthorized office powers.
3. **War over a resource route:** rulers prepare forces independently; a staged battle waits for a nearby affiliated player; the player joins or assists supplies, then leaves. That battle completes and records casualties/control once. The next battle waits. Survivors, prisoners, supplies and damage are still correct after restart.
4. **Rebellion and recovery:** prolonged scarcity produces unrest and emigration, never starvation deaths. A prepared uprising waits for presence. Its bounded outcome can create a successor polity, followed by material-backed relief and repair rather than free resets.
5. **Existing-world upgrade:** named player and NPC rulers, families, private workers, stockpiles, institutions and active quests survive automatic enrollment and a simulated interrupted migration. No extra ruler, town, person, coin or item appears.

## 18. Definition of done

The implementation is complete only when R01–R22 are mapped to working code, content, documentation and test evidence. Ship the functional default production/building/warfare/quest content, complete Townstead fallback, verified provider adapters, connected screens/dialogue, schema migrations, recovery tooling, exact dependency manifest and measured performance results. Update README, CurseForge-facing description, CHANGELOG, MODMAP, configuration/datapack/API references, player guide/Book topics and the certified compatibility record to describe the actual shipped behavior.

The coding-agent handback should identify commits and companion changes, list implemented requirements and evidence, state the tested artifacts/configurations, provide migration and operator recovery instructions, and disclose any remaining unsupported combinations. Do not call an API stub, fake output, decorative worker, unresolved crash-recovery path or missing absent-Townstead simulation “complete.”

## 19. Source register

These links identify the inspected baseline, not an assertion that each repository's current head or installed jar still matches. Revalidate pins during implementation. Source findings are distinguished throughout from proposed design. Official release listings establish availability/loader metadata; they do not certify this combined stack.

[K-development]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java
[K-template]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/development/DevelopmentTemplate.java
[K-dev-store]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/development/DevelopmentSavedData.java
[K-survey]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/development/PlotSurvey.java
[K-atomic]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/persistence/AtomicSavedDataWriter.java
[K-config]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/docs/Configuration.md
[K-companions]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/tools/test/companions.json
[K-target]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/warfare/MilitaryTargetPolicy.java
[T-build]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/build.forge.gradle.kts
[T-work]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1/WorkApi.java
[T-record]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1/model/VillagerRecord.java
[W-release]: https://modrinth.com/mod/villager-workers/version/49csRXJc
[CTC-api]: https://github.com/otectus/CustomTradeCurrency/blob/28841f418a12d6dbb4da2c763eb578f1464c33ad/src/main/java/com/customtradecurrency/api/package-info.java
[C-commitment]: https://github.com/otectus/MCAConversations/blob/184f8095c95019f69e2f24a2aa5f981aed34a8c3/src/main/java/dev/otectus/mcaconversations/history/CommitmentResolver.java
[Q-api]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/api/McaQuestsApi.java
[Q-payment]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/quest/InstitutionalCommissionBridge.java
[F-cap]: https://docs.minecraftforge.net/en/1.20.x/datastorage/capabilities/
[F-save]: https://docs.minecraftforge.net/en/1.20.x/datastorage/saveddata/
[F-net]: https://docs.minecraftforge.net/en/1.20.x/networking/simpleimpl/

Additional implementation entry points:

- [GovernmentService](https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/politics/GovernmentService.java), [SettlementRecord](https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/core/SettlementRecord.java), and [PoliticalPartyRef](https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/api/politics/PoliticalPartyRef.java): actor permissions, legacy cultural keys, and existing independent-realm concepts.
- [CampaignService](https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/warfare/CampaignService.java) and [EvolutionService](https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionService.java): existing campaign authority, sieges, presence and opportunity progression.
- [Workers AbstractWorkerEntity, 2.0.3 source candidate](https://github.com/talhanation/workers/blob/d15dd8201b16c4ca24e1e49890f9f921306128b0/src/main/java/com/talhanation/workers/entities/AbstractWorkerEntity.java) and [Recruits AbstractRecruitEntity, release-version source candidate](https://github.com/talhanation/recruits/blob/1cc10948f6e04fd146f9eb7fb84421033629dc1a/src/main/java/com/talhanation/recruits/entities/AbstractRecruitEntity.java): native ownership/work gating, persistence and deprivation paths.
- [Workers inspected main metadata](https://github.com/talhanation/workers/blob/075a8a16e73615fc926dd54d245046d350a8c385/src/main/resources/META-INF/mods.toml) and [Recruits inspected main metadata](https://github.com/talhanation/recruits/blob/e06f8c9fc666e256e133f27bbf9a5716a9676334/src/main/resources/META-INF/mods.toml): why released and source-ahead capabilities must be kept distinct.
- [Official MCA 7.7.1-beta.5 artifact listing](https://modrinth.com/project/1W98a849/version/KJixRMB5) and [official Custom Trade Currency 0.8.0 artifact listing](https://www.curseforge.com/minecraft/mc-mods/custom-trade-currency/files/9379767): loader/version availability, separate from combined-runtime certification.




