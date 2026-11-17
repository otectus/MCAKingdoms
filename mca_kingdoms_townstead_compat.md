# MCA: Kingdoms × Townstead 0.8.0

## Source review and full compatibility implementation handoff

**Prepared:** November 1, 2026  
**Primary target:** Minecraft 1.20.1, Forge 47, Java 17  
**Deliverable:** Implementation instructions; no repository changes or runtime certification were performed during this review.

Implement a coherent partnership in which MCA: Kingdoms supplies persistent civic identity and its existing government, guild, institution, development and diplomatic systems, while Townstead supplies the village's people, buildings, work, needs, culture, calendar and social life. Make the connection visible and useful to players. Preserve existing worlds and let each system's owner validate its own actions.

The work divides into two deliverables:

1. **Kingdoms compatibility with the released Townstead 0.8.0:** adopt its public v1 API, improve evidence and presentation, add bounded event subscriptions, and retain older Townstead/MCA-only fallbacks.
2. **Reciprocal integration needed for full compatibility:** add a supported political-provider contract in Townstead, connect Kingdoms to it, and complete the naming, heraldry and lifecycle safeguards described below. Some of these operations are not exposed by Townstead's released public API. Do not describe the first deliverable alone as complete political integration.

All new class names, configuration keys and schemas proposed below are designs to implement, unless identified as existing source. Do not assume a proposed API already exists.

**Reading guide:** Begin with the [dependency baseline](#1-pinned-research-baseline), [findings](#2-findings-that-should-drive-implementation) and [ownership model](#4-ownership-model). Use the [implementation sequence](#14-implementation-sequence-and-concrete-deliverables) as the work queue, the [upstream contract table](#13-upstream-contract-work-required-for-full-integration) to track reciprocal changes, and the [acceptance matrix](#15-verification-and-acceptance-matrix) as the release gate.

## 1. Pinned research baseline

| Project | Reviewed revision | What this establishes |
| --- | --- | --- |
| MCA: Kingdoms | `main` at `5602f2ca5c90ee06138408f55494a6629f048a9c`; `mod_version=0.3.0` | Current integration, public interfaces, gameplay consumers, configuration and recorded acceptance baseline. |
| Townstead | release tag `0.8.0`, commit `c0c1634c498c0cba192bd1391d1141880c8223fb` | Exact released source, rather than an assumed future version. The release was published October 31, 2026. |
| Townstead comparison | `0.7.6`, commit `01af409f2b67e1f0e35dbd32d8df7001264ef700` | The `api.v1` facade files are additions relative to this older tagged tree. |

Recheck the heads when beginning implementation and reconcile any intervening changes. The relevant baseline is the source and current maintained references, not older design documents that still call Townstead's entire API read-only. The legacy facade remains present, but 0.8.0 adds a much broader versioned API. [K1] [K2] [T1] [T2]

### 1.1 Resolve the actual dependency tuple before coding

Kingdoms currently accepts Townstead `[0.7.6,0.9)` but records validation against `0.7.6+1.20.1`, MCA `7.6.26+1.20.1` and Architectury `9.2.14`. Accepted metadata is not a tested compatibility claim.

Townstead's tagged Forge build declares:

- Minecraft **1.20.1** and **Java 17**.
- MCA production target **`7.7.1-beta.5+1.20.1`**, with **`1.20.1-SNAPSHOT`** as the development alternative.
- Runtime MCA namespace **`forge.net.conczin.mca`**, applied through Stonecutter replacements.
- In `META-INF/mods.toml`, an exact MCA union of the configured development and production versions, not a broad `[7.6,8)` range.
- A separate Forge build script and a public `apiJar` task containing `com.aetherianartificer.townstead.api.v1/**`.

The root Townstead `gradle.properties` defaults to 1.21.1/NeoForge. **Do not use those root defaults to select this integration's runtime or Java level.** The Forge project is `1.20.1-forge` in `settings.gradle.kts`. Inspect the actual distributed jar's metadata and hash before certifying it; the values above establish the tagged build contract, not the contents of an independently downloaded release binary. [K2] [K3] [T3]

Deliver separate acceptance tuples for the legacy Townstead stack and the new stack. Revalidate Kingdoms' direct MCA village, family, residency and building adapters on MCA 7.7.1-beta.5. A successful Townstead v1 bind does not prove those other adapters work.

## 2. Findings that should drive implementation

These are source findings and risks to test, not claims that a live modpack reproduced every failure.

| Priority | Finding | Required response |
| --- | --- | --- |
| P0 | Townstead 0.8.0 exposes `TownsteadApiV1.get()`, API generation **1**, revision **2**. Kingdoms' `TownsteadBinding` still probes `api.TownsteadAPI` and internal spirit/reaction classes. | Add a v1 backend and use it first. Preserve the older backend for supported 0.7.x installations. |
| P0 | Townstead now has factions, government offices, charter actions, seats, legitimacy and external-government handling. The reviewed civic-provider registry registers Capitals; the repository search found no `mcakingdoms` bridge. | Establish explicit ownership before enabling political integration. A `PoliticsApi` snapshot is not delegation. |
| P0 | `CivicProviders` is outside `api.v1`, returns internal charter DTOs, has a process-global registry and no unregister contract. External ownership is also persisted in `PoliticalSavedData`. | Implement a supported lifecycle and recovery contract in Townstead. Do not mistake an internal Java `public` class for the stable API. |
| P1 | Kingdoms enumerates buildings with MCA `Village.getBuildings()` and constructs types from `getType()`, while Townstead v1 uses `McaBuildings.all()` and effective types. | Prefer the provider's unified list, family/tier and completion state. Keep MCA-only enumeration as a deliberately limited fallback. |
| P1 | Kingdoms' `TownsteadBuildingView` lacks completion state; its fingerprint contains only binding ID, building ID, type and size. | Add completion and geometry evidence without breaking existing API consumers. Detect changes that preserve type and size. |
| P1 | Reconciliation currently polls settlements occupied by players every 200 ticks by default and drops fingerprints when a settlement leaves that set. | Use events for prompt invalidation, with fair bounded polling for initialization, missed events and legacy providers. |
| P1 | The legacy Kingdoms needs view has fatigue and no temperature, enabled flags, per-need scale, coverage or sample age. | Add richer views; never interpret unavailable data as a healthy village or an empty numeric snapshot as a crisis. |
| P1 | Townstead `homeOf` has a nearest-village fallback, including an implementation fallback for entities without a recorded home. | Preserve Kingdoms' existing positive-residence evidence rules. Do not grant residency, allegiance or authority from proximity. |
| P1 | Townstead's building/spirit deeds already file into MCA: Reputation, with reporter attribution. | Do not create a second producer for the same local deed or award it to a nearby player. |
| P1 | `WorkCompletedEvent` identifies the worker, verb, optional object, magnitude and applied career XP; it has no durable transaction ID, order ID, payer or delivered quantity. | Use it for cache invalidation and narrative only unless a stronger upstream receipt contract is added. |
| P1 | `ChroniclesApi.emit` increments a counter before the emitter returns an optional story ID; it requires a loaded actor. | Do not retry on an empty return as if nothing happened. Do not use emitted stories as payment or governance evidence. |
| P2 | The current Blueprint header deliberately skips `townstead_` pages. Townstead's public client API does not provide a general charter/Blueprint extension contract. | Add a proper integration surface or a verified narrow screen adapter; a header alone does not provide integrated civic navigation. |

Evidence: [K4]–[K8], [T2], [T4]–[T12], [T15]–[T18].

## 3. Townstead 0.8.0 API inventory

Resolve the public API through `com.aetherianartificer.townstead.api.v1.TownsteadApiV1.get()`. Cache the API generation, revision and mod version once per backend initialization. Probe actual capabilities as well as the revision; the default `politics()` and `settings()` implementations can return empty facades. [T2]

The reviewed implementation returns generation `1` and revision `2`; verify both in the actual runtime artifact. [T19]

| Public surface | Useful operations | Kingdoms integration |
| --- | --- | --- |
| `villages()` | `village`, `within`, `all`, `homeOf`, `buildings`, `buildingAt`, `spirit`, `needs`, spirit IDs/thresholds | Exact village mapping, institution evidence, public welfare overview, culture context and building diagnostics. |
| `villagers()` | loaded `snapshot`, cheaper `needs`, unload-safe `record`, registered need IDs | NPC eligibility and availability, resident evidence, last-known welfare. Read before using any mutation. |
| `professions()` | `track`, `progress`, `career`, profession and skill registries; `awardXp`, `learnSkill`, `forgetSkill` | Optional career requirements and carefully authored training rewards. Keep profession tier distinct from vanilla trade level. |
| `calendar()` | calendar snapshots and profile reads | Date labels, seasonal context and presentation of civic events. Do not silently replace existing gameplay clocks. |
| `social()` | root/gene definitions, context tags, reaction gates, `fireReaction`, expression cues | Replace internal reaction reflection; use Townstead life-stage definitions for confirmed NPC adulthood. |
| `events()` | `subscribe(Class<E>, Consumer<E>)`, closeable `Subscription` | Bounded invalidation after committed provider changes; lifecycle cleanup is mandatory. |
| `work()` | worksites, orders, storage role definitions, item/tag counts | Production and supply visibility. This is a read surface, not an order editor or inventory transaction API. |
| `chronicles()` | archive availability, counters, public village digest, paginated truth reads, namespaced `emit` | Civic history and village news. Belief reads are narrative only. |
| `hangouts()` / `schedules()` | hangout context; schedule snapshots/templates and supported schedule operations | Explain that a contact is resting, working or socializing. Do not take over their timetable. |
| `registries()` | cultures, founding profiles, professions, skills, roots, reactions, needs and other IDs | Validate datapacks and provide discoverable choices without hard-coded content assumptions. |
| `politics()` — revision 2 | factions, bonds, land holder, sovereign, founding profiles/results, governance, seats, `mayAct` | Read Townstead's political context and detect competing ownership. All these operations are read-only. |
| `settings()` — revision 2 | system IDs, enabled state, effective settings, root/culture access, content gates | Respect per-world Switchboard choices and refresh on `WorldSettingsChangedEvent`. |

### 3.1 Contract details that must survive conversion

- **Threading:** game-state reads run on the server thread. Chronicle archive futures complete on a reader thread; return through `server.execute` before accessing the world, a player, a cache or a packet sender. Cancel or discard stale results when the server session ends.
- **Reads can be empty:** public implementations often catch failures and return empty values. A successfully bound method does not make every empty collection proof of deletion. Preserve the distinction between no record, insufficient evidence, disabled simulation and a capability failure where the available evidence permits it.
- **Record accessors:** returned records may gain components. Read documented accessors rather than assuming a fixed record component count or reconstructing returned records. Probe the input constructors actually used for `VillageId` and `ReactionCause`; future additive constructor changes need compatible factories or a tested adapter update.
- **Dimensions:** `VillageId` is `(ResourceLocation dimension, int villageId)`. Kingdoms' serialized `McaCommunityRef` uses `dimension#id`; Townstead's `asString()` uses `dimension/id`. Convert fields, not these display strings.
- **Spatial meaning:** `within` uses Townstead's town range; `nearest` permits a nearest/merge-margin fallback. Neither is ownership authorization. `VillageSnapshot.min/max` bounds buildings, and `rangeMargin` describes the expanded town range; these are not automatically Kingdoms claims.
- **Settings:** unknown `systemEnabled` IDs return `true`. Check `systems()` and the capability first, and do not invent keys such as a nonexistent `needs` system. Hunger, thirst and temperature have individual settings and per-need enabled flags.
- **Needs:** v1 exposes energy, with higher values meaning more energy, whereas the older facade exposes fatigue. Carry `NeedLevel` ranges, bands, crisis and enabled flags. Do not assume a universal 0–20 scale.
- **Temperature:** heat and cold can both be harmful. The tagged `NeedLevel.shortfall()` only measures below-neutral values; `NeedsSnapshot.primaryNeed()` uses it without filtering disabled levels, and `inCrisis()` does not filter disabled levels either. Filter `enabled` first and use Townstead's thermal band/crisis signal. Do not use these convenience methods as a universal welfare score.
- **Aggregate temperature:** `ResidentRegister.summary()` derives `NeedBand` from mean scale fractions for every need, including temperature. That calculation is not a symmetric thermal-comfort metric. Show thermal crisis counts and body-temperature context rather than calling a hotter village more prosperous.
- **Reactions:** use `ReactionCause` with source `context`. `command`/`forced()` bypasses normal reaction gates and is inappropriate for ambient political events.
- **Registries:** treat unrecognized enum-like strings as unknown, not the first enum value. Reload data-driven mappings when the provider's definitions change.

These requirements come from the interfaces and their tagged implementations, not only Javadoc. [T4]–[T11]

## 4. Ownership model

### 4.1 Keep concepts separate

| Concept | Authoritative owner | Rule |
| --- | --- | --- |
| Persistent Kingdoms settlement UUID, aliases, discovery and civic kingdom | MCA: Kingdoms | Resolve Townstead/MCA villages to these records. Do not create a parallel settlement for each UI or building. |
| MCA village key and actual villager home | MCA, accessed through appropriate MCA/Townstead APIs | Dimension-qualified identity; proximity is not a home. |
| Needs, work, schedules, careers, roots, clothing, life stages and local spirit | Townstead | Kingdoms reads context and requests only explicitly authored supported actions. |
| Townstead culture and founding profile | Townstead | Culture is not the same as a Kingdoms kingdom, root, political faction or military realm. |
| Kingdoms crown, dynasty and succession | Existing Kingdoms sovereignty policy, including Capitals where mapped | Preserve current per-kingdom delegation and quarantine rules. |
| Local civic offices, institution charters and Kingdoms guild progression | MCA: Kingdoms for scopes assigned to it | Townstead may display their results; never appoint a second equivalent officeholder. |
| Unmapped Townstead factions, social associations and their internal bonds | Townstead | Do not delete or import them merely because Kingdoms is installed. |
| Local reputation deeds and incident resolution | MCA: Reputation, with Townstead retaining its deed producer | Do not mirror a local score into kingdom standing as a second additive award. |
| Criminal jurisdiction, charges and enforcement | MCA: Crime through its existing contracts | Neither a rumour nor a Townstead political bond creates an arrest permission. |
| Quest acceptance, inventory turn-ins and completion receipts | MCA: Quests, or the existing Kingdoms service owning a native task | A journal entry or work event never substitutes for a durable completion receipt. |
| Recruits/Living Villages combat, occupation and resident preservation | Existing military integration owners | No Townstead guard, faction or land-holder event independently starts a campaign or reallocates soldiers. |
| News | The originating owner for its facts; Townstead for its narrative presentation | Emit one narrative projection of an owner's committed event, with no feedback into rewards. |

### 4.2 Government ownership must be resolved by scope

Implement one server-owned resolver returning at least:

`owner`, `scope`, `state`, `evidence`, `revision`, `reason`, `allowedActions`.

Suggested states: `NATIVE`, `EXTERNAL_ACTIVE`, `EXTERNAL_UNAVAILABLE`, `CONFLICT`, `UNMAPPED`, `DISABLED`. Keep this separate from Townstead's existing faction `status` strings.

Rules:

1. Preserve existing Capitals mappings first. Kingdoms already delegates crown and related warfare matters per kingdom while retaining civic offices, guilds, agreements and development. Townstead must receive the same answer, not select a provider solely by insertion order.
2. Resolve Kingdoms local government only for an exactly mapped settlement and an enabled, writable relevant subsystem. Installation of Kingdoms alone does not mean every Townstead faction is governed by it.
3. Let Townstead govern genuinely unmapped native factions. A local Townstead guild is not automatically a Kingdoms guild chapter.
4. A previously recorded external government stays unavailable when its provider disappears or fails. Do not silently create a native council, reactivate old officers or grant permissions.
5. Existing populated political records on both sides produce a conflict report. Do not automatically choose a sovereign, combine officeholders or erase either history. Provide an operator adoption/release workflow inside the implemented mod, with a concrete preview of affected records.
6. Map only like-for-like scopes. Capitals owning a kingdom crown does not imply that all Kingdoms civic offices or Townstead daily-life systems become disabled.
7. Expose the resolver's explanation in both mods' civic UI and Server health. The player should see who governs this action and where to perform it.

### 4.3 Required reciprocal Townstead work

The released `PoliticsApi` cannot register an external government or submit a charter transaction. Build a Townstead companion change that promotes the useful part of `CivicProviders` into a public, versioned contract. Prefer a source-side provider integration over reflective writes into `PoliticalSavedData` or charter internals. [T12] [T13]

The proposed contract must support:

- Server-scoped registration with a stable provider ID and closeable lifecycle handle.
- Read-only ownership decisions that do not create factions or write ownership while painting a screen.
- Explicit recorded bindings, separated from transient provider availability.
- Per-domain ownership: crown, local government, organization, heraldry/naming as appropriate. A single `ownsGovernment` boolean is insufficient for the Capitals/Kingdoms split.
- A neutral civic snapshot containing presentation data and permitted action IDs, with no internal charter packet types in the public API.
- An action request containing actor, exact settlement/faction scope, expected revision and request ID. The provider revalidates permissions; a client-supplied action list is never authority.
- An explicit conflict/adoption/release workflow with durable outcomes. Removing a mod or changing a toggle is not a release transaction.
- An unavailable snapshot that preserves provenance and blocks actions without pretending the government ceased to exist.
- Change notifications and deterministic provider selection. Registering a second claimant must surface a conflict, not overwrite the first provider.

Audit all Townstead political mutation paths, not just the charter screen: founding profiles, automatic office filling, charter edits, seats, rename actions, legitimacy, succession and native political commands. In the tagged source, `FactionUpkeep.run` reaches office filling without an external-government guard; `FactionBonds.form` also does not make the provider ownership check at its entrance. Preserve permitted resident/social bookkeeping while preventing independent government maintenance in externally owned scopes. Add tests around these paths before treating provider registration as sufficient.

Also resolve these tagged inconsistencies:

- `PoliticalSavedData.externalGovernment` can persist an external flag while answering a query, and the stored flag is faction-wide. A faction spanning several settlements needs an explicit policy if only one settlement is externally governed.
- `PoliticsImpl.governance` builds native offices, head and legitimacy, then appends a provider ID. That is not an external ruler projection. Expose external authority data distinctly so consumers cannot display the native head as the external ruler.
- `VillageRenames.allowed` returns to MCA's rule for an external government. It does not delegate to Kingdoms' rename permission or update Kingdoms' name. Complete that transaction through the authoritative owner.
- Public provider availability and persisted ownership must stay consistent after removal, restart and release. Do not directly clear NBT markers from Kingdoms.

**Release boundary:** while this companion contract is unavailable, ship the public-data integration with an explicit “political delegation unavailable” state. An operator can deliberately run Townstead with its politics system disabled, but Kingdoms must not silently change that world setting or claim that this equals selective full integration. If an exact-0.8.0 internal bridge is offered as a temporary compatibility option, isolate and artifact-gate it, verify every mutation path above, and label it experimental; do not make it the default substitute for the reciprocal contract.

## 5. Backend architecture and lifecycle

Extend the current integration; do not replace the existing civic API or spread Townstead dependencies throughout the mod.

### 5.1 Proposed implementation layout

| Component | Responsibility |
| --- | --- |
| `TownsteadBackend` | Kingdoms-owned internal interface, returning Kingdoms DTOs and explicit availability results. |
| `TownsteadV1Backend` | Isolated consumer of the public v1 API; API generation/revision and member validation. |
| `TownsteadLegacyBackend` | Existing 0.7.x public facade, with separately controlled legacy internal fallbacks. |
| `McaBuildingBackend` | Existing MCA-only building evidence when Townstead is absent or explicitly not used. |
| `TownsteadCapabilities` | Independent binding, configuration and runtime health for each operation. |
| `TownsteadEventBridge` | Owns subscriptions, copies event data and marks bounded work queues dirty. |
| `TownsteadVillageContextService` | Exact community resolution, welfare/work snapshots, freshness and permission-filtered views. |
| `TownsteadPoliticalBridge` | Consumer of the reciprocal provider contract; authority and action routing. |
| `TownsteadChronicleBridge` | Projects selected committed Kingdoms events once, without reward feedback. |
| `TownsteadDiagnostics` | User-readable status and operator troubleshooting. |

Use a compile-only Townstead v1 API artifact with a lazily loaded isolated backend if practical. Kingdoms' public API, common entrypoint, config classes and packet DTOs must have no Townstead or Townstead-internal type references. The existing reflection architecture is also viable if exact return types and accessor conversions are tested. Resolve only the public `TownsteadApiV1` entrypoint; do not bind directly to `api.impl.v1`.

Do not package Townstead API classes or its implementation inside Kingdoms. Extend the existing API-jar isolation checks to catch leaks. Keep client-only classes out of dedicated-server loading paths. Test the final reobfuscated Forge artifact, not only development mappings.

### 5.2 Capability selection

1. At server attach, inspect installed provider metadata and integration config.
2. If v1 exists and `getApiVersion()==1`, bind each supported facade and its required accessors independently.
3. If no usable v1 backend exists, use the legacy facade for the older supported versions.
4. For v1, prefer public `villages().spirit` and `social().fireReaction` regardless of `allowInternalFallback`. That flag should control only legacy internals.
5. Keep MCA-only building evidence available as it is today. Its availability must not accidentally turn on Townstead social or political features.
6. Do not automatically switch from a failed v1 list to raw MCA data midway through an authoritative reconciliation and report success. Different backends can expose different building sets. Mark degraded evidence and reconcile conservatively.

Expand capabilities to distinguish village identity, building list, building point query, completed-building evidence, resident record, needs summary, career reads, work orders, storage counts, settings, events, reactions, Chronicle reads/emission and political delegation. Binding readiness and a world-disabled subsystem are separate states. A missing animation backend should explain silent reactions without disabling building reads.

### 5.3 Preserve published API compatibility

`KingdomsTownsteadApi`, `TownsteadService`, the existing view records and serialized `BoundCivicBuilding` are already public. Adding components to a Java record changes its canonical constructor and can break compiled callers. Prefer new richer view types and additive default interface methods, preserving existing constructors/accessors. If a deliberate API break is unavoidable, version it and migrate every known consumer explicitly.

Keep `BoundCivicBuilding`'s existing saved fields readable. Add freshness, completion and provider provenance alongside the existing binding; do not regenerate every binding UUID simply because the backend changed. The current binding UUID depends on dimension, settlement UUID, MCA village ID and building ID. Existing merge handling must remain valid.

### 5.4 Subscription and cache lifecycle

- Subscribe after Kingdoms services attach. Initialize snapshots once without treating existing state as a newly earned achievement.
- Store and close every Townstead `Subscription` during detach. The provider event registry is static, so skipping close can leak a previous integrated server/world into the next one.
- Consume either `events().subscribe` or the loader mirror, never both for the same event.
- Filter server-bearing events to the attached server. For entity-bearing events, verify `entity.getServer()`. Events without a server, such as `VillagerVillageChangedEvent`, require session-scoped subscription and verification through current records; do not infer a world solely from a UUID.
- Copy only required scalar fields/IDs into queued work. Do not retain entity references in long-lived queues or background tasks.
- Treat event handlers as invalidation hints. Coalesce per village/settlement and re-read committed provider state under a bounded tick budget.
- Maintain a server-session token and data revision. Drop asynchronous responses after disconnect, world change, provider detach or superseding requests.
- Clear caches, subscriptions and screen state on stop. Test world A → title screen → world B in the same JVM.

## 6. Identity, founding and migration

### 6.1 Exact mapping

Use `KingdomsService.getSettlementForMcaVillage(dimension, villageId)` for an incoming Townstead village key. Preserve the existing `mca` external reference and existing structure/POI canonicalization. Do not match by display name, root, faction name, village center alone, or Townstead's nearest-village query. [K9]

When a Townstead-founded village has no Kingdoms record yet, feed verified evidence through the established settlement detector/candidate path. Let Kingdoms perform its existing overlap and merge preflight rules. Do not call a second registration mechanism that bypasses structure identity, locked names, political conflicts or Living Villages' protected bindings.

New founding events can prompt discovery of a candidate, but must not grant every player discovery or citizenship. Preserve settlement/kingdom assignment locks and retain aliases when an approved rename occurs.

### 6.2 Residence is stronger evidence than location

Kingdoms' existing MCA evidence provider uses an actual home village and preserves origin. Retain that behavior. A Townstead `homeOf` or snapshot home field may be derived using the nearest fallback. For residence changes, verify actual MCA home evidence or a validated resident-register transition with appropriate provenance before submitting a `CivicIdentityHint`.

Do not:

- Assign a visitor's residence because they approached a charter bell.
- Change a player's residence because a Townstead ownership plaque or generic home lookup exists; use the explicitly authorized player-residence workflow.
- Reset birth/origin settlement when an NPC moves, becomes a vampire/werewolf, changes root, changes profession or is reborn.
- Infer a dynasty relation, inheritance right or allegiance from root/heritage proportions.

Townstead birth, death, marriage, home and life-stage events can invalidate Kingdoms' civic/dynasty views. MCA family records and Kingdoms' existing dynasty rules remain authoritative. `VillagerRecord` is useful for unloaded needs and profession observations but **does not contain a life stage or a family tree**. Unloaded eligibility therefore remains unknown unless a separate supported source establishes it.

### 6.3 Existing-world upgrade

Implement an idempotent, bounded reconciliation that:

1. Preserves existing settlement UUIDs, kingdom assignments, government histories, standing and institutional bindings.
2. Resolves exact MCA/Townstead links, following existing settlement aliases after a Kingdoms merge.
3. Records newly observed provider context as a baseline, with no historical rewards, promotions or Chronicle emission.
4. Reports conflicting Townstead and Kingdoms governments for review instead of silently importing officeholders.
5. Leaves missing/unloaded provider records pending. Missing Townstead is not destruction of a building, village, faction or person.
6. Keeps separate provenance for adopted Townstead political state and preexisting Kingdoms state.
7. Detects a restored provider backup through revisions/fingerprints where available. A mismatch produces recovery-required state, not fresh awards or automatic transfers.

Test a settlement merge involving multiple foreign village references. The current Townstead service reads one `mca` reference from `externalRefs`; do not assume that this enumerates every foreign identity retained elsewhere by merge logic. Add a canonical reverse lookup/index if necessary, preserve each building's original dimension-qualified village key, and reject ambiguous joins.

### 6.4 Naming and culture

Display three different labels when applicable: **settlement name**, **civic kingdom**, **Townstead culture**. A Townstead faction/realm name is a fourth political concept, not a replacement for the kingdom field.

Provide optional data-driven culture/founding-profile hints for newly discovered unassigned settlements. Explicit saved assignments and operator choices outrank hints. Do not rewrite existing culture on conquest or kingdom reassignment; do not generate a new root or wardrobe from a kingdom change.

For rename synchronization, implement a public Townstead/MCA rename proposal/commit route or a reciprocal bridge. Both directions must check authority, expected revision, name locks and aliases, then echo one committed result. Until that contract exists, show the Kingdoms civic name alongside Townstead's local name and report mismatch. Never run two asynchronous “keep names equal” loops.

## 7. Buildings, institutions and settlement development

### 7.1 Prefer Townstead's effective building evidence

Replace the v1 enumeration path with `villages().buildings(server, VillageId)` and point reads with `villages().buildingAt(level, pos)`. Preserve returned type, family, tier, size, center, bounds and `complete`.

This delegates external-building/room differences to Townstead. Its `McaBuildings` adapter explicitly handles MCA's split building maps and open-air geometry; the importance of that path depends on the installed MCA build. Do not claim that every 1.20.1 building currently uses the newer split. [T5] [T14]

For actual point membership, prefer Townstead's point query rather than treating the bounding box as an exact footprint. Pens, L-shaped enclosures and wharves can contain empty space inside their boxes. Bounds remain useful for display, broad-phase matching and change detection.

### 7.2 Completion and lifecycle

Add richer evidence to the Kingdoms building API:

- Identity: dimension, MCA village ID, building ID and canonical Kingdoms settlement.
- State: effective type, family, tier, completion, bounds/center and observation revision.
- Availability: current, stale, unloaded, unknown, provider unavailable or confirmed removed.

Require a completed valid building for a newly recognized institution or a completed construction task, unless the definition explicitly calls for an in-progress project. Preserve legacy behavior for provider versions that cannot expose completion, but label the weaker evidence and do not claim it was verified.

Update every consumer, including `GovernmentService`, `CivicService`, `DevelopmentService`, `PlotSurvey`, and building-bound quest/commission validation. If a college becomes incomplete or a workshop loses its functional type, suspend the relevant service with an explanation; retain its charter/history for recovery.

Extend the internal reconciliation fingerprint to include completion, family/tier and geometry. Do this additively if public fingerprint records are already used by addons. Sort snapshots deterministically so provider collection order does not create false change events.

### 7.3 Recovery policy

Keep the existing `WAIT`, `FAIL_WITH_REASON` and `REBIND_SAME_FAMILY` semantics. Prefer exact foreign identity first. An unreadable provider or unloaded scope must not trigger same-family reassignment.

The current recovery policy chooses the lowest building ID in the family. Preserve that documented behavior for legacy bindings, but offer a separate explicit replacement-selection policy for high-value civic institutions. Show the proposed replacement and verify settlement, complete state, required tier, ownership and use constraints. Do not silently move a seat or paid commission to another workshop because both share a family string.

Events `BuildingEstablished`, `BuildingUpgraded`, `BuildingRemoved` and `VillageSpiritChanged` should invalidate the mapped scope promptly. Retain slow reconciliation for geometry/completion changes that may not emit the expected event, chunk re-entry and resource reload. A transient empty list must not irreversibly destroy institutions.

### 7.4 Connect development to visible village life

Add a development panel showing relevant recognized buildings, missing completion evidence and current Townstead specialization/spirit. When a building satisfies a Kingdoms project, require the project's own delivery and completion rules as well; do not skip paid material requirements because the building exists.

Suggested first content: a functioning civic hall, a learning institution, a granary/supply facility and a hospitality site. Validate actual Townstead/MCA type IDs from the loaded registries/configured mappings. Use family and minimum-tier predicates where appropriate rather than naming only `library` or assuming all upgraded variants have identical IDs.

## 8. Welfare, spirit and daily life

### 8.1 A useful village overview

Add **Village life** to the settlement's existing Ledger/Tasks flow. Show:

- Residents, known samples, live samples and oldest sample age.
- Enabled needs and how many sampled residents are in crisis.
- Community spirit's primary/secondary identity, contributions and tier.
- Townstead culture, calendar date and relevant seasonal context.
- A small number of actionable explanations such as “Food is running low,” “Some residents are exhausted,” or “Thermal distress reported.”

Example: “18 residents · 14 known · 6 currently loaded · oldest observation 2 days ago.” If coverage is poor, say **insufficient current information**. If thirst is not simulated, say **not simulated**, rather than “everyone is hydrated.”

`VillageNeedsSummary` mixes live and last-known observations. It is not an offline simulation. Its tagged implementation also selects stored entries by recorded village association rather than directly iterating only the current MCA roll, and refreshes loaded entries while reading. Test resident moves/deaths and inconsistent counts. When necessary, expose raw counts with a warning and request a provider-side correction; do not turn an apparently capped `coverage()` result into proof that every current resident was measured. [T6]

### 8.2 Turn information into modest, understandable opportunities

Implement optional **civic aid suggestions** using existing task/commission systems:

| Signal | Suggested opportunity | Completion authority |
| --- | --- | --- |
| Sustained hunger problem with adequate fresh coverage | Deliver food for a documented relief contract or complete a food-storage project | Owning quest/development transaction and its receipt. |
| Thermal crisis with the temperature system enabled | Clothing/shelter assistance using supported Townstead content | Authored objective and verified turn-in/project result; not a raw temperature average. |
| Exhaustion or collapse | Explain rest/access needs, suggest improving hospitality or schedule arrangements | Townstead simulation; no forced schedule edits. |
| An established learning site and active careers | An apprenticeship or guild examination | Explicit career predicate plus existing commission terms. |
| A new community spirit tier | Show a civic milestone and relevant optional content | Townstead owns the spirit calculation; Reputation owns its existing local deed. |

Use hysteresis, minimum sample coverage, freshness limits and cooldowns so one fluctuating villager does not repeatedly create and cancel tasks. Initial observation is baseline, not a crisis transition. Do not impose political penalties, famine deaths, automatic rebellions or compulsory welfare taxes merely because this integration can read needs.

Keep these opportunities useful when only some Townstead systems are enabled. Never require an impossible thirst, temperature, career or spirit objective when its provider is absent, switched off or has no matching content.

### 8.3 Schedules and interactions

Read schedules/hangouts to explain why a civic contact is unavailable and, where existing UI supports it, when to return. Do not teleport or wake NPCs, interrupt unrelated hangouts, assign work, or hold a dialogue lock through an administrative screen. Leave dialogue opening/closing ownership with the dialogue integration actually running the conversation.

Preserve current adult eligibility through `social().root(...).lifeStages()` and `presentsAs`. Share one eligibility evaluator among political appointments, guild contacts and dynasty checks so they do not disagree about the same Townstead NPC. Distinguish an ordinary confirmed adult from unreadable life-stage evidence. Test roots with custom stage names, immortality and aging disabled.

## 9. Careers, guilds, production and supplies

### 9.1 Career-aware guild content

Add optional predicates through the existing progression/requirements layer:

- A specific canonical Townstead profession at or above a tier.
- A known learned skill.
- Any qualifying profession from an explicit tag/list, when the content really allows alternatives.

Resolve `track` before evaluating progression. A profession without a progression track does not gain tiers; it must produce a clear unsupported-requirement reason rather than a permanently impossible objective. Use canonical profession IDs and data reload validation. Do not assume Townstead career tier equals vanilla trading level or Kingdoms guild rank.

Prefer advancement through player action and existing guild/commission receipts. If a training reward awards Townstead XP, call `professions().awardXp` with a non-null `mcakingdoms:<source>`, respect the daily cap by default, and inspect the result's actual applied amount/status. Never write career NBT, automatically force-learn skills, or bypass world source restrictions with a null source.

A cross-mod XP award is not inherently atomic with a Kingdoms receipt. Start with read-only career prerequisites. Enable durable XP/skill rewards only with an idempotent provider operation keyed by the originating receipt, or a recovery design that explicitly handles an indeterminate result. A local “done” flag written before/after a provider call cannot guarantee exactly-once rewards across a crash.

### 9.2 Production view

Show worksites and their orders, including product, target, mode, scope, pause state, profession/rank restrictions and assigned villager where permitted. Explain “production requested” separately from “goods available” and “goods delivered.” `OrderSnapshot.index` is an ordering position, not a stable order identity. Never persist it as the identity of a contract.

Use `WorkCompletedEvent` to refresh relevant displays and celebrate occasional milestones. **Do not** pay a player, complete a supply commission or mint organization evidence directly from it: the event cannot identify a particular order or transaction and `magnitude`/`appliedXp` is not a certified item count.

### 9.3 Storage and real transactions

`work().countItem/countTag` can support advisory stock summaries. Validate that the `ServerLevel` dimension equals `VillageId.dimension()` before calling them: the tagged implementation passes the integer village ID into `VillageStores` with the supplied level, without comparing those two dimensions itself.

Counts do not reserve goods and a zero may be incomplete evidence. Do not run these potentially broad inventory scans on every tick, every rendered frame or separately for each player viewing the same settlement. Cache bounded selected metrics, invalidate when known relevant work changes, and refresh on demand.

For the first release, use existing player-delivery/institutional-commission transactions. Full automated procurement requires a **new** Townstead contract with stable order IDs, requester identity, validated authorization, inventory source, exact item/NBT requirements, reservation/commit/cancel and durable receipts. It must prevent the same goods being counted as stock, consumed by Townstead and simultaneously paid out by Kingdoms. Deliver that provider change before enabling automated requisitions, taxes from storage, or military supply withdrawals.

## 10. Chronicles, reactions and companion coordination

### 10.1 Reactions

Move the existing standing-improved, standing-worsened and settlement-allegiance reactions to `social().fireReaction`. Preserve the existing residency/kingdom checks and quiet-operation suppression. Keep the existing `data/mcakingdoms/townstead/reactions/` IDs unless deliberately migrating them.

Check registered reaction IDs, enabled settings and `reactionsPlayable()`. Respect cooldown/lock/chance gates. Bound candidates inspected as well as successful reactions: the current counters advance only after successful dispatch, so an unavailable backend should not cause an unbounded search through a large entity list. Use spatially limited candidate queries, batching and event coalescing; do not query an entire enormous settlement for every standing change.

### 10.2 Meaningful civic history

Project only committed, significant Kingdoms events: a charter enacted, an institution recognized/restored, a peace agreement concluded, a major development completed or an appropriate guild appointment. Keep routine numeric standing drift out of the public Chronicle by default.

Use namespaced external triggers such as proposed `mcakingdoms:institution_recognized` with templates under `data/mcakingdoms/chronicle_event/`. Define template roles, localized headlines, reach and category using Townstead's actual schema; `trigger.type` is `external` for the public emission API. A parameter called `settlement_id` does not change the emitter's actual village scope.

The existing API requires a loaded actor and derives real event context from that actor. Emit only when a legitimate actor and the intended location are available. For a remote treaty, offline appointment or settlement-wide event without an actor, keep Kingdoms' own history and wait for a proposed location/UUID-based external-event API. Do not spawn a fake NPC or select an unrelated nearby resident to anchor a story.

Important delivery semantics:

- Use the original Kingdoms event/receipt identity to track projection attempts.
- `emit` increments its actor counter before story emission and may return empty when no template succeeds. Empty is not a safe retry signal.
- Do not call `addCounter` for the same moment already passed to `emit`.
- Without provider-side idempotency, choose a bounded at-most-once best-effort narrative attempt and document that a crash can omit it. Do not promise exactly-once delivery. Mechanically important rewards never depend on this projection.
- A future strong emission API should accept a source event ID, stable participants, explicit location/village, and return recorded/duplicate/disabled/rejected statuses. It should apply counter and story effects idempotently in the provider.
- Ignore Kingdoms-origin stories on inbound narrative processing. Do not create a Chronicle → reputation → standing → Chronicle loop.

Show Townstead's **public village digest** in the Kingdoms history view with pagination. Filter discovery/access on the Kingdoms server before returning it. For archive futures, use a session token, timeouts/limits and a main-thread permission recheck. Keep rumours/beliefs labeled as such and out of guilt, voting eligibility, war justification, rewards and inheritance decisions.

### 10.3 Companion ownership table

| Existing path | Integration rule |
| --- | --- |
| Townstead → MCA: Reputation for building raised/upgraded/spirit tier | Leave Townstead as producer. Kingdoms can display the resulting context; no duplicate local incident. |
| MCA: Quests → Kingdoms commission/organization receipt consumers | Keep the established receipt path. Townstead journal display cannot complete or pay the quest again. |
| MCA: Conversations and Townstead social reactions | Pass consistent civic context through existing public seams; one integration owns a conversation lock and one owns each news projection. |
| MCA: Crime and Townstead safety/standing | Read through the responsible provider. Do not treat Townstead faction authority as criminal enforcement permission. |
| MCA Capitals → Kingdoms sovereignty and Townstead civic provider | Share one scoped authority resolution; preserve Kingdoms' existing read-only Capitals policy and do not route around it through Townstead. |
| Recruits: Living Villages → Kingdoms realm/control records | Keep occupation distinct from civic identity. A Townstead faction event does not become a second conquest result. |

Companion versions have been evolving separately. Verify their current APIs during implementation; this review inspected the two requested repositories and Townstead's embedded companion adapters, not every companion's current HEAD.

## 11. Heraldry, livery, culture and unified navigation

### 11.1 Heraldry without overwriting player work

Townstead 0.8.0 can supply faction color/emblem metadata through `FactionSnapshot`, but it has no public v1 heraldry/livery provider registration or mutation API. Its native `GuardHeraldry` already paints only plain shields and prefers local settlement/faction arms. Preserve that behavior. [T16]

Implement a reciprocal read-only heraldry provider for externally governed scopes:

1. Retain personally decorated equipment and explicit local proclaimed arms.
2. Retain Townstead faction/organization arms where that faction owns the relevant identity.
3. Use the recognized external government's approved design as a configurable fallback for standard-issue equipment and civic displays.
4. If no compatible design exists, display the Kingdoms crest in the civic UI without inventing or overwriting an item design.

Kingdoms' GUI heraldry texture is not automatically a Minecraft banner-pattern recipe or a Townstead emblem recipe. Provide an explicit datapack mapping for each built-in kingdom, validate supported components, and document the fallback. Local flags should remain recognizable after a change of political control. Applying livery must not create guild membership, military enlistment or government authority.

Cultures may inform decorative descriptions, preferred buildings and authored commission themes. Keep them independent from biology, morality, faction standing and hostility. Custom datapack cultures must work through registry enumeration and mappings; do not hard-code only the bundled cultures or kingdoms.

### 11.2 Extend existing screens

Use the existing manuscript design for Kingdoms pages. Add three focused views to the current settlement flow:

- **Village life:** needs coverage, culture, spirit, work and useful suggestions.
- **Government and services:** recognized owners, civic offices, institutions and safe navigation.
- **History:** Kingdoms official records and Townstead public village news, clearly identified by source.

Use Townstead's own visual design for any added Townstead charter/Blueprint section. Do not replace its catalog, tracking menu, order sheets or quest journal.

A reciprocal civic-provider UI should expose a compact Kingdoms section with settlement, kingdom, responsible authority and permitted links such as “View civic offices” or “Open Kingdom Tasks.” Prefer opening the existing authoritative workflow rather than recreating appointments, treaties and guild management in a second screen.

Until a supported extension point exists, an isolated compatibility adapter may add a small read-only header and navigation control after verifying the actual Forge 0.8.0 screen geometry. Test both the original MCA Blueprint pages and Townstead-added pages. The present `isTownsteadPage` suppression must not be removed blindly: the larger Townstead UI may occupy that space.

### 11.3 Player-facing availability and security

- Use plain explanations: “Townstead careers are disabled in this world,” “This building is incomplete,” “This government is managed by MCA: Kingdoms,” or “This resident's current status is unknown.”
- Keep API revisions, class names and stack traces in operator diagnostics, not normal village menus.
- Preserve keyboard navigation, narration, readable contrast, localization, GUI scaling and narrow-window layouts.
- Discovery permissions apply to the new overview and news pages. A raw provider API can expose more than the current player should see.
- Revalidate player identity, dimension, distance or permitted remote access, settlement discovery, ownership and expected revision on the server for every request/action.
- Bound packet strings, row counts, cursor sizes and request frequency. Separate request IDs and data revisions; discard late replies for another page or village.
- Do not broaden the existing nearby Blueprint header into an unrestricted server-wide village enumeration endpoint. Sensitive individual needs/career records should be available only through an intended authorized view; public summaries are the default.

## 12. Configuration, diagnostics and data packs

### 12.1 Proposed settings

Keep `mcakingdoms-townstead-common.toml` and current keys working. Add these only where their feature is implemented; names below are proposed, not existing config keys.

| Setting group | Recommended default | Behavior |
| --- | --- | --- |
| Public v1 reads and events | On when available | Use supported public capabilities; no changes to Townstead settings. |
| Legacy internal fallback | Preserve existing user choice | Only for old spirit/reaction surfaces; report when used. |
| Village-life and work overview | On | Permission-filtered reads. |
| Political ownership integration | On only when the reciprocal contract is verified | Otherwise report unavailable. Persisted ownership survives toggling off. |
| Existing political reactions | Preserve existing value | Normal Townstead gates and bounded dispatch. |
| Chronicle projection | On for a small allowlist of major committed events | Narrative only, no historical replay. |
| Welfare suggestions | On, conservative | No automatic penalties or mandatory tasks. |
| Career-aware content | On for validated definitions | No impossible objectives when careers/tracks are unavailable. |
| XP/skill reward mutations | Off until receipt/idempotency support is validated | Explicit authored rewards only. |
| Production-order writes / storage withdrawals | Unavailable until provider contract exists | Do not offer a functioning toggle for an absent API. |
| Heraldry fallback | Opt-in initially | Explicit local/personal designs retain precedence. |
| Reconciliation interval and queue limits | Preserve current 200-tick fallback initially; tune from measurements | Events provide prompt updates; polling remains bounded. |

Set initial budgets for engineering tests, then measure: at most the existing 32 active settlements per poll round, a separate small per-tick dirty queue budget, deduplicated village reads across viewers, and bounded paginated UI rows. Use fair queues so player-list ordering cannot indefinitely starve another settlement.

### 12.2 Operator diagnostics

Extend existing Server health and add proposed `/kingdoms compat townstead` subcommands consistent with the repository's command patterns:

- `status`: installed versions, API generation/revision, active backend, capabilities, subsystem settings, tested tuple and any degraded state.
- `village <settlement>`: dimension-qualified MCA key, provider village, Kingdoms UUID, aliases, culture, building/read freshness and ownership evidence.
- `buildings <settlement>`: binding, effective type/family/tier, completion, exact/legacy source and any recovery reason.
- `authority <settlement>`: ownership per domain, native/external conflicts, persisted binding and why each action is allowed/refused.
- `queues`: bounded queue size, coalesced/dropped work, sample age, subscription count and rate-limited errors.
- `reconcile <settlement>`: bounded refresh only; it must not award benefits, change government, reset records or replay history.

Separate `ABSENT`, `DISABLED`, `UNSUPPORTED`, `READY`, `DEGRADED`, `STALE` and `RECOVERY_REQUIRED`. If a call returns an empty result with no diagnostic distinction available from Townstead, say that evidence is unavailable or absent; do not claim to know which internal failure occurred.

### 12.3 Data-driven content

Add a small Kingdoms-owned mapping layer for:

- Institution/development requirements based on Townstead building family, minimum tier and completion.
- Profession/skill prerequisites and the canonical owning guild or commission.
- Optional culture/founding-profile hints for new settlement assignment.
- Optional village-life suggestions and their minimum coverage/freshness/cooldown.
- Heraldry fallback designs.
- Chronicle trigger definitions and political reaction mappings.

Validate loaded IDs, ranges, optional providers and dependencies on reload. Unknown IDs must produce useful diagnostics and disable only affected content. Missing optional Townstead must not prevent unrelated Kingdoms datapacks from loading. Bundle a small working example pack and reload tests; do not advertise every hypothetical combination as supported.

## 13. Upstream contract work required for full integration

Implement these as a separately reviewable Townstead change, with the Kingdoms consumer in its own change. Keep both implementations optional. Do not publish upstream issues, comments or PR messages without the user's instruction; the coding handoff can prepare patches locally.

| Contract/change | Required for | Minimum acceptance |
| --- | --- | --- |
| Public external civic-provider API | Full government/charter interoperability | Per-domain authority, closeable server lifecycle, no internal DTO leakage, explicit conflict and removal behavior. |
| Provider-aware native political mutations | Actual ownership enforcement | Founding/bootstrap/upkeep/charter/seat/rename/legitimacy/succession paths cannot maintain a rival government in externally owned scopes. |
| External government snapshot | Correct Townstead and third-party UI | External ruler/offices/availability are not mislabeled native values; provider removal keeps provenance. |
| Supported rename transaction | One consistent civic name across screens | Authority and locks checked, alias retained, source/request ID prevents echo; no arbitrary NBT mutation. |
| Heraldry/livery provider and neutral view | Kingdoms designs on native Townstead equipment/displays | Explicit precedence, no overwrite of local/personal designs, safe missing-design fallback. |
| Public UI extension/navigation seam | Integrated charter/Blueprint navigation | No global screen replacement or private-field dependency in the stable contract. |
| Idempotent external Chronicle event API | Reliable actorless/remote civic narratives | Source event ID, explicit scope/participants, status, counter/story dedupe and unavailable handling. |
| Stable order and inventory transaction API | Automated procurement and supply delivery | Stable IDs, authorization, reservations and exact committed delivery receipts. Read counts alone are insufficient. |
| Idempotent career reward operation | Crash-safe XP/skill rewards | Repeated originating receipt returns duplicate without another reward; status includes actual applied result. |
| Needs helper/aggregation corrections | Reliable welfare consumers | Disabled needs excluded; thermal distress interpreted symmetrically; live home changes and stale resident-roll membership tested. |

The first six make the civic partnership complete. The transaction extensions enable the deeper automation described in sections 9–10; those features must remain unavailable until their contracts exist. Read-only work visibility and best-effort, locally grounded Chronicle projections can ship earlier.

For the new political contract, add integration fixtures containing a native Townstead council, an adopted Kingdoms government, a Capitals-managed crown with Kingdoms civic offices, an unavailable provider, and a multi-settlement Townstead faction with mixed ownership. The last fixture must either be supported explicitly or refused as a conflict; a faction-wide boolean cannot silently disable unrelated settlements.

## 14. Implementation sequence and concrete deliverables

### Phase A — establish the baseline and close unsafe assumptions

1. Pin repositories and actual jars. Record versions and SHA-256 hashes in the existing acceptance manifest.
2. Run the unchanged Kingdoms baseline on its recorded legacy tuple. Establish the new MCA/Townstead tuple separately.
3. Add contract tests using the real 0.8.0 public API artifact and verify Forge relocation/class loading.
4. Inventory all current `KingdomsTownsteadApi` consumers, direct MCA reads and political ownership paths. Preserve existing public and save contracts.
5. Add explicit diagnostics for unavailable political delegation and stronger-vs-legacy building evidence.

**Deliverable:** a small compatibility baseline change with trustworthy version/capability reporting and fixtures, before gameplay expansion.

### Phase B — public API backend and evidence correctness

1. Implement the v1 backend and capability selection, preserving legacy/MCA-only modes.
2. Add conversions for `professionTier`, v1 energy/needs levels, public spirit, roots/life stages and village/building snapshots.
3. Replace v1 internal spirit/reaction reads with public calls.
4. Add closeable event subscriptions and bounded dirty queues.
5. Complete building completion/geometry evidence, institutional consumers and conservative recovery.
6. Test exact mapping, home evidence, merges, missing records and world-switch cleanup.

**Deliverable:** robust compatibility with Townstead 0.8.0's public data and social API; no claim of political delegation yet.

### Phase C — reciprocal political interoperability

1. Implement the Townstead provider contract and native mutation guards.
2. Implement Kingdoms' scoped authority resolver using the existing Capitals and military policies.
3. Add conflict/adoption/release workflows and persistent provenance, including provider-removal cases.
4. Connect Townstead civic presentation/actions to the authoritative Kingdoms workflow.
5. Add supported naming and heraldry projection.

**Deliverable:** a paired change that prevents dual governments and preserves existing records. Validate both mods' packaged jars together.

### Phase D — useful village-life integration

1. Add settlement welfare/spirit/culture and work overview pages.
2. Add a small set of validated career predicates and civic-aid suggestions.
3. Add public village history and bounded reaction/Chronicle projection.
4. Add optional heraldry designs and integrated navigation.
5. Add richer procurement or XP reward features only after their idempotent provider contracts pass acceptance.

**Deliverable:** a cohesive player experience with a short end-to-end loop: discover a village → inspect its needs/work → accept a legitimate civic commission → complete the owner's transaction → see one reward and an appropriate public record.

### Phase E — release evidence and documentation

Update `README.md`, `CHANGELOG.md`, `docs/Configuration.md`, `docs/Addon-API.md`, `docs/compatibility-certified.md`, `tools/test/companions.json`, Server health and the Book of Kingdoms. Add `docs/Townstead-0.8.0-Integration.md` with the ownership matrix, tested tuples, operator recovery, API limits and example datapack.

Keep archived architecture notes historical or clearly mark superseded statements. Do not replace an old tested version with “0.8.0 validated” until the new actual artifact passes the matrix below.

## 15. Verification and acceptance matrix

Use the repository's existing unit tests, GameTests and packaged acceptance harness. Run targeted tests while implementing, then a single complete required check set after the changes stabilize. Do not count source-shape tests or mocks as proof of real mod co-installation.

### 15.1 Unit and contract tests

| Area | Required cases |
| --- | --- |
| Backend loading | Townstead absent; legacy present; v1 generation 1/revision 2; unknown generation; missing facade/member; source-denied mutation; no animation backend. |
| API isolation | No Townstead types leaked through Kingdoms API jar/common bootstrap; no optional class loading when absent; dedicated-server client isolation. |
| Conversions | UUID/String/ResourceLocation conversion, optional unwrapping, `professionTier`, fatigue vs energy, disabled needs, custom scales, hot/cold distress, root life stages and unknown enum strings. |
| Building evidence | Incomplete/complete transitions, effective type changes, same size with changed geometry, tier upgrades, open-air containment, duplicate collection ordering, provider failure vs confirmed removal. |
| Identity | Same numeric village ID in two dimensions; nearest-but-not-home; matching names in different villages; aliases after merge; multiple retained foreign identities; unknown/negative IDs. |
| Event lifecycle | API and loader mirror do not duplicate work; subscriptions close; two worlds in one process; queued events after detach; async response after disconnect/revision change. |
| Authority | Per-domain resolution; existing native conflict; external unavailable; provider removal; explicit release; Capitals crown and Kingdoms local offices; mixed-ownership multi-settlement faction. |
| Transactions | Duplicate/mismatched receipts, indeterminate provider mutation, already-counted stock, no payment from `WorkCompletedEvent`, no retry/double counter on empty Chronicle emission. |
| Data packs | Missing optional provider, renamed/unknown profession/culture/skill, disabled track, reload invalidation and one invalid mapping among valid mappings. |

### 15.2 Packaged runtime matrix

| Scenario | Must demonstrate |
| --- | --- |
| Kingdoms alone | No optional class-load failure; original settlement/Book/Tasks behavior intact. |
| Kingdoms + MCA, no Townstead | Existing building-backed institutions and guild contacts still work through MCA evidence. |
| Kingdoms + legacy MCA + Townstead 0.7.6 | Recorded legacy features remain functional; new v1 features are clearly unavailable. |
| Kingdoms + Townstead 0.8.0 Forge + its declared MCA production dependency | Actual jar startup, API revision, relocated MCA reads, buildings, adulthood, reactions, needs, work and dedicated-server packets. |
| Integrated client and dedicated server | No client-only loading on server; GUI scaling, narration, late packets and server-side authorization. |
| Townstead world switches changed live | Careers/work/spirit/reactions/chronicles/politics off/on; disabled needs; state retained; UI and caches refresh. |
| Existing Kingdoms world upgraded to 0.8.0 stack | Same UUIDs, locks, names, standing and bindings; no retroactive awards or replacement governments. |
| Existing Townstead political world gains Kingdoms | Conflict preview/adoption; no silent crown or office replacement. |
| Townstead removed, then restored | No automatic destruction, default council takeover or reward replay; saved records become readable again. |
| MCA: Reputation, Quests, Conversations and Crime installed together | One deed, one reward, one intended news projection; independent authority and source attribution. |
| Capitals installed with the compatible MCA tuple | Existing sovereignty policy respected; civic offices still work; no native Townstead replacement ruler. |
| Recruits / Living Villages installed | Preserved residents and family identity; no second conquest, tax, worker scheduler or military command path. |

Townstead 0.8.0 with the old MCA 7.6.26 jar is not an expected positive test tuple under its tagged metadata. Record a clean loader incompatibility as such; do not weaken Townstead's MCA requirements to make the test appear successful.

### 15.3 End-to-end scenarios

1. **Building upgrade:** recognize a completed workshop, bind an institution, upgrade its tier, remove a required component, then repair it. Views update promptly; services suspend and recover; the charter/history remains; no repeated reward.
2. **Founding:** establish a Townstead village with its bell workflow. Exactly one Kingdoms settlement emerges through normal detection; no player is automatically made sovereign or a resident.
3. **Residence:** walk a visitor into town, move a resident's actual home, and merge settlement records. Only verified home changes affect residence; origin and family remain stable.
4. **Welfare:** load/unload residents, disable thirst, cause both heat and cold crises, and move a resident between villages. The display distinguishes live/stale/disabled and does not equate hotter with healthier.
5. **Career:** require a particular profession tier, test a custom profession with a track and one without a track, and reload definitions. Requirements explain exactly what counts and never silently broaden to any profession.
6. **Supply:** let villagers produce stock while a player delivers to a Kingdoms commission. Stock observations do not reserve or pay; the owner consumes/delivers exactly once.
7. **Government:** load existing conflicting offices, adopt one authority, restart, remove that provider, then restore it. No second government activates during the gap.
8. **Narrative:** complete one eligible civic event with a real actor. One projection is attempted; disabling/removing its template creates no retry storm or repeated counter; an inbound story cannot generate another reward.
9. **Heraldry:** give a guard a custom shield, change kingdom control and local arms, then issue a plain shield. Custom equipment remains intact and the configured fallback is applied only where appropriate.
10. **Recovery:** restore an older provider save and present a stale UI request. The bridge diagnoses conflict and refuses stale actions without overwriting records.

### 15.4 Performance and failure behavior

Profile a representative modpack with many known villages, multiple simultaneous viewers and a burst of building/work events. Measure integration time, number of provider calls, queue depth, average/p95 refresh delay and packet volume. Establish budgets from the actual server; avoid promising hardware-independent millisecond figures.

Required properties:

- No forced chunk loading for integration lookups.
- No world-wide resident, storage or entity scan in a per-player/per-tick UI path.
- Shared cached summaries across viewers, bounded fair queues and bounded historical pages.
- A failed optional capability leaves unrelated Kingdoms functionality usable.
- Recovery never turns missing evidence into a destructive mutation or authority grant.
- No subscriptions, stale screen responses or server references survive a world switch.

## 16. Definition of done

The full integration is complete when:

- Both the new and retained legacy dependency tuples have truthful validation records.
- Townstead 0.8.0 public APIs replace unnecessary internal reflection for supported operations.
- Exact dimension-qualified identity, complete building evidence and conservative unload behavior work in real packaged runs.
- The reciprocal provider contract prevents parallel government ownership across all relevant mutation paths.
- Existing Capitals, military, quest, crime and reputation responsibilities remain consistent.
- Players can inspect village life, find the correct civic workflow and understand unavailable features.
- Rewards and state-changing operations rely on their owner's validated transaction/receipt, not event guesses or stock counts.
- Migration, removal, restart, reload and rollback tests pass without erasing world history.
- New content is localized, data-driven, optional and documented.
- Release notes distinguish shipped capabilities, tested artifacts and any remaining upstream-dependent feature.

Do not make unrelated combat, supernatural balance, NPC reproduction, family migration or offline simulation changes as part of this compatibility task. The purpose is to connect the two existing designs reliably and make the resulting village experience richer.

## 17. Source map

All repository links below are pinned to the reviewed commits or release tag. The review used the GitHub plugin to read interfaces and implementations, inspected both repository trees, and compared the Townstead 0.7.6/0.8.0 API file inventory. No Minecraft client/server or Gradle build was run for this planning document.

### MCA: Kingdoms

| Reference | Source and relevance |
| --- | --- |
| [K1] | README: current features, optional providers and maintained-document precedence. |
| [K2] | Gradle properties: version, loader, accepted ranges and recorded provider version. |
| [K3] | Compatibility certification: actual prior artifact tuple and limits. |
| [K4] | `TownsteadBinding`: legacy public API and internal spirit/reaction probes, raw MCA building enumeration. |
| [K5] | `TownsteadServiceImpl`: conversions, empty defaults, binding recovery and community lookup. |
| [K6] | `TownsteadBuildingReconciler`: current polling, fingerprint comparison and active-set lifecycle. |
| [K7] | `TownsteadBuildingView`: existing public shape and deterministic binding ID. |
| [K8] | `TownsteadBlueprintHeader`: screen detection, layout and suppression on Townstead pages. |
| [K9] | `KingdomsService`: identity lookup, detector/evidence registrations and canonical mutation surface. |
| [K10] | `GovernmentService`: institutions, NPC eligibility and political operations. |
| [K11] | `CivicService`: guild contacts, building requirements and commission ownership. |
| [K12] | `LineageAdapter`: family evidence and unloaded life-stage uncertainty. |
| [K13] | Capitals interoperability: scoped crown ownership, persistence and read-only delegation. |
| [K14] | Living Villages integration: realm/control/identity distinctions and resident safeguards. |
| [K15] | `TownsteadRuntime`: server attach/detach and capability reporting. |
| [K16] | `TownsteadPoliticalReactions`: current event dispatch and bounds. |
| [K17] | `TownsteadCivicNetwork`: current server-side header authorization and request channel. |
| [K18] | `ProgressionBridge`: existing requirements integration seam. |
| [K19] | `TownsteadBuildingFingerprint`: currently lacks completion and geometry. |

### Townstead

| Reference | Source and relevance |
| --- | --- |
| [T1] | Tagged 0.8.0 release notes. |
| [T2] | `TownsteadApiV1`: public contract, facades and revision-added APIs. |
| [T3] | Forge build: MCA dependency target, relocation, Java 17 and `apiJar`. |
| [T4] | v1 facade directory: politics/settings/work/social/career/Chronicle interfaces. |
| [T5] | `VillagesImpl`: effective building types, unified enumeration, containment and village snapshots. |
| [T6] | `ResidentRegister`: last-known observations, summary construction and update behavior. |
| [T7] | v1 model directory: `NeedLevel`, `NeedsSnapshot`, `VillageNeedsSummary`, buildings, orders and governance. |
| [T8] | `NeedScales`: energy inversion, enabled flags and thermal values. |
| [T9] | `ApiEvents`: subscription registry, event dispatch and loader mirroring. |
| [T10] | `WorkImpl`: order snapshots, inventory-count implementation and dimension caveat. |
| [T11] | `ChroniclesImpl`: external emission, pre-emission counter increment, paging and asynchronous reads. |
| [T12] | `CivicProviders`: internal external-government extension and Capitals registration. |
| [T13] | `PoliticalSavedData`: persistent external-government marking. |
| [T14] | `McaBuildings`: rooms/external sites and provider-owned containment behavior. |
| [T15] | `ReputationDeeds`: existing building/spirit incident producer and reporter attribution. |
| [T16] | `GuardHeraldry`: local/faction arm precedence and plain-shield preservation. |
| [T17] | `WorkCompletedEvent`: exact event fields and missing transaction identity. |
| [T18] | `ApiSupport`: home/nearest fallback and general implementation helper behavior. |
| [T19] | `TownsteadApiV1Impl`: API generation 1, revision 2. |
| [T20] | `FactionUpkeep`: citizenship bookkeeping and automatic office filling. |
| [T21] | `PoliticsImpl`: native governance fields plus external-provider annotation. |
| [T22] | `VillageRenames`: existing external-government rename fallback. |
| [T23] | Forge `mods.toml`: exact supported MCA dependency alternatives. |
| [T24] | `Systems`: actual Switchboard system IDs and preservation intent. |
| [T25] | `FactionBonds`: native political bond creation path. |

[K1]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/README.md
[K2]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/gradle.properties
[K3]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/docs/compatibility-certified.md
[K4]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadBinding.java
[K5]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadServiceImpl.java
[K6]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadBuildingReconciler.java
[K7]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/api/townstead/TownsteadBuildingView.java
[K8]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/client/townstead/TownsteadBlueprintHeader.java
[K9]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/api/KingdomsService.java
[K10]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/politics/GovernmentService.java
[K11]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/civic/CivicService.java
[K12]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/politics/dynasty/LineageAdapter.java
[K13]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/docs/Capitals-Interoperability.md
[K14]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/docs/Living-Villages-Integration.md
[K15]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadRuntime.java
[K16]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadPoliticalReactions.java
[K17]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/townstead/network/TownsteadCivicNetwork.java
[K18]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/compat/progression/ProgressionBridge.java
[K19]: https://github.com/otectus/MCAKingdoms/blob/5602f2ca5c90ee06138408f55494a6629f048a9c/src/main/java/dev/otectus/mcakingdoms/api/townstead/TownsteadBuildingFingerprint.java
[T1]: https://github.com/AetherianArtificer/Townstead/releases/tag/0.8.0
[T2]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1/TownsteadApiV1.java
[T3]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/build.forge.gradle.kts
[T4]: https://github.com/AetherianArtificer/Townstead/tree/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1
[T5]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/VillagesImpl.java
[T6]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/village/ResidentRegister.java
[T7]: https://github.com/AetherianArtificer/Townstead/tree/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1/model
[T8]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/NeedScales.java
[T9]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/ApiEvents.java
[T10]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/WorkImpl.java
[T11]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/ChroniclesImpl.java
[T12]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/charter/CivicProviders.java
[T13]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/state/PoliticalSavedData.java
[T14]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/compat/mca/McaBuildings.java
[T15]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/compat/otectus/ReputationDeeds.java
[T16]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/heraldry/GuardHeraldry.java
[T17]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/v1/event/WorkCompletedEvent.java
[T18]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/ApiSupport.java
[T19]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/TownsteadApiV1Impl.java
[T20]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/state/FactionUpkeep.java
[T21]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/api/impl/v1/PoliticsImpl.java
[T22]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/charter/VillageRenames.java
[T23]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/resources/META-INF/mods.toml
[T24]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/switchboard/Systems.java
[T25]: https://github.com/AetherianArtificer/Townstead/blob/c0c1634c498c0cba192bd1391d1141880c8223fb/src/main/java/com/aetherianartificer/townstead/politics/state/FactionBonds.java
