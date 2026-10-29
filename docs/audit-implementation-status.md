# Audit implementation status (0.3.0)

Every item of the [27 October 2026 audit handoff](../MCAKingdoms_Audit_and_Implementation_Handoff_2026-10-27.md),
with its status on `release/0.3.0`: **DONE**, **PARTIAL**, **BLOCKED** (needs something another mod does not
offer) or **DEFERRED** (deliberately not done, with the reason). Test commands, results and logs are in
[Testing 0.3.0](testing/0.3.0/README.md); the old roadmap's IDs are classified in the
[roadmap delta ledger](Roadmap-Delta-Ledger.md); upgrade steps are in [Migrating to 0.3.0](migration-0.3.0.md). Paths are
under `src/main/java/dev/otectus/mcakingdoms/` unless they start with another folder. "Not executed" means a check
that was not run; it is never counted as passed.

## Stabilization (H01–H12)

| ID | Status | What was done | Evidence |
| --- | --- | --- | --- |
| H01 R3 siege fixture | DONE | The runner writes `militaryActions = false` before the server starts; the scenario no longer calls `ConfigValue.set` (warfare reads go through `warfare/WarfareConfig.enabled()`, which a scenario can override without rewriting the file); breadcrumbs name each phase and the native state. | 20 consecutive packaged `r3` runs on one build, each with all 5 boots ([Testing](testing/0.3.0/README.md#h01-r3-repeated)). |
| H02 Action availability agrees with mutation | DONE | One pure evaluator, `politics/GovernmentActionPolicy`, for preflight, pages and mutation; office-specific removal choices. | Unit `GovernmentActionPolicyTest` (9); GameTest `PoliticalGameTests.electedLeaderOfficeIsProtectedAmongSeveralOffices`. |
| H03 Localization and readable output | DONE | Keyed results (`interaction/Keyed`), readable unit and person names, typed scenario checks. 3,956 keys in each of `en_us`, `es_es`, `zh_cn`. | `LangCoverageTest`, `TranslationCoverageTest`, `GuideTranslationTest`, `tools/test/validate_resources.py`. |
| H04 Portable test tooling and CI | DONE | `.github/workflows/ci.yml`, explicit EULA acceptance, `SKIPPED` outcomes, `quick_check.py`, `validate_resources.py`, GameTests move `run/world` aside and their world generates no natural villages (a GameTest-only data override; a random scenario once landed beside a real village), the companion manifest now lists certified tuples. | `tools/test/companions.json`, [Certified compatibility](compatibility-certified.md). |
| H05 Usability, accessibility, multiplayer validation | PARTIAL | Automated client checks as listed in Testing (GUI tour at three scales and three languages, the Book, four two-client tests). Their screenshots showed the compact Kingdom page squeezed by three rows of tabs; short pages now use one Section picker. **Not executed:** the five new-player sessions, narrator audio with a working system voice, two-client full-pack runs of the 0.3.0 features. | [Testing](testing/0.3.0/README.md#not-executed). |
| H06 One save-failure and atomicity policy | DONE | `persistence/AtomicSavedDataWriter` (strict, once-per-session `.bak`, refuses to overwrite an unreadable file) and `persistence/DurableSavedData` for every store, including the four new ones. [Save durability](Save-Durability.md). | Unit `AtomicSavedDataWriterTest` (5). |
| H07 Bounded Recruits durability check | DONE | A targeted fence: the unit's own chunk and Recruits' named files instead of a global save; counters in Server health. Measured on a packaged server with 400–500 loaded entities: the targeted check took p95 7–16 ms and p99 at most 21 ms for 1 to 100 units, against 430–670 ms median for the old broad flush. | Packaged phase `r4-fence` ([Testing](testing/0.3.0/README.md#h07-transfer-fence-profile)). |
| H08 Network payload caps | DONE | Byte budgets (`network/PayloadBudget`), deterministic trimming with "more" (`politics/PoliticalPacking`), code-point-safe clipping. | Unit `PoliticalNetworkBoundsTest` (3). |
| H09 Rename retirement | DONE (aliases kept) | Opt-in, idempotent progress migration (`persistence/LegacyProgress`, `migration.legacyProgress`). On-disk store migration kept. Runtime aliases stay until the companions drop them together (coordinated release; not done here). | Unit `LegacyProgressTest`, `LegacyRenameTest`, `LegacyMigrationFailureTest`. |
| H10 Deferred UX | DONE, two parts DEFERRED | Book diagrams with localized descriptions and a text fallback when a texture is missing (six topics); history filters by kind, date and visibility; a pinned task on the HUD with a key and automatic unpinning; the Ledger's direction and sketch of known places. **Deferred:** a JEI hand-off (reaching JEI needs a JEI plugin compiled against its API, which this mod's reflection-only integration rule excludes; the Book's recipes stay complete) and a Map Atlases link (it exposes no marker API). | `tools/art/guide_diagrams.py`, `politics/HistoryFilter`, `client/PinnedTask`, `client/PlacesSketch`; `GuideSchemaTest`, `GuideTranslationTest`. |
| H11 Search completeness | DONE (0.3.0 milestones) | Ledger search counts every visible match ([Completion ledger F13](Completion-Ledger.md)). | Unit `OrderedSettlementsTest`. |
| H12 Config truth and documentation | DONE | Reserved keys are labelled as having no effect in [Configuration](Configuration.md); every new key is documented; MODMAP regenerated. | This document set. |

## Roadmap reconciliation (P01–P08)

See the [roadmap delta ledger](Roadmap-Delta-Ledger.md) for every old roadmap ID.

| ID | Status | Notes |
| --- | --- | --- |
| P01 | DONE | Typed authority, receipts and capabilities exist; per-kingdom sovereignty added; the provider matrix is logged at start-up (`integration/ProviderMatrix`). |
| P02 | PARTIAL | The peaceful guild loop exists; the additional quest templates are MCA Quests content, a sibling repository not changed here. |
| P03 | PARTIAL | Jurisdiction policy is provided to MCA: Crime; consequences of committed cases need a Crime-owned event contract. |
| P04 | PARTIAL | Treaty clauses with effects; tariffs and dues need a tested economy provider (none integrated). |
| P05 | PARTIAL | Discovery and places exist; routes are abstract only. |
| P06 | PARTIAL | Progression predicates read Runic Skills; other magic, faith and lore adapters need verified APIs. |
| P07 | DONE | Budgeted evolving world and world events; succession disputes, regencies and memorials. |
| P08 | DONE | Example pack extended (law, rank, development template, non-military institution); `/kingdoms definitions diagnose|validate|explain`. |

## A — Dynastic monarchy

| ID | Status | What was done | Evidence |
| --- | --- | --- | --- |
| A1 Lineage and houses | DONE | `politics/dynasty/Lineage` (pure family graph), `LineageAdapter` (MCA family tree read-only; life stage from Townstead or MCA; unknown stays unknown), `DynastySavedData` keyed to existing governments. A missing provider or person blocks for review rather than guessing. | Unit `SuccessionResolverTest` (14: loops, twins, missing people, same-sex parents, childless rulers, grandchildren). |
| A2 Laws and succession | DONE | Per-government law, **Manual only** by default; five built-in laws plus data-pack laws; one restart-safe succession case per vacancy; adult NPC heirs seated, player heirs offered the crown, minor heirs under a regency (a living player parent, or an operator) until verified adulthood; council confirmation; elected rulers and elected successors never displaced; Capitals crowns never decided here; Succession tab and Book topic. | GameTests `DynastyGameTests` (5); unit `DynastyDefinitionsTest`, `DynastySavedDataTest`. |
| A3 Ranks, legitimacy, court | DONE, one part DEFERRED | Data-driven ranks with per-kingdom styles (no English titles imposed); title grants with acknowledgment, resignation and revocation, granting no authority; claims (contest, recognition, restoration) decided by an official, never the claimant, with appeals after a cooldown; an explainable legitimacy reading with configurable weights. **Deferred:** inter-house mediation (the political record holds one house per kingdom) and MCA Conversations audience dialogue (a sibling change). | `DynastyDefinitionsTest.legitimacyIsAReadingOfBoundedFacts`, `ExamplePackDynastyTest`. |
| A4 Ceremonies and mourning | DONE, one part DEFERRED | One memorial per ended reign; mourning shown on pages and darkened banners only (no villager or player changed); the herald tells the kingdom's connected officials only the succession's own steps (a law adopted, a succession opened, a renunciation, a declined or unanswered crown, a council's refusal), once per case, never the death or abdication a political notice already announced; heirs, councils and claimants are told directly; `dynasty.announcements` turns the herald off (requests to an heir or a council are sent either way). **Deferred:** the optional scheduled funeral contract. | GameTest `DynastyGameTests.anObservedDeathOpensOneCaseWithMemorialAndMourning`. |
| A5 Tests | PARTIAL | Pure, store and GameTests above. **Not executed:** the full-pack client walkthrough. | — |

## B — Physical heraldry and charters

| ID | Status | What was done | Evidence |
| --- | --- | --- | --- |
| B1 Content | DONE | Kingdom banner, heraldic shield, court noticeboard, seal press, kingdom charter: models, blockstates, loot, tags, recipes and advancements (`tools/art/heraldry_assets.py`), named colours, en/es/zh strings. | `check_mod.py` 0 errors; `validate_resources.py`. |
| B2 Charters and seals | DONE | A charter only names a ledger record (`document/DocumentLedger`, written before the charter is sealed); verdicts official, official but historical, revoked, unverified; proclamations, appointments, treaties, founding and title grants; revocation with reason. | GameTest `DocumentGameTests.chartersAreOfficialOnlyWhileTheirRecordHolds`. |
| B3 Placement and performance | DONE | Placed objects bind to the settlement's kingdom (or the placer's residence), keep bounded render hints, show historical arms when a kingdom is gone, and refresh every 10 seconds (staggered); possession grants nothing. Crown documents of a Capitals-governed kingdom are refused. | GameTest `DocumentGameTests.aPlacedBannerStandsForTheSettlementItIsIn`. |
| B4 Tests | PARTIAL | Registry, lang and resource checks; forged hash, wrong kingdom, revocation, historical record, reused request, ledger reload. **Not executed:** GUI-scale, shader and texture-memory checks of the new blocks. | — |

## C — MCA Capitals co-install

| ID | Status | What was done | Evidence |
| --- | --- | --- | --- |
| C1 Capability layer | DONE | `compat/capitals/CapitalsBridge` probes public getters only; tested 1.1.1 and 1.3.7 read API. | `CapitalsInteropGameTests`; packaged `capitals`. |
| C2 Modes and safety | DONE | Per-kingdom sovereignty (`politics/Sovereignty`): crown actions refused where Capitals governs, civic actions continue; `capitals.interop`. | Same. |
| C3 Mapping and reconciliation | DONE | Mapped, quarantined, released; dry-run report, accept and release commands and tasks. | Same; [Capitals interoperability](Capitals-Interoperability.md). |
| C4 A, B, D without double writes | DONE | Dynasty laws, cases and titles refuse non-native crowns; founding and title charters refuse them; war is refused for a Capitals crown. | `DynastyGameTests.aCrownGovernedElsewhereIsNeverDecidedHere`. |
| C5 Tests | PARTIAL | GameTest and a packaged co-install with Capitals 1.1.1. Capitals 1.2.1–1.3.7 cannot load with the pack's MCA 7.6.26. **Blocked:** delegated crown actions (no public Capitals mutation API; [proposal](Capitals-Upstream-API-Proposal.md)). | [Certified compatibility](compatibility-certified.md). |

## D — Development and economy

| ID | Status | What was done | Evidence |
| --- | --- | --- | --- |
| D1 Modes | DONE | `OFF` (default), `OBSERVE`, `PROJECTS`, `MANAGED_GROWTH`, `FULL_ECONOMY`; construction and economy pause separately; per-settlement opt-in; lowering the mode pauses work, raising it resumes only after review. | `DevelopmentGameTests.theDefaultModeChangesNothing`, `loweringTheModePausesConstruction`. |
| D2 Economy model | DONE | Real deliveries taken exactly once per request and handed back if the record cannot be saved; refunds for cancelled works; provenance labels (`PHYSICAL_VERIFIED`, `ABSTRACT_SIMULATED`, `AUTHORED_DEMAND`); no currency, no tax. | Unit `DevelopmentLedgerTest` (5); `aCancelledWorkRefundsItsDeliveriesOnce`. |
| D3 Planning and construction | DONE | Small vanilla-safe templates; plot survey (loaded chunks only, allowed dimensions, settlement bounds, homes, buildings, storage, beds, farmland, doors, redstone, points of interest, claim/protection hooks as the approving official); journaled block-by-block building that never replaces a changed block and never loads a chunk; rollback of only its own blocks. | `aFundedLampPostIsBuiltOnceAcrossARestart`, `aSurveyRefusesAPlotBesideABed`, `aChangedBlockStopsConstructionAndRollbackRemovesOnlyOurs`. |
| D4 Trade and routes | PARTIAL | Capped abstract trade between settlements of kingdoms with an agreement in force, labelled abstract. **Deferred:** physical caravans and optional risk events (no supported provider API). | `DevelopmentLedgerTest.abstractProductionIsCappedAndTradeConservesGoods`. |
| D5 UI and commands | DONE | Development tab, eleven Kingdom Tasks, `/kingdoms development inspect|pause|resume|dryrun|repair|export`, `/kingdoms economy inspect|diagnose|reconcile`, Book topic. | — |
| D6 Performance and recovery | PARTIAL | Bounded passes (16 works, a global block budget), no offline catch-up, metrics in Server health and `diagnose`, restart-safe journal. **Not executed:** the 10/50/200-settlement performance matrix. | — |
| D7 Tests | PARTIAL | Unit and GameTests above. **Not executed:** two-client full-pack and performance runs. | — |

## E — NeoForge port

Not approved by the handoff; not started.
