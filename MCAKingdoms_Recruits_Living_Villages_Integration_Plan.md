# MCA: Kingdoms × Recruits: Living Villages
## Full compatibility and integration — coding-agent implementation specification

**Prepared:** October 28, 2026  
**Primary repository:** https://github.com/otectus/MCAKingdoms  
**Target platform:** Minecraft 1.20.1, Forge 47.x, Java 17  
**Baseline:** MCA: Kingdoms 0.2.0; Recruits 1.15.2; Recruits: Living Villages 1.1.0  
**Deliverable:** Implement and validate an optional integration in MCA: Kingdoms. This document is a specification, not a claim that integration already exists.

> **Core objective:** Living Villages should supply autonomous settlements and armies; Kingdoms should give those same settlements consistent civic identity, political context, history, player interactions, and companion-mod consequences. Villages must be able to initiate and resolve wars without a player directing them, including outside player view, without duplicate populations, competing diplomacy systems, or lost MCA characters.

Read this document completely before editing. Inspect repository instructions and the current source before implementing. Preserve newer fixes if the branch has advanced beyond the inspected baseline. Complete the feature, migration, interface, and runtime validation work; do not stop at dependency detection, a compatibility label, or a read-only display.

## 1. Intended player experience

With the three supported mods installed and warfare permitted by the server:

1. A Living Villages settlement receives one Kingdoms settlement record. The arrival title, Village Ledger, War Room, and Living Villages screen refer to the same place.
2. Its population, treasury, buildings, army, capital relationship, and controlling faction remain grounded in Living Villages and Recruits.
3. AI factions develop relations, prepare armies, fight, retreat, conquer, and make permitted diplomatic changes without a player impersonating their leader.
4. Players can see the conflict and its consequences in Kingdoms, participate through authorized actions, negotiate where their authority allows, and perform supported civilian work.
5. A battle remains the same conflict when players approach or leave. Reinforcements, casualties, occupation, rewards, and history are applied once.
6. Conquest changes military control and the applicable political administration. It does not casually rename the town, replace its residents, change their genetics or families, or rewrite the settlement's civic kingdom.
7. A named MCA resident remains that person after employment, promotion, travel, chunk unload, world restart, or conquest.
8. A peace agreement accepted through Kingdoms is respected by Living Villages' next diplomatic and war-planning evaluations.
9. Existing saves can acquire the integration without requiring newly generated terrain, resetting provider data, or rebuilding established villages.
10. The feature remains optional. Kingdoms without Living Villages, and its existing player-led Recruits integration, continue to work.

**Example:** Two independently governed Lunari settlements may become rivals even though they share a civic kingdom. If one occupies the other, the Ledger reports its new controller and the battle history while preserving the occupied settlement's Lunari identity and residents. If a Serenum faction later wins that settlement, the same rules apply; cultural identity and actual control are separate facts.

### Scope boundaries

- Implement this in Kingdoms with an isolated optional adapter and narrowly targeted compatibility hooks. Prefer a real upstream API if one becomes available.
- Do not fork or redistribute Living Villages or Recruits as part of Kingdoms. Their inspected distributions are marked All Rights Reserved. Dependencies stay separate.
- Do not replace Living Villages' economic simulation, army balance, building catalogue, equipment integrations, or combat model with a competing simulation.
- Do not invent a new six-faction conversion that merges every settlement with the same Kingdoms label. Existing independent factions must survive.
- Do not implement another Minecraft version or loader in this task. Do not advertise an untested version range.
- Supporting optional MCA companions means using their actual contracts and preserving their data. It does not authorize rewriting each companion's whole feature set.

## 2. Research baseline and evidence

### 2.1 Exact inspected inputs

| Component | Inspected input | Implementation implication |
| --- | --- | --- |
| MCA: Kingdoms | `main`, commit `01f8d14a0eece06c8c06f9d2bf2b814d244c5506`, October 27, 2026; source identifies 0.2.0 | Use this as the evidence baseline, then reconcile with current HEAD. |
| Recruits | Official `recruits-1.20.1-1.15.2.jar`; Modrinth version `2zXpVxK4` | Kingdoms currently enables military hooks only for exactly 1.15.2. |
| Living Villages | Official `recruitsvillages-1.1.0.jar`; Modrinth project `TLtnScVf`, version `DhD8obdv`, published August 22, 2026 | Its actual mod ID is `recruitsvillages`; its implementation package is `com.diego.recruitsvillages`. |
| Optional MCA Reborn | Kingdoms documents validation against `7.6.26+1.20.1`, with Architectury `9.2.14` | Start the MCA identity test matrix with this tuple. |

Verified download fingerprints:

~~~text
Living Villages 1.1.0
SHA-1:   92f8e9ef21ad7c82cfdaccf4390be7db7aff60cb
SHA-256: 55a7da1802360bbc18337ad190b826a1a2292f3038c623ab09f926bb0746dda8

Recruits 1.15.2
SHA-1:   8d2ed3f0ab18c67a1d9394526bcae47b3ec162ab
SHA-256: 4b53c1b752e886ba10985aad2df30d668971810230eb77867e07428a21d1af7f
~~~

Living Villages' description still contains a 1.0.6 heading, but its release metadata and inspected JAR identify **1.1.0**. Its Modrinth version metadata requires the specific Recruits version `2zXpVxK4`, which resolves to **1.15.2**. Its JAR metadata permits Recruits `[1.15.2,)`; that broader declaration does not establish compatibility with every newer release. [L1], [L2], [L3], [R1]

No public Living Villages source URL or issue URL was supplied by its Modrinth project metadata. Its released JAR was inspected locally, including class structure and decompiled implementation. Decompiled Minecraft method names remain partly obfuscated. The findings below are static evidence, not results from launching a combined modpack. Reconfirm exact bytecode signatures, event ordering, and runtime behavior before writing mixins. Do not invent a public `LivingVillagesAPI` or assume the symbols below are a supported extension API.

### 2.2 Confirmed integration obstacles

| ID | Current evidence | Required response |
| --- | --- | --- |
| F01 | `CampaignService` accepts connected `ServerPlayer` actors. Its campaign and receipt records require player UUIDs. [K2], [K3] | Introduce server-created provider actors for AI actions; keep existing player authorization intact. |
| F02 | `siegeAllowed` checks active campaigns, capital protection, and an online defending leader for bound claims. [K2] | Imported AI wars need a valid campaign authorization path and explicit AI/player policy distinctions. Merely binding villages would otherwise obstruct native AI sieges. |
| F03 | `RecruitsEvents` and several Kingdoms mixins enforce siege policy across start, tick, registration, and completion. [K4] | Update the complete policy chain. Do not patch only the first siege event or globally return `true`. |
| F04 | Living Villages registers AI Recruits factions with a zero UUID leader. `FactionData.leaderRef` instead points to a roster unit. [L3] (`RecruitsFactionBridge`, `FactionData`) | Neither value is a connected player or a valid universal political identity. Resolve AI authority from provider records. |
| F05 | `considerAiWars` selects between `declareWar` and `startAbstractSiege`; `tickAbstractSieges` can resolve losses and occupation without a native live siege. [L3] (`WarManager`) | Cover live, abstract, and transition paths. Native `SiegeEvent.Success` alone is insufficient. |
| F06 | `settleDematerializedSiege` can finish a live war through `resolveAbstract` after players leave. [L3] (`WarManager`) | Preserve conflict identity and apply the same policy before both transition and resolution. |
| F07 | `Relations.tick` can write both directions of native diplomacy. [L3] (`Relations`) | Add one arbitration path for treaties, AI opinions, declarations, and native relations. Prevent changes from undoing each other. |
| F08 | `FactionData.getId()` identifies a village record. `getFactionId()` derives from its current capital; conquest can change capital and team string, and remove an unused old team. [L3] (`FactionData`, `WarManager.joinFaction`) | Use stable village IDs and separately persisted realm identities. Do not key durable history by mutable team name or current capital alone. |
| F09 | `ControlState.Binding` permits one native claim per settlement, and its legacy control classification compares mapped kingdom labels. [K5] | Account for claim lifecycle changes and same-culture rival realms. Culture equality cannot prove lawful control. |
| F10 | `CivilianMaterializer.capture` discards loaded civilian bodies and forgets their recorded IDs. Worker dematerialization discards bodies too. [L3] (materialization classes) | Preserve real residents and their identity; do not assume full MCA state is already serialized. |
| F11 | `promoteIfNeeded` replaces a worker entity when its displayed level changes; `consumeJobless`, surplus trimming, and cleanup can also discard bodies. [L3] (`MaterializationManager`) | Integrate employment, promotion, training, and cleanup, not just spawning. |
| F12 | Worker code requires vanilla `Villager` instances and uses vanilla professions and worker behavior. [L3] (`WorkerMaterializer`, `MaterializationManager`) | Audit the actual MCA type hierarchy and behavior; changing `civilianEntityType` alone is not full MCA support. |
| F13 | Recruit persistence records a stable unit ID and a body ID, but `saveState` only records inventory and hunger; other identity state is not a complete entity snapshot. [L3] (`RecruitUnit`, `RecruitMaterializer`) | Preserve additional persistent identity and state where required. Do not claim the native record already covers it. |
| F14 | `WarEvents.onServerAboutToStart` can change Recruits' global siege threshold, noble spawning, and owner-online setting. [L3] (`WarEvents`) | Report the effective settings and enforce scoped Kingdoms policies; avoid another global setting tug-of-war. |
| F15 | Live wars and abstract sieges use private static collections without a durable unique war ID in the inspected records. The provider battle log is bounded. [L3] (`WarManager`, `LivingVillagesSavedData`) | Add durable bridge conflict identities and tested restart recovery. A polling diff or a recent battle log is not a reliable event journal. |
| F16 | `PlayerFactionEvents` creates virtual player faction records before a physical village exists. `PlayerFactionService.join` adds team membership. [L3] | Do not create phantom settlements at a default position or make every member a ruler. |

### 2.3 Read these Kingdoms areas first

- `README.md`, `CHANGELOG.md`, `docs/Configuration.md`, and `docs/Addon-API.md`.
- `core/KingdomsServiceImpl`, `SettlementReconciler`, `ExternalRefIndex`, and the settlement mutation events.
- `api/SettlementCandidate`, `KingdomsService`, `SettlementDetector`, and `VillageFootprintSource`.
- `warfare/ControlState`, `ControlSavedData`, `WarfareRuntime`, `CampaignState`, `CampaignSavedData`, `CampaignService`, and `MilitaryTargetPolicy`.
- Every class under `compat/recruits/` and every `Recruits*Mixin`.
- `api/politics/PoliticalService`, `Politics`, `politics/GovernmentService`, and the political history implementation.
- `evolution/drama/DramaService`, `evolution/EvolutionRuntime`, mobilization, recruit transfers, and civilian contracts.
- `interaction/WarfareTasks`, `PoliticalTasks`, `TaskMeta`, `client/WarRoomScreen`, settlement summaries, and the current manuscript interface components.
- Existing unit, GameTest, packaged-server, crash-recovery, and real-client harnesses under `src/test`, `src/gameTest`, and `tools/test`.

Use current operational references when older R1–R4 design records disagree with newer code or documentation. The R4 world-evolution opt-in is not itself an autonomous-war integration. [K1], [K6]

## 3. Ownership and identity rules

### 3.1 One owner for every authoritative fact

| Fact or operation | Authoritative owner | Kingdoms integration responsibility |
| --- | --- | --- |
| Settlement UUID, civic name, aliases, civic kingdom, discovery | Kingdoms | Attach provider references and maintain one settlement identity. |
| Living Villages village record and development simulation | Living Villages | Read through the adapter; issue supported requests through its owner. |
| Native faction/team, claim membership, live siege progress | Recruits, coordinated by Living Villages | Validate bindings and policy; observe confirmed state changes. |
| AI target selection and simulated combat calculations | Living Villages | Filter through shared policy and record accepted outcomes. |
| Treasury, production, growth, building costs, native hire prices | Living Villages | Display and consume verified transaction receipts; do not run a duplicate economy. |
| Army roster, equipment progression, formations and native orders | Living Villages/Recruits | Preserve ownership, synchronize political context, arbitrate temporary command leases. |
| Political agreements, mandates, recognized sovereignty and civic history | Kingdoms | Add provider realm parties and narrowly scoped AI decision authority. |
| Persistent MCA person, family, genetics, relationships and personal data | MCA Reborn and its existing identity owners | Preserve the same person throughout lifecycle changes. |
| Quest objectives, item consumption and quest rewards | MCA: Quests | Supply context and verified completion inputs. |
| Crime evidence, guilt, custody and legal enforcement | MCA: Crime | Supply control and combat context without fabricating crimes or blanket exemptions. |
| Battlefield corpses and native loot | Native combat / Corpse integration | Do not generate a second loot payout from political history. |

### 3.2 Distinguish these identities in code

Introduce a bridge model that distinguishes:

- **Settlement:** stable Kingdoms place UUID.
- **Provider village:** stable Living Villages `FactionData.getId()` plus dimension and provider/save provenance.
- **Civic kingdom:** the existing Kingdoms resource ID, used for identity and existing cultural content.
- **Political realm:** a durable Kingdoms bridge identity for a sovereign Living Villages faction and its subordinate villages.
- **Native faction:** Recruits team/string ID, treated as a replaceable external reference with history.
- **Capital:** the realm's current capital village, which may change without changing the realm's identity.
- **Military controller:** the faction/realm presently controlling a claim.
- **Recognized sovereign:** the legitimate authority recognized by Kingdoms' current agreement or initial recognized state.
- **Person / unit / body:** persistent person identity, provider roster unit ID, and current entity UUID. These are related identifiers, not interchangeable values.

Many villages may belong to one realm; many independent realms may share a civic kingdom; a realm may occupy villages of several civic kingdoms. A conquest changes the occupied village's controller and provider membership, not the civic labels of every village sharing its culture.

When an old capital falls and surviving villages select a new one, retain the surviving realm ID and record a capital change. Retire the defeated realm only if its actual sovereign organization disappears. Preserve historical aliases and defeat records.

### 3.3 Extend political scope without corrupting legacy APIs

Kingdoms' existing government API is keyed by kingdom string. Do not pretend that all same-culture Living Villages realms are that one government.

Add a typed political-party/scope abstraction, for example `PoliticalPartyRef` with legacy kingdom and provider realm variants. Keep existing kingdom methods working through legacy wrappers. Add realm-aware governments, mandates, agreements, control decisions, and history where needed, or a common political service that accepts these typed parties.

Requirements:

1. An automatically imported AI realm gets a provider-backed political authority view, using its verified native leader/realm state. It does not take over an existing player-founded kingdom government.
2. A Living Villages military leader and a Kingdoms civic office holder can be different people. Show that distinction.
3. Existing kingdom governments can negotiate with a realm. Independent realms can negotiate with each other.
4. If an operator deliberately associates an existing kingdom government with a realm, store an explicit, revisioned association and authority scope. Never infer it solely from matching names or civic kingdom.
5. Agreement scope must identify the participating realm and covered settlements. It must not accidentally bind every independent realm with the same civic kingdom.
6. Existing saved political records retain their meaning. Do not silently reinterpret a legacy kingdom agreement as a treaty with all newly imported factions.

Do not expand this task into mandatory cultural federations or automatic unification. Those would be separate gameplay policies, not prerequisites for integration.

## 4. Adapter architecture and version support

### 4.1 Recommended organization

Use an optional package such as `compat/livingvillages/`, with responsibilities similar to:

~~~text
LivingVillagesCompat             version/capability detection and lifecycle
LivingVillagesAdapter            provider-facing boundary
LivingVillages110Adapter         audited 1.1.0 implementation
LivingVillagesSnapshots          immutable records without provider classes
LivingVillagesBindings           villages, realms, claims, aliases, redirects
LivingVillagesWarBridge          policy, campaigns, transitions, outcomes
LivingVillagesDiplomacyBridge    treaties and relation arbitration
LivingVillagesResidents          persistent people and worker lifecycle
LivingVillagesEconomyBridge      native economy context and receipts
LivingVillagesRecovery           journal replay and reconciliation
LivingVillagesDiagnostics        operator-facing health information
~~~

These are proposed names, not assertions that these classes already exist. Reuse the repository's established service, persistence, and lifecycle patterns. Avoid one large compatibility class with dozens of unrelated reflective calls.

### 4.2 Provider boundary

- Load optional provider classes only after checking the mod ID and exact audited tuple.
- Do not expose Living Villages implementation classes in Kingdoms' public API artifact, packet types, shared static initialization, or unconditional client/server code.
- Use official events where adequate, cached method handles for audited read/request operations, and version-gated mixins/accessors for lifecycle boundaries with no usable event.
- Keep mutation requests on the server thread. Background work may handle immutable snapshots, never live provider objects.
- Unregister listeners, clear queues and handles, and discard server-scoped caches at shutdown. Opening a second integrated-server world in the same JVM must not reuse the first world's runtime objects.
- Detect changes in the audited method/field shape. A version string alone is not enough when patch packs may replace classes.
- Keep dependencies external; use development compile/runtime arrangements already supported by the project. Verify a packaged production JAR because development remapping can conceal optional-class and mixin mistakes.

### 4.3 Capability reporting

Expose capabilities individually: metadata read, village enumeration, stable binding, realm membership, diplomacy request, war preflight, abstract combat interception, conquest observation, resident preservation, worker support, economy receipts, and UI access.

Display `READY`, `PARTIAL`, `UNSUPPORTED`, `UNAVAILABLE`, or `RECOVERY_REQUIRED`, with concrete reasons. Do not label a read-only adapter as full integration.

For an unsupported tuple, keep new binding and mutation disabled and preserve saved bridge data. If an already integrated world depends on identity-protection hooks that cannot bind, prevent the unsafe managed lifecycle from starting; if that cannot be done reliably, fail compatibility initialization before world mutation with an actionable version/recovery message. Silently allowing known destructive resident handling is not a safe fallback.

Full release support initially means **Kingdoms target build + Recruits 1.15.2 + Living Villages 1.1.0**, with separately documented optional-mod tuples. Expand that matrix only after equivalent runtime validation.

## 5. Settlement discovery, binding, and existing worlds

### 5.1 Automatic binding workflow

Run a bounded server-side reconciliation after both providers have loaded their saved data, then update incrementally from verified changes.

1. Read Living Villages records without spawning entities or placing buildings.
2. Distinguish real villages from virtual player faction records. A player faction without a confirmed physical settlement/ledger anchor is a realm record only; an unset/default coordinate is not a village.
3. Resolve an existing external reference first. Use a namespaced key such as `recruitsvillages:village` and the immutable village UUID; keep dimension and world provenance in the binding.
4. If there is no reference, look for an existing Kingdoms settlement with strong spatial/structural evidence that it is the same village. Preserve the existing settlement UUID, locked name, civic assignment, discovery, and history.
5. Where no existing settlement matches, register an external candidate using `SettlementCandidate.external` and `KingdomsService.registerCandidate`. Propose the provider's village name for a new record; obtain civic assignment through Kingdoms' resolver.
6. Supply a trusted provider footprint so later structure/POI/MCA discovery attaches to that same village instead of creating another record.
7. Register realm membership and the actual native claim after verifying both provider and Recruits records. Do not execute player-only mapping commands through a fake player.
8. Commit an idempotent binding receipt and update relevant screens.

Use `ExternalRefIndex`, alias sets, and settlement redirects rather than building an unrelated parallel settlement database. Resolve redirected IDs on every long-lived reference. [K7]

### 5.2 Matching and bounds

- Matching is dimension-aware and deterministic. Never choose a settlement by name alone.
- The inspected Living Villages store/simulation and Kingdoms military lookup are Overworld-based. Support that scope first; reject unsupported cross-dimension bindings explicitly. An Overworld-saved record is not evidence that the provider supports Nether or End villages.
- A rectangular claim envelope is a broad-phase search aid, not proof that all enclosed land belongs to that village. Maintain actual claim chunks and village/building footprint evidence separately.
- A shared faction or shared capital does not merge its settlements.
- Two nearby Living Villages records remain distinct unless a verified provider operation actually merges them.
- A growing claim may cross another settlement's civic footprint. That is a boundary/control conflict to reconcile, not a reason to silently merge towns.
- Respect existing settlement mutation preflight checks. Add a specific bridge-aware merge/rebind transaction when needed; do not globally bypass protections on militarily bound settlements.
- If a match is ambiguous, retain the existing records and expose a named reconciliation task. Show candidates and the reason for ambiguity; do not guess or delete one.
- If terrain is not loaded and cultural assignment needs unavailable biome/structure evidence, retain a pending binding and resolve it when evidence becomes available. Do not force terrain generation just to assign a label.
- Public discovery remains separate from server registration. Importing 100 distant villages must not mark all 100 as visited by every player.

### 5.3 Naming and heraldry

For new settlements, retain the Living Villages name when valid. For an existing Kingdoms settlement, retain its current name and preserve the provider name as an alias.

Provide an explicit rename operation that updates permitted provider display fields and Kingdoms atomically through a recoverable request. Changing a display name must not rewrite team identifiers or external keys. Preserve manually locked names and record aliases.

Use separate visual identities:

- Civic kingdom heraldry identifies culture/affiliation.
- Realm/faction banner identifies current political or military control.

Show both where useful, with labels. Native shields, armor trims, and equipment remain Living Villages/Recruits-owned. A civic rename must not recolor every soldier; a conquest may update native banners through the native owner.

### 5.4 Existing-world migration

Implement an additive, versioned migration that can run without new terrain:

- Import existing Living Villages records, including subordinate villages, player-owned virtual capitals, current claims, and existing conflicts.
- Preserve already mapped Kingdoms/Recruits bindings when consistent. Record disagreements for reconciliation rather than overwriting them.
- Create no retroactive victory rewards, quests, reputation changes, or civilian casualties from the initial snapshot.
- Mark imported old control as a baseline observation, not a newly witnessed battle.
- Back up affected bridge data before schema changes; do not require deletion of either mod's configuration or saved-data files.
- Resume batches across restart. Re-running migration is a no-op for completed bindings.
- Keep data from an absent provider readable as historical/unavailable records. Absence is not faction destruction.

An operator preview should list new bindings, preserved records, unresolved matches, unsafe populations, and required decisions. The ordinary unambiguous import should complete automatically; do not demand manual mapping of every village.

### 5.5 Adopting ordinary Kingdoms/MCA villages

Identity recognition alone must not claim that every vanilla or MCA village has become a Living Villages settlement.

Provide a deliberate **Enable Living Villages management here** workflow for eligible existing villages, in addition to automatic import of native Living Villages villages. It must:

1. Inventory existing residents, buildings, beds, ownership, institutions, claims, and player modifications.
2. Show what simulation, recruitment, construction, and war exposure would become active.
3. Create an adopted provider record through a tested adapter path. Do not call the default founding routine if it would stamp a new town hall, flatten terrain, or create a second population.
4. Seed counts and occupancy from verified existing residents without duplicate starter villagers, troops, currency, or catch-up production.
5. Use existing structures where supported. Treat unknown buildings as preserved external buildings, not empty space.
6. Leave automatic construction disabled for the adopted footprint until a valid layout/protected-area policy is established.
7. Respect player claim and political authority. Proximity or operator UI visibility is not ownership.

If the inspected release has no safe adoption API, implement a narrow compatibility path or arrange an upstream extension. Do not call a destructive normal founding path and describe it as migration.

## 6. Autonomous war integration

### 6.1 Actor model

Add a typed actor representation covering at least authenticated players, verified provider-controlled AI realms, and explicit administrative recovery operations.

A provider AI actor must be constructed only by trusted server integration code, using current realm membership, provider provenance, capabilities, and a saved request ID. It cannot be selected or manufactured in a client packet. Store its realm identity in history instead of a fabricated player UUID.

Keep player authority checks specific to each action:

- Military command requires actual native command authority and any applicable Kingdoms mandate.
- Political ratification requires the current political authority of the represented party.
- A member joining an AI faction is not automatically its commander or ruler.
- Becoming a native player faction owner does not appoint the player ruler of every settlement sharing its civic kingdom.
- Administrative reconciliation records the operator's identity and reason but does not mint ordinary battlefield rewards.

Provide provider-safe service overloads; do not relax the existing `ServerPlayer` overloads to accept null or treat the zero UUID as an administrator.

### 6.2 One campaign for each provider conflict

Give each accepted conflict a durable bridge UUID, bound to its source realm, target village, claim, current controller, coalition, and provider runtime instance. Capture the identity before the provider can launch troops or spend war resources.

Recommended lifecycle:

~~~text
PROPOSED -> NOTICE -> ACTIVE_LIVE or ACTIVE_ABSTRACT
ACTIVE_LIVE <-> ACTIVE_ABSTRACT
ACTIVE_* -> RESOLVING -> COMPLETED / REPELLED / WITHDRAWN / CANCELLED
Any nonterminal state -> SUSPENDED / RECOVERY_REQUIRED when evidence is unavailable
~~~

This is a proposed bridge lifecycle. Map it explicitly to Kingdoms' existing campaign phases and the provider's runtime states; do not claim these enum names exist upstream.

A declaration is not a battle result. An enemy relation is not a siege. A native success event is not necessarily a fully saved conquest. A changed owner observed after restart is not proof of which attacker deserves a reward.

### 6.3 Shared preflight policy

Apply the same policy before:

- AI target selection commits to a target.
- `declareWar`, including player and console paths.
- `startAbstractSiege`.
- Transition from abstract to live or live to abstract.
- Native siege registration, progress and completion.
- Direct abstract resolution and occupation.
- A new ally or reinforcement village joins an existing conflict.

Validate supported provider state, valid binding, current target/controller, faction relation, applicable treaty, capital policy, player protection, valid participants, active-conflict limits, notice, and resources through the native owner.

A denied target should be skipped so the AI can consider another eligible neighbour. Do not let one protected capital monopolize its selection loop. A refused start must not leave spent resources, reserved troops, hostile side effects, hidden siege records, canceled-event broadcasts, or a stuck war slot.

Use the existing Kingdoms siege guards to enforce the accepted campaign, including the completion guard. Do not disable them because Living Villages is installed. For claims outside integration scope, preserve their existing native behavior.

### 6.4 AI versus player policy

Default behavior should preserve autonomous AI-versus-AI conflict while keeping existing player protection meaningful:

| Situation | Default integration behavior |
| --- | --- |
| Verified AI realm attacks another verified AI realm | Permit when native and integrated rules allow; no connected human leader is required. |
| AI realm has player members but remains AI-controlled | Distinguish membership from sovereignty; preserve provider participation rules and clearly disclose exposure. |
| Target is a provider virtual player capital | Preserve Living Villages 1.1.0's native exclusion; do not remove it incidentally. |
| Target is a player-governed or explicitly player-protected settlement | Apply configured offline/notice protections and native restrictions. |
| Legacy Kingdoms player campaign without Living Villages ownership | Preserve current authority, notice and online-defender rules. |
| Existing protected Kingdoms capital | Honor existing capital protection unless an explicit per-world/per-settlement policy changes it. |
| Ordinary AI realm capital, not an existing protected Kingdoms capital | Allow native capital warfare by default; do not make every imported capital invulnerable. |

Evaluate the ultimate controlling realm and applicable player governance, not just the target village's `ownerUuid`; a subordinate village may be controlled by a player-owned capital.

Treat existing warfare switches as effective controls. Explain whether refusal comes from Kingdoms, the bridge, or Living Villages. Leave R4 authored drama opt-in intact, but do not require enabling R4 drama merely to preserve native Living Villages AI warfare.

### 6.5 Live and abstract parity

Living Villages remains the combat executor. The bridge must not calculate an additional outcome or deduct a second casualty batch.

- Register native and abstract casualties against persistent unit IDs, not only current entity UUIDs.
- Freeze the committed contingent/coalition membership for each stage and record legitimate reinforcement changes.
- Each unit can be committed to only one active deployment. It cannot simultaneously defend its home village, appear at another battlefield, and count in an abstract reserve.
- Reuse the same campaign ID across view-distance transitions. Do not issue fresh declaration rewards or repeat the joining prompt on every transition.
- Recheck peace/protection before the next abstract casualty step and before final occupation; don't rely solely on the start check.
- Preserve actual survivors, gear, injuries, mount links and home assignments when returning to physical simulation. Never refill a unit to its template merely because it rematerializes.
- Support multiple attacking factions and allied reinforcements. Replace the legacy assumption that every allied attacker needs a separate manually declared campaign with a verified coalition authorization attached to the shared conflict.
- An ally on both sides, a stale realm reference, or a repeated join must be refused or reconciled deterministically.
- A failed abstract-to-live start must leave a resumable conflict; it must not drop the abstract record and lose the war.

### 6.6 Time and restart semantics

Living Villages advances its economy from server ticks; parts of its live-war flow use wall-clock milliseconds. Kingdoms uses game ticks. Make this distinction explicit in the adapter.

Use persisted remaining game-time durations for bridge notice, authorization expiry, and recovery. Translate native timeouts at the boundary without resetting them on restart. Do not advance bridge wars while the server is stopped or single-player is paused. If a provider has an explicit catch-up mechanism, handle it as a distinct provider operation, not invented elapsed-wall-time combat.

Persist enough information to reconnect a native conflict after save/restart: participants, committed units, stage, target/controller revision, timers, current transition, and outcome receipt. If the supported release cannot restore the necessary native runtime state, implement the targeted restoration hooks or a safe, explicit cancellation/return transaction. Never automatically reroll combat, grant victory, or discard deployed armies because a static list was lost.

## 7. Diplomacy, peace, and political decisions

### 7.1 One effective military relation

Use Living Villages' native opinion calculation as input. Use a shared arbitration service to produce the effective native relation and permissions for bound realms. Kingdoms should not run a second independent AI diplomacy scheduler.

Maintain distinct concepts:

- Numeric opinion and its explanatory modifiers.
- Native directed military relation.
- A formally accepted Kingdoms agreement.
- An active conflict and its coalition membership.
- Civic reputation of an individual player.

Do not turn these into one numeric score. A scholar-exchange agreement does not automatically mean a military alliance; one helpful player does not make two governments allies.

### 7.2 Precedence and feedback prevention

For managed parties, decide relation changes through one documented sequence:

1. Apply explicit protected-state restrictions and valid treaty terms.
2. Apply current conflict obligations and accepted declaration/withdrawal requests.
3. Evaluate native AI proposals within those constraints.
4. Apply the necessary native directed relation changes once, through the audited provider operation.
5. Acknowledge the saved result and publish history/UI changes.

Attach source, operation ID, revision, and before/after relation to each request. Observing the bridge's own native write acknowledges it rather than generating a new request in the opposite direction. Handle the two native relation directions explicitly; do not assume one write updates both.

A deliberate native action that breaches a treaty must produce an explicit, attributable breach decision if policy permits it. A periodic AI recomputation must not silently overwrite an active peace agreement. Rejected writes must not be endlessly retried every tick.

### 7.3 AI diplomacy authority

Allow provider-backed realms to evaluate supported proposals through a server-side, data-driven policy. Use bounded factors already available from provider facts: native opinion, current conflict, relative strength, occupation, recent losses, applicable obligations, and configured terms.

- Human-led parties still require their legitimate human authority; no automatic signature on their behalf.
- AI decisions use the verified realm actor and record the decision reason.
- Begin with peace/ceasefire, military alliance, withdrawal, and settlement-control terms that the adapter can actually enforce.
- Support Kingdoms' relevant civic agreements through typed party scope without falsely treating a native relation as a signature.
- Decisions must have stable request IDs, bounded evaluation cadence, and reproducible audit evidence. Do not reroll accept/reject outcomes every UI refresh.
- If a requested term requires a provider operation that cannot be performed safely, show it as unavailable with a concrete reason. A treaty must not display as active while its practical terms are unimplemented.

### 7.4 Executing peace

A successfully applied peace/withdrawal agreement must address the whole conflict:

- Stop eligible new native and abstract combat progression.
- Cancel or settle queued attacks, battle windows, allied commitments, and pending reinforcements according to the agreed scope.
- Restore or return surviving armies once, keeping inventories and ownership intact.
- Remove active siege state through the native owner, without applying conquest rewards.
- Preserve casualties already incurred and the history of the conflict.
- Respect a configured truce before the same AI retries an equivalent attack.
- Reconcile both native relation directions and provider conflict state before reporting that peace is operational.

Do not globally reset diplomacy or all wars because one settlement signed a local agreement. If a provider's diplomacy is faction-wide, explain that scope before confirmation and reject impossible local-only promises.

## 8. Conquest, control, capitals, and sovereignty

### 8.1 Observe the completed provider transaction

Hook or wrap both live occupation and abstract occupation, plus native claim ownership changes. Register pre-operation intent and acknowledge after the relevant provider saves and claim records agree.

A canonical conquest receipt should include:

- Conflict and operation IDs, provenance, dimension and game time.
- Defeated village and stable Kingdoms settlement IDs.
- Previous and new realms/native factions; previous and resulting claim references.
- Participating sides and the authority that caused the operation.
- Confirmed capital/membership changes.
- Confirmed unit transfers and aggregate casualties, with personal detail restricted appropriately.
- Native resource/spoils transaction references where available.
- Before/after control revisions and durable acknowledgment state.

A short provider battle log may inform presentation; it cannot substitute for this durable receipt.

### 8.2 Preserve distinct control facts

After occupation:

- Update actual controller, provider faction membership, garrison and relevant display banner.
- Preserve settlement UUID, name/aliases, civic kingdom, residents, homes, buildings, guild memberships and accepted contracts.
- Record **occupied** when the controller differs from the recognized sovereign realm, even when both realms share a civic kingdom.
- Keep recognized sovereignty until the appropriate authorized settlement decision changes it.
- Apply local law through Crime's actual jurisdiction contract. Do not erase outstanding cases or treat occupation as an amnesty.
- Scope government changes to the affected realm/settlement. Do not replace a whole cultural kingdom's government after capturing one village.

### 8.3 Claim and faction lifecycle

Cover claim expansion, replacement, split/merge if supported, transfer, deletion, recreation, capital relocation, faction defeat, disbanding, and liberation.

Preserve existing one-claim bindings when still valid. Where the provider legitimately changes the claim identity or a village spans multiple claims, introduce a versioned claim-binding set and verified successor links. Keep one authoritative controlling interpretation per claimed area; do not simply loosen uniqueness checks.

A rebind must have provider evidence tying old and new records to the same village. Retired claim UUIDs must not be silently reused. Unrelated claims near the former center cannot inherit old war permissions.

When the last village of a realm is lost, retain its historical identity and treaties as defeated/expired as appropriate. When a player faction disbands and subordinate villages become independent, derive new or restored realms from the actual provider result without losing each settlement's history.

### 8.4 Consequences are receipt-driven

Publish political history, notifications and supported companion context after confirmation. Do not pay a second conquest bonus, create duplicate corpses, or award player kills for abstract casualties.

Player rewards and standing changes require attributable participation and the normal owner receipt. A player who happened to be online, visited earlier, or belongs to the same culture is not automatically a combat participant.

Treat a no-battle operator ownership edit as an administrative control change, not a victory. Handle canceled success events and partially applied conquest as recovery states, not completed achievements.

## 9. Persistent residents, workers, and recruits

This is a release-blocking part of full compatibility. Simply switching a configured entity type to an MCA villager is insufficient because other native paths discard, replace, train, promote, or clean up the resulting entity.

### 9.1 Default: retain persistent people

For integrated settlements, preserve materialized residents as ordinary persistent entities saved with their chunks. Maintain durable bridge identity and employment records while they are unloaded. Do not repeatedly destroy and recreate established residents as an optimization.

An abstract population slot can remain virtual before its first materialization. Once it becomes a real person, bind it to a persistent identity. Growth may create additional pending slots, but it must not create a second body for an existing person.

If virtualizing a particular entity is necessary, require a tested full-state snapshot/restore contract and an exclusive ownership handoff between the world entity and virtual record. Otherwise retain the real entity and suspend only the unsupported operation.

Do not keep village chunks force-loaded to accomplish this. Ordinary entity persistence and bounded saved-data summaries should support unloaded villages.

### 9.2 Required identity record

For each managed person/unit, retain as appropriate:

- Stable bridge person ID, actual entity UUID, original entity type, provider village and unit/slot IDs.
- Residence, employment slot, recruitment status, current deployment, and confirmed last-known dimension/location.
- Lifecycle state: pending first spawn, materialized, saved/unloaded, verified virtualized, migrating, dead, or unresolved.
- Data needed to preserve name, age, sex/gender as provided by the entity, appearance, genetics, traits, relationships, family references, home and profession.
- Inventory, equipment, health, effects, experience, hunger/morale where supported, ownership, and mount/passenger relationships.
- Persistent Forge data/capabilities and companion references through their actual supported persistence contracts.
- Source generation/revision and the last acknowledged lifecycle operation.

Do not blindly copy temporary AI targets or stale entity references into another world/session. Define which fields belong to the provider, which are durable personal state, and which must be rebound when the same entity becomes active.

### 9.3 Spawn and MCA conversion

Audit the actual MCA spawn/conversion flow for the supported tuple. Prefer an explicit supported entity factory/initialization path that creates the intended person once.

Living Villages currently adds a civilian entity before attaching some of its tracking tags. Integration must handle an MCA conversion/cancellation during entity-join processing, not assume the returned vanilla entity is still the live resident.

- Reserve the person/slot ID before adding an entity.
- Apply integration provenance before conversion callbacks need it, through an audited hook.
- If MCA replaces the body, transfer/rebind the provider tags, worker slot, persistent state and identity references to the actual surviving entity.
- Preserve UUIDs where supported; where replacement necessarily changes the entity UUID, retain explicit old-to-new aliases and update all affected references transactionally.
- Never treat a canceled spawn as a successful materialization or as a death.
- Do not globally disable MCA conversion. Restrict any suppression to a specific bridge spawn whose replacement is already handled correctly.
- Do not create a one-sex population by selecting a single entity ID as a shortcut. Use the provider's valid population initialization mechanism.

### 9.4 Employment and promotion

Replace destructive worker transitions for integrated persistent residents:

- Employ the existing eligible adult when possible. Preserve the same person and body; changing jobs must not clear their family or reputation.
- Apply supported rank/profession changes in place. Native `promoteIfNeeded` replacement is not acceptable for a persistent MCA resident.
- Preserve relevant trade progress and job experience according to the chosen worker policy. Do not reset trades by respawning on each promotion.
- Keep construction-slot identity separate from employee identity so death, resignation, migration or demolition can vacate a job without deleting its worker.
- Use one behavior owner per job. Integrate provider work schedules with MCA sleeping, home, interaction and movement behavior; don't continuously overwrite each other's navigation or home memory.
- Opening an MCA conversation, interacting with family, or issuing a supported player follow command should not make the worker appear missing and trigger a replacement.
- A missing employee in an unloaded area remains assigned/unavailable until positive evidence establishes a change.
- Where vanilla worker methods require `Villager`, use an audited entity adapter. Do not downcast MCA types speculatively.

### 9.5 Population accounting and recruitment

Separate total resident records, active bodies, working adults, jobless adults, children, deployed soldiers, and virtual pending population. Counting only currently loaded bodies causes duplication.

- Growth and births have exactly one owner per person. Reconcile MCA birth/death/migration events with native population changes so the same birth is not counted twice.
- Existing children cannot be consumed as anonymous manpower or drafted into native soldiers.
- A named resident, spouse, family member, office holder, active quest contact, or player-controlled companion is protected from automatic replacement/consumption.
- For an ordinary eligible adult selected for native recruitment, either preserve the same persistent identity through a verified conversion or recruit from an unmaterialized generic manpower slot. Do not silently discard the resident and generate an unrelated soldier.
- Require deliberate authorization for any player-facing recruitment that changes an established resident's role.
- Public totals may remain numeric, but every materialized resident must be accounted for without virtual duplicates.
- Provider surplus trimming and `/rvcleanup` must not delete legitimate imported MCA residents merely because a model count or body map is stale. Reconcile first; quarantine ambiguous records and report them for repair.
- A provider birth/growth cap prevents creating new excess slots; it is not permission to delete existing people to hit the cap.

### 9.6 Soldiers and temporary orders

Preserve the provider roster unit ID across body changes and conquest transfers. Add stable person linkage for soldiers with persistent character data. The inspected inventory-and-hunger snapshot alone is not enough for this.

Audit recruitment, hiring, native command groups, leadership promotion, garrison placement, stand-down, mounting, death, and body recreation. Preserve equipment and important state across each transition.

Integrate Kingdoms' mobilization leases and bilateral recruit transfers:

- Living Villages controls its autonomous army; it must not immediately overwrite a valid, authorized temporary player lease.
- A leased unit must be excluded from simultaneous AI redeployment, or the native order must explicitly revoke the lease through a recorded policy.
- Do not permit ordinary players to muster or transfer provider-owned troops merely because the units are nearby.
- Restore prior orders only if current identity and applied orders still match the lease. Preserve manual player changes and native emergency orders according to explicit precedence.
- Conquest transfers a unit between village rosters once; it must not duplicate it into the winner while retaining the losing live entity or former roster entry.
- If optional MCA Mob Compatibility supplies appearance/genetics for recruit entities, preserve its data through its actual integration. Do not make that companion mandatory or implement a second competing conversion system.

### 9.7 Unload, absence, death, and cleanup

Treat these as different events:

| Event | Required interpretation |
| --- | --- |
| Chunk/entity section not loaded | Person exists; no replacement body, vacancy or death consequence. |
| Resident follows a player out of town | Person may be away; reconcile residence/work rules, do not respawn a copy at home. |
| Dimension travel or teleport | Migration/availability change; preserve identity and reservations. |
| Native stand-down of a verified virtualizable unit | Persisted lifecycle transition, not death. |
| Confirmed living death or verified abstract unit casualty | Exactly one death/casualty record and appropriate owner consequences. |
| Conversion, resurrection, curing or provider replacement | Rebind verified continuity; do not invent a new unrelated person. |
| Unknown disappearance | Mark unresolved and investigate; neither duplicate nor declare dead. |

Audit every destructive path: `capture`, `discard`, `dematerialize`, `absorb`, `consumeJobless`, `trimSurplusVillagers`, worker promotion, cleanup, garrison reset and faction removal. Protect only integrated records, with explicit tests for untouched native behavior outside that scope.

## 10. Buildings, land, growth, and economy

### 10.1 Construction ownership

Living Villages remains responsible for its own layouts, structures, roads, mines, upgrades, workstations and development costs. The bridge publishes recognized building/claim changes to Kingdoms' footprint and institution systems.

A provider building is not automatically a politically recognized institution. Establish the appropriate building evidence and apply normal recognition rules. Likewise, an existing Kingdoms institution is not automatically permission to reconstruct its building.

For new Living Villages villages, allow ordinary native construction inside validated provider-owned footprints. For adopted or overlapping settlements:

- Check player claims, established structures, block entities/inventories, active institutional buildings, protected sites, and another settlement's footprint before founding or expansion.
- Validate before choosing a site and again before applying construction writes. A preflight only at initial import does not protect later upgrades or roads.
- Track provider-built footprints and expected changes so normal upgrades can proceed while player additions receive appropriate protection.
- Refuse an unsafe site with a specific reason and let the provider choose another; do not mark the building as constructed after skipping its placement.
- Keep mine depth, road grading, foundations and decorative clearing inside the same protection policy.
- Never register every claimed chunk as a residential building or replace hand-built villages with provider templates.

If Townstead is present, use its verified building evidence where available. Designate one construction and job owner for each building/population; do not enroll the same residents in two independent growth simulators.

### 10.2 Native economy remains canonical

Read currency from Living Villages' actual configuration. Although methods such as `getEmeralds()` retain that name, the configured currency can be something else; UI must use the configured item's display name and icon.

Cover treasury, food, materials, production, taxes, wages, training, construction, upkeep, native hiring, capital transfers, conquest spoils, and player war bonuses:

- Display current native amounts and rates without duplicating accrual.
- Observe native transfer/payment receipts; do not infer a payment from a displayed balance difference.
- Keep simulated treasury wealth distinct from physical emeralds/items in personal inventory or world chests.
- Never pay a second reward because a quest and conquest observation both saw the same native action.
- For relief supplies, use a real, bounded transaction: validate need and capacity, reserve/deduct player items through the owner, credit the provider once, confirm, then issue any quest receipt. Handle partial capacity and restart safely.
- Do not freely mint physical products for every background production step. Use the provider's existing materialization/claim rules.
- Conquest transfers native resources through the provider. Kingdoms records the result and does not transfer the same percentage again.
- Preserve native catch-up behavior for genuinely new provider villages. Imported/adopted established villages must not receive a second founding grant or full historical catch-up payout.

### 10.3 Development feedback

Expose native reasons for stalled growth—insufficient funds, beds, workers, food, usable land, materials or war disruption—through Kingdoms' ordinary menus. Where useful, offer an actual permitted action such as supplying food or opening the village terminal.

Do not offer an action that bypasses native costs or promises construction the adapter cannot complete. Keep diagnostics actionable: the user should see what is missing and where to address it.

## 11. MCA companion integration

Keep each companion optional and probe its actual supported contracts. Use existing Kingdoms bridges where they fit; add small scoped contracts where evidence is missing. Do not use reflection to mutate arbitrary private companion storage.

| Companion | Required integrated behavior | Guardrail |
| --- | --- | --- |
| MCA Reborn | Persistent residents, residence/origin evidence, family continuity, safe work and soldier lifecycle | Conquest and unloading do not rewrite families, personal names or affection. |
| MCA: Quests | Real relief, defense, escort, reconnaissance, mediation and recovery opportunities tied to specific conflicts/settlements | Native objectives consume items and issue rewards once; abstract battles do not fabricate player participation. |
| MCA: Reputation | Attribute verified service, aggression, aid and other supported deeds to the actual player and place | Separate personal standing, village standing, realm relations and civic kingdom reputation. |
| MCA: Crime | Accurate local control context, civilian protection, lawful combat attribution and continued custody/case state | War does not make civilian murder, private theft, kidnapping or every guard attack legal. |
| MCA: Conversations | Current village/realm/controller, war, peace, shortages and available civic tasks | Only use facts visible to the speaker/player; avoid claims of events not yet confirmed. |
| Townstead | Consistent footprint/building evidence, institutions and resident availability where supported | One population/work owner; no assumed unsupported archive API. |
| Map Atlases via MCA: Quests | Supported discovered settlement and conflict context without duplicate markers | Preserve manual pins and the configured information visibility policy. |

### 11.1 Quest and service design

Use a small set of meaningful situations instead of generating a task for every simulation tick:

- Deliver a native food/material shortage to a specific eligible village.
- Escort or defend a verified convoy/deployment where the underlying owner supports it.
- Reconnoitre a known conflict site with real presence evidence.
- Provide civilian relief after a confirmed occupation.
- Mediate a specific ceasefire or fulfill a negotiated aid obligation.

Freeze settlement, conflict, provider epoch, beneficiary and giver identity when the quest is accepted. Conquest may change eligibility or create an explicit recovery route; it must not silently redirect payment or delete already consumed inputs. A giver unloading pauses availability rather than killing the quest contact. Avoid global quest spam.

### 11.2 Combat and civilian law

Extend `MilitaryTargetPolicy` to use the same effective relation, coalition and combat authorization as the bridge. Cover direct attacks, arrows, summoned/projectile ownership and mounts where supported.

A faction tag alone must never classify a civilian as a combatant. Combatant status comes from a verified active roster/deployment and lawful role. Keep safe conduct and existing Crime enforcement checks.

For abstract casualties, publish only genuine provider-confirmed unit losses. Do not create a player murder case, kill-stat credit, corpse, loot drop, or personal revenge event without the evidence that system requires.

### 11.3 Existing third-party integrations

Preserve Living Villages' Epic Knights, Musket Mod and Corpse behavior. Test their actual gear, ammunition, banners, corpse looting, and cleanup through live/abstract transitions. A bridge snapshot must not normalize away custom items, enchantments, tags or extra inventory slots.

Do not require any of these extras for the base integration. Expose capability-specific degradation if an optional feature cannot be supported by the tested tuple.

## 12. Persistence, transactions, and recovery

### 12.1 Durable bridge records

Add versioned, server-owned saved data using the repository's existing persistence discipline. Keep logically separate records for:

| Record | Minimum durable content |
| --- | --- |
| Provider binding | World/provider provenance, village UUID, settlement UUID, dimension, claim references, aliases, reconciliation revision and status |
| Realm | Stable realm UUID, native faction aliases, current capital/member villages, civic associations, leadership mode, governance association and historical status |
| Resident | Person/unit/slot/body IDs, lifecycle state, residency/employment, protected-state references and supported snapshot metadata |
| Conflict | Stable conflict UUID, typed actor, sides/coalitions, target, policy revision, phase, commitments, remaining durations and native runtime association |
| Operation journal | Request/source ID, intent, expected before-state, target after-state, provider acknowledgment, effects already published and recovery result |
| Diplomacy constraint | Parties, actual scope, native directions, accepted terms, expiry/truce and effective/breached/suspended status |

Keep indexes for provider village→settlement, claim→binding, native faction→realm, person/body→resident, and unit→active deployment. Cap histories, queues and packets without deleting still-needed recovery evidence. Archive terminal records only after their replay protection can be safely retained in compact form.

### 12.2 Operation protocol

For consequential cross-mod mutations:

1. Validate current authority, bindings, supported capabilities, provider state and expected revisions.
2. Allocate or reuse a server-authorized operation ID; reserve affected entities, units or balances.
3. Persist the bridge intent before applying the provider mutation.
4. Invoke the authoritative owner's operation once.
5. Verify its actual in-memory result and acknowledged persistent state through a proven save boundary.
6. Persist completion and an outbox of downstream effects.
7. Publish history, notifications, quest/standing receipts and screen deltas idempotently.

Forge `SavedData.setDirty()` schedules data for saving; it is not proof that the change is already durable. [F1] Audit actual save boundaries rather than treating a setter call, dirty flag, returned boolean, or event as a committed multi-file transaction.

There is no automatic atomic transaction across Kingdoms, Living Villages, Recruits and entity chunk storage. Use recoverable intent and before/after evidence, not a claim of universal exactly-once writes. If the provider has no transaction ID, attach a bridge token at an audited operation boundary or retain a sufficient fingerprint and operation-specific recovery rule.

On restart:

- Rebuild indexes and resolve aliases before enabling new writes.
- Compare pending intent with actual provider/entity saved state.
- If the operation definitely did not apply, resume it when still authorized.
- If it definitely applied, acknowledge without paying, spawning or transferring again.
- If evidence conflicts or is insufficient, suspend only the affected operation and expose an explicit repair task. Do not blindly replay it or restore an old snapshot over a newer legitimate state.

### 12.3 Conflicts and failure isolation

- Failed relation writes must not leave a treaty falsely active.
- A saved roster change without a saved body removal must not create two living copies.
- A body saved without its new bridge linkage must be adoptable by provenance, not killed as surplus.
- A partial conquest must not generate a second garrison or resource transfer on retry.
- Missing provider classes, future schema versions, and unknown registry entries preserve records without destructive rewriting.
- Temporarily unavailable chunks or a not-yet-ready provider manager are not deletion evidence.
- Restoring one provider from an older backup must be detected as reconciliation, not interpreted as a new round of victories.
- Close all server-scoped state at shutdown. Audit provider static war lists, delayed effects and pending recall/garrison offers for cross-world leakage, and add targeted cleanup where needed.

### 12.4 Performance and networking

Use bounded change queues and incremental reconciliation. Do not scan all living entities, all settlements, all diplomacy pairs, or every claim every tick for the bridge.

Recommended starting budgets, configurable and subject to measurement:

- Process up to 8 changed village records per 20 ticks.
- Reconcile a bounded slice of known bindings every 200 ticks.
- Coalesce repeated UI changes per settlement/realm and send only to authorized relevant viewers.
- Keep expensive resident matching limited to affected loaded entity sections and indexed identity records.
- Do not load or generate chunks for background bridge reconciliation. Provider materialization itself follows its native behavior and explicit protection policy.

Do not recompute Living Villages' already expensive diplomacy graph inside the bridge. Consume its proposal/change boundaries and cache immutable snapshots.

All client requests must be revalidated server-side for authority, distance/presence where applicable, knowledge, selected record revisions, supported provider state and operation limits. Clients cannot nominate an AI actor, forge a provider receipt, write treasury balances, or choose a new controller directly.

## 13. Player interface, documentation, and diagnostics

### 13.1 Extend the current interfaces

Keep Kingdoms' manuscript design and ordinary action flow. Add contextual entries to Kingdom Tasks, the Village Ledger, government/realm pages, and War Room. Reuse actual names and searchable selectors; routine play must not require copied UUIDs.

A settlement page should show:

- Civic kingdom and settlement name.
- Current controlling realm and recognized sovereign, when different.
- Native capital/subordinate status.
- Population and available workforce, treasury currency, food, and important development needs, subject to visibility.
- Garrison/deployment information the viewer is entitled to know.
- Current conflict, peace, occupation or recovery status, in plain language.
- A working action to open the native village screen or the appropriate Kingdoms task.

Useful labels include **Open village**, **View its realm**, **Join the army**, **War and peace**, **Provide supplies**, and **Why is growth paused?** Show unavailable actions with specific requirements instead of buttons that fail without explanation.

### 13.2 Cross-navigation and duplicate prompts

- Offer a Kingdoms entry point from the Living Villages terminal where a safe client hook exists; otherwise supply clear native-screen navigation from Kingdoms.
- Keep one primary notification for a given war declaration/conquest when both mods would announce it. Store per-player delivery cursors and preference settings.
- Preserve native troop/group selection for player deployments. Do not replace it with an indiscriminate commitment of every owned recruit.
- Display the actual territory and political scope before a war, treaty, adoption, transfer or rename action is confirmed.
- Record whether a result is completed, waiting on native confirmation, refused, or needs recovery. Avoid success messages for queued mutations.
- Replace `TaskMeta` and other scattered provider checks with a shared capability snapshot so the guide, buttons and server authorization agree.

### 13.3 Maps and discovery

Keep provider-native military maps and Kingdoms civic discovery consistent. Use one marker identity per settlement per layer; allow distinct civic and military overlays without duplicate towns.

Choose and document the effective visibility policy. If Kingdoms promises known-settlement visibility, enforce it in every integrated marker/relations packet, including any Living Villages broadcast path; hiding only the Kingdoms screen does not prevent the native map from revealing the world. Preserve explicitly public/operator views and do not claim secrecy while another enabled native view intentionally publishes the information.

Do not expose private political negotiations, individual ballots, family facts or hidden Crime evidence in a public war tooltip.

### 13.4 Guide and localization

Update the in-game Book of Kingdoms with illustrated/diagrammed explanations where they help:

- Civic kingdom versus realm versus military control.
- Why same-culture settlements can still be rivals.
- How autonomous wars begin and what server settings affect them.
- How players join, command, defend, negotiate or provide relief.
- Why occupation does not erase a village's identity.
- Existing-world import and deliberate management of ordinary villages.
- Persistent residents and the meaning of an unavailable/unloaded person.
- Compatibility versions, stalled-growth help and recovery procedures.

Keep command details in advanced/operator sections. Localize new player-facing text into the repository's current languages, including English, Spanish and Simplified Chinese. Preserve keyboard navigation, narration, compact layouts, scrolling and readable status labels.

### 13.5 Operator tools

Add focused integration diagnostics, for example under a proposed `/kingdoms livingvillages` root:

- `status`: exact tuple, capability readiness, effective policies and queue counts.
- `inspect <settlement>`: village/realm/claim bindings, revisions, source provenance, current blockers.
- `reconcile [scope]`: bounded preview and repair of known records; no unbounded world scan.
- `migration preview/apply`: ambiguous or destructive adoption/migration decisions with a reviewable plan.
- `conflicts`: pending operations and unresolved identity/control disagreements.
- `recover <operation>`: operation-specific recovery after fresh preflight.

These are proposed commands, not existing syntax. Provide equivalent named GUI tasks. Normal users receive concise gameplay explanations; raw IDs, fingerprints and adapter details belong in operator diagnostics/support exports.

## 14. Configuration and defaults

Add a documented integration configuration using repository conventions. The names below are proposed. Do not claim they are existing keys or silently rewrite provider configuration files.

| Proposed setting | Initial default | Purpose |
| --- | --- | --- |
| `enabled` | `true` when the supported tuple is present | Activate verified integration; remain optional. |
| `autoBindNativeVillages` | `true` | Import unambiguous Living Villages settlement records. |
| `autoCreateRealmRecords` | `true` | Represent genuine native AI/player factions without making fake villages. |
| `preserveExistingNames` | `true` | Respect established/locked Kingdoms names and aliases. |
| `adoptOrdinaryVillagesAutomatically` | `false` | Require deliberate management adoption for non-provider villages. |
| `allowAiVsAiWars` | `true` | Retain autonomous native conflict where effective warfare rules permit. |
| `playerTargetPolicy` | `PROVIDER_AND_KINGDOMS` | Preserve native exclusions plus applicable player protections. |
| `aiRealmCapitalPolicy` | `ALLOW_UNLESS_PROTECTED` | Avoid making all AI capitals invulnerable. |
| `respectExistingCapitalProtection` | `true` | Honor legacy Kingdoms capital protections. |
| `aiTreatyDecisions` | `true` for supported terms | Let verified AI realms decide proposals without fake players. |
| `nativeRelationArbitration` | `true` | Enforce one effective relation for bound realms. |
| `minimumTruceTicks` | `24000` | Starting gameplay value: one Minecraft day; tune with playtesting. |
| `persistentResidents` | `true` | Preserve established resident entities and identity. |
| `automaticRecruitmentOfProtectedResidents` | `false` | Prevent involuntary loss of important characters. |
| `constructionInAdoptedVillages` | `false` until reviewed | Protect established builds during adoption. |
| `protectExternalBuildings` | `true` | Respect player and other-mod construction. |
| `companionContext` | `true` where supported | Feed verified context to installed MCA companions. |
| `deduplicateNotifications` | `true` | Avoid repeated native/Kingdoms announcements. |
| `changedRecordsPerBatch` | `8` | Bound event reconciliation. |
| `batchIntervalTicks` | `20` | Process changed records about once per second. |
| `reconciliationIntervalTicks` | `200` | Check a bounded slice for missed changes. |

Publish effective policy, including native `aiWarEnabled`, `aiWarRequiresEnemy`, relations settings, currency and siege settings. Distinguish server-wide defaults from per-world migration state and per-settlement exceptions.

Changing settings must not erase active wars, populations, treaties or bindings. Disabling a feature should block new relevant operations and settle/suspend existing operations through the documented lifecycle. Readiness and protection flags that are necessary to preserve already integrated people cannot be switched off by a casual client preference.

Do not copy Living Villages' entire balance configuration into Kingdoms. The native config remains the place to tune native growth, tax rates, army sizes, buildings, equipment and combat power.

## 15. Implementation sequence

Implement in reviewable stages, with working behavior and focused tests at each stage. Stage boundaries organize delivery; they do not reduce the final scope.

### Stage 0 — Reproduce and lock the baseline

- Read repository instructions and check current HEAD against the inspected commit.
- Obtain the official dependency JARs and record versions/hashes. Keep them out of the published Kingdoms artifact.
- Launch a minimal packaged dedicated server and client with Kingdoms + Recruits, then with Living Villages added, then with MCA added.
- Reproduce the relevant baseline interactions on disposable worlds: AI war, manual binding, off-screen war, worker promotion, civilian departure/re-entry and save/restart.
- Build a method/event map for the exact provider classes listed in section 17. Record where state actually becomes durable.
- Add representative integration fixtures before changing the lifecycle behavior.

**Exit evidence:** reproducible baseline, actual symbol/signature map, tested dependency tuple, identified existing failures rather than assumed symptoms.

### Stage 1 — Provider boundary and identities

Implement capability detection, lifecycle cleanup, immutable snapshots, settlement/realm bindings, typed political scope, persistence migration, and operator diagnostics. Import real villages and virtual faction records correctly. Keep destructive operations inactive until resident and war hooks are ready.

**Exit evidence:** one civic record per real village after repeated import and restart; no fake settlement for a virtual capital; no culture-based faction merging; provider absence works.

### Stage 2 — Resident and worker preservation

Implement the persistent-person model and guard every relevant removal/replacement path. Add audited MCA spawn/conversion, employment and in-place promotion support. Integrate births, recruitment eligibility, unloaded resident tracking and cleanup. Preserve full soldier state where required.

**Exit evidence:** named residents and family relationships survive repeated load/unload, employment, promotion, recruitment where authorized, and restart with no extra bodies or stale job holders.

### Stage 3 — Shared actors, policy, and wars

Add provider actors, campaign provenance, coalition membership, preflight, live/abstract hooks, deployment exclusivity, and transition handling. Retain legacy player authorization and scoped capital/offline protection.

**Exit evidence:** a native AI war starts and finishes through either execution path without fake players, global guard bypasses, duplicate armies or invalid player attacks.

### Stage 4 — Diplomacy and political decisions

Implement relation arbitration, typed treaty parties, AI proposal evaluation, enforceable peace/withdrawal, truce, and feedback suppression. Integrate native command/menu entry points.

**Exit evidence:** a ratified peace remains effective through subsequent AI relation ticks, saves, reloads and rejected/replayed requests; an unrelated conflict remains unaffected.

### Stage 5 — Conquest and recovery

Implement live/abstract conquest receipts, current versus recognized control, capital/realm succession, claim successor handling, history publication, cross-store journal recovery, and shutdown cleanup.

**Exit evidence:** conquest updates one settlement correctly and once; partial saves and process termination do not duplicate spoils/garrisons or reset personal state.

### Stage 6 — Economy, institutions, and companions

Add native economic context, receipted relief, protected construction, institution evidence, authorized mobilization/transfers and the supported MCA companion hooks.

**Exit evidence:** one payment owner, accurate legal/quest context, no duplicate rewards, no destructive reconstruction of adopted buildings.

### Stage 7 — Player experience and adoption

Complete interfaces, capability-driven task availability, cross-navigation, notifications, map policy, localization, guide content, operator previews and existing-village adoption.

**Exit evidence:** a player can discover a village, understand its ruler and war, participate, and inspect the outcome through normal menus; an operator can diagnose and resolve a specific blocked binding.

### Stage 8 — Full validation and release preparation

Run the matrix below using the final packaged JAR, then update README, compatibility tables, configuration reference, changelog, guide and release notes. Choose the project's next appropriate version; do not silently reuse 0.2.0 or assume a version number assigned elsewhere is available.

**Exit evidence:** supported tuples and all release gates are backed by actual results, with known limits accurately disclosed.

## 16. Validation matrix and acceptance criteria

### 16.1 Required environments

| Environment | Purpose |
| --- | --- |
| Kingdoms alone | Optional integration must not introduce a hard dependency or load provider classes. |
| Kingdoms + Recruits 1.15.2 | Existing player campaigns, control, mobilization and transfers still work. |
| Recruits 1.15.2 + Living Villages 1.1.0 | Native reference behavior for simulation, diplomacy, economy and war. |
| Kingdoms + Recruits 1.15.2 + Living Villages 1.1.0 | Complete core integration. |
| Core tuple + documented MCA/Architectury pair | Persistent person and worker integration. |
| Core/MCA tuple + individual companion mods, then the combined suite | Identify capability-specific issues and cross-mod feedback. |
| Core tuple + Epic Knights, Musket Mod, Corpse where available | Preserve native optional equipment and loot behavior. |
| Missing, unsupported and deliberately mismatched provider versions | Accurate readiness, clean startup/failure behavior and preservation of existing data. |

Use a dedicated server with at least two real clients for permissions, notification, concurrency and interface tests. Also test integrated single-player, pause/resume, and opening world B after closing world A in the same JVM.

### 16.2 Required scenarios

| Test | Setup/action | Required result |
| --- | --- | --- |
| T01 | Import an existing native village repeatedly; restart | One stable Kingdoms settlement and one provider binding; no rewards from import. |
| T02 | Visit the same village through structure, POI and MCA discovery | All evidence resolves to the same settlement; no duplicate arrival titles. |
| T03 | Create a player faction before placing its ledger/establishing a village | A realm exists without a phantom settlement at a default coordinate. |
| T04 | Two independent native factions share one civic kingdom | Separate sovereign realms and armies; shared culture grants no authority. |
| T05 | Grow a claim near another settlement | Accurate footprint and claims; no accidental civic merge or stale permissions. |
| T06 | Import a named/locked existing Kingdoms village | Preserve its UUID, name, civic assignment, discovery and institutions. |
| T07 | Native AI starts and wins a visible war | One authorized campaign, real combat, confirmed occupation and one history result. |
| T08 | Native AI starts and resolves a completely abstract war | Same policy and consequences as the corresponding live path; no dependence on live siege events. |
| T09 | Approach and leave a battle repeatedly | One conflict identity; no repeated declaration, troop duplication, rerolled result or refreshed rewards. |
| T10 | An abstract-to-live start is rejected or fails mid-transition | Recoverable conflict remains; units and commitments are not lost or duplicated. |
| T11 | Ally and subordinate villages join both sides | Valid distinct coalition membership, exclusive unit commitments and correct retreat/return. |
| T12 | Attempt AI attack on a protected capital/offline player-controlled target | Correct scope-specific refusal; other eligible AI conflicts still proceed. |
| T13 | Attack an ordinary AI capital with no player authority requirement | Allowed when native rules permit; zero native leader UUID does not require a fake player. |
| T14 | Cancel native start, tick or success from another listener | No canceled-event conquest, hidden siege, leaked commitment or false success history. |
| T15 | Negotiate peace during live combat, then repeat during abstract combat | Actual conflict progression stops as agreed; troops return once; treaty remains effective. |
| T16 | Run many AI diplomacy evaluations after accepting a treaty | No write loop or silent treaty reversal; accepted scope is respected. |
| T17 | Submit duplicate/stale declaration, treaty, supply or transfer packets | Server rejects or acknowledges idempotently; no second effect. |
| T18 | Player joins an AI army, then tries ruler-only actions | Membership works; unrelated command/governance authority is denied. |
| T19 | Capture a village from a realm with the same civic kingdom | Controller and occupation are correctly distinguished from culture. |
| T20 | Capture a realm capital while its other villages survive | Stable surviving realm, correct new capital, preserved history; no mass culture rewrite. |
| T21 | Disband a player faction with occupied/subordinate villages | Follow actual provider independence outcome; retain settlement histories and reconcile claims. |
| T22 | Claim is replaced, deleted, or moved during a campaign | Suspend/rebind only with verified succession evidence; no permission inherited by an unrelated claim. |
| T23 | Name/marry/befriend an MCA resident; leave and return repeatedly | Same persistent person and data; exactly one body when loaded. |
| T24 | Hire and promote a worker through every supported job/rank | No destructive replacement; profession, trades, schedules and relationships remain valid. |
| T25 | MCA converts/cancels a provider spawn during entity joining | Bind to the surviving real entity or retain pending state; no orphan slot or duplicate spawn. |
| T26 | Resident follows a player away, changes dimension, or becomes temporarily unavailable | No automatic replacement or fabricated death; employment/residence reconcile correctly. |
| T27 | Simulate births, growth, death and recruitment | Correct counts; children/protected people are not discarded as anonymous manpower. |
| T28 | Run surplus cleanup on an integrated village with imported residents | Legitimate residents survive; ambiguous duplicate identities are reported, not blindly deleted. |
| T29 | Dematerialize/rematerialize or move a soldier with custom gear and state | Unit/person continuity, inventory, health and supported attributes survive; no template reset. |
| T30 | Overlap an AI deployment, Kingdoms lease and recruit transfer | Exactly one active controller/commitment; restore respects later valid orders. |
| T31 | Supply a nearly full treasury/store; retry after disconnect/restart | Correct partial acceptance or refusal; items and provider credit occur once. |
| T32 | Conquer with native player bonuses and optional quest/reputation hooks | No duplicate spoils, personal rewards, kill credit or quest completion. |
| T33 | Attack civilian/ally/neutral companion during war; test projectile attribution | Target and damage policy agrees with actual combat status and Crime authority. |
| T34 | Adopt an ordinary existing village with family homes and player storage | No flattening, duplicate starters, stolen inventories or automatic population replacement. |
| T35 | Later house upgrade, road grading or mine expansion crosses protected construction | Refuse unsafe placement without charging/marking phantom completion; native safe growth continues. |
| T36 | Unknown distant village exists on server | UI/map visibility matches the documented policy across both mods' packets. |
| T37 | Inspect interfaces at small GUI scale, keyboard-only and narrator use | Readable labels, accessible controls, correct scrolling and real task destinations. |
| T38 | Remove an optional companion or install an unsupported provider tuple | No linkage crash from optional code; unsupported mutations blocked and records retained. |
| T39 | Restore only one provider's older backup | Detect divergent state; no automatic replay of already applied rewards or creation of extra bodies. |
| T40 | Stop/restart mid-war; then open another world in the same process | Recover/cancel through recorded policy; no timers, wars or pending offers leak across worlds. |

### 16.3 Crash-injection checkpoints

Kill the test process at each important boundary, then reload the same copied world:

- After bridge intent is durable but before the provider operation.
- After native declaration/relations change but before bridge acknowledgment.
- After casualty or roster mutation but before conflict progress acknowledgment.
- After entity save/snapshot but before old-body removal, and after removal but before new linkage acknowledgment.
- After conquest changes the native owner but before provider realm/resource changes are fully acknowledged.
- After completion is durable but before downstream notices/rewards are acknowledged.

Pass criteria are state conservation and correct recovery: no duplicate person, unit, item or payout; no invented winner; no silently dropped active obligation. Where recovery is genuinely ambiguous, preserve both evidence and the affected records, halt that operation, and produce a usable repair path.

### 16.4 Test levels and commands

Use focused pure tests for identity remapping, actor/permission policy, treaty precedence, state migration, event deduplication, coalition membership and operation replay. Use GameTests and real packaged runtimes for actual lifecycle/event behavior.

Run existing required repository checks, starting from the documented commands:

~~~bash
./gradlew build
python3 tools/test/run_gametests.py
~~~

Use a fresh isolated GameTest world as the repository requires. Extend the existing production/integration harnesses (`tools/test/integration_runtime.py`, `production_runtime.py`, and related fixtures) for new scenarios; inspect their current arguments instead of inventing command flags.

Capture server/client logs, exact mod hashes, scenario outcomes and relevant UI screenshots for release evidence. All claims of completed runtime validation must come from the final packaged build. A unit test over a mocked adapter does not prove the real JAR's mixin descriptor or entity conversion order works.

### 16.5 Performance validation

Compare the native reference tuple and the integrated tuple on the same seed/configuration with representative nearby villages, distant villages, active wars and many saved bindings.

Record median and high-percentile MSPT, bridge work per tick, queue delay, entity counts, loaded chunks, save duration and packet volume. Include repeated approach/leave cycles and a large saved-data-only fixture. Prove that bridge reconciliation does not force-load chunks or produce growing duplicate populations.

Choose numerical budgets from these measurements and record the test hardware. Treat bridge work exceeding its configured record budget, steadily growing queues/memory, or disproportionate whole-world scans as failures even if a small world appears responsive.

## 17. Native hook map and source references

### 17.1 Living Villages 1.1.0 symbols to audit

All names below were observed in the official JAR; they are implementation details, not a promised public API. Confirm bytecode signatures and overloads in the agent's exact downloaded build.

| Area | Observed symbols | Integration purpose |
| --- | --- | --- |
| Saved data and identity | `data.LivingVillagesSavedData`, `data.FactionData`, `data.RecruitUnit`, `data.StructureSlot` | Village/realm membership, roster identity, body/worker links and provider save boundaries. |
| Founding and native faction setup | `faction.VillageFounder.found`, `faction.RecruitsFactionBridge.ensureFaction`, `transferClaimOwnership`, `growClaimForDevelopment` | Safe import/adoption, actual native claims and controller changes. |
| Player faction lifecycle | `player.PlayerFactionEvents.onFactionCreated`, `onFactionDisbanded`, `PlayerFactionService.join` | Virtual capital handling, disbanding and correct membership authority. |
| Native simulation | `sim.SimulationTicker.onServerTick`, `OfflineSimulation.advance`, `NaturalGeneration.applyCatchupProgress` | Preserve simulation ownership and distinguish native catch-up from migration. |
| Diplomacy | `sim.Relations.modifiers`, `score`, `tick`, `apply`, `set` | Observe opinion proposals and arbitrate actual relation changes. |
| War starts and transitions | `war.WarManager.declareWar`, `considerAiWars`, `startAbstractSiege`, `tickAbstractSieges`, `settleDematerializedSiege`, `resolveAbstract` | Cover all execution paths and maintain one conflict. |
| Native siege listeners | `war.WarEvents.onSiegeStart`, `onSiegeTick`, `onSiegeSuccess`, `onSiegeEnd`, `onServerAboutToStart` | Event ordering, cancellation, native setting interactions and live policy. |
| Conquest and realm changes | `WarManager.occupy`, `occupyAbstract`, `joinFaction`, `reassignCapitalIfNeeded`, `endWar` | Confirmed outcomes, capitals, membership and effects. |
| Materialization | `materialize.MaterializationManager`, `CivilianMaterializer`, `WorkerMaterializer`, `RecruitMaterializer`, `MaterializeEvents` | Preserve people, workers, units and data across activity transitions. |
| Destructive resident paths | `capture`, `discard`, `dematerialize`, `absorb`, `consumeJobless`, `trimSurplusVillagers`, `promoteIfNeeded`, `cleanup` | Prevent identity loss and false duplicate cleanup. |
| Army commitment | `raiseContingent`, `musterAttackers`, `sendReinforcements`, `recallForcesHome`, `acceptGarrison` | Exclusive deployment and correct home/garrison ownership. |
| UI and requests | `network.RequestWarMessage`, `DefendWarMessage`, `HireFromScreenMessage`, `RequestVillageDataMessage`, `VillageScreenData`, `client.VillageScreen` | Cross-navigation, validation and consistent presentation. |
| Maps | `network.VillageMarkersMessage`, `MarkerEvents`, `client.MapMarkers` | Marker identity and effective visibility. |
| Economy and buildings | `sim.Currency`, `Stores`, `Taxes`, `Population`, `Jobs`, `materialize.StructurePlacer` | Native prices/counts, transactions and safe footprints. |

Keep hooks narrow and version-gated. Prefer recording events at the authoritative mutation boundary over polling for effects after the fact. Do not ship copied decompiled implementations in the addon.

### 17.2 Primary references

**Kingdoms source, pinned to the inspected commit:**

- [K1 — README and compatibility baseline][K1]
- [K2 — CampaignService: player authority and siege policy][K2]
- [K3 — CampaignState: campaigns, signatures, receipts and history][K3]
- [K4 — RecruitsEvents: native event integration][K4]; also inspect the sibling `mixin/RecruitsDetectionMixin.java`, `RecruitsSiegeRegistrationMixin.java`, and `RecruitsSiegeSuccessMixin.java`.
- [K5 — ControlState: faction mapping and control classification][K5]
- [K6 — R4 operations and explicit drama participation][K6]
- [K7 — ExternalRefIndex: settlement-provider identity uniqueness][K7]
- [K8 — KingdomsService: registration, redirects, external references and extension contracts][K8]
- [K9 — Configuration reference][K9]

**Provider releases and inspected binary:**

- [L1 — Living Villages official project page][L1]
- [L2 — Official Modrinth metadata for Living Villages 1.1.0][L2]
- [L3 — Official Living Villages 1.1.0 JAR inspected for this specification][L3]
- [L4 — Living Villages 1.1.0 release notes][L4]
- [R1 — Recruits 1.15.2 release][R1]
- [R2 — Official metadata for the required Recruits version][R2]
- [F1 — Forge 1.20.1 Saved Data documentation][F1]

Static inspection established the risks and suggested hook locations. No combined Minecraft runtime was launched while preparing this document. The implementing agent must produce the runtime evidence required above; it must not present this research as proof that the new integration already works.

## 18. Final coding-agent deliverables

Deliver all of the following:

1. Complete optional integration in the Kingdoms repository, including migrations and supported-version checks.
2. Persistent settlement, realm, resident, campaign, diplomacy and operation records with recovery behavior.
3. Working live and abstract autonomous warfare, authoritative conquest and practical treaty enforcement.
4. Resident/worker identity preservation and tested optional MCA/companion behavior.
5. Completed player interfaces, guide content, localization, configuration and operator diagnostics.
6. Meaningful automated and real-runtime tests with exact mod hashes and reproducible scenario results.
7. Updated compatibility documentation and release notes describing what works, how existing worlds migrate, and any remaining unsupported combinations.
8. A reviewer summary identifying the implemented changes, validation performed, migration effects and material limits.

**Definition of done:** The supported combination behaves as one coherent settlement-and-politics experience: autonomous native villages can develop and fight; Kingdoms consistently represents their identities, governments, control and consequences; persistent people and player assets survive; saved state recovers safely; and ordinary players can understand and use the integration through the existing interfaces.

Do not mark this complete while core behavior still depends on fake players, global permission bypasses, destructive resident replacement, manual mapping of every native village, unverified success events, duplicate rewards, or untested provider hooks.

[K1]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/README.md
[K2]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/warfare/CampaignService.java
[K3]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/warfare/CampaignState.java
[K4]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/compat/recruits/RecruitsEvents.java
[K5]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/warfare/ControlState.java
[K6]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/R4-Evolving-World.md
[K7]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/core/ExternalRefIndex.java
[K8]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/api/KingdomsService.java
[K9]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/Configuration.md
[L1]: https://modrinth.com/mod/recruits-living-villages
[L2]: https://api.modrinth.com/v2/version/DhD8obdv
[L3]: https://cdn.modrinth.com/data/TLtnScVf/versions/DhD8obdv/recruitsvillages-1.1.0.jar
[L4]: https://modrinth.com/mod/recruits-living-villages/version/DhD8obdv
[R1]: https://modrinth.com/mod/villager-recruits/version/2zXpVxK4
[R2]: https://api.modrinth.com/v2/version/2zXpVxK4
[F1]: https://docs.minecraftforge.net/en/1.20.1/datastorage/saveddata/
