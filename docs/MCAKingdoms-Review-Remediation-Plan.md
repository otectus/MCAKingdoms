# MCA: Kingdoms remediation plan

Companion to `REVIEW.md` (2026-10-01). This is the implementation plan for the findings there,
written for the next session to execute. Finding ids (I-1, K-1, …) refer to that report. Every task
lists its files, what to change, how to verify, and what it depends on.

**Status: implemented on 2026-10-01** with the recommended defaults for D1 to D5. `REVIEW.md` section 9
records what changed and the verification. Corrections to this plan:

- K-14: Forge 47 does not read `[[mixins]]` from `mods.toml`, so dev runs pass `-mixin.config` instead.
- 1b step 2: the config alias is ANDed with the new switch, because ORing two default-on switches ignores
  an old "off".
- K-10: the hardcoded fallback was unreachable dead code.
- D5 below points at "task 6"; the Townstead work is task 3.

Ground rules that apply to every task: Forge 1.20.1 / 47.x / Java 17 only; version strings stay in
`gradle.properties`; registration on the MOD bus, gameplay on the FORGE bus; common code never imports
`client`; sibling mods keep binding Kingdoms by reflection (no Kingdoms type on their compile path);
iterate with `.mcmod-tools/gradlew-quiet.sh <repo> compileJava` and finish each repo with its full
`check`.

## 0. Decisions to make first

These change what the tasks below do. Each has a recommended default so the work can start without
waiting.

- **D1. Migrate old `ultima_kingdoms` worlds, or document a hard cut?** Recommended: migrate once,
  automatically, at first load (task 2). The family compatibility log records at least one dedicated
  server that ran the old id, and the cost of a hard cut is every settlement, government, faction
  score and receipt on it.
- **D2. Accept both mod ids in the add-ons for one release, or hard-switch?** Recommended: accept
  both. Each add-on bridge checks `ModList.isLoaded` for a list of ids and probes the new package
  first, then the old. This keeps a pack that mixes release dates working and costs a few lines. Drop
  the old id in the release after next.
- **D3. Quest registry ids `ultima_kingdoms:standing` and `ultima_kingdoms:faction_standing`.**
  Recommended: register both ids (old as alias) for one release; packs may still carry the old
  strings. Shipped Kingdoms content does not use either, so the alias is only for third-party packs.
- **D4. Completion-receipt consumer id.** Recommended: standardize on
  `mcakingdoms:regional_civic_network` in both mods and migrate persisted consumer ids in Quests
  (task 4). The alternative (keep the old string in Kingdoms) avoids the Quests migration but leaves a
  foreign namespace in Kingdoms forever.
- **D5. Townstead policy.** Recommended: replace the exact-version check with capability probing
  (task 6) rather than pinning the range, because the changelog already promised lower-bound-only
  ranges.

## 1. Repair the add-on bindings (I-1, Q-1, R-1, R-3/C-2)

Priority: do this first; nothing else in the integration can be tested until it is done.

### 1a. MCAQuests

Files: `src/main/java/dev/otectus/mcaquests/compat/KingdomIntegration.java`,
`quest/InstitutionalCommissionBridge.java`, `data/OptionalModNamespaces.java`,
`quest/IntegrationRequirements.java`, `quest/condition/ConditionTypes.java`,
`quest/reward/RewardTypes.java`, `src/main/resources/META-INF/mods.toml`, and the tests under
`src/test/java/dev/otectus/mcaquests/` that pin the strings (`NoUltimaStaticLinkTest`,
`OptionalModNamespacesTest`, `IntegrationRequirementsTest`, `InstitutionalCommissionBridgeTest`,
`KingdomLifecycle*Test`, `KingdomBindingPersistenceTest`, `CompletionReceiptOutboxTest`,
`InstitutionalCommissionPersistenceTest`, `FactionStandingRewardIdentityTest`).

Steps:

1. In `KingdomIntegration`, replace `MOD_ID` with a list `MOD_IDS = {"mcakingdoms", "ultima_kingdoms"}`
   (per D2) and `available()` with "any id loaded and the gate class present". Replace every
   `com.ultimakingdoms.api.…` constant with the `dev.otectus.mcakingdoms.api.…` name, and rename
   `UltimaFactionsApi` → `KingdomsFactionsApi`, `UltimaFactionsService` → `KingdomsFactionsService`,
   `UltimaTownsteadApi` → `KingdomsTownsteadApi`. Keep the old names as a second probe only if D2 says
   so; `find()` already caches absence, so probing two names is cheap.
2. Same for `InstitutionalCommissionBridge.API_CLASS`.
3. `OptionalModNamespaces.KNOWN`: add `mcakingdoms` (keep `ultima_kingdoms` while D2 holds).
4. `IntegrationRequirements.Integration.ULTIMA`: rename to `KINGDOMS`, display name "MCA: Kingdoms",
   mod id from the primary id. Grep for `Integration.ULTIMA` and `"ultima"` lowercase capability
   strings that reach datapacks (`compat_capability` provider ids); if a pack can name the provider
   as `ultima_kingdoms`, keep that spelling accepted.
5. `ConditionTypes:205` and `RewardTypes:59`: per D3, register `mcakingdoms:standing` /
   `mcakingdoms:faction_standing` and keep the old ids as aliases to the same codec.
6. `mods.toml`: dependency `modId="mcakingdoms"`, ordering NONE (as now); add a second optional
   entry for `ultima_kingdoms` only if D2 says so.
7. Rename the test that enforces no static link (`NoUltimaStaticLinkTest`) to check
   `dev/otectus/mcakingdoms/` and update every fixture string.
8. Update `CLAUDE.md`, `MODMAP.md` (the Known issues entry added by the review), `CAPITALS.md` or
   wherever "Ultima Kingdoms" appears in user docs.

Verify: `gradlew-quiet.sh MCAQuests check`; then a packaged dedicated server with Kingdoms, Quests and
MCA: `/mcaquests` offers `mcakingdoms:evolution/aid` from a guild contact after
`/kingdoms guild workshop` (needs tasks 4 and 6 for the full path), and the log shows no
"Optional Ultima integration failed closed".

### 1b. MCAReputation

Files: `src/main/java/dev/otectus/mcareputation/McaReputation.java`, `api/McaReputationApi.java`,
`McaReputationConfig.java`, `src/main/resources/META-INF/mods.toml`, `API.md`, `CONFIG.md`,
`MODMAP.md`.

Steps:

1. Add `KINGDOMS_MOD_ID = "mcakingdoms"`; in `integrationEnabled`, match either id (per D2) to the
   same switch.
2. Rename the config key to `enableKingdomsIntegration` and keep `enableUltimaKingdomsIntegration`
   as a read-through alias for one release (ForgeConfigSpec cannot alias; read both, OR them, and
   document the old key as deprecated), or keep the old key and only fix the comment. Decide with the
   user; the review recommends the alias.
3. `mods.toml`: `modId="mcakingdoms"`, `ordering="BEFORE"` (Kingdoms declares `mcareputation` AFTER,
   so this stays acyclic). Keep the `ultima_kingdoms` entry only under D2.
4. R-2: when `registerStandingConsumer` is called for `mcakingdoms:factions` and a durable cursor
   exists for `ultima_kingdoms:factions`, either forget the old one automatically or log one line
   telling the operator to run `/mcareputation standing consumers forget ultima_kingdoms:factions`.
   Automatic forget is safe because the old consumer never acknowledges again.

Verify: `gradlew-quiet.sh MCAReputation check`; with the switch off, Kingdoms'
`/kingdoms faction local_effect` is refused as DISABLED.

### 1c. MCAConversations

Files: `src/main/java/dev/otectus/mcaconversations/compat/KingdomBridge.java`, `compat/CivicBridge.java`,
`src/content/topics/guild_contact.json` (every `"integrations": ["ultima_kingdoms"]`), `mods.toml`,
`MODMAP.md`, and the tests `NoUltimaStaticLinkTest`, `KingdomGateSpecTest`, `TopicGateTest`,
`CivicBridgeTest`, `CivicContextSourceTest`, `CivicContentContractTest`, `ContentCompilerTest`,
`OptionalProfessionIsolationTest`.

Steps:

1. `KingdomBridge`: `MOD_ID` → id list; `GATE_API`, `FACTIONS_API` (`KingdomsFactionsApi`),
   `STANDING_SCOPE`, and the `McaCommunityRef` name at `:148-149` → `dev.otectus.mcakingdoms.api.…`.
2. `CivicBridge`: same for `MOD_ID` and `API`.
3. `guild_contact.json`: change `integrations` to `mcakingdoms` (the scene eligibility predicate
   tests a mod id). If D2 is accepted, the content schema needs either both ids in the list or an
   alias in the predicate; prefer the predicate so content stays single-valued.
4. Run `./gradlew generateConversationContent generateVoiceOverlays`, commit the regenerated
   `src/main/resources` output, and confirm `verifyGeneratedConversationContent` passes.
5. `mods.toml`: `modId="mcakingdoms"`, AFTER.
6. Mirror every change to the NeoForge port under `1.21.1 Ports/` as the family rules require, and
   update `docs/ROADMAP.md` / `AUDIT.md` open item lists if they mention Ultima.

Verify: `gradlew-quiet.sh MCAConversations check`, plus `verifyGeneratedConversationContent` and
`verifyVoiceOverlays`; in game, a guild contact shows the "An introduction through …" response card.

### 1d. MCACrime and the family docs

Files: `MCACrime/src/main/resources/META-INF/mods.toml`, `MCACrime/src/test/java/dev/otectus/mcacrime/
ModsTomlOrderingTest.java`, `MCAReputation/docs/FAMILY_COMPATIBILITY.md`, and every `CLAUDE.md` in
the family that says "Ultima Kingdoms" in the load-order rule.

Steps: `modId="mcakingdoms"` BEFORE in Crime; update the test assertion; update the family doc's rule 6
and tuple tables to the new name. No code path in Crime references the id.

Verify: `gradlew-quiet.sh MCACrime check`.

### 1e. Kingdoms side of I-1

Files: `src/test/java/dev/otectus/mcakingdoms/compat/ModsTomlRangesTest.java` (javadoc),
`docs/politics/compatibility-matrix.md:40`, `CHANGELOG.md` "Compatibility".

Steps: fix the javadoc, name the real Reputation switch, and add a changelog line stating the rename
and the companion versions that bind the new id. Depends on 1a–1d for the version numbers.

## 2. Save and config migration (K-1, D1)

Files: every `SavedData` class listed under K-1, `citizen/CivicIdentityStore.java`,
`McaKingdoms.java` (constructor and `serverAboutToStart`), new class
`persistence/LegacyRename.java`, `README.md` ("Upgrading from Ultima Kingdoms").

Design:

1. **SavedData files.** In `ServerAboutToStartEvent`, before any `computeIfAbsent`, for each known
   name pair (`ultima_kingdoms_settlements` → `mcakingdoms_settlements`, and the sixteen others), if
   `<world>/data/<new>.dat` is absent and `<old>.dat` exists, copy old → new with
   `AtomicSavedDataWriter` semantics (read NBT, write new file, fsync) and leave the old file in place.
   Do it once per world and log each copy. Never delete the old file.
2. **Slug namespaces.** `SettlementRecord.load` and `SettlementSnapshot` keep `ResourceLocation`
   slugs; on migration rewrite `Slug` and `RetiredSlugs` from `ultima_kingdoms:` to `mcakingdoms:` and
   record the old slug in `RetiredSlugs` so `findSettlement` still resolves it. Do the same for
   `kingdoms/*` ids inside records only if the datapack namespace also changed (it did:
   `data/ultima_kingdoms/...` → `data/mcakingdoms/...`), so `Kingdom` fields need the same rewrite.
   Check `PoliticalSavedData` (`governments` keys and `profileId`), `FactionSavedData`
   (`Kingdom` strings), `OrganizationSavedData` (organization ids such as `ultima_kingdoms:lamplighters`
   → `mcakingdoms:lamplighters`), `CivicSavedData` (`organization`), `InstitutionalCommissionData`
   (`organization`, `kingdom`, `honor`), `ControlState.Mapping.kingdom`, and the drama / evolution /
   protection payloads for the same strings. A single `String.replace("ultima_kingdoms:", "mcakingdoms:")`
   over the JSON payloads before parsing is the simplest correct approach for the Gson-backed stores;
   the NBT-backed ones (`SettlementSavedData`, `FactionSavedData`) need field-level rewrites.
3. **Entity NBT.** In `CivicIdentityStore.read`, if `mcakingdoms:civic_identity` is absent and
   `ultima_kingdoms:civic_identity` is present, read the old compound, rewrite `OriginKingdom`'s
   namespace, write it under the new key and remove the old. This migrates lazily as villagers are
   observed.
4. **Player flag.** In `playerLoggedIn`, treat `ultima_kingdoms:welcomed` as already welcomed.
5. **Config.** Document the file renames in the README; optionally copy `config/ultima_kingdoms-*.toml`
   → `config/mcakingdoms-*.toml` when the new file does not exist, before `registerConfig` (the
   constructor runs before configs load, so a copy there is early enough).
6. **Quest receipts in Quests' player data** are handled in task 4.

Tests: a unit test per Gson store that loads a fixture payload containing `ultima_kingdoms:` ids and
asserts the rewritten ids; a GameTest that writes `ultima_kingdoms_settlements.dat` into the test
world, starts, and finds the settlement by its old slug.

Verify: K-1 checklist item 3 now finds the village and `/kingdoms village info ultima_kingdoms:<slug>`
resolves.

Risks: double-migration if the server crashes between copy and first save (mitigated by only copying
when the new file is absent); packs that still ship `data/ultima_kingdoms/` datapack folders will
define duplicate kingdoms under the old namespace (document that they must be removed).

## 3. Townstead version policy (K-2, K-7, D5)

Files: `politics/GovernmentService.java:586-588, 602`, `politics/PoliticalProviderVersions.java`,
`src/main/resources/mcakingdoms-politics.properties`, `gradle.properties:28`,
`compat/townstead/TownsteadBinding.java`, `compat/townstead/TownsteadRuntime.java`, `README.md`,
`docs/politics/compatibility-matrix.md`.

Steps:

1. Replace the two version-string checks with capability checks on
   `KingdomsTownsteadApi.get(server).capabilities()` (`READ_VILLAGER` + `READ_ORIGIN` for adulthood,
   `READ_BUILDING` for buildings), which `TownsteadBinding` already probes member by member. Keep a
   one-line log at attach time naming the installed Townstead version and whether it is the
   validated one, instead of refusing.
2. K-7: split `TownsteadBinding` so `resolveBuildingList` (the MCA-only `READ_BUILDINGS` query) runs
   whenever an MCA root is present, independent of `installed`. `TownsteadRuntime.attach` then
   registers the reconciler when either Townstead or MCA buildings are readable. Audit
   `CivicService.place` and `activateSeed` so a site-based chapter can be `AVAILABLE` from
   `READ_BUILDINGS` alone when `READ_BUILDING` (the Townstead public API) is absent.
3. If the user prefers D5's alternative, instead narrow `townstead_version_range` to
   `[0.7.6,0.7.7)` and state the exact requirement in the README.
4. README: state plainly which features need Townstead and which need MCA only.

Verify: with Townstead absent and MCA present, a Lamplighters sponsor quest activates a chapter
(`/kingdoms guild chapters` lists it); with Townstead 0.8 the institution recognition task no longer
refuses on the version string (or, under the alternative, Forge refuses to launch with a clear range).

## 4. Completion-receipt consumer id (I-2, D4)

Files: Kingdoms `compat/quests/receipts/QuestCompletionBridge.java:20`; Quests
`api/McaQuestsApi.java:41-42`, `quest/QuestManager.java:119-120`, `state/CompletionReceiptOutbox.java`
(`load`), and a new migration in `state/PlayerQuestData` load.

Steps:

1. Choose the id per D4 and set it identically in all three constants. Consider exposing it from
   `McaQuestsApi` as a public constant so a future rename cannot split it again, and have Kingdoms
   read it reflectively with a fallback.
2. Quests: in `CompletionReceiptOutbox.load`, rewrite `ultima_kingdoms:regional_civic_network` to the
   new id in `consumers`, `subscriptions`, every entry's `intended_for` and `acknowledged_by`, and every
   tombstone's `intended_for`, merging duplicates (an entry intended for both ids after rewrite must
   keep one id and the union of acknowledgements). Bump `SCHEMA` to 4 so a downgrade is refused
   read-only as the existing future-schema path already does.
3. Add a unit test to `CompletionReceiptOutboxTest` with a schema-3 fixture that contains the old id and
   assert the rewritten cohort and that `status()` returns `ready`.

Depends on task 1a. Verify: checklist item 2; after ten institutional completions the player's outbox
has no un-acknowledged entries.

## 5. Language keys (K-3)

Files: `src/main/resources/assets/mcakingdoms/lang/en_us.json`, `interaction/EvolutionTasks.java:279`,
`interaction/CivicTasks.java:26`, `compat/quests/McaQuestsCompat.java:140`.

Keys to add (all found by diffing string literals against the lang file):

`condition.mcakingdoms.kingdom`; `organization.active_source_membership_required`,
`organization.charter_authority_required`, `organization.dissolve_authority_required`,
`organization.dissolved`, `organization.founded`, `organization.id_reserved`,
`organization.invalid_charter`, `organization.lifecycle_capacity`, `organization.lifecycle_inactive`,
`organization.lifecycle_unavailable`, `organization.merge_already_applied`,
`organization.merge_already_opted_out`, `organization.merge_already_pending`,
`organization.merge_authority_required`, `organization.merge_closed`, `organization.merge_consented`,
`organization.merge_not_pending`, `organization.merge_opted_out`, `organization.merge_party_unavailable`,
`organization.merge_pending_consent`, `organization.merge_unavailable`, `organization.merged`,
`organization.obligation_novated`, `organization.obligation_unavailable`,
`organization.obligations_unsettled`, `organization.state_changed`, `organization.template_unavailable`;
`progression.codex_unavailable`, `progression.matches`, `progression.not_ready`,
`progression.provider_absent`, `progression.provider_unsupported`, `progression.provider_unavailable`,
`progression.read_failed`, `progression.requirement_not_met`, `progression.skill_unavailable`.

(`organization.dissolve`, `organization.found`, `organization.merge_novate`, `organization.merge_propose`,
`organization.lifecycle_inspect`, `organization.lifecycle_overview` are task ids, not keys; `text.empty`,
`text.long`, `kingdom.list`, `kingdom.inspect`, `warfare.declare`, `warfare.accord`,
`warfare.contract`, `warfare.muster_nearby`, `key.categories.misc` are also not missing keys.)

Then make `organizationResult` compose a `Component` whose reason part is `ActionRegistry.tr(key,
fallback)` rather than string concatenation, so translations apply (the result pipeline already
accepts a `Component`-bearing paragraph through `PlayerWords.component`). Same for the
`ProgressionResult.reason` path.

Verify: `check_mod.py` stays clean; the K-3 checklist item shows readable text; add a unit test that
asserts every `organization.*` and `progression.*` literal in `src/main/java` exists in `en_us.json`
(the review's diff script is the model).

## 6. Petitions and honors (K-4, K-5)

Files: `politics/GovernmentService.java:684, 710-714, 826`,
`data/mcakingdoms/mcakingdoms/petitions/commission_petition.json`,
`data/mcakingdoms/mcakingdoms/honors/*.json`, `data/mcakingdoms/mcakingdoms/kingdoms/*.json`.

Steps:

1. K-4: either implement `commission_petition` (approval opens a curated commission through
   `QuestCompletionBridge.openCommissions` for the petitioner, mirroring `CivicService.commissions`)
   or remove the definition and the `:684` branch. Remove or compute the `:826` overview row from
   `QuestCompletionBridge.status` so it reflects the real provider state.
2. K-5: add `"petition_honor": "<honor id>"` to each kingdom definition's `metadata` (the parser
   already keeps a string map) and read it in `approvePetition`, falling back to `open_bell` only when
   the metadata is absent. Update the datapack docs in the README.

Verify: GameTest for an honor petition on a datapack kingdom with a custom honor; a petition of the
commission kind either opens the menu or is no longer offered.

## 7. Contact range (K-6)

Files: `civic/CivicConfig.java`, `civic/CivicService.java:158-161`, `interaction/BasicTargets.java:17`,
`interaction/InteractionNetwork.java:202`, `warfare/contracts/CivilianContractService.java:113`,
`evolution/EvolutionContracts.java:45`.

Steps: raise `contactRange`'s maximum to 32, make the `npc`/`person` selectors use
`CivicConfig.CONTACT_RANGE.get()` for guild and contract targets, and have the two contract validators
read the same value. Note Crime's workshop query keeps its own 8-block rule; say so in the GUI hint
for the workshop task.

Verify: the K-6 checklist item either lists no out-of-range contact or accepts the listed one.

## 8. Small fixes (K-9 … K-14)

- K-9: drop the `mutation` field from `PoliticalNetwork.Query` (bump that channel's protocol to "2").
- K-10: when nothing is selected, open the Kingdom Tasks "Government" search instead of a fixed id.
- K-11: in `EvolutionService.tick`, commit only when a scenario, region set, cursor-dependent digest
  or subscription changed; keep `nextEvaluation` in memory between saves (it is already recomputed
  from `clock` on load).
- K-12: delete `pack/r1`–`r3/providers/**` or move them to `docs/history/` with a README line.
- K-13: map `NumberFormatException`/`IllegalArgumentException` from field parsing to
  `interaction.mcakingdoms.*` keys in `InteractionNetwork.reason`.
- K-14: add `[[mixins]] config="mcakingdoms.mixins.json"` to `mods.toml` so dev runs and
  `modmap.py` see the config (the manifest attribute can stay), and note in `README.md`
  "Development" that warfare needs Recruits 1.15.2 present for the hooks to apply.

## 9. Verification order and commands

1. Per repo during iteration: `.mcmod-tools/gradlew-quiet.sh <repo> compileJava`.
2. Kingdoms: `gradlew-quiet.sh MCAKingdoms check` (unit tests and `verifyApiJar`);
   `python3 tools/test/run_gametests.py` (needs the migration GameTest from task 2);
   `python3 .mcmod-tools/check_mod.py MCAKingdoms`.
3. Quests: `gradlew-quiet.sh MCAQuests check`; Reputation: `check`; Conversations: `check`,
   `verifyGeneratedConversationContent`, `verifyVoiceOverlays`; Crime: `check`.
4. Packaged dedicated server (the only runtime that applies the mixins and real MCA): build every jar,
   then run `python3 tools/test/integration_runtime.py` phases `initial`, `civic-loop`, `r2`, `r3`,
   `r4` with the new jars, and the manual checklist in `REVIEW.md` section 6 items 1, 2, 3, 5, 6, 7.
5. Update `MCAReputation/docs/FAMILY_COMPATIBILITY.md` with the new verified tuple and the rename
   note, and clear the Known issues entries added by the review from each `MODMAP.md` as they close.

## 10. Dependencies and risks

- Task 1 before 4; task 2 before any upgrade test; task 3 before any R2 runtime phase without
  Townstead 0.7.6.
- Risk: D2's dual-id acceptance means `OptionalModNamespaces` treats a `ultima_kingdoms:` type as
  "optional mod absent" even when Kingdoms is present under the new id. Mitigate by making
  `isAbsentOptionalMod` consult the alias map.
- Risk: task 2's string rewrite over Gson payloads can touch user-authored text containing
  `ultima_kingdoms:` (an explanation string). Acceptable; note it in the changelog.
- Risk: task 4's schema bump makes the Quests player data unreadable to older Quests builds; the
  existing future-schema fence already turns that into a read-only refusal rather than data loss.
- Unresolved: whether MCA 7.7.1 should be runtime-validated in the same pass (checklist item 11); it
  is independent of the rename but the family doc lists it as still open.
