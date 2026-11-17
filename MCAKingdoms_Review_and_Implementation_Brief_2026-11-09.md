# MCA: Kingdoms — review and implementation brief

**Review date:** 9 November 2026  
**Target:** the current working tree in this repository, including its existing uncommitted Townstead work  
**Baseline:** branch `main`, HEAD `5602f2ca5c90ee06138408f55494a6629f048a9c`  
**Platform:** Minecraft 1.20.1, Forge 47.4.23, official 1.20.1 mappings, Java 17, Gradle 8.8; mod version 0.3.0

## 1. Purpose and conclusions

Use this document as an implementation handoff. It addresses correctness, recovery, integration completeness, and the player experience behind these two reports:

> “Any chance of Waystones integration that uses the settlement name when applicable? Of course, for now I can simply rename the Waystone, but it would be handy if it does that by default”

> “Very confusing”

The mod already has substantial working infrastructure: guided tasks, named target selectors, review screens, a searchable Book of Kingdoms, server capability explanations, discovery privacy, persistent political records, and automated tests. Preserve those systems. The appropriate work is to finish their integration and correct specific lifecycle gaps.

The highest-priority findings concern settlement identity durability, delivery/refund recovery, construction consent and rollback, and development records after settlement merges. The usability findings are also concrete: some feature summaries contradict the actual task gates, newer tasks do not consistently follow the established recovery conventions, unrelated activity invalidates reviews, and Townstead tasks fall into the wrong category and sometimes require internal identifiers.

Waystones integration is absent from the reviewed implementation. It is feasible for the installed Forge versions, but a naming-event listener alone would miss player placement, delayed settlement discovery, and duplicate-name handling. Section 5 specifies the complete requested behavior and the exact upstream contracts verified during this review.

This is a broad source and validation review, not a guarantee that every possible bug has been found. Source-confirmed failure paths below have not all been reproduced in a running game. The brief explicitly distinguishes those findings from new feature requirements, usability hypotheses, and checks still needed.

### Instructions for the implementing agent

1. Read applicable AGENTS.md instructions, the Minecraft mod development skill, and relevant project guidance. Recheck the working tree before editing: it already contains extensive user work. Do not reset, overwrite, or recreate that work.
2. Treat file names and method names below as navigation anchors. Line numbers refer to the reviewed working tree and will move.
3. Implement in the dependency order in section 8. Keep each change reviewable; do not turn this into a loader port, framework rewrite, or expansion of the mod's economic scope.
4. Preserve persistent IDs, namespace compatibility, public API boundaries, discovery privacy, server authority, and optional dependency loading. Schema changes require migration and recovery evidence.
5. Reproduce each confirmed defect with a meaningful regression test before or alongside its fix. Reuse passing unrelated checks. A test that merely repeats the new implementation is not sufficient.
6. Separate an action being accepted, its effects being completed, and its recovery being unresolved. Never report “nothing changed” after a world or inventory effect may already have occurred.
7. Complete the affected packaged-server and client checks. Compilation or standalone GameTests alone do not establish companion integration or usability.

## 2. Review scope and validation evidence

### Scope

The review traced settlement detection, registration and merges; persistence and capacity policy; government and companion ownership boundaries; development and physical documents; interaction metadata, forms and status; Townstead integration and reloads; client onboarding and transient state; and the testing/release guidance. Existing tests and evidence for civic, warfare, evolution, dynasty, and optional companions were consulted. The deeper new findings concentrate on development and its connections to the older systems.

No production fixes were made during the review. This Markdown file is the requested deliverable. Existing changed and untracked files belong to the baseline, not to this review.

### Checks actually performed

| Check | Result | Limits and evidence |
| --- | --- | --- |
| `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms check` | PASS | The Java compilation and test tasks were up to date. Existing XML reports contain 395 tests: 394 passed, 1 skipped, 0 failures/errors. Includes the project's API/add-on checks. Log: `/tmp/gradle-MCAKingdoms-check-20261109-135502.log`. |
| `python3 -I tools/test/validate_resources.py` | PASS | Resource JSON and placeholder checks passed; en_us, es_es and zh_cn each have 4,598 language keys. This establishes structural parity, not translation quality or layout quality. |
| `python3 -I tools/test/environment.py` | Completed | Found Java and the local installation/pack. An existing accepted EULA was available. Xvfb was unavailable. |
| `/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAKingdoms runGameTestServer -PrunDir=/tmp/mcakingdoms-audit-gametest-ffh6815p` | PASS, fresh world | All 112 required GameTests passed. This was a standalone run. Its exact MCA lifecycle scenario explicitly logged `SKIP exact MCA lifecycle: standalone runtime`. Log: `/tmp/gradle-MCAKingdoms-runGameTestServer-20261109-135806.log`; world/logs remain in the temporary run directory. |
| Installed Waystones/Balm artifact inspection and upstream source inspection | Completed | Verified the exact naming/activation contracts described in section 5, including the important persistence and event-order limitations. This was API inspection, not an integration runtime test. |

The skipped unit test is the conditional installed-public-API probe in TownsteadBindingTest. Do not turn either this skip or the standalone MCA lifecycle skip into a companion-compatibility claim.

The GameTest run used a fresh temporary directory and copied an already accepted local EULA through the environment helper. It did not reuse the project's existing world. The usual GameTest script was bypassed because of AUD-18.

### Not established by this review

- A newly built distributable running with the full current modpack.
- Two-client synchronization, actual on-screen usability, audible narration, shader compatibility, or key conflicts.
- Forced-crash reproductions for the newly identified persistence windows.
- A completed 10/50/200-settlement performance matrix.
- Public availability of companion capabilities that were previously tested only using local companion builds.

Preserve historical evidence, but label it with its source revision and exact companion tuple. It does not automatically certify the dirty working tree reviewed here.

## 3. Priorities and work index

**P1:** high priority; possible lost/duplicated resources, unintended world changes, inconsistent durable identity, or a central user-requested outcome. Fix correctness findings before shipping the affected enabled feature.  
**P2:** functional correctness, integration reliability, or substantial player-facing incompleteness.  
**P3:** smaller presentation or documentation inconsistency.

“Confirmed” below means the behavior follows from inspected source, unless runtime evidence is explicitly given. “Requirement” is a proposed completion criterion, not a claim that existing code violates an already implemented contract.

| ID | Priority | Finding or deliverable | Evidence class |
| --- | --- | --- | --- |
| AUD-01 | P1 | Durable settlement identity and dependent-store updates are not one recoverable operation | Confirmed source path |
| AUD-02 | P1 | Development contributions/refunds cross an unjournaled inventory boundary | Confirmed source path |
| AUD-03 | P1 | Construction and its saved journal can disagree after failure/restart | Confirmed source path |
| AUD-04 | P1 | Existing construction does not consistently honor withdrawn consent and changed safety conditions | Confirmed source path |
| AUD-05 | P1 | Repair/rollback can adopt or overwrite player changes and can abandon unloaded work | Confirmed source path |
| AUD-06 | P1 | Development merges omit completed assets, economy state, and current kingdom authority | Confirmed source path |
| AUD-07 | P2 | Retention can remove production sources and forget request identity | Confirmed source path |
| AUD-08 | P1 | Disabling newer features can strand recovery actions and refunds | Confirmed source path |
| AUD-09 | P2 | Charter sealing can persist a record without delivering its item state | Confirmed source path |
| AUD-10 | P2 | Global revisions invalidate unrelated reviews; newer results lack useful live checks | Confirmed source path / UX completion |
| AUD-11 | P2 | A new POI settlement in already loaded chunks can remain undiscovered; scan queue lacks a runtime cap | Confirmed scheduling gap |
| AUD-12 | P2 | Spatial indexing accepts geometry that can demand unbounded work | Confirmed validation gap |
| AUD-13 | P2 | Townstead mappings publish before the shared successful-reload boundary | Confirmed source path |
| AUD-14 | P2 | Availability summaries disagree with actual requirements | Confirmed source path |
| AUD-15 | P2 | Townstead task routing, raw identifiers and unavailable-career wording confuse ordinary play | Confirmed presentation paths |
| AUD-16 | P3 | Pinned-task availability can survive a server switch | Confirmed state-lifecycle gap |
| AUD-17 | P2/P3 | Current guidance contains contradictory or stale behavior claims | Confirmed documentation gaps |
| AUD-18 | P2 | GameTest launcher can move the wrong world | Confirmed tooling defect |
| WAY-01 | P1 | Default Waystones names from applicable settlements | User requirement; section 5 |
| UX-01 | P1 | Clear first-session path and comprehensible ongoing tasks | User requirement; section 6 |
| VAL-01 | P2 | Close the affected runtime, usability and performance evidence gaps | Validation requirement; section 7 |

## 4. Confirmed findings and implementation instructions

### AUD-01 — Make settlement identity changes durably recoverable

**Evidence:** [SettlementSavedData.java](src/main/java/dev/otectus/mcakingdoms/core/SettlementSavedData.java), add/changed/redirect, lines 96–132; [KingdomsServiceImpl.java](src/main/java/dev/otectus/mcakingdoms/core/KingdomsServiceImpl.java), candidate registration and merge, approximately lines 291–355 and 435–466; [DurableSavedData.java](src/main/java/dev/otectus/mcakingdoms/persistence/DurableSavedData.java); [McaKingdoms.java](src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java), settlementMerged, lines 241–259; [CivicService.java](src/main/java/dev/otectus/mcakingdoms/civic/CivicService.java), lines 72–78.

The core registry changes memory and calls setDirty. Its durable write happens on save. The merge event then immediately rewrites some dependent stores using their synchronous durable commits. The event-handler comment says the merge has “already committed,” but the settlement redirect is not yet necessarily on disk.

A crash after a dependent-store rewrite and before the root save can restore the old settlement registry alongside already migrated civic/evolution records. A newly created settlement can similarly be referenced by a durably saved dependent record before its own identity is saved. Atomic replacement of individual files does not make this a cross-file transaction.

**Implement:**

1. Identify all operations that create, retire, redirect, or change the ownership of settlement identity and all durable consumers. Define one explicit root commit boundary.
2. Persist the root identity/redirect successfully before publishing a durable-dependent event or reporting final success. If that write fails, retain the previous in-memory and durable state.
3. Journal dependent migrations using a stable operation ID and canonical source/target IDs. Record outstanding consumers so startup or maintenance can retry incomplete migration. Each consumer must be idempotent.
4. Read through canonical redirects while migration is pending. Do not claim that read-time resolution alone repairs aggregations or ownership; AUD-06 covers those.
5. Distinguish “identity committed; dependent reconciliation pending” from a wholly refused merge. Preserve broken or newer-schema files; do not initialize an empty replacement to make recovery appear successful.
6. Ensure event/listener exceptions cannot turn a committed mutation into an ambiguous repeated request. Add a durable outbox where an event drives a necessary cross-store effect.

**Acceptance:** crash/fault injection before root write, after root write, between two dependent commits, and before acknowledgement. After restart there is one canonical identity, no orphaned reference, no duplicate migration effect, and a visible pending status for any unreadable dependent store. Include creation followed immediately by founding/recognition, and a merge with both source and target carrying records.

### AUD-02 — Journal inventory contributions and refund delivery

**Evidence:** [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), change at lines 94–112, contribute at 170–211, and claimRefunds at 326–345.

Contributions remove inventory stacks before the development sidecar commits. An exception can return the stacks in the same process, but a process crash is outside that exception handler. The sidecar may retain a contribution while the player's last inventory save still contains its items.

Refund claiming removes debts and commits before giving items to the player. A crash between these steps can lose the refund. Missing registry items are skipped after the debt has already been removed. A failed inventory insertion can also produce a world item whose persistence is independent of the refund ledger.

**Implement:**

1. Introduce a durable delivery/claim state machine with stable operation IDs, item quantities, actor, project, and explicit stages. Persist the intent before consuming or delivering inventory.
2. Couple recovery to durable player-side evidence, not just in-memory inventory changes. Prove the save ordering for contribution debit and refund credit. An operation ID in a sidecar alone cannot establish that an item reached the player's saved inventory.
3. Serialize operations for the affected player and project. Recheck the reviewed inventory plan and demand on apply. Preserve the existing exclusion of named, enchanted, or otherwise tagged items.
4. On an ambiguous crash, reconcile only from adequate evidence. Keep an unresolved escrow/claim state when the system cannot safely prove whether delivery occurred; never guess by issuing another refund.
5. Keep unknown item IDs as outstanding debts with an actionable diagnostic. Inventory-full handling must remain recoverable; prefer retained claimable balance to an untracked drop.
6. Provide read-only status and an authorized reconciliation path. Do not require the feature to remain enabled to recover owed resources.

**Acceptance:** force termination around every durable boundary, then restart with saved player data. Item conservation must hold: the player, recoverable escrow, completed contribution, and acknowledged refund account for each item exactly once. Cover full inventory, disconnect/death, missing item mod, repeated request, failed write, partial delivery, and feature disablement.

### AUD-03 — Reconcile construction journals with durable world state

**Evidence:** [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), maintain lines 360–380 and build lines 408–440.

The worker changes a block, marks its placement complete in a copied journal, then commits the sidecar. It ignores the boolean result of setBlock. There is no durable world-effect transaction. If the sidecar write fails, the world may already have changed. In the opposite crash ordering, the journal can be saved while the chunk still contains its previous state; later passes skip every placement already marked placed.

**Implement:**

1. Define placement stages such as planned, intent committed, applied pending verification, and verified. Store the original state, intended effective state, project ID, and operation ID.
2. Write placement intent before changing the world. After mutation, check the return value and actual state. Do not count or announce a refused placement as complete.
3. On restart and chunk availability, reconcile both directions: a placed block with an unfinished journal, and a completed journal entry whose chunk has not persisted the block.
4. Use ownership and player-change checks from AUD-05. Never recreate a missing completed block merely because the journal says it once existed; a player may have intentionally removed it after completion. Distinguish recoverable in-flight placement from later world editing.
5. Tie completion points, output assets, and completion notifications to a single verified operation. Recovery must not award points or announce completion twice.
6. Bound recovery work per tick and restrict it to loaded chunks. Keep unavailable positions pending.

**Acceptance:** force sidecar failure after a placement; kill before/after chunk save and sidecar save; inject setBlock failure; edit or remove a block before recovery. No false completion, duplicate credit, unapproved reconstruction, or loaded-chunk expansion is permitted.

### AUD-04 — Honor withdrawn construction consent and current plot safety

**Evidence:** [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), resume lines 226–234, optIn/protectHome lines 296–322, maintain/step lines 360–405; [PlotSurvey.java](src/main/java/dev/otectus/mcakingdoms/development/PlotSurvey.java), lines 45–82.

Settlement opt-in is checked when a FUNDED work advances. READY/BUILDING work does not check it again. Opting out therefore does not stop existing construction despite the player-facing “no longer allows construction” promise. Home exclusion and several other survey conditions are also not revisited during building. Resume says it re-surveys but merely changes state.

There are additional mode-transition ambiguities: OFF returns without transitioning active projects; re-enabling can resume them immediately. A funded physical plan in a lower mode can be completed as an abstract milestone. This behavior needs an explicit reviewed policy rather than accidental dependence on the mode at the next tick.

**Implement:**

1. Make opt-out, home protection, construction pause, and mode changes take effect before the next block placement. Check relevant current policy per bounded batch, or invalidate all affected cached surveys with reliable revisions.
2. Re-evaluate settlement identity/current kingdom, approval authority, construction consent, dimension policy, bounds, protected homes, recognized-building exclusion, and native protection checks.
3. Resume must survey the remaining cells and show changed blockers. Do not claim resurvey if only the state changes.
4. Persist the chosen work kind: abstract milestone or physical construction. A server-mode downgrade must pause a physical plan rather than silently consume it as a milestone. If conversion is supported, expose it as an explicit reviewed action with its costs.
5. Specify operator approvals: either document an intentional durable operator override or revalidate current operator authority. The present boolean must not silently imply whichever policy is convenient.
6. Distinguish “Townstead absent” from “installed building evidence temporarily unreadable.” If safety depends on installed building evidence, pause that construction instead of treating an exception as proof of an empty plot.

**Acceptance:** while a multi-block work is building, opt out, protect an overlapping home, remove the approver's office, change allowed dimensions, add a recognized building, lower the mode, then re-enable. Each unsafe change must stop the next affected placement, retain progress, and give a precise recovery reason.

### AUD-05 — Preserve ownership and safety during repair and rollback

**Evidence:** [PlotSurvey.java](src/main/java/dev/otectus/mcakingdoms/development/PlotSurvey.java), lines 116–129; [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), repair lines 254–292 and build line 420.

Both unchanged and ours compare only the block's registry ID, discarding block-state properties. A rotated or waterlogged block can be treated as unchanged. An unplaced journal cell containing the intended block type is adopted as the project's work without proof that the project placed it.

Resurvey removes every blocker containing the text “occupied” if the project has placed any block. This can remove a blocker at an unrelated cell, record a player's new block as the new “before” state, and later replace it. Rollback changes blocks without rechecking the protection path, counts unloaded placements as “changed since by someone else,” and closes the project even when cleanup remains outstanding.

**Implement:**

1. Replace text-based blockers with structured reasons containing position, cause, and relevant evidence. Localization belongs at presentation time.
2. Exempt only the exact cells proved to belong to this work. A matching block type alone is not ownership. Use the in-flight evidence from AUD-03.
3. Compare full block states with a narrowly documented normalization for genuinely neighbor-derived properties. Preserve player-set orientation, waterlogging, and other meaningful state. Do not adopt a broad “ignore all properties” rule.
4. Before rollback, recheck current state, ownership, current protection rules, and permission to mutate. Verify the mutation succeeded.
5. Separate changed cells, protected cells, unloaded cells, and invalid original states. Keep rollback pending/recoverable while eligible cells remain unavailable. Provide an explicit finalization path that reports retained cells.
6. Never expand a resurvey to overwrite a newly occupied cell. Repair should stop and explain what the player must resolve.

**Acceptance:** place one project block and a player's different block in another planned cell, then resurvey. The player block must remain a blocker. Also cover same-block/different-state edits, a matching player-placed block before construction, a new land claim, partial unloading, failed restore, and restart midway through rollback.

### AUD-06 — Migrate development and economy state through merges and ownership changes

**Evidence:** [McaKingdoms.java](src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java), settlementMerged lines 246–259; [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), lines 370–376, 465–507; [DevelopmentSavedData.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentSavedData.java), Project.movedTo; [DevelopmentEvents.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentEvents.java), reconcile lines 185–214; [DevelopmentTasks.java](src/main/java/dev/otectus/mcakingdoms/interaction/DevelopmentTasks.java), settlement project listing.

The central merge handler has no development consumer. Maintenance redirects only open projects. Completed projects and economy records stay under the retired ID, while normal observation reads the survivor's exact ID. The manual reconcile command redirects projects and recomputes points, but does not merge stored goods or consent. Project.movedTo retains the old kingdom.

This can hide completed development, strand output, and leave permission checks tied to the former kingdom after reassignment. It is not enough that some settlement lookups follow redirects.

**Implement:**

1. Add a development migration consumer to AUD-01's durable reconciliation mechanism. Include completed projects/assets, open projects, economy aggregates, trade history references, refunds/provenance, and relevant presentation queries.
2. Reconcile current governing kingdom independently from historical issuer/proposer fields. Keep historical provenance but authorize new actions against the settlement's current authority.
3. Define aggregation rules: retain all economic value, sum compatible balances safely, avoid double-counting assets, and retain overflow in a recoverable representation instead of truncating it.
4. Use a conservative consent rule. A merge or reassignment must not silently opt new land into construction. Pause inherited physical works for the appropriate current authority to review.
5. Make canonicalization idempotent on load, live merge, and manual reconciliation. Persist migration completion. A sidecar failure must be reported and retryable.
6. Update exact-ID filters and selectors to resolve canonical identity consistently.

**Acceptance:** merge two settlements with completed production, stored goods, open projects, pending refunds, and different opt-in states. Repeat reconciliation and restart. Totals must be preserved once, works must remain visible, and only current authority may approve/resume. Include reassignment without a merge.

### AUD-07 — Separate economic assets and replay safety from retained history

**Evidence:** [DevelopmentSavedData.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentSavedData.java), commit/pruneClosed lines 156–179; [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), receipt handling lines 98–107 and output calculation lines 469–471; [DevelopmentEvents.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentEvents.java), lines 197–210; [Capacity-and-Maintenance.md](docs/Capacity-and-Maintenance.md).

When the project collection exceeds 512 entries, closed projects are removed. Completed projects are also the source used to calculate daily production. Removing one therefore removes productive capacity. Reconciliation subsequently recomputes points from surviving projects, potentially reducing previously earned development.

Receipt retention removes old request IDs beyond 4,096 entries. The development service treats an absent receipt as a new request. For repeatable actions such as contribution, replaying an evicted request is not inherently refused. This contradicts the maintenance table's inherited “reused request is then refused” description. This is an idempotency contract problem, not evidence of a remotely exploitable packet attack.

**Implement:**

1. Persist completed productive assets or an equivalent aggregate independently of display-history retention. Include earned points, role contribution, output, and originating work identity.
2. Define template-reload policy. Historical earned points should not disappear simply because a template is removed; any intentional rebalance must be explicit and documented.
3. Archive or compact display/history records only after their live effects and references are represented elsewhere.
4. Specify replay lifetime and enforce it: retain sufficient domain identity/tombstones, or use explicitly expiring server-issued operation identities that are refused once no longer valid. Do not merely increase the receipt limit.
5. Audit document and dynasty retention for the same documented promise; extend fixes only where their actual behavior requires it.
6. Correct capacity diagnostics so “automatic retention,” “operator compaction,” and “refused at capacity” describe the real policy.

**Acceptance:** exceed the project/history bounds while keeping a productive old work. Output, earned points and references must survive pruning and reconciliation. Evict an old request receipt, replay its operation, and prove that no material debit, refund, or duplicated award occurs.

### AUD-08 — Keep recovery usable when a feature is disabled

**Evidence:** [TaskMeta.java](src/main/java/dev/otectus/mcakingdoms/interaction/TaskMeta.java), Intent and feature gates, lines 110–157; [DevelopmentService.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentService.java), minimum-mode checks at change/cancel/repair/claimRefunds; [DocumentService.java](src/main/java/dev/otectus/mcakingdoms/document/DocumentService.java), revoke lines 184–199.

The established task-intent policy says inspecting, canceling, verifying and restoring retained operations remain available when new operations are disabled. The newer development recovery tasks are not classified accordingly and their services require active development modes. Refunds become inaccessible in OBSERVE/OFF. Revocation is also blocked by the heraldry creation switch and even reports a message about sealing.

**Implement:**

1. Extend the intent matrix across development, dynasty and documents. Classify each actual operation; do not infer safety from a task name alone.
2. Split repair into “survey/resume new construction” and “restore/reconcile existing effects” where necessary. Split an opt-in toggle's enabling and disabling semantics.
3. Allow authorized inspection, pause/opt-out, cancellation, refund recovery, document revocation and safe restoration while new activity is disabled. Retain writable-store checks and any provider needed to touch native state.
4. Apply the same policy in services, commands, menus and direct APIs. UI metadata alone is insufficient.
5. Explain separate reasons: feature disabled, provider unavailable, read-only records, missing authority, or recovery awaiting evidence.

**Acceptance:** create each kind of outstanding operation, disable the feature, and complete or inspect its safe recovery path. New work must still be refused. Test a disabled feature with a missing native provider and with a read-only damaged store.

### AUD-09 — Recover sealed document records whose item update was not saved

**Evidence:** [DocumentService.java](src/main/java/dev/otectus/mcakingdoms/document/DocumentService.java), seal lines 58–124.

The document and request receipt are committed before the charter in the player's hand receives its NBT reference. A crash can leave a valid issued record and a still-blank saved item. Replaying the request returns “the charter in your hand now carries this record” without verifying or restoring the item.

**Implement:**

1. Record issuance and item attachment/delivery separately. Reuse the operation ID and recovery approach from AUD-02 where appropriate.
2. On retry, inspect the actual item and receipt. Return success only if the claimed attachment exists, or safely complete a pending attachment to an eligible blank charter.
3. If the player moved, replaced or lost the original item, provide an explicit recover/reissue action that preserves document identity and makes the treatment of copies clear.
4. Do not let recovery grant an office, title or treaty authority; the document remains evidence of the authoritative ledger.
5. Preserve revocation and the existing one-item stack limit. Avoid creating a second independent issued record to solve an attachment failure.

**Acceptance:** crash after record commit and before player save, then retry after reconnect; also test a changed held item, revoked record, full inventory, and repeated recovery. Inspecting the resulting charter must resolve the original record, and messages must describe actual delivery.

### AUD-10 — Scope review versions and provide live operation status

**Evidence:** [DevelopmentTasks.java](src/main/java/dev/otectus/mcakingdoms/interaction/DevelopmentTasks.java), version lines 30–31 and action registration; [DocumentTasks.java](src/main/java/dev/otectus/mcakingdoms/interaction/DocumentTasks.java), lines 25–26; [InteractionNetwork.java](src/main/java/dev/otectus/mcakingdoms/interaction/InteractionNetwork.java), apply validation line 188, execute lines 245–250, recheck lines 268–284.

Development reviews depend on the entire development-store revision. Another settlement's construction can change it every maintenance pass and repeatedly invalidate a player's otherwise valid review. Document tasks similarly depend on global document and political revisions.

Several newer handlers return strings rather than typed outcomes with operation references. CHECK retains the original result when there is no operation/probe. An accepted proposal may correctly be a completed submission, but the player still needs an accurate way to inspect the continuing work.

**Implement:**

1. Use operation-specific review fingerprints: selected record state/revision, relevant template/rule version, current authority, and any reviewed inventory/plot inputs. An unrelated project or kingdom must not invalidate the form.
2. Retain service-side revalidation at apply. Narrowing the fingerprint must not weaken authorization or allow a stale material total.
3. Return typed outcomes for continuing or recoverable operations and register read-only probes. Include a stable project/document/succession/operation identity as appropriate.
4. Distinguish “proposal recorded” from “construction complete.” CHECK must read current state without invoking the original mutation.
5. Show material demand, delivered quantities, remaining quantities, construction mode, exact selected settlement, and the next available action in development status/review.
6. Keep user-entered text and unaffected selections when a relevant change requires review. Explain what changed.

**Acceptance:** keep construction running in settlement A while player B reviews a work in B; B can apply if its relevant state is unchanged. A changed B demand or permission still invalidates safely. CHECK reflects completion/failure after restart without consuming items or repeating a decision.

### AUD-11 — Discover newly built settlements without requiring chunk reloads

**Evidence:** [McaKingdoms.java](src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java), chunkLoaded and startup scan scheduling; [KingdomsServiceImpl.java](src/main/java/dev/otectus/mcakingdoms/core/KingdomsServiceImpl.java), enqueueChunk/tickDiscovery lines 629–654; [PoiClusterDetector.java](src/main/java/dev/otectus/mcakingdoms/settlement/PoiClusterDetector.java); [CoreTasks.java](src/main/java/dev/otectus/mcakingdoms/interaction/CoreTasks.java).

The normal scan queue is fed by chunk loading and startup work. There is no corresponding general POI-change scheduling path for a vanilla/standalone settlement built after its loaded chunks have already been scanned. “Read this settlement” performs lookup. Walking around inside continuously loaded chunks need not discover the new cluster. Companion village events help only when that companion is present.

The active chunk queue also grows without a capacity bound; the early-startup pending queue's bound does not bound this runtime queue.

**Implement:**

1. Schedule debounced, loaded-chunk-only rescans after relevant bed/workstation/bell changes, accounting for POI updates becoming visible after the block event.
2. Add a bounded, fair recovery scan for nearby loaded chunks so missed changes and changes from other mods eventually receive coverage. Prefer reuse of existing detector queues over a second scanner.
3. Give “Read here” a clear pending/not-recognized explanation and, if appropriate, a rate-limited request to scan the player's loaded surroundings.
4. Bound and deduplicate queued work by dimension/chunk. Use fair cursors or priorities so a busy dimension cannot starve another. Overflow should postpone coverage, not lose it indefinitely.
5. Preserve manual names, registered IDs, merge rules and discovery privacy when rediscovering. Never load distant chunks to satisfy a UI request.

**Acceptance:** in a standalone server, wait for the initial scan, build a qualifying POI cluster without unloading the area, and observe discovery within a documented bound. Test break/rebuild, duplicate events, rapid exploration, dimension changes, and a player requesting repeated scans.

### AUD-12 — Bound geometry before allocating the spatial index

**Evidence:** [SettlementBounds.java](src/main/java/dev/otectus/mcakingdoms/api/SettlementBounds.java), constructor/around; [SettlementCandidate.java](src/main/java/dev/otectus/mcakingdoms/api/SettlementCandidate.java); [SpatialSettlementIndex.java](src/main/java/dev/otectus/mcakingdoms/settlement/SpatialSettlementIndex.java), visit; [KingdomsServiceImpl.java](src/main/java/dev/otectus/mcakingdoms/core/KingdomsServiceImpl.java), new-candidate registration.

Bounds validate ordering, and radius validates non-negativity. The spatial index enumerates every chunk in the rectangle. Initial external/add-on candidates and persisted geometry can reach this path without a limit on indexed area. Extremely large but ordered bounds can demand excessive memory or server-thread time. Arithmetic around a radius also needs overflow handling.

**Implement:**

1. Validate spans and covered-chunk counts with overflow-safe long arithmetic before allocating or persisting index entries.
2. Apply the rule at API registration, loading, reconciliation and index rebuild, not just at a command argument.
3. Choose a documented domain bound compatible with existing legitimate settlements. If very large regions are supported, use a bounded secondary structure rather than enumerating their entire chunk rectangle.
4. Preserve invalid/unsupported saved records for repair and report their IDs. Do not silently shrink or delete them.
5. Keep new API validation errors actionable for add-on developers.

**Acceptance:** malformed and extreme geometry is refused or isolated quickly without allocating the rectangle. Boundary-valid existing saves still load. Include negative coordinates, integer limits, large radii, and a reload/rebuild.

### AUD-13 — Publish Townstead mappings at the successful reload boundary

**Evidence:** [TownsteadMappings.java](src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadMappings.java), apply lines 173–181 and provider validation lines 300–349; [McaKingdoms.java](src/main/java/dev/otectus/mcakingdoms/McaKingdoms.java), datapackSync lines 181–199; [DevelopmentDefinitions.java](src/main/java/dev/otectus/mcakingdoms/development/DevelopmentDefinitions.java) for the existing staged pattern.

TownsteadMappings replaces its static snapshot during its reload listener's apply. Other major definitions stage their updates until successful datapack synchronization. A failure in a later reload step can leave Townstead's new mappings paired with the previous committed definitions.

**Implement:**

1. Stage parsed mappings and diagnostics. Publish them at the same successful commit boundary as related definitions.
2. Perform provider-ID validation on the staged generation in a defined order, then publish a coherent snapshot and revision. Avoid listeners racing to observe half-validated mappings.
3. Invalidate dependent caches only when the new snapshot becomes current.
4. Preserve the intended per-entry validation policy: one invalid optional mapping does not necessarily invalidate every valid mapping. Do not accidentally change optional Townstead prerequisites into mandatory ones.
5. Keep failed-reload diagnostics distinct from committed active-state diagnostics.

**Acceptance:** make a later reload listener fail after Townstead prepares/applies; all active systems retain the previous coherent generation. A successful reload switches them together. Repeat with invalid provider IDs, Townstead absent, and two consecutive reloads.

### AUD-14 — Use one truthful capability model for tasks and summaries

**Evidence:** [ServerFacts.java](src/main/java/dev/otectus/mcakingdoms/interaction/ServerFacts.java), forPlayer lines 55–95 and workshopLine lines 120–126; [TaskMeta.java](src/main/java/dev/otectus/mcakingdoms/interaction/TaskMeta.java), feature requirements and availability lines 238–250.

Paid-workshop summary checks civic switches and Crime, but not the Quests capabilities required by the actual workshop task. It can say commissions are on while the task refuses them. The player feature summary also omits several newer systems. Its Capitals explanation assumes readable per-kingdom sharing even when the actual state can be all-read-only. Townstead's general task gate accepts a v1 backend without checking each task's actual capability and feature switches. Availability probing catches runtime failures and continues toward AVAILABLE.

**Implement:**

1. Define shared server-side capability facts consumed by task availability, the start page, “What is available here?”, the Book's contextual status and operator diagnostics.
2. Include development mode, dynasty, heraldry/documents, Townstead capabilities, Living Villages, Waystones, and current sovereignty ownership alongside the older features.
3. Distinguish enabled, disabled, missing provider, unsupported provider, temporarily unreadable, missing content, read-only records, and player-specific prerequisites.
4. An exception is “could not determine,” not proof of availability. Keep retry and the owning service's authoritative check.
5. Name the missing condition and give a useful next step. Ordinary players should see understandable mod/feature names; operators can expand exact versions, configuration keys and diagnostics.
6. Do not expose private institutions, discoveries or political records while assembling a public feature summary.

**Acceptance:** table-test summaries and task gates from the same capability fixture: no Quests, legacy Quests, missing Crime, Townstead disabled/unreadable, development OFF/OBSERVE, Capitals read-only, and corrupt local records. The summary must never promise an action that its known prerequisites forbid.

### AUD-15 — Finish Townstead's integration into ordinary player tasks

**Evidence:** [TaskMeta.java](src/main/java/dev/otectus/mcakingdoms/interaction/TaskMeta.java), group lines 43–54; [TownsteadTasks.java](src/main/java/dev/otectus/mcakingdoms/interaction/TownsteadTasks.java), qualify lines 73–83; [TownsteadBackend.java](src/main/java/dev/otectus/mcakingdoms/compat/townstead/TownsteadBackend.java), provider registry access.

The townstead prefix falls into HELP. Village life/work/news and government/service inspection are therefore not grouped where their subject suggests. Career checking asks for a raw profession or skill ID, always asks for a tier even though the skill branch ignores it, and reports “Not qualified” when a provider answer may actually be unavailable.

**Implement:**

1. Give these tasks explicit groups: village life/work/news under Places; government/service ownership under Government; career checking under the appropriate personal/guild progression context. Preserve Administration for operator tasks.
2. Provide named searchable choices from the supported provider registries, with translated titles and short descriptions. Keep identifiers available only in expanded technical detail.
3. Make fields depend on the selected requirement kind. A learned-skill check must not require an irrelevant tier.
4. Distinguish qualified, not yet qualified, and cannot verify. Explain what is missing and avoid suggesting the player failed a requirement that could not be read.
5. Link relevant career requirements directly from guild/workshop status so players do not have to construct the predicate themselves.

**Acceptance:** a player with no knowledge of resource IDs can inspect a village and check a profession/skill. Missing provider data yields an honest unavailable result. All paths work in the three shipped languages, by keyboard, and with narration.

### AUD-16 — Clear server-specific pinned-task status on disconnect

**Evidence:** [PinnedTask.java](src/main/java/dev/otectus/mcakingdoms/client/PinnedTask.java), static reason and catalogue/toggle/render; [ClientPresentationState.java](src/main/java/dev/otectus/mcakingdoms/client/ClientPresentationState.java), logout cleanup.

The pinned task ID is an intentional local preference. Its last server-provided unavailability reason is also static, but is not cleared with the other server-specific state. Joining a different server can show the previous server's reason before a new catalogue arrives.

**Implement:** retain the local pin preference, clear its reason to unknown on disconnect/world teardown, and refresh from the new authorized catalogue. Do not treat unknown as confirmed unavailable.

**Acceptance:** pin a disabled task on server A, join server B where it is available, and inspect the HUD before and after opening tasks. No A-specific status may appear in B.

### AUD-17 — Correct contradictory current guidance

**Evidence:** [politics usage](docs/politics/usage.md), line 52; [Capacity and maintenance](docs/Capacity-and-Maintenance.md); [MODMAP.md](MODMAP.md); [0.3.0 testing notes](docs/testing/0.3.0/README.md); [compatibility certification](docs/compatibility-certified.md).

The political usage page still broadly says automatic hereditary selection is unimplemented, despite the dynasty subsystem. Maintenance documentation first says nothing is deleted to make room, then documents automatic pruning. Some retention descriptions promise replay refusal that the development implementation does not enforce. MODMAP's historical branch/revision narrative is not the current tree, and its generated mixin description needs checking against the actual mixin configuration. Historical companion blockers can be mistaken for current compatibility status.

**Implement:**

1. Update current user/operator guidance as each affected fix lands. Explain opt-in dynastic behavior precisely; do not imply MCA genealogy, names or marriage are rewritten.
2. Make maintenance descriptions match AUD-07's final semantics, including the difference between disposable history, persistent effects and replay evidence.
3. Regenerate generated maps with their tool if needed; do not hand-edit an auto-managed section. Remove or clearly date stale current-state prose.
4. Preserve historical test records as history. Link to the latest tested version tuple and mark superseded blockers.
5. Distinguish shipped/public companion contracts from locally built contracts. Paid commissions must remain honestly gated until the required public Quests/Crime tuple exists and is tested.
6. Apply changed explanations to en_us, es_es and zh_cn guides, task help, and tooltips. Structural key parity alone is insufficient.

**Acceptance:** current README, guide, configuration, compatibility and maintenance pages describe one consistent behavior. No statement presents a proposed check or local-only provider API as a delivered public capability.

### AUD-18 — Make the GameTest launcher honor its actual run directory

**Evidence:** [run_gametests.py](tools/test/run_gametests.py), lines 10–16.

The script always renames root/run/world before forwarding arguments to Gradle. With a custom -PrunDir, it can move an unrelated existing world while leaving the actual target directory's reused world untouched.

**Implement:**

1. Resolve the effective run directory before any world operation, using the same semantics as the Gradle property. Prefer a fresh explicit temporary directory for automated runs.
2. Never move a different directory's world. Preserve an existing target world with a collision-safe backup name, or choose a new isolated target instead.
3. Print the resolved run directory and evidence/log locations. Keep the existing completed-test-count and pass-marker checks.
4. Avoid hard-coded local EULA assumptions; reuse the existing environment helper and the user's established acceptance.

**Acceptance:** exercise default and custom run directories, a path containing spaces, an existing unrelated run/world, and an existing target world. Only the intended test location may change. A startup-only/zero-test run must still fail validation.

## 5. WAY-01 — Settlement-based Waystones names

### 5.1 Required player behavior

When a normal Waystone first needs a default name and is inside a recognized MCA: Kingdoms settlement, use that settlement's display name. The player should not have to know an identifier, issue a command, or rename each new stone manually.

Use these initial product decisions:

| Situation | Required behavior |
| --- | --- |
| Newly activated generated normal Waystone in a recognized settlement | Default to the settlement name before the activation message/list is shown. |
| Newly placed, unnamed normal Waystone in a recognized settlement | Default to the settlement name before the native settings screen opens; the player can immediately replace it. |
| Stone outside a settlement | Keep Waystones' native behavior. Do not invent a nearest-settlement association. |
| Two stones that would receive the same name | Keep the first assignment and use a stable human-readable suffix, e.g. “Oakfield (2)”. |
| Already named stone, including one picked up and placed again | Preserve its name. Appearance of a generated-looking name is not evidence that it is safe to overwrite. |
| A player edits an automatically named stone | The player's choice wins permanently until they explicitly request settlement naming again. |
| Settlement detected shortly after the stone's naming/placement event | Complete a bounded pending naming operation only if there is positive evidence it is still eligible; never overwrite an intervening edit. |
| Settlement renamed or merged after initial naming | Default behavior is one-time naming. Do not silently rename established stones. An explicitly enabled follow mode, if added, must use the ownership rules below. |
| Waystones absent or unsupported | Core Kingdoms loads normally; status explains that integration is unavailable. |
| Sharestone, Warp Plate, or another non-normal stone type | Leave unchanged in the initial implementation. |

Naming is a convenience, not a new travel/discovery system. Preserve stone UUID, owner, global status, activation state, permissions, teleport costs, cooldowns, and native menus.

### 5.2 Verified provider contract

The actual local pack contains:

- `waystones-forge-1.20.1-14.1.21.jar`, SHA-256 `fd6fe3c645328ea7be5bc6723d02c881e34650e0cbd4527ba59e42fa4cbe3c80`.
- `balm-forge-1.20.1-7.3.44.jar`, SHA-256 `ad11d968770bb1ce73377626b6f1dcd4ae6f8be1ef6f37cee9b872b8176aebd3`.

They were inspected in `/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods`. That absolute path is audit evidence, not a path to hard-code into the build or runtime.

The matching upstream Waystones commit is `0c2160e6cb0cdf9b79978f335821496ab385595d`, “Set version to 14.1.21.” Use these pinned primary sources:

| Source | Verified implication |
| --- | --- |
| [GenerateWaystoneNameEvent](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/api/GenerateWaystoneNameEvent.java) | A Balm event exposes getWaystone, getName and setName. This is the normal automatic-name extension point. |
| [NameGenerator](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/worldgen/namegen/NameGenerator.java) | Native duplicate resolution happens **before** the name event. Substituting a settlement name must perform its own collision handling. |
| [PlayerWaystoneManager](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/core/PlayerWaystoneManager.java) | Generated unnamed stones are named during activation. Player-placed stones do not use that generated-name branch. The manager marks data dirty, records activation, then fires WaystoneActivatedEvent. |
| [WaystoneActivatedEvent](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/api/WaystoneActivatedEvent.java) | Exposes the player and stone. Use its server-side path to handle a newly placed, still-unnamed normal stone. |
| [WaystoneBlockBase.setPlacedBy](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/block/WaystoneBlockBase.java) | Initializes/restores the stone, activates it, synchronizes activated stones, then opens settings. Naming in the activation event can therefore precede the placement settings screen. Preserve the restore-from-existing-UUID path. |
| [WaystonesAPI](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/api/WaystonesAPI.java) | Provides lookup by position/UUID and streams of registered stones. It does **not** expose a complete public rename-and-persist-and-synchronize operation. |
| [Waystone](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/core/Waystone.java) and [WaystoneProxy](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/core/WaystoneProxy.java) | setName alone changes a value; it does not establish the complete persistence/synchronization contract for an arbitrary later rename. |
| [EditWaystoneMessage](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/network/message/EditWaystoneMessage.java) | Native user editing checks authority/distance, legalizes the name, changes the backing record, marks WaystoneManager dirty and sends a native update. Its decoded name limit is 255 characters under the packet's string semantics. |
| [WaystoneManager](https://github.com/TwelveIterations/Waystones/blob/0c2160e6cb0cdf9b79978f335821496ab385595d/common/src/main/java/net/blay09/mods/waystones/core/WaystoneManager.java) | Owns the saved record and dirty state. Use a narrowly isolated, version-checked adapter if later updates need this non-public-API contract. |

Balm 7.3.44 exposes event registration through `BalmEvents.onEvent(Class, Consumer)`, with a priority overload. Register using the actual supported event API. Do not copy a tutorial written for a different Minecraft/Waystones generation or assume a fabricated WaystoneCreatedEvent exists.

### 5.3 Implementation structure

Add an isolated `compat/waystones` integration with these responsibilities; names can follow existing repository conventions:

1. **Binding/capability:** detect installed versions, verify required methods, expose absent/ready/unsupported/read-only status, and load provider-dependent classes only after the check. Do not leak Waystones/Balm types into the public Kingdoms API or common always-loaded signatures.
2. **Naming policy:** a provider-independent pure component that accepts settlement display name, stone identity, reserved/current names, and limits; returns a proposed name or a reason to retain native behavior.
3. **Server lifecycle:** register listeners once, keep state per server/world, and clear it on shutdown. Reject client-side events. Dedicated-server startup must not load Minecraft client classes.
4. **Native adapter:** initial name event, player-placement activation handling, and—only where needed—checked later mutation with persistence and native synchronization.
5. **Managed-name ledger:** store provenance for pending/managed assignments, canonical settlement ID, stone UUID, dimension/position, last observed native name, assigned name/suffix, operation state, and manual-detachment state. Use the repository's bounded, schema-aware persistence patterns.
6. **Player/operator presentation:** concise status in existing capability/help screens; an authorized “Use settlement name” action for an individual eligible stone if reapplication is offered. Reuse native editing permissions and normal task review conventions.

Update build/test companion metadata and optional dependency declarations as required. Do not make Waystones mandatory, bundle either provider, or declare an untested unlimited supported version range.

### 5.4 Association and collision rules

- Resolve membership from the authoritative settlement service at the stone's dimension and position. Follow canonical merge redirects. Use its existing overlap-resolution rule; do not invent another nearest-center heuristic.
- The current settlement bounds are horizontal. Respect that contract and test vertically separated sites. If vertical membership must later change, treat it as a settlement-model change rather than a private Waystones guess.
- Do not create a settlement, change a settlement name, alter a kingdom, or force-load a chunk just to name a stone.
- The base is the settlement's display name, without a mandatory kingdom prefix. User-authored names remain literal; localize explanatory labels, not the authoritative proper name differently per client.
- Build a bounded/cacheable index of registered native names, without generating/loading chunks. Include existing manually named stones when avoiding confusing collisions.
- Allocate suffixes deterministically at first assignment and retain them across restart. A second assignment must not renumber the first. Handle equal settlement names in different dimensions.
- Apply the native string constraints, control-character policy, and any reserved-name rules. Truncate safely without splitting Unicode pairs and reserve room for the suffix. An unusable settlement name should leave the native default intact and produce a diagnostic.
- Serialize simultaneous assignments on the server thread. Another listener's or player's intervening name change must not be overwritten by stale pending state.

### 5.5 Timing, manual edits, and recovery

**Generated stones:** intercept GenerateWaystoneNameEvent, verify normal type and server context, and supply the final collision-safe name. Record that this integration actually supplied it. A stone merely having a random-looking name is not eligible evidence.

**Player placement:** handle the server-side WaystoneActivatedEvent only when the actual normal stone is still unnamed and eligible. The verified placement flow fires it before the settings screen. Preserve named stones restored from items and explicit choices made in that screen. Test this ordering with the packaged provider rather than only a fake event.

**Late discovery:** retain a small pending record when initial settlement lookup is not ready. Retry on relevant settlement creation/reconciliation and with a bounded timeout. Request ordinary loaded-area discovery through the established queue if needed. Do not loop over every stone every tick.

A delayed rename needs stronger evidence than “its current name equals the old automatic name.” A player may have explicitly submitted that same name. Track accepted native manual edits for pending/managed stones. If the pinned provider exposes no suitable public event, implement a narrowly scoped, optional, version-tested observer at the accepted native edit path; do not intercept arbitrary packets and assume they passed permission checks. Derive any hook descriptors from the installed artifact, and keep the hook disabled when its contract is unsupported.

For later native updates, encapsulate the verified backing-record mutation, manager dirtying and native update delivery. Do not send an edit packet on a player's behalf, change the global flag, activate a stone for anyone, or use a bare setName call as the whole operation.

Persist enough intent to reconcile a crash between Kingdoms provenance and the provider's save. If provenance cannot be trusted after restart, leave the native name intact and expose an unresolved status; the convenience feature must not destroy a player's choice. Repeated reconciliation must not allocate a fresh suffix or apply another rename.

Default to **one-time naming**. Automatic follow-on settlement renames are an optional extension, not required to satisfy the original request. If exposed, require explicit opt-in, positive managed-name provenance, accepted-manual-edit detection, a supported later-update adapter, and a reviewable backfill policy. Never advertise a follow mode that is only implemented by an in-memory setter.

### 5.6 Existing worlds and diagnostics

Existing names are preserved by default. An operator backfill must begin with a dry-run list/count of eligible stones and reasons. Unknown historical names are not automatically eligible. An individual owner can explicitly request a replacement under native permissions.

Track understandable reasons: outside a known settlement, waiting for settlement discovery, player name preserved, non-normal type, unsupported provider, read-only ledger, and collision suffix chosen. Keep raw IDs/version probes in operator detail. Avoid per-tick logs and global chat spam.

Do not reveal private settlement records or add undiscovered destinations to a client's travel list. A stone's name update should follow the provider's expected synchronization path without granting new activation/discovery.

### 5.7 Required Waystones tests

| Scenario | Required evidence |
| --- | --- |
| No Waystones; no Balm | Dedicated server and client load; core tasks remain usable. |
| Exact supported Forge tuple | Generated first activation and new player placement show the settlement name in native messages, menu and destination list. |
| Unsupported tuple or missing expected method | Integration reports unsupported without crashing core loading. |
| Two new stones in one settlement; distinct settlement names that collide after legal truncation | Stable collision-safe names across restart; existing manually chosen names remain unchanged. Also cover duplicate legacy names if admitted by a migration. |
| Delayed discovery | Eligible pending assignment completes once; no force-loading or duplicate settlement creation. |
| Manual edit before pending retry, including submitting the same text | No automatic overwrite. |
| Renamed or silk-touched existing stone | Re-placement preserves identity and name. |
| Merge/rename after initial assignment | Default one-time behavior stays stable; any optional follow mode obeys its explicit policy. |
| Same coordinates in different dimensions, border/overlap cases | Correct authoritative membership. |
| Sharestone/Warp Plate | Unchanged. |
| Crash/restart, read-only naming ledger, full pending queue | No name churn, silent ownership adoption, or unbounded work. |
| Two clients, reconnect, unloaded/reloaded chunk | Native UI converges; names persist; activation/owner/global/cost state is unchanged. |

## 6. UX-01 — Make the mod understandable in normal play

### 6.1 Interpret the feedback carefully

“Very confusing” does not identify a screen, language, server configuration, or player goal. It is a strong reason to test the first-session experience, but not evidence that every player needs a new tutorial book.

The current mod already has a welcome/keybinding message, a start page, recommendations, a searchable Book, task forms, review editing, and recipes. Extend these. Do not build another parallel menu or make ordinary players read a technical compatibility matrix.

Concrete issues to address first are AUD-08, AUD-10, AUD-14, AUD-15 and AUD-16. They make the system's own answers more consistent before adding explanatory prose.

### 6.2 First-session path

Relevant files: [KingdomActionsScreen.java](src/main/java/dev/otectus/mcakingdoms/client/KingdomActionsScreen.java), startPieces around lines 272–286; [Suggestions.java](src/main/java/dev/otectus/mcakingdoms/interaction/Suggestions.java); [ServerFacts.java](src/main/java/dev/otectus/mcakingdoms/interaction/ServerFacts.java); [VillageLedgerScreen.java](src/main/java/dev/otectus/mcakingdoms/client/VillageLedgerScreen.java); the three language versions under [guide assets](src/main/resources/assets/mcakingdoms/guide).

Make the first five to ten minutes support this journey:

1. **Where am I?** Show the recognized settlement name and kingdom, or a specific “not recognized yet” explanation. If detection is pending, say so and give the read/scan action from AUD-11.
2. **What can I do here?** Show two or three contextually available goals with one-sentence outcomes: read the village, inspect village life, find local guild service, or contribute to an existing work.
3. **What do I need?** Selecting a goal shows the actual missing condition: residence, an introduction, a material quantity, a current office, a provider, or a server setting.
4. **What will this change?** Review names the settlement, cost, permission and consequence in ordinary language. Distinguish a contribution from a proposal, a funded milestone from physical construction, and a document from the authority it records.
5. **Did it work, and what next?** Show the actual outcome and one appropriate next step. A running operation has a live status link; a refusal has an actionable reason.

The current start page always pairs “Read this settlement” with “Open its War Room” when a settlement is present. Make that secondary action contextual. In a peaceful/default experience, Village Ledger or Village life is a better general continuation. Keep War Room discoverable for relevant conflict/control tasks and when those features are available.

Do not automatically register residence, found a government, join a guild, opt a settlement into construction, or enable warfare as part of onboarding.

### 6.3 Vocabulary and ownership

Use a small contextual glossary, linked at the point of confusion:

| Term | Meaning the UI should establish |
| --- | --- |
| Settlement | A named place with recognized bounds; the place where the player is standing. |
| Kingdom | The political/cultural association of one or more settlements; not a synonym for a single village. |
| Residence | A recorded relationship to a home/settlement; distinct from holding office or owning land. |
| Guild membership/rank | Organizational progression; distinct from Townstead profession tier and from government permission. |
| Government/office | Who can authorize particular actions. Show the responsibility the action actually needs. |
| Construction permission | Separate server mode, settlement consent, current authority, and plot safety. One “enabled” label cannot represent all four. |
| Charter | A physical reference to a recorded decision; holding it does not grant the underlying office or title. |
| Provider ownership | Which mod controls a particular domain, such as Capitals' crown or Townstead's village life. Give a direct destination for the relevant action. |

Prefer “You need a recorded home here” or “An official with Appoint officials can approve this” over unexplained enumerations and IDs. Keep an expandable operator diagnostic when technical detail helps solve a problem.

### 6.4 Task and status design

- Keep stable task IDs, saved bookmarks and search aliases. Changing visible wording must not break saved navigation.
- Rank recommendations using the player's actual context and capabilities. Do not bury basic local actions beneath unrelated advanced systems; urgent obligations should still be visible.
- Preserve direct search and advanced task access for experienced players. Progressive disclosure must not become removal of functionality.
- Show named material icons/amounts and shortages before a contribution. Revalidate on apply rather than trusting the client.
- Give physical works a compact lifecycle: proposed/surveyed, approved, collecting materials, funded, waiting/paused/building, complete, or needs repair. Explain the current blocker and who can resolve it.
- Make cancellation terms explicit. Current partial construction can consume the entire material contribution once any block is built. If retaining that policy, say so before cancellation. If adding partial refunds, first define an auditable consumed-material allocation and integrate it with AUD-02; do not invent material refunds from block count alone.
- Keep unknown/unreadable distinct from empty/none. This applies to settlement detection, careers, buildings, compatibility and operation status.
- Use dynamic keybinding components. Include a route from the Book/controls screen when the preferred key is unbound or conflicts with the pack.
- Preserve keyboard navigation, sensible focus after Back/Edit, narration order, wrapping, and large GUI scales. Do not convey status with color alone.

### 6.5 Usability acceptance

Run at least five first-time-player sessions, as already contemplated by the repository's testing notes. Include the plain mod and the relevant companion pack. Give participants goals rather than instructions on which button to press:

1. Identify the current settlement and explain how it differs from its kingdom.
2. Find one useful action they can perform now.
3. Explain one unavailable action and what would make it available.
4. Inspect or contribute to a public work and then find its current state.
5. Place/activate a Waystone and understand why it received its name and how to override it.

Record time to first useful action, wrong turns, terms participants misinterpret, and whether they need commands or raw identifiers. Acceptance is that participants can complete the applicable goals and explain the resulting state without developer assistance. If a goal is unavailable on the fixture, they should correctly explain why.

Separately inspect en_us, es_es and zh_cn, small windows and large GUI scales, keyboard-only operation, and audible narration on a machine with a working voice backend. Do not claim usability success from JSON validation or screenshot existence alone.

## 7. VAL-01 — Validation still required

### 7.1 Regression and runtime matrix

| Area | Minimum validation |
| --- | --- |
| Identity and migrations | Existing save fixtures; creation/merge/reassignment; injected partial writes; forced restart; older/newer schema; idempotent reconciliation. |
| Inventory/document effects | Saved player inventory and sidecar boundary tests; full inventory; disconnect/death; unknown items; repeated requests; disabled features. |
| Construction | Multi-block work; current consent/authority changes; claims; protected homes; changed states; unloading; write failure; chunk/sidecar crash ordering. |
| Development economy | More than 512 historical works; receipt expiry; removed/changed template; merge aggregation; capacity/overflow; repeated reconciliation. |
| Interaction | Concurrent unrelated mutations; relevant stale reviews; saved drafts; live CHECK; inaccessible records; capability failures. |
| Discovery/index | POIs added after initial scan; bounded queue under exploration; malformed geometry; dimension fairness; no forced chunk loads. |
| Reloads | Successful and failed reloads; coherent mapping/definition generations; provider registry changes; cache invalidation. |
| Waystones | Section 5.7 using actual packaged Waystones/Balm artifacts. |
| Presentation | Real client actions, multiple GUI scales/languages, narration, key conflicts, reconnect/server switch, native Waystones UI. |

Keep companion tests separate from standalone tests. A fixture that returns success after logging a skip is evidence of test completion, not evidence of the skipped integration behavior.

### 7.2 Packaged delivery checks

After implementation, run the existing build/check and resource-validation workflow with the quiet Gradle wrapper. Use isolated worlds and the corrected GameTest launcher. Build the actual distributable, record its SHA-256, and run the relevant dedicated server and two clients against that artifact.

At minimum test:

- Standalone Kingdoms, without optional companions.
- The exact Waystones 14.1.21 / Balm 7.3.44 tuple.
- The currently supported MCA/Townstead tuple for village lifecycle, career and building evidence.
- Capitals ownership sharing and read-only fallback when those areas changed.
- The actual Quests/Crime tuple for any paid-workshop claims. Distinguish released artifacts from local builds.
- The full target pack for overlapping keybindings, activation/menu ordering, and optional integration interactions.

Do not upgrade or publish companion mods as an incidental step. Where an upstream API is genuinely missing, record the exact external dependency and keep the local feature truthfully gated. Do not substitute guesses or pretend a locally compiled provider is a publicly delivered release.

The existing [0.3.0 testing notes](docs/testing/0.3.0/README.md) explicitly leave new-player sessions, audible narration, two-client dynasty/charter/development runs, a full-pack founding-to-memorial walkthrough, new-block visual profiling, and the development performance matrix unexecuted. Close the portions affected by this implementation and retain honest status for the rest.

### 7.3 Performance

Measure with 10, 50 and 200 settlements, and 1, 10 and 50 active works. Include queued settlement scans, a substantial native Waystones registry, partially unloaded construction, and two clients interacting while maintenance runs.

Record tick-time distributions, worst observed maintenance duration, queue size/age, file sizes, recovery duration and relevant memory use. Verify no unbounded full-registry scan is added to every tick or naming request. Use fair cursors where bounded processing would otherwise repeatedly favor the same records.

Set concrete budgets from the existing configuration and a measured baseline. Do not invent impressive performance numbers or label a matrix passed without running it.

### 7.4 Deliverable evidence

For each completed work item, provide:

- The relevant change and migration/schema summary.
- The regression test or reproducible scenario demonstrating the previous failure and corrected result.
- Exact commands, result counts, skipped conditions, log/artifact paths and companion versions/hashes.
- A small set of screenshots or recorded interaction evidence for changed client flows, plus actual usability observations where required.
- Remaining external blockers or unexecuted checks, without marking the whole feature complete prematurely.

Do not run a broad coverage campaign unrelated to these findings. Add tests that protect the corrected behavior and complete existing required acceptance work.

## 8. Implementation order and completion criteria

### Phase A — Durability and safe recovery

Implement AUD-01, AUD-02 and AUD-03 first, then AUD-04/AUD-05 and AUD-09 on those recovery primitives. Keep root identity, inventory effects and world effects as explicit separate transaction domains with defined recovery; one generic “atomic save” helper cannot make all three atomic.

Complete AUD-08 alongside these changes so disabling a feature never blocks the new recovery paths. A useful independent early change is AUD-18, which makes subsequent validation safer.

**Exit:** forced-failure and restart tests conserve items, preserve player blocks, and recover canonical identity without false success.

### Phase B — Lifecycle completeness

Implement AUD-06 and AUD-07 with migration fixtures; AUD-11/AUD-12 for discovery and bounds; AUD-13 for reload coherence. Make retirement, retention and canonical identity agree before using settlement lifecycle events for more integrations.

**Exit:** old saves, merges, mode changes, retention and failed reloads preserve authoritative state and remain bounded.

### Phase C — Requested integration and coherent interaction

Implement WAY-01 against the verified provider tuple. Implement AUD-10, AUD-14, AUD-15 and AUD-16, then the first-session changes in UX-01. Shared capability facts should exist before finalizing new Waystones status/help.

**Exit:** generated and player-placed eligible stones get sensible defaults; player names survive; reviews and status remain usable during normal server activity.

### Phase D — Documentation and delivery

Update AUD-17's current guidance as behavior settles, complete VAL-01's affected acceptance matrix, and run the real first-time-player sessions.

**Exit:** the distributable has evidence for its advertised behavior, changed workflows are understandable, and public compatibility claims match actual released/tested providers.

### Boundaries and deferred design questions

- Do not automatically make optional Townstead career prerequisites mandatory when Townstead is absent or unreadable. The current integration deliberately treats some requirements as not applied in that situation. Explain that policy; change it only through an explicit schema/product decision.
- Do not implement taxes, simulated caravans, wholesale economy replacement, genetics, a new claims system, or a loader port to address these reports.
- Other finite ledgers without supported compaction remain a long-world maintenance concern. Preserve their bounds and evidence. A future archival feature needs a reference graph, durable archive/tombstone design and preview/recovery tooling; arbitrary deletion or larger constants are not a complete fix.
- Do not add follow-on automatic Waystone renaming merely because initial default naming is useful. One-time default naming satisfies the request; managed follow mode has a separate manual-override and persistence contract.
- Do not interpret every provider exception as a security exploit. Report the actual effect: unavailable evidence, misleading status, an unsafe fallback, or a refused operation.

### Definition of done

The work is complete when the confirmed defects have scoped fixes with meaningful regression evidence; the required default Waystones naming works on both generated and newly placed stones; the first-session flow and ongoing work status have been exercised by real players; save/restart/merge/disable behavior is recoverable; and documentation/compatibility statements match the tested artifact.

If an external capability or validation environment prevents a required acceptance check, leave that item explicitly open with its exact reason and next action. Do not relabel a proposal, a successful compile, or a skipped scenario as full implementation.
