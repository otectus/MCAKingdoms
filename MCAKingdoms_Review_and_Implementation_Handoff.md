# MCA: Kingdoms — review and coding-agent implementation handoff

Review date: **27 October 2026**. Target: **Minecraft 1.20.1, Forge 47.x, Java 17**.

Reviewed repository: [otectus/MCAKingdoms](https://github.com/otectus/MCAKingdoms), `main` at **`01f8d14a0eece06c8c06f9d2bf2b814d244c5506`**, version **0.2.0**. The commit already includes the usability work and manuscript interface overhaul. This document requests implementation work; the review itself changed no repository code.

## 1. Instructions and scope

Implement the repairs below in small, reviewable changes. First compare your checkout with the reviewed commit, read any current `AGENTS.md`, and preserve intervening work. Reproduce each finding against the implementation branch before fixing it. If a finding has since been fixed, record the responsible commit and its verification instead of reimplementing it.

Prioritize reliable saves, companion contracts, operation recovery, and fair maintenance before adding presentation or convenience features. Keep existing worlds, datapacks, public APIs, and provider-owned state compatible. A method returning successfully is not sufficient evidence that an external operation completed.

The user explicitly selected these additions during this review:

1. **Verified player residence registration**, including a controlled way to change residence.
2. **Optional political notices and visible civic office badges**, including appropriate accession/coronation and memorial presentation.

These selections are authorized scope; do not ask for the same decisions again. Their detailed acceptance criteria appear in A01–A02. Existing optional roadmap items remain a later backlog, not a reason to delay essential repairs. Ask before implementing genuinely new systems outside these selections and the established plans.

### Preserve these ownership rules

| Domain | Owner and constraint |
| --- | --- |
| Settlement identity, kingdom affiliation, civic residence/origin | Kingdoms. Reuse stable settlement UUIDs and redirects. Do not create a second village detector. |
| Personal names, parentage, marriage, clothing, profession, needs, aging, daily schedules | MCA/Townstead and their configured providers. Political offices are additional roles. |
| Native troops, ownership, groups, wages, orders, claims and siege execution | Recruits. Kingdoms issues validated requests and retains recovery records. |
| Quest objectives, item delivery, payment and completion | MCA Quests or the established provider. Use its authenticated, durable evidence. |
| Crime, legal knowledge, restitution and enforcement | MCA Crime. Public civic policy must not expose private case data. |
| Reputation and personal narrative | The configured Reputation/Townstead providers. Avoid a competing score or ordinary-life chronicle. |
| Discovery and privacy | Server-authorized viewer knowledge. Hidden locations, proposals, ballots and transfer details stay private. |

Keep the existing **MCA Capitals conflict guard**: Kingdoms' native politics are disabled when `mcacapitals` is installed. Supporting two political authorities would require a separate design and user decision.

Do not add automatic taxation, generated village stock, remote chest extraction, autonomous offline conquest, forced family arrangements, clothing-based mourning, new worker/guard AI, or compulsory celebrations. The older Capitals-inspired plan's blanket warfare exclusion was superseded by the explicitly designed R3 native Recruits integration; that does not authorize an independent army simulation.

## 2. Review results and evidence limits

The highest-impact findings are lost player civic identity after cloning, unsafe failure handling around authoritative saves, public companion sources that do not match documented compatibility, scheduling starvation, and task results that can misrepresent pending/refused operations as completed.

This was a source and contract review covering the major services, persistence, public API boundaries, network/task flows, resources, tests, and design/validation records. It is not a claim that every possible gameplay bug has been found.

| Check performed in this review | Result |
| --- | --- |
| Repository inventory | 342 production Java files; 59 Java files under `src/test`; additional GameTest and packaged acceptance harnesses. |
| Production TODO/FIXME/stub sweep | No literal production TODO/FIXME backlog found. Reflection-proxy default `UnsupportedOperationException` branches are not unfinished features. |
| Resource JSON parse and duplicate-key check | **125 files parsed; zero parse failures or duplicate object keys.** |
| Bundled language-key parity | `en_us`, `es_es`, `zh_cn`: **2,389 keys each**, matching key sets. This does not establish translated dynamic service output or linguistic quality. |
| R1 local content checks | All 12 overlay hashes and quest IDs matched the manifest; deeds and commission references agreed; peaceful independent-route thresholds were attainable from the bundled definitions. External original pack files were unavailable. |
| R2 local content checks | Workshop definition retained four consumed lanterns, six emeralds, original-giver turn-in, once-only repetition and institutional conditions. Provider execution was not run. |
| Political maintenance reproduction | A standalone reproduction of the cursor algorithm visited only records 0–7 of 16 timed records after 20 passes when eight elections also existed. |
| Evolution sampling reproduction | Eight populated regions, player budget four, 20 alternating passes: **zero advancing evaluations in every region** under the current consecutive-sample rule. |
| Gradle | `./gradlew test check --no-daemon` stopped before compilation: Gradle 8.8 download from `services.gradle.org` failed with `SocketException: Network is unreachable`. |
| Minecraft, current build, multiplayer and injected save failures | **Not executed in this review.** These are acceptance requirements below. |
| GitHub tracker | Issue search and open-PR search returned no entries at review time. Repository plans and source therefore supplied the backlog. |

The repository contains substantial prior validation: the usability record reports 229 tests with one skip and 73 GameTests; the later manuscript record reports successful 0.2.0 builds, client workflows and 159 final GUI/interaction/Blueprint captures. Treat those as **historical evidence supplied by the repository**, with their exact artifact hashes and limits. They do not prove that the findings here were exercised. See [usability validation][doc-usability] and [manuscript validation][doc-book].

Severity: **P1** = correctness, save continuity, blocked recovery, or a materially broken advertised workflow; **P2** = bounded reliability, scale, usability, validation or release completeness. Confidence is stated per item. No new P0 exploit or universally reproducible crash was established.

## 3. Prioritized work list

| ID | Priority | Work package | Evidence class |
| --- | --- | --- | --- |
| F01 | P1 | Preserve player civic identity across clone/respawn | Code-confirmed lifecycle gap |
| F02 | P1 | Refuse destructive partial settlement loads | Code-confirmed failure path |
| F03 | P1 | Stop writable empty fallback after failed legacy migration | Code-confirmed failure path |
| F04 | P1 | Repair/publish the actual companion contract tuple | Confirmed public-source mismatch; installed jars unverified |
| F05 | P1 | Remove political deadline starvation | Algorithm reproduced |
| F06 | P1 | Correct evolution active-time accounting and digest fairness | Algorithm reproduced; digest loop confirmed |
| F07 | P1 | Report typed operation outcomes and make Check read current state | Code-confirmed mismatch |
| F08 | P1 | Keep authorized cleanup accessible after feature disablement | Code-confirmed task/service mismatch |
| F09 | P2 | Publish scenario notices after durable commit | Code-confirmed ordering defect |
| F10 | P2 | Expire pre-application recruit offers safely | Code-confirmed missing lifecycle |
| F11 | P2 | Fix gallery retry loops and stale server-derived content | Code-confirmed cache/retry paths |
| F12 | P2 | Provide sustainable capacity and retention handling | Confirmed bounds; documented long-running limitation |
| F13 | P2 | Remove repeated global sorting and profile remaining hot paths | Repeated work confirmed; timings unmeasured |
| F14 | P2 | Preserve translation keys through service results | Code-confirmed and documented gap |
| F15 | P2 | Scope form revision checks to relevant state | Code-confirmed unnecessary invalidation |
| F16 | P2 | Make acceptance tooling reproducible and fix the R3 fixture | Nonportable paths confirmed; race diagnosis provisional |
| F17 | P2 | Reconcile documentation, compatibility and release evidence | Confirmed documentation drift |
| A01 | After P1 | Add verified player residence registration | User approved |
| A02 | After F07/F09 | Add optional political notices and office badges | User approved |

### F01 — Preserve civic identity across player clone/respawn

**Evidence:** [CivicIdentityStore][src-identity] writes `mcakingdoms:civic_identity` directly into entity persistent data. [McaKingdoms event handlers][src-main] copy identity for living-entity conversion but have no `PlayerEvent.Clone` handler. Automatic NPC evidence does not repopulate player identity. Forge's [ServerPlayer restore patch][forge-clone] copies its designated persisted subtag and emits the clone event; an arbitrary sibling compound is not automatically preserved.

**Trigger/impact:** Register a player's civic residence/origin, then die and respawn. The replacement player can lose the Kingdoms compound, affecting residence-based founding and civic eligibility. Exercise End-return cloning too; do not assume every clone is a death.

**Implement:** Handle the player clone lifecycle on the server and copy only Kingdoms' own identity state, using the existing copy/migration logic. Alternatively use Forge's designated persisted storage with a compatible migration, but avoid dual authoritative copies. Preserve immutable origin and residence-change time, normalize settlement redirects, and keep NPC conversion behavior intact. Do not copy arbitrary persistent NBT belonging to other mods.

**Acceptance:** Death with both `keepInventory` settings; End return; reconnect and full restart; old namespace identity; residence after a settlement merge; origin after a kingdom reassignment. Exactly one identity survives, with no fabricated residence or duplicate join/leave effects. A previously eligible player remains eligible after respawn.

### F02 — Refuse destructive partial settlement loads

**Evidence:** [SettlementSavedData.load][src-settlement-data] protects unsupported schemas and conflicting external references, but catches malformed individual settlement records, logs that they were skipped, and returns a writable store. Duplicate record IDs overwrite through map insertion. Malformed redirect entries can also be ignored.

**Trigger/impact:** One bad record in an otherwise valid file disappears from memory; a later normal mutation/save can overwrite the file with only the surviving records. References from politics, discovery, citizens and providers can then point to an erased settlement.

**Implement:** Validate the complete authoritative settlement payload before making it writable. On malformed records, duplicate identities or invalid redirect topology, retain the original payload and refuse startup or the identity subsystem's writes with an explicit diagnostic. Follow the project's established refusal model; do not silently substitute an empty or partial canonical identity store. Validate redirect cycles, conflicting sources and nonexistent terminal targets without rejecting legitimate retired-ID chains. Keep untouched bytes/backups available for repair.

**Acceptance:** Invalid UUID, malformed bounds, duplicate ID, duplicate external reference, malformed redirect, cycle, valid redirect chain and unsupported future schema. An attempted tick/mutation/autosave after refusal must not replace the original evidence. The healthy fixture must load with identical identities.

### F03 — Fail closed when legacy save migration fails

**Evidence:** [LegacyRename.migrateSavedData][src-legacy] catches a failed copy/rewrite and explicitly continues with that store empty. Startup proceeds. Once a new destination file is created, the next launch skips migration because the destination exists.

**Trigger/impact:** A corrupt old file, read failure, full disk or failed rename-copy can split one world's state between old and new stores. Gameplay can strand old progress even though the old source file still exists.

**Implement:** Complete a preflight before starting writable services. A recognized legacy store that could not migrate must block dependent writes or startup and produce an actionable health report. Never treat migration failure as a new world. Keep copies atomic, idempotent and non-overwriting; preserve old files. Account for a multi-file migration interrupted after some successful copies, and for destinations already containing genuine newer progress. Do not instruct automated recovery to delete those destinations. Assess config-copy failure separately, especially when defaults would change authority policy.

**Acceptance:** Inject read, decode, write and move failures; restart after partial completion; test old-only, new-only and both-present files; verify all 17 save-name mappings. No new empty authoritative save may supersede a failed legacy import. Migration diagnostics must distinguish failure, successful copy and an intentionally skipped existing destination.

### F04 — Repair and publish the actual companion contract tuple

**Evidence:** Kingdoms' [QuestCompletionBridge][src-quest-bridge] prefers a provider-supplied consumer ID, but falls back to `mcakingdoms:regional_civic_network`. The public MCA Quests `main` commit **`784f5d5b7061ae3e8c441467204e7e8095379169`**, version 1.7.1, still has all of the following:

- [KingdomIntegration][quests-integration] detects `ultima_kingdoms` and reflects `com.ultimakingdoms` facades.
- [InstitutionalCommissionBridge][quests-institution] reflects the old institutional API class.
- [McaQuestsApi][quests-api] and [QuestManager][quests-manager] require `ultima_kingdoms:regional_civic_network`; the API has no `institutionalCompletionConsumer()` getter.
- [CompletionReceiptOutbox][quests-outbox] retains literal consumer identities; the inspected source does not perform the promised rename migration.

The [Kingdoms README][doc-readme] and [CHANGELOG][doc-changelog] describe repaired companion trees and the provider getter/migration. Those may refer to local development artifacts; they do not describe the public Quests source inspected here. This is a reproducibility and functional integration defect for a user building the linked repositories, not proof that the maintainer's tested local jar lacked the fixes.

There is a second migration concern in public MCA Reputation **`6e90ee4459ac2182691af30f6e6748427d161065`**: [StandingOutbox.register][rep-outbox] registers an unfamiliar consumer at the current tail and has no Kingdoms rename handoff. Its metadata still names the old optional mod ID. An upgrade with pending old-consumer changes needs a tested handoff; blindly registering the renamed consumer cannot be assumed to deliver the backlog. Conversations' inspected `KingdomsIdentity` does recognize both generations; do not unnecessarily rewrite it. Crime's inspected workshop API matches the `workshop(ServerPlayer, Entity)` signature used here; that source match is not a packaged integration test.

**Implement in this order:**

1. Inventory exact intended companion commits, jar hashes and configs. Find and publish already-written fixes if they exist, instead of recreating divergent versions.
2. Repair Quests' optional mod detection, reflective facade paths, required namespace validation and institutional callback binding. Prefer one explicit provider-owned consumer identity contract.
3. Migrate Quests' registrations, subscriptions, intended-consumer sets, acknowledgements and tombstones atomically in Quests. Preserve provider epoch and receipt IDs. Do not poll/award through both names and accidentally double-credit.
4. Migrate Reputation's old/new consumer state in Reputation, retaining recoverable captures/cursors and surfacing genuine retention gaps. Never erase pending history merely to eliminate an old consumer row. Verify integration-disable switches as well as enabled mode.
5. Probe complete required methods, not just API class existence. Expose unsupported-contract status before accepting paid or otherwise consequential work. Keep capability-specific refusal so unrelated read-only functions remain usable.
6. Correct compatibility claims to distinguish accepted version range, validated artifact, public source and publicly available release. Bump companion versions when required by their release process; version 1.7.1 alone currently does not identify one reliable contract.

**Acceptance:** Minimal dedicated server; current published tuple; legacy-to-current upgrade with outstanding receipts and standing changes; each optional provider absent; supported older reader; incompatible API; provider removed/reinstalled; interrupted acknowledgement. One workshop delivers exactly once, grants exactly one honor, and clears its intended receipt through restart. No fake completion, lost pending credit, duplicate reward or static optional-class linkage.

### F05 — Give political maintenance independent fair cursors

**Evidence:** [GovernmentService.maintainDeadlines][src-government-maintenance] uses one incrementing `maintenanceCursor` for three differently sized collections: agreements/petitions, elections and regencies.

**Reproduction:** With 16 agreements/petitions and eight elections, each pass advances the cursor by 16. The timed-record loop repeatedly visits the same first eight entries; the remaining eight never reach expiration maintenance. Other size combinations produce other starvation patterns. A timestamp check elsewhere may still deny an expired privilege; the confirmed defect is that state transitions and their history/notifications can fail to occur.

**Implement:** Independent round-robin cursors per work domain, or one fair bounded work queue. Preserve bounded tick work and account for insertion/removal, cursor overflow and empty collections. Domain maintenance must not be disrupted by unrelated record counts.

**Acceptance:** At least `(timed, elections, regencies)` sizes `(16,8,0)` and `(17,9,3)`, plus shrinking/growing sets, zero records and cursor wraparound. Every eligible record transitions within a documented finite number of passes; the fact/notice is committed once. Include receipt pruning in regression coverage without conflating it with these domain cursors.

### F06 — Separate active-world time from sampling and make digests fair

**Evidence:** [EvolutionService.tick][src-evolution-tick] rotates a bounded player sample, but only advances a region's scenario when the region appears in both the current and previous sample. With eight players in different eligible regions and budget four, the samples alternate; every region's deadline is paused repeatedly despite continuous presence. Digest delivery separately takes the first sorted `BUDGET` players on every pass, permanently excluding later UUIDs.

**Implement:** Track actual eligible-region activity across evaluation windows independently of which players receive this pass's expensive generation work. Use bounded event/activity bookkeeping or an equivalent precise model. Preserve the documented no-offline-catch-up rule, pause semantics and no-force-load policy. Use a fair independent cursor/queue for subscribed digest recipients. Do not solve either problem by increasing the budget until a small test passes.

**Acceptance:** More players than the budget, multiple players in one region, logins/logouts, dimension changes, paused regions, duplicate settlement redirects and restarts. Populated regions advance and empty regions pause according to the documented rule. Every subscribed eligible online player eventually receives a due digest; unsubscribed players do not. Disabling evolution must retain pending state without advancing it accidentally.

### F07 — Make operation results truthful and Check authoritative

**Evidence:** [InteractionNetwork.execute][src-interaction] sets `done` for any normal handler return unless it is the special asynchronous `Pending` type. Real services return ordinary strings for refusals and pending native saves: [RecruitTransferService.apply/confirm][src-transfers] and [MobilizationService.muster/recover][src-mobilization] contain such paths. [PoliticalTasks.result][src-political-tasks] says a saved political decision is “in effect,” including proposals awaiting signature. The `CHECK` path returns the retained result; it does not reread the owning operation.

**Implement:** Add a typed internal outcome contract with states such as `APPLIED`, `PENDING`, `REFUSED`, `UNKNOWN` and `READ`, plus translated reason, operation reference and allowed next action. Distinguish a successfully recorded proposal from an operational agreement. Preserve existing public API compatibility with additive adapters or an intentional versioned migration. Do not infer state by parsing English messages.

Retain the stable operation/request identifier and have Check query its current server-authorized state without rerunning the mutation. Keep useful recovery after session expiry/reconnect through the existing inspect/recovery task. An unconfirmed provider save must never acquire a success badge because the Java call returned normally. Read-only output should stay read-only.

**Acceptance:** Refused muster; unknown/unloaded recruit; pending apply; partially applied/restore state; completed transfer; asynchronous reload; saved unsigned proposal; signed agreement; save failure; timeout followed by late reply; repeated Apply and Check; reopen/reconnect. Checking never repeats payment, native ownership changes, votes or agreement signatures. The displayed next action must be valid for the returned phase.

### F08 — Do not hide safe cleanup behind creation-feature gates

**Evidence:** [TaskMeta][src-task-meta] places withdraw/dismiss/recover actions in military/evolution/drama feature groups. `InteractionNetwork` rejects the task before its form opens when those features are disabled. Some owning methods deliberately permit safe cleanup without the creation flag: [EvolutionService.withdraw/releaseRejected][src-evolution], [DramaService.exit][src-drama] and [MobilizationService.authorizeRestoration][src-mobilization]. Their command path can therefore remain usable when the GUI path is blocked.

**Implement:** Classify operation capabilities by intent: start/expand, inspect, cancel-before-effect, verify-existing-effect, and restore/reconcile. Derive GUI availability from the same rules as the owning service. Keep actor/ownership, writable-save, revision and required native-provider checks. Do not simply allow every action when disabled; for example, drama recovery may have stricter service requirements than exit.

**Acceptance:** Start an obligation, disable the feature, then inspect and invoke each allowed cleanup from both GUI and command. Restore the original unit/orders only under existing identity checks; never discard an unconfirmed restoration. An incompatible or removed native provider gives an honest pending/unavailable explanation without force-loading chunks. Re-enabling does not replay completed cleanup.

### F09 — Commit scenario creation before announcing it

**Evidence:** [EvolutionService.generate][src-evolution-generate] sends its government opportunity notice before `tick()` durably commits the updated scenario state. Digest notices in the same service already follow the safer commit-before-delivery order.

**Implement:** Collect notifications while preparing the transaction, commit the scenario/fact, then publish from committed state. If retryable delivery is required, key it by stable committed event ID. Deduplication must not suppress a real later event or announce a failed write.

**Acceptance:** Inject a failed evolution write: no opportunity notification or actionable link is sent. A subsequent successful evaluation creates and announces one real scenario. Crash/restart around commit/delivery produces no contradictory history. Use this same boundary for A02.

### F10 — Expire untouched recruit-transfer offers

**Evidence:** [RecruitTransferData][src-transfer-data] treats only `COMPLETE`, `CANCELLED` and `RECONCILED` as terminal. Deadlines prevent consent/application, but no expiry maintenance transitions an abandoned `OFFERED`/`CONSENTED` record. [blocksMobilization][src-transfers] and the one-active-transfer-per-unit guard continue treating it as blocking until a party cancels it manually.

**Implement:** Add a bounded, durable expiration transition for offers that have not begun a native mutation. It may use a distinct `EXPIRED` phase with a compatible schema migration or another unambiguous terminal representation. Do not auto-expire `APPLYING`, `CONFIRMING`, `RESTORING` or records with unresolved native snapshots/accounting divergence; those require recovery. Run harmless expiration even when new drama proposals are disabled.

**Acceptance:** An overdue untouched offer stops blocking a new transfer/muster, including after restart and with both players offline. Deadline-edge consent/apply is serialized correctly. An overdue partially applied transfer retains its recovery fence and still prevents a conflicting operation. History explains expiration rather than claiming successful delivery.

### F11 — Bound gallery retries and invalidate stale server-derived data

**Evidence:** [GuideKingdoms.ensure/tick][src-gallery] caches `READY` until disconnect. A failed request transitions to `FAILED` and refreshes the article, whose [Kingdoms block][src-guide-article] calls `ensure()` again; `ensure()` allows `FAILED` and resets the attempt count. This can produce repeated three-attempt cycles instead of the advertised stable failure state. Discovery changes and definition reloads also leave the cached known counts/kingdom list stale. [ClientPresentationState][src-client-state] refreshes articles for recipe/tag updates but does not invalidate this server-derived gallery there.

**Implement:** Separate pure rendering, initial request and explicit retry. Preserve a visible terminal failure state until a deliberate retry/reopen or defined connection event. Invalidate or revalidate server summaries on relevant definition and knowledge revisions, with debouncing and a connection/session generation. Resource reload alone must not be treated as authoritative knowledge. Preserve request-ID rejection of stale replies and disconnect cleanup.

**Acceptance:** Drop all replies and prove retries stop; retry explicitly and recover. Discover another settlement, reload changed kingdom definitions, switch resource language, disconnect from server A and join B, and inject A's delayed reply. Counts and definitions become current without leaking A's data, repeatedly requesting from render code, or losing the reading position.

### F12 — Provide a safe path beyond finite historical-record caps

**Evidence:** [RecruitTransferData][src-transfer-data] caps all transfers at 1,024 including completed records; [EvolutionState][src-evolution-state] caps scenarios at 4,096, and generation stops at that total. [PoliticalSavedData][src-political-data] also caps collections such as 512 elections and 4,096 petitions/agreements. Some other histories/receipts already have bounded pruning; do not report that all storage is unbounded or that political request receipts still lack pruning. R1 explicitly documents capacity backpressure as an initial-release limitation.

**Implement:** Inventory each cap, what consumes it, what currently frees it, and what invariant requires retention. Expose actionable near-capacity and capacity health. Deliver a supported, reference-aware archive/compaction or versioned storage-growth path for terminal domain records. Separate active state, user history and replay evidence. Preserve unresolved native intents, source acknowledgements, effect deduplication, election mandate provenance and records still referenced by other stores.

Automatic deletion is not an acceptable default fix. Where safe compaction cannot yet be proven, retain refusal and provide an explicit offline migration/maintenance procedure with a dry run and backup requirement. Do not silently raise every bound or remove defensive bounds.

**Acceptance:** Fill each affected collection to its bound with terminal records and pending references. Exercise normal operation, maintenance, restart, downgrade refusal and replay of old packets/provider receipts. Capacity relief must not re-award old work, erase current authority, or free a recruit whose native state remains uncertain.

### F13 — Fix repeated sorting; profile rather than guess at the rest

**Evidence:** [SettlementKnowledge.search][src-knowledge] fetches operator records in pages of 64 up to a 20,000-record cap. Each [KingdomsServiceImpl.getSettlementPage][src-core-page] reconstructs snapshots and sorts the whole matching collection before skipping. One large search can perform roughly 313 full sorts. `all()` has the same page-loop pattern. Applying the search cap before text filtering also hides later matches without an explicit incomplete-results signal.

**Implement:** Produce/filter/sort one bounded snapshot per revision/query or maintain suitable server-thread indexes. Reuse the result across pagination without mixing viewer permissions. Make the total and truncation semantics truthful; prefer complete filtered pagination where practical. Do not move live Minecraft entity/world access to an unsafe background thread.

Profile remaining JSON round-trip snapshots in evolution/protection/politics and selected world-context mutations, selector construction, native entity-save verification and high-population notifications. Current encounter handling already avoids snapshots for exhausted/no-credit farm events; preserve that improvement. Optimize demonstrated bottlenecks with immutable read models or targeted copies, not a sweeping persistence rewrite without measurements.

**Acceptance:** Seed 1k, 10k and over-20k settlements and representative large stores. Record server-thread timings, allocations and worst tick impact for operator and ordinary-player searches, paging and actions. Search should not redo a full global sort per page. Verify hidden IDs/counts never enter unauthorized replies, no chunks load, and save/reload invalidation is correct.

### F14 — Keep translatable results translatable

**Evidence:** [CivicTasks][src-civic-tasks] resolves reason keys through `PlayerWords.text` into a server-side string before the component reaches the client. Other services still return English refusal/recovery sentences. The usability record acknowledges this remaining gap. Full language-key parity does not repair pre-flattened or literal service output.

**Implement:** Carry reason key plus typed arguments, or a translatable Component, across internal results and packet encoding. Keep dynamic person/place names literal. Preserve stable machine reason codes for tests and integrations. Update English, Spanish and Simplified Chinese resources for changed/new flows, including A01/A02. Avoid making service logic depend on a client's language or translating by English-string matching.

**Acceptance:** Two clients with different locales receive the same underlying refusal in their respective languages. Cover paid workshop policy, neutral qualifications, membership, transfer phases, disabled-feature recovery, election/grace state and new residence rules. Exercise accented search, CJK wrapping, narration, long names and a pseudo-locale. Do not claim linguistic review from key-parity tests alone.

### F15 — Limit review invalidation to relevant state

**Evidence:** [PoliticalTasks][src-political-tasks] binds almost every task to the global government-service revision. [CivicTasks][src-civic-tasks] normally includes global civic/organization revisions and the commission fingerprint. Unrelated activity can therefore invalidate another player's otherwise unchanged review. Membership tasks already have a narrower version and should remain narrow.

**Implement:** Define explicit review dependencies for the selected government/record, definition revision, actor authority and relevant provider evidence. Recheck those dependencies immediately before commit. Retain the owning service's concurrency and stale-state protection; narrowing only the form token is insufficient if the mutation API still requires a global revision. Make compatible API changes deliberately.

**Acceptance:** Two players acting on unrelated governments/chapters can finish valid reviews. A changed signer, mandate, term, selected building, quest binding, unit equipment, recipient group or provider fingerprint still invalidates the affected review. Two simultaneous conflicting decisions produce one authorized effect, never a silent last-writer win.

### F16 — Make validation reproducible and stabilize the R3 fixture

**Evidence:** [integration_runtime.py][src-integration-script] copies EULA acceptance from an absolute path in the maintainer's MCACrime checkout. Several client scripts and documented wrapper commands depend on that machine's installations/display tools. [verify_r2_pack.py][src-r2-verify] verifies historical exported provider patches against ignored build directories, while current docs explicitly prohibit reapplying those old patches. [MODMAP][doc-modmap] records intermittent R3 siege acceptance and a plausible config-watcher race; the current fixture still changes `MILITARY` mid-run.

**Implement:** Parameterize provider jars, installation roots, Java/display tools and an existing accepted EULA file; provide a clear prerequisite check. Do not assume license acceptance. Replace active release verification of historical patch exports with a manifest of actual companion commits/artifacts/contracts. Retain historical checks only as explicitly historical tooling.

For R3, configure `militaryActions = false` in the isolated fixture config before launch, remove the racing runtime toggle where appropriate, and assert the native siege flag immediately after the attempted start. Treat the recorded race explanation as a hypothesis until this test proves it. Keep the test that detects a genuinely cancelled siege; do not add blind retries or weaken its assertions.

**Acceptance:** A clean checkout on another machine can locate prerequisites, build and launch isolated acceptance fixtures without editing source paths. Missing prerequisites fail clearly. The R3 sequence passes repeatably with native start/history/restart assertions. Record intentional fixture changes separately from production fixes.

### F17 — Reconcile the live documentation and release evidence

**Evidence:** The current main commit supersedes much of the older audit/GUI plan. The generated MODMAP inventory still contains stale asset/language/mixin descriptions, and some older experience text describes the interface as entirely text-based. Compatibility claims are stronger than the public companion source evidence in F04. The one-release legacy-compatibility removal checkbox is still open.

**Implement:** Add a single current completion ledger mapping old finding IDs to fixed commits, active tasks or deliberate exclusions. Update README, CurseForge copy, configuration reference, in-game guide and compatibility matrix together. Label design histories and acceptance records accurately. Refresh generated inventories from the current tree; do not erase useful historical evidence.

Distinguish **runtime old-name adapter aliases** from **on-disk migration support**. Removing old aliases can be a coordinated release step once the supported companion tuple is published and tested. Do not remove old-save import merely because one release elapsed. Record remaining non-migrated advancement/recipe/statistics behavior as the existing cosmetic limitation, not a demand to invent retroactive progress.

**Acceptance:** Every advertised player path is reachable in the advertised tuple. Every supported config key either affects behavior or is clearly marked reserved/informational. Current docs report actual artifact hashes and tests, not inaccessible local logs as the sole evidence. Remove “unreleased” only through the project's actual release process.

## 4. User-approved additions

### A01 — Verified player residence registration

**Problem:** `openFounding` defaults to allowing a resident to found a government, but normal players currently lack an in-game self-registration route; `citizen.residence` is an operator task and automatic evidence is for NPCs. The user selected verified registration rather than retaining operator-only control.

**Implement after F01–F03:**

1. Add **Register my residence** and **Move my residence** entry points in Kingdom Tasks and the relevant settlement/Getting started view. Show the current record, proposed settlement, verification evidence, effects and any cooldown before confirmation. Use the existing task/session/permission infrastructure.
2. Define a server-owned evidence policy. A practical baseline is the authenticated player's recorded respawn bed in a loaded, recognized settlement, with the player physically present and the bed still valid. Use a provider's stronger authoritative player-home evidence when that capability is available. Do not accept arbitrary client coordinates, a global spawnpoint, mere discovery, or temporary proximity as proof. This baseline verifies a declared home; it is not a property-ownership or land-claim system.
3. Treat verification and registration as separate acts: sleeping/visiting/trading alone must not silently move civic residence. An unavailable/unloaded evidence source produces a clear refusal and never loads chunks for proof.
4. Make first registration explicit; preserve the existing policy that first residence initializes origin if no origin exists, explaining it in review. A later move changes residence while retaining origin. Use canonical settlement IDs and a monotonic server time basis for cooldowns across dimensions/restarts.
5. Add an operator-configurable move cooldown and an explicit operator override. Use 24,000 game ticks (one Minecraft day) as the initial engineering default, documented as server-tick time rather than wall time. Same-residence confirmation is an idempotent no-op. Enforce the policy in the service, not only the GUI. Existing administrative `setResidence` remains available to its authorized callers.
6. Re-evaluate founding/service eligibility from the committed identity. Preserve frozen election electorates and existing mandates according to their rules; do not grant a second vote or retroactively rewrite an election when someone moves. Do not silently change citizenship because a bed is later broken.
7. Display the reason for ineligibility and the next practical step. Registering residence must not grant a government office, claim someone else's land, change an NPC home, confer guild membership or enroll the player in war.

**Acceptance:** Fresh survival player registers and, if all existing charter rules permit, founds their first government without operator intervention. Test invalid/remote/broken bed, wrong dimension/settlement, unloaded proof, cooldown bypass attempts, repeated requests, stale evidence, old operator-assigned identity, death/restart, merged settlement and two simultaneous founding attempts. All new state has migration and failure-handling tests. English/Spanish/Chinese flows and keyboard narration are complete.

### A02 — Optional political notices and civic office badges

**Intent:** Add the visible social payoff that Capitals provides while keeping Kingdoms' authority boundaries. The user selected both notices and badges. The existing political fact/notification model should remain the source of truth.

**Implement:**

1. Add opt-in presentation for committed accession/charter events and memorial/vacancy events. Respect each government's terminology: do not call every elected or non-monarchical leader a king or label every installation a coronation. Announce only confirmed lifecycle facts; an unloaded or offline person is not dead.
2. Use existing political facts/notice delivery with stable event IDs, post-commit ordering, bounded queues, audience filtering and cooldowns. Honor private proposal/consent boundaries. Reconnect/restart must not replay a ceremony as a newly occurring event.
3. Show an office badge/icon and readable role in the Ledger/government/person context. Where the client integration safely supports it, add a bounded nearby visual badge or separate title line for a currently observed office holder. Use an additive rendering layer; never rewrite the entity's custom name, surname, equipment or skin. Do not infer offices from display-name text.
4. Sync only authorized role presentation for observed entities; invalidate it after dismissal, succession, government changes, entity unload and disconnect. Avoid per-frame server scans or global NPC packets. Multiple roles need a readable compact treatment and full tooltip/narration.
5. Provide notification and badge settings, with accessible text equivalents and unobtrusive defaults. Avoid mandatory sounds, forced movement, time changes, mourning outfits, work interruptions or attendance requirements. Preserve existing notifications without duplicates.
6. If Townstead exposes a verified political-fact narrative bridge, reuse it; otherwise present Kingdoms' committed political record locally and clearly report the missing integration. Do not fabricate an archive receipt or build a competing ordinary-life chronicle.

**Acceptance:** Founding, election accession, named succession, abdication, confirmed death, vacant office and dismissal all show the right current role/event once. Private roles/facts stay private. Check both languages with long role names and CJK, compact layouts, nameplate conflicts, resource packs, multiplayer, server switch and absent Townstead. Native names, family, clothing and daily behavior remain byte-for-byte/behaviorally unchanged by the presentation operation.

## 5. Existing TODO and roadmap disposition

### Current feature coverage

| Area | Current implementation | Remaining action |
| --- | --- | --- |
| Settlement recognition | Generated-village structure identity, POI/MCA evidence, redirects, merge/reconciliation, names and locks exist; loaded structures in existing worlds are supported. | F01–F03, scale tests and regression fixtures; do not replace with larger-radius guessing. |
| Kingdom definitions | Six bundled kingdoms: Serenum, Lunari, Madera, Anemosia, Yew and Shimaguni; data-driven definitions and reload. | Maintain atomic reload and missing-definition behavior; F11/F17. |
| Civic/faction backbone | Membership/ranks, qualification explanations, deeds, introductions, provider receipts and Lamplighters slice exist. | F04/F07/F12/F14 and exact-pack validation. |
| Institutions and peaceful agreements | Recognition, workshop commission, honors, hospitality and Crime policy bridge exist. | Verify real provider contract, payment/refusal/recovery and operational-versus-proposed state. |
| Government | Charter, capital seat, offices, mandates, petitions, honors, houses, succession, elections and regency exist. | F05/F07/F12/F15, A01/A02. Do not propose elections or houses as wholly missing. |
| Warfare | Native Recruits mapping, campaigns, accords, control, mobilization and civilian service exist. | F07/F08/F10/F16, exact Recruits 1.15.2 mutation/restart tests. |
| Exploration/world context | Known sites, routes, encounter evidence and optional map publication exist. | Scale, privacy and content verification; no new terrain mapper needed. |
| Evolution/drama | Opt-in opportunities, subscriptions, protectorates/obligations, family introductions, transfers, explicit drama and organization changes exist. | F06–F10/F12; preserve opt-in and no offline simulation. |
| Book and UI | Rich manuscript Book, recipes, illustrations, Tasks/start suggestions, search, review editing, government/guild/War Room/settings surfaces and three locales exist. | F07/F08/F11/F14/F15 and remaining runtime/accessibility checks. Do not redo the overhaul. |
| Addon/datapack authoring | Reference docs, example datapack, classes-only API artifact and compile-checked addon example exist. | Extend examples for changed contracts; maintain API isolation and schema validation. |

### Historical audits: retain fixes, reopen only the residual issue

The [September audit][doc-audit], [October review][doc-review] and [remediation plan][doc-remediation] describe earlier commits. Their open wording is not a current bug list. Reconcile them as follows, verifying their tests when touching the area:

| Historical item | Current disposition |
| --- | --- |
| September D-1: every living entity becomes resident | Type-restricted NPC evidence now exists. Preserve it; A01 is explicit player registration. |
| D-2: full-world save on every transfer confirmation | Targeted entity/overworld-data flush and verification cooldown now exist. Profile remaining IO; do not remove the durability fence. |
| D-3: permanent 8,192 political-request ceiling | Request-receipt expiry/pruning exists. F12 concerns other terminal domain collections and references. |
| D-4/D-14: destructive text rewriting and raw player-facing record output | Much of the presentation has structured labels/components. F07/F14 address remaining outcome/localization problems. |
| D-5: only first 32 institutions considered | Institution paging exists. Retain beyond-first-page regression coverage. |
| D-6: elections/mandates/electorate | Configurable resident electorate and elected mandate logic exist. Preserve frozen election semantics in A01. |
| D-7: silent discovery-load failure | Health/diagnostic handling exists. Preserve privacy refusal; do not “recover” by revealing all settlements. |
| D-8 and village-identity T1–T4 | Structure footprint normalization and redirect reconciliation exist. Recheck sidecars and provider bindings through upgrade fixtures. |
| D-9: native mutation commits then reports an error | Domain recovery paths are substantially improved; F07 covers the remaining task-layer misclassification. |
| D-10: family introduction cannot be retried after withdrawal | Evidence-release handling exists. Preserve distinction between reusable proofs and consumed receipt evidence. |
| D-11: withdrawal always restores neutrality | Prior-relation handling exists. Keep asymmetric-relation and restart fixtures. |
| D-12/D-13: untranslated interface and docs | Major GUI/localization work is complete; dynamic service output and some documentation remain F14/F17. |
| September R-1/R-2: copying and repeated sorting | Some world-context paths gained indexes/early exits; F13 addresses demonstrably remaining work and measures the rest. |
| R-3: concurrent review sessions | Commission fingerprints and narrower membership versions exist. Keep repeat-apply tests; F15 must not weaken conflict detection. |
| R-4: raw UUID command paths | No new authority bypass established. Preserve service-level authorization and test GUI/command parity. |
| R-5: fail-closed siege hooks | Exact-version gating is deliberate. Require useful diagnostics and provider-failure tests, not permissive fallback. |
| R-6 and October K-9: legacy political packet writes | Read-only handling/refusal exists; keep malformed/forged packet tests. |
| R-7: startup refusal when discovery cannot persist | An intentional fail-closed tradeoff; consistent with protecting authoritative identity in F02/F03. Document it. |
| R-8: no-credit mob-farm encounters serialize the store | Early capacity, duplicate and repeat-budget checks now avoid that work. Retain the optimization. |
| October I-1/I-2/Q-1/R-2 and village-identity T5 | Public Quests/Reputation sources do not establish completed companion rename/migration work. **Reopened under F04.** |
| October K-1: no rename migration | Migration exists; repair its failure policy under F03. |
| October K-2/K-7: exact Townstead pin and hidden dependency | Member capability probes and documented requirements now exist. Validate the intended 0.8.x contract separately rather than merely widening ranges. |
| October K-3–K-6 | Language keys, honor/petition definitions and range work are present; verify changed flows rather than blanket reimplementation. |
| October K-10 | The original review itself corrected the hardcoded-ledger-fallback claim as overstated. Do not reopen without a new reproduction. |
| October K-11 | Evolution avoids durable writes for schedule-only changes; F06/F09 are different defects. |
| October K-12 | Provider patches were moved to history. F16 must stop using them as current-release verification inputs. |
| October K-13/K-14 | Player-safe fault handling and mixin packaging work exist; generated inventory and portable acceptance still need F16/F17. |
| GUI audit / usability U1–U11, UX-01–UX-12 | Substantially delivered in the reviewed 0.2.0 commit. Fix the residual paths identified here; preserve manuscript visuals. |

### Explicitly retained limitations and deferred plans

- `cacheBySettlementRevision` is documented as reserved; `settlementReassignmentPolicy` currently expresses the single freeze-history policy. They are not evidence that a new caching or retroactive reassignment feature must be implemented.
- One-release old-name compatibility removal remains a coordinated release task, conditional on F04 and upgrade tests. It does not authorize deleting save migration support.
- The broad [factions roadmap][doc-factions-roadmap] is not wholly completed by R1–R4. C01 institution sponsorship has a real slice. C06 occupation and C08 organization-survival ideas have relevant foundations. Richer winter demand, cultural diplomacy, attribution-driven political consequences, religious refuge, expanded research/expedition loops and content packs still require their actual provider evidence and authored content. Do not mark every roadmap example implemented merely because a generic adapter exists.
- Conversely, do not implement every aspirational roadmap paragraph as a release blocker. Keep the approved R1–R4 scope and any already committed content decisions; take a later vertical slice only after its provider and canon prerequisites are resolved. New obligations, currencies, automatic sanctions or changes to ownership rules need a user decision.
- The existing usability plan [section 13][doc-ux-plan] already proposes optional JEI/REI handoff, a discovered-places schematic/map handoff, richer history filters/timeline, and one removable personal objective pin. Keep these as optional later work. Reuse existing quest/map services and discovery filters; none requires a mandatory dependency or another progression system.
- No NeoForge port is requested by this review. Do not infer a cross-loader rewrite from other mods supporting it.

## 6. Comparison with MCA Capitals and similar mods

The comparison below informs design choices; it does not establish binary compatibility. In particular, Capitals' public source checkout and its advertised releases are different evidence sets.

| Mod/source | Distinct strengths | Kingdoms comparison and decision |
| --- | --- | --- |
| **MCA Capitals** — [official project][capitals-project] and [public source][capitals-source] | Monarchy-centered courts, noble houses, succession, petitions/decrees and visible royal identity; the current project description also advertises diplomacy and war. | Kingdoms already has most political primitives, but ties governments to persistent multi-settlement kingdoms and integrates several independent providers. Use A02 for readable office identity and significant political moments. Preserve institutions, private consent and provider ownership instead of reproducing family-name or mourning-outfit behavior. |
| **MineColonies** — [Town Hall][minecolonies-hall] and [Requests][minecolonies-requests] | Clear rank/permission controls and a concrete request/production/delivery chain. | Useful lessons are visible prerequisites, accountable delegated authority and understandable pending/blocked work. F07/F08/F14/F15 complete those qualities in existing Kingdoms workflows. Automated warehouse/courier production belongs to its own simulation and is not a new Kingdoms feature in this handoff. |
| **Villager Recruits** — [official project][recruits-project] | Native troop command, groups, factions/diplomacy, claims and sieges. | It is already Kingdoms' military execution provider. Improve recovery and honest status around that boundary; do not implement duplicate armies or infer persisted success from an in-memory native change. Exact 1.15.2 support remains deliberate until newer contracts are verified. |
| **MineFortress** — [official overview][minefortress-about] | Direct RTS control, blueprints, visible construction/profession progression and army management. | Its immediate feedback is a useful design reference. Kingdoms should express political prerequisites/results clearly while preserving the existing first-person, MCA/Townstead-owned population. RTS selection, worker control and building simulation would be a separate product direction. |
| **Recruits: Living Villages** — [author's project description][living-villages] | Autonomous expanding settlements, taxation, army recruitment, conquest and a village-management terminal; advertises simulation outside loaded chunks. | A close thematic comparator, but its offline population/army model conflicts with Kingdoms' current no-offline-catch-up and provider-ownership rules. Borrow clarity of current-state displays, already addressed here. Do not install it as a presumed compatible provider or add its simulation without a separate design decision. |

For Capitals, the inspected public `master` commit is **`89101881ea3ed683f03b8d4d3c8e5d21f0522908`** and its `gradle.properties` says **1.1.0**. The official CurseForge page observed during this review lists **1.3.7 for Forge 1.20.1**, dated 6 September 2026. Therefore, source-level observations about implementation are limited to that pinned checkout; advertised newer functionality was not verified by running its jar.

**Recommended implementation scope from this comparison:** the repairs and the two additions the user approved. Office-aware notices/badges supply social visibility; verified residence completes the normal-player route into civic participation. Other comparator mechanics above are deliberately not added to the coding scope.

## 7. Implementation sequence and acceptance gates

### Milestone 1 — Data and contract safety

Implement F01–F04. Establish the exact supported artifact tuple and preserve all legacy/pending evidence before any cleanup or compatibility removal. Add regression fixtures for the actual failures, not tests that simply mirror new helper methods. Produce a migration report and an upgrade/downgrade policy. A missing provider build is a visible blocker for the dependent feature, not a reason to fabricate its success.

### Milestone 2 — Correct lifecycle and recovery

Implement F05–F10. Introduce typed outcomes before revising recovery UI text. Make existing safe cleanup reachable while disabled. Exercise save-failure and restart boundaries at each durable-intent/native-effect/acknowledgement stage. Keep private operation state scoped to its actor/parties.

### Milestone 3 — Sustainable UI, scale and authoring

Implement F11–F16 and the supported portion of F12. Measure existing bottlenecks and retain bounded server work. Keep the current visual system. Improve author documentation and examples only for real supported behavior. Finish F17 continuously as behavior changes.

### Milestone 4 — Approved player-facing additions

Implement A01 and A02 on the repaired foundations. Residence must work in ordinary survival without creating a land-claim system. Notices/badges must remain presentation derived from committed authority. Test both together through founding, changing office, moving residence, death and restart.

### Build and test commands

Use repository-local tooling once dependencies and fixture prerequisites are available. These are implementation acceptance commands, **not successful commands from this review**:

```bash
./gradlew check build --no-daemon
./gradlew productionTestJar -I tools/test/production-tests.gradle --no-daemon
./gradlew productionClientTestJar -I tools/client-test/client-test.gradle --no-daemon
python3 tools/test/run_gametests.py
git diff --check
```

Inspect/repair the runtime scripts' prerequisites under F16 before invoking them. Use fresh disposable work directories and explicit provider paths; never run fault-injection tests against a player's only world. The repository's `check` includes API-jar isolation and addon-example compilation; keep these requirements. Do not copy the maintainer's `/home/.../.mcmod-tools` paths into a supposedly portable command.

### Required runtime matrix

| Dimension | Minimum coverage |
| --- | --- |
| Baseline | Kingdoms alone, then MCA plus its required dependency, then the exact supported full companion tuple. |
| Provider boundaries | Quests, Reputation, Crime, Conversations, Townstead and Recruits individually absent/unsupported; compatible reinstall with retained data. Validate contract-specific behavior, not just successful server startup. |
| MCA/Townstead | Existing validated MCA 7.6.26/Townstead 0.7.6 tuple; separately test any newer tuple advertised as supported. Probe package-root/member changes explicitly. |
| Capitals coexistence | Both installed: native-politics conflict mode is clear and no second authority silently starts. Do not claim interoperability merely because both jars load. |
| Dedicated server | No client-class linkage; authenticated actions, no forced chunk loads; resource/data reload retains last-good definitions on invalid input. |
| Multiplayer | Two players at the same institution, competing signatures/votes/outcomes, unauthorized bystander, repeated packets, moved authority and offline participants. |
| Player lifecycle | Death, End return, reconnect, server restart, dimension switch and residence/settlement redirects. |
| Transaction recovery | Save failure before intent, after intent, after native mutation, before acknowledgement, and after acknowledgement; restart at each point. |
| Disabled features | Inspect/cancel/recover retained operations according to owning-service policy; no new consequential action through a recovery loophole. |
| Time/fairness | More players/regions than budgets, record counts that share divisors, clock continuity and active/inactive regions. |
| Capacity/performance | Near/full caps, many terminal records, large selector/search sets, repeated no-credit encounters and bounded notification queues. |
| Client | English/Spanish/Chinese, keyboard-only navigation and narration text; compact viewport and ordinary/wide desktop sizes; multiple GUI scales; long and non-ASCII names. |
| Real native actions | Campaign declaration/withdrawal, muster/dismiss/recover and bilateral recruit transfer from real clients. Prior snapshot/privacy tests alone do not cover these mutations. |
| Cache/privacy | Server A → disconnect → server B, late replies, provider reload/removal, changed discovery, private proposals and ballots. |

Complete the remaining human-facing validation honestly: spoken narrator audio on a host with the required library; materially different fonts/resource packs; representative 1080p/1440p layouts and GUI scales not covered by the recorded runs; a brief fresh-player walkthrough of registration, founding and recovery. An unavailable test must be reported as unavailable, not counted as passed.

### Final coding-agent deliverables

1. Focused commits/PRs ordered by milestone, with a concise before/after behavior and relevant validation.
2. A completed finding ledger: fixed, already fixed, deliberately deferred, or not reproduced—with evidence for each disposition.
3. Regression tests for each confirmed nontrivial bug; fault and migration fixtures for persistent/native effects.
4. Exact production and companion artifact versions, commits and SHA-256 hashes, plus configs/pack overlays used for acceptance.
5. Updated user/operator/datapack/addon documentation and translations; no stale promise that a proposal is already operational.
6. Release notes and an upgrade/recovery runbook. Preserve pending operations and provide a supported capacity-maintenance path.
7. A concise list of any genuinely new design questions discovered during implementation. Do not reopen the two choices already approved here.

## 8. Source ledger

Repository source links below are pinned to the reviewed Kingdoms commit. Companion links are pinned to the inspected public commits where established. External mod pages were consulted on 27 October 2026; their descriptions inform comparison rather than runtime compatibility claims.

The principal repository documents are the README/CHANGELOG, MODMAP, September audit, October review/remediation plan, village identity diagnosis, Capitals-inspired implementation guide, factions integration roadmap, R1–R4 delivery records, usability implementation plan/record, and Book interface design/validation. Current source and later delivery records take precedence over earlier aspirational wording.

| Repository/source | Reviewed commit or evidence | Scope of comparison |
| --- | --- | --- |
| MCAKingdoms | `01f8d14a0eece06c8c06f9d2bf2b814d244c5506` | Main code, resources, tests, plans and validation records. |
| MCAQuests | `784f5d5b7061ae3e8c441467204e7e8095379169` | Public API, Kingdoms/institutional bridges, receipt outbox, turn-in contract and version metadata. |
| MCAReputation | `6e90ee4459ac2182691af30f6e6748427d161065` | Public standing API, consumer journal and optional dependency metadata. |
| MCAConversations | `184f8095c95019f69e2f24a2aa5f981aed34a8c3` | [Kingdoms identity](https://github.com/otectus/MCAConversations/blob/184f8095c95019f69e2f24a2aa5f981aed34a8c3/src/main/java/dev/otectus/mcaconversations/compat/KingdomsIdentity.java) and [gate bridge](https://github.com/otectus/MCAConversations/blob/184f8095c95019f69e2f24a2aa5f981aed34a8c3/src/main/java/dev/otectus/mcaconversations/compat/KingdomBridge.java). |
| MCACrime | `719fc401a57d1b3dd43da7ea64c35318ccbe4770` | [Institutional workshop API](https://github.com/otectus/MCACrime/blob/719fc401a57d1b3dd43da7ea64c35318ccbe4770/src/main/java/dev/otectus/mcacrime/api/InstitutionalServiceApi.java); not a review of every Crime integration. |
| MCA Capitals | `89101881ea3ed683f03b8d4d3c8e5d21f0522908` plus official project description | Public-source architecture and advertised features; no released-jar runtime verification. |

[doc-usability]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/Usability-Implementation-Record.md
[doc-book]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/Book-Interface-Design.md
[doc-readme]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/README.md
[doc-changelog]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/CHANGELOG.md
[doc-modmap]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/MODMAP.md
[doc-audit]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/MCAKingdoms-Audit.md
[doc-review]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/REVIEW.md
[doc-remediation]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/MCAKingdoms-Review-Remediation-Plan.md
[doc-factions-roadmap]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/docs/MCAKingdoms-Factions-Integration-Audit-and-Roadmap.md
[doc-ux-plan]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/MCAKingdoms_Usability_Implementation_Plan.md#L506
[src-identity]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/citizen/CivicIdentityStore.java#L17
[src-main]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java#L250
[src-settlement-data]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/core/SettlementSavedData.java#L137
[src-legacy]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/persistence/LegacyRename.java#L159
[src-quest-bridge]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/compat/quests/receipts/QuestCompletionBridge.java#L23
[src-government-maintenance]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/politics/GovernmentService.java#L1311
[src-evolution-tick]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionService.java#L233
[src-interaction]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/interaction/InteractionNetwork.java#L127
[src-transfers]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/RecruitTransferService.java#L15
[src-mobilization]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/warfare/mobilization/MobilizationService.java#L31
[src-political-tasks]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/interaction/PoliticalTasks.java#L25
[src-task-meta]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/interaction/TaskMeta.java#L92
[src-evolution]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionService.java
[src-drama]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/drama/DramaService.java
[src-evolution-generate]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionService.java#L284
[src-transfer-data]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/RecruitTransferData.java#L40
[src-gallery]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/client/guide/GuideKingdoms.java#L32
[src-guide-article]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/client/guide/GuideArticle.java#L95
[src-client-state]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/client/ClientPresentationState.java#L117
[src-evolution-state]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/evolution/EvolutionState.java#L7
[src-political-data]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/politics/PoliticalSavedData.java#L36
[src-knowledge]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/knowledge/SettlementKnowledge.java#L151
[src-core-page]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/core/KingdomsServiceImpl.java#L198
[src-civic-tasks]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/src/main/java/dev/otectus/mcakingdoms/interaction/CivicTasks.java#L14
[src-integration-script]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/tools/test/integration_runtime.py#L5
[src-r2-verify]: https://github.com/otectus/MCAKingdoms/blob/01f8d14a0eece06c8c06f9d2bf2b814d244c5506/tools/test/verify_r2_pack.py#L5
[forge-clone]: https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/patches/minecraft/net/minecraft/server/level/ServerPlayer.java.patch
[quests-integration]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/compat/KingdomIntegration.java#L35
[quests-institution]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/quest/InstitutionalCommissionBridge.java#L31
[quests-api]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/api/McaQuestsApi.java#L41
[quests-manager]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/quest/QuestManager.java#L119
[quests-outbox]: https://github.com/otectus/MCAQuests/blob/784f5d5b7061ae3e8c441467204e7e8095379169/src/main/java/dev/otectus/mcaquests/state/CompletionReceiptOutbox.java
[rep-outbox]: https://github.com/otectus/MCAReputation/blob/6e90ee4459ac2182691af30f6e6748427d161065/src/main/java/dev/otectus/mcareputation/state/StandingOutbox.java#L52
[capitals-project]: https://www.curseforge.com/minecraft/mc-mods/mca-capitals
[capitals-source]: https://github.com/MajesttyX/mca_capitals_addon/tree/89101881ea3ed683f03b8d4d3c8e5d21f0522908
[minecolonies-hall]: https://minecolonies.com/wiki/buildings/townhall/
[minecolonies-requests]: https://minecolonies.com/wiki/systems/request/
[recruits-project]: https://www.curseforge.com/minecraft/mc-mods/recruits
[minefortress-about]: https://www.minefortress.net/about
[living-villages]: https://modrinth.com/mod/recruits-living-villages
