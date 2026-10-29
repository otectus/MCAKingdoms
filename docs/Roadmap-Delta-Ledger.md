# Roadmap delta ledger

The older [factions integration roadmap](MCAKingdoms-Factions-Integration-Audit-and-Roadmap.md) predates the R1–R4
deliveries. This ledger classifies every one of its IDs against the source on `release/0.3.0` (after the 27 October
2026 audit handoff), as that handoff requires before any new faction or combat code. Paths are under
`src/main/java/dev/otectus/mcakingdoms/` unless they start with `src/`, `tools/`, `docs/` or `examples/`.

**Categories** (the handoff's): *implemented and verified* (a test in this repository exercises it), *implemented but
untested*, *partially implemented*, *blocked by provider* (needs an API another mod does not offer), *unimplemented*,
*no longer desirable*. Work that belongs in a sibling repository (MCA Quests, MCA Reputation, MCA: Crime, MCA
Conversations) is named as such; this release changes no sibling repository.

## Baseline and ownership

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| A01 | Freeze the integration baseline | Implemented and verified | `tools/test/companions.json` (jars by SHA-256, contracts, certified tuples), `tools/test/verify_companions.py`, `tools/test/environment.py`; the provider matrix is logged at every start (`integration/ProviderMatrix`). |
| A02 | Authoritative ownership | Implemented and verified | Owner column of the provider matrix; MCA Capitals crowns owned per kingdom (`compat/capitals/CapitalsInterop`, `politics/Sovereignty`); Recruits gated to exactly 1.15.2 (`interaction/TaskMeta.recruits`). GameTest `CapitalsInteropGameTests`, unit `GovernmentActionPolicyTest`. |
| A03 | Baseline pack health (JEI, lectern mixin conflict) | No longer desirable here | A modpack configuration matter, not this mod's source. Nothing in MCA: Kingdoms touches those mixins. |

## Factions, permissions and events

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| B01 | General faction identity and affiliation | Implemented and verified | `factions/organization/*`, `api/factions/organization/*`. Unit `OrganizationDefinitionTest` (including the example institution), `OrganizationSavedDataTest`. |
| B02 | Typed permissions and relation composition | Implemented and verified | Government responsibilities (`api/politics/Politics.Permission`, `politics/GovernmentActionPolicy`), organization rank permissions (`factions/organization/OrganizationPolicy`); troop command stays with Recruits. Unit `GovernmentActionPolicyTest`, `OrganizationPolicyTest`. |
| B03 | Semantic event delivery | Implemented and verified | `api/politics/PoliticalCommittedEvent` after each durable commit; `compat/reputation/ReputationOutboxBridge` (per-consumer journal cursor); `compat/quests/receipts/QuestReceiptData` (deduplicated receipts). Unit `ReputationOutboxBridgeTest`, `QuestReceiptDataTest`. |
| B04 | Domain health and explainability | Implemented and verified | `/kingdoms integrations` (with the provider matrix), Server health (`interaction/ServerFacts`), `/kingdoms guild explain` (`civic/CivicCommands`), `/kingdoms definitions diagnose`. Unit `TaskIntentTest`, `CapacityTest`. |

## Social and political identity

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| S01 | Political identity without replacing personhood | Implemented and verified | Civic identity (`api/CivicIdentityView`), office badges, organization membership snapshots; the dynasty references people and MCA family records without copying them (`politics/dynasty/LineageAdapter`). Unit `CivicIdentityCarryOverTest`; GameTests `PlayerIdentityGameTests`, `DynastyGameTests`. |
| S02 | Context-aware dialogue | Partially implemented | Kingdom gates for addon content (`integration/gating/*`) are what MCA Conversations reads. A richer faction-context query is MCA Conversations' change (sibling repository). |
| S03 | Recruiters, envoys, advocates, careers | Partially implemented | Offices (envoy, herald, chancellor, keeper of records, local steward) and ceremonial court ranks (`politics/dynasty/NobleRank`). NPC jobs and schedules stay MCA's and Townstead's; no recruiter or examiner behaviour is added. |
| S04 | Family influence, defection, divided loyalty | Unimplemented | Family records are read only for succession. Defection needs MCA Conversations and Reputation contracts. |

## Reputation, history and diplomacy

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| R01 | Evidence-weighted political propagation | Implemented and verified | Standing sync modes and journal (`factions/FactionServiceImpl`, `factions/config/FactionConfig`). Unit `FactionSavedDataTest`; packaged phase `migration`. |
| R02 | History, recovery and correction | Implemented and verified | `api/politics/PoliticalFact` with `Correction` and `Visibility`; history page with type, date and visibility filters (`politics/HistoryFilter`). Unit `PoliticalSavedDataTest`; GameTest `PoliticalPresentationGameTests`. |
| R03 | Treaty clauses with real, bounded effects | Implemented and verified | `Politics.Clause`, `api/politics/AgreementClauseView`. GameTest `PoliticalGameTests.hospitalityRequiresExplicitLiveFrozenTerms`. |
| R04 | Diplomacy arbitration with Recruits | Implemented and verified (contract and observation) | `compat/recruits/RecruitsMilitary` reads Recruits' diplomacy as authoritative. Packaged phases `r3`, `military`. Writes stay limited to consented, mapped factions. |

## Law and enforcement

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| L01 | Political consequences of attributable cases | Partially implemented | Institutional service legality through `compat/crime/InstitutionalCrimeBridge`. Kingdom-level consequences of committed Crime cases need a Crime-owned event contract; none is consumed. |
| L02 | Jurisdiction and treaty policy provider | Implemented and verified | `compat/crime/JurisdictionPolicyBridge` registers this mod as MCA: Crime's jurisdiction policy provider through Crime's own `JurisdictionPolicyApi` (shipped in Crime 0.7.5). Packaged phase `r3-civilian` (`R3CivilianScenario`). Without that API it logs the extension as unavailable and changes nothing. |
| L03 | Contraband, smuggling, inspections | Unimplemented | — |
| L04 | Justice, prisoners, negotiated settlement | Unimplemented | Crime-owned facilities; no contract exists. |

## Quests

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| Q01 | Faction-aware variants of existing content | Partially implemented | Kingdoms side: kingdom gates and completion receipts. The quest content lives in MCA Quests (sibling repository). |
| Q02 | Political objectives and completion receipts | Implemented and verified (framework) | `compat/quests/receipts/QuestCompletionBridge`, institutional commissions. Unit `QuestContractProbeTest`, `QuestReceiptDataTest`; packaged phase `r2`. |
| Q03 | Competing outcomes with an exit route | Partially implemented | World events resolve once among their authored outcomes (`DECLARE`, `NEGOTIATE`, `EXIT`, `RECOVER`, `FOUND_SCHISM`, `APPOINT_REGENT`; `evolution/drama/*`). Quest-side competing outcomes belong to MCA Quests. |
| Q04 | Board and project integration | Partially implemented | Public works with real deliveries (`development/*`, 0.3.0) cover project demand. Bountiful integration is MCA Quests'. |

## Economy

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| E01 | Transaction owner per channel | Implemented (decision recorded) | No wallet or currency provider is integrated, so development is funded with items only and no balance exists (`development/DevelopmentService`, [Configuration](Configuration.md)). |
| E02 | Privileges, tariffs, embargoes, neutral commerce | Partially implemented / blocked by provider | Access privileges through treaty clauses (`WORKSHOP_ACCESS`, `HOSPITALITY`). Tariffs, embargoes and dues need a tested economy provider; none is integrated, and periodic taxation is not approved. |
| E03 | Demand-driven public contracts | Implemented and verified | Development works in `PROJECTS` mode and above: capped demand, real deliveries, exactly-once receipts, refunds (`development/*`). Unit `DevelopmentLedgerTest`; GameTest `DevelopmentGameTests`. |
| E04 | Trade routes and strategic resources | Partially implemented | Capped, labelled abstract trade between settlements of kingdoms with an agreement in force (`FULL_ECONOMY`, `development/Development.trade`). Physical caravans and route waypoints are not implemented. |

## Magic, faith, race and lore

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| M01 | Institutions teach; progression providers govern | Partially implemented | `compat/progression/ProgressionBridge` reads Runic Skills levels for organization qualifications (`api/progression`). No test runs against a real Runic Skills jar in this release. |
| M02 | Magical traditions and infrastructure | Unimplemented | No verified APIs were in scope. |
| M03 | Worship distinct from religious membership | Unimplemented | Runic Gods is only listed by `/kingdoms integrations`. |
| M04 | Race, origin and culture without allegiance | Partially implemented | Townstead origins and life stages are read (`api/townstead/*`, `politics/dynasty/LineageAdapter`). No Runic Races adapter. |
| M05 | Relics, lore and certifications | Unimplemented | — |

## Exploration and places

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| X01 | Shared discovery and political intelligence | Implemented and verified | `knowledge/SettlementKnowledge` (viewer-scoped before paging), the Ledger and its places sketch (`client/PlacesSketch`, 0.3.0). Unit `SettlementKnowledgeTest`. |
| X02 | Existing structures as political places | Implemented and verified | World-context selectors over structure ids and tags (`worldcontext/*`). Unit `WorldContextDefinitionsReloadTest`; GameTest `WorldContextGameTests`. |
| X03 | Visible institutions and borders | Partially implemented | Kingdom banners, heraldic shields and court noticeboards bound to their kingdom (`heraldry/*`, 0.3.0). GameTest `DocumentGameTests`. No border markers. |
| X04 | Hostile factions, bosses, exploration progression | Partially implemented | Authored world-context encounters (`src/main/resources/data/mcakingdoms/warfare/world/`, `worldcontext/*`). Dynamic occupation is not implemented. |
| X05 | Travel networks as diplomacy | Unimplemented | No Waystones adapter. |

## Events and stories

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| D01 | Durable political event stream and readable history | Implemented and verified | `PoliticalFact` log, history page and filters. Unit `PoliticalSavedDataTest`; GameTest `PoliticalPresentationGameTests`. |
| D02 | Bounded event director | Implemented and verified | `evolution/EvolutionRuntime` (budgeted, per region), `evolution/drama/*`. Unit `EvolutionActivityTest`, `DramaStateTest`; packaged phase `r4`. |
| D03 | Story-generating combinations | Partially implemented | Authored world-event templates combine campaign and organization outcomes; succession disputes and memorials (0.3.0 dynasty) add causal history. No open-ended combination engine. |

## API and authoring

| ID | Item | Status | Evidence |
| --- | --- | --- | --- |
| API01 | Additive, provider-free public API | Implemented and verified | `api/*` packaged by `apiJar` and checked by `verifyApiJar` (`build.gradle`); `examples/addon`. |
| API02 | Strict schemas and transactional reload | Implemented and verified | `data/ReloadReport`, `data/DefinitionReloads`; keep-last-good domains (civic rules, world context, succession laws and ranks, development templates). Unit `ReloadReportTest`, `CivicDefinitionsReloadTest`, the `ExamplePack*` tests with their invalid variants. |
| API03 | Author tools and script surface | Partially implemented | `examples/datapack` (now with a succession law, court rank, development template and a non-military institution), `/kingdoms reload`, `/kingdoms definitions diagnose|validate|explain` (`data/DefinitionCommands`). No KubeJS facade. |

## Work packages P01–P08

| Package | Status after 0.3.0 | Where |
| --- | --- | --- |
| P01 Shared ontology, receipts, permissions | Implemented | B01–B04 above; per-kingdom sovereignty (`politics/Sovereignty`). |
| P02 Peaceful institution loops | Partially implemented | The Lamplighters loop (guild, chapters, commissions, packaged `r2`). The 10–12 additional quest templates are MCA Quests content (sibling repository), not changed here. |
| P03 Law, mediation, protected civilians | Partially implemented | L02 (jurisdiction policy) is in place; L01 consequences of committed cases need a Crime-owned event contract. |
| P04 Agreements with real effects | Partially implemented | R03 clauses; one economy service (`development/*`) for any later pricing. Tariffs and dues blocked by provider (E02). |
| P05 Exploration, routes, settlement context | Partially implemented | X01, X02 and the Ledger sketch; abstract routes only (E04). |
| P06 Magic, religion, race, culture | Partially implemented | M01, M04; the rest unimplemented (no verified APIs). |
| P07 Bounded emergent politics | Implemented | D02, world events, and 0.3.0 succession disputes, regencies and memorials. |
| P08 Datapack and third-party authoring | Implemented | API02, API03; [Datapack reference](Datapack-Reference.md). |
