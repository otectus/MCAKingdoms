# Completion ledger

This is the single current account of review findings and their dispositions. When an older audit, review or plan disagrees with this page, this page and the current source win.

Scope: the 27 October 2026 review handoff of `main` at `01f8d14` (0.2.0), implemented as **0.3.0** on branch `release/0.3.0`.

**Dispositions:**

- **Fixed**: changed in 0.3.0, with a regression test.
- **Already fixed**: an earlier commit; retained and covered by the full suites.
- **Deferred**: deliberately not done, with the reason.
- **Not reproduced.**

**Commits on `release/0.3.0`:**

| Commit | Content |
| --- | --- |
| `00cdd5b` | Milestones 1–2: F01–F10. |
| `3c323d2` | Milestones 3–4: F11–F16, A01, A02. |
| Release commit (the one adding this file) | F17 documentation, version 0.3.0, the changelog and the Book topics. |

[Release evidence](#release-evidence) lists the tests, artifact hashes and runtime checks for this release.

## 0.3.0 findings

| ID | Finding | Disposition | Behaviour now | Regression evidence |
| --- | --- | --- | --- | --- |
| F01 | Civic identity lost on player clone or respawn | Fixed (`00cdd5b`) | `PlayerEvent.Clone` carries only `mcakingdoms:civic_identity` (and moves a pre-rename key). Merged settlements resolve through redirects. | Unit `CivicIdentityCarryOverTest` (4). GameTests `PlayerIdentityGameTests`: death and End return, merges, pre-rename key, founding eligibility after respawn. |
| F02 | Partial or undecodable settlement saves started over | Fixed (`00cdd5b`) | The whole payload is validated before anything is writable. A refused store keeps the original, never writes and stops the server from starting. | GameTests `SettlementLoadRefusalGameTests` (10): ids, bounds, duplicates, external references, redirects, cycles, dangling targets, retired chains, future schema, unreadable file. |
| F03 | Failed legacy migration treated as a new world | Fixed (`00cdd5b`) | Any failed saved-data or common-config copy stops the world before any store exists. `world/data/mcakingdoms_migration_report.txt` records each step. Successful copies are kept and resumed. | Unit `LegacyMigrationFailureTest` (7, with injected read, decode, write and move failures) and `LegacyRenameTest`. |
| F04 | Advertised companion contract not in public sources | Fixed in Kingdoms (`00cdd5b`). **Publishing companions deferred** (see below). | A method-level probe reports `available`, `legacy_consumer_contract`, `foreign_consumer_namespace` or `missing_institutional_methods`. Paid workshop jobs are refused before any offer unless the contract is available. `tools/test/companions.json` records the validated jars by SHA-256, the public commits inspected and the unpublished local work. | Unit `QuestContractProbeTest` (4). `python3 -I tools/test/verify_companions.py --pack`: PASS for 7 companions. MCA Reputation's local `StandingOutboxTest` covers keeping a pending old cursor. |
| F05 | Political maintenance cursor starvation | Fixed (`00cdd5b`) | Each domain takes its own due list, ordered by deadline then id, with a budget of 8. There is no shared cursor. | Unit `MaintenanceFairnessTest` (7), including the review's reproduction: 16 timed records beside 8 elections all expire. |
| F06 | Evolution activity required consecutive samples; unfair digests | Fixed (`00cdd5b`) | Occupied time is accounted from presence, separately from the sample. Generation rotates over present regions. Digests go oldest first within the budget. | Unit `EvolutionActivityTest` (7), including eight regions advancing under a budget of four. |
| F07 | Pending or refused operations shown as completed | Fixed (`00cdd5b`) | Typed outcomes: applied, pending, refused, unknown, read. **Check this action** asks the owning service's probe and never repeats the action. Probes cover warfare, evolution, transfers, drama and politics tasks. | GameTests `OutcomeCheckGameTests` and `PoliticalOutcomeGameTests`. Unit `TaskIntentTest` (every operation with a native or pending phase has a probe). |
| F08 | Safe cleanup hidden behind creation-feature gates | Fixed (`00cdd5b`) | Task intents: start, inspect, cancel, verify, restore. Inspect and cancel need no feature switch. Native verify and restore still need their provider. | Unit `TaskIntentTest` (5). |
| F09 | Scenarios announced before their commit | Fixed (`00cdd5b`) | Announcements are queued and sent only after the durable commit succeeds. | Unit `EvolutionActivityTest.opportunitiesAreAnnouncedOnlyAfterTheirCommit`. |
| F10 | Untouched recruit-transfer offers never expired | Fixed (`00cdd5b`) | An offer still offered or consented, past its deadline, with no native snapshot and no divergence becomes **expired**. At most 8 expire per 200-tick pass, both parties are told, and native state is unchanged. | GameTest `TransferExpiryGameTests.overdueUntouchedOffersExpireAndStopBlocking`. |
| F11 | Unbounded gallery retries; stale server-derived data | Fixed (`3c323d2`) | A pure request state machine allows 3 attempts with a 40-tick timeout. A failure rests until Try again or reopening. A server-pushed stale notice (own discovery, definition reload) refreshes once. Late replies are ignored. | Unit `GalleryRequestsTest` (7). |
| F12 | Finite historical-record caps without a path | Fixed for transfers and opportunities (`3c323d2`). **Reference-aware compaction for the other collections deferred.** | A capacity inventory covers every bounded collection, in Server health and `/kingdoms maintenance capacity`. Dry-run-first compaction archives finished transfers and opportunities to `<world>/mcakingdoms_archive/` before removing them. Evidence is never compacted, and generation stops before the bounds. The other collections keep refusing at the bound, as the handoff allows; the offline procedure is in [Capacity and record maintenance](Capacity-and-Maintenance.md). | Unit `CapacityTest` and `CompactionTest` (2). GameTests `CompactionGameTests`. |
| F13 | Repeated sorting per page; truncated search totals | Fixed (`3c323d2`) | One ordered snapshot per store revision. Ledger search counts every visible match. With 25,000 settlements, paging takes 34.5 ms in total instead of about 10.45 s. | Unit `OrderedSettlementsTest` (2), which prints the profile. |
| F14 | Translatable results flattened to English | Fixed for the named flows (`00cdd5b`, `3c323d2`): civic, election, regency, transition and refusal messages. **Some older political, mobilization and campaign result strings remain English** (see below). | Keys and arguments travel through results; commands and screens render them per player. | Unit `LangCoverageTest` and `TranslationCoverageTest`; en/es/zh key parity. |
| F15 | Reviews invalidated by unrelated activity | Fixed (`3c323d2`) | Political and civic review versions cover only the records, governments, definitions and authority they name. | GameTest `ReviewScopeGameTests.unrelatedGovernmentsDoNotVoidAReview`. |
| F16 | Non-portable validation; racing R3 fixture | Fixed (`3c323d2`) | `tools/test/environment.py` reads prerequisites from the environment or an ignored local file and fails clearly when one is missing. The companion manifest replaces the historical patch checks, which moved to `tools/history/`. The R3 fixture writes `militaryActions = false` before launch and asserts the native siege flag at once. | Fixture-only change; see the release evidence for the packaged runs. |
| F17 | Live documentation and release evidence out of date | Fixed (release commit) | This ledger; the README, CurseForge text, configuration reference, compatibility table and Book topics updated together; capacity and upgrade runbooks; MODMAP regenerated; historical documents labelled. Chinese terms unified across the Book and interface. | `GuideTranslationTest`, `GuideSchemaTest` and `GuideTaskLinksTest` for the Book topics. |
| A01 | Verified player residence registration | Fixed (`3c323d2`) | **Register my residence** and **Move my residence**, plus an operator override. The evidence is a bed-set respawn point in a loaded chunk inside a recognized settlement, with the player standing there. A provider's player-home evidence takes precedence. Moves wait `residenceMoveCooldown` (24,000 ticks). Registering the same settlement does nothing. The review names the reason and the next step for each unmet condition (release commit). Commands: `/kingdoms residence status\|register\|move`. | GameTests `ResidenceRegistrationGameTests` (4): evidence checks, register/move/cooldown/override, operator-assigned and merged residences, and a fresh player who registers then founds without an operator. |
| A02 | Optional political notices and office badges | Fixed (`3c323d2`) | Notices for founding, accession, vacancy and confirmed-death memorial come from committed public facts, once each. They are off by default on each client. Badges reach only players tracking the holder and are drawn as a separate line. A Politics settings tab controls both, and person pickers and the council list name offices. Server health states that no Townstead narrative bridge exists. | GameTest `PoliticalPresentationGameTests.committedMomentsBecomeNoticesAndBadges`: notices, privacy of mandates, local-office audience, badges, memorial, and NPC data unchanged. |

### Deferred or incomplete

- **Companion publishing (F04).** The contract Kingdoms needs exists in local trees: MCA Quests 1.7.2 and the MCA Reputation outbox adoption fix. The pack's validated `mcaquests-1.7.1.jar` was built from that local work. These trees also hold unrelated uncommitted work, and publishing is an outward action for their owner. Until they are released, the public sources do not carry the contract. Kingdoms reports this in Server health and refuses paid workshop jobs rather than accepting work it cannot complete.
- **Reference-aware compaction beyond transfers and opportunities (F12).** Removing political, campaign, drama, protection, civic or knowledge records safely needs a reference analysis for each domain. 0.3.0 keeps the refusal at the bound and documents the offline procedure.
- **Remaining English result strings (F14).** Older political, mobilization and campaign result texts produced deep inside those services are still English sentences. The flows named in the review were converted, and the rest are listed for a later pass.
- **Old-name alias removal.** This is a coordinated step after the companion builds are published and upgrade-tested. On-disk migration support stays; see [Upgrade and recovery](Upgrade-and-Recovery.md#old-names-runtime-aliases-and-on-disk-migration).

## New design questions

These came up during implementation. None reopens the two approved additions.

1. **Retention policy for political, campaign and world-event records.** Safe relief needs a decision: either archive with reference rewriting, or raise the bounds behind a new storage schema that older builds refuse. Archiving would need each referring record to point at an archive. Raising bounds would grow saves and replies; history and clauses would have to stay intact either way.
2. **Companion release order.** Kingdoms 0.3.0 works with both the public and the locally built companions, and reports the difference. The companion owner decides when MCA Quests 1.7.2 and the MCA Reputation outbox handoff are published. Only after that can the old-name aliases be removed.
3. **Structured service results.** Keying the remaining English sentences inside the political, mobilization and campaign services means changing their internal result types from strings to keys with arguments. This is an internal API change across those services, best done as one pass.

## Historical findings

These come from the September audit (`docs/MCAKingdoms-Audit.md`), the October review (`REVIEW.md`) and its remediation plan, the village-identity plan, and the GUI and usability work. Their open wording is not a current bug list. "Retained" means the earlier fix is unchanged in 0.3.0, and the unit and GameTest suites that cover it pass in this release.

| Historical item | Disposition | Evidence or note |
| --- | --- | --- |
| D-1: every living entity became resident | Already fixed (type-restricted NPC evidence) | Retained. Players now register explicitly (A01); movement, sleeping and trading never set a residence. |
| D-2: full-world save on every transfer confirmation | Already fixed (targeted flush and verification cooldown) | Retained. The durability fence is unchanged. |
| D-3: permanent political request ceiling | Already fixed (receipt expiry) | Listed with "retention" relief in the 0.3.0 capacity inventory. |
| D-4/D-14: destructive text rewriting; raw record output | Mostly fixed earlier | Residual outcome and localization issues handled under F07 and F14. |
| D-5: only the first 32 institutions considered | Already fixed (paging) | Retained. |
| D-6: elections, mandates and electorate | Already fixed | Frozen electorates are preserved by A01: registering or moving never rewrites an open election. |
| D-7: silent discovery-load failure | Already fixed | Retained; the privacy refusal is kept. |
| D-8 and village identity T1–T4 | Already fixed (footprints, redirects) | Retained. F02 now refuses broken redirect chains instead of loading them. |
| D-9: native mutation commits, then reports an error | Substantially fixed earlier | The task-layer misclassification is fixed under F07. |
| D-10: family introduction not retryable after withdrawal | Already fixed (evidence release) | Retained. |
| D-11: withdrawal always restored neutrality | Already fixed (prior relation) | Retained. |
| D-12/D-13: untranslated interface and docs | Mostly fixed in 0.2.0 | Residual items under F14 and F17. |
| R-1/R-2: copying and repeated sorting | Partly fixed earlier | The remaining settlement paging sort is fixed under F13. |
| R-3: concurrent review sessions | Already fixed (fingerprints) | F15 narrows versions without weakening conflict detection: `ReviewScopeGameTests` checks that a conflicting decision still voids a review. |
| R-4: raw UUID command paths | Not reproduced | No authority bypass found. Service-level authorization unchanged. |
| R-5: fail-closed siege hooks | By design | Exact-version gating retained; Server health reports it. |
| R-6 and K-9: legacy political packet writes | Already fixed (read-only, refusal) | Retained. |
| R-7: startup refusal when discovery cannot persist | By design | Documented as a fail-closed tradeoff, consistent with F02 and F03. |
| R-8: no-credit mob-farm encounters serialized the store | Already fixed (early checks) | Retained. |
| I-1/I-2/Q-1/R-2 (October) and village identity T5 | Reopened under F04 | See F04 and companion publishing above. |
| K-1: no rename migration | Already fixed | Its failure policy is repaired under F03. |
| K-2/K-7: Townstead pin and hidden dependency | Already fixed (capability probes) | A newer Townstead tuple still needs its own validation. |
| K-3 to K-6 | Already fixed | Retained. |
| K-10: hardcoded ledger fallback | Not reproduced | The original review corrected this claim itself. |
| K-11: schedule-only evolution writes | Already fixed | F06 and F09 were different defects. |
| K-12: provider patches as verification | Fixed under F16 | The patch checks are historical tooling in `tools/history/`. |
| K-13/K-14: fault handling; mixin packaging | Already fixed | Generated inventory refreshed and portable acceptance done under F16 and F17. |
| GUI audit; usability U1–U11 and UX-01–UX-12 | Delivered in 0.2.0 | Residual paths fixed under F07, F08, F11, F14 and F15; the manuscript visuals are kept. |

## Document status

| Kind | Documents |
| --- | --- |
| Current references | `README.md`, `CURSEFORGE.md`, `docs/Configuration.md`, `docs/Datapack-Reference.md`, `docs/Addon-API.md`, `docs/Capacity-and-Maintenance.md`, `docs/Upgrade-and-Recovery.md`, `docs/politics/`, `docs/presets/`, this ledger, and the in-game Book of Kingdoms. |
| Design and delivery records | `docs/mcakingdoms.md`, `docs/R1-Civic-Foundation.md` to `docs/R4-Evolving-World.md`, `docs/Book-Interface-Design.md`, `docs/Localized-Search-Design.md`, `docs/Usability-Implementation-Record.md`, `MCAKingdoms_Usability_Implementation_Plan.md`. |
| Historical reviews and audits | `docs/MCAKingdoms-Audit.md` (September), `REVIEW.md` and `docs/MCAKingdoms-Review-Remediation-Plan.md` (October), `docs/MCAKingdoms-GUI-Audit.md`, `docs/Player-Experience-Review.md`, `docs/MCAKingdoms-Village-Identity-Diagnosis-and-Plan.md`, `docs/MCAKingdoms-Capitals-Inspired-Implementation-Guide.md`, `docs/MCAKingdoms-Factions-Integration-Audit-and-Roadmap.md`, `docs/mcakingdoms-factions-audit/`, `docs/ultima-factions-audit/`, `docs/history/`. |

## Release evidence

Everything below was run on 27 October 2026 on branch `release/0.3.0`, version 0.3.0, with Java 17 and Forge 47.4.23. The Gradle work went through the project wrapper (`gradlew-quiet.sh`, never `clean`). Logs and work folders are kept under `build/v030-evidence/`. That folder is local and git-ignored, so the hashes and counts are recorded here.

### Artifacts

| Artifact | SHA-256 |
| --- | --- |
| `mcakingdoms-0.3.0.jar` (release) | `0dd679e46ac7d53b9dd2598431fafc1fe3de061311f608155a4c23ecaefb2cee` |
| `mcakingdoms-0.3.0-api.jar` | `6e8f56b0323fc6fd0aa49b0149a49c4397278b79c5da948d1e0977428c1dc821` |
| `mcakingdoms-0.3.0-sources.jar` | `f24aac451c01ae977481bfcbbd15fe79f36cc007be1662ed22976757a6159d46` |
| `mcakingdoms-0.3.0-acceptance.jar` (packaged-test fixture, not shipped) | `73e814f23542b05c5e6afd9614fdc00cea4c766248c29a4ae1a43af5a7bf7b0d` |
| `mcakingdoms-0.3.0-client-acceptance.jar` (client-test harness, not shipped) | `0361228192a251f7dd62eafdf0a7f7a90b790b641037f7100faadc0ac90540f7` |
| `mcakingdoms-0.3.0.jar`, pre-final build used by the packaged and multiplayer runs | `812e3e41ef8e1a5f1109c9cf1778c0e217798bbdf1768e1f77c8774705966ab9` |

The pre-final jar differs from the release jar only in seven text resources: the Spanish and Chinese language files and five Spanish/Chinese Book files. Both have 925 file entries; no class or other resource differs. The last change was the wording pass on those translations.

Companion jars used in acceptance are listed by SHA-256 in `tools/test/companions.json`. `python3 -I tools/test/verify_companions.py --pack` reports PASS for all seven.

### Build and tests

| Check | Command | Result |
| --- | --- | --- |
| Build, unit tests, API isolation, addon example | `gradlew-quiet.sh . check build productionTestJar -I tools/test/production-tests.gradle` | PASS. **275 unit tests, 0 failures, 1 skipped** (65 classes). Baseline at `01f8d14`: 229 tests, 1 skipped. |
| GameTests | `python3 tools/test/run_gametests.py`, fresh `run/world` | **97 of 97 passed** (baseline 73). |
| Artifact check | `python3 tools/test/verify_artifacts.py` | PASS for the three hashes above. |
| Consistency | `check_mod.py`, `git diff --check` | 0 errors, 0 warnings; no whitespace problems. |
| Language parity | `apply_lang.py`, `LangCoverageTest`, `TranslationCoverageTest`, `GuideTranslationTest` | 2,607 keys in each of `en_us`, `es_es` and `zh_cn`; the Book has the same topics and structure in all three. |

### Packaged dedicated-server acceptance

Command: `python3 tools/test/integration_runtime.py --work-dir <dir> --phase <phase> --mod <jar>…`. Each run is a fresh isolated server with the Kingdoms jar and the acceptance fixture. Each boot must end with `PASS integration`. These runs used the pre-final jar `812e3e41…`. The R3 run on the release jar is listed under [Real clients](#real-clients) with the other release-jar runs.

| Phase | Companions | Result |
| --- | --- | --- |
| `r3`, three runs | Recruits 1.15.2 | Each run passed all 5 boots: r3, restart, Recruits absent, reinstalled, future control schema. Every run includes "saved native siege appears in history" and "R3 fixture config must turn military actions off before launch". Three earlier runs on a pre-fix jar (`d0f578bd…`, kept in `runtime/superseded/`) also passed. |
| `split-village` | none | PASS, 2 boots. One start-up reconciliation line; the settlement file is unchanged and holds one record after restart, so redirect validation does not refuse a real merged world. |
| `politics` | none | PASS, 3 boots. The future-schema politics file stays byte-identical across save and shutdown. |
| `r4` | Recruits, MCA 7.6.26, Architectury 9.2.14, MCA Quests 1.7.1 (contract build), MCA: Crime 0.7.5, Townstead 0.7.6, Patchouli, GeckoLib | PASS, 5 boots plus future sidecars unchanged. Covers native transfer veto recovery and durable ownership. |
| `migration` | MCA Reputation 0.6.1, MCA, Architectury | PASS, 3 boots: preview and import, restart idempotency, a 1,000-event replay loop. |
| `r2` | MCA Quests 1.7.1 (contract build), MCA: Crime 0.7.5, MCA, Architectury, Townstead, Patchouli, GeckoLib | PASS, 2 boots. Recognized workshop, native payment, stale-building refusal, durable honor replay. This is the F04 `available` path. |

Fixture inputs written by the runner:

- R3: `config/mcakingdoms-warfare-common.toml` with `militaryActions = false`.
- R2: `enabled = true` in the civic config, and the pack's `pack/r2` workshop quest overlay.

Everything else uses defaults.

### Real clients

The first three suites ran on the pre-final jar `812e3e41…`. Rows marked **release jar** ran on `0dd679e4…`.

| Suite | Command | Result |
| --- | --- | --- |
| Multiplayer politics: two clients, dedicated server, governments founded while both are online | `with_xvfb.sh :93 … python3 tools/client-test/politics_multiplayer.py` | PASS: the authorized client sees the private proposal; the unauthorized one receives none. No client errors from the notice or badge packets. |
| Multiplayer warfare | `python3 tools/client-test/warfare_multiplayer.py --display :91` | PASS: R3 snapshot privacy and the native diplomacy packet, both clients. |
| Multiplayer evolution | `python3 tools/client-test/evolution_multiplayer.py --display :92` | PASS: R4 private history packet and a competing outcome command, both clients. |
| R3 again, **release jar** | `python3 tools/test/integration_runtime.py --phase r3 --mod recruits-1.20.1-1.15.2.jar` | PASS: all 5 boots, with the saved native siege in history. This is the seventh consecutive R3 pass across three builds. |
| Multiplayer politics again, **release jar** | `with_xvfb.sh :93 … python3 tools/client-test/politics_multiplayer.py` | PASS, with the same assertions as above. |
| GUI tour, **release jar**: English, Spanish and Chinese at GUI scales 2 and 3 and a 240-pixel compact view | `python3 tools/client-test/interaction_client.py --gui-style --languages es_es,zh_cn` | PASS: 162 tour pages (18 pages per language and scale, now including the Politics settings tab and Register my residence). No widget lies outside the screen or overlaps another. Keyboard checks cover Settings (including the Politics-tab draft and Cancel), Tasks, Guilds, Kingdom and the War Room. The interaction checks also pass. 168 screenshots. |
| Book and Ledger, **release jar** | `python3 tools/client-test/book_client.py` | PASS: item use, every topic parsed, live recipes, data-pack replacement and removal applied with `/reload` while the Book is open, keyboard links, the kingdom gallery loaded from the server, compact and narrow layouts, Spanish and Chinese pages. 22 screenshots. |

### Not covered by this release's runs

- **Human-only checks:** spoken narrator audio (the narrator library `libflite` is absent on this host), other fonts and resource packs, 1080p and 1440p layouts beyond the three recorded GUI scales, and a human fresh-player walkthrough. Registration, founding and recovery are covered by GameTests and the packaged runs.
- **Real-client native mutations:** campaign declaration and withdrawal, muster, dismiss and recover, and bilateral recruit transfer were not performed by real clients. The packaged R3/R4 fixtures and the GameTests exercise them server-side.
- **Other phases:** MCA: Capitals coexistence (no Capitals jar was available), and the packaged `r1`, `civic-loop`, `named`, `r4-crash` and `crash-replay` phases. None of these was rerun for 0.3.0.
- **A02 visual checks:** an in-world capture of badge rendering above an entity, including CJK role names next to other name-tag mods. The server side (who receives which badge) is covered by `PoliticalPresentationGameTests`, and the client packet handling by the multiplayer politics run.
