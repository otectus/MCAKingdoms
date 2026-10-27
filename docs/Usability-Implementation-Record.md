# Usability implementation record

Work on the first version of [MCAKingdoms_Usability_Implementation_Plan.md](../MCAKingdoms_Usability_Implementation_Plan.md) (packages U1–U11), done on 2026-10-23/24 on `main` at `6dbb3c7` (the plan's review baseline) and left uncommitted for review. Each package lists its status under the plan's section 4.3 terms — **reproduced**, **already fixed**, **changed**, **validated**, **blocked** — with the main locations. Paths are relative to `src/main/java/dev/otectus/mcakingdoms/` unless they start with another top-level folder.

## U1 — Honest reload outcomes and actionable definition errors

- **Reproduced.** `CivicDefinitions.apply` and `WorldContextDefinitions.apply` kept the old snapshot on rejection, while `/kingdoms reload` and the reload task reported success from the completed future.
- **Changed.** A per-attempt report, `data/ReloadReport.java:28`, with domains at `:36` (six fatal at prepare, civic and world-context domain-local) and one description for command, task and Server health (`:190`). Diagnostics name file, field, found value and expected form (`data/DefinitionProblems.java:13`); civic and world-context reject domain-locally (`civic/CivicDefinitions.java:38`, `worldcontext/WorldContextDefinitions.java:46`); the command and task read the attempt (`command/KingdomsCommands.java:118`, `interaction/CoreTasks.java:64`); a successful sync commits it (`McaKingdoms.java:95`). First load with nothing retained is reported as such.
- **Validated.** `ReloadReportTest`, `DefinitionRegistryDiagnosticsTest`, `PoliticalDefinitionsDiagnosticsTest`, `CivicDefinitionsReloadTest`, `WorldContextDefinitionsReloadTest`, and the example-pack invalid variants (`ExamplePack*Test`).

## U2 — Correct Book links and expose authorized resident founding

- **Reproduced.** Political Book entries linked to operator-only `politics.diagnose`; BOOTSTRAP was operator-only in Tasks and in `/kingdoms politics bootstrap` although `openFounding` defaulted on.
- **Changed.** The service rule stays authoritative (`politics/GovernmentService.java:1053`, checked at `:356`, with the setting injectable for tests). Tasks constrain a resident's choices to their kingdom, residence and self and explain refusals (`interaction/PoliticalTasks.java:96`, status task at `:50`); the command accepts eligible residents (`politics/PoliticalCommands.java:34`). Book entries link to read tasks; operator-only topics carry a player alternative or an explanation (`client/KingdomGuideScreen.java:83`).
- **Validated.** GameTests `residentFoundingReachesOnlyTheServiceRule` (non-resident, wrong capital, other leader, lost eligibility, governed kingdom, operator path, repeated Apply) and `residentFoundingFollowsTheSetting` (setting off refuses, on founds); `GuideTaskLinksTest` over all three Book languages.

## U3 — Documentation, compatibility and migration guidance

- **Reproduced.** CurseForge gave the MCA range as one exact version and omitted the Book recipe and Tasks; the Book said three POIs, operator-only founding and per-region opt-in; R1 gave the contact range as 2–16 blocks; R4 said settlements are opted in one by one by UUID; the README's retry advice was unconditional.
- **Changed.** README restructured (first session, guides by audience, compatibility table, upgrade runbook, operating section) with old anchors kept; CURSEFORGE rewritten; [Configuration](Configuration.md) covers all 8 files and 65 keys with read timing verified from code; [presets](presets/README.md); config comments added to every key (FactionConfig had none); "design and delivery record" banners on R1–R4 and a design-history banner on `docs/mcakingdoms.md`; R1 contact range and R4 opt-in corrected; Book corrections (POI threshold, founding, regions, renamed tasks, R-labels, MCA Quests 1.7.1).
- **Validated.** Link and anchor check of every changed document (all resolve); claims checked against the cited code.

## U4 — A usable first ten minutes

- **Reproduced.** No persistent help in Tasks; no Ledger recipe unlock; terse empty settlement result; overlay time ran while hidden.
- **Changed.** Getting started panel (`client/GettingStarted.java:12`, `client/KingdomActionsScreen.java:133`); Ledger recipe advancement (`src/main/resources/data/mcakingdoms/advancements/recipes/village_ledger.json`); empty settlement and Ledger states (`client/VillageLedgerScreen.java:194`); overlay waits while a screen, F1 or pause hides it and drops stale context (`client/ClientPresentationState.java:72`); MCA: Kingdoms key category with a Controls shortcut (`client/InteractionClient.java:16`).
- **Validated.** Packaged client: K opens Tasks; the GUI tour includes the Getting started page at three scales in English, Spanish and Chinese, with its button set checked (`*-getting-started.png` in the localized run under Validation).
- **Not verified at runtime.** JEI recipe display, a deliberate key conflict, reconnect, and leaving or changing dimension while a screen hides the banner (code path covered by review only).

## U5 — Search, empty states and focus

- **Reproduced.** Whole-string matching ("join guild" missed "Join a guild"); ambiguous empty states; `localPage` reset on selection; focus lost after replies.
- **Changed.** Token, accent and alias search shared by server and client (`interaction/SearchText.java:11`); separate no-match / no-choices / unavailable / loading states with Clear; local page and focus kept by stable identity (`client/KingdomActionsScreen.java`).
- **Validated.** GameTest `catalogueSearchUsesWordsAliasesAndOrder`; packaged client keyboard search and paging.

## U6 — Field guidance and review editing

- **Reproduced.** No structured bounds; review without an edit route; prefilled text drafts dropped; Back erased the earlier answer.
- **Changed.** `Constraint` with units and examples checked before advancing and again by the handler (`interaction/ActionRegistry.java:36`); Step X of Y; Edit on review that clears only dependent answers and invalidates the old review (`interaction/InteractionNetwork.java:158`).
- **Validated.** GameTest `typedEntriesAndReviewEditsKeepUnaffectedAnswers`; client screenshot `gui-2-form.png` shows bounds and steps.

## U7 — Capability, prerequisites and outcomes

- **Reproduced.** `civic.commissions_unavailable` covered several causes; integrations listed versions only; notices pointed at the generic catalogue.
- **Changed.** Reason categories (`interaction/Unavailable.java:9`); separate commission causes (`civic/CivicService.java:275`); What is available here? (`interaction/HelpTasks.java:7`, facts in `interaction/ServerFacts.java:30`); next steps after results (`interaction/NextSteps.java:13`); notices name the exact review task (`interaction/Notify.java:16`), including the evolving-world digest (`evolution/EvolutionService.java:274`).
- **Validated.** `TranslationCoverageTest` covers every cause in three languages; GameTests exercise refusals.

## U8 — Operator tools

- **Reproduced.** No reconcile task; no health view; no command help.
- **Changed.** Server health task and `/kingdoms health` (`interaction/ServerFacts.java:30`, store writability from `persistence/StoreStatus.java:13`, migration outcomes from `persistence/LegacyRename.java:50`, reconciliation status from `core/KingdomsServiceImpl.java:522`); Reconcile nearby village records (`interaction/CoreTasks.java:50`); merge review from `previewMerge` (`core/KingdomsServiceImpl.java:475`, shown by `interaction/CoreTasks.java:78`); `/kingdoms help [topic]` (`command/KingdomsHelpCommands.java:26`).
- **Validated.** `KingdomsHelpCommandsTest`; GameTests for operator tasks; packaged client operator flow.
- **Not measured.** Performance of reconciliation and discovery budgets (the plan asks for benchmarks before any budget change; none was changed).

## U9 — Localization and plain language

- **Reproduced.** Composed labels, political rows, War Room lines and results were English-only; search used English; the Book was English-only; more than 300 task strings had no language entry.
- **Changed.** Translatable components throughout the interaction path; server-side reading of labels in the bundled languages (`interaction/LocalizedText.java:21`); presented political rows (`politics/GovernmentService.java:930`, clauses at `:915`); War Room lines (`warfare/CampaignService.java:346`); localized warfare, guild and template choices (`interaction/WarfareTasks.java:21`, `interaction/CivicTasks.java`); the task index searched on the client (`interaction/InteractionNetwork.java:352`, protocol 3 at `:32`); the renamed labels from the plan's table; Spanish and Chinese for the full Book (65 entries each, `src/main/resources/assets/mcakingdoms/guide/es_es`, `zh_cn`). Design note: [Localized-Search-Design.md](Localized-Search-Design.md).
- **Validated.** `TranslationCoverageTest`, `LangCoverageTest`, `GuideTranslationTest`; packaged client GUI tour in `es_es` and `zh_cn`.
- **Limits.** Record-choice search does not see translations that exist only in a client resource pack; political service refusal messages and other service-produced sentences remain English; scenario and world event titles from data packs are shown as written; no native-speaker review of the Spanish and Chinese text was available.

## U10 — Datapack and addon authoring

- **Changed.** [Datapack reference](Datapack-Reference.md); [example pack](../examples/datapack/README.md) with six invalid variants; [addon guide](Addon-API.md); `KingdomsService` contracts in Javadoc; [addon example](../examples/addon/README.md) compiled against the API jar by `check` (`build.gradle`, `addonExample` source set). `kingdomGating.unknownKingdomPolicy`, documented but never read, now applies to MCA: Kingdoms' own gates and MCA Quests conditions (`integration/IntegrationConfig.java:39`).
- **Validated.** `ExamplePackCoreTest`, `ExamplePackPoliticsTest`, `ExamplePackGateTest`, `ExamplePackWorldContextTest`, `ExamplePackEvolutionTest`, `IntegrationConfigTest`; `compileAddonExampleJava`; `verifyApiJar`.
- **Not run.** Loading the example pack into a live server world (the unit tests use the real loaders over the shipped resources plus the pack).

## U11 — Accessibility and release validation

- **Changed.** Political status and errors in the focusable detail panel (`client/politics/KingdomScreen.java:93`); narration once per meaningful change; the GUI tour gained the Getting started page and a language option (`src/clientTest/.../GuiStyleAcceptance.java`, `InteractionClientHarness.java`, `tools/client-test/interaction_client.py --languages`); client launchers derive artifact names from `gradle.properties` instead of the stale 0.1.0.
- **Validated.** Packaged client interaction acceptance and GUI tours in three languages; the packaged Forge client with real MCA Reborn, Townstead, MCA Quests, Reputation and Conversations, where the actual Blueprint screen shows the civic header from a real server response (see Validation). The Blueprint launcher now skips the first-launch accessibility screen, which had stopped it (`tools/client-test/production_client.py`, `src/clientTest/.../BlueprintAcceptanceHarness.java`).
- **Blocked / not verified.** Narrator audio (no `flite` on this host); JEI; an alternative font resource pack.

## Validation

Final runs on 2026-10-24. Every check ran after the last change to the code it covers. Logs are under `build/usability-evidence/` (git-ignored); `$PWD` is the repository root, and `Xvfb` is `/tmp/kd-xvfb/usr/bin/Xvfb`.

| Check | Command | Result | Evidence |
|---|---|---|---|
| Build, unit tests, API jar, addon example | `GRADLEW_QUIET_LOG_DIR=$PWD/build/usability-evidence/logs /home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh $PWD check build productionTestJar -I tools/test/production-tests.gradle` | PASS: 218 tests in 54 classes, 0 failures, 0 errors, 1 skipped (baseline 181/0/1). `verifyApiJar` and `compileAddonExampleJava` run under `check`. | `logs/gradle-MCAKingdoms-check-20261024-223810.log` |
| GameTests | `python3 tools/test/run_gametests.py`, after moving the previous `run/world` aside | PASS: 62 of 62 required (baseline 57). New: the four in `UsabilityGameTests` and `residentFoundingFollowsTheSetting`. | `logs/gametest-final.txt`, `logs/gradle-MCAKingdoms-runGameTestServer-20261024-223915.log` |
| Resources | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCAKingdoms` | 0 errors, 0 warnings, 0 notes | console |
| Whitespace | `git diff --check` | clean | console |
| Client harness jar | `gradlew-quiet.sh $PWD productionClientTestJar -I tools/client-test/client-test.gradle` | BUILD SUCCESSFUL, rebuilt after the Blueprint onboarding fix | `logs/gradle-MCAKingdoms-productionClientTestJar-20261024-223930.log` |
| Packaged interaction client and GUI tour in three languages | `python3 tools/client-test/interaction_client.py --work-dir build/usability-evidence/client-languages-2 --xvfb Xvfb --gui-style --languages es_es,zh_cn` | PASS: K key, named search, create/read/rename review and apply, compact paging, War Room handoff to the wizard; 95 screenshots (35 English, 30 Spanish, 30 Chinese), including the Getting started page | `client-languages-2/`, `logs/client-languages-2.txt` |
| Packaged Forge client with real MCA Reborn 7.6.26, Townstead 0.7.6, MCA Quests 1.7.1, Reputation 0.6.1, Conversations 1.8.0 | `python3 tools/client-test/production_client.py usability-2 --xvfb Xvfb` | PASS: actual Blueprint screen and mixin, real civic request and response (name, kingdom, heraldry, faction), mouse input, reassignment while open, compact resize, cleanup; the conversation entry check passed against Conversations 1.8.0 | `build/production-client-blueprint-usability-2/` (`BLUEPRINT_PASS.txt`, `CONVERSATION_ENTRY_PASS.txt`), `logs/client-blueprint-2.txt` |

All client runs loaded the same mod jar as the final build.

| Artifact | SHA-256 |
|---|---|
| `build/libs/mcakingdoms-0.1.1.jar` | `e499eed88f213d2c096892b77fe6dcfc58cf50f2fdc61a8af70d1b6998191ecb` |
| `build/libs/mcakingdoms-0.1.1-api.jar` | `d55df9e7f6d32a4b9d1d9ab44321ee89cd0b6aaabc4e0f9eab02c5c60f8a3653` |
| `build/libs/mcakingdoms-0.1.1-sources.jar` | `cb2b552fb6441028f176c6340f5361f360d0c5c1d5d049141423e77e1107e56c` |
| `build/test-artifacts/mcakingdoms-0.1.1-acceptance.jar` | `ffc7d74d81bb7ce0fb586bc69e88e27c91844b97d8cb1bf136cd9d180ec6c564` |
| `build/test-artifacts/mcakingdoms-0.1.1-client-acceptance.jar` | `a507f30bdbbbc7656bcddeb2843bb948af0a1e8dc4b26ea0b8f5d144e252f0c4` |

The localized run used an earlier client harness build (`852ea228…`). The only difference from the final build is `BlueprintAcceptanceHarness.class`, which that run does not use.

## Save, schema, API and network impact

- **Network (2026-10-24; superseded by the 2026-10-26 section below):** the `mcakingdoms:interactions`, `politics` and `war_room` channels are protocol 3; client and server must run the same build (strict version equality, `interaction/InteractionNetwork.java:32`, `politics/PoliticalNetwork.java:21`, `warfare/WarfareNetwork.java:34`).
- **Saves:** unchanged; no saved-data format, file name or migration marker was altered.
- **Data pack schema:** unchanged (schema 1; no new mandatory field).
- **Public API:** no signature change; `KingdomsService` gained Javadoc. `verifyApiJar` passes.
- **Config:** no key or value change; comments were added, which Forge writes into existing files on the next start. One documented-but-dead key (`unknownKingdomPolicy`) now has its documented effect, with an unchanged default.

## 2026-10-26 — Visual guidebook and usability plan (UX-01–UX-12)

The plan file now holds the 2026-10-26 plan (UX-01–UX-12, sections 4–14). It was implemented on 2026-10-26 on `main` at `6dbb3c7`, on top of the uncommitted U1–U11 work, and is also left uncommitted. Each area below separates what was **implemented**, what is **tested automatically** (unit tests and GameTests), what was **checked in the packaged client** (real widgets, rendering and networking, with screenshots inspected by eye), and what is **not verified**. Paths are relative to `src/main/java/dev/otectus/mcakingdoms/` unless they start with another top-level folder.

### Correctness and copy (plan sections 3–4, slice A)

- **Implemented.** U2's founding access and political links are kept; Book article links are now validated for their target and audience as well as the primary task. Article text no longer hard-codes "K": `{key:tasks}` renders the bound Kingdom Tasks key (`client/guide/GuideArticle.java:54`), and the welcome line and advancement name the bound key (`McaKingdoms.java:235`, `src/main/resources/data/mcakingdoms/advancements/welcome.json`). The world-evolution overview distinguishes "turned off" from "none known to you" (`interaction/EvolutionTasks.java:237`). The task catalogue is titled Kingdom Tasks, as the key and the start view are.
- **Tested automatically.** `GuideTaskLinksTest.articleLinksResolveAndRespectAccess`; `GuideTranslationTest.noArticleHardCodesTheTasksKey`; new `TranslationCoverageTest.translationsReadTheSameArgumentsAsEnglish`, which compares the `%s`/`%n$s` arguments of every Spanish and Chinese entry with English (A12: no mismatch existed).

### Visual Book of Kingdoms (plan section 5, slice B)

- **Implemented.** Versioned resources: schema 2 typed blocks (paragraph, heading, steps, recipe, item, callout, image, comparison, diagram, requirements, links, kingdoms) beside adapted schema 1 entries; lenient reading for the game and strict reading for tests (`guide/KingdomGuide.java:267`, blocks at `:344`). The recipe display model is common code built from a `RecipeManager` (`guide/GuideRecipe.java:77`; text version at `:200`); the client resolves it from the synchronized recipes and refreshes an open article on `RecipesUpdatedEvent` and tag updates. Articles are rendered by a scrollable panel with focusable link targets (`client/rich/RichPanel.java:29`, Tab handling at `:251`; recipe grid at `client/rich/Pieces.java:784`). The library loads English, then the selected language topic by topic, bounded, and reloads with resource packs (`client/guide/GuideLibrary.java:48`, listener in `client/ClientExtensions.java:22`). The reader has Back with position (`client/KingdomGuideScreen.java:30`), Home, bookmarks and recent topics under Saved, "Topic N of M", ranked search, commands folded outside the operator and reference chapters, a narrow layout with a Contents/Article switch (`:121`), and short Previous/Next labels where the full ones would clip. The kingdom gallery comes from the server (`network/GuideKingdomsPacket.java:13`, `client/guide/GuideKingdoms.java:53`). Local state (bookmarks, recent topics and tasks, favourites, last topic) is kept in `config/mcakingdoms-client-state.json` (`client/ClientState.java:24`). Content: 70 topics in each language; new *Two books, two purposes*, *Craft the Book of Kingdoms*, *Craft the Village Ledger*, *Kingdom Tasks, step by step* and *Kingdom, government and control*; converted topics with steps, a requirements example, link sets, an agreement diagram, a recruit-transfer diagram and the gallery. Search synonyms match only at word starts, so "job" finds "MCA Quests" but not "Request" (`interaction/SearchText.java:117`).
- **Tested automatically.** `GuideSchemaTest` (8 tests: legacy adaptation, every block type, invalid block fallback, skipped entries, unsupported schema, budgets, ranking, synonyms and goal phrases); `GuideTranslationTest` (structure parity across languages); GameTests `GuideRecipeGameTests` (5: shipped recipes from the server, a replaced recipe, a removed recipe never shown as available, alternatives and empty tags, special and cooking recipes).
- **Checked in the packaged client.** `BookGuideClientHarness` via `book_client.py`: actual item use opens the Book; all topics parse; both recipes come from the synchronized recipe manager; a data pack replacing and then removing the Ledger recipe is applied by `/reload` while the Book is open, and removing the pack restores it; keyboard focus and Enter on a link; Back, Home, a bookmark written to the state file and listed under Saved; "job" search; the six-kingdom gallery; folded commands; the compact (240 GUI pixels) and narrow layouts; Escape; reopening at the last topic. Screenshots 01–13 were inspected. The harness now takes each screenshot one stage after the change it shows; earlier runs captured the previous frame for 06, 07, 11 and 12.

### Task discovery and onboarding (plan section 6, slice C)

- **Implemented.** Stable task groups (Places, Guilds and jobs, Government, Exploration, Conflict, World events, Help, Operator tools) and server feature checks (`interaction/TaskMeta.java:24`, `:164`); a task this server cannot run is listed as "not available here" and opens an explanation with its Book topic instead of a form (`interaction/InteractionNetwork.java:138`). A start view (`:132`, `interaction/Suggestions.java:34`) shows where you are, up to three suggestions (transfer consents, signatures and votes waiting for you, world-event consents, the settlement here, a nearby guild contact, the first-village guide), favourites, recent tasks and server features; `tasks.openTo` can skip it. Goal phrases for 33 tasks (`task.mcakingdoms.<id>.aliases`) rank right after titles, and an exact phrase ranks first (`interaction/SearchText.java:80`).
- **Tested automatically.** GameTests `startViewSuggestsOnlyWhatThePlayerMayKnow`, `unavailableFeaturesStayListedWithTheirReason`, `goalPhrasesFindTheirTask`.
- **Checked in the packaged client.** K opens the start view, then the catalogue; categories in the GUI tour at three scales and three languages.

### Requirements, forms and results (plan section 7)

- **Implemented.** A shared requirement row with ✓ Ready, ✗ Needed, ? Could not check, and an informational note for which route applies (`interaction/ActionRegistry.java:80`). Rows for guild services with the member or independent route and exact numbers (`civic/CivicRequirements.java:68`), contact range, resident founding, the responsibility each political action needs, and the shared transfer destination (`interaction/Requirements.java:32`). Reviews name their action on the confirm button (`interaction/ActionRegistry.java:135`). Results say done, waiting, not done or not confirmed; a read-only task has no status line and no Check button, because nothing was sent to check. Error and unavailable pages offer Copy support details (`client/KingdomActionsScreen.java:182`).
- **Fixed during validation.** The two-client civic run showed a second player's join refused as stale because the first player had just joined: every civic task was versioned by the global organization revision. Joining and leaving are now versioned by the player's own memberships and the defined guilds (`interaction/CivicTasks.java:19`); other civic tasks keep their broader version.
- **Tested automatically.** GameTest `guildRequirementsShowTheRouteAndTheReviewNamesTheAction` (route, exact numbers, separate contact row, named join, done and refused statuses, leave); the existing duplicate, stale and forged-request GameTests still pass.
- **Checked in the packaged client.** The review and result pages (`01-create-review.png`, `03-rename-result.png`); the War Room's campaign link, without Recruits, opens the unavailable page with Learn more and Copy support details (`05-war-room-unavailable.png`).

### Screens (plan section 8, slice D)

- **Implemented.** Ledger: server-side search over the settlements the viewer knows (names, former names, slug), sorting by name or distance, a kingdom picker, and pagination with counts that never include hidden records (`knowledge/SettlementKnowledge.java:151`); a missing kingdom filter falls back to all kingdoms with a notice (`network/NetworkHandler.java:170`); list, details and picker views with selection kept by identity (`client/VillageLedgerScreen.java:36`). Guilds: a structured, read-only view (`civic/CivicViews.java:45`; the civic channel refuses mutations at `civic/CivicNetwork.java:45`) and a screen whose join, leave and service buttons open the reviewed tasks. Government: a header with state, leader, capital, regency, elections and your responsibilities (`politics/GovernmentService.java:1142`), labelled record fields (`:921`), documents awaiting your signature (`:639`), replies bounded to the packet size (`politics/PoliticalNetwork.java:80`), founding actions on the Overview (`client/politics/KingdomScreen.java:149`). War Room: control, campaigns, orders and civilian sections (`warfare/CampaignService.java:347`), each task link in its section and marked "not available here" with the reason when this server cannot run it (`warfare/WarfareNetwork.java`, `client/WarRoomScreen.java`).
- **Tested automatically.** GameTests `ledgerSearchesOnlyKnownSettlements`, `kingdomPagesReadAsLabelledSections`; `WarfareNetworkTest` (sections and link availability round trip, bounds).
- **Checked in the packaged client.** The interaction client and GUI tour (14 pages × 3 scales × 3 languages) cover the Ledger, Guilds, Kingdom, War Room, form, guide, recipe page, entry banner, Getting started, start view, categories and settings pages; the political client covers the Kingdom screen workflow.

### Accessibility, settings and feature status (plan sections 9–10, slice E)

- **Implemented.** Every panel built from pieces has a narration text (recipes as rows and ingredients, diagrams and comparisons by their alt text, galleries by name). A settings screen with an entry-banner preview, what the Tasks key opens, the introduction, reduced recipe motion and clearing history (`client/KingdomsSettingsScreen.java:31`; keys at `presentation/PresentationConfig.java:40`; Mods → Config at `client/ClientExtensions.java:24`).
- **Checked in the packaged client.** Keyboard link navigation and Enter in the Book; the recipe narration text (`BOOK_NARRATION.txt`); the settings page at three scales and three languages.
- **Not verified.** Spoken narration: this host has no `flite`, so Minecraft's narrator cannot speak; only the narration text was checked. No formative testing with players new to the mod.

### Plan test cases (section 14.1)

| Case | Covered by |
|---|---|
| A01 | `GuideSchemaTest`, `GuideTranslationTest`, `KingdomGuideTest`; the Book client parses every packaged topic |
| A02 | `GuideRecipeGameTests` (shaped, shapeless, quantities, empty slots, alternatives, empty tags, special and cooking recipes); recipe narration in the Book client |
| A03 | Book client: data-pack replacement and removal applied by `/reload` with the Book open, then restored. Clearing the gallery cache on logout is code-reviewed only; no run joined a second server |
| A04 | `GuideTaskLinksTest` (primary task links, article links and their audience, player-reachable political reading and founding) |
| A05 | GameTests `residentFoundingReachesOnlyTheServiceRule`, `residentFoundingFollowsTheSetting` (from U2, still passing) |
| A06 | GameTest `guildRequirementsShowTheRouteAndTheReviewNamesTheAction`; the civic multiplayer run |
| A07 | GameTests `goalPhrasesFindTheirTask` and U5's localized task search; `GuideSchemaTest.searchUnderstandsPlayerSynonymsAndGoalPhrases` |
| A08 | GameTest `ledgerSearchesOnlyKnownSettlements` |
| A09 | U6's review-edit GameTests; Book client resize and pane switching keep the topic |
| A10 | GameTests `namedSettlementWizardRejectsSessionAttacksAndExecutesOnce`, `permissionLossAndPrivateTargetsAreRechecked` |
| A11 | The civic multiplayer run: Guild screen join, leave and rejoin through the reviewed Tasks steps, for two players at once |
| A12 | `TranslationCoverageTest` (keys and arguments per language), `LangCoverageTest`; Spanish and Chinese GUI tours |
| A13 | GameTests `startViewSuggestsOnlyWhatThePlayerMayKnow`, `ledgerSearchesOnlyKnownSettlements`; the political and warfare multiplayer runs (private proposal and War Room privacy) |
| A14 | GameTests on a dedicated server (a client-class link crashed it once during this work and was moved, see MODMAP); `verifyApiJar` |

### Validation (2026-10-26)

Final runs, all after the last code change. `$PWD` is the repository root; `Xvfb` is `/tmp/kd-xvfb/usr/bin/Xvfb`; logs are under `build/usability-v2/logs/` (git-ignored).

| Check | Command | Result | Evidence |
|---|---|---|---|
| Build, unit tests, API jar, addon example | `GRADLEW_QUIET_LOG_DIR=$PWD/build/usability-v2/logs /home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh $PWD check build productionTestJar -I tools/test/production-tests.gradle` | PASS: 229 tests in 55 classes, 0 failures, 0 errors, 1 skipped (218 after U1–U11). `verifyApiJar` ran; the API jar is byte-identical to 2026-10-24. | `gradle-MCAKingdoms-check-20261026-124304.log` |
| Client harness jar | `… gradlew-quiet.sh $PWD productionClientTestJar -I tools/client-test/client-test.gradle` | PASS | `gradle-MCAKingdoms-productionClientTestJar-20261026-124318.log` |
| GameTests | `python3 tools/test/run_gametests.py` after moving `run/world` aside | PASS: 73 of 73 (62 after U1–U11; new: 5 `GuideRecipeGameTests`, 6 `GuidedUsabilityGameTests`). Run on the final server code; the one later change is client-only (`client/politics/KingdomScreen.java`) | `gradle-MCAKingdoms-runGameTestServer-20261026-124055.log` |
| Resources | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCAKingdoms` | 0 errors, 0 warnings, 0 notes | console |
| Whitespace | `git diff --check` | clean | console |
| Book of Kingdoms client | `python3 tools/client-test/book_client.py --work-dir build/usability-v2/final-book --xvfb Xvfb --display :96` | PASS; 13 screenshots inspected | `final-book/` (`BOOK_GUIDE_PASS.txt`, `BOOK_NARRATION.txt`) |
| Interaction client and GUI tours | `python3 tools/client-test/interaction_client.py --work-dir build/usability-v2/final-menus --gui-style --languages es_es,zh_cn --xvfb Xvfb --display :97` | PASS: K opens the start view; All tasks; named search; create, read and rename with named confirm; compact paging; the War Room's campaign link, without Recruits, marked unavailable and explained. GUI tours: 14 pages × 3 scales × 3 languages; 132 screenshots, sampled by eye in all three languages | `final-menus/` |
| Political client | `python3 tools/client-test/interaction_client.py --work-dir build/usability-v2/final-politics --suite politics --xvfb Xvfb --display :98` | PASS: real packets, founding through the reviewed task, council, compact form, tabs, return to the Ledger; the Kingdom screen refreshes itself after the action; 4 screenshots inspected | `final-politics/` |
| Two clients, guilds | `python3 tools/client-test/civic_multiplayer.py --work-dir build/usability-v2/final-civic-mp --xvfb Xvfb --display :95` | PASS: both players join, leave and rejoin through reviewed Tasks with isolated profiles; unavailable services reported as refused; 4 screenshots | `final-civic-mp/` |
| Two clients, politics | `build/usability-v2/scripts/with_xvfb.sh :94 <log> python3 tools/client-test/politics_multiplayer.py --work-dir build/usability-v2/final-politics-mp` | PASS: the authorized client sees the private proposal; the other receives none | `final-politics-mp/` |
| Two clients, warfare (with Recruits 1.15.2) | `python3 tools/client-test/warfare_multiplayer.py --work-dir build/usability-v2/final-warfare-mp --xvfb Xvfb --display :93` | PASS: War Room snapshot privacy (the stranger receives no snapshot of an undiscovered settlement) and native diplomacy packets, with Recruits 1.15.2 on the server | `final-warfare-mp/` |
| Two clients, evolving world | `python3 tools/client-test/evolution_multiplayer.py --work-dir build/usability-v2/final-evolution-mp --xvfb Xvfb --display :92` | PASS: private history packets and competing outcome commands for two players | `final-evolution-mp/` |
| Real pack: MCA Reborn 7.6.26, Townstead 0.7.6, MCA Quests 1.7.1, Reputation 0.6.1, Conversations 1.8.0 | `python3 tools/client-test/production_client.py usability-v2-final --xvfb Xvfb` | PASS: the real Blueprint screen and mixin, a real civic request and response (name, kingdom, heraldry, faction), mouse input, reassignment while open, compact resize, cleanup; the conversation entry check passed with Conversations 1.8.0 | `build/production-client-blueprint-usability-v2-final/` |

Every client run above loaded the final mod jar, as recorded in each run's `artifacts.json` or `artifacts.sha256`.

| Artifact | SHA-256 |
|---|---|
| `build/libs/mcakingdoms-0.1.1.jar` | `6641e51682b7f539cd9ab35f992c01460478b676291a4e9c0bf1d4033e0f9d1f` |
| `build/libs/mcakingdoms-0.1.1-api.jar` | `d55df9e7f6d32a4b9d1d9ab44321ee89cd0b6aaabc4e0f9eab02c5c60f8a3653` |
| `build/libs/mcakingdoms-0.1.1-sources.jar` | `59071a0073a54c973ddf4a963b54331b974259af20d76ef30d4fd69926390b91` |
| `build/test-artifacts/mcakingdoms-0.1.1-acceptance.jar` | `5b35a66b349ebecea7d9ba6a4f3a34bfd7fbbb7e2c97419b3f48173ec14f4734` |
| `build/test-artifacts/mcakingdoms-0.1.1-client-acceptance.jar` | `909b65f57042916f994a10564b21b750c165cca837556eec2a830fcc8733b6e0` |

Found and fixed by these runs: screenshots taken before the frame showed the change (Book harness); the campaign link leading into a form that cannot succeed without Recruits; read-only results labelled "Done" with a Check button; doubled blank lines in results; the catalogue titled "Kingdoms"; alternative guild routes marked "Could not check"; the Ledger's misaligned rows, "About 0 blocks away" and a truncated page line; clipped Previous/Next labels in the compact Book; a repeated empty-tab text on Kingdom pages; the War Room naming kingdoms in English for every reader; a guild join refused as stale when another player joined; a 240-second budget shared by three GUI tours; and multiplayer launchers that pointed at another project's Xvfb.

### Not done, and why

- **Spoken narration.** This host has no `flite`, so Minecraft's narrator cannot speak. Every piece has a narration text, and the recipe narration was checked, but the plan asks for a functioning narrator test.
- **Formative testing with new players** (section 14.2): needs people; not possible here.
- **Optional enhancements** (section 13): JEI/REI handoff, a discovered-places schematic, political history filters and a personal objective pin were not started; the plan marks them as follow-ons.
- **QA matrix gaps** (section 9): the runs cover a 1280×800 window at GUI scales 2, 3 and the 240-pixel compact case in English, Spanish and Chinese. 1920×1080 and 2560×1440 windows, scale 1 and 4, a materially different resource pack and a pseudo-locale were not run.
- **Service sentences.** Political, transfer and evolution refusal messages produced inside the services are still English sentences; the screens around them are translated.
- **Recruits-dependent journeys in a client.** Without Recruits, the interaction client checks that military links are marked unavailable; the warfare multiplayer run uses Recruits 1.15.2 for snapshot privacy only. No client run declares a campaign or transfers a recruit; GameTests cover those services.
- **Image blocks** are supported and tested in `GuideSchemaTest`, but no bundled topic uses one yet.
- **NeoForge.** MCA: Kingdoms has no NeoForge 1.21.1 port, and the plan asks nothing of one.

### Save, schema, API and network impact (2026-10-26)

- **Network:** main channel protocol 6, `interactions` 4, `civic` 4, `politics` 4, `war_room` 4, each with strict version equality (`network/NetworkHandler.java:25`, `interaction/InteractionNetwork.java:34`, `civic/CivicNetwork.java:20`, `politics/PoliticalNetwork.java:23`, `warfare/WarfareNetwork.java:55`). Client and server must run this build.
- **Saves and data pack schema:** unchanged.
- **Resource packs:** Book pages in schema 1 still load; schema 2 is new and documented in the [datapack reference](Datapack-Reference.md#book-of-kingdoms-pages).
- **Public API:** unchanged; the API jar is byte-identical to 2026-10-24.
- **Config:** three new client keys (`onboarding.showIntro`, `tasks.openTo`, `guide.reducedMotion`) with defaults that keep the previous behaviour except that Kingdom Tasks opens on the start view; local state in `config/mcakingdoms-client-state.json`.

