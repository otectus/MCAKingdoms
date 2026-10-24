# MCA: Kingdoms review and integration audit

Date: 2026-10-01. Target: Minecraft 1.20.1, Forge 47.x, Java 17. Reviewed tree: the uncommitted
working tree on `main` after commit `3a9538b` (the `ultima_kingdoms` → `mcakingdoms` rename), version
0.1.1. Companion trees: MCAQuests 1.7.1, MCAReputation 0.6.1, MCAConversations 1.8.0 (and MCACrime
0.7.5 where it crosses the same seams). This is a review, not a fix pass; no code was changed. The
only files written are this report, `docs/MCAKingdoms-Review-Remediation-Plan.md`, and the Known
issues sections of the four `MODMAP.md` files.

Confidence labels: **Confirmed** (traced in code), **Likely** (strong evidence, needs a test),
**Needs in-game test**. Location labels: Kingdoms, Quests, Reputation, Conversations, Crime,
Integration (the seam between two mods), Upstream (MCA Reborn).

## 1. What was run

| Check | Command | Result |
|---|---|---|
| Resource lint, all four | `python3 .mcmod-tools/check_mod.py <project>` | 0 errors, 0 warnings, 0 notes for mcakingdoms, mcaquests, mcareputation, mcaconversations |
| Registration map | `python3 .mcmod-tools/modmap.py MCAKingdoms` | wrote `MODMAP.md` (2 items, 1 argument type); the three add-ons already had one |
| Compile | `.mcmod-tools/gradlew-quiet.sh MCAKingdoms compileJava` | PASS (7s), log `/tmp/gradle-MCAKingdoms-compileJava-20261001-175546.log` |
| Unit tests | not re-run; `build/test-results/test` from the 2026-10-01 09:58 build | 135 tests, 0 failed, 1 skipped |
| API existence | `.mcmod-tools/find_api.py --project MCAKingdoms …` | Forge symbols resolved (`TickEvent#getServer`, `OnDatapackSyncEvent#getPlayerList`); vanilla symbols could not be resolved because no mapped Minecraft sources jar is in the Gradle cache, see section 8 |

Nothing was launched. No GameTest, client harness or packaged-server phase was run in this pass.

## 2. Summary: top five issues by impact

1. **Every add-on still binds the old identity.** Quests, Reputation, Conversations and Crime check
   `ModList.isLoaded("ultima_kingdoms")` and load `com.ultimakingdoms.api.*` classes by name; Kingdoms
   now registers as `mcakingdoms` under `dev.otectus.mcakingdoms.api` and renamed three facade classes.
   Every reflective bridge fails closed, so kingdom gates, faction standing rewards, institutional
   commissions, civic contacts and the Reputation integration switch are all inert (I-1, Critical,
   Confirmed).
2. **No migration from `ultima_kingdoms` worlds.** All seventeen `SavedData` names, the villager civic
   identity NBT key, the player welcome flag and every config file name changed, and nothing reads the
   old names. An existing world loads with no settlements, governments, factions or receipts and starts
   over, while the old files sit untouched beside the new ones (K-1, Critical, Confirmed).
3. **The Quests completion-receipt consumer id was split by the rename.** Kingdoms polls and acks as
   `mcakingdoms:regional_civic_network`; Quests still renews and requires
   `ultima_kingdoms:regional_civic_network`. Once I-1 is repaired, every institutional completion
   freezes a receipt owed to a consumer that never acks, the per-player outbox fills at 256 entries and
   every quest completion for that player is refused (I-2, High, Confirmed by trace).
4. **Political and institutional NPC evidence requires exactly Townstead `0.7.6+1.20.1`** by version
   string, while `mods.toml` admits `[0.7.6,0.9)` and the 0.1.1 changelog says the bridges fail closed
   on API drift. With Townstead 0.8 installed, offices for NPCs, institution recognition, workshop
   commissions and civic chapters all refuse (K-2, High, Confirmed).
5. **The civic network silently depends on Townstead being installed at all**, because the building
   capabilities it needs are only bound when Townstead is loaded even though they read MCA classes,
   and the README presents Townstead as optional (K-7, Medium, Confirmed). Close behind: 29
   organization lifecycle reasons and the Quests condition description are shown as raw language keys
   (K-3).

## 3. Findings by severity

### Critical

**I-1. Add-on bridges bind the pre-rename mod id, package and class names.** Integration (Quests,
Reputation, Conversations, Crime). Confirmed.

Kingdoms side: `src/main/java/dev/otectus/mcakingdoms/api/McaKingdomsApi.java:11` sets `MOD_ID =
"mcakingdoms"`; `gradle.properties:31-34` changed `mod_id`, `mod_group_id`; the facades are now
`api/factions/KingdomsFactionsApi.java`, `api/factions/KingdomsFactionsService.java`,
`api/townstead/KingdomsTownsteadApi.java` (formerly `UltimaFactionsApi`, `UltimaFactionsService`,
`UltimaTownsteadApi`).

Add-on side, all still pointing at the old names:

- MCAQuests `src/main/java/dev/otectus/mcaquests/compat/KingdomIntegration.java:36` (`MOD_ID =
  "ultima_kingdoms"`), `:44-55` (`com.ultimakingdoms.api.gating.KingdomGateApi`,
  `…factions.UltimaFactionsApi`, `…factions.UltimaFactionsService`, `…townstead.UltimaTownsteadApi`,
  and five more); `quest/InstitutionalCommissionBridge.java:31`
  (`com.ultimakingdoms.api.civic.InstitutionalCommissionApi`); `quest/IntegrationRequirements.java:116`
  (`ULTIMA(KingdomIntegration.MOD_ID, …)`); `src/main/resources/META-INF/mods.toml` dependency
  `modId="ultima_kingdoms"`.
- MCAReputation `src/main/java/dev/otectus/mcareputation/McaReputation.java:31`
  (`ULTIMA_KINGDOMS_MOD_ID = "ultima_kingdoms"`), `api/McaReputationApi.java:104-106`, `mods.toml:75-80`.
- MCAConversations `src/main/java/dev/otectus/mcaconversations/compat/KingdomBridge.java:25-28` and
  `:148-149`; `compat/CivicBridge.java:19-20`; `src/content/topics/guild_contact.json:63-65, 203-205,
  353-355, 489-491` (`"integrations": ["ultima_kingdoms"]`), compiled into
  `src/main/resources/data/mcaconversations/conversation_catalog/topics.json` and the scene files;
  `mods.toml` dependency `modId="ultima_kingdoms"`.
- MCACrime `src/main/resources/META-INF/mods.toml:74-80` and
  `src/test/java/dev/otectus/mcacrime/ModsTomlOrderingTest.java:57` (ordering only; Crime's own API is
  consumed by Kingdoms through class names that did not change, so Kingdoms → Crime still works).

Traced consequences:

- `KingdomIntegration.available()` is false, so `IntegrationRequirements.requirementsMet` excludes
  every definition that needs the `ULTIMA` integration: any `kingdom_lifecycle` that is not
  `offer_only` with `when_unknown: allow`, any `faction_standing` reward, any
  `institutional_commission` (`IntegrationRequirements.java:98-100, 366, 377-378, 548`). That covers the four
  quests Kingdoms ships under `data/mcakingdoms/mcaquests/quests/evolution/` (all have
  `"institutional_commission": true`) and every R1–R3 pack quest that carries
  `mcaquests:institutional_service_available`.
- Quests' `openInstitutionalCommissionMenu` validates through the missing class and refuses; Kingdoms'
  `CivicService.workshop` (`civic/CivicService.java:297-299`) and `CivilianContractService.offerScoped`
  (`warfare/contracts/CivilianContractService.java:92-95`) commit an OFFERED contract first, then
  report `civic.commissions_unavailable` / `warfare.quest_provider_unavailable`. The offers expire and
  are pruned on the next `put`, so this is noise rather than a leak.
- Conversations: `TopicGate.allows` (`conversation/TopicGate.java:41-44`) hides every `civic_contact`
  topic because `CivicBridge.isAvailable()` is false, `SceneEligibility` (`scene/SceneEligibility.java:95-99`)
  refuses every scene whose `integrations` lists `ultima_kingdoms`, and `KingdomBridge.allows` returns
  `gate.allowsWhenUnavailable()` for every `kingdom_gate`, so only `when_unknown: allow` content opens.
- Reputation: see R-1.
- Kingdoms → add-ons still works, because Kingdoms binds `dev.otectus.mcaquests.api.McaQuestsApi`,
  `dev.otectus.mcareputation.api.McaReputationApi`, and `dev.otectus.mcacrime.api.*` by their unchanged
  names. The integration is therefore half-alive: Kingdoms registers its condition, polls receipts,
  reads standing and registers a jurisdiction provider, but nothing flows back.

Fix direction: update the string constants and class names in the three add-ons (plus Crime's
`mods.toml` and test), regenerate Conversations' compiled content, and decide whether to accept both
ids for one release (plan D2).

**K-1. No save, NBT or config migration from `ultima_kingdoms`.** Kingdoms. Confirmed.

- `SavedData` names all carry the new prefix and nothing reads the old one:
  `core/SettlementSavedData.java:20` (`mcakingdoms_settlements`), `factions/FactionSavedData.java:24`,
  `knowledge/SettlementKnowledge.java:17`, `politics/PoliticalSavedData.java:17`,
  `civic/CivicSavedData.java:14`, `civic/InstitutionalCommissionData.java:14`,
  `compat/quests/receipts/QuestReceiptData.java:14`, `warfare/CampaignSavedData.java:11`,
  `warfare/ControlSavedData.java:12`, `warfare/contracts/CivilianContractData.java:16`,
  `factions/organization/OrganizationSavedData.java:19`, `evolution/EvolutionSavedData.java:12`,
  `evolution/drama/DramaSavedData.java:12`, and the protection, transfer, mobilization and world-context
  stores. `grep -rn ultima_kingdoms src/main` returns nothing, so there is no rename-on-load.
- Villager civic identity lives in entity `ForgeData` under `citizen/CivicIdentityStore.java:16`
  (`mcakingdoms:civic_identity`); every villager's origin and residence is dropped.
- The first-login flag `McaKingdoms.java:187-188` (`mcakingdoms:welcomed`) resets, so every returning
  player gets the welcome line again (cosmetic).
- Config files: `McaKingdoms.java:50-53`, `presentation/Presentation.java:20-21, 29-30`,
  `factions/config/FactionConfig.java:50-51`, `compat/townstead/TownsteadIntegrationConfig.java:47-48`,
  `integration/IntegrationConfig.java:8` all write `mcakingdoms-*.toml`; operator settings in the old
  files are silently abandoned (defaults regenerate).
- Old settlement slugs are `ultima_kingdoms:<name>`; `core/KingdomsServiceImpl.java:187-188` and
  `knowledge/SettlementKnowledge.java:121` prefix bare queries with `mcakingdoms:`, so even a hand-copied
  save would not resolve old slugs by short name (aliases and display names still match).
- Network channels (`network/NetworkHandler.java:30`, `interaction/InteractionNetwork.java:63`,
  `politics/PoliticalNetwork.java:16`, `warfare/WarfareNetwork.java:30`, `civic/CivicNetwork.java:15`,
  `compat/townstead/network/TownsteadCivicNetwork.java:28`) changed name; that is expected for a rename
  and only matters for mixed-version client/server pairs.

Impact: upgrading a world that ran Ultima Kingdoms 0.1.1 (the family compatibility doc records such a
dedicated server) silently restarts every Kingdoms system. Fix direction: a one-time load-time migration
that reads `ultima_kingdoms_*.dat` when `mcakingdoms_*.dat` is absent, rewrites slug namespaces,
copies the old `ForgeData` key on first entity observation, and either reads the old config files or
documents the manual step. See plan D1.

### High

**I-2. Quests and Kingdoms disagree on the institutional completion-receipt consumer id.** Integration
(Quests + Kingdoms). Confirmed by trace; the runtime symptom is Likely until the bindings are repaired.

- Quests `api/McaQuestsApi.java:41-42` (`INSTITUTIONAL_COMPLETION_CONSUMER =
  ultima_kingdoms:regional_civic_network`) and `quest/QuestManager.java:119-120`
  (`INSTITUTIONAL_RECEIPT_CONSUMER`, same value).
- Kingdoms `compat/quests/receipts/QuestCompletionBridge.java:20` (`CONSUMER =
  mcakingdoms:regional_civic_network`).
- Flow once I-1 is fixed: `CivicService.validate` (`civic/CivicService.java:329`) and
  `CivilianContractService.validate` (`:156`) call `ensureInstitutionalRecipient`, which invokes
  `renewInstitutionalCompletionConsumer` (`McaQuestsApi.java:195-206`). That calls
  `readCompletionReceipts(ultima_kingdoms:…)`, and `CompletionReceiptOutbox.read`
  (`state/CompletionReceiptOutbox.java:97-103`) registers a lease for that id. At turn-in,
  `QuestManager.java:1286-1299` requires that id to be active and freezes the receipt's cohort from
  `activeConsumers`, which now contains both ids. Kingdoms acks only its own id. `prune` (`:186-199`)
  and `retireAcknowledgedUnderPressure` (`:201-213`) retire only fully acknowledged entries, so each
  institutional completion leaves a permanent entry; at `MAX_RECEIPTS = 256` the status is `full`,
  `canCaptureCompletionReceipt` is false, and `QuestManager.java:1294-1299` refuses every completion
  for that player, institutional or not, because `shouldCaptureCompletionReceipt` is true whenever
  Kingdoms' poller holds an active lease.
- Existing saves already contain entries intended for `ultima_kingdoms:regional_civic_network`; those
  can never be acknowledged by the renamed consumer and count toward the same cap.

Fix direction: one shared id in both mods (either keep `ultima_kingdoms:regional_civic_network` for
compatibility or change both to `mcakingdoms:…`), and a Quests-side migration that renames the consumer
in persisted `intended_for`, `acknowledged_by`, `consumers` and `subscriptions` lists.

**K-2. Political NPC evidence and institution recognition require an exact Townstead version string.**
Kingdoms. Confirmed.

- `politics/GovernmentService.java:586-588` (`person`, NPC branch) and `:602` (`building`) compare the
  installed Townstead version with `PoliticalProviderVersions.TOWNSTEAD`
  (`politics/PoliticalProviderVersions.java:7-16`, expanded from `gradle.properties:28`,
  `politics_townstead_version=0.7.6+1.20.1`).
- `gradle.properties:17` admits `townstead_version_range=[0.7.6,0.9)`; `CHANGELOG.md` 0.1.1 says the
  upper bounds were dropped because "every one of those bridges already fails closed on API drift".
  This bridge does not probe the API; it refuses on the version string.
- Everything that passes through `building()` or an NPC `person()` is refused with Townstead 0.8.x:
  `RECOGNIZE`, `REVALIDATE`, institution petitions, NPC office appointments, `approvePetition`, and in
  turn `CivicService.workshop` / `validate` (institutions never become `operational`), chapter charters
  and R3 civilian contracts (`institutionAt` finds no operational institution).
- `docs/politics/compatibility-matrix.md:29` documents the exact-version rule, but the README and
  changelog do not.

Fix direction: either probe the Townstead API surface (`TownsteadBinding` already does) and drop the
string check, or pin the range to what the string check accepts, and say so in the README.

### Medium

**R-1. Reputation's integration switch no longer gates Kingdoms.** Reputation. Confirmed.

`api/McaReputationApi.java:104-106` compares the request source namespace with
`McaReputation.ULTIMA_KINGDOMS_MOD_ID` (`McaReputation.java:31`, `"ultima_kingdoms"`); Kingdoms now
sends `mcakingdoms:mca_reputation_sync` (`compat/reputation/ReputationBridge.java:126`,
`ReputationOutboxBridge.java:22`, `factions/FactionServiceImpl.java:166`). Unknown namespaces are
"always accepted" (`:108`), so `enableUltimaKingdomsIntegration=false`
(`McaReputationConfig.java:324-328`) no longer refuses Kingdoms' `deliverStandingEffect` writes.
Kingdoms' own `docs/politics/compatibility-matrix.md:40` names the switch
`enableMCAKingdomsIntegration`, which does not exist. Fix direction: match the namespace to
`mcakingdoms` (keep the old one as a second match for one release) and rename or alias the config key.

**R-2. A dead `ultima_kingdoms:factions` standing-journal consumer persists in Reputation saves.**
Integration (Reputation + Kingdoms). Likely.

`ReputationOutboxBridge.java:21` registers `mcakingdoms:factions`. `state/StandingOutbox.java:52-58`
registers a new consumer at the current tail (so no history replay, which is correct), and `:61-62`
says `unregister` keeps the durable cursor. The old consumer's cursor therefore stays in
`mcareputation.dat`, is listed by `/mcareputation standing consumers`, and holds the journal's trim
point until the `standingJournalMaxEntries` bound lapses it (`:157-165`). No data is lost, but the
journal is retained at its maximum until an operator runs `forget`. Fix direction: migration note or
an automatic `forget` of the old id when the new one registers.

**Q-1. `OptionalModNamespaces` does not know `mcakingdoms`.** Quests. Confirmed.

`data/OptionalModNamespaces.java:27-29` lists `ultima_kingdoms` but not `mcakingdoms`. Kingdoms
registers its quest condition as `mcakingdoms:kingdom` (`compat/quests/McaQuestsCompat.java:34-35`).
A pack that uses it loads as an error, not an "excluded, optional mod absent" summary, on a server
without Kingdoms (`:21-23` explains that this is deliberate for unknown namespaces). Fix direction:
add `mcakingdoms` (keep `ultima_kingdoms` while packs may still carry it).

**K-3. Player-facing reasons and a condition description have no language entries.** Kingdoms.
Confirmed.

- `compat/quests/McaQuestsCompat.java:140` describes the condition with
  `condition.mcakingdoms.kingdom`; the key is absent from `assets/mcakingdoms/lang/en_us.json`, so the
  Quests journal shows the raw key.
- 29 `organization.*` reasons returned by `factions/organization/OrganizationServiceImpl.java` (for
  example `organization.merge_pending_consent` `:376`, `organization.charter_authority_required` `:351`,
  `organization.obligations_unsettled` `:413`, `organization.dissolved` `:455`,
  `organization.state_changed` `:480`) are absent. `interaction/EvolutionTasks.java:279` concatenates
  `result.reason()` into the result text and `PlayerWords.component` only translates whole paragraphs
  that are bundled keys, so Kingdom Tasks prints the raw key.
- `compat/progression/ProgressionBridge.java:42, 49, 55, 60, 63` return `progression.*` reasons that
  `interaction/CivicTasks.java:26` passes through `PlayerWords.text`, which falls back to the key.
- Diff method: every `"<prefix>.<path>"` string literal in `src/main/java` against the lang file;
  the full list is in the remediation plan.

**K-4. The shipped `commission_petition` definition can never be submitted.** Kingdoms. Confirmed.

`data/mcakingdoms/mcakingdoms/petitions/commission_petition.json` is loaded and offered as a petition
definition, but `politics/GovernmentService.java:684` throws "Commission provider completion receipts
are unavailable; sponsorship is disabled" for it unconditionally. `GovernmentService.java:826` also
prints a fixed overview row saying commission completion receipts are unsupported, although
`compat/quests/receipts/QuestCompletionBridge` implements exactly that. Fix direction: implement the
petition through the receipt bridge or remove the definition and the row.

**K-5. Honor petitions map kingdoms to honors by a hardcoded switch.** Kingdoms. Confirmed.

`politics/GovernmentService.java:710-714` chooses `winter_record`, `living_craft`, `tide_archive`,
`open_road`, `greenwood` for the five named kingdoms and `open_bell` for everything else. Kingdoms and
honors are datapack definitions (`README.md` "Datapacks"), so a pack-added kingdom always awards
Serenum's honor. Fix direction: read the honor id from the kingdom definition's metadata or the
petition definition.

**K-6. Contact range is defined five ways and the GUI offers contacts the service rejects.**
Kingdoms. Confirmed.

`civic/CivicConfig.java:15` (`contactRange`, default 8, maximum 16) is what `CivicService.near`
(`civic/CivicService.java:158-161`) enforces; `interaction/BasicTargets.java:17` lists NPCs within 32
blocks; `interaction/InteractionNetwork.java:202` shows locations within 32; `warfare/contracts/
CivilianContractService.java:113` and `evolution/EvolutionContracts.java:45` use 144 (12 blocks);
Crime's `InstitutionalServiceApi.java:48` uses 64 (8 blocks). A player who picks a contact 20 blocks
away gets `civic.contact_unavailable` after a full review page. Fix direction: one shared constant or
config value used by the selectors and every validator.

**K-7. The civic network requires Townstead to be installed, and the README does not say so.**
Kingdoms. Confirmed.

`compat/townstead/TownsteadRuntime.java:30` sets `installed = ModList.isLoaded("townstead") && …`;
`compat/townstead/TownsteadBinding.java:50-56` returns before binding anything when `installed` is
false, including `READ_BUILDINGS`, whose implementation (`:199-215`) reads only MCA's
`VillageManager`/`Village`/`Building`. `civic/CivicService.java:110-121` (`activateSeed`, which needs `buildings()` to return a family match) and
`:143-150` (`place`, status `PROVIDER_UNAVAILABLE` without `READ_BUILDING`) therefore never activate an
authored contact or mark a site-based chapter operational without Townstead; institution-backed
chapters are blocked by K-2's `building()` as well. `docs/R1-Civic-Foundation.md:11` says an NPC
"needs a real, loaded Townstead workshop"; `README.md` "Optional integrations" presents Townstead as an
adapter that can be disabled. Fix direction: either bind the MCA building query independently of
Townstead or state the hard requirement in the README and the integrations task.

**K-8. Documentation drift from the rename.** Kingdoms. Confirmed.

`docs/politics/compatibility-matrix.md:40` names a Reputation key that does not exist (see R-1);
`src/test/java/dev/otectus/mcakingdoms/compat/ModsTomlRangesTest.java:79-83` claims "MCA: Crime
declares mcakingdoms BEFORE" while Crime declares `ultima_kingdoms`; `CHANGELOG.md` "Compatibility"
says every reflective binding in both directions matches, which is no longer true after the rename.

### Low

**K-9. The legacy politics channel still deserializes a client-supplied mutation it ignores.**
Kingdoms. Confirmed. `politics/PoliticalNetwork.java:25-27` parses up to 4096 characters of
client JSON into `Politics.Request` on the network thread; `:38` discards it. Remove the field or
stop decoding it.

**K-10. Ledger "Kingdom" button falls back to a hardcoded kingdom id.** Kingdoms. Confirmed.
`client/VillageLedgerScreen.java:71` uses `mcakingdoms:serenum` when nothing is selected and no filter
is set; packs that remove Serenum open an empty government page.

**K-11. World evolution saves every evaluation even when nothing changed.** Kingdoms. Confirmed.
`evolution/EvolutionService.java:233` advances `nextEvaluation` and `:256` commits through
`AtomicSavedDataWriter` (fsync and atomic move) every `interval` ticks (default 1200) while enabled.

**K-12. Stale provider patches under `pack/`.** Kingdoms. Confirmed. `pack/r1`–`r3` providers still
carry `ultima_kingdoms` and `com.ultimakingdoms` in 24 files (62 lines), while `CHANGELOG.md`
"Compatibility" says Quests 1.7.0 and Conversations 1.8.0 ship the provider halves natively. Either
delete the patch trees or mark them historical.

**K-13. Error replies echo raw Java exception text.** Kingdoms. Confirmed.
`interaction/InteractionNetwork.java:133-135` turns any `Exception` message (for example
`NumberFormatException: For input string: "x"`) into the player-facing detail.

**K-14. `modmap.py` reports "Mixins: no" and dev runs never apply the Recruits mixins.** Kingdoms /
tooling. Confirmed. The mixin config is declared only through the jar manifest (`build.gradle:191`),
not `[[mixins]]` in `mods.toml`, so the tool does not see it and a `runServer`/GameTest run has no
manifest; `compat/recruits/RecruitsMilitary.java:84, 108-115` then refuses every warfare write with
"Verified native military security hooks unavailable" in dev. Production jars are unaffected (the audit's
packaged military phase passed).

**R-3 / C-2. Dead `BEFORE` ordering entries.** Reputation `mods.toml:75-80` and Crime `mods.toml:74-80`
order `ultima_kingdoms` BEFORE; Quests' `NONE` and Conversations' `AFTER` name the same id. None of
these apply to `mcakingdoms`. There is no cycle today because Kingdoms declares every companion
`AFTER`; the entries and `ModsTomlOrderingTest` are simply stale. Confirmed.

## 4. Integration matrix

Columns are the mod that owns or consumes the touchpoint. "works" means the trace completes with the
current trees; "broken" means it fails closed today; "missing" means no code path exists.

| Touchpoint | Kingdoms | Quests | Reputation | Conversations |
|---|---|---|---|---|
| `mods.toml` dependency on the other mod | works (`mcaquests`, `mcareputation`, `mcacrime` AFTER) | broken (declares `ultima_kingdoms`) | broken (declares `ultima_kingdoms` BEFORE) | broken (declares `ultima_kingdoms` AFTER) |
| Presence check (`ModList.isLoaded`) | works | broken (`KingdomIntegration:36`) | broken (`McaReputationApi:104`) | broken (`KingdomBridge:25`, `CivicBridge:19`) |
| Kingdom gate evaluation (`KingdomGateApi.testJson/testNamed/resolveSnapshot/…`) | works (signatures unchanged) | broken (class name) | n/a | broken (class name) |
| Faction standing read/write (`KingdomsFactionsApi.get`, `matches`, `getLocalStanding`, `apply`, `flushStandingChanges`) | works | broken (`UltimaFactionsApi` name) | n/a | broken (`UltimaFactionsApi` name) |
| Quest condition `mcakingdoms:kingdom` registered into Quests | works (`McaQuestsCompat`) | works at parse; `OptionalModNamespaces` lacks the namespace (Q-1) | n/a | n/a |
| Native `ultima_kingdoms:standing` condition and `ultima_kingdoms:faction_standing` reward | n/a | broken (bridge absent); ids unchanged | n/a | n/a |
| Institutional commissions (`InstitutionalCommissionApi.validate/accepted/cancelled`) | works | broken (`InstitutionalCommissionBridge:31`) | n/a | n/a |
| Completion receipts (`readCompletionReceipts`, `acknowledge…`, `renewInstitutionalCompletionConsumer`) | works (polls `mcakingdoms:regional_civic_network`) | broken: consumer id split (I-2) | n/a | n/a |
| Civic contact context and actions (`CivicNetworkApi.speakerContext/requestIntroduction/requestCommissions`) | works | n/a | n/a | broken (`CivicBridge:20`); `civic_contact` topics hidden |
| Townstead building facade for civic buildings (`KingdomsTownsteadApi`, `buildingAt`, `bind`, `recover`) | works only with Townstead installed (K-7) | broken (`UltimaTownsteadApi` name) | n/a | n/a |
| Local standing read (`McaReputationApi.getScore`, `standingBaselines`) | works | n/a | works | n/a |
| Local standing effect delivery (`deliverStandingEffect`, source `mcakingdoms:mca_reputation_sync`) | works | n/a | works, but the integration switch no longer gates it (R-1) | n/a |
| Standing journal consumer (`registerStandingConsumer` …) | works (`mcakingdoms:factions`) | n/a | works; old consumer left behind (R-2) | n/a |
| Crime workshop legality and jurisdiction provider | works (class names unchanged) | n/a | n/a | n/a |
| Atlas map points (`publishExternalMapPoints`, owner `mcakingdoms`) | works | works (map-shaped input) | n/a | n/a |
| Shared identity: settlement UUID, `mca` external ref `<dimension>#<villageId>`, player UUID | works; survives restart via `mcakingdoms_settlements` | works (frozen `KingdomBindingSnapshot`) | works (`CommunityKey(dimension, villageId)`) | works (`McaCommunityRef(dimension, villageId)`) |
| Identity after villager death / zombie conversion | works (`LivingConversionEvent.Post` copy); death discards as designed | n/a | n/a | n/a |
| Network channel or packet id collisions | none: all channels are `mcakingdoms:*` | none | none | none |
| Lang key collisions | none found; keys are `*.mcakingdoms.*` or `civic.`/`organization.`/`warfare.`/`progression.` prefixes used only here | none | none | none |
| Config key collisions | none; separate files | none | key name `enableUltimaKingdomsIntegration` is now misleading | none |
| Load order | works (consumer loads last) | stale entry | stale entry | stale entry |

Load scenarios:

- Kingdoms alone: all internal systems load; nothing in the review suggests a crash. Civic chapters and
  institutions are inert without Townstead (K-7).
- Kingdoms + each add-on, or all four together: Kingdoms-side bridges attach and log success; every
  add-on-side bridge logs "absent" (they test the old id) and falls back. Nothing crashes; features are
  silently missing (I-1).
- Each add-on without Kingdoms: unchanged from before the rename; they already degrade. The only new
  wrinkle is Q-1.
- Dedicated server only: no client class is reachable from common code (grep over
  `net.minecraft.client` outside `client/` is empty; `Presentation.java:31` uses
  `DistExecutor.safeRunWhenOn`). Nothing found that differs between singleplayer and dedicated
  beyond `SettlementKnowledge.adopt` writing the discovery file at startup on both.

## 5. Incompleteness list

- `commission_petition` definition with no fulfilment path (K-4); the overview row that calls receipts
  unsupported (K-4).
- Honor petition mapping that ignores datapack kingdoms (K-5).
- 29 `organization.*` reasons, `progression.*` reasons and `condition.mcakingdoms.kingdom` without
  language entries (K-3).
- `PoliticalNetwork.Query.mutation` is parsed and never used (K-9).
- `pack/r1`–`r3` provider patch trees that no longer match the shipped providers (K-12).
- `ModsTomlRangesTest` javadoc and `compatibility-matrix.md` describe an integration state that no
  longer exists (K-8).
- No migration code for any renamed persistent name (K-1).
- Advertised in README but unverifiable here: MCA Reborn 7.7 package roots are probed
  (`compat/mca/McaAccess.java:30-35`) but only 7.6.26 was runtime-validated (README says so).

## 6. Test checklist

Each item names the finding it confirms. Use the packaged jars, not dev runs (K-14).

1. **I-1, all four mods, dedicated server.** Start Forge 47.4.x with MCA Reborn 7.6.26, Kingdoms
   0.1.1 (renamed), Quests 1.7.1, Reputation 0.6.1, Conversations 1.8.0. Expect in `latest.log`:
   Kingdoms logs "Registered MCA: Quests … kingdom offer/accept condition", "durable MCA Reputation
   outbox enabled"; Quests logs nothing about Ultima; Conversations logs no "kingdom gates detected"
   line. Then `/kingdoms guild integrations` shows every mod installed while `/mcaquests …` journal
   never offers `mcakingdoms:evolution/aid`. Confirms the one-directional state.
2. **I-2 (after the bindings are repaired).** With a recognized workshop, run `/kingdoms guild
   workshop mcakingdoms:lamplighters` and complete the commission. Then
   `McaQuestsApi.completionReceiptStatus` (through `/mcaquests` debug or a log line) should stay
   `ready`; if it reports entries that never clear, or `full` after repeated completions, the split id
   is live. Also inspect `playerdata/<uuid>.dat` → `ForgeCaps`/quest data `receipts[].intended_for`
   for `ultima_kingdoms:…` entries.
3. **K-1.** Copy a world saved under Ultima Kingdoms 0.1.1, start it under the renamed jar, run
   `/kingdoms village here` inside a previously recognized village: expect "no village here" and a fresh
   `mcakingdoms_settlements.dat` next to the untouched `ultima_kingdoms_settlements.dat`.
4. **K-2.** Install Townstead 0.8.x (admitted by `[0.7.6,0.9)`), open Kingdom Tasks → "Recognize
   institution" at a workshop: expect "Political building evidence requires verified Townstead
   0.7.6+1.20.1".
5. **K-7.** Without Townstead, complete a Lamplighters sponsor quest; `/kingdoms guild chapters`
   stays empty and the contact's `speakerContext` reports `civic.workshop_needed` indefinitely.
6. **R-1.** Set `enableUltimaKingdomsIntegration=false` in `mcareputation-common.toml`, set Kingdoms'
   `reputationSync.mode = BIDIRECTIONAL_SEMANTIC`, run `/kingdoms faction local_effect <player>
   <dim> <village> 5`: expect APPLIED (switch ignored).
7. **R-2.** On a Reputation save that ran the old Kingdoms, `/mcareputation standing consumers`
   lists both `ultima_kingdoms:factions` (stalled) and `mcakingdoms:factions`.
8. **K-3.** Open Kingdom Tasks → Organizations → "Propose organization merge" with two dynamic
   organizations: the result line contains the literal `organization.merge_pending_consent`.
9. **K-6.** Stand 20 blocks from a guild contact, open Kingdom Tasks → "Request an introduction",
   pick the contact (it is listed), apply: expect `civic.contact_unavailable`.
10. **K-14.** `./gradlew runServer` with Recruits 1.15.2 and `/kingdoms warfare declare …`: expect
    "Verified native military security hooks unavailable"; the same on the packaged jar succeeds.
11. **Upstream / MCA 7.7.1.** Start with MCA 7.7.1-beta.2 and confirm Kingdoms logs "Attached MCA …
    through package root forge.net.conczin.mca" and that villagers still acquire residence; this was
    only ever validated on 7.6.26.

## 7. Polish

- `GovernmentService.page("history")` and `Names.player` read the profile cache by UUID only (no
  network), fine; but `page` does not null-check `getProfileCache()` while `Names.player` does.
- `CampaignService.siegeAllowed` copies every campaign and accord map and stats a file on each call,
  and Recruits calls it once per active siege per tick through the redirect in
  `mixin/RecruitsDetectionMixin.java:12-18`.
- `PoliticalNetwork.Reply` and `InteractionNetwork.Reply` are JSON-in-a-string packets (up to 60,000
  characters); within Forge's limits, but worth a note in the protocol docs.
- `GuildScreen`, `WarRoomScreen` and `VillageLedgerScreen` use `gui.back` (vanilla key) for one button
  and `gui.mcakingdoms.back` for another; consistent would be nicer.
- `CivicCommands` and `StandingTasks.administration.integrations` both hardcode the same list of
  eleven mod ids.

## 8. What could not be checked

- **Vanilla API existence by tool.** `find_api.py` found only `forge-1.20.1-47.4.23-sources.jar` (Forge's
  own sources) and reported `Component.translatableWithFallback`, `NbtUtils.addCurrentDataVersion`,
  `ServerPlayer.serverLevel`, `LivingConversionEvent` and `ModLoadingContext.registerConfig` as NOT
  FOUND; the mapped Minecraft sources jar is not in `~/.gradle/caches/forge_gradle` (only the binary
  `_mapped_official_1.20.1.jar`). `compileJava` passing against 47.4.23 is the evidence that these exist.
- **Runtime.** No server or client was launched; the GameTest suite, the client harness and the
  `tools/test/integration_runtime.py` phases were not run. The 135 unit tests are from the build the
  rename left in `build/`.
- **MCA Reborn internals.** Upstream source was not available; the four package roots and the
  `Residency#getHomeVillage` / `VillageManager` shapes are taken from Kingdoms' own probe list.
- **Recruits 1.15.2 and Townstead 0.7.6 / 0.8.** Neither jar was inspected; the Recruits mixin targets
  and the Townstead API member names are taken on trust from Kingdoms' manifests.
- **Pack provider patches** under `pack/` were not applied or diffed against the current add-on trees.
- **MCACrime** was read only where Kingdoms touches it (`InstitutionalServiceApi`,
  `JurisdictionPolicyApi`, `CrimeCommunityKey`, `mods.toml`); no full review.

## 9. Remediation status (2026-10-01)

Every finding above was addressed the same day, following `docs/MCAKingdoms-Review-Remediation-Plan.md`
with its recommended defaults (D1 migrate, D2 accept both ids for one release, D3 alias the old quest ids,
D4 standardize on `mcakingdoms:regional_civic_network`, D5 probe Townstead capabilities). Three items
differ from what this report or the plan said:

- **K-10 was overstated.** The ledger's `mcakingdoms:serenum` fallback is unreachable: the line before it
  already opens the Government search when nothing is selected. The dead fallback was removed.
- **K-14's suggested fix would not work.** Forge 47's loader does not read `[[mixins]]` from `mods.toml`;
  mixin configs come only from a jar's `MixinConfigs` manifest attribute. Dev runs now pass
  `-mixin.config=mcakingdoms.mixins.json` instead (`build.gradle`, `minecraft.runs.configureEach`).
- **R-1's config alias is ANDed, not ORed.** Both switches default on, so OR would have ignored a server that
  had switched the old name off. Either switch off refuses Kingdoms' writes.

| Finding | Status | Where |
|---|---|---|
| I-1 | Fixed in Quests, Reputation, Conversations and Crime, and mirrored in their NeoForge 1.21.1 ports. Each resolves the installed generation, `mcakingdoms` first, `ultima_kingdoms` for one release. | Quests `compat/KingdomIntegration`; Conversations `compat/KingdomsIdentity`; every `mods.toml` |
| K-1 | Fixed: one-time copy of each saved-data and config file with ids rewritten; lazy entity identity move; welcome flag; item remap on `MissingMappingsEvent`; old slugs resolve. | `persistence/LegacyRename`, `McaKingdoms`, `CivicIdentityStore` |
| I-2 | Fixed: Quests names the consumer (`institutionalCompletionConsumer()`); Kingdoms reads it; outbox schema 4 renames persisted ids and merges split cohorts. | Quests `McaQuestsApi`, `CompletionReceiptOutbox`; Kingdoms `QuestCompletionBridge` |
| K-2 | Fixed: capability probes replace the version string; start-up warns on an unvalidated Townstead. | `GovernmentService`, `TownsteadRuntime` |
| R-1 | Fixed: both namespaces gated; `enableKingdomsIntegration` plus the deprecated alias. | Reputation `McaReputationApi`, `McaReputationConfig` |
| R-2 | Fixed: the renamed consumer adopts the old cursor and captures, or forgets an idle old cursor. | Reputation `StandingOutbox` |
| Q-1 | Fixed: `mcakingdoms` is known; the two namespaces are aliases. | Quests `OptionalModNamespaces` |
| K-3 | Fixed: 43 entries added, namely 27 organization reasons (K-3 above says 29, but some of the literals it counted are task ids), 9 progression reasons, the Quests condition description, 2 scoped-contract refusals and 4 evolving contract kinds. Results put a reason in its own translatable paragraph; `LangCoverageTest` keeps it that way. | `en_us.json`, `EvolutionTasks`, `CivicTasks` |
| K-4 | Fixed by removal (no fulfilment existed); overview row computed from the providers. | `GovernmentService`, petitions data |
| K-5 | Fixed: `petition_honor` kingdom metadata. | `GovernmentService`, kingdom definitions |
| K-6 | Fixed: one `contactRange` (max 32) for pickers and validators; `nearby_npc` target. | `CivicConfig`, `BasicTargets`, contract services |
| K-7 | Fixed: MCA building data backs `READ_BUILDINGS` and a `READ_BUILDING` fallback without Townstead. | `TownsteadBinding`, `TownsteadServiceImpl`, `TownsteadRuntime` |
| K-8 | Fixed: compatibility matrix, test javadoc and changelog corrected. | docs |
| K-9 | Fixed: politics channel query carries no request (protocol 2). | `PoliticalNetwork`, `KingdomScreen` |
| K-10 | Dead fallback removed (see above). | `VillageLedgerScreen` |
| K-11 | Fixed: schedule-only evaluations stay in memory until the next world save. | `EvolutionService`, `EvolutionSavedData` |
| K-12 | Moved to `docs/history/provider-patches/` with a README; scripts repointed. | `pack/*/stage_delivery.py`, `tools/test/verify_r2_pack.py` |
| K-13 | Fixed: typed field reads explain bad values; unexpected faults show a plain sentence. | `ActionRegistry.Context`, `InteractionNetwork` |
| K-14 | Fixed differently (see above). | `build.gradle` |
| R-3 / C-2 | Fixed: Reputation and Crime declare `mcakingdoms` `BEFORE` plus the legacy entry. | `mods.toml`, `ModsTomlOrderingTest` |

Verification, all on 2026-10-01: Kingdoms `check` (151 tests, 0 failed, 1 skipped; GameTest, client-test and
runtime-test sources compile), `runGameTestServer` (47 of 47 on a fresh world), Quests `check` (1,518, 0 failed),
Reputation `check` (862, 0 failed), Conversations `check` plus both content verifiers (1,745, 0 failed), Crime
`check` (2,670, 0 failed), and the four NeoForge ports' `check`. Packaged dedicated servers with the rebuilt
jars passed the `initial` (core, and with Reputation including crash replay), `quests`, `r2`, `civic-loop`
(with Townstead, and with MCA alone), `r3` and `r4` acceptance phases. The first `r3` run failed once at
"saved native siege appears in history" and the rerun passed all five boots; the warfare code is unchanged
from the pre-rename commit, and the Astra diagnosis points at a race in the acceptance fixture (tracked in
`MODMAP.md`). A world written by the old Ultima Kingdoms jar also opened under the renamed jar with its
settlements, config files and chest items carried over, and its old slug resolving.
